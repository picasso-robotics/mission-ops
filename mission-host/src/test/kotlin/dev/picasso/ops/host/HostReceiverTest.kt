package dev.picasso.ops.host

import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.TIMING
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.awaitHeartbeatExpired
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.confirm
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.driveAll
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.evidence
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.executionOf
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.units
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.Readiness
import dev.picasso.ops.host.lease.WaitReason
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 실제 수신자(picasso `adapter-host`, `FENCED` + 파일 바닥값, 정리 대기 1초)로 보는 기체 준비(HA 3단계, picasso ADR 54). quadruped-01 은 시험 안
 * `AdapterHost` 뒤의 시험 어댑터([ScriptedAdapter])에, humanoid-01 은 대조하지 않는 mimic 에 있다(이 시험의 실행은 quadruped-01 만 쓴다). 실행 호스트는
 * 운영과 같은 배선([MissionHostApplication.clientPort] → [GatedRobotPort] → [HostRobotPort])이다.
 *
 * mimic 기준 시험([HostEpochTakeoverTest])과 다른 점은 수신자가 «결과 모름» 을 실제로 만든다는 것이다 — 앞 세대로 통과한 명령이 닫히기를 정리 대기만큼
 * 기다리고, 결과를 모르는 접수·조작이 남으면 `DURABLE_PENDING` 으로 답한다. 수신자를 다시 띄우면 그 미확정이 지워진다(알려진 한계, picasso 한계 대장
 * «§15.330 · 미확정 접수» 뒷면) — 그 거동은 특성 시험으로 지금 모양을 고정한다.
 *
 * 가상 시계는 mimic 의 것을 수신자도 쓴다. «결과가 남았다» 는 곧바로 단언하지 않고 기다린다(`eventually`·[driveAll]).
 */
class HostReceiverTest {

    /** 수신자·시험 어댑터·시험 세트 하나. [dropStarts] 가 참인 동안 quadruped-01 의 출발은 수신자에 닿지 않는다(보내는 쪽이 결과를 모름). */
    private class World(durable: Boolean = true, nextDir: (Path) -> Path = { it }) : AutoCloseable {
        @Volatile private var clockOf: HostBench? = null
        /** 세트의 가상 시계. 세트가 서기 전(수신자 기동의 생존 보고)에는 기원 시각이다. */
        private fun now(): Instant = clockOf?.now() ?: Instant.EPOCH

        val adapter = ScriptedAdapter(::now)
        val receiver = Receiver(mapOf(QUADRUPED to (PROFILE to adapter)), ::now, durable = durable, nextDir = nextDir)

        @Volatile var dropStarts = false

        val bench = HostBench(lease = TIMING, wiring = { b, leadership, clock -> port(b, leadership, clock) })

        private fun port(b: HostBench, leadership: HostLeadership, clock: HostClock): RobotPort {
            clockOf = b
            val humanoid = MissionHostApplication.clientPort(b.channel(), leadership::controllerEpoch, leadership::superseded)
            val quadruped = MissionHostApplication.clientPort(receiver.channel(), leadership::controllerEpoch, leadership::superseded)
            val reaching = object : RobotPort by quadruped {
                override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
                    if (dropStarts) throw IllegalStateException("수신자에 닿지 않았다(시험)") else quadruped.start(robotId, taskId, revision, skillType, parameters)
            }
            return HostRobotPort(GatedRobotPort(RoutedRobotPort(mapOf(HUMANOID to humanoid, QUADRUPED to reaching)), leadership), clock)
        }

        /** 수신자에 세대 [epoch] 로 곧바로 묻는다(그 자체가 같은 세대의 인수 확인이다). */
        fun read(epoch: Long): RobotSnapshot =
            checkNotNull(MissionHostApplication.clientPort(receiver.channel(), { epoch }, { _, _, _ -> }).snapshot(QUADRUPED)) { "수신자를 못 읽었다" }

