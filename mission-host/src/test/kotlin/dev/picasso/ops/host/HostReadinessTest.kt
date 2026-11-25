package dev.picasso.ops.host

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.middleware.Authority
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.lease.Acquired
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.host.lease.HostLease
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeadershipListener
import dev.picasso.ops.host.lease.LeaseConnections
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.lease.Readiness
import dev.picasso.ops.host.lease.Requirement
import dev.picasso.ops.host.lease.RestartHolds
import dev.picasso.ops.host.lease.WaitReason
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 관측([LeaderState.HOLDING]) 안의 준비 집합과 권위 판정(HA 3단계)을 호스트 없이 실제 리스로 본다. 수신자가 든 기체로 실제 인수를 보는 것은
 * [HostEpochTakeoverTest] 다. 이 시험이 따로 있는 까닭: 실행 하나가 기체 둘을 쓰는 계획(단위 묶음)은 지금 picasso 의 공개 입구로 만들 수 없어,
 * «실행 단위 요구» 의 접기는 «실행 → 필수 기체·플릿» 표를 직접 넣어 본다.
 */
class HostReadinessTest {

    /** 지금 읽기의 요청 순번(운영에서는 [HostRobotPort] 가 매긴다). */
    private val seq = java.util.concurrent.atomic.AtomicLong()

    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

