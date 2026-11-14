package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.executions.ExecutionControls
import dev.picasso.ops.service.host.HostCall
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 실행 취소와 잔여 파지 표시 API(P0-b2), 운영자 결함 감수 API(S3a JSON 계약 §9.8).
 *
 * 읽기(`GET /api/robots/residual-holds`)는 모드와 관계없고 호스트 본문을 그대로 넘긴다. 호스트가 안 닿으면 503 `HOST_SILENT` 다.
 *
 * 세 조작은 운영자 모드만 한다. 판정 순서는 게이트(헤더 400 `ACTOR_REQUIRED`, 모드 403) → 본문(400 `CANCEL_BAD_REQUEST`·
 * `CLEAR_BAD_REQUEST`·`RELEASE_BAD_REQUEST`) → 사유(400 `REASON_REQUIRED`)다. 모두 호스트에 닿지 않은 사전 거부라 조작 기록에 남지
 * 않는다. 본문은 바이트로 받아 게이트를 지난 뒤 직접 읽고, 쓰기 본문은 `application/json` 만 받는다([IncidentController] 와 같은
 * 규칙). 세 조작은 호스트가 무엇을 답했든 200 과 조작 결과다.
 *
 * 복원 못 한 실행의 운영자 종결(결함 14, `POST /api/job-orders/{jobOrderId}/close`)도 운영자 모드만 하고 같은 규칙이다(본문 400
 * `CLOSE_BAD_REQUEST`, 사유 400 `REASON_REQUIRED`).
 */
@RestController
class ExecutionControlController(private val controls: ExecutionControls) {
    private val json = ObjectMapper()

    @GetMapping("/api/robots/residual-holds")
    fun residualHolds(): ResponseEntity<Any> = when (val call = controls.residualHolds()) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> reject(HttpStatus.SERVICE_UNAVAILABLE, JobOrderController.HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
    }

    /** 본문 `{instanceId, reason}`. instanceId 는 실행 목록의 `instanceId` 다. */
    @PostMapping("/api/executions/{executionId}/cancel", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun cancel(
        @PathVariable executionId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val instanceId = text(node, "instanceId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CANCEL_BAD_REQUEST, "instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)")
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "취소 사유가 없다")
        ResponseEntity.ok(controls.cancel(actor, executionId, instanceId, reason))
    }

    /**
     * 본문 `{instanceId, reason, sawFaults}`. instanceId 는 실행 목록의 `instanceId`, sawFaults 는 그 실행의 `blockedFaults` 에서
     * 운영자가 본 결함 `[{errorType, references:[{key, value}]}]` 이다. 호스트에는 그 칸만 옮겨 보낸다(결함 객체의 다른 칸은 버린다).
     */
    @PostMapping("/api/executions/{executionId}/release", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun release(
        @PathVariable executionId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val instanceId = text(node, "instanceId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, RELEASE_BAD_REQUEST, "instanceId 는 비어 있지 않은 문자열이다(실행 목록의 instanceId)")
        val saw = sawFaults(node?.get("sawFaults"))
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, RELEASE_BAD_REQUEST, SAW_FAULTS_SHAPE)
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "결함 수용 사유가 없다")
        ResponseEntity.ok(controls.release(actor, executionId, instanceId, reason, saw))
    }

