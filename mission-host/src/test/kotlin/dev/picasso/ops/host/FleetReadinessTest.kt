package dev.picasso.ops.host

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.middleware.Authority
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.lease.Acquired
import dev.picasso.ops.host.lease.ControllerConfirmation
import dev.picasso.ops.host.lease.FleetReadiness
import dev.picasso.ops.host.lease.FleetWaitReason
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.host.lease.HostLease
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeadershipListener
import dev.picasso.ops.host.lease.LeaseConnections
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.lease.Requirement
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 관측([LeaderState.HOLDING]) 안의 플릿 준비 슬롯(HA 3단계 PR B2)을 호스트 없이 실제 리스로 본다 — 확인 답으로 후보가 서고, 재대조 뒤 래치하며, 어떤 답이
 * 래치를 풀고 어떤 답은 풀지 않는가. 실제 pump 의 재대조·근거는 [FleetReadinessHostTest] 가 본다.
 */
class FleetReadinessTest {

    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

    @BeforeTest
    fun fresh() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
    }

    private fun answer(floor: Long, status: String, boot: String = BOOT) = ControllerConfirmation(200, floor, status, boot, "")

    private fun HostLeadership.confirm(seq: Long, answer: ControllerConfirmation?, handleBoots: Set<String> = emptySet()) =
        observeFleetConfirm(seq, answer, handleBoots, Instant.EPOCH)

    private fun HostLeadership.reason(): FleetWaitReason? = (fleetReadiness() as? FleetReadiness.Waiting)?.reason

    /** 확인 → 후보 → 래치(재대조 성공을 흉내). */
    private fun HostLeadership.open(seq: Long, boot: String = BOOT): FleetReadiness.Ready {
        confirm(seq, answer(lease.generation!!, "DURABLE_CONFIRMED", boot))
        assertTrue(latchFleet(assertNotNull(fleetCandidate())))
        return assertIs<FleetReadiness.Ready>(fleetReadiness())
    }

    @Test
    fun `플릿 전용 실행은 플릿 준비만 요구하고 기체와 플릿이 든 혼합 실행은 둘 다 요구하며 전역 발행은 닫힌 채다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(
                // 플릿 전용 실행: 기체 단위가 없다. 발행 관문은 묶인 기체(quadruped-01)로 묻는다.
                Requirement("exec-F", "JO-F", emptySet(), fleet = true, subjects = setOf(QUADRUPED)),
                Requirement("exec-M", "JO-M", setOf(HUMANOID), fleet = true),
            )
            assertEquals(FleetWaitReason.UNREAD, leadership.reason())
            assertFalse(leadership.mayDispatch("JO-F#HU-1"))
            val refused = assertIs<Authority.NotHeld>(leadership.authorityOf(QUADRUPED))
            assertTrue("실행 exec-F(작업 지시 JO-F)의 플릿: ${FleetWaitReason.UNREAD.text}" in refused.reason, refused.reason)

            // 확인 답(바닥값 = 내 세대 ∧ DURABLE_CONFIRMED)만으로는 열리지 않는다 — 재대조·근거를 기다린다.
            leadership.confirm(10, answer(mine, "DURABLE_CONFIRMED"))
            assertEquals(FleetWaitReason.RECONCILING, leadership.reason())
            assertFalse(leadership.mayDispatch("JO-F#HU-1"))
            assertTrue(leadership.latchFleet(assertNotNull(leadership.fleetCandidate())))

            // 플릿 전용 실행은 기체 준비 없이 열린다(묶인 기체는 명령을 받지 않는다).
            assertTrue(leadership.mayDispatch("JO-F#HU-1"))
            assertEquals(Authority.Held, leadership.authorityOf(QUADRUPED))
            // 혼합 실행은 humanoid-01 이 준비돼야 열린다.
            assertFalse(leadership.mayDispatch("JO-M#HU-2"))
            assertFalse(leadership.mayIssue(HUMANOID))
            leadership.observeFresh(HUMANOID, snapshot(mine), 1, Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            assertTrue(leadership.mayDispatch("JO-M#HU-2"))
            assertTrue(leadership.mayIssue(HUMANOID))
            // 전역 발행·발행 표시는 그대로 닫혀 있다.
            assertFalse(leadership.mayIssue())
            assertEquals(LeaderState.HOLDING, leadership.state)
            assertEquals(false, PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) })
        }
    }

    @Test
    fun `플릿 전용 실행에 묶인 기체는 발행 관문에서는 플릿 준비로 쥐지만 그 기체로 나가는 출발과 취소는 그 기체의 확인 없이는 막힌다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-F", "JO-F", emptySet(), fleet = true, subjects = setOf(QUADRUPED)))
            leadership.open(10)
            // 미들웨어 발행 관문(실행 요구 접기): 플릿 전용 실행의 묶인 기체는 플릿 준비만으로 쥔다.
            assertEquals(Authority.Held, leadership.authorityOf(QUADRUPED))
            assertTrue(leadership.mayDispatch("JO-F#HU-1"))
            // 기체 송신 직전 확인: quadruped-01 은 수신자 확인이 없다 — 같은 pump 안 재할당이나 실행 밖 취소로 그 기체에 나가는 명령은 막힌다.
            assertFalse(leadership.mayIssue(QUADRUPED))
            val sent = mutableListOf<String>()
            val gated = dev.picasso.ops.host.lease.GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent))), leadership)
            kotlin.test.assertFailsWith<dev.picasso.middleware.CommandNotSent> { gated.start(QUADRUPED, "JO-F#T1", 1, "navigate_to", emptyMap()) }
            kotlin.test.assertFailsWith<dev.picasso.middleware.CommandNotSent> { gated.cancel(QUADRUPED, dev.picasso.contracts.v1.TaskHandle.newBuilder().setTaskId("JO-X#T1").build()) }
            assertEquals(emptyList<String>(), sent)
            // 그 기체가 확인되면(래치) 나간다.
            leadership.observeFresh(QUADRUPED, snapshot(mine), 1, Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            assertTrue(leadership.mayIssue(QUADRUPED))
            gated.start(QUADRUPED, "JO-F#T1", 1, "navigate_to", emptyMap())
            assertEquals(listOf("start JO-F#T1"), sent)
        }
    }

    @Test
    fun `래치 직전 아는 운반 핸들의 기동이 후보의 기동과 다르면 재시작으로 열지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            // 재대조 중의 첫 확인 — 아직 아는 핸들이 없어 기동 기준이 그 답(b1)으로 선다.
            leadership.confirm(10, answer(mine, "DURABLE_CONFIRMED", "b1"))
            val candidate = assertNotNull(leadership.fleetCandidate())
            assertTrue(leadership.fleetBootsAgree(candidate, setOf("b1")))
            // 복원한 운반이 옛 기동(b0)의 것이다 — 현장이 그 운반을 맡긴 뒤 다시 떴다.
            assertFalse(leadership.fleetBootsAgree(candidate, setOf("b0", "b1")))
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
            assertFalse(leadership.latchFleet(candidate))
            leadership.confirm(11, answer(mine, "DURABLE_CONFIRMED", "b1"))
            assertNull(leadership.fleetCandidate())
        }
    }

    @Test
    fun `경계 뒤 읽기끼리 응답이 역전돼 늦게 온 앞 순번의 낮은 바닥값으로는 풀지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.open(10)
            leadership.observeFleetFloor(12, mine)
            // 순번 11(12 보다 앞서 보냄)의 응답이 늦게 닿았다.
            leadership.observeFleetFloor(11, mine - 1)
            assertIs<FleetReadiness.Ready>(leadership.fleetReadiness())
            leadership.observeFleetFloor(13, mine - 1)
            assertEquals(FleetWaitReason.FLOOR_MISMATCH, leadership.reason())
        }
    }

    @Test
    fun `기동 기준은 가장 높은 세대의 기록이고 같은 세대 안에서는 발행 중 기록이 늦게 적힌 재시작 기준보다 앞선다`() {
        val records = dev.picasso.ops.host.store.HostRecords(
            org.springframework.jdbc.core.simple.JdbcClient.create(ds), TestLease.guard(ds), com.fasterxml.jackson.databind.ObjectMapper(),
        )
        fun boot(generation: Int, id: String, kind: String, seq: Long = 0) = Fenced.execute(
            "INSERT INTO mission.host_fleet_receiver_boot (generation, instance_id, fleet_id, receiver_boot_id, kind, observed_seq) " +
                "VALUES ($generation, 'mw-x', 'f', '$id', '$kind', $seq)",
        )
        assertNull(records.latestFleetBoot())
        boot(1, "b0", "ISSUING")
        // 세대 2: 관측 중 재시작 판정(기준 b0) → 운영자 확인으로 발행(b1) → 재시작 기준 기록이 늦게 재시도됨(b0).
        boot(2, "b1", "ISSUING")
        boot(2, "b0", "RESTART_BASIS")
        assertEquals("b1", records.latestFleetBoot())
        // 더 높은 세대의 재시작 기준(운영자 확인 전에 죽은 보유자)은 앞 세대의 발행 중 기록보다 앞선다.
        boot(3, "b1", "RESTART_BASIS")
        boot(1, "b9", "ISSUING")
        assertEquals("b1", records.latestFleetBoot())
        boot(4, "b2", "RESTART_BASIS")
        assertEquals("b2", records.latestFleetBoot())
        // 같은 세대·종류 안에서는 행 순서가 아니라 관측 순번 — 발행에 든 순간의 옛 기동(b2, 순번 5)이 쓰기 직전에 멈춘 사이 확인 스레드가 새 기동(b3, 순번 7)을
        // 적고, 옛 기동이 늦게(더 큰 행 번호로) 들어가도 기준은 b3 다. 같은 기동을 더 뒤의 확인으로 다시 적으면 새 줄로 앞선다.
        boot(5, "b3", "ISSUING", 7)
        boot(5, "b2", "ISSUING", 5)
        assertEquals("b3", records.latestFleetBoot())
        boot(5, "b2", "ISSUING", 9)
        assertEquals("b2", records.latestFleetBoot())
    }

    @Test
    fun `래치 뒤 확인이 4xx 로 바뀌면 옛 현장으로 보고 푼다`() {
        holding().use { leadership ->
            leadership.open(10)
            leadership.confirm(11, ControllerConfirmation(404, null, null, null, ""))
            assertEquals(FleetWaitReason.UNSUPPORTED, leadership.reason())
        }
    }

    @Test
    fun `래치는 바닥값 후퇴와 휘발·대조 끔에만 풀리고 같은 세대 미확정·안 닿음·경계 앞 늦은 응답으로는 풀리지 않으며 되풀이 확인은 경계를 옮기지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            val opened = leadership.open(10)
            assertEquals(10L, opened.boundary)
            // 풀지 않는 답.
            leadership.confirm(11, answer(mine, "DURABLE_PENDING"))
            leadership.confirm(12, null)
            leadership.confirm(13, ControllerConfirmation(503, null, null, null, ""))
            leadership.observeFleetFloor(9, 0)
            leadership.observeFleetFloor(14, mine)
            assertIs<FleetReadiness.Ready>(leadership.fleetReadiness())
            // 경계 뒤에 보낸 읽기의 바닥값 후퇴는 푼다.
            leadership.observeFleetFloor(15, mine - 1)
            assertEquals(FleetWaitReason.FLOOR_MISMATCH, leadership.reason())

            // 다시 확인하면 새 경계로 후보가 선다. 그 뒤의 되풀이 확인은 경계를 옮기지 않는다.
            leadership.confirm(20, answer(mine, "DURABLE_CONFIRMED"))
            leadership.confirm(21, answer(mine, "DURABLE_CONFIRMED"))
            assertEquals(20L, leadership.fleetCandidate()?.boundary)
            // 후보 중의 같은 세대 미확정은 후보를 버린다(확인이 아니다).
            leadership.confirm(22, answer(mine, "DURABLE_PENDING"))
            assertNull(leadership.fleetCandidate())
            assertEquals(FleetWaitReason.DURABLE_PENDING, leadership.reason())

            leadership.open(30)
            leadership.confirm(31, answer(mine, "VOLATILE"))
            assertEquals(FleetWaitReason.VOLATILE, leadership.reason())
            leadership.open(40)
            leadership.confirm(41, answer(0, "UNSPECIFIED"))
            assertEquals(FleetWaitReason.UNFENCED, leadership.reason())
        }
    }

    @Test
    fun `현장 기동 ID 가 바뀌면 래치를 풀고 재확인 성공으로는 다시 열리지 않으며 운영자 확인 뒤 발행으로 열린다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-F", "JO-F", emptySet(), fleet = true, subjects = setOf(QUADRUPED)))
            leadership.open(10, "b1")
            assertTrue(leadership.mayDispatch("JO-F#HU-1"))
            leadership.confirm(11, answer(mine, "DURABLE_CONFIRMED", "b2"))
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
            assertFalse(leadership.mayDispatch("JO-F#HU-1"))
            // 새 기동의 확인이 다시 성공해도, 옛 기동으로 돌아와도 열리지 않는다(재시작은 운영자 확인 사유).
            leadership.confirm(12, answer(mine, "DURABLE_CONFIRMED", "b2"))
            leadership.confirm(13, answer(mine, "DURABLE_CONFIRMED", "b1"))
            assertNull(leadership.fleetCandidate())
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
            // 운영자 확인 → 전역 발행 → 운반도 나간다.
            assertTrue(leadership.confirmed())
            assertTrue(leadership.mayDispatch("JO-F#HU-1"))
        }
        // 앞 근거(이전 세대)의 기동과 다르거나 아는 운반 핸들의 기동과 다르면 처음부터 재시작이다.
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.fleetConfirmable(true)
            leadership.fleetBootBaseline("b0")
            leadership.confirm(10, answer(mine, "DURABLE_CONFIRMED", "b1"))
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
        }
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.confirm(10, answer(mine, "DURABLE_CONFIRMED", "b1"), handleBoots = setOf("b0"))
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
        }
        // 확인이 먼저 닿아 기준이 선 뒤 앞 근거의 기동이 다르게 들어와도 재시작이다.
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.confirm(10, answer(mine, "DURABLE_CONFIRMED", "b1"))
            leadership.fleetBootBaseline("b0")
            assertEquals(FleetWaitReason.RECEIVER_RESTARTED, leadership.reason())
            assertFalse(leadership.latchFleet(FleetCandidate(mine)))
        }
    }

    @Test
    fun `옛 현장의 4xx 와 대조 끔은 자동으로 열리지 않고 잃은 뒤에는 후보를 래치하지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.confirm(10, ControllerConfirmation(404, null, null, null, ""))
            assertEquals(FleetWaitReason.UNSUPPORTED, leadership.reason())
            assertNull(leadership.fleetCandidate())
            leadership.confirm(11, answer(0, "UNSPECIFIED"))
            assertEquals(FleetWaitReason.UNFENCED, leadership.reason())
            assertNull(leadership.fleetCandidate())

            leadership.confirm(12, answer(mine, "DURABLE_CONFIRMED"))
            val candidate = assertNotNull(leadership.fleetCandidate())
            leadership.lost("시험: 확인 도중 잃음")
            assertFalse(leadership.latchFleet(candidate))
            assertIs<FleetReadiness.Waiting>(leadership.fleetReadiness())
        }
    }

    /** 이 세대의 후보 모양(기동 b1, 경계 10) — 지금 후보가 아니므로 래치되지 않아야 한다. */
    private fun FleetCandidate(generation: Long) =
        dev.picasso.ops.host.lease.FleetCandidate(generation, 10, generation, "DURABLE_CONFIRMED", "b1", Instant.EPOCH)

    /** 만료 인수로 관측에 든 리더(세대 5). 플릿 인수 확인을 부르는 배선이다. */
    private fun holding(): HostLeadership {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
        Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('mw-other', clock_timestamp() + interval '1 hour')")
        Fenced.execute("UPDATE mission.host_lease SET holder = 'mw-old', generation = 4 WHERE role = 'host'")
        val leadership = HostLeadership(HostLease("mw-b", TIMING, PLAIN), TIMING, HostExit {})
        leadership.fleetConfirmable(true)
        leadership.begin(object : LeadershipListener {
            override fun acquired(acquired: Acquired) = leadership.reconciled()
            override fun lost(reason: String) = Unit
        }) { it.run() }
        assertEquals(LeaderState.HOLDING, leadership.state)
        return leadership
    }

    /** 출발·취소를 적기만 하는 포트. */
    private class Recording(private val sent: MutableList<String>) : dev.picasso.middleware.RobotPort {
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
            dev.picasso.contracts.v1.StartTaskResponse.getDefaultInstance().also { sent += "start $taskId" }
        override fun watch(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = emptyList<dev.picasso.contracts.v1.WatchTaskResponse>()
        override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) =
            dev.picasso.contracts.v1.CancelTaskResponse.getDefaultInstance().also { sent += "cancel ${handle.taskId}" }
        override fun snapshot(robotId: String): RobotSnapshot? = null
        override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
    }

    private companion object {
        const val BOOT = "b1"
        val TIMING = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))
        val PLAIN = LeaseConnections { PostgresSupport.connection() }

        fun snapshot(floor: Long) = RobotSnapshot(
            sequence = 1, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap(),
            controllerFloor = floor, fenceStatus = FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED,
        )
    }
}
