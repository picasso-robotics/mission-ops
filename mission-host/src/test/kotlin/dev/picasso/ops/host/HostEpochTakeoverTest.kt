package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.adapter.host.FileFenceStore
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FenceStore
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.lease.Readiness
import dev.picasso.ops.host.lease.WaitReason
import dev.picasso.registry.PostgresSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 수신 측 제어자 세대 대조의 소비(HA 3단계, picasso ADR 54). 실제 실행 호스트(Spring 컨텍스트)와 실제 Postgres, 대조하는 mimic(`FENCED`, 바닥값은
 * 운영과 같은 `FileFenceStore` 파일 또는 메모리)으로 본다.
 *
 * - 만료 인수 뒤 관측([LeaderState.HOLDING]) 중, 수신자가 새 세대를 내구 저장·정리 확인한(`DURABLE_CONFIRMED`) 기체의 실행만 다시 출발한다. 전역
 *   발행·발행 표시는 그대로 닫혀 있다.
 * - 대조하지 않는 수신자(바닥값 0)·휘발 저장(`VOLATILE`)·정리 미확정(`DURABLE_PENDING`)은 까닭별로 기다리고 운영자 확인 뒤에 열린다.
 * - 만료 뒤 깨어난 옛 능동이 송신 직전 확인을 지나 낸 명령은 수신자가 거절한다(기체 태스크 이력 불변). 밀려남은 잃음으로 간다.
 * - 운영자 확인은 미해소 원천 보유자 연쇄를 지목해야 한다.
 */
class HostEpochTakeoverTest {

    private val dirs = mutableListOf<Path>()
    private val stores = mutableListOf<FileFenceStore>()

