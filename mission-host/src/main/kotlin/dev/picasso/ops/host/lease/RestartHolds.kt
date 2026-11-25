package dev.picasso.ops.host.lease

import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.middleware.RobotSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * 수신자 재시작 의심 보류(HA 후속 D1 완화, picasso 한계 대장 §15.330 의 실행 호스트 쪽 완화).
 *
 * 실제 수신자(`adapter-host`)는 접수 장부와 결과 모르는 접수의 기록을 메모리에 둔다. 다시 뜨면 둘 다 사라지고(장부 소실) 인수 확인은
 * `DURABLE_CONFIRMED` 로 답한다 — 그 뒤 같은 태스크 키를 다시 보내면 벤더가 같은 일을 두 번 받는다. 만료 인수 뒤 복원은 앞 세대가 보냈을 수 있는
 * 단위(앞 세대 요청 흔적 또는 송신 의도 행이 있고 판정이 없는 같은 시도의 기체 단위)를 **후보**([State.PENDING])로 먼저 막는다([hold]).
 *
 * 판별은 스냅숏 응답을 **요청 순번**과 함께 받는다([observe]) — 후보를 세운 때의 순번([Hold.afterSeq]) 이하이거나 그 보류의 앞 판정에 쓴 응답보다 앞
 * 순번인 응답은 판정에 쓰지 않는다(늦게 소비한 옛 응답이 더 새 응답을 거스르지 않게). 순번을 모르는 응답도 쓰지 않는다.
 * - 붙음: 그 태스크가 송신 의도의 리비전 이상으로 보이면 [State.ATTACHED] — 막지 않지만 지우지도 않는다. 뒤의 응답마다 다시 보고, 적격 읽기에서
 *   사라졌으면(그 사이 수신자 재시작) 의심으로, 적격 아닌 읽기에서 사라졌으면 다시 후보로 막는다. 보류를 닫는 것은 이 세대가 그 태스크의 출발 의도를
 *   커밋할 때([closeIssued]) — 그 뒤의 재시작은 같은 세대 한계다 — 나 그 단위가 판정을 얻을 때([closeVerdicts])다.
 * - 의심([State.SUSPECT]): 적격 읽기에서 붙지 않았다. 적격 읽기는 대조하는 수신자면 바닥값 = 이 세대 ∧ `DURABLE_CONFIRMED`(앞 세대로 통과한 명령이 다
 *   닫혔다는 답), 운영자 확인 뒤 발행 중이면 같은 세대 `DURABLE_PENDING` 도([Basis.ISSUING_PENDING] — 수신자가 앞 세대 미확정을 계속 들고 있으면 붙음도
 *   확인도 오지 않아 해제할 의심 ID 없이 막히므로), 대조하지 않는 수신자(바닥값 0)·`VOLATILE` 이면 후보를 세운 뒤의 성공한 읽기다. 낮은 리비전만
 *   보이거나 리비전을 대지 못하는 흔적(송신 의도 행 없음 — 이 버전 전의 흔적)은 붙음이 아니다([Basis.REVISION_UNKNOWN]).
 *
 * 의심은 운영자 단위 해제([release])로 닫히고, 뒤에 같은 리비전 이상 붙음이 보이면 시스템 해제로도 닫힌다([systemReleases] — 수신자가 그 접수를 안다는
 * 적극적 증거라 재전송이 멱등이다). 실행이 정착·취소돼도 남는다(장부에 없는 태스크의 취소는 벤더에 닿지 않을 수 있어 기체가 유령 작업을 계속 돌 수
 * 있다). 막는 보류(후보·의심)가 하나라도 있는 기체는 [blocks] 가 참이고, 송신 관문은 그 기체로 가는 **출발**을 리더 상태와 무관하게 막는다(취소·읽기는
 * 이것으로 막지 않는다). 복원을 미룬(`DEFERRED`) 실행의 기체도 그 실행을 다시 지어 후보를 정할 때까지 막는다([deferHold]).
 *
 * 변경은 실행 호스트의 호스트 잠금 아래에서 하고(해제는 HTTP 스레드, [closeIssued] 는 송신 스레드), [blocks] 는 송신 스레드가 읽는다 — 모두 이 객체의
 * 잠금 아래이고 막힌 기체 집합은 휘발 참조 하나로 읽는다.
 */
