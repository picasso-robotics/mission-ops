package dev.picasso.ops.service.joborders

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostExecutionRef
import dev.picasso.ops.service.host.HostExecutions
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSourceConfirmation
import dev.picasso.ops.service.host.HostSubmitOutcome
import dev.picasso.ops.service.host.HostSubmitResult
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 작업 지시 제출의 200 응답(S3a 스펙 §8). 기존 `OperationOutcome` 과 모양이 달라 화면이 따로 읽는다.
 *
 * @param jobOrderId 운영 서비스가 만든 작업 지시 id. 조작 기록의 대상이고 실행 목록의 `jobOrderId` 와 같다. 화면이 제출 직후
 *   실행 목록에서 그 행을 찾는 데 쓴다
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트 응답. 호스트가 안 닿았거나(연결 실패, 시간 초과, 5xx) 200 본문을 못 읽었으면 널이다
 */
data class JobOrderOutcome(
    val requestId: UUID,
    val jobOrderId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: HostSubmitOutcome?,
)

/** 제출 한 번의 답. 후보가 없으면 호스트를 부르지 않는다. */
sealed interface JobOrderSubmission {
    data class Submitted(val outcome: JobOrderOutcome) : JobOrderSubmission

    /** 배정 가능한 기체가 없다. [detail] 은 기체별 이유를 이은 문장이다. 조작 기록에 남지 않는다. */
    data class NoEligibleRobot(val detail: String) : JobOrderSubmission

    /**
     * 실행 호스트가 판정을 거부했다(예: 400 `FLEET_PORT_ABSENT`). 제출하지 않았고 조작 기록에 남지 않는다. [error]·[detail] 은 호스트의 것
     * 그대로다 — «배정 가능한 기체 없음» 으로 접지 않는다.
     */
    data class HostRefused(val error: String, val detail: String) : JobOrderSubmission
}

/**
 * 작업 지시 제출(S3a 스펙 §8). 작업 지시 id 를 만들고, 배정 가능을 다시 판정해 통과한 기체만 후보로 호스트에 넘기고, 조작 기록을
 * 직접 쓴다.
 *
 * `OperationRunner` 를 쓰지 않는 것은 결과를 가르는 자리가 달라서다. registry 는 HTTP 상태 코드로 결과를 말하지만 호스트는
 * 200 본문의 `result` 로 말한다. 응답 없음과 재조회의 규칙은 같다. 응답이 오지 않거나 5xx 면 «응답 없음» 을 남기고
 * [requeryDelay] 뒤 그 작업 지시로 좁힌 `GET /host/executions?jobOrderId=` 를 한 번 읽어, 그 작업 지시 id 의 실행이 있으면 반영됨,
 * 없으면 반영 안 됨을 같은 요청 id 의 새 행으로 붙인다. 재조회도 못 읽으면 행을 붙이지 않는다. 재시도는 하지 않는다.
 *
 * 200 인데 본문을 못 읽으면 응답 없음과 같이 다룬다. 호스트는 답했지만 무엇을 했는지 모르기 때문이다.
 *
 * 후보가 없으면 호스트를 부르지 않고 기록하지 않는다. 상태를 바꾸는 쪽에 닿지 않은 요청은 조작이 아니다(Guard 의 관례).
 */