    @AfterTest
    fun cleanup() {
        stores.forEach { runCatching { it.close() } }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    /** 운영과 같은 파일 저장으로 대조하는 수신자. 시험마다 새 디렉터리다(DB 와 같은 수명). */
    private fun fileFence(): ControllerFence {
        val dir = Files.createTempDirectory("fence").also { dirs.add(it) }
        return ControllerFence(FenceMode.FENCED, FileFenceStore(dir).also { stores.add(it) })
    }

    @Test
    fun `만료 인수 뒤 수신자가 새 세대를 확인한 기체의 실행만 관측 중에 다시 출발하고 대조하지 않는 기체는 까닭을 보이며 운영자 확인 뒤에 열린다`() {
        // humanoid-01 은 대조하지 않는 mimic, quadruped-01 은 대조하는(파일 저장) 둘째 mimic 에 있다.
        HostBench(lease = TIMING, split = fileFence()).use { bench ->
            submitBoth(bench)
            val first = bench.host.instanceId
            bench.killHost()
            val second = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            val before = bench.tasks(HUMANOID)

            // quadruped-01 은 지금 읽은 응답(바닥값 2, DURABLE_CONFIRMED)으로 자동으로 열리고, humanoid-01 은 바닥값 0 이라 기다린다.
            bench.eventually("quadruped-01 준비") { bench.leadership.readinessOf(QUADRUPED) is Readiness.Ready }
            val leader = bench.get("/host/leader")
            val robots = leader["readiness"]["robots"].associateBy { it["robotId"].asText() }
            assertEquals(listOf("true", "AUTO", "2", "FENCE_STATUS_DURABLE_CONFIRMED"), listOf("ready", "basis", "floor", "fenceStatus").map { robots.getValue(QUADRUPED)[it].asText() }, leader.toString())
            assertEquals(listOf("false", "UNFENCED", WaitReason.UNFENCED.text, "0"), listOf("ready", "reasonCode", "reason", "floor").map { robots.getValue(HUMANOID)[it].asText() }, leader.toString())
            assertEquals(listOf("$QUADRUPED|2|FENCE_STATUS_DURABLE_CONFIRMED|$second"), evidence())

            // 관측 중: quadruped-01 의 실행은 다음 단위를 내고 humanoid-01 의 실행은 그 자리에 머문다. 막힌 단위는 까닭(막는 기체)을 싣는다.
            val q = executionOf(bench, "JO-Q")
            val h = executionOf(bench, "JO-H")
            bench.driveAll { units(bench, q)["T2.travel"] != "PENDING" }
            assertEquals("PENDING", units(bench, h)["T2.travel"])
            assertTrue("JO-Q#T2.travel" in bench.tasks(QUADRUPED), bench.tasks(QUADRUPED).toString())
            assertEquals(before, bench.tasks(HUMANOID))
            val block = bench.execution(h)!!["permitBlock"]
            val reason = block["robots"].single()["reasons"].single()
            assertEquals(listOf(HUMANOID, "AUTHORITY_NOT_HELD"), listOf(block["robots"].single()["robotId"].asText(), reason["cause"].asText()))
            assertTrue(reason["subject"].asText().contains("의 기체 $HUMANOID: ${WaitReason.UNFENCED.text}"), reason.toString())
            // 전역 발행은 열리지 않았다: 상태 HOLDING, 발행 표시 없음, 준비 503, 변경 요청 503.
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            assertEquals(false, issuingMark())
            assertEquals(503, bench.fetch("/host/ready").status)
            assertEquals(503, bench.post("/host/job-orders", request(inspect("JO-X", "T1" to "bay-7"), "candidates", QUADRUPED)).status)
            assertEquals(null, bench.get("/host/leader")["issuingBasis"].textValue())

            // 운영자 확인(미해소 원천 보유자 = 죽은 첫째) 뒤에는 humanoid-01 도 나간다. 전역 발행의 근거는 사람의 확인이다.
            awaitHeartbeatExpired(first)
            // 보유자 이력이 끊긴 경우(이 표 전의 세대를 흉내 내어 세대 1 의 줄을 지움): 셈은 빈 집합에서 멈추고 «다 보지 못함» 이다. 그것을 알았다는
            // 표시 없이는 받지 않는다.
            Fenced.execute("DELETE FROM mission.host_lease_grant WHERE generation = 1")
            val incomplete = bench.get("/host/leader")
            assertEquals(listOf("[]", "false"), listOf(incomplete["unresolvedHolders"].toString(), incomplete["unresolvedHistoryComplete"].asText()))
            assertEquals("HISTORY_INCOMPLETE", confirm(bench, second, 2, previous = first, unresolved = emptyList()).body!!["error"].asText())
            val confirmed = confirm(bench, second, 2, previous = first, unresolved = emptyList(), acknowledged = true)
            assertEquals(200, confirmed.status, confirmed.body.toString())
            assertEquals("OPERATOR", confirmed.body!!["issuingBasis"].asText())
            assertEquals(false, PostgresSupport.queryOne("SELECT history_complete FROM mission.host_takeover_confirmation WHERE generation = 2") { it.getBoolean(1) })
            bench.driveAll { units(bench, h)["T2.travel"] != "PENDING" }
            assertEquals(true, issuingMark())
        }
    }

    @Test
    fun `만료 뒤 깨어난 옛 능동이 송신 직전 확인을 지나 낸 출발은 수신자가 STALE_CONTROLLER 로 거절하고 기체 태스크 이력이 그대로이며 옛 능동은 밀려나 잃는다`() {
        HostBench(lease = TIMING, fence = fileFence()).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.driveAll { units(bench, "exec-1")["T1"] == "RUNNING" }
            bench.idlePumps()
            val old = bench.leadership
            val standby = bench.startHost()
            val next = bench.leadershipOf(standby)
            try {
                // 대기의 세대 공급은 0 이다.
                assertEquals(LeaderState.STANDBY, next.state)
                assertEquals(0L, next.controllerEpoch())
                old.stall.close()
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10), next)
                bench.eventually("새 보유자가 humanoid-01 을 연다") { next.readinessOf(HUMANOID) is Readiness.Ready }
                // 멎은 옛 능동은 아직 자기가 발행 중이라고 안다(세대 1). 그 사이 송신 직전 확인을 지난 출발 하나가 하위 포트로 곧바로 나간다.
                assertEquals(LeaderState.ISSUING, old.state)
                assertEquals(1L, old.controllerEpoch())
                val late = MissionHostApplication.clientPort(bench.channel(), old::controllerEpoch, old::superseded)
                // 인자 없이 보낸다 — 인자 형을 고르려고 능력을 읽으면 그 응답 머리말로 먼저 밀려남을 알아 선로에 내지 않는다(그것도 막는 길이다).
                val reply = late.start(HUMANOID, "JO-1#T2.travel", 1, "navigate_to", emptyMap())
                assertTrue(reply.hasRejection(), reply.toString())
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, reply.rejection.code)
                assertEquals(1L to 2L, reply.rejection.requestedControllerEpoch to reply.rejection.controllerEpochFloor)
                // 수신자는 벤더도 장부도 건드리지 않았다 — 거절된 태스크가 이력에 없다. 새 보유자는 humanoid-01 을 열어 exec-1 을 이어 가므로 이력 전체는
                // 정당하게 자랄 수 있어 비교하지 않는다(가상 시계를 밀지 않아 T1 이 끝나지 않으므로 새 보유자가 T2.travel 을 내지는 않는다).
                bench.eventually("거절된 출발이 이력에 없음") { "JO-1#T2.travel" !in bench.history(HUMANOID).keys }
                // 밀려남(바닥값 2 > 내 세대 1)은 잃음으로 간다.
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
    fun `밀려난 옛 능동은 읽기 응답 머리말만으로도 잃고 세대를 싣지 않은 명령의 거절은 새 보유자를 잃음으로 보내지 않는다`() {
        HostBench(lease = TIMING, fence = fileFence()).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.driveAll { units(bench, "exec-1")["T1"] == "RUNNING" }
            val old = bench.leadership
            val oldPort = bench.bean(RobotPort::class.java)
            val standby = bench.startHost()
            val next = bench.leadershipOf(standby)
            try {
                old.stall.close()
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10), next)
                bench.eventually("새 보유자가 humanoid-01 을 연다") { next.readinessOf(HUMANOID) is Readiness.Ready }
                // 옛 능동의 읽기 하나 — 응답 머리말의 바닥값(2)이 내 세대(1)보다 높다.
                val read = oldPort.snapshot(HUMANOID)
                assertEquals(2L, read!!.controllerFloor)
                assertEquals(LeaderState.LOST, old.state)
                assertTrue(old.lostReason!!.startsWith("수신자가 더 높은 세대로 넘겨받았다고 알렸다"), old.lostReason)