class RestartHolds {

    enum class State { PENDING, ATTACHED, SUSPECT }

    /** 의심의 근거 종류(V22 `basis_kind`). */
    enum class Basis {
        /** 적격 읽기에서 그 태스크가 수신자 장부에 없거나 낮은 리비전만 있다. */
        ABSENT,

        /** 붙음을 댈 리비전이 없다(송신 의도 행이 없는 흔적). */
        REVISION_UNKNOWN,

        /** 운영자 확인 뒤 발행 중 수신자가 같은 세대 `DURABLE_PENDING` 으로 답한다(앞 세대 미확정을 계속 들고 있음). */
        ISSUING_PENDING,

        /** 그 실행의 구동이 끝나 판별을 이어 갈 읽기가 끊겼다. */
        DRIVE_ENDED,

        /** 복원하지 못한 실행(포기·운영자 종결)에 판정 없는 송신 흔적이 남았다. */
        RESTORE_ABANDONED,
    }

    /**
     * 보류 하나. [taskId] 가 열쇠다. [revision] 은 그 태스크의 송신 의도 행 가운데 가장 큰 리비전(없으면 `null` — 붙음을 대지 못한다),
     * [basisIntentId] 는 가장 큰 송신 의도 순번(없으면 0), [basisRecordId] 는 후보를 세운 판단 기록 행이다. [afterSeq] 는 후보를 세운 때의 요청 순번,
     * [lastSeq] 는 이 보류의 상태를 마지막으로 정한 응답의 순번이다. [suspicionId] 는 저장한 의심 행(아직 못 적었으면 `null`), [systemRelease] 는 의심 뒤
     * 붙음을 보아 시스템 해제를 적을 차례라는 표시다.
     */
    data class Hold(
        val robotId: String,
        val jobOrderId: String,
        val executionId: String?,
        val unitId: String,
        val attempt: Int,
        val taskId: String,
        val revision: Int?,
        val basisIntentId: Long,
        val basisRecordId: Long?,
        val state: State = State.PENDING,
        val basis: Basis? = null,
        val suspicionId: Long? = null,
        val generation: Long? = null,
        val observedFloor: Long? = null,
        val observedStatus: String? = null,
        val afterSeq: Long = 0,
        val lastSeq: Long = 0,
        val systemRelease: Boolean = false,
    ) {
        val confirmed: Boolean get() = state == State.SUSPECT
        val blocking: Boolean get() = state != State.ATTACHED
    }

    private val holds = LinkedHashMap<String, Hold>()

    /** 복원을 미룬 작업 지시 → 그 기체들. 다시 지어 후보를 정하거나 의심으로 넘기면 지운다. */
    private val deferred = LinkedHashMap<String, Set<String>>()

    /** 기체마다 마지막으로 본 응답 세션(송신 의도 행에 기록만 한다). */
    private val sessions = ConcurrentHashMap<String, String>()

    @Volatile
    private var blocked: Set<String> = emptySet()

    /** 그 기체로 가는 출발을 막는가(후보·의심·미룬 복원). 송신 스레드가 읽는다. */
    fun blocks(robotId: String): Boolean = robotId in blocked || ALL_ROBOTS in blocked

    /** 막는 보류가 있는 기체들(모든 기체를 막는 표시 [ALL_ROBOTS] 는 빼고). */
    fun blockedRobots(): Set<String> = blocked - ALL_ROBOTS

    /** 그 기체에 확정한 의심이 있는가. */
    @Synchronized
    fun suspected(robotId: String): Boolean = holds.values.any { it.robotId == robotId && it.confirmed }

    /** 보류 전부(세운 순서, 붙음 포함). */
    @Synchronized
    fun holds(): List<Hold> = holds.values.toList()

    /** 복원을 미뤄 막고 있는 (작업 지시 → 기체들). */
    @Synchronized
    fun deferredHolds(): Map<String, Set<String>> = LinkedHashMap(deferred)

    /** 그 태스크의 보류가 있는가(붙음 포함). */
    @Synchronized
    fun has(taskId: String): Boolean = taskId in holds

