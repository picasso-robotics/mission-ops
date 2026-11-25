package dev.picasso.ops.host.lease

import dev.picasso.middleware.Authority
import dev.picasso.middleware.AuthorityPort
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.ops.host.store.LeaseClaim
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 리더 상태(HA 설계 기록 §7). 리스 획득은 [ISSUING] 이 아니라 [RECONCILING] 진입이다.
 *
 * - [STANDBY]: 리스가 없다. 심장박동과 주기적 획득 시도만 한다(복원·pump·셀 읽기·기체/플릿 호출·변경 HTTP 없음).
 * - [RECONCILING]: 리스를 쥐었고 카탈로그·일지·잔여 파지를 DB 에서 다시 읽어 실행을 다시 짓는 중이다. 송신하지 않는다.
 * - [HOLDING]: 만료 인수 뒤(또는 확인 안 된 인수를 물려받음). 다시 지은 실행에 다시 붙어 관측만 한다. 신규 발행(기체·플릿·셀 명령, 변경 HTTP)은
 *   운영자 확인 뒤에 연다.
 * - [ISSUING]: 발행한다. 발행을 열었던 보유자의 정상 종료에서 넘겨받았거나 운영자가 옛 프로세스 종료를 확인했다. 들기 전에 리스 기간 행에 «이 세대가
 *   발행을 열었다» 를 적는다.
 * - [STOPPING]: 정상 종료 중. 송신·DB 쓰기·변경 HTTP 를 닫고 송신 중인 명령이 다 끝나기를 기다린다. 리스는 내놓을 때까지 갱신한다.
 * - [LOST]: 리스를 잃었다. 송신·pump 를 멈추고 자기 차단을 (제한 시간 안에) 적은 뒤 프로세스를 끝낸다. 다시 얻지 않는다(감독자의 재기동이 새
 *   인스턴스로 띄운다).
 */
enum class LeaderState { STANDBY, RECONCILING, HOLDING, ISSUING, STOPPING, LOST }

/** 리스를 얻었을 때와 잃었을 때 실행 호스트가 할 일. */
interface LeadershipListener {
    /** 리스를 얻었다([LeaderState.RECONCILING]). 재대조를 마치면 [HostLeadership.reconciled] 를 부른다. */
    fun acquired(acquired: Acquired)

    /** 리스를 잃었다([LeaderState.LOST]). 송신·pump 를 멈추고 자기 차단을 적는다. 그 뒤 프로세스 종료 훅이 돈다. 오래 막히면 안 된다. */
    fun lost(reason: String)
}

/** 리스를 잃은 프로세스를 끝내는 훅. 운영은 프로세스 종료이고 시험은 기록만 하는 훅을 넣는다. */
fun interface HostExit {
    fun exit(reason: String)

    companion object {
        /** 종료 코드. 감독자(자동 재기동)가 새 인스턴스로 다시 띄운다. */
        const val LOST_EXIT_CODE = 70

        /** `System.exit` 의 종료 훅(Spring 닫기)이 이만큼 안에 끝나지 않으면 `Runtime.halt` 로 끝낸다. */
        const val HALT_AFTER_MILLIS = 10_000L

        /**
         * 새 스레드에서 `System.exit` 를 부르고(종료 훅이 Spring 컨텍스트를 닫는다), 그 훅이 [HALT_AFTER_MILLIS] 안에 끝나지 않으면 `Runtime.halt` 로
         * 끝낸다 — LOST 인 채 프로세스가 남으면 감독자가 다시 띄우지 않는다. 부른 스레드를 막지 않는다.
         */
        val PROCESS = HostExit { reason ->
            LoggerFactory.getLogger(HostExit::class.java).error("리스를 잃어 프로세스를 끝낸다: {}", reason)
            Thread({
                Thread.sleep(HALT_AFTER_MILLIS)
                Runtime.getRuntime().halt(LOST_EXIT_CODE)
            }, "mission-host-halt").apply { isDaemon = true }.start()
            Thread({ System.exit(LOST_EXIT_CODE) }, "mission-host-exit").start()
        }
    }
}

/**
 * 송신 직전 로컬 권위 확인(HA 설계 기록 §4.4 의 3). 기체·플릿 명령, 셀 신호 쓰기, 결함 주입 중계가 실제 송신을 시작하기 직전에 [sending] 으로
 * 감싼다. 리더가 [LeaderState.ISSUING] 이고 자기 차단 기한 전이어야 보낸다. **틈을 줄일 뿐 닫지 않는다** — 확인과 송신 사이에 멎은 프로세스,
 * 이미 나간 명령은 막지 못한다. 받는 쪽의 세대 대조(수신자가 켰을 때)가 그 뒤를 맡는다.
 *
 * 기체 명령은 기체 단위로([mayIssue] `robotId`), 플릿 운반은 운반 참조로([mayDispatch]) 묻는다 — 만료 인수 뒤 관측([LeaderState.HOLDING]) 중에도
 * 수신자가 이 세대를 확인한 기체의 실행은 보낸다([HostLeadership]). 셀 신호 쓰기·결함 주입 중계는 전역 [mayIssue] 그대로다.
 */
interface SendAuthority {
    fun mayIssue(): Boolean

    /** 기체 [robotId] 에 출발·취소를 보내도 되는가. 기본은 전역 [mayIssue] 다. */
    fun mayIssue(robotId: String): Boolean = mayIssue()

    /** 운반 참조 [reference](태스크 id)의 맡기기·취소를 보내도 되는가. 기본은 전역 [mayIssue] 다. */
    fun mayDispatch(reference: String): Boolean = mayIssue()

    /**
     * 기체 [robotId] 로 **출발**(`StartTask` — 첫 출발·개정 갱신·미확정 조회)을 보내도 되는가 — [mayIssue] `robotId` 에 더해 묻는다. 기본은 늘 참이다.
     * 실행 호스트는 수신자 재시작 의심 보류([RestartHolds])가 있는 기체에 거짓으로 답한다(리더 상태와 무관). 취소는 묻지 않는다.
     */
    fun mayStart(robotId: String): Boolean = true

    /**
     * 출발을 실제로 보내기 직전(확인을 지난 뒤, 위임 호출 앞) 부른다. 실행 호스트는 여기서 송신 의도 한 줄을 커밋한다 — 커밋하지 못하면
     * [CommandNotSent] 를 던져 보내지 않는다. 기본은 아무것도 하지 않는다.
     */
    fun beforeStart(robotId: String, taskId: String, revision: Int) {}

    /** 출발을 큐에 넣는 자리(송신 관문의 `issue`, 호스트 잠금 아래)에서 부른다. 실행 호스트는 그 출발의 실행·단위를 적어 둔다. 기본은 아무것도 안 한다. */
    fun noteIssue(robotId: String, taskId: String, revision: Int) {}

    /** 송신 의도 커밋 뒤 위임 직전 재확인을 지났다(곧 위임한다). 실행 호스트는 그 태스크의 붙음·후보 보류를 닫는다. 기본은 아무것도 안 한다. */
    fun started(robotId: String, taskId: String, revision: Int) {}

    /** 보낼 수 없으면 [CommandNotSent] 를 던진다(보내지 않았음을 아는 실패라 미들웨어가 미확정으로 읽지 않는다). */
    fun checkIssue(what: String) {
        if (!mayIssue()) throw CommandNotSent("$NOT_ISSUING: $what")
    }

    /** 확인한 뒤 [send] 를 부른다. [HostLeadership] 은 송신 중인 수를 세어 정상 종료가 그것이 0 이 되기를 기다린다. */
    fun <T> sending(what: String, send: () -> T): T = sending(what, ::mayIssue, send)

    /** [allowed] 로 확인한 뒤 [send] 를 부른다. 거짓이면 보내지 않고 [CommandNotSent] 를 던진다. */
    fun <T> sending(what: String, allowed: () -> Boolean, send: () -> T): T {
        if (!allowed()) throw CommandNotSent("$NOT_ISSUING: $what")
        return send()
    }

