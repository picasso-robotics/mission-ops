package dev.picasso.ops.host

import dev.picasso.ops.host.store.CancelRow
import dev.picasso.ops.host.store.CloseRow
import dev.picasso.ops.host.store.HoldObservation
import dev.picasso.ops.host.store.ResidualHoldClearingRow
import dev.picasso.ops.host.store.ResidualHoldRow
import java.time.Instant
import java.util.UUID

/**
 * 운영자 실행 취소의 결과 이름(P0-b2).
 *
 * - [CANCELED]: 취소를 적고 미들웨어에 걸었다. 실제 중단은 다음 pump 가 하류를 읽고 확정한다(물리 상태 `ABORTED`).
 * - [NOT_CANCELABLE]: 그 실행이 더 돌지 않거나(`PhysicalState.pumps` 가 거짓), 이미 취소를 걸었거나, 실행 일지에 없다.
 * - [NOT_FOUND]: 지금 인스턴스에 그 실행이 없다.
 * - [REFUSED]: 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
 */
enum class CancelResult { CANCELED, NOT_CANCELABLE, NOT_FOUND, REFUSED }

/**
 * `POST /host/executions/{executionId}/cancel` 의 200 본문.
 *
 * @param physicalState 판정한 때의 물리 상태. NOT_FOUND 면 `null` 이다
 * @param detail 결과가 CANCELED 가 아닐 때의 사유. CANCELED 면 `null` 이다
 * @param requestId 요청의 requestId 를 그대로 돌려준다(저장하지 않는다)
 */
data class CancelView(
    val result: CancelResult,
    val executionId: String,
    val physicalState: String?,
    val detail: String?,
    val requestId: String?,
)

/** 실행에 걸린 운영자 취소(실행 목록의 `cancel` 칸). [physicalState] 는 취소를 건 때의 물리 상태, [recordedAt] 은 적은 DB 시각이다. */
data class CancelRecordView(
    val approverId: String,
    val approverKind: String,
    val reason: String,
    val physicalState: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: CancelRow) = CancelRecordView(row.approverId, row.approverKind, row.reason, row.physicalState, row.recordedAt)
    }
}

/**
 * 미해소 잔여 파지 표시 하나(P0-b2). 실행이 정착할 때 경로가 로봇인 단위의 마지막 파지 관측이 HOLDING 이었다. 해소할 때까지 실행
 * 호스트 판정이 그 기체를 배정에서 뺀다. [objectRef] 는 관측이 이름을 싣지 않았으면 `null` 이다.
 */
data class ResidualHoldView(
    val markerId: Long,
    val robotId: String,
    val jobOrderId: String,
    val executionId: String,
    val instanceId: String,
    val unitId: String,
    val objectRef: String?,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: ResidualHoldRow) = ResidualHoldView(
            row.markerId, row.robotId, row.jobOrderId, row.executionId, row.instanceId, row.unitId, row.objectRef, row.recordedAt,
        )
    }
}

/** `GET /host/robots/residual-holds` 의 본문. 표시를 적은 순서다. */
data class ResidualHoldsView(val holds: List<ResidualHoldView>)

/**
 * 해소 한 행. [clearedAt] 은 적은 DB 시각이다. [requestId] 는 V15 전 옛 행만 `null` 이고 그 행의 [observation] 은 `NOT_RECORDED` 다.
 * [observation] 은 해소할 때 실행 호스트가 지금 읽은 기체 손이다. [conflictRequestId] 는 지목한 충돌이고 [acknowledgedMismatch] 는 발신자가 지금 든 채라고
 * 말하는 것을 알고 해소했는가다(관측이 HOLDING 일 때만 참).
 */
data class ResidualHoldClearingView(
    val approverId: String,
    val approverKind: String,
    val reason: String,
    val clearedAt: Instant,
    val requestId: String?,
    val observation: HoldObservation,
    val conflictRequestId: String?,
    val acknowledgedMismatch: Boolean,
) {
    companion object {
        fun of(row: ResidualHoldClearingRow) = ResidualHoldClearingView(
            row.approverId, row.approverKind, row.reason, row.clearedAt, row.requestId?.toString(), row.observation,
            row.conflictRequestId?.toString(), row.acknowledgedMismatch,
        )
    }
}

/**
 * `POST /host/robots/{robotId}/residual-holds/{markerId}/clear` 의 200 본문. 해소한 표시와 해소 행이다. [requestId] 는 해소 행의 요청 id 다(요청이 싣지
 * 않았으면 호스트가 만든 것).
 */
data class ResidualHoldClearedView(val hold: ResidualHoldView, val clearing: ResidualHoldClearingView, val requestId: String?)