    @BeforeTest
    fun fresh() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
    }

    @Test
    fun `실행이 든 기체가 하나라도 준비되지 않으면 준비된 기체도 권위를 쥐지 않고 까닭에 막는 기체가 실리며 그 실행이 빠지면 열린다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(
                Requirement("exec-1", "JO-1", setOf(HUMANOID, QUADRUPED), fleet = false),
                Requirement("exec-2", "JO-2", setOf(QUADRUPED), fleet = false),
            )
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.observeFresh(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet(), Instant.EPOCH)
            // 지금 읽기로 본 준비는 재대조 뒤 공개(래치)해야 선다.
            assertIs<Readiness.Waiting>(leadership.readinessOf(QUADRUPED))
            assertFalse(leadership.mayIssue(QUADRUPED))
            leadership.pendingReady().single().let(leadership::latch)
            assertIs<Readiness.Ready>(leadership.readinessOf(QUADRUPED))

            // 준비된 기체(quadruped-01)라도 그것이 든 실행 exec-1 의 humanoid-01 이 준비되지 않았으므로 쥐지 않았다. 까닭에 막는 기체가 실린다.
            val refused = assertIs<Authority.NotHeld>(leadership.authorityOf(QUADRUPED))
            assertTrue(refused.reason.startsWith(HostLeadership.AWAITING_CONFIRMATION), refused.reason)
            assertTrue("실행 exec-1(작업 지시 JO-1)의 기체 $HUMANOID: ${WaitReason.UNFENCED.text}" in refused.reason, refused.reason)
            assertFalse(leadership.mayIssue(QUADRUPED))
            assertFalse(leadership.mayIssue(HUMANOID))
            assertEquals(mapOf(QUADRUPED to refused), leadership.authorityPort.read(setOf(QUADRUPED)))
            // 송신 직전 확인도 같은 판정이다(기체 단위, 취소 포함).
            val sent = mutableListOf<String>()
            val gated = GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent))), leadership)
            assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-2#T1", 1, "navigate_to", emptyMap()) }
            assertFailsWith<CommandNotSent> { gated.cancel(QUADRUPED, dev.picasso.contracts.v1.TaskHandle.newBuilder().setTaskId("JO-2#T1").build()) }
            assertEquals(emptyList<String>(), sent)

            // exec-1 이 빠지면(정착) quadruped-01 이 든 실행의 요구가 모두 준비라 쥔다. humanoid-01 은 그대로 막힌다.
            leadership.requirements = listOf(Requirement("exec-2", "JO-2", setOf(QUADRUPED), fleet = false))
            assertEquals(Authority.Held, leadership.authorityOf(QUADRUPED))
            assertTrue(leadership.mayIssue(QUADRUPED))
            gated.start(QUADRUPED, "JO-2#T1", 1, "navigate_to", emptyMap())
            assertEquals(listOf("start JO-2#T1"), sent)
            assertFalse(leadership.mayIssue(HUMANOID))
            // 전역 발행은 여전히 닫혀 있다(발행 표시도 없다).
            assertFalse(leadership.mayIssue())
            assertEquals(LeaderState.HOLDING, leadership.state)
            assertEquals(false, PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) })

            // 플릿 단위가 든 실행은 기체가 모두 준비여도 쥐지 않는다(플릿 준비가 서지 않았다 — 플릿 인수 확인을 본 적이 없다). 운반 참조로 되짚은 송신 직전 확인도 막는다.
            leadership.requirements = listOf(Requirement("exec-3", "JO-3", setOf(QUADRUPED), fleet = true))
            val fleet = assertIs<Authority.NotHeld>(leadership.authorityOf(QUADRUPED))
            assertTrue("실행 exec-3(작업 지시 JO-3)의 플릿" in fleet.reason, fleet.reason)
            assertFalse(leadership.mayDispatch("JO-3#T2"))
            assertFalse(leadership.mayDispatch("JO-UNKNOWN#T1"))
        }
    }

    @Test
    fun `준비는 지금 읽은 응답의 바닥값이 내 세대이고 DURABLE_CONFIRMED 일 때만이고 까닭별로 기다리며 지금 읽기 대상은 미리 읽기가 준비를 보인 기체뿐이다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            fun reasonAfter(snapshot: RobotSnapshot?): WaitReason {
                leadership.observeFresh(HUMANOID, snapshot, seq.incrementAndGet(), Instant.EPOCH)
                assertTrue(leadership.pendingReady().isEmpty())
                return assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason
            }
            assertEquals(WaitReason.UNREAD, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            assertEquals(WaitReason.UNREAD, reasonAfter(null))
            assertEquals(WaitReason.UNFENCED, reasonAfter(snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED)))
            assertEquals(WaitReason.VOLATILE, reasonAfter(snapshot(mine, FenceStatus.FENCE_STATUS_VOLATILE)))
            assertEquals(WaitReason.DURABLE_PENDING, reasonAfter(snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING)))
            assertEquals(WaitReason.UNREAD, reasonAfter(snapshot(mine, FenceStatus.FENCE_STATUS_UNSPECIFIED)))
            assertEquals(WaitReason.FLOOR_MISMATCH, reasonAfter(snapshot(mine - 1, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED)))
            // 세대 0·낮은 바닥값은 밀려남이 아니다 — 관측 그대로다.
            assertEquals(LeaderState.HOLDING, leadership.state)

            // 지금 읽기 대상은 미리 읽기가 준비를 보인 기체뿐이다(대조 안 함·휘발은 호스트 잠금 아래에서 읽지 않는다). 미리 읽은 값으로는 열지 않는다.
            assertEquals(emptySet(), leadership.freshTargets())
            leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_VOLATILE), seq.incrementAndGet())
            assertEquals(emptySet(), leadership.freshTargets())
            assertEquals(WaitReason.VOLATILE, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertEquals(setOf(HUMANOID), leadership.freshTargets())
            assertTrue(leadership.pendingReady().isEmpty(), "미리 읽은 값으로 후보가 섰다")
            assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID))

            // 후보는 그 라운드의 것이다 — pump 앞에서 비우면 앞 라운드의 후보는 래치되지 않는다.
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            assertEquals(1, leadership.pendingReady().size)
            leadership.clearCandidates()
            assertTrue(leadership.pendingReady().isEmpty())
            assertFalse(leadership.mayIssue(HUMANOID))

            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            assertTrue(leadership.mayIssue(HUMANOID))
            assertEquals(emptySet(), leadership.freshTargets())
            // 래치: 읽기 실패(응답 없음)로는 풀리지 않는다.
            leadership.observeFresh(HUMANOID, null, seq.incrementAndGet(), Instant.EPOCH)
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID))
            assertTrue(leadership.mayIssue(HUMANOID))
        }
    }

    @Test
    fun `래치된 기체는 바닥값 후퇴나 휘발·확인 답 없음에만 풀리고 자기 세대의 미확정과 늦게 소비한 옛 응답으로는 풀리지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            fun open() {
                leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
                leadership.pendingReady().single().let(leadership::latch)
                assertTrue(leadership.mayIssue(HUMANOID))
            }
            open()
            // 미리 읽은 응답의 바닥값이 0 으로 후퇴(수신자가 바닥값을 잃고 다시 떴거나 대조를 껐다): 푼다.
            leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet())
            assertEquals(WaitReason.UNFENCED, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            assertFalse(leadership.mayIssue(HUMANOID))
            open()
            // 내 세대보다 낮은 0 아닌 바닥값도 후퇴다.
            leadership.observePrefetched(HUMANOID, snapshot(mine - 1, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertEquals(WaitReason.FLOOR_MISMATCH, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            open()
            // 바닥값이 내 세대이고 DURABLE_PENDING 인 답은 이 세대 자신의 미확정이다 — 풀지 않는다(지금 읽기·미리 읽기 모두).
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING), seq.incrementAndGet(), Instant.EPOCH)
            leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING), seq.incrementAndGet())
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID))
            assertTrue(leadership.mayIssue(HUMANOID))
            // 바닥값은 같은데 휘발·확인 답 없음이면 푼다.
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_VOLATILE), seq.incrementAndGet(), Instant.EPOCH)
            assertEquals(WaitReason.VOLATILE, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            assertFalse(leadership.mayIssue(HUMANOID))
            open()
            leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet())
            assertEquals(WaitReason.UNREAD, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)

            // 응답 순서(요청 순번): 래치 근거가 된 지금 읽기(순번 1000)보다 먼저 보낸 미리 읽기(순번 999)의 후퇴 응답은 늦게 소비돼도 풀지 않는다. 뒤에
            // 보낸 것(순번 1001)은 푼다. 받은 시각은 셋 다 같다.
            seq.set(998)
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 1000, Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), 999)
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), "늦게 소비한 옛 응답이 새 래치를 풀었다")
            // 순번을 모르는 응답(응답 객체로 순번을 되찾지 못함)은 옛 응답으로 다룬다 — 풀지 않는다.
            leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), null)
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), "순번 모름 응답이 래치를 풀었다")
            leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), 1001)
            assertEquals(WaitReason.UNFENCED, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
            assertEquals(LeaderState.HOLDING, leadership.state)
        }
    }

    @Test
    fun `같은 라운드에서 후보를 세운 뒤 후퇴를 보면 그 후보는 공개되지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            assertEquals(1, leadership.pendingReady().size)
            // 같은 pump 의 뒤 스냅숏(보류 재검토 따위)이 바닥값 후퇴를 보인다.
            leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet())
            assertTrue(leadership.pendingReady().isEmpty(), "후퇴를 본 뒤에도 후보가 남았다")
            assertFalse(leadership.mayIssue(HUMANOID))
            // 관측 중 지금 읽어도 되는 기체는 열렸거나 미리 읽기가 준비를 보인 기체뿐이다.
            assertFalse(leadership.mayReadNow(HUMANOID))
            leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertTrue(leadership.mayReadNow(HUMANOID))
            assertTrue(leadership.pendingReady().isEmpty(), "미리 읽은 값으로 후보가 섰다")
        }
    }

    @Test
    fun `준비를 공개하고 푸는 동안 다른 스레드의 권위 조회와 까닭 조회가 실패하지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID, QUADRUPED), fleet = false))
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val readers = (1..3).map {
                Thread {
                    while (!stop.get()) {
                        try {
                            leadership.authorityOf(HUMANOID)
                            leadership.authorityOf(QUADRUPED)
                            leadership.requirements.forEach(leadership::blockers)
                            leadership.mayDispatch("JO-1#T1")
                        } catch (e: Throwable) {
                            errors += e
                        }
                    }
                }.apply { start() }
            }
            repeat(3_000) {
                leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
                leadership.pendingReady().forEach(leadership::latch)
                leadership.observePrefetched(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet())
            }
            stop.set(true)
            readers.forEach { it.join(5_000) }
            assertEquals(emptyList(), errors.map { it.toString() })
        }
    }

    @Test
    fun `밀려남만 잃음으로 가고 세대 0 의 거절과 낮은 바닥값은 아무것도 하지 않으며 세대 공급은 대기와 잃음에서 0 이다`() {
        val other = HostLeadership(HostLease("mw-a", TIMING, PLAIN), TIMING, HostExit {})
        other.use {
            // 대기(리스 없음)의 세대 공급은 0 이다.
            assertEquals(LeaderState.STANDBY, other.state)
            assertEquals(0L, other.controllerEpoch())
        }
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            assertEquals(mine, leadership.controllerEpoch())
            leadership.superseded(HUMANOID, 0, mine + 5)
            leadership.superseded(HUMANOID, mine, mine)
            leadership.superseded(HUMANOID, mine, mine - 1)
            assertEquals(LeaderState.HOLDING, leadership.state)
            // 응답 바닥값이 내 세대보다 높으면 밀려남이다(읽기 머리말).
            leadership.observeFresh(HUMANOID, snapshot(mine + 1, FenceStatus.FENCE_STATUS_UNSPECIFIED), seq.incrementAndGet(), Instant.EPOCH)
            assertEquals(LeaderState.LOST, leadership.state)
            assertTrue(leadership.lostReason!!.startsWith("수신자가 더 높은 세대로 넘겨받았다고 알렸다"), leadership.lostReason)
            // 잃은 뒤의 세대 공급은 0 이다 — picasso 클라이언트는 붙잡은 세대와 달라 명령을 보내지 않는다.
            assertEquals(0L, leadership.controllerEpoch())
            assertFalse(leadership.mayIssue(HUMANOID))
        }
    }

    @Test
    fun `응답 순서는 요청 순번으로 가려 같은 시각이나 역행하는 시계에서도 옛 미리 읽기가 새 래치를 풀지 않고 새 후퇴는 버리지 않는다`() {
        val frozen = Instant.parse("2026-11-17T00:00:00Z")
        var backwards = frozen
        val clocks = listOf("같은 시각" to HostClock { frozen }, "역행" to HostClock { backwards.also { backwards = it.minusSeconds(1) } })
        for ((name, clock) in clocks) {
            fresh()
            holding().use { leadership ->
                val mine = leadership.lease.generation!!
                leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
                // 응답마다 새 객체다(수신자의 응답처럼) — 순번은 응답 객체에 매긴다.
                fun confirmed() = snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED)
                fun retreat() = snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED)
                // 미리 읽기를 그 자리에서 돌린다 — 지금 읽기보다 먼저 요청이 나간다(가상 스레드에서 먼저 보낸 미리 읽기와 같은 순서).
                val robot = Scripted()
                HostRobotPort(robot, clock, java.util.concurrent.Executor { it.run() }).use { port ->
                    port.onFreshRead = { r, s, n, at -> leadership.observeFresh(r, s, n, at) }
                    port.onOtherRead = { r, s, n -> leadership.observePrefetched(r, s, n) }
                    port.unreadFallback = leadership::allowUnreadFallback
                    // 1라운드: 미리 읽기(확인) → 지금 읽기(확인) → 공개.
                    port.readNowFor(setOf(HUMANOID))
                    robot.push(confirmed(), confirmed())
                    port.prefetched(HUMANOID)
                    leadership.pendingReady().forEach(leadership::latch)
                    assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), name)
                    // 2라운드: 미리 읽기(후퇴, 지금 읽기보다 먼저 나감) → 지금 읽기(확인). 소비한 것은 1라운드의 미리 읽기다.
                    robot.push(retreat(), confirmed())
                    port.prefetched(HUMANOID)
                    assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), name)
                    // 3라운드: 2라운드의 후퇴 응답을 이제 소비한다 — 그 요청은 2라운드 지금 읽기보다 먼저 나갔으므로 풀지 않는다.
                    port.readNowFor(emptySet())
                    robot.push(retreat())
                    port.prefetched(HUMANOID)
                    assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), "$name: 늦게 소비한 옛 응답이 새 래치를 풀었다")
                    // 4라운드: 3라운드에 나간(지금 읽기보다 뒤) 후퇴 응답은 버리지 않는다.
                    robot.push(retreat())
                    port.prefetched(HUMANOID)
                    assertEquals(WaitReason.UNFENCED, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID), name).reason)
                }
            }
        }
    }

    @Test
    fun `관측 중 미리 읽은 값이 없는 미준비 기체는 첫 관측 뒤로는 간격마다 한 번만 동기로 읽고 회복하면 그 읽기로 열린다`() {
        var nanos = 0L
        holding { nanos }.use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            val robot = Scripted(fallback = snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED))
            // 미리 읽기가 끝나지 않는다(느린 수신자) — 늘 진행 중이라 `Unread` 다.
            val stuck = java.util.concurrent.Executor { }
            HostRobotPort(robot, HostClock { Instant.parse("2026-11-17T00:00:00Z") }, stuck).use { port ->
                port.onFreshRead = { r, s, n, at -> leadership.observeFresh(r, s, n, at) }
                port.onOtherRead = { r, s, n -> leadership.observePrefetched(r, s, n) }
                port.unreadFallback = leadership::allowUnreadFallback
                repeat(5) {
                    port.readNowFor(emptySet())
                    port.prefetched(HUMANOID)
                }
                assertEquals(1, robot.calls.get(), "미준비 기체를 매 라운드 동기로 읽었다")
                assertEquals(WaitReason.UNFENCED, assertIs<Readiness.Waiting>(leadership.readinessOf(HUMANOID)).reason)
                // 간격(10초) 안에서는 더 읽지 않는다.
                nanos += java.time.Duration.ofSeconds(9).toNanos()
                repeat(3) { port.readNowFor(emptySet()); port.prefetched(HUMANOID) }
                assertEquals(1, robot.calls.get(), "간격 안에서 동기로 읽었다")
                // 간격이 지나면 한 번 읽는다. 수신자가 회복해 확인으로 답하면 그 지금 읽기로 후보가 서고 열린다.
                nanos += java.time.Duration.ofSeconds(2).toNanos()
                robot.push(snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED))
                repeat(3) { port.readNowFor(emptySet()); port.prefetched(HUMANOID) }
                assertEquals(2, robot.calls.get(), "간격마다 한 번이 아니다")
                leadership.pendingReady().forEach(leadership::latch)
                assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID))
                // 지금 읽기 대상이면 이것과 관계없이 읽는다.
                port.readNowFor(setOf(HUMANOID))
                port.prefetched(HUMANOID)
                assertEquals(3, robot.calls.get())
            }
        }
    }

    @Test
    fun `미리 읽은 응답은 실제 미리 읽는 포트를 지나 순번을 달고 닿으며 그 순번은 뒤 지금 읽기보다 작다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            // 응답마다 새 객체다(같은 객체를 되돌려주면 가장 큰 순번이 붙는다 — 판정은 같다).
            val robot = Scripted().apply { repeat(4) { push(snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED)) } }
            val other = mutableListOf<Long?>()
            val fresh = mutableListOf<Long>()
            HostRobotPort(robot, HostClock { Instant.parse("2026-11-17T00:00:00Z") }, java.util.concurrent.Executor { it.run() }).use { port ->
                port.onFreshRead = { _, _, n, _ -> fresh += n }
                port.onOtherRead = { r, s, n -> if (s != null && r == HUMANOID) other += n }
                port.readNowFor(setOf(HUMANOID))
                port.prefetched(HUMANOID) // 미리 읽기(그 자리에서) → 지금 읽기
                port.prefetched(HUMANOID) // 앞 미리 읽기를 소비 → 지금 읽기
            }
            val prefetchedSeq = other.single()
            assertTrue(prefetchedSeq != null, "미리 읽은 응답의 순번을 잃었다(실제 미리 읽는 포트가 응답 객체를 바꿨을 수 있다)")
            assertTrue(prefetchedSeq < fresh.first(), "미리 읽기 $prefetchedSeq 가 뒤 지금 읽기 ${fresh.first()} 보다 작지 않다")
        }
    }

    @Test
    fun `같은 응답 객체를 두 요청이 돌려줘도 그 둘이 지금 읽기보다 앞이면 옛 응답으로 래치를 풀지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            val same = snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED)
            val sequenced = HostRobotPort.Sequenced(Scripted(fallback = same))
            sequenced.snapshot(HUMANOID)
            sequenced.snapshot(HUMANOID)
            val n = sequenced.number()
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), n, Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            val old = sequenced.prefetchedSeq(HUMANOID, same)
            assertEquals(2L, old, "같은 객체면 가장 큰 순번")
            leadership.observePrefetched(HUMANOID, same, old)
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID), "같은 객체의 옛 응답이 새 래치를 풀었다")
            // 소비 쪽 객체가 최근 슬롯에 없으면 순번을 모른다.
            assertEquals(null, sequenced.prefetchedSeq(HUMANOID, snapshot(0, FenceStatus.FENCE_STATUS_UNSPECIFIED)))
        }
    }

    @Test
    fun `지금 읽기가 미준비나 실패를 보이면 앞 미리 읽기의 확인 표시를 지워 답하지 않는 기체를 간격 안에서 다시 동기로 읽지 않는다`() {
        var nanos = 0L
        holding { nanos }.use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            val robot = Scripted() // 비면 null(답 없음)
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            fun drain() = Thread { while (true) (queued.poll() ?: break).run() }.apply { start(); join() }
            val test = Thread.currentThread()
            val direct = java.util.concurrent.atomic.AtomicInteger()
            val counting = object : dev.picasso.middleware.RobotPort by robot {
                override fun snapshot(robotId: String): RobotSnapshot? {
                    if (Thread.currentThread() == test) direct.incrementAndGet()
                    return robot.snapshot(robotId)
                }
            }
            HostRobotPort(counting, HostClock { Instant.parse("2026-11-17T00:00:00Z") }, java.util.concurrent.Executor { queued += it }).use { port ->
                port.onFreshRead = { r, s, n, at -> leadership.observeFresh(r, s, n, at) }
                port.onOtherRead = { r, s, n -> leadership.observePrefetched(r, s, n) }
                port.unreadFallback = leadership::allowUnreadFallback
                fun round() {
                    port.readNowFor(leadership.freshTargets())
                    port.prefetched(HUMANOID)
                }
                // 1라운드: 첫 관측(지금 읽기, 답 없음). 미리 읽기 하나가 떠 있다.
                round()
                assertEquals(1, direct.get())
                // 미리 읽기가 확인으로 끝난다 → 2라운드에 소비해 확인 표시가 선다(지금 읽기 대상이 된다).
                robot.push(snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED))
                drain()
                round()
                assertEquals(setOf(HUMANOID), leadership.freshTargets())
                // 3라운드: 지금 읽기가 답하지 않는다(null) → 확인 표시를 지운다.
                round()
                assertEquals(2, direct.get())
                assertEquals(emptySet(), leadership.freshTargets())
                // 그 뒤 미리 읽기도 답이 없다(null). 간격 안에서는 동기로 다시 읽지 않는다.
                repeat(5) {
                    drain()
                    round()
                }
                assertEquals(2, direct.get(), "답하지 않는 미준비 기체를 간격 안에서 다시 동기로 읽었다")
                // 간격이 지나면 한 번 읽는다.
                nanos += java.time.Duration.ofSeconds(11).toNanos()
                round()
                assertEquals(3, direct.get())
            }
        }
    }

    @Test
    fun `미리 읽기가 순번 모름이나 미확정만 내는 미준비 기체도 간격 뒤에는 지금 읽기 대상이 되어 열릴 수 있고 순번 모름은 세어 보인다`() {
        var nanos = 0L
        holding { nanos }.use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(HUMANOID), fleet = false))
            // 첫 관측(지금 읽기): 미확정.
            assertEquals(setOf(HUMANOID), leadership.freshTargets())
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING), seq.incrementAndGet(), Instant.EPOCH)
            assertEquals(emptySet(), leadership.freshTargets())
            // 미리 읽기는 `Read` 로 오지만 순번 모름(확인을 담았어도 쓰지 않음)이 이어진다.
            repeat(3) { leadership.observePrefetched(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), null) }
            assertEquals(emptySet(), leadership.freshTargets())
            assertEquals(3L, leadership.unknownSeqResponses(HUMANOID))
            assertFalse(leadership.mayReadNow(HUMANOID))
            // 간격이 지나면 지금 읽기 대상이 된다. 그 지금 읽기가 확인이면 열린다.
            nanos += java.time.Duration.ofSeconds(11).toNanos()
            assertEquals(setOf(HUMANOID), leadership.freshTargets())
            assertTrue(leadership.mayReadNow(HUMANOID))
            leadership.observeFresh(HUMANOID, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            assertIs<Readiness.Ready>(leadership.readinessOf(HUMANOID))
        }
    }

    /** 정해 둔 응답을 차례로 내는 기체 포트. 비면 [fallback] 을 낸다. */
    private class Scripted(private val fallback: RobotSnapshot? = null) : dev.picasso.middleware.RobotPort {
        private val next = java.util.ArrayDeque<RobotSnapshot>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        fun push(vararg snapshots: RobotSnapshot) = synchronized(next) { next.addAll(snapshots) }
        override fun snapshot(robotId: String): RobotSnapshot? = synchronized(next) { calls.incrementAndGet(); next.pollFirst() ?: fallback }
        override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
            throw UnsupportedOperationException()
        override fun watch(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = emptyList<dev.picasso.contracts.v1.WatchTaskResponse>()
        override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = throw UnsupportedOperationException()
    }

    @Test
    fun `수신자 재시작 의심 보류가 있는 기체는 관측 중이든 발행 중이든 출발만 막히고 취소는 지나며 권위는 의심 머리로 쥐지 않고 해제하면 풀린다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(QUADRUPED), fleet = false))
            val sent = mutableListOf<String>()
            val intents = mutableListOf<String>()
            leadership.startIntent = { robot, task, revision -> intents += "$robot $task $revision" }
            val gated = GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent), HUMANOID to Recording(sent))), leadership)
            val cancel = dev.picasso.contracts.v1.TaskHandle.newBuilder().setTaskId("JO-1#T1").setRevision(1).setRobotId(QUADRUPED).build()

            // 래치된 기체에 후보 하나 — 겉으로는 재시작 의심(밑의 판정이 준비), 근거 없이 출발만 막힘.
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            val hold = RestartHolds.Hold(QUADRUPED, "JO-1", "exec-1", "T2.travel", 0, "JO-1#T2.travel", 1, 7, 3)
            leadership.restartHolds.hold(listOf(hold))
            assertEquals(WaitReason.RESTART_SUSPECT, (leadership.readinessOf(QUADRUPED) as Readiness.Waiting).reason)
            assertFalse(leadership.mayStart(QUADRUPED))
            assertTrue(leadership.mayIssue(QUADRUPED), "재시작 의심 보류는 취소의 송신 직전 확인을 막지 않는다")
            assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-1#T3", 1, "navigate_to", emptyMap()) }
            assertIs<dev.picasso.middleware.Issue.NotSent>(gated.issue(QUADRUPED, "JO-1#T3", 1, "navigate_to", emptyMap()))
            gated.cancel(QUADRUPED, cancel)
            assertEquals(listOf("cancel JO-1#T1"), sent)
            assertEquals(emptyList(), intents, "막힌 출발의 송신 의도를 적었다")
            // 관측 중 대조 중인 후보는 인수 확인 대기의 겉으로 실린다.
            val holdingRefusal = assertIs<Authority.NotHeld>(leadership.authorityOf(QUADRUPED))
            assertTrue(holdingRefusal.reason.startsWith(HostLeadership.AWAITING_CONFIRMATION), holdingRefusal.reason)
            assertTrue(WaitReason.RESTART_SUSPECT.text in holdingRefusal.reason, holdingRefusal.reason)

            // 발행 중(운영자 확인 뒤)에도 그대로 막히고 권위는 의심 머리로 쥐지 않는다. 다른 기체는 쥔다.
            assertTrue(leadership.confirmed())
            assertEquals(LeaderState.ISSUING, leadership.state)
            val issuing = assertIs<Authority.NotHeld>(leadership.authorityOf(QUADRUPED))
            assertTrue(issuing.reason.startsWith(HostLeadership.RESTART_SUSPECT_HELD), issuing.reason)
            assertTrue("JO-1#T2.travel" in issuing.reason, issuing.reason)
            assertEquals(Authority.Held, leadership.authorityOf(HUMANOID))
            assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-1#T3", 1, "navigate_to", emptyMap()) }
            gated.start(HUMANOID, "JO-9#T1", 2, "navigate_to", emptyMap())
            assertEquals(listOf("$HUMANOID JO-9#T1 2"), intents)
            gated.cancel(QUADRUPED, cancel)
            assertEquals(listOf("cancel JO-1#T1", "start JO-9#T1", "cancel JO-1#T1"), sent)

            // 적격 읽기에서 안 보여 의심으로 확정 → 저장 → 해제하면 풀린다.
            assertTrue(leadership.restartHolds.observe(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), mine))
            leadership.restartHolds.persisted("JO-1#T2.travel", 11, mine)
            assertTrue(leadership.restartHolds.suspected(QUADRUPED))
            assertEquals(null, leadership.restartHolds.release(12))
            assertEquals("JO-1#T2.travel", leadership.restartHolds.release(11)?.taskId)
            assertTrue(leadership.mayStart(QUADRUPED))
            assertEquals(Authority.Held, leadership.authorityOf(QUADRUPED))
            gated.start(QUADRUPED, "JO-1#T3", 1, "navigate_to", emptyMap())
            assertEquals(listOf("$HUMANOID JO-9#T1 2", "$QUADRUPED JO-1#T3 1"), intents)
        }
    }

    @Test
    fun `송신 의도는 바깥 관문과 안쪽 관문을 함께 지나도 한 번만 적고 적지 못하면 위임하지 않는다`() {
        holding().use { leadership ->
            assertTrue(leadership.confirmed())
            val sent = mutableListOf<String>()
            val intents = mutableListOf<String>()
            leadership.startIntent = { _, task, _ -> intents += task }
            val outer = GatedRobotPort(GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent))), leadership), leadership)
            outer.start(QUADRUPED, "JO-1#T1", 1, "navigate_to", emptyMap())
            assertEquals(listOf("JO-1#T1"), intents)
            assertEquals(listOf("start JO-1#T1"), sent)
            leadership.startIntent = { _, _, _ -> throw CommandNotSent("주입: 의도 쓰기 실패") }
            assertFailsWith<CommandNotSent> { outer.start(QUADRUPED, "JO-1#T2", 1, "navigate_to", emptyMap()) }
            assertEquals(listOf("start JO-1#T1"), sent, "송신 의도를 적지 못했는데 보냈다")
            // 다음 출발은 다시 적는다(앞 실패가 스레드 표시를 남기지 않음).
            leadership.startIntent = { _, task, _ -> intents += task }
            outer.start(QUADRUPED, "JO-1#T3", 1, "navigate_to", emptyMap())
            assertEquals(listOf("JO-1#T1", "JO-1#T3"), intents)
        }
    }

    @Test
    fun `송신 의도를 커밋한 뒤 위임 직전에 권위와 기체 보류를 다시 보아 그 사이 보류가 서거나 리스를 잃으면 보내지 않는다`() {
        holding().use { leadership ->
            assertTrue(leadership.confirmed())
            val sent = mutableListOf<String>()
            val gated = GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent))), leadership)
            // 의도 커밋 중에 늦은 복원이 그 기체에 보류를 세웠다.
            leadership.startIntent = { robot, task, _ ->
                leadership.restartHolds.hold(listOf(RestartHolds.Hold(robot, "JO-L", "exec-9", "T1", 0, "JO-L#T1", 1, 1, null)))
                assertTrue(task.isNotEmpty())
            }
            val held = assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-1#T1", 1, "navigate_to", emptyMap()) }
            assertTrue(held.message!!.startsWith(dev.picasso.ops.host.lease.SendAuthority.RESTART_HELD), held.message)
            assertEquals(emptyList(), sent)
            leadership.restartHolds.closeIssued("JO-L#T1")
            // 의도 커밋 중에 리스를 잃었다(자기 차단 기한·밀려남과 같은 자리).
            leadership.startIntent = { _, _, _ -> leadership.lost("주입: 의도 커밋 중 리스를 잃음") }
            val lost = assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-1#T2", 1, "navigate_to", emptyMap()) }
            assertTrue(lost.message!!.startsWith(dev.picasso.ops.host.lease.SendAuthority.NOT_ISSUING), lost.message)
            assertEquals(emptyList(), sent, "의도 커밋 뒤 권위를 잃었는데 보냈다")
        }
    }

    @Test
    fun `관측 중 수신자 재시작 의심 보류가 막는 기체는 미리 읽기가 확인을 보여도 매 pump 지금 읽지 않고 간격 회복으로만 읽으며 보류가 풀리면 다시 대상이 된다`() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        holding(clock::get).use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(QUADRUPED), fleet = false))
            leadership.restartHolds.hold(listOf(RestartHolds.Hold(QUADRUPED, "JO-1", "exec-1", "T1", 0, "JO-1#T1", 1, 1, null)))
            // 첫 관측(간격 회복 차례)은 한 번 지금 읽는다.
            assertEquals(setOf(QUADRUPED), leadership.freshTargets())
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::holdBack)
            // 그 뒤 미리 읽기가 확인을 보여도 간격 안에서는 지금 읽기 대상이 아니다.
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertEquals(emptySet(), leadership.freshTargets(), "보류 기체를 매 pump 잠금 아래 지금 읽기 대상에 넣었다")
            assertFalse(leadership.mayReadNow(QUADRUPED))
            assertEquals(WaitReason.RESTART_SUSPECT, (leadership.readinessOf(QUADRUPED) as Readiness.Waiting).reason)
            // 간격이 지나면 한 번 읽는다(미리 읽기가 끝나지 않는 기체에도 판별·래치의 길).
            clock.addAndGet(HostLeadership.DEFAULT_UNREAD_RETRY.toNanos() + 1)
            assertEquals(setOf(QUADRUPED), leadership.freshTargets())
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::holdBack)
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            // 보류가 풀리면 미리 읽기 확인으로 다시 대상이 된다.
            leadership.restartHolds.closeIssued("JO-1#T1")
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertTrue(leadership.mayReadNow(QUADRUPED))
            assertEquals(setOf(QUADRUPED), leadership.freshTargets())
        }
    }

    @Test
    fun `보류 기체는 래치됐든 발행 중이든 매 pump 지금 읽지 않고 간격 회복만 하며 한 pump 의 회복 읽기는 보류 기체 하나다`() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        holding(clock::get).use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(QUADRUPED, HUMANOID), fleet = false))
            // 두 기체 모두 래치된 뒤 보류가 생겼다.
            listOf(QUADRUPED, HUMANOID).forEach { robot ->
                leadership.observeFresh(robot, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            }
            leadership.pendingReady().forEach(leadership::latch)
            leadership.restartHolds.hold(
                listOf(QUADRUPED, HUMANOID).map { RestartHolds.Hold(it, "JO-1", "exec-1", "T-$it", 0, "JO-1#T-$it", 1, 1, null) },
            )
            clock.addAndGet(HostLeadership.DEFAULT_UNREAD_RETRY.toNanos() + 1)
            leadership.beginRound()
            // 간격이 지났어도 한 pump 에 보류 기체 하나만 회복 읽기를 한다.
            assertEquals(1, listOf(QUADRUPED, HUMANOID).count { leadership.mayReadNow(it) })
            val read = listOf(QUADRUPED, HUMANOID).single { leadership.mayReadNow(it) }
            leadership.observeFresh(read, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.beginRound()
            assertFalse(leadership.mayReadNow(read), "래치된 보류 기체를 간격 안에서 다시 지금 읽었다")
            // 발행 중에도 같다.
            assertTrue(leadership.confirmed())
            leadership.beginRound()
            assertFalse(leadership.mayReadNow(read))
            assertFalse(leadership.allowUnreadFallback(read))
            leadership.observeFresh(read, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.beginRound()
            assertFalse(leadership.mayReadNow(read), "발행 중 보류 기체를 매 pump 지금 읽었다")
            // 보류가 풀리면 발행 중에는 늘 읽는다(오늘 거동).
            leadership.restartHolds.closeIssued("JO-1#T-$read")
            assertTrue(leadership.mayReadNow(read))
        }
    }

    @Test
    fun `보류 기체 셋은 pump 마다 가장 오래 회복 읽기를 못 한 기체가 몫을 받아 차례가 돌고 발행 중에도 출발을 앞두지 않은 기체가 몫을 받는다`() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        holding(clock::get).use { leadership ->
            val mine = leadership.lease.generation!!
            val robots = listOf(QUADRUPED, HUMANOID, "robot-3")
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", robots.toSet(), fleet = false))
            leadership.restartHolds.hold(robots.map { RestartHolds.Hold(it, "JO-1", "exec-1", "T-$it", 0, "JO-1#T-$it", 1, 1, null) })
            assertTrue(leadership.confirmed())
            val turns = mutableListOf<String>()
            repeat(3) {
                clock.addAndGet(HostLeadership.DEFAULT_UNREAD_RETRY.toNanos() + 1)
                leadership.beginRound()
                val target = leadership.heldRecoveryTargets().single()
                // 같은 pump 의 다른 보류 기체는 몫이 없다.
                assertEquals(listOf(target), robots.filter { leadership.mayReadNow(it) })
                turns += target
                leadership.observeFresh(target, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            }
            assertEquals(robots.toSet(), turns.toSet(), "보류 기체의 회복 차례가 돌지 않았다: $turns")
        }
    }

    @Test
    fun `읽히지 않는 보류 기체는 회복 몫을 잡지 않아 도는 실행의 보류 기체가 미리 읽기가 계속 실패해도 실제 기체 포트로 간격마다 회복 읽기를 받는다`() {
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        holding(clock::get).use { leadership ->
            // A(humanoid-01): 도는 실행이 없는 보류 기체(종결·포기 행의 후보 따위 — 미들웨어가 읽지 않음). B(quadruped-01): 도는 실행의 보류 기체.
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(QUADRUPED), fleet = false))
            leadership.restartHolds.hold(
                listOf(
                    RestartHolds.Hold(HUMANOID, "JO-OLD", null, "T1", 0, "JO-OLD#T1", 1, 1, null),
                    RestartHolds.Hold(QUADRUPED, "JO-1", "exec-1", "T1", 0, "JO-1#T1", 1, 1, null),
                ),
            )
            assertTrue(leadership.confirmed())
            val fresh = mutableListOf<String>()
            // B 의 읽기는 모두 실패한다(미리 읽기 실패가 이어짐).
            val failing = object : dev.picasso.middleware.RobotPort {
                override fun snapshot(robotId: String): RobotSnapshot? = throw IllegalStateException("주입: 읽기 실패")
                override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
                override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
                    throw UnsupportedOperationException()
                override fun watch(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = emptyList<dev.picasso.contracts.v1.WatchTaskResponse>()
                override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = throw UnsupportedOperationException()
            }
            HostRobotPort(failing, HostClock { Instant.parse("2026-11-17T00:00:00Z") }, java.util.concurrent.Executor { it.run() }).use { port ->
                port.onFreshRead = { r, s, n, at -> fresh += r; leadership.observeFresh(r, s, n, at) }
                port.onOtherRead = { r, s, n -> leadership.observePrefetched(r, s, n) }
                port.unreadFallback = leadership::allowUnreadFallback
                repeat(3) {
                    clock.addAndGet(HostLeadership.DEFAULT_UNREAD_RETRY.toNanos() + 1)
                    // pump 한 번 — 실행 호스트와 같은 순서(몫을 새로 셈 → 지금 읽을 기체 → 미들웨어가 도는 실행의 기체를 읽음).
                    leadership.beginRound()
                    port.readNowFor(leadership.heldRecoveryTargets())
                    port.prefetched(QUADRUPED)
                }
            }
            assertEquals(listOf(QUADRUPED, QUADRUPED, QUADRUPED), fresh, "읽히지 않는 보류 기체가 회복 몫을 잡았다")
        }
    }

    @Test
    fun `붙음 보류는 송신 의도 커밋 뒤 재확인을 지나야 닫히고 재확인에서 막혀 보내지 않으면 남는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            assertTrue(leadership.confirmed())
            val sent = mutableListOf<String>()
            val gated = GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to Recording(sent))), leadership)
            leadership.restartHolds.hold(listOf(RestartHolds.Hold(QUADRUPED, "JO-1", "exec-1", "T1", 0, "JO-1#T1", 1, 1, null)))
            leadership.restartHolds.observe(
                QUADRUPED,
                snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED).copy(tasks = mapOf("JO-1#T1" to dev.picasso.contracts.v1.TaskState.TASK_STATE_RUNNING), revisions = mapOf("JO-1#T1" to 1)),
                seq.incrementAndGet(), mine, issuing = true,
            )
            assertFalse(leadership.restartHolds.blocks(QUADRUPED))
            // 의도는 커밋됐는데 재확인에서 리스를 잃어 보내지 않았다 — 붙음 보류는 남는다.
            leadership.startIntent = { _, _, _ -> leadership.lost("주입: 의도 커밋 중 리스를 잃음") }
            assertFailsWith<CommandNotSent> { gated.start(QUADRUPED, "JO-1#T1", 1, "inspect", emptyMap()) }
            assertEquals(emptyList(), sent)
            assertTrue(leadership.restartHolds.has("JO-1#T1"), "보내지 않은 출발로 붙음 보류를 닫았다")
        }
    }

    @Test
    fun `래치 뒤 요청 순번상 가장 뒤의 성공한 응답이 같은 세대 DURABLE_PENDING 이면 표시만 하고 읽기 실패와 늦은 앞 순번 응답은 덮지 않는다`() {
        holding().use { leadership ->
            val mine = leadership.lease.generation!!
            leadership.requirements = listOf(Requirement("exec-1", "JO-1", setOf(QUADRUPED), fleet = false))
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            leadership.pendingReady().single().let(leadership::latch)
            assertFalse(leadership.latchedPending(QUADRUPED))
            val early = seq.incrementAndGet()
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING), seq.incrementAndGet(), Instant.EPOCH)
            assertTrue(leadership.latchedPending(QUADRUPED))
            assertIs<Readiness.Ready>(leadership.readinessOf(QUADRUPED))
            leadership.observeFresh(QUADRUPED, null, seq.incrementAndGet(), Instant.EPOCH)
            assertTrue(leadership.latchedPending(QUADRUPED), "읽기 실패가 표시를 덮었다")
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), early)
            assertTrue(leadership.latchedPending(QUADRUPED), "늦게 소비한 앞 순번 응답이 표시를 덮었다")
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet())
            assertFalse(leadership.latchedPending(QUADRUPED))
            // 발행 중에도 따라간다.
            assertTrue(leadership.confirmed())
            leadership.observePrefetched(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_PENDING), seq.incrementAndGet())
            assertTrue(leadership.latchedPending(QUADRUPED), "발행 중 같은 세대 미확정을 따라가지 않았다")
            leadership.observeFresh(QUADRUPED, snapshot(mine, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), seq.incrementAndGet(), Instant.EPOCH)
            assertFalse(leadership.latchedPending(QUADRUPED))
        }
    }

    /**
     * 만료 인수로 관측에 든 리더(세대 5). 앞 보유자(세대 4)는 멈춤을 적지 않았고 다른 인스턴스의 심장박동도 살아 있다. 세대를 1 보다 크게 두어 «0 이
     * 아닌 낮은 바닥값» 을 만들 수 있게 한다.
     */
    private fun holding(nanoTime: () -> Long = System::nanoTime): HostLeadership {
        Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('mw-other', clock_timestamp() + interval '1 hour')")
        Fenced.execute("UPDATE mission.host_lease SET holder = 'mw-old', generation = 4 WHERE role = 'host'")
        val leadership = HostLeadership(HostLease("mw-b", TIMING, PLAIN), TIMING, HostExit {}, HostLeadership.DEFAULT_UNREAD_RETRY, nanoTime)
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
        /** 주기 0.3초·만료 3초·여유 1초 — 시험 동안 갱신이 리스를 쥔다. */
        val TIMING = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))

        val PLAIN = LeaseConnections { PostgresSupport.connection() }

        fun snapshot(floor: Long, status: FenceStatus) = RobotSnapshot(
            sequence = 1, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap(),
            controllerFloor = floor, fenceStatus = status,
        )
    }
}