    companion object {
        const val NOT_ISSUING = "리스로 발행하는 리더가 아니어서 보내지 않았다"

        /** 수신자 재시작 의심 보류로 보내지 않은 출발의 문구 머리. */
        const val RESTART_HELD = "수신자 재시작 의심 보류로 출발을 보내지 않았다"

        /** 언제나 보낸다. 리스 없이 쓰는 자리(시험, 모의 실행)만 쓴다. */
        val ALWAYS = object : SendAuthority {
            override fun mayIssue(): Boolean = true
        }
    }
}

/**
 * 리더 상태기계(HA 설계 기록 §7). [lease] 를 주기마다 획득(대기)하거나 갱신(보유)하고, 감시가 자기 차단 기한을 본다. 실행 호스트는
 * [begin] 으로 듣는 쪽을 걸고, 획득하면 [LeadershipListener.acquired] 에서 재대조한 뒤 [reconciled] 로 [LeaderState.ISSUING]·
 * [LeaderState.HOLDING] 에 든다.
 *
 * 리스를 잃는 길은 셋이다: 갱신이 0행(보유자·세대 불일치나 만료), 자기 차단 기한이 지남(갱신이 제때 안 됨), DB 쓰기 가드가 리스 불일치를 봄
 * ([lost]). 어느 길이든 한 번만 [LeaderState.LOST] 로 가고, 그 순간부터 송신 확인([mayIssue])·DB 가드([claim])가 막는다. 그 뒤 새
 * 스레드에서 pump 정지·자기 차단 기록([LeadershipListener.lost]) → 종료 훅([exit]) 순으로 돈다.
 *
 * 정상 종료는 [stopping] → (pump 정지) → [awaitQuiet] → 멈춤 기록 → [release] 순서다(실행 호스트의 `close`).
 */
