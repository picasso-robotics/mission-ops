package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.middleware.OperatorDecision
import dev.picasso.ops.host.CitedConflict
import dev.picasso.ops.host.FaultReferenceView
import dev.picasso.ops.host.FaultSighting
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.SourceConfirmation
import java.time.Instant
import java.util.UUID

/** 거부한 요청의 답. 운영 서비스의 사전 거부(`PreRejection`)와 같은 모양이다. */
data class HostRejection(val error: String, val detail: String)

/**
 * 운영자 판단 요청(S4a 스펙 T6). [requestId] 는 운영 서비스가 실은 요청 id 이며 호스트는 응답에 그대로 돌려줄 뿐 저장하지 않는다.
 * 응답 없음 뒤의 재조회는 인시던트의 판단으로 대조한다. [instanceId] 는 운영 서비스가 상세에서 받은 인스턴스다(S4b 스펙 T8).
 */
data class ResolveRequest(val decision: OperatorDecision, val approverId: String, val requestId: String?, val instanceId: String)

/** 운영자 실행 취소 요청(P0-b2). [instanceId] 는 운영 서비스가 실행 목록에서 받은 인스턴스다. [requestId] 는 응답에 그대로 돌려줄 뿐이다. */
data class CancelRequest(val approverId: String, val reason: String, val instanceId: String, val requestId: String?)

/**
 * 운영자 결함 감수 요청(결함 감수·조치 승인 노출 설계 제안 §2.1). [sawFaults] 는 운영자가 본 막는 결함이다. [requestId] 는 감수 행에 적고
 * 같은 id 를 두 번 받지 않는다.
 */
data class ReleaseRequest(val approverId: String, val reason: String, val instanceId: String, val sawFaults: List<FaultSighting>, val requestId: String?)

/**
 * 복원 못 한 실행의 운영자 종결 요청(결함 14). [requestId] 는 필수이고 종결 행에 적는다. [executionInstanceId]·[executionId] 는 운영자가 본 종결할
 * 실행이다(지금 일지와 다르면 `TARGET_CHANGED`). [attestedStopped] 는 운영자가 로봇이 현장에서 멈춘 것을 확인했다는 증언이다.
 */
data class CloseRequest(
    val approverId: String,
    val reason: String,
    val requestId: String,
    val executionInstanceId: String,
    val executionId: String,
    val attestedStopped: Boolean,
)

/** 작업 지시 철회 요청(B3). [requestId] 는 정규형 UUID 문자열이다. */
data class WithdrawRequest(val requestId: String, val reason: String, val requestedBy: String)

/** 잔여 파지 표시 해소 요청(P0-b2). [cited] 는 다시 확인할 때 지목하는 저장된 충돌이다(B2). 없으면 `null` 이다. */
data class ClearRequest(val approverId: String, val reason: String, val requestId: String?, val cited: CitedConflict? = null)

/** 본문을 못 받는 까닭. [error] 가 응답의 `error` 칸이다. */
class BadRequest(val error: String, detail: String) : RuntimeException(detail)

/**
 * 호스트 REST 의 요청 본문(S3a 스펙 §7.5·§7.6). 작업 지시 본문의 칸은 picasso `JobOrder` 와 같다.
 *
 * 스프링에 맡기지 않고 직접 읽는다. 맡기면 못 읽는 본문의 400 이 [HostRejection] 모양이 아니다. 칸은 엄격하게 본다.
 * 문자열 칸에 숫자가 오거나 정수 칸에 소수가 오면 없는 것으로 보고 400 이다.
 */
object HostRequests {

    const val BAD_REQUEST = "BAD_REQUEST"
    const val UNKNOWN_WORK_MASTER = "UNKNOWN_WORK_MASTER"

    /** 지목의 순번 문자열. 부호 있는 10진 정수다. */
    private val SEQUENCE = Regex("-?[0-9]{1,19}")

    /** `{jobOrder, <listField>}` 를 읽는다. [listField] 는 판정이면 `robotIds`, 제출이면 `candidates` 다. */
    fun read(body: JsonNode?, listField: String): Pair<JobOrder, List<String>> {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val order = jobOrder(body.get("jobOrder"))
        val robots = strings(body.get(listField), listField)
        return order to robots
    }

