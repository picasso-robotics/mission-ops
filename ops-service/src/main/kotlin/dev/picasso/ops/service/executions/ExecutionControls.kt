package dev.picasso.ops.service.executions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionControls
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 운영자 실행 취소의 200 응답(P0-b2).
 *
 * @param result 조작 기록의 결과. `CANCELED` 는 성공, `NOT_CANCELABLE`·`NOT_FOUND`·`REFUSED` 는 거부, 호스트가 안 닿으면 응답 없음이다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했거나 호스트가 그 사이 재기동했으면 널이다
 * @param outcome 호스트 취소 결과 이름 그대로. 호스트가 200 으로 답했을 때만 있다
 * @param answer 호스트의 200 본문 그대로
 * @param rejection 호스트가 4xx 로 막았을 때만 있다(409 `INSTANCE_MISMATCH` 등)
 */
data class ExecutionCancelOutcome(
    val requestId: UUID,
    val executionId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 운영자 결함 감수의 200 응답(S3a JSON 계약 §9.8). 칸은 [ExecutionCancelOutcome] 과 같다.
 *
 * @param result 조작 기록의 결과. `RELEASED` 는 성공, `NOT_BLOCKED`·`BLOCK_CHANGED`·`NOT_FOUND`·`REFUSED` 와 호스트 4xx 는 거부,
 *   호스트가 안 닿으면 응답 없음이다
 * @param outcome 호스트 감수 결과 이름 그대로. 호스트가 200 으로 답했을 때만 있다
 */
data class ExecutionReleaseOutcome(
    val requestId: UUID,
    val executionId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 복원 못 한 실행의 운영자 종결의 200 응답(결함 14). 칸은 [ExecutionCancelOutcome] 과 같다.
 *
 * @param result 조작 기록의 결과. `CLOSED` 는 성공, 그 밖의 호스트 결과(ALREADY_CLOSED·NOT_CLOSABLE·TASK_LIVE·STOP_UNCONFIRMED·TARGET_CHANGED·
 *   NOT_FOUND·REFUSED)와 호스트 4xx 는 거부, 호스트가 안 닿으면 응답 없음이다
 * @param outcome 호스트 종결 결과 이름 그대로. 호스트가 200 으로 답했을 때만 있다
 */
data class ExecutionCloseOutcome(
    val requestId: UUID,
    val jobOrderId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: String?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 잔여 파지 표시 해소(기체 빈손 확인)의 200 응답(P0-b2, B2).
 *
 * @param result 호스트가 해소하면 성공, 404 `RESIDUAL_HOLD_NOT_FOUND`·409 `RESIDUAL_HOLD_ALREADY_CLEARED`·409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH`·
 *   400 `RESIDUAL_HOLD_CITATION_INVALID` 는 거부다
 * @param answer 호스트의 200 본문(해소한 표시와 해소 행) 그대로
 * @param rejection 호스트 4xx. 어긋남(409)이면 [HostRejection.fields] 에 `conflictRequestId`·`observation` 이 있어 화면이 다시 확인할 지목을 만든다
 */
data class RobotEmptyOutcome(
    val requestId: UUID,
    val robotId: String,
    val markerId: Long,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val answer: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 운영자 실행 취소와 기체 빈손 확인(P0-b2), 운영자 결함 감수(S3a JSON 계약 §5.10). 셋 다 운영자 모드의 조작이며(모드 검사는 컨트롤러의
 * 몫) 승인자는 행위자(`X-Ops-User`)다.
 * 호스트는 그 id 로 `Approver(id, PERSON)` 을 만든다(ADR 43). 사유는 조작 기록과 호스트의 표 둘에 남는다.
 *
 * 취소: 부분 완료(`PARTIAL`) 실행은 picasso 가 계속 돌리므로 그 기체가 배정에서 빠져 있다. 운영 서비스는 작업 지시 id 를 다시 쓰지
 * 않아 리비전으로 닫을 길이 없고, 사람이 취소해야 그 실행이 `ABORTED` 로 정착한다. 응답 없음 뒤 재조회는 실행 목록으로 한다. 목록의
 * 인스턴스가 요청한 인스턴스와 같고 그 실행의 `cancel` 이 행위자의 것이며 적은 시각이 보내기 직전 이후이면 반영됨이다. 인스턴스가
 * 다르면 호스트가 그 사이 재기동한 것이라 확인하지 못한다(널).
 *
 * 빈손 확인: 정착할 때 로봇 단위의 마지막 파지 관측이 HOLDING 이던 기체에 호스트가 표시를 세우고, 표시가 있는 동안 그 기체를
 * 배정에서 뺀다. 사람이 현장에서 빈손을 확인해야 푼다. 호스트는 확인할 때 기체 스냅숏을 지금 읽고, 발신자가 지금 든 채라고 말하면 해소하지 않고
 * 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 로 그 관측(충돌)을 돌려준다(B2). 다시 확인하려면 운영자가 그 충돌을 요청 본문의 `cited` 로 지목한다 —
 * `cited` 는 요청 본문에서만 오고 운영 서비스가 호스트 응답이나 목록으로 채우지 않는다. 응답 없음 뒤 재조회는 그 표시 하나의 조회(표시와 해소 행)로
 * 하고 다시 보내지 않는다. 해소 행의 요청 id 가 이 요청의 것일 때만 반영됨이고, 해소 행이 없거나 다른 요청의 해소면 반영 안 됨이다.
 *
 * 복원 못 한 실행의 종결(결함 14): 기동 복원이 GAVE_UP 이나 DEFERRED 로 남긴 일지 행을 운영자가 끝낸다. 호스트가 일지 이벤트와 UNVERIFIED 종결 응답을 한
 * 트랜잭션으로 적고 운영 서비스는 그것을 보통의 결과 수집으로 모은다. 작업 요청 상태 집합은 그대로이고 종결 응답의 `closure` 표시가 결과 버전에 실린다
 * ([dev.picasso.ops.service.requests.WorkRequests.CLOSED_KINDS]).
 *
 * 결함 감수: picasso `release` 는 막는 결함 전부를 감수하므로 운영자가 본 결함(`sawFaults`)을 함께 보내고, 대조는 실행 호스트가
 * 잠금 아래에서 한다. 감수는 결함을 지우지 않고 작업 허가 막음을 풀지 않는다. 재작업이 아니라 계획의 다음 단위로 가므로 재작업
 * 게이트([dev.picasso.ops.service.incidents.ReworkGuard])을 거치지 않는다. 호스트는 요청 id 를 감수 행에 적고 실행 목록의
 * `releases` 로 낸다. 응답 없음 뒤 재조회는 그 실행 하나로 좁힌 실행 목록이다. 목록의 인스턴스가 요청한 인스턴스와 같고 그 실행의
 * `releases` 에 이 요청 id 가 있으면 반영됨, 실행이 없거나 그런 줄이 없으면 반영 안 됨이다. 인스턴스가 다르면 같은 실행 id 가 다른
 * 실행일 수 있어 확인하지 못한다(널).
 */
class ExecutionControls(
    private val host: HostExecutionControls,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    /** 미해소 잔여 파지 표시. 호스트 본문 그대로다. */
    fun residualHolds(): HostCall<JsonNode> = host.residualHolds()

    /** [instanceId]·[reason] 은 컨트롤러가 비어 있지 않음을 본 값이다. */
    fun cancel(actor: Actor, executionId: String, instanceId: String, reason: String): ExecutionCancelOutcome {
        val request = json.createObjectNode()
            .put("op", CANCEL_OP)
            .put("executionId", executionId)
            .put("approverId", actor.user)
            .put("instanceId", instanceId)
        var sentAt: Instant? = null
        val ran = runner.run(
            actor, executionId, request, reason,
            accepted = { body -> cancelResultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { sentAt?.let { at -> recheckCancel(executionId, instanceId, actor.user, at) } },
        ) { requestId ->
            sentAt = clock.instant()
            host.cancelExecution(executionId, actor.user, reason, instanceId, requestId)
        }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return ExecutionCancelOutcome(ran.requestId, executionId, ran.result, ran.confirmation, outcome, ran.body, ran.rejection)
    }

    /** [instanceId]·[reason] 은 컨트롤러가 비어 있지 않음을, [sawFaults] 는 모양을 본 값이다. */
    fun release(actor: Actor, executionId: String, instanceId: String, reason: String, sawFaults: ArrayNode): ExecutionReleaseOutcome {
        val request = json.createObjectNode()
            .put("op", RELEASE_OP)
            .put("executionId", executionId)
            .put("approverId", actor.user)
            .put("instanceId", instanceId)
        request.set<JsonNode>("sawFaults", sawFaults)
        val ran = runner.run(
            actor, executionId, request, reason,
            accepted = { body -> releaseResultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { requestId -> recheckRelease(executionId, instanceId, requestId) },
        ) { requestId -> host.releaseFaults(executionId, actor.user, reason, instanceId, sawFaults, requestId) }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return ExecutionReleaseOutcome(ran.requestId, executionId, ran.result, ran.confirmation, outcome, ran.body, ran.rejection)
    }

    /**
     * 복원 못 한 실행의 운영자 종결(결함 14). [executionInstanceId]·[executionId] 는 운영자가 실행 목록에서 본 종결할 실행이고 [reason] 과 함께 컨트롤러가
     * 비어 있지 않음을 본 값이다. 종결은 호스트가 정지를 확인하는 것(기체 스냅숏, 또는 [attestedStopped] 증언)에 달려 있고 로봇을 멈추지 않으며 성공을
     * 추정하지 않는다. 호스트는 UNVERIFIED 종결 응답을 결과로 내고 운영 서비스가 그것을 결과 버전으로 모은다(작업 요청 상태 집합은 그대로다).
     *
     * 응답 없음 뒤 재조회는 `GET /host/job-orders/{jobOrderId}/closure?requestId=` 다. 종결 행의 요청 id 가 이 요청의 것이면 반영됨이고, 종결이 없거나 다른
     * 요청 id 의 종결이면 반영 안 됨이다. 호스트가 이 요청을 아직 처리 중이면(잠금 대기·스냅숏 읽기) 못 읽은 것과 같이 확인하지 못한다(널). 다른 요청의 종결은
     * 이 요청의 반영이 아니다.
     */
    fun close(
        actor: Actor,
        jobOrderId: String,
        executionInstanceId: String,
        executionId: String,
        reason: String,
        attestedStopped: Boolean,
    ): ExecutionCloseOutcome {
        val request = json.createObjectNode()
            .put("op", CLOSE_OP)
            .put("jobOrderId", jobOrderId)
            .put("approverId", actor.user)
            .put("executionInstanceId", executionInstanceId)
            .put("executionId", executionId)
            .put("attestedStopped", attestedStopped)
        val ran = runner.run(
            actor, jobOrderId, request, reason,
            accepted = { body -> closeResultOf(body.path("result").takeIf { it.isTextual }?.asText()) },
            recheck = { requestId -> recheckClose(jobOrderId, requestId) },
        ) { requestId -> host.closeUnrestored(jobOrderId, actor.user, reason, executionInstanceId, executionId, attestedStopped, requestId) }
        val outcome = ran.body?.path("result")?.takeIf { it.isTextual }?.asText()
        return ExecutionCloseOutcome(ran.requestId, jobOrderId, ran.result, ran.confirmation, outcome, ran.body, ran.rejection)
    }

    /**
     * [reason] 은 컨트롤러가 비어 있지 않음을, [cited] 는 모양을 본 값이다. [cited] 는 운영자 요청 본문의 지목 그대로이고 조작 기록의 요청 칸에도 남는다.
     */
    fun confirmEmpty(actor: Actor, robotId: String, markerId: Long, reason: String, cited: ObjectNode? = null): RobotEmptyOutcome {
        val request = json.createObjectNode()
            .put("op", CONFIRM_EMPTY_OP)
            .put("robotId", robotId)
            .put("markerId", markerId)
            .put("approverId", actor.user)
        if (cited != null) request.set<JsonNode>("cited", cited)
        val ran = runner.run(
            actor, "$robotId/$markerId", request, reason,
            accepted = { body -> if (body.path("clearing").isObject) OperationResult.SUCCEEDED else null },
            recheck = { requestId -> recheckClear(robotId, markerId, requestId) },
        ) { requestId -> host.clearResidualHold(robotId, markerId, actor.user, reason, requestId, cited) }
        return RobotEmptyOutcome(ran.requestId, robotId, markerId, ran.result, ran.confirmation, ran.body, ran.rejection)
    }

    private fun recheckCancel(executionId: String, instanceId: String, approver: String, sentAt: Instant): HostRecheck? {
        val body = (host.executionsOf(executionId) as? HostCall.Ok)?.value ?: return null
        if (body.path("instanceId").asText() != instanceId) return null
        val execution = body.path("executions").takeIf { it.isArray }?.firstOrNull { it.path("executionId").asText() == executionId }
            ?: return HostRecheck(false, null)
        val cancel = execution.path("cancel").takeIf { it.isObject }
        val applied = cancel != null && cancel.path("approverId").asText() == approver &&
            instant(cancel.path("recordedAt"))?.let { it >= sentAt } == true
        return HostRecheck(applied, execution)
    }

    private fun recheckRelease(executionId: String, instanceId: String, requestId: UUID): HostRecheck? {
        val body = (host.executionsOf(executionId) as? HostCall.Ok)?.value ?: return null
        if (body.path("instanceId").asText() != instanceId) return null
        val execution = body.path("executions").takeIf { it.isArray }?.firstOrNull { it.path("executionId").asText() == executionId }
            ?: return HostRecheck(false, null)
        val releases = execution.path("releases").takeIf { it.isArray } ?: return HostRecheck(false, execution)
        return HostRecheck(releases.any { it.path("requestId").asText() == requestId.toString() }, execution)
    }

    private fun recheckClose(jobOrderId: String, requestId: UUID): HostRecheck? = when (val call = host.closure(jobOrderId, requestId)) {
        is HostCall.Silent -> null
        is HostCall.Ok -> {
            val closure = call.value
            HostRecheck(closure != null && closure.path("requestId").asText() == requestId.toString(), closure)
        }
    }

    /** 읽기만 한다. 해소 행의 요청 id 가 [requestId] 일 때만 반영됨이다(다른 요청의 해소는 이 요청의 반영이 아니다). */
    private fun recheckClear(robotId: String, markerId: Long, requestId: UUID): HostRecheck? = when (val call = host.residualHold(robotId, markerId)) {
        is HostCall.Silent -> null
        is HostCall.Ok -> {
            val record = call.value
            val clearing = record?.path("clearing")?.takeIf { it.isObject }
            HostRecheck(clearing != null && clearing.path("requestId").asText() == requestId.toString(), record)
        }
    }

    private fun instant(node: JsonNode): Instant? =
        node.takeIf { it.isTextual }?.let { runCatching { Instant.parse(it.asText()) }.getOrNull() }

    companion object {
        const val CANCEL_OP = "CANCEL_EXECUTION"
        const val CONFIRM_EMPTY_OP = "CONFIRM_ROBOT_EMPTY"

        /** 호스트 취소 결과 이름(P0-b2). */
        const val CANCELED = "CANCELED"
        val CANCEL_REFUSALS: Set<String> = setOf("NOT_CANCELABLE", "NOT_FOUND", "REFUSED")

        fun cancelResultOf(name: String?): OperationResult? = when (name) {
            CANCELED -> OperationResult.SUCCEEDED
            in CANCEL_REFUSALS -> OperationResult.REJECTED
            else -> null
        }

        const val CLOSE_OP = "CLOSE_UNRESTORED"

        /** 호스트 종결 결과 이름(결함 14). */
        const val CLOSED = "CLOSED"
        val CLOSE_REFUSALS: Set<String> =
            setOf("ALREADY_CLOSED", "NOT_CLOSABLE", "TASK_LIVE", "STOP_UNCONFIRMED", "TARGET_CHANGED", "NOT_FOUND", "REFUSED")

        fun closeResultOf(name: String?): OperationResult? = when (name) {
            CLOSED -> OperationResult.SUCCEEDED
            in CLOSE_REFUSALS -> OperationResult.REJECTED
            else -> null
        }

        const val RELEASE_OP = "RELEASE_FAULT_BLOCK"

        /** 호스트 감수 결과 이름(S3a JSON 계약 §5.10). */
        const val RELEASED = "RELEASED"
        val RELEASE_REFUSALS: Set<String> = setOf("NOT_BLOCKED", "BLOCK_CHANGED", "NOT_FOUND", "REFUSED")

        fun releaseResultOf(name: String?): OperationResult? = when (name) {
            RELEASED -> OperationResult.SUCCEEDED
            in RELEASE_REFUSALS -> OperationResult.REJECTED
            else -> null
        }
    }
}