class HostLeadership(
    val lease: HostLease,
    val timing: LeaseTiming,
    private val exit: HostExit = HostExit.PROCESS,
    /** 관측 중 미준비 기체를 미리 읽기 결과와 관계없이 첫 관측 뒤 다시 동기로 읽는 기체별 간격([dueForRetry], `host.readiness.unread-retry`). */
    val unreadRetry: java.time.Duration = DEFAULT_UNREAD_RETRY,
    private val nanoTime: () -> Long = System::nanoTime,
) : SendAuthority, AutoCloseable {

    val instanceId: String get() = lease.instanceId

    private val stateRef = AtomicReference(LeaderState.STANDBY)

    val state: LeaderState get() = stateRef.get()

    /** 이번 보유의 획득 결과. 쥔 적이 없으면 `null`. */
    @Volatile
    var acquired: Acquired? = null
        private set

    /** [LeaderState.LOST] 의 까닭. */
    @Volatile
    var lostReason: String? = null
        private set

    private var listener: LeadershipListener? = null

    /** 송신 직전 확인을 지나 아직 송신 중인 명령 수([sending]). */
    private val inFlight = AtomicInteger()

    /** 시험이 프로세스 멈춤(긴 GC 정지 따위)을 흉내 내는 문. 리스·감시 스레드와 실행 호스트의 pump·심장박동이 주기마다 지난다. */
    internal val stall = Stall()

    private val leaseThread = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-lease").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-lease-watch").apply { isDaemon = true } }

    override fun mayIssue(): Boolean = state == LeaderState.ISSUING && lease.withinDeadline()

    /** 수신자 재시작 의심 보류(HA 후속 D1 완화). 실행 호스트가 복원·읽기·해제로 바꾸고 송신 관문이 읽는다. */
    val restartHolds = RestartHolds()

    /** 출발 송신 직전 송신 의도를 커밋하는 쪽([beforeStart]). 실행 호스트가 건다. 실패하면 [CommandNotSent] 를 던져야 한다. */
    @Volatile
    var startIntent: ((String, String, Int) -> Unit)? = null

    /** 수신자 재시작 의심 보류가 있는 기체로는 리더 상태와 무관하게 출발을 보내지 않는다. */
    override fun mayStart(robotId: String): Boolean = !restartHolds.blocks(robotId)

    override fun beforeStart(robotId: String, taskId: String, revision: Int) {
        startIntent?.invoke(robotId, taskId, revision)
    }

    /** 출발을 큐에 넣을 때 실행 호스트가 거는 쪽([noteIssue]). */
    @Volatile
    var issueNote: ((String, String, Int) -> Unit)? = null

    override fun noteIssue(robotId: String, taskId: String, revision: Int) {
        issueNote?.invoke(robotId, taskId, revision)
    }

    /** 재확인을 지나 곧 위임하는 출발 — 그 태스크의 붙음·후보 보류를 닫는다(이 세대가 낸 출발이 되었다). */
    override fun started(robotId: String, taskId: String, revision: Int) {
        restartHolds.closeIssued(taskId)
    }

    /**
     * 송신 중인 수를 먼저 올리고 확인한다 — [stopping] 이 상태를 바꾼 뒤 [awaitQuiet] 가 0 을 보면, 그 뒤에 확인을 지나는 송신은 없다(올린 뒤
     * 확인하므로 상태 변경 전에 지난 송신은 수에 이미 들어 있다).
     */
    override fun <T> sending(what: String, allowed: () -> Boolean, send: () -> T): T {
        inFlight.incrementAndGet()
        try {
            if (!allowed()) throw CommandNotSent("${SendAuthority.NOT_ISSUING}: $what")
            return send()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    // ── 수신 측 세대 대조 소비(HA 3단계). 준비 집합은 이 세대 안에서만 뜻이 있다 — 프로세스 하나는 세대 하나다(다시 얻지 않는다).

    /**
     * 기체 클라이언트가 요청 머리말에 실을 제어자 세대. 리스를 쥐었으면 그 세대, 아니면(대기·잃음·내놓음) 0 이다. picasso `PicassoClient` 는 처음 본
     * 0 아닌 값을 붙잡고, 그 뒤 이 값이 바뀌면(잃어서 0) 명령을 보내지 않는다.
     */
    fun controllerEpoch(): Long = lease.generation ?: 0L

    /** 래치된 준비(기체 → 근거). 래치 뒤 응답이 후퇴([retreats])를 보이면([observePrefetched]·[observeFresh]) 풀고 대기로 돌린다. */
    private val ready = ConcurrentHashMap<String, Readiness.Ready>()

    /** 마지막으로 본 응답이 준비가 아니었던 기체의 까닭. */
    private val waiting = ConcurrentHashMap<String, Readiness.Waiting>()

    /** 지금 읽기로 준비를 보았으나 아직 공개(근거 기록 뒤 래치)하지 않은 기체. 그 라운드의 pump 가 재대조를 마친 뒤 [latch] 한다. */
    private val candidates = ConcurrentHashMap<String, Readiness.Ready>()

    /**
     * 래치되지 않은 기체 가운데 마지막 미리 읽기(잠금 밖)가 바닥값 = 내 세대 ∧ `DURABLE_CONFIRMED` 를 보인 것. 이 기체만 지금 읽기 대상에 넣는다
     * ([freshTargets]) — 여는 판정은 여전히 지금 읽기만 한다. 준비가 설 수 없는 기체(대조 안 함·휘발)를 매 라운드 호스트 잠금 아래에서 읽지 않으려는 거름이다.
     */
    private val prefetchConfirmed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 기체마다 마지막 지금 읽기의 요청 순번([dev.picasso.ops.host.HostRobotPort] 가 보내기 직전에 매기는 엄격히 증가하는 번호 — 벽시계가 아니다). 그보다
     * 작은 순번의 미리 읽기 응답은 해제·거르기·까닭에 쓰지 않는다(늦게 소비한 옛 응답). 같은 순번은 없다.
     */
    private val lastFreshSeq = ConcurrentHashMap<String, Long>()

    /** 기체마다 마지막 지금 읽기를 한 단조 시각(나노초). [allowUnreadFallback] 의 간격을 잰다. */
    private val lastFreshNanos = ConcurrentHashMap<String, Long>()

    /**
     * 래치된 기체마다 요청 순번상 가장 뒤의 성공한 응답(순번, 바닥값, 확인 답). 읽기 실패와 그보다 앞 순번의 늦은 미리 읽기는 덮지 않는다. [latchedPending]
     * 이 읽는다(표시만 — 래치는 이것으로 바뀌지 않는다).
     */
    private val latestAnswer = ConcurrentHashMap<String, Triple<Long, Long, dev.picasso.contracts.v1.FenceStatus>>()

    private fun noteAnswer(robotId: String, seq: Long, snapshot: RobotSnapshot) {
        latestAnswer.merge(robotId, Triple(seq, snapshot.controllerFloor, snapshot.fenceStatus)) { old, new -> if (new.first > old.first) new else old }
    }

    /**
     * 래치된 기체인데 요청 순번상 가장 뒤의 성공한 응답이 같은 세대 `DURABLE_PENDING` 인가(D4 표시). 그 미확정은 이 세대 자신의 것이라 래치를 풀지 않는다
     * ([retreats]) — 화면이 그것을 보이게 할 뿐이다.
     */
    fun latchedPending(robotId: String): Boolean {
        val latched = ready[robotId] ?: return false
        val answer = latestAnswer[robotId] ?: return false
        return answer.first > latched.seq && answer.second == latched.floor &&
            answer.third == dev.picasso.contracts.v1.FenceStatus.FENCE_STATUS_DURABLE_PENDING
    }

    /** «실행 → 필수 기체·플릿» 표. 실행 호스트가 호스트 잠금 아래에서 바꾸고, 송신 스레드는 이 참조 하나를 읽는다. */
    @Volatile
    var requirements: List<Requirement> = emptyList()

    /**
     * pump 가 이 세대로 **지금** 읽은 스냅숏 하나를 본다([dev.picasso.ops.host.HostRobotPort] 의 지금 읽기만 부른다). [seq] 는 그 요청의 순번, [at] 은
     * 받은 시각이다. 관측([LeaderState.HOLDING]) 중에만 뜻이 있다. 래치되지 않은 기체는 준비면 후보로 두고(공개는 [latch]),
     * 아니면 까닭을 둔다. 래치된 기체는 응답이 후퇴([retreats])를 보이면 푼다([unlatch]). 바닥값이 이 세대보다 높으면 밀려남이다([superseded]).
     */
    fun observeFresh(robotId: String, snapshot: RobotSnapshot?, seq: Long, at: Instant) {
        // 래치된 기체의 가장 뒤 성공한 응답은 발행 중에도 따라간다([latchedPending] 표시).
        if (snapshot != null && ready.containsKey(robotId)) noteAnswer(robotId, seq, snapshot)
        // 보류 기체의 회복 간격은 발행 중에도 잰다(지금 읽기마다).
        if (state != LeaderState.HOLDING) {
            lastFreshNanos[robotId] = nanoTime()
            return
        }
        val mine = lease.generation ?: return
        snapshot?.let { superseded(robotId, mine, it.controllerFloor) }
        lastFreshSeq.merge(robotId, seq, ::maxOf)
        lastFreshNanos[robotId] = nanoTime()
        val judged = judgeReadiness(robotId, mine, snapshot, at)
        if (ready.containsKey(robotId)) {
            snapshot?.let { noteAnswer(robotId, seq, it) }
            if (snapshot != null && judged is Readiness.Waiting && retreats(mine, snapshot)) unlatch(judged)
            return
        }
        when (judged) {
            is Readiness.Ready -> {
                candidates[robotId] = judged.copy(seq = seq)
                waiting.remove(robotId)
            }
            is Readiness.Waiting -> {
                candidates.remove(robotId)
                // 지금 읽기가 미준비(실패 포함)를 보였다 — 앞 미리 읽기의 확인 표시는 낡았다. 다시 서려면 이 지금 읽기보다 뒤 순번의 미리 읽기 확인이
                // 필요하다(그 전에는 간격 회복만). 남겨 두면 답하지 않는 기체를 매 pump 지금 읽는다.
                prefetchConfirmed.remove(robotId)
                waiting[robotId] = judged
            }
        }
    }

    /**
     * 미리 읽은(또는 그 밖의) 스냅숏 응답 하나를 본다. [seq] 는 그 요청의 순번이다(모르면 `null` — **옛 응답으로 다룬다**: 밀려남 말고는 보지 않는다).
     * **이것으로 기체를 열지 않는다.** 그 기체의 마지막 지금 읽기(래치 근거 포함)보다 작은 순번의 요청의 응답이면 밀려남 말고는 보지 않는다(늦게 소비한 옛 응답). 응답이
     * 후퇴([retreats])를 보이면 래치를 풀고 그 라운드의 후보도 지운다(공개되지 않게). 래치되지 않은 기체는 준비를 보이면 지금 읽기 대상에 넣고
     * ([freshTargets]), 아니면 까닭을 둔다.
     */
    fun observePrefetched(robotId: String, snapshot: RobotSnapshot?, seq: Long?) {
        if (snapshot != null && seq != null && ready.containsKey(robotId)) noteAnswer(robotId, seq, snapshot)
        if (state != LeaderState.HOLDING || snapshot == null) return
        val mine = lease.generation ?: return
        superseded(robotId, mine, snapshot.controllerFloor)
        val basis = listOfNotNull(lastFreshSeq[robotId], ready[robotId]?.seq).maxOrNull()
        if (seq == null) {
            noteUnknownSeq(robotId)
            return
        }
        if (basis != null && seq < basis) return
        val judged = judgeReadiness(robotId, mine, snapshot, Instant.EPOCH)
        if (retreats(mine, snapshot)) candidates.remove(robotId)
        if (ready.containsKey(robotId)) {
            noteAnswer(robotId, seq, snapshot)
            if (judged is Readiness.Waiting && retreats(mine, snapshot)) unlatch(judged)
            return
        }
        when (judged) {
            // 수신자 재시작 의심 보류가 막는 기체는 지금 읽기 대상에 넣지 않는다 — 보류 판별은 미리 읽기 응답으로 하고, 풀리면 다음 미리 읽기가 넣는다.
            is Readiness.Ready -> if (!restartHolds.blocks(robotId)) prefetchConfirmed.add(robotId)
            is Readiness.Waiting -> {
                prefetchConfirmed.remove(robotId)
                waiting[robotId] = judged
            }
        }
    }

    /**
     * 관측 중 이 기체를 호스트 잠금 아래에서 지금 읽어도 되는가 — 이미 열렸거나 마지막 미리 읽기가 준비를 보였다. 준비가 설 수 없는 기체(대조 안 함·
     * 휘발)는 어차피 출발하지 못하므로 출발을 앞뒀어도 지금 읽지 않는다. 관측이 아니면 늘 참이다.
     */
    fun mayReadNow(robotId: String): Boolean {
        // 수신자 재시작 의심 보류가 막는 기체는 상태·래치와 무관하게 간격 회복 읽기만 — 출발을 앞뒀어도 매 pump 지금 읽지 않는다.
        if (restartHolds.blocks(robotId)) return claimHeldRead(robotId)
        return state != LeaderState.HOLDING || ready.containsKey(robotId) || robotId in prefetchConfirmed || dueForRetry(robotId)
    }

    /**
     * 보류 기체의 간격 회복 읽기 차례인가 — 마지막 지금 읽기에서 [unreadRetry] 넘게 지났고, 이번 pump 에 보류 기체의 회복 읽기를 아직 [HELD_READS] 개
     * 넘게 하지 않았다(여러 보류 기체가 한 pump 에 몰려 호스트 잠금 아래의 동기 읽기가 쌓이지 않게). 판별은 미리 읽기 응답으로 한다.
     */
    private fun heldRecoveryDue(robotId: String): Boolean =
        dueForRetry(robotId) && (robotId in heldReadsThisRound || heldReadsThisRound.size < HELD_READS && robotId == oldestDueHeld())

    /**
     * 회복 차례인 보류 기체 가운데 가장 오래 지금 읽지도 몫을 받지도 못한 것(한 번도 없으면 맨 앞, 같으면 이름 순) — 그 pump 의 회복 몫은 이 기체가
     * 받는다(먼저 순회한 기체가 아니라). 대상은 이번 pump 에 실제로 읽힐 기체 — 도는 실행의 요구 표에 든 기체 — 뿐이다(읽히지 않는 보류 기체가 몫을 잡아
     * 다른 보류 기체의 회복을 막지 않게). 몫을 줄 때 시각을 찍으므로 같은 기체가 연달아 잡지 못한다. 모든 기체를 막는 표시면 요구 표의 기체 전부가 대상이다.
     */
    private fun oldestDueHeld(): String? {
        val readable = requirements.flatMapTo(HashSet()) { it.robots + it.subjects }
        val held = if (restartHolds.blocks(RestartHolds.ALL_ROBOTS)) readable else restartHolds.blockedRobots().filterTo(HashSet()) { it in readable }
        return held.filter(::dueForRetry)
            .minWithOrNull(compareBy<String>({ maxOf(lastFreshNanos[it] ?: Long.MIN_VALUE, lastHeldSlotNanos[it] ?: Long.MIN_VALUE) }, { it }))
    }

    /** 보류 기체마다 마지막으로 회복 몫을 준 단조 시각(나노초). */
    private val lastHeldSlotNanos = ConcurrentHashMap<String, Long>()

    /** 이번 pump 에 회복 읽기를 할 보류 기체(몫을 센다). 관측·발행 모두 — 출발을 앞두지 않았거나 미리 읽기가 계속 실패하는 보류 기체도 차례가 온다. */
    fun heldRecoveryTargets(): Set<String> = oldestDueHeld()?.takeIf(::claimHeldRead)?.let(::setOf) ?: emptySet()

    /** 이번 pump 에 회복 읽기를 허락한 보류 기체. [beginRound] 가 비운다. */
    private val heldReadsThisRound: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** pump 앞에서 부른다 — 보류 기체 회복 읽기의 pump 당 상한을 새로 센다. */
    fun beginRound() {
        heldReadsThisRound.clear()
    }

    /** 보류 기체의 회복 읽기 하나를 이번 pump 몫으로 센다([heldRecoveryDue] 가 참일 때 부르는 쪽이 셈). */
    private fun claimHeldRead(robotId: String): Boolean {
        if (!heldRecoveryDue(robotId)) return false
        if (heldReadsThisRound.add(robotId)) lastHeldSlotNanos[robotId] = nanoTime()
        return true
    }

    /**
     * 관측 중 미준비 기체를 미리 읽기 결과와 관계없이 지금 읽을 차례인가 — 이번 보유에서 아직 지금 읽은 적이 없거나(첫 관측) 마지막 지금 읽기에서
     * [unreadRetry] 넘게 지났다. 미리 읽기가 `Unread` 든, `Read` 로 오지만 순번 모름·미확정이 이어지든 간격마다 한 번 지금 읽어 열릴 길을 남긴다.
     */
    private fun dueForRetry(robotId: String): Boolean {
        val last = lastFreshNanos[robotId] ?: return true
        return nanoTime() - last >= unreadRetry.toNanos()
    }

    /** 기체마다 순번을 모르는(옛 응답으로 다룬) 미리 읽기 응답 수. `GET /host/leader` 의 `readiness` 가 싣는다. */
    private val unknownSeq = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** 기체마다 마지막으로 순번 모름 경고를 남긴 단조 시각(나노초). 경고는 기체마다 1분에 한 번이다. */
    private val unknownSeqWarnedAt = ConcurrentHashMap<String, Long>()

    private fun noteUnknownSeq(robotId: String) {
        val count = unknownSeq.computeIfAbsent(robotId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        val now = nanoTime()
        val last = unknownSeqWarnedAt[robotId]
        if (last == null || now - last >= java.time.Duration.ofMinutes(1).toNanos()) {
            unknownSeqWarnedAt[robotId] = now
            log.warn("기체 {} 의 미리 읽기 응답 순번을 몰라 옛 응답으로 다뤘다(누계 {}) — 응답 객체의 동일성이 깨졌을 수 있다", robotId, count)
        }
    }

    /** 이 기체에서 순번을 모른 미리 읽기 응답의 누계. */
    fun unknownSeqResponses(robotId: String): Long = unknownSeq[robotId]?.get() ?: 0L

    /**
     * 미리 읽은 값이 없을 때(`Unread` — 첫 라운드, 또는 미리 읽기가 진행 중이거나 `maxAge` 넘게 답이 없음) 호스트 잠금 아래에서 동기로 읽어도 되는가.
     * 관측 중에는 이번 보유에서 그 기체를 아직 지금 읽은 적이 없을 때(첫 관측, 곧바로)와, 그 뒤 마지막 지금 읽기에서 [unreadRetry](기본 10초, 단조
     * 시계) 넘게 지났을 때만이다 — 미리 읽기가 계속 진행 중이거나 결과가 매번 낡아 버려져도 회복한 수신자의 기체가 그 간격마다 한 번 지금 읽혀 열릴
     * 길이 남는다(그 사이는 미리 읽기 결과를 기다린다 — 미준비 기체는 어차피 출발하지 못한다). 간격은 답하지 않는 기체 하나가 스냅숏 기한(10초)만큼
     * 호스트 잠금을 쥐는 일을 그 간격에 한 번으로 묶는 값이다. 지금 읽기 대상([dev.picasso.ops.host.HostRobotPort.readNowFor])이면 이것과 관계없이
     * 읽는다. 관측이 아니면(발행 중 포함) 늘 참이다.
     */
    fun allowUnreadFallback(robotId: String): Boolean =
        if (restartHolds.blocks(robotId)) claimHeldRead(robotId) else state != LeaderState.HOLDING || dueForRetry(robotId)

    /** 래치된 준비를 푼다 — 응답이 후퇴를 보였다(더 막는 쪽). 그 라운드의 후보도 지운다. 그 뒤 다시 지금 읽기로 판정한다. */
    private fun unlatch(waitingNow: Readiness.Waiting) {
        val robotId = waitingNow.robotId
        candidates.remove(robotId)
        if (ready.remove(robotId) != null) {
            waiting[robotId] = waitingNow
            prefetchConfirmed.remove(robotId)
            log.warn("기체 {} 의 준비를 푼다 — 응답이 후퇴를 보였다(바닥값 {}, {}, 세대 {})", robotId, waitingNow.floor, waitingNow.fenceStatus, lease.generation)
        }
    }

    /** 이 라운드의 후보를 비운다. pump 앞에서 부른다 — 앞 라운드에서 남은 후보가 재대조 없이 래치되지 않게. */
    fun clearCandidates() {
        candidates.clear()
    }

    /** 공개를 기다리는 준비. */
    fun pendingReady(): List<Readiness.Ready> = candidates.values.toList()

    /** 근거를 적은 뒤 공개한다(래치). 응답이 후퇴([retreats])를 보이기 전까지는 풀리지 않는다. */
    fun latch(readiness: Readiness.Ready) {
        if (candidates.remove(readiness.robotId, readiness)) {
            latestAnswer.remove(readiness.robotId)
            ready.putIfAbsent(readiness.robotId, readiness)
            waiting.remove(readiness.robotId)
            prefetchConfirmed.remove(readiness.robotId)
            log.info("기체 {} 준비(세대 {}, 바닥값 {}, {})", readiness.robotId, lease.generation, readiness.floor, readiness.fenceStatus)
        }
    }

    /**
     * 수신자 재시작 의심 보류가 있는 기체의 후보를 공개하지 않고 버린다 — 근거를 적지 않고 래치하지 않는다. 까닭은 [WaitReason.RESTART_SUSPECT] 로
     * 두고(바닥값·확인 답은 그 읽기의 값), 보류가 풀린 뒤 다음 지금 읽기가 다시 판정한다.
     */
    fun holdBack(readiness: Readiness.Ready) {
        if (candidates.remove(readiness.robotId, readiness)) {
            prefetchConfirmed.remove(readiness.robotId)
            waiting[readiness.robotId] = Readiness.Waiting(readiness.robotId, WaitReason.RESTART_SUSPECT, readiness.floor, readiness.fenceStatus)
        }
    }

    /** 근거를 적지 못한 후보를 버린다. 다음 라운드의 지금 읽기가 다시 판정한다. */
    fun dropCandidate(robotId: String) {
        candidates.remove(robotId)
    }

    /**
     * 이 기체의 준비. 읽은 적이 없으면 까닭 [WaitReason.UNREAD] 다. 판독값 하나를 돌려준다 — 부르는 쪽은 형으로 가른다.
     *
     * 수신자 재시작 의심 보류([restartHolds])가 있으면 확정한 의심이 있거나 밑의 판정이 준비일 때 [WaitReason.RESTART_SUSPECT] 로 보인다(래치는 그대로
     * 두고 겉만 바꾼다 — 보류가 풀리면 밑의 판정이 다시 보인다). 아직 대조 중인 후보뿐이고 밑의 판정이 기다림이면 그 까닭을 그대로 보인다.
     */
    fun readinessOf(robotId: String): Readiness {
        val base = rawReadinessOf(robotId)
        if (!restartHolds.blocks(robotId)) return base
        if (base !is Readiness.Ready && !restartHolds.suspected(robotId)) return base
        return when (base) {
            is Readiness.Ready -> Readiness.Waiting(robotId, WaitReason.RESTART_SUSPECT, base.floor, base.fenceStatus)
            is Readiness.Waiting -> Readiness.Waiting(robotId, WaitReason.RESTART_SUSPECT, base.floor, base.fenceStatus)
        }
    }

    /** 보류를 겹치지 않은 준비(래치·까닭 그대로). 취소의 송신 직전 확인이 쓴다 — 재시작 의심 보류는 출발만 막는다. */
    private fun rawReadinessOf(robotId: String): Readiness = ready[robotId] ?: waiting[robotId] ?: Readiness.Waiting(robotId, WaitReason.UNREAD)

    /**
     * 관측 중 이번 pump 에서 지금 읽을 기체 — 표의 기체 가운데 아직 래치되지 않았고, 마지막 미리 읽기가 (마지막 지금 읽기보다 뒤 순번으로) 준비를
     * 보였거나 간격 회복 차례인 것([dueForRetry]: 첫 관측, 그 뒤 [unreadRetry] 간격마다). 관측이 아니면 비어 있다. 지금 읽기가 미준비를 보이면 미리
     * 읽기의 확인 표시는 지워지므로, 답하지 않는 기체는 간격마다 한 번만 지금 읽힌다.
     */
    fun freshTargets(): Set<String> =
        if (state != LeaderState.HOLDING) emptySet()
        else requirements.flatMapTo(sortedSetOf()) { it.robots }.filterTo(sortedSetOf()) {
            // 수신자 재시작 의심 보류가 막는 기체는 미리 읽기가 확인을 보여도 매 pump 지금 읽지 않는다 — 간격 회복([dueForRetry]) 말고는 판별을 미리 읽기
            // 응답으로 한다(미리 읽기가 끝나지 않는 기체도 간격마다 한 번은 지금 읽혀 판별·래치의 길이 남는다).
            !ready.containsKey(it) && if (restartHolds.blocks(it)) claimHeldRead(it) else (it in prefetchConfirmed || dueForRetry(it))
        }

    /**
     * **공통 판정 함수.** 요구 [requirement] 가 아직 준비되지 않은 까닭들(비면 준비). 기체·플릿 모두 래치된 준비만 준비로 친다([fleetReadiness]).
     */
    fun blockers(requirement: Requirement): List<String> = blockers(requirement, holds = true)

    /** [holds] 가 거짓이면 재시작 의심 보류를 겹치지 않은 준비로 판정한다(취소·운반의 송신 직전 확인). */
    private fun blockers(requirement: Requirement, holds: Boolean): List<String> = buildList {
        requirement.robots.sorted().forEach { robot ->
            when (val r = if (holds) readinessOf(robot) else rawReadinessOf(robot)) {
                is Readiness.Ready -> Unit
                is Readiness.Waiting -> add("실행 ${requirement.executionId}(작업 지시 ${requirement.jobOrderId})의 기체 $robot: ${r.reason.text}")
            }
        }
        if (requirement.fleet) {
            when (val f = fleetReadiness()) {
                is FleetReadiness.Ready -> Unit
                is FleetReadiness.Waiting -> add("실행 ${requirement.executionId}(작업 지시 ${requirement.jobOrderId})의 플릿: ${f.reason.text}")
            }
        }
    }

    // ── 플릿 준비(HA 3단계 PR B2). 기체를 가장하지 않는 별도 슬롯 하나 — 이 세대 안에서만 뜻이 있다(프로세스 하나는 세대 하나).

    private val fleetLock = Any()

    /** 래치된 플릿 준비. 후퇴·휘발·대조 끔·기동 ID 변경을 보이면 푼다. */
    private var fleetReady: FleetReadiness.Ready? = null

    /** 확인됐고 재대조·근거를 기다리는 후보. 같은 세대의 확인이 되풀이돼도 경계([FleetCandidate.boundary])를 옮기지 않는다. */
    private var fleetCandidate: FleetCandidate? = null

    /** 래치되지 않았을 때 보이는 까닭. */
    private var fleetWaiting: FleetReadiness.Waiting = FleetReadiness.Waiting(FleetWaitReason.NO_CONFIRM)

    /** 현장 플릿 면의 기동 ID 기준 — 앞 근거(이전 세대)의 것이거나 이번 보유에서 처음 본 것. 다르면 재시작이다. */
    private var fleetBoot: String? = null

    /** 이번 보유에서 현장 재시작을 보았다 — 재확인 성공으로 지우지 않는다(운영자 확인 뒤 전역 발행으로만 열린다). */
    private var fleetRestarted = false

    /**
     * 플릿 인수 확인을 부를 수 있는 배선인가(현장 플릿 면). 실행 호스트가 기동 때 정한다. 아니면 까닭은 늘 [FleetWaitReason.NO_CONFIRM] 이다.
     */
    fun fleetConfirmable(confirmable: Boolean) = synchronized(fleetLock) {
        if (fleetReady == null && fleetCandidate == null && fleetWaiting.reason in setOf(FleetWaitReason.NO_CONFIRM, FleetWaitReason.UNREAD)) {
            fleetWaiting = FleetReadiness.Waiting(if (confirmable) FleetWaitReason.UNREAD else FleetWaitReason.NO_CONFIRM)
        }
    }

    /** 앞 근거(이전 세대)의 현장 기동 ID. 재대조 전에 실행 호스트가 넣는다. 이번 확인의 기동 ID 가 다르면 재시작이다. */
    fun fleetBootBaseline(bootId: String?) = synchronized(fleetLock) {
        if (bootId == null) return@synchronized
        val seen = fleetBoot
        if (seen == null) {
            fleetBoot = bootId
        } else if (seen != bootId) {
            // 이번 보유의 확인이 먼저 닿아 기준이 섰는데 앞 근거와 다르다 — 그 사이 현장이 다시 떴다.
            fleetRestarted = true
            fleetRestartBasis = fleetRestartBasis ?: bootId
            releaseFleet(FleetReadiness.Waiting(FleetWaitReason.RECEIVER_RESTARTED, null, null, seen))
        }
    }

    /** 이번 보유에서 현장 재시작을 보았는가(재확인 성공으로 지우지 않는다). */
    fun fleetRestartSeen(): Boolean = synchronized(fleetLock) { fleetRestarted }

    /**
     * 재시작을 판정한 기준 기동(앞 보유자들이 믿은 장부의 기동 — 기준 기동이나 아는 운반 핸들의 기동). 실행 호스트가 이것을 기동 기준 표에 적어(관측 중에도) 이
     * 보유자가 운영자 확인 전에 죽어도 다음 보유자가 같은 재시작을 본다. 재시작을 보지 않았으면 `null`.
     */
    fun fleetRestartBasis(): String? = synchronized(fleetLock) { fleetRestartBasis }

    private var fleetRestartBasis: String? = null

    /** 발행([LeaderState.ISSUING])에 든 순간(정상 종료 인수·운영자 확인) 부를 것. 실행 호스트가 그 시점의 확인 기동을 곧바로 기준으로 적는다. */
    @Volatile
    var onIssuing: (() -> Unit)? = null

    /** 플릿 준비. 래치됐으면 그것, 아니면 까닭(후보가 있으면 [FleetWaitReason.RECONCILING]). */
    fun fleetReadiness(): FleetReadiness = synchronized(fleetLock) {
        fleetReady ?: fleetCandidate?.let { FleetReadiness.Waiting(FleetWaitReason.RECONCILING, it.floor, it.fenceStatus, it.bootId) } ?: fleetWaiting
    }

    /** 재대조를 기다리는 후보(관측 중에만). pump 가 이것이 있으면 확인 뒤에 보낸 플릿 읽기로 한 라운드를 돈다. */
    fun fleetCandidate(): FleetCandidate? = synchronized(fleetLock) { fleetCandidate?.takeIf { state == LeaderState.HOLDING && fleetReady == null } }

    /**
     * 플릿 인수 확인의 답 하나를 본다(호스트 잠금 밖의 확인 스레드). [seq] 는 그 확인 요청의 순번(플릿 읽기와 같은 순번), [answer] 는 답(안 닿으면 `null`),
     * [handleBoots] 는 이 호스트가 아는 운반 핸들의 기동 접두다(복원한 핸들이 다른 기동의 것이면 재시작). 재대조·관측 중에만 뜻이 있다.
     *
     * - 기동 ID 가 기준과 다르면 재시작 — 래치·후보를 풀고 [FleetWaitReason.RECEIVER_RESTARTED] 로 둔다(뒤의 확인 성공으로 지우지 않는다).
     * - 바닥값 = 내 세대 ∧ `DURABLE_CONFIRMED` 면 후보(이미 후보면 경계를 그대로 둔다). 래치돼 있으면 그대로다.
     * - 바닥값 < 내 세대(0 포함)·`VOLATILE`·`UNSPECIFIED` 는 래치를 풀고 후보를 버린다. `DURABLE_PENDING` 은 래치를 풀지 않고(같은 세대의 미확정) 후보만 버린다.
     * - 안 닿음·5xx 는 아무것도 풀지 않는다. 4xx(옛 현장)는 미준비 까닭만 둔다. 바닥값이 더 높으면 밀려남(클라이언트가 이미 잃음으로 넘김).
     */
    fun observeFleetConfirm(seq: Long, answer: dev.picasso.ops.host.lease.ControllerConfirmation?, handleBoots: Set<String>, at: java.time.Instant) {
        val s = state
        if (s != LeaderState.RECONCILING && s != LeaderState.HOLDING) return
        val mine = lease.generation ?: return
        synchronized(fleetLock) {
            if (answer == null || answer.status in 500..599) {
                if (fleetReady == null && fleetCandidate == null && !fleetRestarted) fleetWaiting = FleetReadiness.Waiting(FleetWaitReason.UNREAD)
                return
            }
            if (answer.status != 200) {
                // 4xx: 현장이 플릿 인수 확인을 모른다(옛 현장으로 바뀜 포함) — 래치·후보도 푼다. 409(세대 거부)의 밀려남은 클라이언트가 이미 잃음으로 넘겼다.
                if (answer.status in 400..499 && !fleetRestarted) releaseFleet(FleetReadiness.Waiting(FleetWaitReason.UNSUPPORTED))
                return
            }
            val floor = answer.floor ?: 0L
            val status = answer.fenceStatus.orEmpty()
            val boot = answer.receiverBootId
            if (boot != null) {
                if (fleetBoot == null) fleetBoot = boot
                if (boot != fleetBoot || handleBoots.any { it != boot }) {
                    fleetRestartBasis = fleetRestartBasis ?: (fleetBoot?.takeIf { it != boot } ?: handleBoots.firstOrNull { it != boot })
                    if (!fleetRestarted) log.warn("플릿 현장 기동 ID 가 바뀌었다({} → {}, 핸들 기동 {}) — 플릿 준비를 풀고 운영자 확인을 기다린다", fleetBoot, boot, handleBoots)
                    fleetRestarted = true
                }
            }
            if (fleetRestarted) {
                releaseFleet(FleetReadiness.Waiting(FleetWaitReason.RECEIVER_RESTARTED, floor, status, boot))
                return
            }
            if (floor > mine) return
            val waiting = when {
                floor == 0L || status == "UNSPECIFIED" -> FleetReadiness.Waiting(FleetWaitReason.UNFENCED, floor, status, boot)
                floor < mine -> FleetReadiness.Waiting(FleetWaitReason.FLOOR_MISMATCH, floor, status, boot)
                status == "VOLATILE" -> FleetReadiness.Waiting(FleetWaitReason.VOLATILE, floor, status, boot)
                status == "DURABLE_PENDING" -> FleetReadiness.Waiting(FleetWaitReason.DURABLE_PENDING, floor, status, boot)
                status == "DURABLE_CONFIRMED" && boot != null -> null
                else -> FleetReadiness.Waiting(FleetWaitReason.UNREAD, floor, status, boot)
            }
            if (waiting == null) {
                if (fleetReady == null && fleetCandidate == null) fleetCandidate = FleetCandidate(mine, seq, floor, status, checkNotNull(boot), at)
                return
            }
            if (waiting.reason == FleetWaitReason.DURABLE_PENDING) {
                // 같은 세대의 미확정은 래치를 풀지 않는다(래치 뒤 낮은 세대로 통과한 변경은 없다). 후보는 확인이 아니므로 버린다.
                fleetCandidate = null
                if (fleetReady == null) fleetWaiting = waiting
                return
            }
            releaseFleet(waiting)
        }
    }

    /**
     * 플릿 읽기 응답 하나의 바닥값을 본다(그 요청의 순번 [seq]). 래치·후보의 경계보다 뒤에 보낸 요청의 응답이 바닥값 후퇴(내 세대보다 낮음, 0 포함)를
     * 보이면 푼다. 더 높으면 밀려남(클라이언트가 이미 잃음으로 넘김). 경계보다 앞에 보낸 요청의 늦은 응답으로는 풀지 않는다.
     */
    fun observeFleetFloor(seq: Long, floor: Long) {
        if (state != LeaderState.HOLDING) return
        val mine = lease.generation ?: return
        synchronized(fleetLock) {
            // 응답 역전: 이미 반영한 것보다 앞 순번으로 보낸 요청의 늦은 응답은 해제 근거가 아니다(높은 바닥값의 잃음은 클라이언트가 순번 무관하게 본다).
            if (seq <= fleetFloorSeq) return
            fleetFloorSeq = seq
            if (floor >= mine) return
            val basis = fleetReady?.boundary ?: fleetCandidate?.boundary ?: return
            if (seq <= basis) return
            releaseFleet(FleetReadiness.Waiting(if (floor == 0L) FleetWaitReason.UNFENCED else FleetWaitReason.FLOOR_MISMATCH, floor, null, null))
        }
    }

    /** 바닥값을 반영한 플릿 읽기 응답의 가장 큰 요청 순번. */
    private var fleetFloorSeq = 0L

    private fun releaseFleet(waiting: FleetReadiness.Waiting) {
        fleetCandidate = null
        fleetWaiting = waiting
        if (fleetReady != null) {
            fleetReady = null
            log.warn("플릿 준비를 푼다 — {} (바닥값 {}, {}, 세대 {})", waiting.reason.text, waiting.floor, waiting.fenceStatus, lease.generation)
        }
    }

    /**
     * 래치 직전의 기동 재비교: 후보의 기동 ID 가 이 호스트가 아는 운반 핸들의 기동 접두([handleBoots], 복원한 핸들 포함)와 모두 같은가. 다르면 현장이
     * 그 운반을 맡긴 뒤 다시 떴다 — 재시작으로 두고(재확인 성공으로 지우지 않음) 거짓이다. 확인 답이 핸들을 알기 전에(재대조 중 첫 확인) 왔어도 이것이 잡는다.
     */
    fun fleetBootsAgree(candidate: FleetCandidate, handleBoots: Set<String>): Boolean = synchronized(fleetLock) {
        if (handleBoots.all { it == candidate.bootId }) return@synchronized !fleetRestarted
        log.warn("플릿 후보의 현장 기동 ID({})가 아는 운반 핸들의 기동({})과 다르다 — 플릿 준비를 열지 않고 운영자 확인을 기다린다", candidate.bootId, handleBoots)
        fleetRestarted = true
        fleetRestartBasis = fleetRestartBasis ?: handleBoots.firstOrNull { it != candidate.bootId }
        releaseFleet(FleetReadiness.Waiting(FleetWaitReason.RECEIVER_RESTARTED, candidate.floor, candidate.fenceStatus, candidate.bootId))
        false
    }

    /**
     * 재대조를 마친 후보를 래치한다(근거를 적은 뒤). [candidate] 가 지금 후보와 같고 관측 중이며 재시작을 보지 않았을 때만이다. 래치했는가.
     */
    fun latchFleet(candidate: FleetCandidate): Boolean = synchronized(fleetLock) {
        if (state != LeaderState.HOLDING || lease.generation != candidate.generation || fleetRestarted || fleetCandidate != candidate) return false
        fleetCandidate = null
        fleetReady = FleetReadiness.Ready(candidate.floor, candidate.fenceStatus, candidate.bootId, candidate.boundary, candidate.observedAt)
        log.info("플릿 준비(세대 {}, 바닥값 {}, {}, 기동 {}, 확인 순번 {})", candidate.generation, candidate.floor, candidate.fenceStatus, candidate.bootId, candidate.boundary)
        true
    }

    /**
     * 기체 [robotId] 의 권위. 발행하는 리더면 쥐었다. 관측 중(자기 차단 기한 전)이면 그 기체가 든 실행들의 요구가 **모두** 준비일 때만 쥐었다
     * (든 실행이 없으면 그 기체 자신이 준비여야 한다). 그 밖은 쥐지 않았다.
     */
    fun authorityOf(robotId: String): Authority = authorityOf(robotId, holds = true)

    /**
     * [holds] 가 참이면(미들웨어 발행 관문 — 새 발행만 묻는다) 수신자 재시작 의심 보류도 본다: 발행 중이거나 확정한 의심이 있으면 머리
     * [RESTART_SUSPECT_HELD] 로 쥐지 않았다고 답하고, 관측 중 대조 중인 후보는 준비 판정의 겉([readinessOf])으로 인수 확인 대기에 실린다. 거짓이면(취소의
     * 송신 직전 확인) 보류를 보지 않는다.
     */
    private fun authorityOf(robotId: String, holds: Boolean): Authority {
        if (holds && restartHolds.blocks(robotId) && (mayIssue() || restartHolds.suspected(robotId))) {
            return Authority.NotHeld("$RESTART_SUSPECT_HELD — 기체 $robotId: ${restartHolds.describe(robotId) ?: WaitReason.RESTART_SUSPECT.text}")
        }
        if (mayIssue()) return Authority.Held
        val s = state
        if (s != LeaderState.HOLDING || !lease.withinDeadline()) return Authority.NotHeld("발행하는 리더가 아니다: $s")
        val holding = requirements.filter { robotId in it.subjects || robotId in it.robots }
        val reasons = if (holding.isEmpty()) {
            when (val r = if (holds) readinessOf(robotId) else rawReadinessOf(robotId)) {
                is Readiness.Ready -> emptyList()
                is Readiness.Waiting -> listOf("기체 $robotId: ${r.reason.text}")
            }
        } else {
            holding.flatMap { blockers(it, holds) }.distinct()
        }
        return if (reasons.isEmpty()) Authority.Held else Authority.NotHeld("$AWAITING_CONFIRMATION — ${reasons.joinToString("; ")}")
    }

    /** 미들웨어 발행 관문의 첫째 입력(picasso `AuthorityPort`). 메모리만 읽는다(I/O 없음). */
    val authorityPort: AuthorityPort = AuthorityPort { robots -> robots.associateWith(::authorityOf) }

    /**
     * 기체 단위 송신 직전 확인: 전역 발행이거나, 관측 중 **그 기체 자신의 래치** ∧ 그 기체가 든(기체 단위로든 발행 관문의 기체로든) 실행들의 요구가 모두
     * 준비다. 취소도 같다. 플릿 전용 실행에 묶인 기체는 미들웨어 발행 관문([authorityOf])에서는 플릿 준비만으로 쥐지만, 그 기체로 실제 명령이 나가는
     * 이 확인은 그 기체의 수신자 확인을 늘 요구한다(같은 pump 안의 재할당, 실행 밖에서 그 기체를 겨누는 취소).
     */
    override fun mayIssue(robotId: String): Boolean {
        if (mayIssue()) return true
        if (rawReadinessOf(robotId) !is Readiness.Ready) return false
        return authorityOf(robotId, holds = false) == Authority.Held
    }

    /**
     * 운반 송신 직전 확인: 전역 발행이거나, 관측 중 참조(태스크 id `작업지시#단위`)로 되짚은 실행의 «플릿 준비 ∧ 그 실행의 기체 전부 준비».
     * 되짚지 못하면 막는다. 플릿 준비는 래치된 준비([fleetReadiness])만 친다.
     */
    override fun mayDispatch(reference: String): Boolean {
        if (mayIssue()) return true
        if (state != LeaderState.HOLDING || !lease.withinDeadline()) return false
        val jobOrderId = reference.substringBefore('#')
        val requirement = requirements.firstOrNull { it.jobOrderId == jobOrderId } ?: return false
        return blockers(requirement.copy(fleet = true), holds = false).isEmpty()
    }

    /**
     * 수신자가 이 세대보다 높은 바닥값을 알려 왔다(picasso `ClientRobotPort.Superseded`, 읽기·명령 응답 머리말과 `BELOW_FLOOR` 거절). **밀려남만**
     * 잃음으로 간다 — 내 세대가 0 이거나(대기) 바닥값이 내 세대 이하면 아무것도 하지 않는다(세대 0·범위 밖·자격 없음 거절은 그 명령만의 일이다).
     */
    fun superseded(robotId: String, mine: Long, floor: Long) {
        if (mine <= 0 || floor <= mine) return
        lost("수신자가 더 높은 세대로 넘겨받았다고 알렸다: 대상=$robotId 내 세대 $mine 바닥값 $floor")
    }

    /** DB 쓰기 가드가 쓸 (보유자, 세대). 리스를 쥐고 발행 쪽 상태(재대조·관측·발행)일 때만 있다 — 정상 종료 중·잃음에는 없다. */
    fun claim(): LeaseClaim? {
        val s = state
        if (s != LeaderState.RECONCILING && s != LeaderState.HOLDING && s != LeaderState.ISSUING) return null
        return lease.generation?.let { LeaseClaim(instanceId, it) }
    }

    /**
     * 듣는 쪽을 걸고 리스를 한 번 곧바로 얻어 본다. 얻으면 이 스레드에서 [LeadershipListener.acquired] 를 불러 재대조를 끝낸 뒤 돌아온다(기동
     * 복원이 웹 서버보다 먼저). 못 얻으면 [LeaderState.STANDBY] 로 두고 주기마다 다시 얻어 본다 — 그때의 재대조는 [takeover] 실행기에서 돈다.
     */
    fun begin(listener: LeadershipListener, takeover: (Runnable) -> Unit) {
        this.listener = listener
        val first = try {
            lease.acquire()
        } catch (e: Exception) {
            log.warn("리스 획득 실패(대기로 둔다): {}", e.toString())
            null
        }
        first?.let(::enter)
        val period = timing.period.toMillis()
        leaseThread.scheduleWithFixedDelay({ tick(takeover) }, period, period, TimeUnit.MILLISECONDS)
        val watch = maxOf(20L, period / 5)
        watchdog.scheduleWithFixedDelay(::watch, watch, watch, TimeUnit.MILLISECONDS)
        first?.let(::reconcile)
    }

    /**
     * 재대조를 마쳤다. 곧바로 발행해도 되는 인수([Acquired.graceful])면 «이 세대가 발행을 연다» 를 리스에 적은 뒤 발행하고, 아니면(또는 그 표시를 못
     * 적으면) 관측만 한다.
     */
    fun reconciled() {
        val next = if (acquired?.graceful == true && marked()) LeaderState.ISSUING else LeaderState.HOLDING
        if (stateRef.compareAndSet(LeaderState.RECONCILING, next)) {
            log.info("리더 상태 {} (세대 {})", next, lease.generation)
            if (next == LeaderState.ISSUING) onIssuing?.invoke()
        }
    }

    /**
     * 운영자가 옛 프로세스 종료를 확인했다. [LeaderState.HOLDING] 에서만 «이 세대가 발행을 연다» 를 리스에 적고 [LeaderState.ISSUING] 으로 간다.
     * 적지 못하면(이 세대가 아님) 잃음으로 가고 거짓이다. 연결 장애는 예외로 나간다(상태는 그대로).
     */
    @Synchronized
    fun confirmed(): Boolean {
        if (state != LeaderState.HOLDING) return false
        if (!lease.markIssuing()) {
            lost("발행 표시를 적지 못했다(이 세대의 리스가 아니다)")
            return false
        }
        return stateRef.compareAndSet(LeaderState.HOLDING, LeaderState.ISSUING).also { if (it) onIssuing?.invoke() }
    }

    private fun marked(): Boolean = try {
        lease.markIssuing()
    } catch (e: Exception) {
        log.warn("발행 표시를 적지 못해 관측으로 둔다: {}", e.toString())
        false
    }

    /** 리스를 얻은 스레드에서 곧바로 [LeaderState.RECONCILING] 에 든다(다음 주기부터 갱신한다). */
    private fun enter(acquired: Acquired) {
        this.acquired = acquired
        check(stateRef.compareAndSet(LeaderState.STANDBY, LeaderState.RECONCILING)) { "대기가 아닌데 리스를 얻었다: $state" }
        log.info(
            "리스를 얻었다: 세대 {}, 앞 보유자 {} (멈춤 {}, 앞 세대 발행 {}, 다른 산 인스턴스 {})",
            acquired.generation, acquired.previousHolder, acquired.previousStopped, acquired.previousIssuing, acquired.othersLive,
        )
    }

    /** 재대조. 실패하면 리스를 잃은 것으로 다룬다(다시 얻지 않는다). */
    private fun reconcile(acquired: Acquired) {
        try {
            checkNotNull(listener).acquired(acquired)
        } catch (e: Exception) {
            lost("재대조 실패: $e")
            throw e
        }
    }

    private fun tick(takeover: (Runnable) -> Unit) {
        stall.pass(Stall.Part.LEASE)
        try {
            when (state) {
                LeaderState.STANDBY -> lease.acquire()?.let { got ->
                    enter(got)
                    takeover(Runnable { runCatching { reconcile(got) } })
                }
                LeaderState.RECONCILING, LeaderState.HOLDING, LeaderState.ISSUING, LeaderState.STOPPING ->
                    if (!lease.renew()) lost("리스 갱신이 거부됐다(보유자·세대가 다르거나 이미 만료)")
                LeaderState.LOST -> Unit
            }
        } catch (e: Exception) {
            log.warn("리스 {} 실패: {}", if (state == LeaderState.STANDBY) "획득" else "갱신", e.toString())
        }
    }

    /** 리스를 쥔 동안 자기 차단 기한이 지났으면 잃은 것으로 다룬다. */
    private fun watch() {
        stall.pass(Stall.Part.WATCH)
        val s = state
        if (s != LeaderState.STANDBY && s != LeaderState.LOST && !lease.withinDeadline()) {
            lost("자기 차단 기한이 지났다(제때 갱신하지 못함)")
        }
    }

    /**
     * 리스를 잃었다. 한 번만 돈다. 상태를 곧바로 [LeaderState.LOST] 로 바꿔 송신 확인과 DB 가드를 막고, 새 스레드에서 pump 정지·자기 차단 기록
     * ([LeadershipListener.lost]) 뒤 종료 훅을 부른다. 다시 얻지 않는다.
     */
    fun lost(reason: String) {
        while (true) {
            val s = state
            if (s == LeaderState.LOST || s == LeaderState.STANDBY) return
            if (stateRef.compareAndSet(s, LeaderState.LOST)) break
        }
        lostReason = reason
        lease.forget()
        log.error("리스를 잃었다: {}", reason)
        Thread({
            try {
                listener?.lost(reason)
            } catch (e: Exception) {
                log.warn("자기 차단 처리 실패: {}", e.toString())
            } finally {
                leaseThread.shutdownNow()
                watchdog.shutdownNow()
                exit.exit(reason)
            }
        }, "mission-host-lost").start()
    }

    /** 정상 종료를 시작한다. 리스를 쥔 발행 쪽 상태면 [LeaderState.STOPPING] 으로 가서 송신·DB 쓰기·변경 HTTP 를 닫는다. 그 상태였는가. */
    fun stopping(): Boolean {
        while (true) {
            val s = state
            if (s != LeaderState.RECONCILING && s != LeaderState.HOLDING && s != LeaderState.ISSUING) return false
            if (stateRef.compareAndSet(s, LeaderState.STOPPING)) return true
        }
    }

    /** 송신 직전 확인을 지난 명령이 다 끝날 때까지 [timeoutMillis] 만큼 기다린다. 0 이 되었는가. */
    fun awaitQuiet(timeoutMillis: Long): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (inFlight.get() > 0) {
            if (System.nanoTime() - until > 0) return false
            Thread.sleep(5)
        }
        return true
    }

    /** 리스를 내놓는다(정상 종료). 잃었으면 하지 않는다. */
    fun release() {
        leaseThread.shutdownNow()
        watchdog.shutdownNow()
        leaseThread.awaitTermination(2, TimeUnit.SECONDS)
        if (state != LeaderState.LOST) lease.release()
    }

    override fun close() {
        leaseThread.shutdownNow()
        watchdog.shutdownNow()
        lease.close()
    }

    /** 프로세스 멈춤 흉내. 닫은 부분을 지나는 스레드는 열릴 때까지 기다린다. */
    class Stall {
        enum class Part { LEASE, WATCH, PUMP, BEAT }

        private val gates = EnumMap<Part, CountDownLatch>(Part::class.java)

        @Synchronized
        fun close(vararg parts: Part = Part.entries.toTypedArray()) {
            parts.forEach { if (gates[it] == null) gates[it] = CountDownLatch(1) }
        }

        @Synchronized
        fun open(vararg parts: Part = Part.entries.toTypedArray()) {
            parts.forEach { gates.remove(it)?.countDown() }
        }

        fun pass(part: Part) {
            synchronized(this) { gates[part] }?.await()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HostLeadership::class.java)

        /** [unreadRetry] 의 기본값. */
        val DEFAULT_UNREAD_RETRY: java.time.Duration = java.time.Duration.ofSeconds(10)

        /** 관측 중 준비되지 않은 기체의 권위 거절 문구 머리. 화면이 `AUTHORITY_NOT_HELD` 와 함께 싣는다. */
        const val AWAITING_CONFIRMATION = "인수 확인 대기"

        /** 보류 기체 회복 읽기의 pump 당 상한. */
        const val HELD_READS = 1

        /** 수신자 재시작 의심 보류로 쥐지 않은 권위의 문구 머리(관측·발행 모두). 화면이 `AUTHORITY_NOT_HELD` 와 함께 싣는다. */
        const val RESTART_SUSPECT_HELD = "수신자 재시작 의심"
    }
}