    /**
     * 제출 본문의 선택 칸 `sourceConfirmations` 를 읽는다. 없거나 `null` 이면 빈 목록이다. 있으면 객체의 배열이고 원소마다 `source`·
     * `identity`·`confirmedBy` 가 비어 있지 않은 문자열, `confirmedAt` 이 ISO-8601 시각이다. 같은 자리가 두 번 오면 어느 확인을 쓸지
     * 정할 수 없으므로 400 이다.
     */
    fun sourceConfirmations(node: JsonNode?): List<SourceConfirmation> {
        if (node == null || node.isNull) return emptyList()
        val confirmations = array(node, "sourceConfirmations").map {
            val at = text(it, "confirmedAt").let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
                    ?: throw BadRequest(BAD_REQUEST, "sourceConfirmations 의 confirmedAt 이 ISO-8601 시각이 아니다: $value")
            }
            SourceConfirmation(text(it, "source"), text(it, "identity"), text(it, "confirmedBy"), at)
        }
        val repeated = confirmations.groupBy { it.source }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) throw BadRequest(BAD_REQUEST, "sourceConfirmations 에 같은 자리가 두 번 있다: ${repeated.sorted()}")
        return confirmations
    }

    fun jobOrder(node: JsonNode?): JobOrder {
        if (node == null || !node.isObject) throw BadRequest(BAD_REQUEST, "jobOrder 가 객체가 아니다")
        val workMasterId = text(node, "workMasterId")
        if (workMasterId == "DeliverContainer" || !Regex("[A-Z][A-Za-z0-9]{0,63}").matches(workMasterId)) {
            throw BadRequest(UNKNOWN_WORK_MASTER, "받지 않는 WorkMaster 다: $workMasterId (받는 것: ${MissionHost.WORK_MASTERS.sorted()})")
        }
        val version = node.get("version")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
            ?: throw BadRequest(BAD_REQUEST, "version 이 정수가 아니다")
        if (version < 1) throw BadRequest(BAD_REQUEST, "version 은 1 이상이다: $version")
        val evidence = text(node, "requiredEvidence").let { value ->
            Evidence.entries.firstOrNull { it.name == value }
                ?: throw BadRequest(BAD_REQUEST, "requiredEvidence 가 E0~E3 이 아니다: $value")
        }
        return JobOrder(
            jobOrderId = text(node, "jobOrderId"),
            workMasterId = workMasterId,
            version = version,
            requiredEvidence = evidence,
            parameters = stringMap(node.get("parameters"), "parameters"),
            materialRequirements = array(node.get("materialRequirements"), "materialRequirements").map {
                val quantity = it.get("quantity")?.takeIf { q -> q.isIntegralNumber && q.canConvertToInt() }?.asInt()
                    ?: throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 정수가 아니다")
                if (quantity < 0) throw BadRequest(BAD_REQUEST, "materialRequirements 의 quantity 가 음수다: $quantity")
                MaterialRequirement(text(it, "materialDefinitionId"), quantity)
            },
            equipmentRequirements = array(node.get("equipmentRequirements"), "equipmentRequirements").map {
                EquipmentRequirement(text(it, "id"), text(it, "equipmentUse"), stringMap(it.get("properties"), "properties"))
            },
        )
    }

    /**
     * `{decision, approverId, requestId?, instanceId}` 를 읽는다. decision 은 `CONFIRM_DONE`·`REWORK` 이고 approverId 와
     * instanceId 는 비어 있지 않은 문자열이다(S4b 스펙 T8). requestId 는 없거나 `null` 이거나 UUID 문자열이다.
     */
    fun resolution(body: JsonNode?): ResolveRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val decision = text(body, "decision").let { value ->
            OperatorDecision.entries.firstOrNull { it.name == value }
                ?: throw BadRequest(BAD_REQUEST, "decision 이 ${OperatorDecision.entries.joinToString("·")} 가 아니다: $value")
        }
        val requestId = body.get("requestId")?.takeUnless { it.isNull }?.let { node ->
            node.takeIf { it.isTextual }?.asText()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?: throw BadRequest(BAD_REQUEST, "requestId 가 UUID 문자열이 아니다")
        }
        return ResolveRequest(decision, text(body, "approverId"), requestId, text(body, "instanceId"))
    }

    /**
     * 운영자 실행 취소 `{approverId, reason, instanceId, requestId?}` 를 읽는다(P0-b2). 세 문자열 칸은 비어 있지 않아야 하고 requestId
     * 는 없거나 `null` 이거나 UUID 문자열이다. 사유는 앞뒤 공백을 뗀다.
     */
    fun cancel(body: JsonNode?): CancelRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        return CancelRequest(text(body, "approverId"), text(body, "reason").trim(), text(body, "instanceId"), requestId(body))
    }

    /**
     * 운영자 결함 감수 `{approverId, reason, instanceId, sawFaults, requestId?}` 를 읽는다. 앞 세 칸과 requestId 의 규칙은 [cancel] 과 같다.
     * sawFaults 는 비어 있지 않은 객체의 배열이고 원소마다 `errorType` 이 비어 있지 않은 문자열, `references` 가 객체의 배열(비어도 된다)이며
     * 참조마다 `key` 가 비어 있지 않은 문자열, `value` 가 문자열이다.
     */
    fun release(body: JsonNode?): ReleaseRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val saw = body.get("sawFaults")
        if (saw == null || !saw.isArray || saw.isEmpty || saw.any { !it.isObject }) {
            throw BadRequest(BAD_REQUEST, "sawFaults 가 비어 있지 않은 객체의 배열이 아니다")
        }
        val sightings = saw.map { fault ->
            val references = fault.get("references")
            if (references == null || !references.isArray || references.any { !it.isObject }) {
                throw BadRequest(BAD_REQUEST, "sawFaults 의 references 가 객체의 배열이 아니다")
            }
            FaultSighting(
                text(fault, "errorType"),
                references.map { reference ->
                    val value = reference.get("value")?.takeIf { it.isTextual }?.asText()
                        ?: throw BadRequest(BAD_REQUEST, "sawFaults 의 참조 value 가 문자열이 아니다")
                    FaultReferenceView(text(reference, "key"), value)
                },
            )
        }
        return ReleaseRequest(text(body, "approverId"), text(body, "reason").trim(), text(body, "instanceId"), sightings, requestId(body))
    }

    /**
     * 복원 못 한 실행의 운영자 종결 `{approverId, reason, requestId, executionInstanceId, executionId, attestedStopped}` 를 읽는다(결함 14). 앞 두
     * 칸과 네 번째·다섯 번째 칸은 비어 있지 않은 문자열이고 사유는 앞뒤 공백을 뗀다. requestId 는 취소·수용과 달리 필수이고 UUID 문자열이다(응답 없음 뒤
     * 재조회가 이것으로 대조한다). attestedStopped 는 참거짓 값이다(문자열이나 숫자는 400).
     */
    fun close(body: JsonNode?): CloseRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        // 종결 행과 응답에는 정규형 문자열을 쓴다(운영 서비스가 재조회에서 문자열로 대조한다). 취소·수용·해소의 requestId 와 같이 정규형이 아닌 표기도 받는다.
        val requestId = requestId(body)?.let { UUID.fromString(it).toString() } ?: throw BadRequest(BAD_REQUEST, "requestId 는 필수이고 UUID 문자열이다")
        val attested = body.get("attestedStopped")?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw BadRequest(BAD_REQUEST, "attestedStopped 가 참거짓 값이 아니다")
        return CloseRequest(
            text(body, "approverId"), text(body, "reason").trim(), requestId, text(body, "executionInstanceId"), text(body, "executionId"), attested,
        )
    }

    /**
     * 작업 지시 철회 `{requestId, reason, requestedBy}` 를 읽는다(B3). requestId 는 필수이고 UUID 문자열이다(정규형으로 바꿔 적는다). reason 과
     * requestedBy 는 비어 있지 않은 문자열이고 reason 은 앞뒤 공백을 뗀다.
     */
    fun withdraw(body: JsonNode?): WithdrawRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val requestId = requestId(body)?.let { UUID.fromString(it).toString() } ?: throw BadRequest(BAD_REQUEST, "requestId 는 필수이고 UUID 문자열이다")
        return WithdrawRequest(requestId, text(body, "reason").trim(), text(body, "requestedBy"))
    }

    /**
     * 잔여 파지 표시 해소 `{approverId, reason, requestId?, cited?}` 를 읽는다(P0-b2, B2). 앞 세 칸의 규칙은 [cancel] 과 같다. requestId 는 해소·충돌
     * 행에 정규형으로 적는다. cited 는 없거나 `null` 이거나 객체다: `conflictRequestId` 는 UUID 문자열, `kind` 는 비어 있지 않은 문자열, `objectRef`·
     * `session` 은 없거나 `null` 이거나 문자열(빈 문자열은 `null`), `sequence` 는 없거나 `null` 이거나 10진 정수 문자열이다(관측 JSON 이 문자열로 싣는다.
     * 하위 호환으로 JSON 정수도 받는다).
     */
    fun clearing(body: JsonNode?): ClearRequest {
        if (body == null || !body.isObject) throw BadRequest(BAD_REQUEST, "본문이 JSON 객체가 아니다")
        val cited = body.get("cited")?.takeUnless { it.isNull }?.let { node ->
            if (!node.isObject) throw BadRequest(BAD_REQUEST, "cited 가 객체가 아니다")
            val conflict = node.get("conflictRequestId")?.takeIf { it.isTextual }?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw BadRequest(BAD_REQUEST, "cited.conflictRequestId 가 UUID 문자열이 아니다")
            fun optionalText(field: String): String? = node.get(field)?.takeUnless { it.isNull }?.let {
                if (!it.isTextual) throw BadRequest(BAD_REQUEST, "cited.$field 가 문자열이 아니다")
                it.asText().takeIf { text -> text.isNotEmpty() }
            }
            val sequence = node.get("sequence")?.takeUnless { it.isNull }?.let {
                when {
                    it.isTextual -> it.asText().takeIf { text -> SEQUENCE.matches(text) }?.toLongOrNull()
                    it.isIntegralNumber && it.canConvertToLong() -> it.asLong()
                    else -> null
                } ?: throw BadRequest(BAD_REQUEST, "cited.sequence 가 정수(10진 문자열이나 JSON 정수)가 아니다")
            }
            CitedConflict(conflict, text(node, "kind"), optionalText("objectRef"), optionalText("session"), sequence)
        }
        return ClearRequest(text(body, "approverId"), text(body, "reason").trim(), requestId(body)?.let { UUID.fromString(it).toString() }, cited)
    }

    private fun requestId(body: JsonNode): String? = body.get("requestId")?.takeUnless { it.isNull }?.let { node ->
        node.takeIf { it.isTextual }?.asText()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: throw BadRequest(BAD_REQUEST, "requestId 가 UUID 문자열이 아니다")
    }

    /** 비어 있지 않은 문자열 칸. */
    private fun text(node: JsonNode, field: String): String =
        node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열이 아니다")

    /** 없으면 빈 목록이다. 있으면 객체의 배열이어야 한다. */
    private fun array(node: JsonNode?, field: String): List<JsonNode> {
        if (node == null || node.isNull) return emptyList()
        if (!node.isArray || node.any { !it.isObject }) throw BadRequest(BAD_REQUEST, "$field 가 객체의 배열이 아니다")
        return node.toList()
    }

    /** 없으면 빈 맵이다. 있으면 값이 전부 문자열인 객체여야 한다. */
    private fun stringMap(node: JsonNode?, field: String): Map<String, String> {
        if (node == null || node.isNull) return emptyMap()
        if (!node.isObject) throw BadRequest(BAD_REQUEST, "$field 가 객체가 아니다")
        return node.properties().associate { (key, value) ->
            if (!value.isTextual) throw BadRequest(BAD_REQUEST, "$field.$key 가 문자열이 아니다")
            key to value.asText()
        }
    }

    /** 비어 있지 않은 문자열의 배열. 필수다. */
    private fun strings(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.any { !it.isTextual || it.asText().isBlank() }) {
            throw BadRequest(BAD_REQUEST, "$field 가 비어 있지 않은 문자열의 배열이 아니다")
        }
        return node.map { it.asText() }
    }
}
