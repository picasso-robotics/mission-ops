package dev.picasso.ops.host

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.TaskState
import dev.picasso.middleware.JudgmentRecord
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.middleware.UnitRecord
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.UnitVerdict
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.Verification
import dev.picasso.ops.host.MissionHost.Companion.RestartUnit
import dev.picasso.ops.host.lease.RestartHolds
import dev.picasso.ops.host.store.IntentBasis
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 수신자 재시작 의심 보류(HA 후속 D1 완화)의 판별 규칙을 호스트 없이 본다 — 후보 고르기([MissionHost.restartCandidates])와 붙음·적격 읽기·해제
 * ([RestartHolds]). 실제 수신자로 보는 것은 [HostRestartHoldTest]·[HostReceiverTest] 다.
 */
class RestartHoldsTest {

    @Test
    fun `후보는 판정 없는 같은 시도의 기체 단위 가운데 요청 흔적이나 송신 의도가 있는 것이고 판정·플릿·다른 시도·흔적 없음·해제가 덮은 것은 빠진다`() {
        val at = Instant.parse("2026-11-24T00:00:00Z")
        val verdict = UnitVerdict(UnitState.DONE, Evidence.E0, Verification.NOT_REQUESTED, null, null, null, null)
        val record = JudgmentRecord(
            jobOrderId = "JO-1", orderVersion = 1, acknowledgedFaults = emptyList(), approval = null, cancelRequested = false,
            units = listOf(
                UnitRecord("T1.travel", 0, "JO-1#T1.travel", at, null, verdict, null),
                UnitRecord("T1", 0, "JO-1#T1", at, null, null, null),
                UnitRecord("T2.travel", 0, "JO-1#T2.travel", at, null, null, null),
                UnitRecord("T2", 1, "JO-1#T2@r1", at, null, null, null),
                UnitRecord("T3.travel", 0, "", null, null, null, null),
                UnitRecord("T3", 0, "", null, null, null, null),
                UnitRecord("F1", 0, "JO-1#F1", at, null, null, null),
            ),
        )
        val units = listOf(
            RestartUnit("T1.travel", 0, "quadruped-01", robot = true), // 판정 있음
            RestartUnit("T1", 0, "quadruped-01", robot = true), // 요청 흔적만(송신 의도 없음 — 리비전 모름)
            RestartUnit("T2.travel", 0, "quadruped-01", robot = true), // 해제가 덮음
            RestartUnit("T2", 2, "quadruped-01", robot = true), // 기록은 다른 시도
            RestartUnit("T3.travel", 0, "humanoid-01", robot = true), // 기록 흔적 없음, 송신 의도만(판단 기록 미커밋)
            RestartUnit("T3", 0, "quadruped-01", robot = true), // 흔적 없음
            RestartUnit("F1", 0, "quadruped-01", robot = false), // 플릿
        )
        val intents = mapOf("JO-1#T2.travel" to IntentBasis(5, 1), "JO-1#T3.travel" to IntentBasis(9, 2), "JO-1#F1" to IntentBasis(3, 1))
        val candidates = MissionHost.restartCandidates("JO-1", "exec-2", units, record, 42, intents, mapOf("JO-1#T2.travel" to 5L)) { false }
        assertEquals(listOf("JO-1#T1", "JO-1#T3.travel"), candidates.map { it.taskId })
        candidates.single { it.taskId == "JO-1#T1" }.let {
            assertNull(it.revision)
            assertEquals(0L, it.basisIntentId)
            assertEquals(42L, it.basisRecordId)
        }
        candidates.single { it.taskId == "JO-1#T3.travel" }.let {
            assertEquals("humanoid-01", it.robotId, "단위의 기체(robotOf)를 쓰지 않았다")
            assertEquals(2, it.revision)
            assertEquals(9L, it.basisIntentId)
        }
        // 해제가 그 뒤의 새 송신 의도를 덮지 않는다 — 새 판정.
        val renewed = MissionHost.restartCandidates("JO-1", "exec-2", units, record, 42, intents + ("JO-1#T2.travel" to IntentBasis(6, 1)), mapOf("JO-1#T2.travel" to 5L)) { false }
        assertTrue("JO-1#T2.travel" in renewed.map { it.taskId })
        // 이미 보류가 있으면 빠진다.
        assertEquals(listOf("JO-1#T3.travel"), MissionHost.restartCandidates("JO-1", null, units, record, null, intents, emptyMap()) { it != "JO-1#T3.travel" }.map { it.taskId }.filter { it == "JO-1#T3.travel" })
        // 재작업 시도의 태스크 id 식.
        assertEquals("JO-1#T2@r1", MissionHost.restartTaskId("JO-1", "T2", 1))
        assertEquals("JO-1#T2", MissionHost.restartTaskId("JO-1", "T2", 0))
    }