        /** JO-Q(InspectAsset, 대상 bay-7·dock-3)를 quadruped-01 에 내고 첫 점검(T1)이 RUNNING 일 때까지 민다. T1 은 [finishT1] 까지 끝나지 않는다. */
        fun submit(): String {
            adapter.durations["inspect"] = Duration.ofHours(1)
            val accepted = bench.post("/host/job-orders", request(inspect("JO-Q", "T1" to "bay-7", "T2" to "dock-3"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val execution = executionOf(bench, "JO-Q")
            bench.driveAll { units(bench, execution)["T1"] == "RUNNING" }
            bench.idlePumps()
            return execution
        }

        /** 첫 점검을 끝낸다(다음 단위 T2.travel 의 출발 차례가 선다). 뒤 점검도 4초로 끝난다. */
        fun finishT1() {
            adapter.durations["inspect"] = Duration.ofSeconds(4)
        }

        /** 만료 인수 — 첫째를 죽이고 둘째가 관측(HOLDING)에 들 때까지. 첫째의 인스턴스 id. */
        fun takeover(beforeStart: () -> Unit = {}): String {
            val first = bench.leadership.instanceId
            bench.killHost(beforeStart)
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            return first
        }

        fun readiness(): Readiness = bench.leadership.readinessOf(QUADRUPED)
        fun reason(): WaitReason? = (readiness() as? Readiness.Waiting)?.reason

        /** [what] 이 참이 될 때까지 기다린다(실제 시간 상한 [within]). 가상 시계는 밀지 않는다. */
        fun await(what: String, within: Duration = Duration.ofSeconds(20), condition: () -> Boolean) {
            val deadline = System.nanoTime() + within.toNanos()
            while (!condition()) {
                check(System.nanoTime() < deadline) { "${within.seconds}초 안에 이르지 않았다: $what (${bench.get("/host/leader")})" }
                Thread.sleep(50)
            }
        }

        override fun close() {
            try {
                bench.close()
            } finally {
                receiver.close()
            }
        }
    }

    @Test
    fun `래치 뒤 이 세대 출발이 결과 모름이 되어 수신자가 같은 바닥값 DURABLE_PENDING 으로 답해도 래치와 근거가 그대로이고 조회는 관문을 지나 수신자에 닿되 벤더에는 다시 가지 않으며 읽기 실패도 풀지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val second = run { w.takeover(); w.bench.leadership.instanceId }
            w.await("수신자 확인으로 열림") { w.readiness() is Readiness.Ready }
            assertEquals(listOf("$QUADRUPED|2|FENCE_STATUS_DURABLE_CONFIRMED|$second"), evidence())

            // 관측 중 이 세대가 낸 다음 출발(T2.travel)이 로봇에 닿았으나 답을 잃는다.
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && w.receiver.startRpcsOf(travel) >= 2 }
            // 조회(같은 키 재전송)는 송신 직전 확인(기체 단위)을 지나 수신자에 닿는다. 수신자는 벤더에 다시 보내지 않는다.
            w.bench.driveAll { w.receiver.startRpcsOf(travel) >= 3 }
            assertEquals(1, w.adapter.acceptsOf(travel), "결과를 모르는 접수를 벤더에 다시 보냈다")
            assertEquals(1, w.adapter.effectsOf(travel))

            val pending = w.read(2)
            assertEquals(2L to FenceStatus.FENCE_STATUS_DURABLE_PENDING, pending.controllerFloor to pending.fenceStatus)
            assertIs<Readiness.Ready>(w.readiness(), "자기 세대의 미확정으로 래치를 풀었다")
            assertEquals(1, evidence().size)
            assertEquals(LeaderState.HOLDING, w.bench.leadership.state)

            // 그 뒤 읽기가 실패해도(수신자가 GetSnapshot 을 UNAVAILABLE 로 끝냄) 래치는 그대로다.
            w.receiver.failSnapshots = true
            val failedFrom = w.receiver.snapshotRpcs.get()
            repeat(4) {
                w.bench.advance(Duration.ofSeconds(1))
                w.bench.idlePumps(2)
            }
            assertTrue(w.receiver.snapshotRpcs.get() > failedFrom, "읽기 실패 구간에 읽기가 없었다")
            assertIs<Readiness.Ready>(w.readiness(), "읽기 실패로 래치를 풀었다")
            w.receiver.failSnapshots = false
            assertEquals(1, evidence().size)
            assertTrue(units(w.bench, execution)["T2.travel"] in setOf("IN_DOUBT", "OPERATOR_HOLD"), units(w.bench, execution).toString())
            assertEquals(1, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `래치 뒤 이 세대 취소가 결과 모름이 되어 DURABLE_PENDING 이어도 래치가 그대로이고 읽기 실패도 풀지 않는다`() {
        World().use { w ->
            w.submit()
            w.takeover()
            w.await("수신자 확인으로 열림") { w.readiness() is Readiness.Ready }
            assertEquals(1, evidence().size)

            // 이 세대(2)로 도는 점검(T1)의 취소를 낸다 — 벤더에 닿았는지 모른다(조작 미확정). 출하 어댑터처럼 벤더 관측을 말하지 않아 걷히지 않는다.
            w.adapter.cancelScript += ScriptedAdapter.Answer.IN_DOUBT
            val port = MissionHostApplication.clientPort(w.receiver.channel(), { 2L }, { _, _, _ -> })
            runCatching { port.cancel(QUADRUPED, TaskHandle.newBuilder().setTaskId("JO-Q#T1").setRevision(1).setRobotId(QUADRUPED).build()) }
            assertEquals(1, w.adapter.cancels.get())
            val pending = w.read(2)
            assertEquals(2L to FenceStatus.FENCE_STATUS_DURABLE_PENDING, pending.controllerFloor to pending.fenceStatus)
            repeat(3) {
                w.bench.advance(Duration.ofSeconds(1))
                w.bench.idlePumps(2)
            }
            assertIs<Readiness.Ready>(w.readiness(), "자기 세대의 조작 미확정으로 래치를 풀었다")

            w.receiver.failSnapshots = true
            repeat(3) {
                w.bench.advance(Duration.ofSeconds(1))
                w.bench.idlePumps(2)
            }
            assertIs<Readiness.Ready>(w.readiness(), "읽기 실패로 래치를 풀었다")
            w.receiver.failSnapshots = false
            assertEquals(1, evidence().size)
            assertEquals(1, w.adapter.cancels.get(), "결과를 모르는 취소를 벤더에 다시 보냈다")
        }
    }

    @Test
    fun `앞 세대 접수가 벤더 호출 안에 걸린 채 인수하면 수신자가 정리 대기 뒤 DURABLE_PENDING 으로 답해 열지 않고 그 접수가 끝나면 자동으로 열려 같은 키를 벤더에 한 번만 보낸 채 이어 간다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            val gate = CountDownLatch(1)
            w.adapter.holdNext.set(gate)
            w.finishT1()
            w.bench.driveAll { w.adapter.held.get() == 1 }

            // 첫째의 T2.travel 접수가 벤더 호출 안에 걸린 채(수신자 입구를 세대 1 로 지남) 첫째를 죽인다.
            w.takeover()
            w.await("앞 세대 명령 정리 미확정으로 기다림") { w.reason() == WaitReason.DURABLE_PENDING }
            repeat(3) {
                w.bench.advance(Duration.ofSeconds(1))
                w.bench.idlePumps(2)
            }
            assertEquals(WaitReason.DURABLE_PENDING, w.reason())
            assertEquals(emptyList(), evidence())
            assertEquals(1, w.adapter.acceptsOf(travel))

            // 걸린 접수가 받아들여져 끝난다 — 수신자 장부에 오르고 앞 세대 명령이 닫힌다. 다음 확인부터 DURABLE_CONFIRMED 다.
            val released = System.nanoTime()
            gate.countDown()
            w.await("해소 뒤 자동 열림") { w.readiness() is Readiness.Ready }
            val openedMs = (System.nanoTime() - released) / 1_000_000
            println("[HostReceiverTest] (b1) 해소 → 래치 ${openedMs} ms")
            assertEquals(1, evidence().size)
            assertTrue(evidence().single().contains("|FENCE_STATUS_DURABLE_CONFIRMED|"), evidence().toString())

            w.bench.driveAll { units(w.bench, execution)["T2.travel"] !in setOf("PENDING", "IN_DOUBT") }
            w.bench.driveAll { units(w.bench, execution)["T2"] != "PENDING" }
            assertEquals(1, w.adapter.acceptsOf(travel), "걸렸던 접수와 같은 키를 벤더에 다시 보냈다")
            assertEquals(1, w.adapter.effectsOf(travel))
            assertEquals(LeaderState.HOLDING, w.bench.leadership.state)
        }
    }