    /** 그 기체의 막는 까닭(사람이 읽는 한 줄). 막지 않으면 `null`. */
    @Synchronized
    fun describe(robotId: String): String? {
        val mine = holds.values.filter { it.robotId == robotId && it.blocking }
        val waiting = deferred.filterValues { robotId in it || ALL_ROBOTS in it }.keys
        if (mine.isEmpty() && waiting.isEmpty()) return null
        return buildList {
            mine.forEach { h ->
                add(
                    if (h.confirmed) "의심 ${h.suspicionId?.let { "#$it" } ?: "(저장 대기)"} 단위 ${h.unitId}(태스크 ${h.taskId}, ${h.basis}) — 운영자 단위 해제 필요"
                    else "후보 단위 ${h.unitId}(태스크 ${h.taskId}) — 수신자 장부 대조 대기",
                )
            }
            waiting.forEach { add("복원을 미룬 작업 지시 $it — 다시 지어 대조할 때까지") }
        }.joinToString("; ")
    }

    /** 저장된 열린 의심을 올린다(리스 획득 때 복원보다 먼저). 이미 있는 태스크는 저장된 것으로 갈아 끼운다. */
    @Synchronized
    fun load(rows: List<Hold>) {
        rows.forEach { holds[it.taskId] = it.copy(state = State.SUSPECT) }
        refresh()
    }

    /**
     * 보류를 세운다(후보 또는 곧바로 확정한 의심). 같은 태스크의 보류가 이미 있으면 그대로 둔다 — 단 실행 id 없이 다시 대조하려고 올린 후보(시스템 해제의
     * 재판별 근거, [loadRejudged])는 다시 지은 실행의 후보가 갈아 끼운다(그 실행의 판정·출발로 닫히게).
     */
    @Synchronized
    fun hold(candidates: List<Hold>) {
        candidates.forEach { c ->
            val existing = holds[c.taskId]
            if (existing == null || existing.executionId == null && existing.state != State.SUSPECT && c.executionId != null) holds[c.taskId] = c
        }
        refresh()
    }

    /**
     * 시스템 해제된 의심을 다시 대조할 후보로 올린다(리스 획득 때, 복원보다 먼저). 시스템 해제는 «그때 수신자가 그 접수를 알았다» 일 뿐이라, 그 뒤 이
     * 세대가 그 출발을 내지 않았으면 다음 보유자가 수신자 재시작을 다시 본다. 막는 후보로 둔다 — 그 기체의 성공한 읽기가 붙음을 보이면 풀린다.
     */
    @Synchronized
    fun loadRejudged(rows: List<Hold>) {
        rows.forEach { holds.putIfAbsent(it.taskId, it.copy(state = State.PENDING, basis = null, suspicionId = null, systemRelease = false)) }
        refresh()
    }

    @Synchronized
    fun deferHold(jobOrderId: String, robotIds: Set<String>) {
        deferred[jobOrderId] = robotIds
        refresh()
    }

    @Synchronized
    fun clearDeferred(jobOrderId: String) {
        if (deferred.remove(jobOrderId) != null) refresh()
    }

    /** 그 작업 지시의 복원이 미뤄진 채인가. */
    @Synchronized
    fun isDeferred(jobOrderId: String): Boolean = jobOrderId in deferred

    /**
     * 기체 [robotId] 의 스냅숏 응답 하나를 본다. [seq] 는 그 요청의 순번(모르면 `null` — 판정에 쓰지 않음), [mine] 은 이 인스턴스의 세대, [issuing] 은
     * 전역 발행 중인가다. 새로 확정한 의심이 있으면 참.
     */
    @Synchronized
    fun observe(robotId: String, snapshot: RobotSnapshot?, seq: Long?, mine: Long, issuing: Boolean = false): Boolean {
        if (snapshot == null) return false
        if (snapshot.session.isNotEmpty()) sessions[robotId] = snapshot.session
        if (seq == null) return false
        var confirmed = false
        holds.values.filter { it.robotId == robotId && seq > it.afterSeq && seq > it.lastSeq }.forEach { h ->
            val attached = attached(h, snapshot)
            val next = when (h.state) {
                State.PENDING, State.ATTACHED -> when {
                    attached -> h.copy(state = State.ATTACHED, lastSeq = seq)
                    qualifies(snapshot, mine, issuing) -> {
                        confirmed = true
                        h.copy(
                            state = State.SUSPECT, basis = basisOf(h, snapshot, mine), lastSeq = seq,
                            observedFloor = snapshot.controllerFloor, observedStatus = snapshot.fenceStatus.name,
                        )
                    }
                    else -> h.copy(state = State.PENDING, lastSeq = seq)
                }
                // 의심: 붙지 않은 더 새 응답이면 시스템 해제 예약을 거둔다(더 새 응답이 앞의 붙음을 거스름).
                State.SUSPECT -> when {
                    !attached -> h.copy(lastSeq = seq, systemRelease = false)
                    h.suspicionId != null -> h.copy(systemRelease = true, lastSeq = seq)
                    else -> h.copy(state = State.ATTACHED, basis = null, lastSeq = seq)
                }
            }
            holds[h.taskId] = next
        }
        refresh()
        return confirmed
    }