/** `GET /host/robots/{robotId}/residual-holds/{markerId}` 의 200 본문. 표시와 그 해소 행이다. 해소하지 않았으면 [clearing] 이 `null` 이다. */
data class ResidualHoldRecordView(val hold: ResidualHoldView, val clearing: ResidualHoldClearingView?)

/**
 * 빈손 확인에서 지목하는 저장된 충돌의 키(B2). [conflictRequestId] 는 앞서 409 를 받은 요청 id 이고 나머지는 그 409 의 관측을 그대로 옮긴 것이다
 * (`observedKind`·`observedObjectRef`·`snapshotSession`·`snapshotSequence`). 호스트는 저장된 충돌과 하나라도 다르면 400 으로 막는다.
 */
data class CitedConflict(val conflictRequestId: UUID, val kind: String, val objectRef: String?, val session: String?, val sequence: Long?)

/** 잔여 파지 표시 해소의 결과. REST 는 [Cleared] 만 200 이고 나머지는 4xx 다. */
sealed interface ClearOutcome {
    data class Cleared(val view: ResidualHoldClearedView) : ClearOutcome

    /** 그 기체에 그 표시가 없다. */
    data object NotFound : ClearOutcome

    /** 이미 해소했다. [clearing] 이 앞선 해소다. */
    data class AlreadyCleared(val clearing: ResidualHoldClearingView) : ClearOutcome

    /** 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다. */
    data class Refused(val reason: String) : ClearOutcome

    /**
     * 발신자가 지금 든 채라고 말한다(V15 충돌 행). 해소하지 않았고 표시는 그대로다. [conflictRequestId] 는 그 충돌 행의 요청 id 이고 [observation] 은
     * 저장한 관측이다. 같은 요청 id 의 재전송도 이 저장된 충돌을 그대로 돌려준다.
     */
    data class ObservationMismatch(val conflictRequestId: String, val observation: HoldObservation, val markerId: Long, val robotId: String) : ClearOutcome

    /** 지목한 충돌이 없거나 저장값과 다르다(다른 표시, 다른 승인자, 다른 관측 키). 아무것도 적지 않았다. */
    data class CitationInvalid(val reason: String) : ClearOutcome

    /** 그 요청 id 를 다른 표시의 충돌이나 해소에 이미 썼다. 아무것도 적지 않았다. */
    data class RequestReused(val reason: String) : ClearOutcome
}

/**
 * 운영자 결함 감수의 결과 이름(결함 감수·조치 승인 노출 설계 제안 §2.1).
 *
 * - [RELEASED]: 감수 행을 적고 picasso `release` 가 막는 결함 전부를 감수했다. 실행은 `RUNNING` 이고 다음 pump 가 다음 단위를 낸다.
 * - [NOT_BLOCKED]: 그 실행에 막는 결함이 없다. 작업 허가만 막고 있거나(감수로 풀리지 않는다), 정착했거나(이 인스턴스가 놓은 실행 포함),
 *   결함 없이 돈다.
 * - [BLOCK_CHANGED]: 운영자가 본 결함과 지금 막는 결함이 다르다. 다시 읽고 판단한다.
 * - [NOT_FOUND]: 지금 인스턴스에 그 실행이 없다(메모리에도 정착 본문에도).
 * - [REFUSED]: 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
 *
 * RELEASED 밖의 넷은 아무것도 바꾸지 않는다.
 */
enum class ReleaseResult { RELEASED, NOT_BLOCKED, BLOCK_CHANGED, NOT_FOUND, REFUSED }

/**
 * `POST /host/executions/{executionId}/release` 의 200 본문.
 *
 * @param physicalState 응답할 때의 물리 상태. RELEASED 면 감수 뒤(`RUNNING`), 이 인스턴스가 놓은 실행이면 마지막 정착 본문의 것, NOT_FOUND·
 *   REFUSED 면 `null` 이다
 * @param detail RELEASED 가 아닐 때의 사유. RELEASED 면 작업 허가가 아직 막고 있을 때만 그 사실이고 아니면 `null` 이다
 * @param requestId 요청의 requestId 를 그대로 돌려준다. RELEASED 면 감수 행에 적혀 있다
 */
data class ReleaseView(
    val result: ReleaseResult,
    val executionId: String,
    val physicalState: String?,
    val detail: String?,
    val requestId: String?,
)

/**
 * 운영자가 본 막는 결함 하나(감수 요청의 `sawFaults` 원소). 지금 막는 결함과 오류 종류와 참조로 대조한다. 참조의 순서는 보지 않는다.
 * 열쇠 문자열은 picasso 의 사설 형식이라 다시 만들지 않는다.
 */
data class FaultSighting(val errorType: String, val references: List<FaultReferenceView>)

