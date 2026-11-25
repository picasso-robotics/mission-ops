package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.CommandNotSent
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.FleetReadiness
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.Readiness
import dev.picasso.ops.host.lease.SendAuthority
import dev.picasso.ops.host.store.AuthorityLost
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.NotAuthority
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

/** 리스 행(DB). 못 읽었으면 칸이 비어 있다. */
data class LeaseView(val holder: String?, val generation: Long?, val expiresAt: Instant?, val live: Boolean?)

/** 만료 인수 뒤 운영자 확인(«옛 프로세스 종료 확인»). */
data class TakeoverConfirmationView(val confirmedBy: String, val reason: String, val confirmedAt: Instant)

/** 이번 보유의 인수. [graceful] 이면 앞 보유자가 멈춤을 적어 곧바로 발행했고, 아니면 만료 인수라 운영자 확인([confirmation]) 뒤에 발행한다. */
data class TakeoverView(val generation: Long, val previousHolder: String?, val graceful: Boolean, val confirmation: TakeoverConfirmationView?)

/**
 * 기체 하나의 준비(HA 3단계). [ready] 면 [basis] 가 `AUTO`(수신자가 이 세대를 내구 저장·정리 확인해 자동으로 열림)이고, 아니면 [reasonCode]·[reason]
 * 이 까닭이다([dev.picasso.ops.host.lease.WaitReason]). [ready] 면 [floor]·[fenceStatus] 는 연 근거(지금 읽기)의 값이고, 아니면 마지막으로 본 응답(미리
 * 읽기 포함)의 값이다(읽지 못했으면 `null`). 여는 판정만 지금 읽기로 한다.
 */
data class RobotReadinessView(
    val robotId: String,
    val ready: Boolean,
    val basis: String?,
    val reasonCode: String?,
    val reason: String?,
    val floor: Long?,
    val fenceStatus: String?,
    /** 순번을 몰라 옛 응답으로 다룬 미리 읽기 응답의 누계(0 이 아니면 응답 객체의 동일성이 깨졌을 수 있다). */
    val unknownSeqResponses: Long = 0,
    /**
     * 래치된 기체인데 요청 순번상 가장 뒤의 성공한 응답이 같은 세대 `DURABLE_PENDING` 인가(이 세대 자신의 결과 모르는 변경 호출 — 래치는 그대로,
     * [fenceStatus] 는 래치 근거의 값). 읽기 실패나 늦게 소비한 앞 순번 응답은 이것을 덮지 않는다.
     */
    val latchedPending: Boolean = false,
)

/**
 * 수신자 재시작 의심 보류 하나(HA 후속 D1 완화). [state] 는 `CANDIDATE`(앞 세대가 보냈을 수 있어 수신자 장부와 대조 중 — 막음)·`ATTACHED`(수신자
 * 장부에 붙어 보여 막지 않되 응답마다 다시 봄 — 이 세대가 그 출발을 내거나 단위가 판정을 얻으면 닫힘)·`SUSPECT`(의심 — 막음, 운영자 단위 해제나 뒤의
 * 붙음으로 닫힘). [suspicionId] 는 저장한 의심 행이고 후보·붙음이거나 의심을 아직 못 적었으면(다음 pump 에 다시 적음) `null` 이다 — `null` 인 의심은
 * 해제할 수 없다. [basisKind] 는 의심의 근거 종류(`ABSENT`·`REVISION_UNKNOWN`·`ISSUING_PENDING`·`DRIVE_ENDED`·`RESTORE_ABANDONED`, 의심이 아니면
 * `null`). [executionId] 는 그 보류를 세운 보유자의 실행 id 다(앞 보유자의 것이거나 없을 수 있다). [basisIntentId] 는 판정 때 그 태스크의 가장 큰 송신
 * 의도 순번(없으면 0)이다.
 */
data class RestartHoldView(
    val suspicionId: Long?,
    val state: String,
    val basisKind: String?,
    val robotId: String,
    val jobOrderId: String,
    val executionId: String?,
    val unitId: String,
    val attempt: Int,
    val taskId: String,
    val revision: Int?,
    val basisIntentId: Long,
    val generation: Long?,
)

