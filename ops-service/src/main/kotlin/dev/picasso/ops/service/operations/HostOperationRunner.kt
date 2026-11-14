package dev.picasso.ops.service.operations

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import java.time.Duration
import java.util.UUID

/**
 * 호스트가 4xx 로 막은 조작의 응답. 본문 `{error, detail}` 을 옮긴다. 본문을 못 읽으면 두 칸이 널이다.
 *
 * [fields] 는 4xx 본문 객체의 `error`·`detail` 밖의 칸이다. 그런 칸이 없으면 널이고 응답 JSON 에 싣지 않는다. 빈손 확인의 409
 * `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 는 여기에 `robotId`·`markerId`·`conflictRequestId`·`observation` 을 실어 화면이 다시 확인할 지목을 만든다(B2).
 *
 * 신호 조작에서는 현장 셀 대역의 거부(안전 신호 쓰기 등)가 호스트를 거쳐 그대로 여기에 온다(ADR 32).
 */
data class HostRejection(
    val status: Int,
    val error: String?,
    val detail: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val fields: JsonNode? = null,
)

/**
 * 응답 없음 뒤 재조회의 판정.
 *
 * @param applied 이 조작이 반영됐는가
 * @param observed 재조회에서 본 대상의 모습. 확인 행의 `target_response` 에 `observed` 로 남는다. 대상이 없으면 널이다
 */
data class HostRecheck(val applied: Boolean, val observed: JsonNode?)

/**
 * 호스트 조작 한 번의 결과. 화면에 내는 모양은 부르는 쪽이 이것으로 만든다.
 *
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param body 호스트가 2xx 로 답하고 그 본문을 읽었을 때의 본문. 그 밖에는 널이다
 * @param rejection 호스트가 4xx 로 답했을 때만 있다
 */
data class HostOperationResult(
    val requestId: UUID,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val body: JsonNode?,
    val rejection: HostRejection?,
)

/**
 * 실행 호스트 조작 한 번을 보내고 조작 기록을 직접 쓴다(S3b 스펙 §7, S3a 의 작업 지시 제출과 같은 규칙).
 *
 * registry 조작의 [OperationRunner] 와 달리 결과를 200 본문의 `result` 로 가른다. 요청 id 는 여기서 만들어 호스트에 넘긴다.
 * 호스트가 그 id 를 행에 남기므로(T9) 응답이 없을 때 그 id 로 다시 찾을 수 있다.
 *
 * - 2xx 이고 [accepted] 가 본문을 읽으면 그 결과를 남긴다.
 * - 4xx 는 거부로 남긴다. 호스트가 요청을 받고 아무것도 남기지 않았다는 응답이다.
 * - 응답이 오지 않거나 5xx 거나 2xx 인데 본문을 못 읽으면 «응답 없음» 을 남기고 [requeryDelay] 뒤 [recheck] 를 한 번 불러
 *   반영 여부를 같은 요청 id 의 새 행으로 붙인다. 재조회도 못 읽으면(널) 행을 붙이지 않는다.
 *
 * 같은 요청 id 로 다시 보내지 않는다. 호스트는 이미 쓰인 요청 id 를 409 로 막고(S3b JSON 계약 §3), 다시 보내기는 새 요청
 * id 를 받는 새 조작이며 사람이 정한다.
 */
class HostOperationRunner(
    private val log: OperationLog,
    private val requeryDelay: Duration,
    private val json: ObjectMapper,
) {

    /**
     * @param target 조작 기록의 대상 칸. WorkMaster id 또는 신호 이름이다
     * @param request 조작 기록의 요청 칸. `op` 칸으로 조작을 가른다
     * @param accepted 2xx 본문을 조작 결과로 옮긴다. 본문 모양이 다르면 널을 낸다
     * @param recheck 응답 없음 뒤 반영 여부를 가른다. 읽지 못하면 널을 낸다
     * @param call 요청 id 를 받아 호스트를 한 번 부른다
     */
    fun run(
        actor: Actor,
        target: String,
        request: ObjectNode,
        reason: String?,
        accepted: (JsonNode) -> OperationResult?,
        recheck: (UUID) -> HostRecheck?,
        call: (UUID) -> HostWrite,
    ): HostOperationResult {
        val requestId = UUID.randomUUID()
        val requestJson = json.writeValueAsString(request)
        fun record(result: OperationResult, response: String) =
            log.append(requestId, actor, target, requestJson, reason, result, response)

        fun unanswered(response: String): HostOperationResult {
            record(OperationResult.NO_RESPONSE, response)
            if (!requeryDelay.isZero) Thread.sleep(requeryDelay)
            val check = recheck(requestId)
                ?: return HostOperationResult(requestId, OperationResult.NO_RESPONSE, null, null, null)
            val confirmation = if (check.applied) OperationResult.CONFIRMED_APPLIED else OperationResult.CONFIRMED_NOT_APPLIED
            val observed = json.createObjectNode()
            observed.set<JsonNode>("observed", check.observed ?: json.nullNode())
            record(confirmation, json.writeValueAsString(observed))
            return HostOperationResult(requestId, OperationResult.NO_RESPONSE, confirmation, null, null)
        }

        val write = when (val sent = call(requestId)) {
            is HostWrite.NoResponse -> return unanswered(json.writeValueAsString(json.createObjectNode().put("cause", sent.cause)))
            is HostWrite.Answered -> sent
        }
        val body = objectOrNull(write.body)
        val response = json.createObjectNode().put("status", write.status)
        if (body != null) response.set<JsonNode>("body", body) else response.put("body", write.body)
        val responseJson = json.writeValueAsString(response)
        return when (write.status) {
            in 200..299 -> {
                val result = body?.let(accepted) ?: return unanswered(responseJson)
                record(result, responseJson)
                HostOperationResult(requestId, result, null, body, null)
            }
            in 400..499 -> {
                record(OperationResult.REJECTED, responseJson)
                val rest = (body?.deepCopy<JsonNode>() as? ObjectNode)?.apply { remove(listOf("error", "detail")) }?.takeIf { !it.isEmpty }
                val rejection = HostRejection(
                    write.status,
                    body?.get("error")?.takeIf { it.isTextual }?.asText(),
                    body?.get("detail")?.takeIf { it.isTextual }?.asText(),
                    rest,
                )
                HostOperationResult(requestId, OperationResult.REJECTED, null, null, rejection)
            }
            else -> unanswered(responseJson)
        }
    }

    private fun objectOrNull(body: String): JsonNode? = try {
        json.readTree(body)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
        null
    }
}