    /** 이 세대가 그 태스크의 출발 의도를 커밋했다 — 붙음·후보 보류를 닫는다(의심은 닫지 않는다). */
    @Synchronized
    fun closeIssued(taskId: String) {
        val h = holds[taskId] ?: return
        if (h.state != State.SUSPECT) {
            holds.remove(taskId)
            refresh()
        }
    }

    /** 그 태스크들의 단위가 판정을 얻었다 — 붙음·후보 보류를 닫는다(의심은 닫지 않는다). */
    @Synchronized
    fun closeVerdicts(taskIds: Set<String>) {
        holds.values.filter { it.taskId in taskIds && it.state != State.SUSPECT }.forEach { holds.remove(it.taskId) }
        refresh()
    }

    /**
     * 구동이 끝난 실행의 보류를 정리한다. 붙음은 닫고, 후보는 의심으로 확정한다([Basis.DRIVE_ENDED] — 읽기가 끊겨 풀릴 길이 없으니 운영자 해제로 닫게).
     * 실행 id 가 없는 보류(앞 보유자가 적은 의심·포기 실행)는 건드리지 않는다.
     */
    @Synchronized
    fun confirmOrphans(live: Set<String>) {
        holds.values.filter { it.executionId != null && it.executionId !in live && it.state != State.SUSPECT }.forEach {
            if (it.state == State.ATTACHED) holds.remove(it.taskId) else holds[it.taskId] = it.copy(state = State.SUSPECT, basis = Basis.DRIVE_ENDED)
        }
        refresh()
    }

    /** 확정했으나 아직 저장하지 못한 의심. */
    @Synchronized
    fun unpersisted(): List<Hold> = holds.values.filter { it.confirmed && it.suspicionId == null }

    /** 저장된 의심 가운데 뒤에 붙음을 보아 시스템 해제를 적을 것. */
    @Synchronized
    fun systemReleases(): List<Hold> = holds.values.filter { it.confirmed && it.systemRelease && it.suspicionId != null }

    /** 저장했다. */
    @Synchronized
    fun persisted(taskId: String, suspicionId: Long, generation: Long) {
        holds[taskId]?.let { holds[taskId] = it.copy(suspicionId = suspicionId, generation = it.generation ?: generation) }
    }

    /**
     * 시스템 해제가 커밋됐다 — 그 의심을 붙음([State.ATTACHED])으로 되돌린다. 막지 않되 지우지 않고, 이 세대가 그 출발을 내거나([closeIssued]) 단위가
     * 판정을 얻을 때까지([closeVerdicts]) 응답마다 다시 본다 — 그 사이 수신자가 다시 떠 적격 읽기에서 사라지면 다시 의심이 된다. 시스템 해제가 그 뒤에
     * 다시 예약되지 않았으면(같은 의심 ID) 아무것도 하지 않는다.
     */
    @Synchronized
    fun systemReleased(suspicionId: Long) {
        val hit = holds.values.firstOrNull { it.suspicionId == suspicionId && it.systemRelease } ?: return
        holds[hit.taskId] = hit.copy(state = State.ATTACHED, basis = null, suspicionId = null, systemRelease = false)
        refresh()
    }

    /** 운영자 해제가 커밋됐다 — 그 의심을 닫는다. 닫은 보류(없으면 `null`). */
    @Synchronized
    fun release(suspicionId: Long): Hold? {
        val hit = holds.values.firstOrNull { it.suspicionId == suspicionId } ?: return null
        holds.remove(hit.taskId)
        refresh()
        return hit
    }