class JobOrderOperations(
    private val eligibility: JobOrderEligibility,
    private val writes: HostWrites,
    private val reads: HostReads,
    private val log: OperationLog,
    private val clock: Clock,
    private val requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper(),
) {

    /**
     * [sourceConfirmations] 는 이 작업 요청에 사람이 기록한 출발 자리 확인이다. 호스트 본문과 조작 기록의 요청 칸에 함께 싣는다. 없으면 두
     * 본문 모두 그 칸이 없다.
     */
    fun submit(actor: Actor, draft: JobOrderDraft, interfaceRef: String? = null, interfaceInput: JsonNode? = null, requestId: UUID = UUID.randomUUID(), stableJobOrderId: String? = null, sourceConfirmations: List<HostSourceConfirmation> = emptyList()): JobOrderSubmission {
        val jobOrderId = stableJobOrderId ?: jobOrderId(requestId)
        val order = JobOrderForm.jobOrder(draft, jobOrderId, json)
        val judged = eligibility.judge(order)
        judged.hostRefusal?.let { return JobOrderSubmission.HostRefused(it.error, it.detail) }
        val candidates = judged.robots.orEmpty().filter { it.eligible }.map { it.robotId }
        if (candidates.isEmpty()) return JobOrderSubmission.NoEligibleRobot(noEligibleDetail(judged))

        val request = json.createObjectNode().put("op", OP)
        if (interfaceRef != null) request.put("interfaceRef", interfaceRef)
        if (interfaceInput != null) request.set<JsonNode>("interfaceInput", interfaceInput)
        request.set<JsonNode>("jobOrder", order)
        request.putArray("candidates").apply { candidates.forEach(::add) }
        if (sourceConfirmations.isNotEmpty()) request.set<JsonNode>("sourceConfirmations", json.valueToTree(sourceConfirmations))
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String) =
            log.append(requestId, actor, jobOrderId, requestJson, null, result, response)

        val write = writes.submit(order, candidates, sourceConfirmations)
        if (write is HostWrite.NoResponse) {
            record(OperationResult.NO_RESPONSE, json.writeValueAsString(json.createObjectNode().put("cause", write.cause)))
            return confirm(requestId, jobOrderId, ::record)
        }
        write as HostWrite.Answered
        val response = responseJson(write)
        return when (write.status) {
            in 200..299 -> {
                val outcome = parse(write.body)
                if (outcome == null) {
                    record(OperationResult.NO_RESPONSE, response)
                    return confirm(requestId, jobOrderId, ::record)
                }
                val result = resultOf(outcome.result)
                record(result, response)
                JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, result, null, outcome))
            }
            in 400..499 -> {
                record(OperationResult.REJECTED, response)
                JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.REJECTED, null, hostRefused(write)))
            }
            else -> {
                record(OperationResult.NO_RESPONSE, response)
                confirm(requestId, jobOrderId, ::record)
            }
        }
    }

    /** 응답 없음 뒤 재조회. 그 작업 지시로 좁힌 실행 목록을 읽는다(HA 설계 기록 §10). 읽지 못하면 행을 붙이지 않고 확인 결과를 널로 둔다. */
    private fun confirm(
        requestId: UUID,
        jobOrderId: String,
        record: (OperationResult, String) -> Unit,
    ): JobOrderSubmission {
        if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
        val seen = when (val call = reads.executionsOf(jobOrderIds = listOf(jobOrderId))) {
            is HostCall.Ok -> runCatching { json.treeToValue(call.value, HostExecutions::class.java) }.getOrNull()
            is HostCall.Silent -> null
        }
        if (seen == null) {
            return JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.NO_RESPONSE, null, null))
        }
        val execution = seen.executions.firstOrNull { it.jobOrderId == jobOrderId }
        if (execution?.executionId in seen.journalBlockedExecutionIds) return JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.NO_RESPONSE, null, null))
        val confirmation = if (execution != null) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
        record(confirmation, json.writeValueAsString(observed(seen.instanceId, execution)))
        return JobOrderSubmission.Submitted(JobOrderOutcome(requestId, jobOrderId, OperationResult.NO_RESPONSE, confirmation, null))
    }

    /** 확인 행의 응답 칸. 재조회에서 본 그 실행(없으면 널)과 호스트 인스턴스를 남겨, 그 행만 읽어도 판정 근거가 보이게 한다. */
    private fun observed(instanceId: String, execution: HostExecutionRef?): JsonNode {
        val node = json.createObjectNode()
        val seen = execution?.let {
            json.createObjectNode()
                .put("instanceId", instanceId)
                .put("executionId", it.executionId)
                .put("robotId", it.robotId)
                .put("physicalState", it.physicalState)
        }
        node.set<JsonNode>("observed", seen ?: json.nullNode())
        return node
    }

    private fun parse(body: String): HostSubmitOutcome? = try {
        json.readValue(body, HostSubmitOutcome::class.java)
    } catch (e: JacksonException) {
        null
    }

    /**
     * 호스트가 4xx 로 거부했다. 본문은 `{error, detail}` 이고 제출 결과 모양이 아니므로, 화면이 한 모양으로 읽게 거부 사유에 옮긴다.
     * 운영 서비스가 폼을 먼저 검사하므로 정상 흐름에서는 나오지 않는다.
     */
    private fun hostRefused(write: HostWrite.Answered): HostSubmitOutcome {
        val body = runCatching { json.readTree(write.body) }.getOrNull()?.takeIf { it.isObject }
        val reason = listOfNotNull(
            "실행 호스트가 거부했다(HTTP ${write.status})",
            body?.get("error")?.asText(),
            body?.get("detail")?.asText(),
        ).joinToString(": ")
        return HostSubmitOutcome(HostSubmitResult.REJECTED, rejectionReason = reason)
    }

    /** 조작 기록의 응답 칸. 코드와 본문을 남긴다. 본문이 JSON 이 아니면 글자로 남긴다(`OperationRunner` 와 같은 모양). */
    private fun responseJson(write: HostWrite.Answered): String {
        val node = json.createObjectNode().put("status", write.status)
        val body = runCatching { json.readTree(write.body) }.getOrNull()?.takeIf { it.isObject }
        if (body != null) node.set<JsonNode>("body", body) else node.put("body", write.body)
        return json.writeValueAsString(node)
    }

    /** 사이트 날짜(UTC)와 요청 id 앞 8자리. 운영 서비스를 재기동해도 겹치지 않아야 호스트의 멱등 판정에 걸리지 않는다. */
    private fun jobOrderId(requestId: UUID): String =
        "JO-${DATE.format(clock.instant().atOffset(ZoneOffset.UTC))}-${requestId.toString().take(8)}"

    companion object {
        const val OP = "SUBMIT_JOB_ORDER"
        const val NO_ELIGIBLE_ROBOT = "NO_ELIGIBLE_ROBOT"

        /** 실행 호스트의 판정 거부: 플릿 포트가 없어 플릿 운반 단위가 든 계획을 받지 않는다(실행 호스트 `HostRequests.FLEET_PORT_ABSENT`). */
        const val FLEET_PORT_ABSENT = "FLEET_PORT_ABSENT"
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * 호스트 결과를 조작 기록 결과로 옮긴다(스펙 §8). 작업 수락과 멱등은 작업 지시가 실행에 붙은 것이고, 거부와 미배정은 붙지 않은
         * 것이다. 멱등은 운영 서비스가 매번 새 id 를 만들어 실제로는 나오지 않지만 매핑은 둔다(스펙 §7.6).
         */
        fun resultOf(result: HostSubmitResult): OperationResult = when (result) {
            HostSubmitResult.ACCEPTED, HostSubmitResult.IDEMPOTENT -> OperationResult.SUCCEEDED
            HostSubmitResult.REJECTED, HostSubmitResult.UNASSIGNED -> OperationResult.REJECTED
        }

        /** `NO_ELIGIBLE_ROBOT` 의 detail. 기체마다 `id: 이유; 이유` 를 ` / ` 로 잇는다. */
        fun noEligibleDetail(view: EligibilityView): String {
            val robots = view.robots ?: return "기체 목록을 아직 읽지 못했다"
            if (robots.isEmpty()) return "이 사이트에 기체가 없다"
            return robots.joinToString(" / ") { "${it.robotId}: ${it.reasons.joinToString("; ")}" }
        }
    }
}