    @Test
    fun `앞 세대 접수가 결과 모름으로 끝나면 수신자는 계속 DURABLE_PENDING 이고 운영자 확인으로만 열리며 확인 뒤에도 수신자는 DURABLE_PENDING 이고 벤더에 다시 가지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            val gate = CountDownLatch(1)
            w.adapter.holdNext.set(gate)
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.held.get() == 1 }

            val first = w.takeover()
            val second = w.bench.leadership.instanceId
            w.await("앞 세대 명령 정리 미확정으로 기다림") { w.reason() == WaitReason.DURABLE_PENDING }
            gate.countDown()
            w.await("걸린 접수가 결과 모름으로 끝남") { w.adapter.effectsOf(travel) == 1 }
            repeat(6) {
                w.bench.advance(Duration.ofSeconds(5))
                w.bench.idlePumps(2)
            }
            assertEquals(WaitReason.DURABLE_PENDING, w.reason(), "앞 세대 미확정이 남은 기체를 열었다")
            assertEquals(emptyList(), evidence())

            awaitHeartbeatExpired(first)
            val confirmed = confirm(w.bench, second, 2, previous = first, unresolved = listOf(first))
            assertEquals(200, confirmed.status, confirmed.body.toString())
            w.bench.driveAll { w.receiver.startRpcsOf(travel) >= 2 }
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] in setOf("IN_DOUBT", "OPERATOR_HOLD") }
            assertEquals(1, w.adapter.acceptsOf(travel), "결과를 모르는 접수를 운영자 확인 뒤 벤더에 다시 보냈다")
            assertEquals(1, w.adapter.effectsOf(travel))
            val after = w.read(2)
            assertEquals(2L to FenceStatus.FENCE_STATUS_DURABLE_PENDING, after.controllerFloor to after.fenceStatus)
            assertEquals(LeaderState.ISSUING, w.bench.leadership.state)
        }
    }

    @Test
    fun `알려진 한계(§15_330) — 복원 전에 수신자가 다시 뜨면 미확정이 지워져 DURABLE_CONFIRMED 로 자동 열리고 같은 키가 벤더에 다시 닿는다 · 별도 PR 에서 뒤집음`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }

            // 첫째를 죽이고 둘째가 뜨기 전에 수신자만 다시 띄운다(같은 포트·같은 바닥값 디렉터리). 메모리의 미확정 접수가 사라진다.
            w.takeover { w.receiver.restart() }
            w.await("수신자 재시작 뒤 자동 열림(지금 거동)") { w.readiness() is Readiness.Ready }
            assertTrue(evidence().single().startsWith("$QUADRUPED|2|FENCE_STATUS_DURABLE_CONFIRMED|"), evidence().toString())
            // 로봇은 같은 접수를 두 번 받았다(첫째의 결과 모름 + 둘째의 재전송) — 효과가 두 번 기록된다. 고정물은 접수 수를 먼저 올리고 효과를 뒤에 적으므로 효과를 기다린다.
            w.bench.driveAll { w.adapter.effectsOf(travel) == 2 }
            assertEquals(2, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `알려진 한계(§15_330) — 복원된 실행을 관측하는 중 수신자가 다시 뜨면 파일 바닥값으로 옛 능동은 거절하나 DURABLE_PENDING 이 DURABLE_CONFIRMED 로 바뀌어 자동 열리고 같은 키가 벤더에 다시 닿는다 · 별도 PR 에서 뒤집음`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }

            w.takeover()
            w.await("앞 세대 미확정으로 기다림") { w.reason() == WaitReason.DURABLE_PENDING }
            repeat(3) {
                w.bench.advance(Duration.ofSeconds(1))
                w.bench.idlePumps(2)
            }
            assertEquals(WaitReason.DURABLE_PENDING, w.reason())
            assertEquals(1, w.adapter.acceptsOf(travel))
            // 복원은 미확정(IN_DOUBT)을 저장하지 않는다 — 둘째는 그 단위를 PENDING 으로 다시 짓고 «출발» 로 막혀 있다(조회 재전송이 아니다).
            assertEquals("PENDING", units(w.bench, execution)["T2.travel"])
            assertEquals("START", w.bench.execution(execution)!!["permitBlock"]["withheld"].asText())

            // 다시 뜬 수신자에 둘째의 읽기가 닿기 전에 본다 — 읽기를 막아 둔 채 다시 띄운다. 파일 바닥값(2)이 남아 옛 능동(세대 1)의 명령은 거절된다.
            w.receiver.failSnapshots = true
            w.receiver.restart()
            assertEquals(2L, w.receiver.host.fence.floor(QUADRUPED), "다시 뜬 수신자가 파일 바닥값을 되찾지 못했다")
            val late = MissionHostApplication.clientPort(w.receiver.channel(), { 1L }, { _, _, _ -> })
            val reply = late.start(QUADRUPED, "JO-Z#T1.travel", 1, "navigate_to", emptyMap())
            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, reply.rejection.code)
            assertEquals(1L to 2L, reply.rejection.requestedControllerEpoch to reply.rejection.controllerEpochFloor)
            assertEquals(0, w.adapter.acceptsOf("JO-Z#T1.travel"))
            w.receiver.failSnapshots = false
            w.await("수신자 재시작 뒤 자동 열림(지금 거동)") { w.readiness() is Readiness.Ready }
            assertTrue(evidence().single().startsWith("$QUADRUPED|2|FENCE_STATUS_DURABLE_CONFIRMED|"), evidence().toString())
            w.bench.driveAll { w.adapter.effectsOf(travel) == 2 }
            assertEquals(2, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `앞 세대 출발이 수신자에 아예 닿지 않은 채 인수하면 수신자는 DURABLE_CONFIRMED 로 답해 자동으로 열리고 재전송이 벤더에 한 번 닿아 이어 간다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            assertEquals(0, w.receiver.startRpcsOf(travel))