/** 실행 하나의 요구(기체 단위를 맡은 기체 전부, 플릿 단위가 있으면 플릿)와 준비. [blockers] 가 비면 관측 중에도 그 실행의 기체 명령을 보낸다. */
data class ExecutionReadinessView(
    val executionId: String,
    val jobOrderId: String,
    val robots: List<String>,
    val fleet: Boolean,
    val ready: Boolean,
    val blockers: List<String>,
)

/**
 * 플릿 하나의 준비(HA 3단계 PR B2). [ready] 면 [basis] 가 `AUTO`(자동으로 열림)이고 [floor]·[fenceStatus]·[receiverBootId]·[confirmSeq] 는 연 근거(재대조
 * 경계가 된 인수 확인)의 값이다. 아니면 [reasonCode]·[reason] 이 까닭([dev.picasso.ops.host.lease.FleetWaitReason])이고 나머지는 마지막으로 본 확인 답의 값이다.
 */
data class FleetReadinessView(
    val ready: Boolean,
    val basis: String?,
    val reasonCode: String?,
    val reason: String?,
    val floor: Long?,
    val fenceStatus: String?,
    val receiverBootId: String?,
    val confirmSeq: Long?,
)

/** 대상별 준비. 관측([LeaderState.HOLDING]) 안에서만 발행을 가른다. */
data class ReadinessView(val robots: List<RobotReadinessView>, val executions: List<ExecutionReadinessView>, val fleet: FleetReadinessView)

/**
 * `GET /host/leader`·`GET /host/ready` 의 본문. [ready] 는 발행하는 리더([LeaderState.ISSUING])이고 자기 차단 기한 전일 때만 참이다.
 * [generation] 은 이 인스턴스가 쥔 세대(없으면 `null`), [lease] 는 DB 의 리스 행이다(보유자가 다른 인스턴스일 수 있다).
 *
 * HA 3단계 칸: [unresolvedHolders] 는 운영자 확인이 지목해야 할 미해소 원천 보유자 집합(정렬, 리스를 쥐지 않았으면 `null`)이고
 * [unresolvedHistoryComplete] 는 그 셈이 발행을 연 세대(또는 세대 1)까지 이력을 다 보았는가다(V20 전의 세대는 이력이 없다). [issuingBasis] 는 전역
 * 발행의 근거 — `GRACEFUL`(정상 종료 인수)·`OPERATOR`(사람의 확인)·`null`(발행 전). [readiness] 는 대상별 준비와 까닭이다(자동 확인은 기체마다
 * `basis: AUTO`).
 */
data class LeaderView(
    val instanceId: String,
    val state: LeaderState,
    val ready: Boolean,
    val generation: Long?,
    val lease: LeaseView,
    val takeover: TakeoverView?,
    val lostReason: String?,
    val unresolvedHolders: List<String>? = null,
    val unresolvedHistoryComplete: Boolean? = null,
    val issuingBasis: String? = null,
    val readiness: ReadinessView? = null,
    /** 수신자 재시작 의심 보류(관측·발행 모두, 붙음 포함). */
    val restartHolds: List<RestartHoldView> = emptyList(),
    /** 복원을 미뤄(`DEFERRED`) 다시 지을 때까지 막는 작업 지시와 그 기체들(의심 ID 없음 — 다시 짓거나 포기·종결하면 후보·의심으로 넘어감). */
    val restartDeferred: List<RestartDeferredView> = emptyList(),
)

/** 복원을 미뤄 막는 작업 지시 하나와 그 기체들. */
data class RestartDeferredView(val jobOrderId: String, val robotIds: List<String>)

/**
 * 발행하는 리더가 아니어서 받지 않은 변경 요청(503). [holder]·[generation] 은 DB 의 리스 행이다. [holder] 는 인스턴스 id(`mw-<UUID>`)이지 주소가
 * 아니다 — 보낼 곳은 각 호스트의 `GET /host/leader` 로 그 id 를 가진 호스트를 찾아 정한다.
 */