    /**
     * 복원 못 한 실행의 운영자 종결(결함 14). 본문 `{executionInstanceId, executionId, reason, attestedStopped}`. 앞 둘은 실행 목록 `unrestored` 행의
     * `previousInstanceId`·`previousExecutionId`(운영자가 본 종결할 실행)이고 `attestedStopped` 는 참거짓 값이다(운영자가 로봇이 멈춘 것을 현장에서
     * 확인했다는 증언, 없으면 400). 대상은 작업 지시 id 다.
     */
    @PostMapping("/api/job-orders/{jobOrderId}/close", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun close(
        @PathVariable jobOrderId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val node = read(body)
        val instanceId = text(node, "executionInstanceId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLOSE_BAD_REQUEST, "executionInstanceId 는 비어 있지 않은 문자열이다(복원 못 한 실행의 previousInstanceId)")
        val executionId = text(node, "executionId")
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLOSE_BAD_REQUEST, "executionId 는 비어 있지 않은 문자열이다(복원 못 한 실행의 previousExecutionId)")
        val attested = node?.get("attestedStopped")?.takeIf { it.isBoolean }?.asBoolean()
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLOSE_BAD_REQUEST, "attestedStopped 는 참거짓 값이다")
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "종결 사유가 없다")
        ResponseEntity.ok(controls.close(actor, jobOrderId, instanceId, executionId, reason, attested))
    }

    /**
     * 본문 `{reason, cited?}`. [markerId] 는 미해소 표시 목록의 `markerId` 다. `cited` 는 실행 호스트가 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 로 돌려준
     * 충돌을 다시 확인할 때 지목하는 것이다(B2): `{conflictRequestId, kind, objectRef?, session?, sequence?}`. `conflictRequestId` 는 UUID 문자열, `kind` 는
     * 비어 있지 않은 문자열, `objectRef`·`session` 은 문자열이나 널, `sequence` 는 10진 정수 문자열(관측 JSON 그대로)이나 정수나 널이다. 모양이 틀리면 400 `CLEAR_BAD_REQUEST` 다. 이 칸은 요청
     * 본문에서만 오고 운영 서비스가 채우지 않는다. 그 칸만 옮겨 호스트에 보낸다.
     */
    @PostMapping("/api/robots/{robotId}/residual-holds/{markerId}/clear", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun clear(
        @PathVariable robotId: String,
        @PathVariable markerId: String,
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        val id = markerId.toLongOrNull()?.takeIf { it > 0 }
            ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLEAR_BAD_REQUEST, "markerId 는 양의 정수다: $markerId")
        val node = read(body)
        val cited = node?.get("cited")?.takeUnless { it.isNull }?.let { cited(it) ?: return@guarded reject(HttpStatus.BAD_REQUEST, CLEAR_BAD_REQUEST, CITED_SHAPE) }
        val reason = reason(node) ?: return@guarded reject(HttpStatus.BAD_REQUEST, REASON_REQUIRED, "빈손 확인 사유가 없다")
        ResponseEntity.ok(controls.confirmEmpty(actor, robotId, id, reason, cited))
    }

    /** 모양이 맞으면 지목의 다섯 칸만 옮긴 새 객체, 아니면 널이다. */
    private fun cited(node: JsonNode): ObjectNode? {
        if (!node.isObject) return null
        val conflict = text(node, "conflictRequestId")?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess } ?: return null
        val kind = text(node, "kind") ?: return null
        val copy = json.createObjectNode().put("conflictRequestId", conflict).put("kind", kind)
        for (field in listOf("objectRef", "session")) {
            val value = node.get(field)
            when {
                value == null || value.isNull -> copy.putNull(field)
                value.isTextual -> copy.put(field, value.asText())
                else -> return null
            }
        }
        // 순번은 10진 문자열로 받고 그대로 넘긴다(2^53 을 넘어도 바뀌지 않게). 하위 호환으로 JSON 정수도 받는다.
        val sequence = node.get("sequence")
        when {
            sequence == null || sequence.isNull -> copy.putNull("sequence")
            sequence.isTextual && Regex("-?[0-9]{1,19}").matches(sequence.asText()) && sequence.asText().toLongOrNull() != null ->
                copy.put("sequence", sequence.asText())
            sequence.isIntegralNumber && sequence.canConvertToLong() -> copy.put("sequence", sequence.asLong())
            else -> return null
        }
        return copy
    }

    private fun read(body: ByteArray?): JsonNode? = body?.let { runCatching { json.readTree(it) }.getOrNull() }?.takeIf { it.isObject }

    private fun text(node: JsonNode?, field: String): String? =
        node?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    /** 모양이 맞으면 `errorType`·`references`(원소마다 `key`·`value`)만 옮긴 새 배열, 아니면 널이다. */
    private fun sawFaults(node: JsonNode?): ArrayNode? {
        if (node == null || !node.isArray || node.isEmpty) return null
        val saw = json.createArrayNode()
        for (fault in node) {
            val errorType = text(fault, "errorType") ?: return null
            val references = fault.get("references")?.takeIf { it.isArray } ?: return null
            val copy = saw.addObject().put("errorType", errorType)
            val refs = copy.putArray("references")
            for (reference in references) {
                val key = text(reference, "key") ?: return null
                val value = reference.get("value")?.takeIf { it.isTextual }?.asText() ?: return null
                refs.addObject().put("key", key).put("value", value)
            }
        }
        return saw
    }

    private fun reason(node: JsonNode?): String? = node?.get("reason")?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val CANCEL_BAD_REQUEST = "CANCEL_BAD_REQUEST"
        const val CLEAR_BAD_REQUEST = "CLEAR_BAD_REQUEST"
        const val RELEASE_BAD_REQUEST = "RELEASE_BAD_REQUEST"
        const val CLOSE_BAD_REQUEST = "CLOSE_BAD_REQUEST"
        const val CITED_SHAPE =
            "cited 는 객체이고 conflictRequestId 는 UUID 문자열, kind 는 비어 있지 않은 문자열, objectRef·session 은 문자열이나 null, sequence 는 10진 정수 문자열이나 정수나 null 이다"
        const val SAW_FAULTS_SHAPE =
            "sawFaults 는 비어 있지 않은 배열이고 원소마다 errorType 은 비어 있지 않은 문자열, references 는 {key, value} 객체의 배열이다"

        /** 현장 설정 변경·임무 활성화·운영자 판단과 같은 이름이다. */
        const val REASON_REQUIRED = MissionVersionController.REASON_REQUIRED
    }
}
