package dev.picasso.ops.host

import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.Approver
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportStatus
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.fleet.HostFleetPort
import dev.picasso.ops.host.lease.ControllerConfirmation
import dev.picasso.ops.host.lease.FleetReadiness
import dev.picasso.ops.host.lease.FleetWaitReason
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 관측 중 플릿 준비(HA 3단계 PR B2)를 실제 호스트로 본다. 플릿은 프로세스 안의 플릿 미믹이고, 현장 플릿 면의 인수 확인 답은 시험이 정한다([Site]) —
 * 실제 현장 HTTP 면으로 보는 것은 통합 시험(`HttpEpochFenceTest`)이다. 운반 하나를 맡는 플릿 전용 실행(기체 단위 없음)은 기체가 대조하지 않는 수신자에
 * 있어도 플릿 준비만으로 관측 중에 다시 나간다.
 */
class FleetReadinessHostTest {

    /**
     * 현장 플릿 면 대역. [refuse] 동안 맡기기를 보내지 않음으로 답한다. 인수 확인 답은 [answer], 확인 안의 갈고리는 [onConfirm] 이다. [failPumpReads] 동안
     * pump 스레드의 읽기(확인 뒤 다시 읽기)는 던진다 — 미리 읽기(다른 스레드)는 그대로 답한다.
     */
    private class Site(private val inner: AmrFleetMimic) : AmrFleetPort by inner {
        val refuse = AtomicBoolean(false)
        val dispatched = AtomicInteger()
        val cancelled = AtomicInteger()
        val confirms = AtomicInteger()
        val failPumpReads = AtomicBoolean(false)

        @Volatile
        var answer: () -> ControllerConfirmation? = { null }

        @Volatile
        var onConfirm: (() -> Unit)? = null

        /** 주면 핸들에 이 기동 접두를 단다(`<접두>:tr-N`, 현장 플릿 면의 핸들 모양). */
        @Volatile
        var bootPrefix: String? = null

        private fun outer(handle: TransportHandle?): TransportHandle? = handle?.let { h -> bootPrefix?.let { TransportHandle("$it:${h.id}") } ?: h }

        private fun inner(handle: TransportHandle): TransportHandle = TransportHandle(handle.id.substringAfter(':'))

        override fun dispatch(order: TransportOrder): TransportHandle? {
            if (refuse.get()) throw CommandNotSent("시험: 아직 맡기지 않는다")
            dispatched.incrementAndGet()
            return outer(inner.dispatch(order))
        }

        override fun cancel(handle: TransportHandle): Boolean = inner.cancel(inner(handle)).also { cancelled.incrementAndGet() }

        override fun status(handle: TransportHandle): TransportStatus {
            if (failPumpReads.get() && Thread.currentThread().name == "mission-host-pump") throw IllegalStateException("시험: 확인 뒤 다시 읽기가 답하지 않는다")
            return inner.status(inner(handle))
        }

        /** 참이면 찾기에 늘 «없음» 으로 답한다(다시 뜬 현장이 옛 운반을 모르는 것). */
        val forget = AtomicBoolean(false)

        override fun find(reference: String): TransportHandle? {
            if (failPumpReads.get() && Thread.currentThread().name == "mission-host-pump") throw IllegalStateException("시험: 확인 뒤 다시 읽기가 답하지 않는다")
            if (forget.get()) return null
            return outer(inner.find(reference))
        }

        fun confirm(): ControllerConfirmation? {
            confirms.incrementAndGet()
            onConfirm?.invoke()
            return answer()
        }
    }

    private fun confirmed(floor: Long, boot: String = "b1") = ControllerConfirmation(200, floor, "DURABLE_CONFIRMED", boot, "")

    /** 현장 대역과 그 위의 실행 호스트 플릿 포트. 시계는 세트가 뜬 뒤 그 가상 시계다. */
    private fun fleet(site: Site, bench: AtomicReference<HostBench>): HostFleetPort =
        HostFleetPort(site, HostClock { bench.get()?.now() ?: Instant.EPOCH }, "test://fleet", site::confirm)

    private fun deliver(jobOrderId: String) =
        """{"jobOrderId":"$jobOrderId","workMasterId":"${dev.picasso.middleware.DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
            """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
            """{"id":"$OUT","equipmentUse":"source","properties":{"container":"$CONTAINER"}},""" +
            """{"id":"$IN","equipmentUse":"destination","properties":{}}]}"""