data class NotIssuingRejection(
    val error: String,
    val detail: String,
    val state: LeaderState,
    val instanceId: String,
    val holder: String?,
    val generation: Long?,
)

/**
 * `POST /host/leader/takeover-confirmation` 의 본문. 누가 어떤 근거로 옛 프로세스가 끝난 것을 확인했는가와, 확인한 대상 — 운영자가 `GET /host/leader`
 * 에서 본 이 호스트의 인스턴스 id([instanceId])·세대([generation])·앞 보유자([previousHolder], 없으면 `null`)·미해소 원천 보유자
 * ([unresolvedHolders], `GET /host/leader` 의 같은 칸). 대상이 지금과 다르면 받지 않는다. [unresolvedHolders] 를 싣지 않으면 앞 보유자 하나(없으면
 * 빈 집합)를 확인한 것으로 읽는다(앞 버전 본문 호환). 그래서 조회한 집합을 그대로 싣고 빈 집합이면 빈 배열을 명시한다 — 앞 보유자가 멈춤·자기 차단을 적었거나
 * 확인 안 된 인수가 이어진 연쇄(A 발행 → B 관측·정상 종료 → C 면 B 가 멈췄어도 `[A]`)에서는 앞 보유자 하나와 다르다.
 * 셈이 이력을 다 보지 못했으면(`unresolvedHistoryComplete: false`) [historyIncompleteAcknowledged] 가 참이어야 받는다 — 운영자가 끊긴 세대의 보유자까지
 * 현장에서 확인했다는 표시다.
 */
data class TakeoverConfirmationRequest(
    val confirmedBy: String? = null,
    val reason: String? = null,
    val instanceId: String? = null,
    val generation: Long? = null,
    val previousHolder: String? = null,
    val unresolvedHolders: List<String>? = null,
    val historyIncompleteAcknowledged: Boolean? = null,
)

/**
 * 미해소 원천 보유자 셈의 결과. [holders] 는 정렬한 인스턴스 id, [live] 는 그 가운데 살아 있다는 직접 증거(심장박동 만료 전, 멈춤·자기 차단 없음)가
 * 있는 것, [complete] 는 발행을 연 세대(또는 세대 1)까지 이력을 빈틈없이 보았는가다.
 */
data class UnresolvedHolders(val holders: List<String>, val live: List<String>, val complete: Boolean)

/**
 * `POST /host/restart-holds/release` 의 본문. 해제할 의심([suspicionId])과 그 대상(기체 [robotId]·태스크 [taskId], `GET /host/leader` 의 `restartHolds`
 * 에서 본 값), 지금 호스트([instanceId]·[generation]), 누가([releasedBy]) 무엇을 확인했는가([reason] — 벤더 작업이 끝났거나 효과가 없음).
 */
data class RestartHoldReleaseRequest(
    val suspicionId: Long? = null,
    val robotId: String? = null,
    val taskId: String? = null,
    val instanceId: String? = null,
    val generation: Long? = null,
    val releasedBy: String? = null,
    val reason: String? = null,
)

/**
 * 리더 조회와 만료 인수 뒤 운영자 확인(HA 설계 기록 §7). 운영 서비스는 고치지 않는다 — `ops.host.url` 을 사람이 바꾸거나 앞단 프록시가 [ready] 를 본다.
 */
@RestController
class LeaderController(private val leadership: HostLeadership, private val records: HostRecords) {

    @GetMapping("/host/leader")
    fun leader(): LeaderView = view(leadership, records)