    /** 기체 [robotId] 에서 마지막으로 본 응답 세션(없으면 `null`). */
    fun session(robotId: String): String? = sessions[robotId]

    private fun refresh() {
        blocked = holds.values.filter { it.blocking }.mapTo(HashSet()) { it.robotId } + deferred.values.flatten()
    }

    companion object {
        /** 미룬 복원의 보류가 막을 기체를 알 수 없을 때(송신 흔적을 못 읽음) 모든 기체를 막는 표시. */
        const val ALL_ROBOTS = "*"

        /** 그 태스크가 같은 리비전 이상으로 보이는가. 리비전을 대지 못하면(흔적이나 응답에 없음) 붙음이 아니다. */
        fun attached(hold: Hold, snapshot: RobotSnapshot): Boolean {
            if (hold.taskId !in snapshot.tasks) return false
            val wanted = hold.revision ?: return false
            val seen = snapshot.revisions[hold.taskId] ?: return false
            return seen >= wanted
        }

        /**
         * «붙지 않음» 을 확정할 수 있는 읽기인가. 대조 안 하는 수신자(바닥값 0)·`VOLATILE` 은 어느 성공한 읽기든, 대조 수신자는 바닥값 = 이 세대 ∧
         * `DURABLE_CONFIRMED` 다. 운영자 확인 뒤 발행 중([issuing])이면 바닥값 = 이 세대 ∧ `DURABLE_PENDING` 도 적격이다. 관측 중에는 적격이 아니다 —
         * 벤더 호출에 걸린 앞 세대 접수가 끝나 붙기를 기다린다.
         */
        fun qualifies(snapshot: RobotSnapshot, mine: Long, issuing: Boolean = false): Boolean {
            val floor = snapshot.controllerFloor
            return when {
                floor == 0L -> true
                snapshot.fenceStatus == FenceStatus.FENCE_STATUS_VOLATILE -> true
                mine > 0 && floor == mine && snapshot.fenceStatus == FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED -> true
                issuing && mine > 0 && floor == mine && snapshot.fenceStatus == FenceStatus.FENCE_STATUS_DURABLE_PENDING -> true
                else -> false
            }
        }

        private fun basisOf(hold: Hold, snapshot: RobotSnapshot, mine: Long): Basis = when {
            hold.revision == null -> Basis.REVISION_UNKNOWN
            snapshot.controllerFloor == mine && snapshot.fenceStatus == FenceStatus.FENCE_STATUS_DURABLE_PENDING -> Basis.ISSUING_PENDING
            else -> Basis.ABSENT
        }
    }
}

/**
 * 미들웨어가 읽은 스냅숏 응답을 [observe] 에도 넘기는 기체 포트 — 실행 호스트의 기체 포트가 [dev.picasso.ops.host.HostRobotPort] 가 아닐 때(요청 순번이
 * 없는 시험 배선)만 쓴다. 이 포트를 지나는 읽기는 동기라 소비 순서가 요청 순서이고, 그 순서로 순번을 매긴다([next]). 운영 배선은 [dev.picasso.ops.host.HostRobotPort]
 * 의 지금 읽기·미리 읽기 갈고리가 요청 순번과 함께 넘긴다.
 */
class ObservingRobotPort(
    private val delegate: dev.picasso.middleware.RobotPort,
    private val observe: (String, RobotSnapshot?, Long) -> Unit,
) : dev.picasso.middleware.RobotPort by delegate {
    private val counter = java.util.concurrent.atomic.AtomicLong()

    /** 지금까지 매긴 가장 큰 순번. */
    fun current(): Long = counter.get()

    private fun next(): Long = counter.incrementAndGet()

    override fun snapshot(robotId: String): RobotSnapshot? {
        val seq = next()
        return delegate.snapshot(robotId).also { observe(robotId, it, seq) }
    }

    override fun prefetched(robotId: String): dev.picasso.middleware.PrefetchedRead? {
        val seq = next()
        return delegate.prefetched(robotId).also { if (it is dev.picasso.middleware.PrefetchedRead.Read) observe(robotId, it.snapshot, seq) }
    }
}