            w.takeover { w.dropStarts = false }
            w.await("자동 열림") { w.readiness() is Readiness.Ready }
            w.bench.driveAll { units(w.bench, execution)["T2"] != "PENDING" }
            assertEquals(1, w.adapter.acceptsOf(travel))
            assertEquals(1, w.adapter.effectsOf(travel))
        }
    }

    @Test
    fun `만료 뒤 깨어난 옛 능동이 낸 출발은 실제 수신자가 STALE_CONTROLLER 로 거절하고 벤더에 닿지 않으며 옛 능동은 그 거절로 밀려나 잃는다`() {
        World().use { w ->
            w.submit()
            val old = w.bench.leadership
            val standby = w.bench.startHost()
            val next = w.bench.leadershipOf(standby)
            try {
                old.stall.close()
                w.bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10), next)
                w.await("새 보유자가 quadruped-01 을 연다") { next.readinessOf(QUADRUPED) is Readiness.Ready }
                assertEquals(LeaderState.ISSUING, old.state)
                assertEquals(1L, old.controllerEpoch())
                // 멎은 옛 능동(세대 1)의 송신 직전 확인을 지난 출발 하나가 실제 수신자로 곧바로 나간다. 밀려남은 운영 배선과 같은 연결(old::superseded)이다.
                val late = MissionHostApplication.clientPort(w.receiver.channel(), old::controllerEpoch, old::superseded)
                val reply = late.start(QUADRUPED, "JO-Q#T2.travel", 1, "navigate_to", emptyMap())
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, reply.rejection.code, reply.toString())
                assertEquals(1L to 2L, reply.rejection.requestedControllerEpoch to reply.rejection.controllerEpochFloor)
                assertEquals(0, w.adapter.acceptsOf("JO-Q#T2.travel"), "거절된 출발이 벤더에 닿았다")
                assertEquals(LeaderState.LOST, old.state)
                assertTrue(old.lostReason!!.startsWith("수신자가 더 높은 세대로 넘겨받았다고 알렸다"), old.lostReason)
                assertEquals(LeaderState.HOLDING, next.state)
            } finally {
                old.stall.open()
                standby.close()
            }
        }
    }

    @Test
    fun `바닥값을 메모리에 두는 실제 수신자는 VOLATILE 로 답해 관측 중 열리지 않는다`() {
        World(durable = false).use { w ->
            val execution = w.submit()
            val before = w.adapter.accepts.keys.toSet()
            w.finishT1()
            w.takeover()
            w.await("휘발로 기다림") { w.reason() == WaitReason.VOLATILE }
            repeat(3) {
                w.bench.advance(Duration.ofSeconds(5))
                w.bench.idlePumps(2)
            }
            assertEquals(WaitReason.VOLATILE, w.reason())
            assertEquals(emptyList(), evidence())
            assertEquals(before, w.adapter.accepts.keys.toSet(), "휘발 수신자의 기체에 관측 중 출발을 냈다")
            assertEquals("PENDING", units(w.bench, execution)["T2.travel"])
        }
    }

    private companion object {
        val PROFILE: Path = HostBench.ROOT.resolve("picasso/profile/profiles/quadruped-b.json")

        init {
            check(Files.exists(PROFILE)) { "프로파일이 없다: $PROFILE" }
        }
    }
}