    /** 준비 상태. 발행하는 리더만 200 이고 나머지(대기·재대조·관측·잃음)는 503 이다. 본문은 [leader] 와 같다. */
    @GetMapping("/host/ready")
    fun ready(): ResponseEntity<LeaderView> {
        val view = view(leadership, records)
        return ResponseEntity.status(if (view.ready) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(view)
    }

    /**
     * 만료 인수([LeaderState.HOLDING]) 뒤 운영자가 옛 프로세스가 끝난 것을 확인했다. 누가·왜·언제를 `mission.host_takeover_confirmation` 에 적고
     * (리스 가드 안) 발행([LeaderState.ISSUING])을 연다. 한 번에 하나씩 처리한다.
     * - 칸이 비면 400 `BAD_REQUEST`.
     * - [LeaderState.HOLDING] 이 아니면 409 `NOT_HOLDING`.
     * - 본문의 인스턴스·세대·앞 보유자가 지금과 다르면 409 `TAKEOVER_TARGET_CHANGED`(본 뒤에 호스트가 바뀌었거나 다른 인수가 났다). 본문의
     *   미해소 원천 보유자 집합이 지금 셈([unresolvedHolders])과 다를 때도 같다 — 확인 안 된 인수가 이어진 연쇄(A 발행 → B 관측 → C)에서 C 의 확인은
     *   B 만이 아니라 마지막으로 발행을 연 A 까지 지목해야 한다.
     * - 셈이 이력을 다 보지 못했는데 본문이 그것을 알았다고 하지 않으면(`historyIncompleteAcknowledged`) 409 `HISTORY_INCOMPLETE`.
     * - 앞 보유자나 미해소 원천 보유자 가운데 하나라도 살아 있다는 직접 증거(심장박동 만료 전이고 멈춤·자기 차단 없음)가 있으면 409
     *   `PREVIOUS_HOLDER_LIVE`.
     * - 그 세대의 확인이 이미 있으면: 아직 관측 중이고 그 확인의 대상(인스턴스·앞 보유자)이 같으면 못 마친 발행 전이를 이어 간다(앞 요청이 확인 행만
     *   적고 발행 표시에서 실패한 경우). 그 밖은 409 `ALREADY_CONFIRMED`(이미 발행 중이면 위의 `NOT_HOLDING`).
     */
    @PostMapping(CONFIRM_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Synchronized
    fun confirm(@RequestBody(required = false) body: TakeoverConfirmationRequest?): ResponseEntity<Any> {
        val by = body?.confirmedBy?.trim().orEmpty()
        val reason = body?.reason?.trim().orEmpty()
        if (by.isEmpty() || reason.isEmpty() || body?.instanceId.isNullOrBlank() || body?.generation == null) {
            return ResponseEntity.badRequest().body(HostRejection(HostRequests.BAD_REQUEST, "confirmedBy·reason·instanceId·generation 이 비어 있다"))
        }
        val target = checkNotNull(body)
        val acquired = leadership.acquired
        val generation = leadership.lease.generation
        if (leadership.state != LeaderState.HOLDING || acquired == null || generation == null) {
            return conflict(NOT_HOLDING, "만료 인수 뒤 관측 중인 리더가 아니다: ${leadership.state}")
        }
        if (target.instanceId != leadership.instanceId || target.generation != generation || target.previousHolder != acquired.previousHolder) {
            return conflict(
                TAKEOVER_TARGET_CHANGED,
                "확인한 대상(${target.instanceId}, 세대 ${target.generation}, 앞 보유자 ${target.previousHolder})이 지금" +
                    "(${leadership.instanceId}, 세대 $generation, 앞 보유자 ${acquired.previousHolder})과 다르다",
            )
        }
        val unresolved = unresolvedHolders(records, generation, leadership.instanceId)
        val stated = (target.unresolvedHolders ?: listOfNotNull(target.previousHolder)).map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()
        if (stated != unresolved.holders) {
            return conflict(
                TAKEOVER_TARGET_CHANGED,
                "확인한 미해소 원천 보유자 $stated 가 지금 셈 ${unresolved.holders} 와 다르다(발행을 연 가장 최근 세대부터 직전 세대까지의 보유자 가운데 " +
                    "멈춤·자기 차단을 적지 않은 인스턴스)",
            )
        }
        if (!unresolved.complete && target.historyIncompleteAcknowledged != true) {
            return conflict(
                HISTORY_INCOMPLETE,
                "보유자 이력이 끊겨(이 버전 전의 세대) 미해소 원천 보유자 셈이 ${unresolved.holders} 에서 멈췄다 — 그 앞 세대의 보유자도 현장에서 확인한 뒤 " +
                    "historyIncompleteAcknowledged: true 로 다시 보낸다",
            )
        }
        acquired.previousHolder?.takeIf { records.instanceAlive(it) }?.let {
            return conflict(PREVIOUS_HOLDER_LIVE, "앞 보유자 $it 의 심장박동이 살아 있고 멈춤·자기 차단을 적지 않았다")
        }
        unresolved.live.firstOrNull()?.let {
            return conflict(PREVIOUS_HOLDER_LIVE, "미해소 원천 보유자 $it 의 심장박동이 살아 있고 멈춤·자기 차단을 적지 않았다")
        }
        try {
            records.confirmTakeover(generation, leadership.instanceId, acquired.previousHolder, by, reason, unresolved.holders, unresolved.complete)
        } catch (e: org.springframework.dao.DuplicateKeyException) {
            // 앞 확인이 행만 적고 발행 표시·전이를 못 마쳤으면(연결 장애 따위) 같은 대상의 재요청이 그 전이를 이어 간다(멱등). 다른 대상이면 409.
            val existing = records.takeoverConfirmation(generation)
            if (existing == null || existing.instanceId != leadership.instanceId || existing.previousHolder != acquired.previousHolder) {
                return conflict(ALREADY_CONFIRMED, "세대 $generation 의 확인이 이미 있다")
            }
            // 앞 요청과 이 요청 사이에 셈이 줄기만 했으면(그 사이 멈춤·자기 차단 기록 — 해소뿐) 확인 행이 지목한 집합이 지금 셈을 덮으므로 이어 간다.
            // 새 보유자가 늘었으면 확인 행이 그 보유자를 지목하지 않았으므로 이어 가지 않는다(그 세대는 재기동으로 새 세대에서 확인한다).
            if (!existing.unresolvedHolders.containsAll(unresolved.holders)) {
                return conflict(
                    TAKEOVER_TARGET_CHANGED,
                    "세대 $generation 의 확인 행이 지목한 미해소 원천 보유자 ${existing.unresolvedHolders} 가 지금 셈 ${unresolved.holders} 를 덮지 않는다",
                )
            }
        }
        if (!leadership.confirmed()) return conflict(NOT_HOLDING, "발행을 열지 못했다: ${leadership.state}")
        return ResponseEntity.ok(view(leadership, records))
    }

    /**
     * 수신자 재시작 의심의 운영자 단위 해제(HA 후속 D1 완화). 관측·발행 모두 받는다. 운영자는 현장에서 그 태스크의 벤더 작업이 끝났거나 효과가 없음을
     * 확인한 뒤 보낸다 — 해제 뒤 그 기체로 출발이 다시 나가고, 그 단위가 아직 돌면 같은 태스크 키를 다시 보낸다. 같은 가드 트랜잭션에서 지금 보유자·세대와
     * 의심 행(열림·대상)을 확인하고, 효력은 해제 당시 그 태스크의 가장 큰 송신 의도 순번까지다(그 뒤 새 송신은 다음 인수가 새로 판정). 커밋 뒤 관문에서 닫는다.
     * - 칸이 비면 400 `BAD_REQUEST`, 의심이 없으면 404 `NOT_FOUND`.
     * - 본문의 인스턴스·세대가 지금과 다르면 409 `TAKEOVER_TARGET_CHANGED`, 대상(기체·태스크)이 그 의심과 다르면 409 `RESTART_HOLD_TARGET_CHANGED`,
     *   이미 해제됐으면 409 `RESTART_HOLD_RELEASED`.
     */
    @PostMapping(RELEASE_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Synchronized
    fun release(@RequestBody(required = false) body: RestartHoldReleaseRequest?): ResponseEntity<Any> {
        val by = body?.releasedBy?.trim().orEmpty()
        val reason = body?.reason?.trim().orEmpty()
        if (by.isEmpty() || reason.isEmpty() || body?.suspicionId == null || body.robotId.isNullOrBlank() || body.taskId.isNullOrBlank() ||
            body.instanceId.isNullOrBlank() || body.generation == null
        ) {
            return ResponseEntity.badRequest().body(
                HostRejection(HostRequests.BAD_REQUEST, "suspicionId·robotId·taskId·instanceId·generation·releasedBy·reason 이 비어 있다"),
            )
        }
        // 해제한 사람 칸의 `system:` 머리는 실행 호스트의 시스템 해제 몫이다 — 운영자 해제가 시스템 해제로 읽혀 다음 판별의 해제 범위에서 빠지지 않게.
        if (by.startsWith(RESERVED_RELEASER_PREFIX)) {
            return ResponseEntity.badRequest().body(HostRejection(HostRequests.BAD_REQUEST, "releasedBy 는 '$RESERVED_RELEASER_PREFIX' 로 시작할 수 없다(예약값)"))
        }
        val target = checkNotNull(body)
        fun changed(holder: String?, generation: Long?) = conflict(
            TAKEOVER_TARGET_CHANGED,
            "해제한 대상 호스트(${target.instanceId}, 세대 ${target.generation})가 지금 리스($holder, 세대 $generation)와 다르다",
        )
        // 요청이 겨눈 호스트(인스턴스·세대)는 가드가 잠근 리스 행과 같은 트랜잭션에서 비교한다. 그 사이 인수돼 가드가 이 인스턴스를 거절해도 겨눈 호스트가
        // 지금 리스와 다르면 낡은 요청이라 409 다(503 은 겨눈 호스트가 지금 리스인데 이 인스턴스가 쓰지 못할 때).
        val outcome = try {
            records.releaseRestartSuspicion(
                checkNotNull(target.suspicionId), checkNotNull(target.robotId), checkNotNull(target.taskId), checkNotNull(target.generation),
                checkNotNull(target.instanceId), by, reason,
            )
        } catch (e: RuntimeException) {
            if (e !is NotAuthority && e !is AuthorityLost) throw e
            val row = runCatching { records.leaseRow() }.getOrNull()
            if (row != null && (row.holder != target.instanceId || row.generation != target.generation)) return changed(row.holder, row.generation)
            throw e
        }
        return when (outcome) {
            is dev.picasso.ops.host.store.RestartReleaseOutcome.HostChanged -> changed(outcome.holder, outcome.generation)
            is dev.picasso.ops.host.store.RestartReleaseOutcome.Released -> {
                leadership.restartHolds.release(outcome.suspicion.suspicionId)
                ResponseEntity.ok(view(leadership, records))
            }
            dev.picasso.ops.host.store.RestartReleaseOutcome.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(HostRejection(RESTART_HOLD_NOT_FOUND, "수신자 재시작 의심 #${target.suspicionId} 이 없다"))
            is dev.picasso.ops.host.store.RestartReleaseOutcome.TargetChanged -> conflict(
                RESTART_HOLD_TARGET_CHANGED,
                "의심 #${target.suspicionId} 의 대상(기체 ${outcome.suspicion.robotId}, 태스크 ${outcome.suspicion.taskId})이 본문(${target.robotId}, ${target.taskId})과 다르다",
            )
            is dev.picasso.ops.host.store.RestartReleaseOutcome.AlreadyReleased ->
                conflict(RESTART_HOLD_RELEASED, "의심 #${target.suspicionId} 은 이미 ${outcome.suspicion.releasedBy} 가 해제했다")
        }
    }

    private fun conflict(error: String, detail: String): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.CONFLICT).body(HostRejection(error, detail))

    companion object {
        const val CONFIRM_PATH = "/host/leader/takeover-confirmation"
        const val RELEASE_PATH = "/host/restart-holds/release"
        const val RESTART_HOLD_TARGET_CHANGED = "RESTART_HOLD_TARGET_CHANGED"
        const val RESTART_HOLD_RELEASED = "RESTART_HOLD_RELEASED"
        const val RESTART_HOLD_NOT_FOUND = "NOT_FOUND"
        const val RESERVED_RELEASER_PREFIX = "system:"
        const val NOT_HOLDING = "NOT_HOLDING"
        const val TAKEOVER_TARGET_CHANGED = "TAKEOVER_TARGET_CHANGED"
        const val PREVIOUS_HOLDER_LIVE = "PREVIOUS_HOLDER_LIVE"
        const val ALREADY_CONFIRMED = "ALREADY_CONFIRMED"
        const val HISTORY_INCOMPLETE = "HISTORY_INCOMPLETE"
        const val NOT_ISSUING = "NOT_ISSUING"

        /**
         * 미해소 원천 보유자(HA 3단계): 세대 [generation] 직전 세대부터 거꾸로, 발행을 연 가장 최근 세대(그 세대 포함)까지의 보유자 가운데 멈춤도 자기
         * 차단도 적지 않은 인스턴스([me] 제외). 그 세대들은 대조하지 않는 기체에 마지막으로 명령할 수 있었던 프로세스다 — 확인 안 된 인수가 이어지면
         * 직전 보유자 하나로는 모자란다. 이력(V20 `host_lease_grant`)에 빈 세대가 나오면 거기서 멈추고 [UnresolvedHolders.complete] 를 거짓으로 둔다
         * (이 표 전의 세대). 발행을 연 세대 없이 세대 1 까지 닿으면 다 본 것이다.
         */
        fun unresolvedHolders(records: HostRecords, generation: Long, me: String): UnresolvedHolders {
            val holders = sortedSetOf<String>()
            val live = sortedSetOf<String>()
            var expected = generation - 1
            var complete = expected <= 0
            for (grant in records.leaseGrantsBefore(generation)) {
                if (grant.generation != expected) break
                if (!grant.stopped && !grant.fenced && grant.holder != me) {
                    holders += grant.holder
                    if (grant.alive) live += grant.holder
                }
                expected--
                if (grant.issuing || expected <= 0) {
                    complete = true
                    break
                }
            }
            return UnresolvedHolders(holders.toList(), live.toList(), complete)
        }

        fun view(leadership: HostLeadership, records: HostRecords): LeaderView {
            val row = runCatching { records.leaseRow() }.getOrNull()
            val acquired = leadership.acquired
            val generation = leadership.lease.generation
            val confirmation = acquired?.let { a -> runCatching { records.takeoverConfirmation(a.generation) }.getOrNull() }
            val unresolved = generation?.let { g -> runCatching { unresolvedHolders(records, g, leadership.instanceId) }.getOrNull() }
            val state = leadership.state
            return LeaderView(
                instanceId = leadership.instanceId,
                state = state,
                ready = leadership.mayIssue(),
                generation = generation,
                lease = LeaseView(row?.holder, row?.generation, row?.expiresAt, row?.live),
                takeover = acquired?.let { a ->
                    TakeoverView(a.generation, a.previousHolder, a.graceful, confirmation?.let { TakeoverConfirmationView(it.confirmedBy, it.reason, it.confirmedAt) })
                },
                lostReason = leadership.lostReason,
                unresolvedHolders = unresolved?.holders,
                unresolvedHistoryComplete = unresolved?.complete,
                issuingBasis = when {
                    state != LeaderState.ISSUING && state != LeaderState.STOPPING -> null
                    confirmation != null -> "OPERATOR"
                    acquired?.graceful == true -> "GRACEFUL"
                    else -> null
                },
                readiness = readiness(leadership),
                restartHolds = leadership.restartHolds.holds().map {
                    RestartHoldView(
                        it.suspicionId,
                        when (it.state) {
                            dev.picasso.ops.host.lease.RestartHolds.State.PENDING -> "CANDIDATE"
                            dev.picasso.ops.host.lease.RestartHolds.State.ATTACHED -> "ATTACHED"
                            dev.picasso.ops.host.lease.RestartHolds.State.SUSPECT -> "SUSPECT"
                        },
                        it.basis?.name, it.robotId, it.jobOrderId, it.executionId, it.unitId, it.attempt, it.taskId, it.revision, it.basisIntentId, it.generation,
                    )
                },
                restartDeferred = leadership.restartHolds.deferredHolds().map { (job, robots) -> RestartDeferredView(job, robots.sorted()) },
            )
        }

        /** 대상별 준비(메모리). 표의 기체와 준비를 본 적 있는 기체. */
        private fun readiness(leadership: HostLeadership): ReadinessView {
            val requirements = leadership.requirements
            val robots = requirements.flatMapTo(sortedSetOf()) { it.robots }
            return ReadinessView(
                robots = robots.map { robot ->
                    when (val r = leadership.readinessOf(robot)) {
                        is Readiness.Ready ->
                            RobotReadinessView(
                                robot, true, "AUTO", null, null, r.floor, r.fenceStatus.name, leadership.unknownSeqResponses(robot), leadership.latchedPending(robot),
                            )
                        is Readiness.Waiting ->
                            RobotReadinessView(
                                robot, false, null, r.reason.name, r.reason.text, r.floor, r.fenceStatus?.name, leadership.unknownSeqResponses(robot),
                                leadership.latchedPending(robot),
                            )
                    }
                },
                executions = requirements.sortedBy { it.executionId }.map { req ->
                    val blockers = leadership.blockers(req)
                    ExecutionReadinessView(req.executionId, req.jobOrderId, req.robots.sorted(), req.fleet, blockers.isEmpty(), blockers)
                },
                fleet = when (val f = leadership.fleetReadiness()) {
                    is FleetReadiness.Ready -> FleetReadinessView(true, "AUTO", null, null, f.floor, f.fenceStatus, f.bootId, f.boundary)
                    is FleetReadiness.Waiting -> FleetReadinessView(false, null, f.reason.name, f.reason.text, f.floor, f.fenceStatus, f.bootId, null)
                },
            )
        }

        fun rejection(leadership: HostLeadership, records: HostRecords, detail: String): NotIssuingRejection {
            val row = runCatching { records.leaseRow() }.getOrNull()
            return NotIssuingRejection(NOT_ISSUING, detail, leadership.state, leadership.instanceId, row?.holder, row?.generation)
        }
    }
}

/**
 * 변경 요청 관문. 발행하는 리더([LeaderState.ISSUING]·자기 차단 기한 전, `GET /host/ready` 와 같은 기준)가 아니면 읽기(GET·HEAD·OPTIONS) 밖의
 * 요청을 503 `NOT_ISSUING` 과 리스 보유자·세대로 돌려준다. 예외: 관측([LeaderState.HOLDING]) 중의 운영자 확인([LeaderController.CONFIRM_PATH])과
 * 판정 조회(`POST /host/eligibility`, 쓰지 않는다).
 */
@Component
class LeaderGate(private val leadership: HostLeadership, private val records: HostRecords, private val json: ObjectMapper) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val state = leadership.state
        val path = request.requestURI
        // 수신자 재시작 의심 해제는 상태와 무관하게 들인다 — 낡은 요청(겨눈 호스트가 지금 리스가 아님)은 409, 그 밖에 쓰지 못하면 503 을 컨트롤러가 정한다.
        val passes = request.method in READS || leadership.mayIssue() || path == LeaderController.RELEASE_PATH ||
            state == LeaderState.HOLDING && (path == LeaderController.CONFIRM_PATH || path == ELIGIBILITY)
        if (passes) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.outputStream, LeaderController.rejection(leadership, records, "발행하는 리더가 아니어서 변경 요청을 받지 않는다: $state"))
    }

    private companion object {
        val READS = setOf("GET", "HEAD", "OPTIONS")
        const val ELIGIBILITY = "/host/eligibility"
    }
}

/** 요청을 처리하는 도중 리더가 아니게 된 경우(DB 가드, 송신 직전 확인)도 503 `NOT_ISSUING` 이다. */
@RestControllerAdvice
class LeaderAdvice(private val leadership: HostLeadership, private val records: HostRecords) {
    @ExceptionHandler(NotAuthority::class, AuthorityLost::class)
    fun lost(e: RuntimeException): ResponseEntity<NotIssuingRejection> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(LeaderController.rejection(leadership, records, e.message ?: e.toString()))

    @ExceptionHandler(CommandNotSent::class)
    fun notSent(e: CommandNotSent): ResponseEntity<Any> =
        if (e.message?.startsWith(SendAuthority.NOT_ISSUING) == true) {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(LeaderController.rejection(leadership, records, e.message ?: ""))
        } else {
            throw e
        }
}