                // 세대를 싣지 않은 명령(설정 오류)은 그 명령만 거절된다 — 새 보유자는 잃지 않는다.
                // 새 보유자는 humanoid-01 을 열어 exec-1 을 이어 가므로 그 기체의 이력은 이 사이에도 정당하게 자랄 수 있다 — 거절된 태스크가 없는지만 본다.
                val unfenced = MissionHostApplication.clientPort(bench.channel(), { 0L }, next::superseded)
                val reply = unfenced.start(HUMANOID, "JO-Z#T1.travel", 1, "navigate_to", emptyMap())
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, reply.rejection.code)
                assertEquals(0L, reply.rejection.requestedControllerEpoch)
                assertTrue("JO-Z#T1.travel" !in bench.history(HUMANOID).keys, bench.history(HUMANOID).keys.toString())
                bench.idlePumps()
                assertEquals(LeaderState.HOLDING, next.state)
            } finally {
                old.stall.open()
                standby.close()
            }
        }
    }

    @Test
    fun `휘발 저장과 정리 미확정은 까닭별로 기다리고 미리 읽은 확인으로는 열지 않으며 운영자 확인 뒤에 열린다`() {
        // 두 기체 모두 메모리 저장으로 대조하는 mimic(VOLATILE). humanoid-01 의 포트는 지금 읽기(pump 스레드)에 DURABLE_PENDING 을, 미리 읽기(다른 스레드)에는
        // 바닥값이 맞는 DURABLE_CONFIRMED 를 내는 가짜다 — 낡은 미리 읽기로 열면 humanoid-01 이 열린다.
        val volatile = ControllerFence(FenceMode.FENCED, FenceStore.memory())
        HostBench(
            lease = TIMING, fence = volatile,
            wiring = { b, leadership, clock -> b.hostedPort(leadership, clock) { robot, port -> if (robot == HUMANOID) Pending(port) else port } },
        ).use { bench ->
            submitBoth(bench)
            val first = bench.host.instanceId
            bench.killHost()
            val second = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            bench.eventually("까닭별 대기") {
                reasonOf(bench, HUMANOID) == WaitReason.DURABLE_PENDING && reasonOf(bench, QUADRUPED) == WaitReason.VOLATILE
            }
            val tasks = bench.tasks(HUMANOID) to bench.tasks(QUADRUPED)
            repeat(6) {
                bench.advance(Duration.ofSeconds(5))
                bench.idlePumps(2)
            }
            assertEquals(tasks, bench.tasks(HUMANOID) to bench.tasks(QUADRUPED))
            assertEquals(WaitReason.DURABLE_PENDING, reasonOf(bench, HUMANOID), "미리 읽은 확인으로 기체를 열었다")
            assertEquals(WaitReason.VOLATILE, reasonOf(bench, QUADRUPED))
            assertEquals(emptyList<String>(), evidence())
            val robots = bench.get("/host/leader")["readiness"]["robots"].associateBy { it["robotId"].asText() }
            assertEquals("DURABLE_PENDING", robots.getValue(HUMANOID)["reasonCode"].asText())
            assertEquals("FENCE_STATUS_VOLATILE", robots.getValue(QUADRUPED)["fenceStatus"].asText())
            listOf("JO-H", "JO-Q").forEach { jobOrder ->
                assertEquals("AUTHORITY_NOT_HELD", bench.execution(executionOf(bench, jobOrder))!!["permitBlock"]["robots"].single()["reasons"].single()["cause"].asText())
            }

            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val h = executionOf(bench, "JO-H")
            val q = executionOf(bench, "JO-Q")
            bench.driveAll { units(bench, h)["T2.travel"] != "PENDING" && units(bench, q)["T2.travel"] != "PENDING" }
        }
    }

    @Test
    fun `부분 발행한 관측 보유자가 정상 종료해도 다음 보유자는 관측으로 뜨고 대조하는 기체는 다시 자동으로 열리며 확인은 마지막으로 발행을 연 보유자를 지목해야 한다`() {
        HostBench(lease = TIMING, split = fileFence()).use { bench ->
            submitBoth(bench)
            val a = bench.host.instanceId
            bench.killHost()
            val b = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            val q = executionOf(bench, "JO-Q")
            bench.eventually("quadruped-01 준비") { bench.leadership.readinessOf(QUADRUPED) is Readiness.Ready }
            bench.driveAll { units(bench, q)["T2.travel"] != "PENDING" }
            assertEquals(false, issuingMark())

            // 부분 발행 중인 B 를 정상 종료(멈춤·리스 반환)해도 발행 표시가 없으므로 C 는 곧바로 발행하지 않는다(세탁 없음).
            bench.restartHost()
            val c = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            assertEquals(LeaderState.HOLDING, bench.leadership.state, "부분 발행이 정상 종료로 세탁됐다")
            assertEquals(false, issuingMark())
            // 대조하는 기체는 C 의 세대(3)로 다시 자동으로 열린다.
            bench.eventually("C 가 quadruped-01 을 다시 연다") { bench.leadership.readinessOf(QUADRUPED) is Readiness.Ready }
            assertTrue("$QUADRUPED|3|FENCE_STATUS_DURABLE_CONFIRMED|$c" in evidence(), evidence().toString())

            // 미해소 원천 보유자는 마지막으로 발행을 연 A 다(B 는 멈춤을 적었다). 앞 보유자(B)만 지목한 확인은 받지 않는다.
            val leader = bench.get("/host/leader")
            assertEquals(listOf(a), leader["unresolvedHolders"].map { it.asText() }, leader.toString())
            assertEquals(true, leader["unresolvedHistoryComplete"].asBoolean())
            assertEquals(b, leader["takeover"]["previousHolder"].asText())
            assertEquals("TAKEOVER_TARGET_CHANGED", confirm(bench, c, 3, previous = b, unresolved = null).body!!["error"].asText())
            assertEquals("TAKEOVER_TARGET_CHANGED", confirm(bench, c, 3, previous = b, unresolved = listOf(b)).body!!["error"].asText())
            // A 가 살아 있다는 직접 증거가 있으면 받지 않는다.
            Fenced.execute("UPDATE mission.host_instance SET last_seen_at = clock_timestamp(), expires_at = clock_timestamp() + interval '1 hour' WHERE instance_id = '$a'")
            assertEquals("PREVIOUS_HOLDER_LIVE", confirm(bench, c, 3, previous = b, unresolved = listOf(a)).body!!["error"].asText())
            Fenced.execute("UPDATE mission.host_instance SET last_seen_at = clock_timestamp() - interval '2 seconds', expires_at = clock_timestamp() - interval '1 second' WHERE instance_id = '$a'")
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            // 확인이 확인 행([A])만 적고 발행 표시에서 실패한다(연결 장애).
            bench.leadership.lease.beforeMark = {
                bench.leadership.lease.beforeMark = null
                throw java.sql.SQLException("시험: 발행 표시 직전 연결 장애", "08006")
            }
            assertEquals(500, confirm(bench, c, 3, previous = b, unresolved = listOf(a)).status)
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            // 그 사이 새 보유자가 늘면(B 의 멈춤이 없던 것으로 됨) 확인 행이 그 보유자를 지목하지 않았으므로 이어 가지 않는다.
            Fenced.execute("UPDATE mission.host_instance SET stopped_at = NULL WHERE instance_id = '$b'")
            assertEquals(listOf(a, b).sorted(), bench.get("/host/leader")["unresolvedHolders"].map { it.asText() })
            assertEquals("TAKEOVER_TARGET_CHANGED", confirm(bench, c, 3, previous = b, unresolved = listOf(a, b)).body!!["error"].asText())
            Fenced.execute("UPDATE mission.host_instance SET stopped_at = clock_timestamp() WHERE instance_id = '$b'")
            // 그 사이 해소만 있었으면(A 가 자기 차단을 적어 셈이 빈 집합) 확인 행([A])이 지금 셈을 덮으므로 이어 간다.
            Fenced.execute("UPDATE mission.host_instance SET fenced_at = clock_timestamp(), fenced_reason = '시험' WHERE instance_id = '$a'")
            assertEquals(emptyList<String>(), bench.get("/host/leader")["unresolvedHolders"].map { it.asText() })
            val confirmed = confirm(bench, c, 3, previous = b, unresolved = emptyList())
            assertEquals(200, confirmed.status, confirmed.body.toString())
            assertEquals(LeaderState.ISSUING, bench.leadership.state)
            assertEquals(listOf("3|$b|$a"), PostgresSupport.queryAll(
                "SELECT generation, previous_holder, array_to_string(unresolved_holders, ',') FROM mission.host_takeover_confirmation",
            ) { "${it.getLong(1)}|${it.getString(2)}|${it.getString(3)}" })
            // 보유자 이력: 세대마다 한 줄, 발행을 연 세대만 issuing_at 이 있다.
            assertEquals(listOf("1|$a|true", "2|$b|false", "3|$c|true"), PostgresSupport.queryAll(
                "SELECT generation, holder, issuing_at IS NOT NULL FROM mission.host_lease_grant ORDER BY generation",
            ) { "${it.getLong(1)}|${it.getString(2)}|${it.getBoolean(3)}" })
        }
    }

    @Test
    fun `정상 종료 인수는 대조하는 수신자에서도 곧바로 발행해 끝까지 간다`() {
        HostBench(lease = TIMING, fence = fileFence()).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.driveAll { units(bench, "exec-1")["T1"] == "RUNNING" }
            bench.restartHost()
            assertEquals(LeaderState.ISSUING, bench.leadership.state)
            assertEquals("GRACEFUL", bench.get("/host/leader")["issuingBasis"].asText())
            val executionId = executionOf(bench, "JO-1")
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(emptyList<String>(), evidence(), "정상 종료 인수는 자동 확인 근거를 쓰지 않는다")
        }
    }

    @Test
    fun `대조하는 기체라도 플릿 단위가 든 실행이면 실제 요구표를 지나 플릿 까닭으로 막힌다`() {
        // 기체 둘이 든 실행(단위의 기체 묶음)은 picasso 의 공개 입구로 만들 수 없다(`ExecutionUnit.robotBinding` 은 internal) — 그 접기는
        // HostReadinessTest 가 요구표를 직접 넣어 본다. 여기서는 실제 호스트의 요구표 배선(`refreshRequirements`)이 플릿 단위를 싣는지 본다.
        val fleet = CountingFleet(dev.picasso.fleetmimic.AmrFleetMimic().apply { place(OUT, CONTAINER) })
        HostBench(lease = TIMING, fence = fileFence(), fleet = fleet).use { bench ->
            val delivery = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", delivery["result"].asText(), delivery.toString())
            bench.eventually("플릿에 맡김") { fleet.dispatched.get() == 1 }
            bench.idlePumps()
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            bench.eventually("quadruped-01 준비") { bench.leadership.readinessOf(QUADRUPED) is Readiness.Ready }
            val history = bench.history(QUADRUPED)
            // 기체는 준비지만 그 기체가 든 실행에 플릿 단위가 있어 권위를 쥐지 않는다.
            val refused = bench.leadership.authorityOf(QUADRUPED)
            assertIs<dev.picasso.middleware.Authority.NotHeld>(refused)
            assertTrue("의 플릿: ${dev.picasso.ops.host.lease.FleetWaitReason.NO_CONFIRM.text}" in refused.reason, refused.reason)
            val execution = bench.get("/host/leader")["readiness"]["executions"].single()
            assertEquals(listOf("JO-F", "true", "false"), listOf(execution["jobOrderId"].asText(), execution["fleet"].asText(), execution["ready"].asText()))
            assertTrue(execution["blockers"].any { "플릿" in it.asText() }, execution.toString())
            bench.idlePumps(4)
            assertEquals(1 to 0, fleet.dispatched.get() to fleet.cancelled.get())
            // 기체 쪽에도 새 명령이 나가지 않았다 — 새 태스크가 없다(태스크 상태의 정당한 진행과 경쟁하지 않게 태스크 id 만 본다).
            assertEquals(history.keys, bench.history(QUADRUPED).keys)
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
        }
    }

    @Test
    fun `관측 중 미리 읽기가 끝나지 않고 첫 지금 읽기가 미준비였던 기체도 간격마다 다시 지금 읽혀 수신자가 확인하면 실제 pump 에서 열린다`() {
        // humanoid-01 의 미리 읽기(pump 스레드 밖)는 끝나지 않는다(늘 `Unread`). pump 스레드의 지금 읽기는 처음에는 대조 안 하는 수신자처럼(바닥값 0)
        // 보이다가 뒤에 수신자가 회복한다(실제 응답 그대로). 간격은 3초로 줄인다.
        val gate = java.util.concurrent.CountDownLatch(1)
        val degrade = java.util.concurrent.atomic.AtomicBoolean(true)
        val pumpReads = java.util.concurrent.atomic.AtomicInteger()
        HostBench(
            lease = TIMING, fence = fileFence(), extraArgs = listOf("--host.readiness.unread-retry=PT3S"),
            wiring = { b, leadership, clock -> b.hostedPort(leadership, clock) { robot, port -> if (robot == HUMANOID) Stuck(port, gate, degrade, pumpReads) else port } },
        ).use { bench ->
            try {
                val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
                assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
                bench.driveAll { units(bench, "exec-1")["T1"] == "RUNNING" }
                bench.killHost()
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
                // 첫 관측(곧바로 지금 읽음)은 바닥값 0 이라 기다린다.
                bench.eventually("첫 관측") { reasonOf(bench, HUMANOID) == WaitReason.UNFENCED }
                val before = pumpReads.get()
                Thread.sleep(1_500)
                assertEquals(before, pumpReads.get(), "간격 안에서 위임 포트를 동기로 읽었다")
                assertEquals(WaitReason.UNFENCED, reasonOf(bench, HUMANOID))
                // 수신자가 회복한다. 미리 읽기는 여전히 끝나지 않지만 간격이 지나면 지금 읽기가 확인을 보고 연다.
                degrade.set(false)
                bench.eventually("간격 뒤 지금 읽기로 열림") { bench.leadership.readinessOf(HUMANOID) is Readiness.Ready }
                assertTrue(pumpReads.get() > before)
                assertEquals(LeaderState.HOLDING, bench.leadership.state)
            } finally {
                gate.countDown()
            }
        }
    }

    /**
     * 미리 읽기(호출 스택에 picasso `PrefetchingRobotPort` 가 있는 읽기)는 [gate] 가 열릴 때까지 끝나지 않고, 그 밖(pump 스레드의 지금 읽기, 제출 판정의
     * HTTP 스레드)의 읽기는 [degrade] 동안 바닥값 0 으로 보이는 포트. [pumpReads] 는 pump 스레드의 읽기만 센다.
     */
    private class Stuck(
        private val delegate: RobotPort,
        private val gate: java.util.concurrent.CountDownLatch,
        private val degrade: java.util.concurrent.atomic.AtomicBoolean,
        private val pumpReads: java.util.concurrent.atomic.AtomicInteger,
    ) : RobotPort by delegate {
        override fun snapshot(robotId: String): RobotSnapshot? {
            if (Throwable().stackTrace.any { it.className.startsWith("dev.picasso.middleware.PrefetchingRobotPort") }) {
                gate.await()
                return delegate.snapshot(robotId)
            }
            if (Thread.currentThread().name == "mission-host-pump") pumpReads.incrementAndGet()
            return delegate.snapshot(robotId)?.let {
                if (degrade.get()) it.copy(controllerFloor = 0, fenceStatus = FenceStatus.FENCE_STATUS_UNSPECIFIED) else it
            }
        }
    }

    /** 맡기기·취소를 세는 플릿. */
    private class CountingFleet(private val inner: dev.picasso.fleetmimic.AmrFleetMimic) : dev.picasso.middleware.AmrFleetPort by inner {
        val dispatched = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()

        override fun dispatch(order: dev.picasso.middleware.TransportOrder): dev.picasso.middleware.TransportHandle? =
            inner.dispatch(order).also { dispatched.incrementAndGet() }

        override fun cancel(handle: dev.picasso.middleware.TransportHandle): Boolean = inner.cancel(handle).also { cancelled.incrementAndGet() }
    }

    /** 지금 읽기(pump 스레드)에는 DURABLE_PENDING, 미리 읽기(다른 스레드)에는 바닥값이 맞는 DURABLE_CONFIRMED 를 내는 가짜 포트. */
    private class Pending(private val delegate: RobotPort) : RobotPort by delegate {
        override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.let {
            if (Thread.currentThread().name == "mission-host-pump") it.copy(fenceStatus = FenceStatus.FENCE_STATUS_DURABLE_PENDING)
            else it.copy(fenceStatus = FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED)
        }
    }

    private companion object {
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"

        fun deliver(jobOrderId: String) =
            """{"jobOrderId":"$jobOrderId","workMasterId":"${dev.picasso.middleware.DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
                """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
                """{"id":"$OUT","equipmentUse":"source","properties":{"container":"$CONTAINER"}},""" +
                """{"id":"$IN","equipmentUse":"destination","properties":{}}]}"""

        /** 리스 주기 0.3초·만료 3초·여유 1초·유휴 트랜잭션 제한 2초. */
        val TIMING = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))

        /** 두 기체에 InspectAsset(대상 둘)을 하나씩 내고 둘 다 첫 대상의 점검(T1)이 RUNNING 이 될 때까지 민다. */
        fun submitBoth(bench: HostBench) {
            listOf("JO-H" to HUMANOID, "JO-Q" to QUADRUPED).forEach { (jobOrder, robot) ->
                val accepted = bench.post("/host/job-orders", request(inspect(jobOrder, "T1" to "bay-7", "T2" to "dock-3"), "candidates", robot)).body!!
                assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            }
            bench.driveAll { listOf("JO-H", "JO-Q").all { units(bench, executionOf(bench, it))["T1"] == "RUNNING" } }
            bench.idlePumps()
        }

        fun executionOf(bench: HostBench, jobOrderId: String): String =
            bench.get("/host/executions")["executions"].first { it["jobOrderId"].asText() == jobOrderId }["executionId"].asText()

        fun units(bench: HostBench, executionId: String): Map<String, String> =
            checkNotNull(bench.execution(executionId)) { "실행이 없다: $executionId" }["units"].associate { it["unitId"].asText() to it["state"].asText() }

        fun reasonOf(bench: HostBench, robotId: String): WaitReason? = (bench.leadership.readinessOf(robotId) as? Readiness.Waiting)?.reason

        /** [done] 이 참이 될 때까지 가상 시계를 1초씩 민다(가상 120초 상한). */
        fun HostBench.driveAll(done: () -> Boolean) {
            repeat(120) {
                if (done()) return
                advance(Duration.ofSeconds(1))
                awaitPump()
                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
            }
            check(done()) { "바라는 상태에 이르지 않았다: ${get("/host/executions")}" }
        }

        /** 그 기체를 든 mimic 이 가진 태스크 id(정렬). */
        fun HostBench.tasks(robotId: String): List<String> =
            mimicOf(robotId).let { m -> m.server.exclusive { m.instance(robotId)!!.tasks.all.map { it.taskId } }.sorted() }

        /** 그 기체의 태스크마다 갱신 로그의 상태 이름. 새 접수·새 갱신이 있으면 바뀐다. */
        fun HostBench.history(robotId: String): Map<String, List<String>> =
            mimicOf(robotId).let { m -> m.server.exclusive { m.instance(robotId)!!.tasks.all.associate { t -> t.taskId to t.log.from(0).map { it.state.name } } } }

        fun evidence(): List<String> = PostgresSupport.queryAll(
            "SELECT robot_id, controller_floor, fence_status, instance_id FROM mission.host_readiness_evidence ORDER BY evidence_id",
        ) { "${it.getString(1)}|${it.getLong(2)}|${it.getString(3)}|${it.getString(4)}" }

        fun issuingMark(): Boolean = PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) }

        fun awaitHeartbeatExpired(instanceId: String) {
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (PostgresSupport.queryOne("SELECT coalesce(bool_or(expires_at > clock_timestamp()), false) FROM mission.host_instance WHERE instance_id = '$instanceId'") { it.getBoolean(1) }) {
                check(System.nanoTime() < deadline) { "앞 보유자 심장박동이 만료하지 않았다" }
                Thread.sleep(100)
            }
        }

        fun confirm(
            bench: HostBench, instanceId: String, generation: Long, previous: String?, unresolved: List<String>?, acknowledged: Boolean? = null,
        ): HostBench.Reply {
            val fields = buildList {
                add(""""confirmedBy":"kim","reason":"옛 호스트 프로세스 종료를 현장에서 확인"""")
                add(""""instanceId":"$instanceId","generation":$generation""")
                add(""""previousHolder":${previous?.let { "\"$it\"" } ?: "null"}""")
                unresolved?.let { add(""""unresolvedHolders":[${it.joinToString(",") { h -> "\"$h\"" }}]""") }
                acknowledged?.let { add(""""historyIncompleteAcknowledged":$it""") }
            }
            return bench.post("/host/leader/takeover-confirmation", "{${fields.joinToString(",")}}")
        }

        @Suppress("unused")
        fun JsonNode.text(field: String): String = this[field].asText()
    }
}