    private fun waitFor(what: String, within: Duration = Duration.ofSeconds(15), check: () -> Boolean) {
        val deadline = Instant.now().plus(within)
        while (!check()) {
            check(Instant.now().isBefore(deadline)) { "$within 안에 $what 이(가) 되지 않았다" }
            Thread.sleep(50)
        }
    }

    private fun HostBench.fleetView() = get("/host/leader")["readiness"]["fleet"]

    private fun issuingMark(): Boolean = PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) }

    /** 맡기지 못한 플릿 전용 실행 하나를 두고 호스트를 멈춤 기록 없이 죽여 다음 보유자가 관측(세대 2)으로 뜨게 한다. */
    private fun takeOver(bench: HostBench, site: Site) {
        site.refuse.set(true)
        val accepted = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
        assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
        bench.idlePumps()
        bench.killHost()
        bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
        assertEquals(2L, bench.leadership.controllerEpoch())
    }

    @Test
    fun `플릿 전용 실행은 만료 인수 뒤 근거를 적은 뒤에만 관측 중에 운영자 확인 없이 다시 맡기고 취소하며 근거 쓰기가 실패하면 열지 않는다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        // 다음 보유자(세대 2)의 확인에 바닥값 2 로 답한다. 첫 보유자(세대 1, 발행 중)의 확인 답은 준비에 쓰이지 않는다.
        site.answer = { confirmed(2) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder)).use { bench ->
            holder.set(bench)
            // 근거를 적지 못하게 한다(검사 제약) — 확인과 재대조가 서도 열리지 않아야 한다.
            PostgresSupport.execute("ALTER TABLE mission.host_fleet_readiness_evidence ADD CONSTRAINT test_refuse CHECK (false) NOT VALID")
            takeOver(bench, site)
            site.refuse.set(false)
            waitFor("플릿 확인") { site.confirms.get() >= 2 }
            bench.idlePumps(8)
            assertEquals(0, site.dispatched.get(), "근거 없이 맡겼다")
            assertEquals(FleetWaitReason.RECONCILING.name, bench.fleetView()["reasonCode"].asText(), bench.fleetView().toString())

            PostgresSupport.execute("ALTER TABLE mission.host_fleet_readiness_evidence DROP CONSTRAINT test_refuse")
            waitFor("관측 중 맡기기") { site.dispatched.get() == 1 }
            val evidence = bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).single()
            assertEquals(listOf<Any>(2L, "b1", 2L, "DURABLE_CONFIRMED", "test://fleet"), listOf(evidence.generation, evidence.receiverBootId, evidence.floor, evidence.fenceStatus, evidence.fleetId))
            val view = bench.fleetView()
            assertEquals(listOf("true", "AUTO", "b1", evidence.confirmSeq.toString()), listOf(view["ready"].asText(), view["basis"].asText(), view["receiverBootId"].asText(), view["confirmSeq"].asText()))
            // 관측 그대로다 — 전역 발행·발행 표시는 닫혀 있다.
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            assertEquals(false, issuingMark())

            // 관측 중 운반 취소도 나간다.
            val executionId = bench.get("/host/executions")["executions"].single()["executionId"].asText()
            bench.host.cancel(executionId, Approver("kim", ApproverKind.PERSON), "관측 중 운반 취소", java.util.UUID.randomUUID().toString())
            waitFor("관측 중 취소") { site.cancelled.get() >= 1 }
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            assertEquals(false, issuingMark())
        }
    }

    @Test
    fun `확인 뒤에 보낸 플릿 읽기로만 재대조하고 확인 전 미리 읽은 값으로는 열지 않으며 확인 HTTP 는 호스트 잠금 밖이다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        site.answer = { confirmed(1) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder)).use { bench ->
            holder.set(bench)
            // 첫 보유자가 맡기고 운반이 도는 동안(목적지가 막혀 끝나지 않음) 상태를 미리 읽는다.
            val accepted = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            waitFor("맡김") { site.dispatched.get() == 1 }
            bench.idlePumps()

            // 다음 보유자의 확인은 막아 둔다 — 그동안 pump 와 잠금을 쓰는 조회가 돈다(확인은 잠금 밖).
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            site.answer = { confirmed(2) }
            // 다음 보유자의 확인을 풀 때까지 붙잡는다(죽이기 바로 앞에 건다 — 첫 보유자의 확인은 정착해 10초 간격이라 그사이에 오지 않는다).
            val armed = AtomicBoolean(false)
            site.onConfirm = {
                if (armed.get()) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
            }
            // 확인 뒤 다시 읽기는 답하지 않는다(미리 읽기는 답한다).
            site.failPumpReads.set(true)
            armed.set(true)
            bench.killHost()
            try {
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
                assertTrue(entered.await(15, TimeUnit.SECONDS), "다음 보유자가 플릿 확인을 부르지 않았다")
                val reads = bench.cellEpochs.count { it.first == "/cell" }
                assertEquals(200, bench.fetch("/host/executions/summary").status)
                waitFor("확인이 막힌 동안의 pump") { bench.cellEpochs.count { it.first == "/cell" } > reads + 2 }
                assertIs<FleetReadiness.Waiting>(bench.leadership.fleetReadiness())
            } finally {
                armed.set(false)
                release.countDown()
            }
            site.onConfirm = null
            waitFor("후보") { bench.leadership.fleetCandidate() != null }
            // 확인 뒤에 보낸 다시 읽기가 실패하는 동안은 미리 읽은 값이 있어도 열지 않는다(시간으로 기다려 열지도 않는다).
            bench.idlePumps(10)
            assertEquals(FleetWaitReason.RECONCILING, (bench.leadership.fleetReadiness() as FleetReadiness.Waiting).reason)
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).isEmpty())

            site.failPumpReads.set(false)
            waitFor("플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
            val ready = bench.leadership.fleetReadiness() as FleetReadiness.Ready
            assertEquals(ready.boundary, bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).single().confirmSeq)
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
        }
    }

    @Test
    fun `옛 현장의 확인 404 는 자동으로 열지 않고 운영자 확인 뒤에 맡긴다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        site.answer = { ControllerConfirmation(404, null, null, null, "") }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder)).use { bench ->
            holder.set(bench)
            val first = bench.host.instanceId
            takeOver(bench, site)
            val second = bench.host.instanceId
            site.refuse.set(false)
            waitFor("옛 현장 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.UNSUPPORTED.name }
            bench.idlePumps(6)
            assertEquals(0, site.dispatched.get())

            waitFor("앞 보유자 심장박동 만료") {
                !PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_instance WHERE instance_id = '$first'") { it.getBoolean(1) }
            }
            val confirmed = bench.post(
                "/host/leader/takeover-confirmation",
                """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$second","generation":2,"previousHolder":"$first","unresolvedHolders":["$first"]}""",
            )
            assertEquals(200, confirmed.status, confirmed.body.toString())
            waitFor("운영자 확인 뒤 맡기기") { site.dispatched.get() == 1 }
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence().isEmpty())
        }
    }

    @Test
    fun `앞 근거도 기동 기록도 없는 인수에서 복원한 운반이 옛 기동의 것이면 재대조 중 첫 확인이 새 기동이어도 플릿을 열지 않는다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        site.bootPrefix = "b0"
        // 첫 보유자의 확인은 답을 받지 못한다 — 기동 기록이 남지 않는다.
        site.answer = { null }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder)).use { bench ->
            holder.set(bench)
            val accepted = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            waitFor("맡김") { site.dispatched.get() == 1 }
            bench.idlePumps()
            // 현장이 다시 떴다(새 기동 b1). 다음 보유자는 새 프로세스라 아는 핸들이 없다(새 플릿 포트). 그 pump 를 붙잡아 두어 첫 확인이 핸들을 알기 전에
            // 후보를 세우게 한다 — 래치 직전의 기동 재비교만이 복원한 운반(b0)으로 재시작을 잡는다.
            site.answer = { confirmed(2, "b1") }
            bench.fleet = fleet(site, holder)
            bench.killHost()
            val next = bench.leadership
            next.stall.close(dev.picasso.ops.host.lease.HostLeadership.Stall.Part.PUMP)
            try {
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
                waitFor("핸들을 알기 전의 후보") { next.fleetCandidate() != null }
            } finally {
                next.stall.open(dev.picasso.ops.host.lease.HostLeadership.Stall.Part.PUMP)
            }
            // 다음 확인(정착 뒤 10초 간격)을 기다리지 않고 첫 재대조 pump 에서 잡는다 — 그사이 한 번도 열리지 않는다.
            var opened = false
            waitFor("재시작 까닭", Duration.ofSeconds(4)) {
                opened = opened || next.fleetReadiness() is FleetReadiness.Ready
                (next.fleetReadiness() as? FleetReadiness.Waiting)?.reason == FleetWaitReason.RECEIVER_RESTARTED
            }
            assertTrue(!opened, "복원한 운반의 기동이 다른데 플릿을 열었다")
            bench.idlePumps(6)
            assertIs<FleetReadiness.Waiting>(bench.leadership.fleetReadiness())
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).isEmpty())
            // 관측 중의 확인 기동(b1)은 기준으로 적지 않고, 재시작 판정의 기준(옛 기동 b0)을 적는다.
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT receiver_boot_id FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("재시작 판정의 기준 기록") { boots() == listOf("b0") }

            // 이 보유자가 운영자 확인 없이 죽는다. 다음 보유자(세대 3)는 새 프로세스이고, 다시 뜬 현장은 옛 운반을 모른다(핸들로는 재시작이 안 드러남).
            site.forget.set(true)
            site.answer = { confirmed(3, "b1") }
            bench.fleet = fleet(site, holder)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            // 기준이 옛 기동(b0)이라 확인 기동(b1)과 달라 재시작이다 — 열지 않는다.
            waitFor("다음 보유자 재시작 까닭") { (bench.leadership.fleetReadiness() as? FleetReadiness.Waiting)?.reason == FleetWaitReason.RECEIVER_RESTARTED }
            bench.idlePumps(6)
            assertIs<FleetReadiness.Waiting>(bench.leadership.fleetReadiness())
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(3).isEmpty())
        }
    }

    @Test
    fun `발행 중 본 현장 기동이 다음 인수의 기준이라 발행 중 재기동 뒤에도 자동으로 열리고 인수 직전 재기동만 재시작으로 본다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        val floor = java.util.concurrent.atomic.AtomicLong(1)
        var boot = "B0"
        site.answer = { confirmed(floor.get(), boot) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder), extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S")).use { bench ->
            holder.set(bench)
            val records = { bench.bean(HostRecords::class.java) }
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT receiver_boot_id FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("첫 보유자가 본 기동 기록") { boots() == listOf("B0") }

            // 인수 1(세대 2): 기준 B0 = 확인 B0 → 자동으로 연다.
            val first = bench.host.instanceId
            floor.set(2)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("인수 1 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
            // 운영자 확인으로 발행을 연 뒤 현장을 다시 띄운다(B1) — 발행 중 확인이 그 기동을 적는다.
            val second = bench.host.instanceId
            waitFor("앞 보유자 심장박동 만료") {
                !PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_instance WHERE instance_id = '$first'") { it.getBoolean(1) }
            }
            val confirmed = bench.post(
                "/host/leader/takeover-confirmation",
                """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$second","generation":2,"previousHolder":"$first","unresolvedHolders":["$first"]}""",
            )
            assertEquals(200, confirmed.status, confirmed.body.toString())
            bench.awaitLeader(LeaderState.ISSUING)
            boot = "B1"
            waitFor("발행 중 본 새 기동 기록") { boots().last() == "B1" }

            // 인수 2(세대 3): 기준이 B1 이라 발행 중 재기동은 재시작이 아니다 — 다시 자동으로 연다.
            floor.set(3)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("인수 2 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
            assertEquals("B1", records().fleetReadinessEvidence(3).single().receiverBootId)

            // 인수 3(세대 4): 인수 직전에 현장이 다시 떴다(B2) — 재시작이라 열지 않는다.
            floor.set(4)
            bench.killHost()
            // 죽인 뒤(다음 보유자가 아직 대기인 동안) 현장이 다시 뜬다 — 앞 보유자는 이 기동을 적지 못했다.
            boot = "B2"
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("인수 3 재시작 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.RECEIVER_RESTARTED.name }
            assertTrue(records().fleetReadinessEvidence(4).isEmpty())
        }
    }

    /** 앞 보유자의 심장박동이 만료한 뒤 운영자 확인으로 발행을 연다. */
    private fun HostBench.operatorConfirm(previous: String, generation: Long) {
        val current = host.instanceId
        waitFor("앞 보유자 심장박동 만료") {
            !PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_instance WHERE instance_id = '$previous'") { it.getBoolean(1) }
        }
        val unresolved = get("/host/leader")["unresolvedHolders"].joinToString(",") { "\"${it.asText()}\"" }
        val confirmed = post(
            "/host/leader/takeover-confirmation",
            """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$current","generation":$generation,"previousHolder":"$previous","unresolvedHolders":[$unresolved]}""",
        )
        assertEquals(200, confirmed.status, confirmed.body.toString())
        awaitLeader(LeaderState.ISSUING)
    }

    @Test
    fun `재시작으로 판정한 기동은 운영자 확인 전에는 기준으로 적지 않아 그 보유자가 죽어도 다음 인수가 재시작을 보고 운영자 확인 뒤에는 적혀 다음 인수가 열린다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        val floor = java.util.concurrent.atomic.AtomicLong(1)
        var boot = "B1"
        site.answer = { confirmed(floor.get(), boot) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder), extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S")).use { bench ->
            holder.set(bench)
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT generation || ':' || receiver_boot_id FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("첫 보유자(발행 중)가 본 기동 기록") { boots() == listOf("1:B1") }

            // 인수 B(세대 2): 죽인 뒤 현장이 다시 떴다(B2) — 재시작이다. B 는 운영자 확인 전이라 B2 를 적지 않는다.
            floor.set(2)
            bench.killHost()
            boot = "B2"
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("B 재시작 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.RECEIVER_RESTARTED.name }
            bench.idlePumps(4)
            assertTrue("2:B2" !in boots(), "운영자 확인 전에 재시작 기동을 기준으로 적었다: ${boots()}")

            // B 가 운영자 확인 전에 죽는다 → 인수 C(세대 3): 기준이 여전히 B1 이라 재시작이 보인다.
            floor.set(3)
            val c = run { bench.killHost(); bench.host.instanceId }
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("C 재시작 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.RECEIVER_RESTARTED.name }
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(3).isEmpty())

            // C 를 운영자 확인으로 발행에 들이면 B2 가 적혀, 다음 인수(세대 4)는 자동으로 열린다.
            val previous = PostgresSupport.queryOne("SELECT holder FROM mission.host_lease_grant WHERE generation = 2") { it.getString(1) }
            bench.operatorConfirm(previous, 3)
            assertEquals(c, bench.host.instanceId)
            waitFor("발행 중 B2 기록") { boots().last() == "3:B2" }
            floor.set(4)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("인수 D 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
        }
    }

    @Test
    fun `기동 기준을 읽지 못한 인수는 기준을 읽을 때까지 플릿을 열지 않고 확인 기동을 적지 않으며 읽은 뒤 인수 직전 재시작을 본다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        val floor = java.util.concurrent.atomic.AtomicLong(1)
        var boot = "B0"
        site.answer = { confirmed(floor.get(), boot) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder), extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S")).use { bench ->
            holder.set(bench)
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT receiver_boot_id FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("첫 보유자가 본 기동 기록") { boots() == listOf("B0") }
            floor.set(2)
            bench.killHost()
            boot = "B1"
            // 기준 표를 잠시 읽을 수 없게 한다(이름 바꾸기).
            PostgresSupport.execute("ALTER TABLE mission.host_fleet_receiver_boot RENAME TO host_fleet_receiver_boot_hidden")
            try {
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
                waitFor("후보") { bench.leadership.fleetCandidate() != null }
                bench.idlePumps(6)
                assertIs<FleetReadiness.Waiting>(bench.leadership.fleetReadiness())
                assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).isEmpty())
            } finally {
                PostgresSupport.execute("ALTER TABLE mission.host_fleet_receiver_boot_hidden RENAME TO host_fleet_receiver_boot")
            }
            // 다음 pump 에 기준(B0)을 읽고 확인 기동(B1)과 다름을 본다 — 재시작이다. B1 은 적지 않았다.
            waitFor("기준을 읽은 뒤 재시작 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.RECEIVER_RESTARTED.name }
            bench.idlePumps(4)
            assertTrue("B1" !in boots(), "기준을 읽기 전·재시작 판정 뒤의 확인 기동을 적었다: ${boots()}")
            assertTrue(bench.bean(HostRecords::class.java).fleetReadinessEvidence(2).isEmpty())
        }
    }

    @Test
    fun `재시작 기준 기록이 실패한 채 운영자 확인으로 발행에 들면 뒤의 재시도가 지금 기동을 덮지 않아 다음 인수가 자동으로 열린다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        val floor = java.util.concurrent.atomic.AtomicLong(1)
        var boot = "b0"
        site.answer = { confirmed(floor.get(), boot) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder), extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S")).use { bench ->
            holder.set(bench)
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT generation || ':' || receiver_boot_id || ':' || kind FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("첫 보유자(발행 중)가 본 기동 기록") { boots() == listOf("1:b0:ISSUING") }
            // 재시작 기준 기록을 잠시 실패하게 한다.
            PostgresSupport.execute("ALTER TABLE mission.host_fleet_receiver_boot ADD CONSTRAINT test_refuse_basis CHECK (kind <> 'RESTART_BASIS') NOT VALID")
            floor.set(2)
            val first = bench.host.instanceId
            bench.killHost()
            boot = "b1"
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("재시작 까닭") { bench.fleetView()["reasonCode"].asText() == FleetWaitReason.RECEIVER_RESTARTED.name }
            bench.idlePumps(4)
            assertEquals(listOf("1:b0:ISSUING"), boots(), "재시작 기준 기록이 실패하지 않았다")
            // 운영자 확인 → 발행에 든 순간 지금 기동(b1)을 적는다. 그 뒤 기록 실패가 풀려 재시도가 돌아도 옛 기동(b0)이 기준을 덮지 않는다.
            bench.operatorConfirm(first, 2)
            waitFor("발행 중 b1 기록") { "2:b1:ISSUING" in boots() }
            PostgresSupport.execute("ALTER TABLE mission.host_fleet_receiver_boot DROP CONSTRAINT test_refuse_basis")
            bench.idlePumps(6)
            assertEquals("b1", bench.bean(HostRecords::class.java).latestFleetBoot())

            // 다음 인수(세대 3): 기준 b1 = 확인 b1 → 자동으로 연다.
            floor.set(3)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("다음 인수 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
        }
    }

    @Test
    fun `발행에 든 순간의 옛 기동 기록이 쓰기 직전에 멈춘 사이 확인이 새 기동을 적어도 늦게 들어간 옛 기동이 기준을 덮지 않아 다음 인수가 자동으로 열린다`() {
        val site = Site(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        val floor = java.util.concurrent.atomic.AtomicLong(1)
        var boot = "B0"
        site.answer = { confirmed(floor.get(), boot) }
        val holder = AtomicReference<HostBench>()
        HostBench(lease = TIMING, fleet = fleet(site, holder), extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S")).use { bench ->
            holder.set(bench)
            fun boots(): List<String> = PostgresSupport.queryAll("SELECT generation || ':' || receiver_boot_id FROM mission.host_fleet_receiver_boot ORDER BY boot_id") { it.getString(1) }
            waitFor("첫 보유자(발행 중)가 본 기동 기록") { boots() == listOf("1:B0") }
            floor.set(2)
            val first = bench.host.instanceId
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("인수 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }

            // 확인 스레드는 현장이 다시 뜰 때까지 확인 안에서 기다리고, 발행에 든 순간의 기록(B0)은 쓰기 직전에 선다.
            val paused = java.util.concurrent.CountDownLatch(1)
            val restarted = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            site.onConfirm = { restarted.await(20, java.util.concurrent.TimeUnit.SECONDS) }
            bench.host.beforeFleetBootWrite = { id -> if (id == "B0" && paused.count > 0) { paused.countDown(); release.await(20, java.util.concurrent.TimeUnit.SECONDS) } }
            val confirmer = Thread { bench.operatorConfirm(first, 2) }.apply { start() }
            assertTrue(paused.await(20, java.util.concurrent.TimeUnit.SECONDS), "발행에 든 순간의 기록이 멈추지 않았다")
            // 그 사이 현장이 다시 떠(B1) 확인 스레드가 발행 중 새 기동을 적는다.
            boot = "B1"
            restarted.countDown()
            waitFor("발행 중 B1 기록") { "2:B1" in boots() }
            release.countDown()
            confirmer.join(20_000)
            waitFor("늦게 들어간 옛 기동 기록") { boots().last() == "2:B0" }
            assertEquals("B1", bench.bean(HostRecords::class.java).latestFleetBoot())

            // 다음 인수(세대 3): 기준 B1 = 확인 B1 → 자동으로 연다.
            floor.set(3)
            bench.killHost()
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            waitFor("다음 인수 플릿 준비") { bench.leadership.fleetReadiness() is FleetReadiness.Ready }
        }
    }

    private companion object {
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"

        /** 리스 주기 0.3초·만료 3초·여유 1초·유휴 트랜잭션 제한 2초. */
        val TIMING = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))
    }
}