/**
 * 실행에 건 운영자 결함 감수 하나(실행 목록의 `releases` 칸). 작업 지시 id 로 읽으므로 재기동 전 인스턴스가 적은 감수도 든다.
 *
 * @param faults 감수한 막는 결함 전부(감수할 때의 [FaultDetailView])
 * @param recordedAt 적은 DB 시각
 */
data class FaultReleaseView(
    val requestId: String?,
    val approverId: String,
    val reason: String,
    val faults: List<FaultDetailView>,
    val recordedAt: Instant,
)

/**
 * 복원 못 한 실행의 운영자 종결의 결과 이름(결함 14). 종결은 일지 행이 GAVE_UP 이거나, 기체 스냅숏을 못 읽어 미룬 DEFERRED 행(`RESUME_SNAPSHOT_UNREADABLE`)일
 * 때만 한다. 살아 있는 다른 인스턴스가 든 행은 종결하지 않는다.
 *
 * - [CLOSED]: 일지 이벤트, 종결 행, UNVERIFIED 결과를 한 트랜잭션으로 적었다. 근거는 [CloseView.basis] 다.
 * - [ALREADY_CLOSED]: 그 작업 지시는 이미 종결했다. [CloseView.closure] 가 앞선 종결이다.
 * - [NOT_CLOSABLE]: 종결할 수 없는 행이다(도는 실행, 종결한 작업 지시, 살아 있는 다른 인스턴스가 든 행, 읽히는 스냅숏 위의 미룬 행 따위).
 * - [TASK_LIVE]: 기체가 그 작업 지시의 태스크를 아직 돌리고 있다. 증언으로도 종결하지 않는다.
 * - [STOP_UNCONFIRMED]: 스냅숏을 못 읽었거나 사람을 기다리는 태스크가 남았는데 정지 증언이 없다.
 * - [TARGET_CHANGED]: 운영자가 본 실행(인스턴스와 실행 id)이 지금 일지의 것과 다르다.
 * - [NOT_FOUND]: 그 작업 지시의 일지 행이 없다.
 * - [REFUSED]: 승인자가 사람이 아니다. REST 는 늘 사람 승인자를 만들므로 이 메서드를 직접 부를 때만 난다.
 *
 * [CLOSED] 밖의 일곱은 아무것도 적지 않는다.
 */
enum class CloseResult { CLOSED, ALREADY_CLOSED, NOT_CLOSABLE, TASK_LIVE, STOP_UNCONFIRMED, TARGET_CHANGED, NOT_FOUND, REFUSED }

/**
 * `POST /host/job-orders/{jobOrderId}/close` 의 200 본문.
 *
 * @param basis CLOSED 일 때 근거(`SNAPSHOT`·`ATTESTED`). 그 밖에는 `null` 이다
 * @param responseId CLOSED 일 때 상위로 낸 종결 응답의 id(`close-<eventId>`). 그 밖에는 `null` 이다
 * @param detail CLOSED 가 아닐 때의 사유. CLOSED 면 `null` 이다
 * @param requestId 요청의 requestId 를 그대로 돌려준다(CLOSED 면 종결 행에 적혀 있다)
 * @param closure ALREADY_CLOSED 일 때 앞선 종결. 그 밖에는 `null` 이다
 */
data class CloseView(
    val result: CloseResult,
    val jobOrderId: String,
    val basis: String?,
    val responseId: String?,
    val detail: String?,
    val requestId: String,
    val closure: CloseRecordView? = null,
)

/**
 * 운영자 종결 한 행(`GET /host/job-orders/{jobOrderId}/closure` 의 200 본문, 실행 목록의 `unrestored` 행의 `close` 칸).
 *
 * @param attestedStopped 운영자가 로봇이 멈춘 것을 증언했다([basis] 가 `ATTESTED`)
 * @param closedInstanceId·closedExecutionId 종결한 실행
 * @param restoreResult·restoreReason 종결 전의 복원 결과(`GAVE_UP`·`DEFERRED`)와 그 사유
 * @param responseId 상위로 낸 종결 응답의 id
 * @param recordedAt 적은 DB 시각
 */
data class CloseRecordView(
    val requestId: String,
    val approverId: String,
    val reason: String,
    val basis: String,
    val attestedStopped: Boolean,
    val closedInstanceId: String,
    val closedExecutionId: String,
    val restoreResult: String,
    val restoreReason: String,
    val responseId: String,
    val recordedAt: Instant,
) {
    companion object {
        fun of(row: CloseRow) = CloseRecordView(
            row.requestId.toString(), row.approverId, row.reason, row.basis, row.basis == "ATTESTED", row.closedInstanceId, row.closedExecutionId,
            row.restoreResult, row.restoreReason, row.responseId, row.recordedAt,
        )
    }
}