    @Test
    fun `요청 순번상 뒤의 응답으로만 판정하고 붙음은 막지 않되 다시 보며 적격 읽기에서 사라지면 의심이 되고 낮은 리비전·리비전 모름은 붙음이 아니다`() {
        val holds = RestartHolds()
        holds.hold(listOf(hold("JO-1#A", 2), hold("JO-1#B", 2), hold("JO-1#C", null)).map { it.copy(afterSeq = 10) })
        assertTrue(holds.blocks(Q))
        // 후보를 세운 때 이하 순번의 응답(늦게 소비한 옛 응답)·순번 모름은 판정에 쓰지 않는다.
        assertFalse(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 10, 5))
        assertFalse(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), null, 5))
        assertEquals(setOf(RestartHolds.State.PENDING), holds.holds().map { it.state }.toSet())
        // 적격 아닌 읽기(DURABLE_PENDING): A 는 같은 리비전으로 붙음(막지 않음), B 낮은 리비전·C 리비전 모름은 후보 그대로.
        assertFalse(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_PENDING, "JO-1#A" to 2, "JO-1#B" to 1, "JO-1#C" to 1), 11, 5))
        assertEquals(mapOf("JO-1#A" to RestartHolds.State.ATTACHED, "JO-1#B" to RestartHolds.State.PENDING, "JO-1#C" to RestartHolds.State.PENDING), states(holds))
        assertTrue(holds.blocks(Q))
        // 다른 세대의 확인 답(바닥값 불일치)은 적격이 아니다.
        assertFalse(holds.observe(Q, snap(4, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 2), 12, 5))
        assertFalse(holds.suspected(Q))
        // 적격 읽기: A 는 붙음 그대로, B 낮은 리비전 → 의심(ABSENT), C 리비전 모름 → 의심(REVISION_UNKNOWN).
        assertTrue(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 2, "JO-1#B" to 1, "JO-1#C" to 3), 13, 5))
        assertEquals(RestartHolds.State.ATTACHED, holds.holds().single { it.taskId == "JO-1#A" }.state)
        assertEquals(RestartHolds.Basis.ABSENT, holds.holds().single { it.taskId == "JO-1#B" }.basis)
        assertEquals(RestartHolds.Basis.REVISION_UNKNOWN, holds.holds().single { it.taskId == "JO-1#C" }.basis)
        // 앞 순번의 «없음» 응답은 뒤의 붙음을 거스르지 않는다.
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 12, 5)
        assertEquals(RestartHolds.State.ATTACHED, holds.holds().single { it.taskId == "JO-1#A" }.state)
        // 붙음 뒤 수신자가 다시 떠 적격 읽기에서 사라지면 의심이 된다(붙음을 지우지 않고 다시 본다).
        assertTrue(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 14, 5))
        assertEquals(RestartHolds.State.SUSPECT, holds.holds().single { it.taskId == "JO-1#A" }.state)
        // 저장된 의심은 뒤에 같은 리비전 이상으로 붙어 보이면 시스템 해제 차례가 된다(리비전 모름은 붙을 수 없다).
        holds.persisted("JO-1#B", 7, 5)
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#B" to 2, "JO-1#C" to 9), 15, 5)
        assertEquals(listOf("JO-1#B"), holds.systemReleases().map { it.taskId })
        assertTrue(holds.blocks(Q), "시스템 해제를 적기 전에 기체를 열었다")
        assertEquals("JO-1#B", holds.release(7)?.taskId)
        // 저장 전의 의심이 붙어 보이면 붙음으로 되돌린다.
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 3), 16, 5)
        assertEquals(RestartHolds.State.ATTACHED, holds.holds().single { it.taskId == "JO-1#A" }.state)
        // 이 세대가 그 태스크의 출발 의도를 커밋하면 붙음·후보는 닫히고 의심은 닫히지 않는다.
        holds.closeIssued("JO-1#A")
        holds.closeIssued("JO-1#C")
        assertEquals(listOf("JO-1#C"), holds.holds().map { it.taskId })
    }

    @Test
    fun `시스템 해제 예약은 더 새 응답이 부재를 보이면 거두고 시스템 해제 뒤에도 붙음으로 남아 다시 사라지면 의심이 된다`() {
        val holds = RestartHolds()
        holds.load(listOf(hold("JO-1#A", 2).copy(suspicionId = 7, generation = 4, afterSeq = 10)))
        // 미리 읽기 N 이 붙음을 보여 시스템 해제를 예약했는데 지금 읽기 N+1 이 수신자 재시작 뒤 부재를 보인다 — 예약을 거두고 막은 채 둔다.
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 2), 20, 5)
        assertEquals(listOf("JO-1#A"), holds.systemReleases().map { it.taskId })
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 21, 5)
        assertTrue(holds.systemReleases().isEmpty(), "더 새 응답의 부재를 거스르고 시스템 해제를 예약한 채 두었다")
        assertTrue(holds.blocks(Q))
        // 앞 순번(늦게 소비한) 붙음은 예약을 되살리지 않는다.
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 2), 20, 5)
        assertTrue(holds.systemReleases().isEmpty())
        // 다시 붙음 → 예약 → 시스템 해제 커밋 → 붙음으로 남음(막지 않음, 의심 ID 없음).
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, "JO-1#A" to 2), 22, 5)
        holds.systemReleased(7)
        assertEquals(RestartHolds.State.ATTACHED, holds.holds().single().state)
        assertNull(holds.holds().single().suspicionId)
        assertFalse(holds.blocks(Q))
        // 이 세대가 그 출발을 내기 전에 수신자가 다시 떠 적격 읽기에서 사라지면 다시 의심(새로 적을 것).
        assertTrue(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED), 23, 5))
        assertTrue(holds.blocks(Q))
        assertEquals(listOf("JO-1#A"), holds.unpersisted().map { it.taskId })
    }

    @Test
    fun `다시 대조할 후보는 막고 다시 지은 실행의 후보가 갈아 끼우며 모든 기체를 막는 표시는 어느 기체든 막는다`() {
        val holds = RestartHolds()
        holds.loadRejudged(listOf(hold("JO-1#A", 1).copy(executionId = null, state = RestartHolds.State.SUSPECT, suspicionId = 3)))
        assertEquals(RestartHolds.State.PENDING, holds.holds().single().state)
        assertNull(holds.holds().single().suspicionId)
        assertTrue(holds.blocks(Q))
        // 다시 지은 실행의 후보가 갈아 끼운다(그 실행의 출발·판정으로 닫히게). 실행 id 가 있는 보류는 갈아 끼우지 않는다.
        holds.hold(listOf(hold("JO-1#A", 1).copy(executionId = "exec-2")))
        assertEquals("exec-2", holds.holds().single().executionId)
        holds.hold(listOf(hold("JO-1#A", 1).copy(executionId = "exec-3")))
        assertEquals("exec-2", holds.holds().single().executionId)
        // 모든 기체를 막는 표시.
        val all = RestartHolds()
        all.deferHold("JO-9", setOf(RestartHolds.ALL_ROBOTS))
        assertTrue(all.blocks("humanoid-01"))
        assertTrue(all.blocks(Q))
        assertTrue(all.describe("humanoid-01")!!.contains("JO-9"))
        assertEquals(emptySet(), all.blockedRobots())
        all.clearDeferred("JO-9")
        assertFalse(all.blocks(Q))
    }

    @Test
    fun `대조 안 하는 수신자·VOLATILE 은 뒤의 성공한 읽기에서 판정하고 발행 중 같은 세대 DURABLE_PENDING 은 근거 종류를 남겨 의심이 되며 판정을 얻은 단위는 닫힌다`() {
        val holds = RestartHolds()
        holds.hold(listOf(hold("JO-1#D", 1, robot = "humanoid-01"), hold("JO-2#A", 1, robot = "volatile-01"), hold("JO-3#A", 1), hold("JO-4#A", 1)))
        assertTrue(holds.observe("humanoid-01", snap(0, FenceStatus.FENCE_STATUS_UNSPECIFIED), 1, 5))
        assertTrue(holds.observe("volatile-01", snap(5, FenceStatus.FENCE_STATUS_VOLATILE), 2, 5))
        // 관측 중 같은 세대 DURABLE_PENDING 은 적격이 아니고(벤더 호출에 걸린 접수를 기다림), 발행 중이면 적격이다.
        assertFalse(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_PENDING), 3, 5, issuing = false))
        assertTrue(holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_PENDING), 4, 5, issuing = true))
        assertEquals(setOf(RestartHolds.Basis.ISSUING_PENDING), holds.holds().filter { it.robotId == Q }.map { it.basis }.toSet())
        // 판정을 얻은 단위의 붙음·후보는 닫힌다.
        val other = RestartHolds()
        other.hold(listOf(hold("JO-5#A", 1), hold("JO-5#B", 1)))
        other.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_PENDING, "JO-5#A" to 1), 1, 5)
        other.closeVerdicts(setOf("JO-5#A", "JO-5#B"))
        assertTrue(other.holds().isEmpty())
        assertFalse(other.blocks(Q))
    }

    @Test
    fun `해제는 저장된 의심 ID 로만 닫고 두 의심 중 하나만 풀면 기체는 막힌 채이며 미룬 복원은 그 기체들을 막고 구동 끝난 실행의 후보는 근거를 남겨 의심이 된다`() {
        val holds = RestartHolds()
        holds.load(listOf(hold("JO-1#A", 1).copy(suspicionId = 1, generation = 3), hold("JO-1#B", 1).copy(suspicionId = 2, generation = 3)))
        assertTrue(holds.suspected(Q))
        assertNull(holds.release(99))
        assertEquals("JO-1#A", holds.release(1)?.taskId)
        assertTrue(holds.blocks(Q), "의심 하나가 남았는데 기체를 열었다")
        assertEquals("JO-1#B", holds.release(2)?.taskId)
        assertFalse(holds.blocks(Q))
        // 미룬 복원 — 일지 행의 기체와 송신 의도의 기체 모두.
        holds.deferHold("JO-9", setOf(Q, "humanoid-01"))
        assertTrue(holds.blocks(Q))
        assertTrue(holds.blocks("humanoid-01"))
        assertTrue(holds.describe(Q)!!.contains("JO-9"))
        holds.clearDeferred("JO-9")
        assertFalse(holds.blocks(Q))
        // 구동이 끝난 실행: 후보는 의심(DRIVE_ENDED)으로, 붙음은 닫힘. 실행 id 없는 보류(앞 보유자 것)는 건드리지 않음.
        holds.hold(listOf(hold("JO-4#A", 1).copy(executionId = "exec-4"), hold("JO-4#B", 1).copy(executionId = "exec-4"), hold("JO-6#A", 1).copy(executionId = null)))
        holds.observe(Q, snap(5, FenceStatus.FENCE_STATUS_DURABLE_PENDING, "JO-4#B" to 1), 1, 5)
        holds.confirmOrphans(setOf("exec-5"))
        assertEquals(RestartHolds.Basis.DRIVE_ENDED, holds.holds().single { it.taskId == "JO-4#A" }.basis)
        assertFalse(holds.has("JO-4#B"))
        assertEquals(RestartHolds.State.PENDING, holds.holds().single { it.taskId == "JO-6#A" }.state)
        assertTrue(holds.blocks(Q))
    }

    @Test
    fun `판별 범위는 정상 종료 인수가 아닌 획득 전부다 — 비정상 단일 재기동·관측 중 정상 종료한 보유자의 후임·조회로 복구한 획득·다른 인스턴스가 산 첫 획득`() {
        // 비정상 종료한 단일 프로세스의 재기동(앞 보유자가 멈춤을 적지 못함) — 같은 경로가 만료 인수다.
        assertTrue(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(4, "mw-a", previousStopped = false, previousIssuing = true)))
        // 관측(HOLDING) 중에 정상 종료한 보유자의 후임 — 앞 세대가 발행을 열지 않았다.
        assertTrue(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(4, "mw-a", previousStopped = true, previousIssuing = false)))
        // 획득 결과를 조회로 복구한 획득.
        assertTrue(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(4, null, previousStopped = false, recovered = true)))
        // 다른 인스턴스의 심장박동이 살아 있는 첫 획득.
        assertTrue(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(1, null, previousStopped = false, othersLive = true)))
        // 정상 종료 인수와 홀로 뜬 첫 획득은 판별하지 않는다(알려진 한계 — 다음 과제).
        assertFalse(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(4, "mw-a", previousStopped = true, previousIssuing = true)))
        assertFalse(MissionHost.discriminates(dev.picasso.ops.host.lease.Acquired(1, null, previousStopped = false)))
    }

    private companion object {
        const val Q = "quadruped-01"

        fun states(holds: RestartHolds) = holds.holds().associate { it.taskId to it.state }

        fun hold(taskId: String, revision: Int?, robot: String = Q) =
            RestartHolds.Hold(robot, taskId.substringBefore('#'), "exec-1", taskId.substringAfter('#'), 0, taskId, revision, 1, null)

        fun snap(floor: Long, status: FenceStatus, vararg tasks: Pair<String, Int>) = RobotSnapshot(
            sequence = 1, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE,
            tasks = tasks.associate { it.first to TaskState.TASK_STATE_RUNNING }, revisions = tasks.toMap(),
            controllerFloor = floor, fenceStatus = status,
        )
    }
}
