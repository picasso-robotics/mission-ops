package dev.picasso.ops.service.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.joborders.FormRejection
import dev.picasso.ops.service.joborders.JobOrderDraft
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderSubmission
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 작업 지시와 실행 API(S3a 스펙 §8). 판정과 두 조회는 모드와 관계없고, 제출은 운영자 모드만 한다.
 *
 * 폼 본문은 바이트로 받아 직접 읽는다([SiteSettingsController] 와 같은 이유). 제출은 게이트를 먼저 지나므로 엔지니어 모드의
 * 깨진 본문은 400 이 아니라 403 이다. 폼 오류(`JOB_ORDER_BAD_REQUEST`·`UNKNOWN_WORK_MASTER`·`UNIT_ID_CONFLICT`)와 후보 없음
 * (`NO_ELIGIBLE_ROBOT`)은 호스트에 닿지 않은 사전 거부라 조작 기록에 남지 않는다. 쓰기 본문은 `application/json` 만 받는다
 * (다른 출처 방어는 [RobotOperationsController]). 판정은 POST 지만 부작용이 없다. 폼 초안을 본문으로 실어야 해서 POST 다.
 *
 * 직접 제출도 영속 접수를 거친다. 자원 대기는 202와 접수 ID, 즉시 제출은 기존 200 결과를 반환한다.
 * 재접수 멱등성이 필요한 호출자는 requestId를 받는 /api/work-requests/manual을 사용한다.
 * 실행 목록과 셀은 호스트 본문을 그대로 넘기고, 호스트가 안 닿으면 503 과 이유다.
 */
@RestController
class JobOrderController(
    private val eligibility: JobOrderEligibility,
    private val operations: JobOrderOperations,
    private val host: HostReads,
    private val requests: dev.picasso.ops.service.requests.WorkRequests,
) {
    private val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    @PostMapping("/api/job-orders/eligibility", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun eligibility(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> = drafted(body) { draft ->
        ResponseEntity.ok(eligibility.judge(JobOrderForm.jobOrder(draft, DRAFT_ID, json)))
    }

    @PostMapping("/api/job-orders", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @RequestHeader(Actor.MODE_HEADER, required = false) mode: String?,
        @RequestHeader(Actor.USER_HEADER, required = false) user: String?,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> = guarded(mode, user, Mode.OPERATOR) { actor ->
        drafted(body) { _ ->
            val value=json.createObjectNode().put("requestId",java.util.UUID.randomUUID().toString())
            value.set<JsonNode>("form",json.readTree(body))
            val accepted=requests.receiveManual(actor,value)
            when(accepted.receipt.state) {
                "WAITING_RESOURCE" -> ResponseEntity.status(202).body(accepted)
                // 호스트가 판정을 거부해 제출하지 않았다(FLEET_PORT_ABSENT). 접수 상세가 «코드: 문구» 이고 그 코드를 그대로 사전 거부로 낸다.
                "REJECTED" -> hostRefusalOf(accepted.receipt)?.let { (code, detail) -> reject(HttpStatus.BAD_REQUEST, code, detail) }
                    ?: ResponseEntity.ok(accepted.receipt.submission?.let{json.treeToValue(it,dev.picasso.ops.service.joborders.JobOrderOutcome::class.java)}?:accepted)
                "UNASSIGNED" -> if(accepted.receipt.submission==null) reject(HttpStatus.BAD_REQUEST, JobOrderOperations.NO_ELIGIBLE_ROBOT,accepted.receipt.detail?:"미배정") else ResponseEntity.ok(json.treeToValue(accepted.receipt.submission,dev.picasso.ops.service.joborders.JobOrderOutcome::class.java))
                else -> ResponseEntity.ok(accepted.receipt.submission?.let{json.treeToValue(it,dev.picasso.ops.service.joborders.JobOrderOutcome::class.java)}?:accepted)
            }
        }
    }

    /**
     * 실행 목록. 인자 없이 부르면 호스트 전체 목록 그대로다. 화면 페이지(HA 설계 기록 §10): `recent=N` 은 지금 인스턴스의 남은 실행 모두와
     * 최근 정착 이력 N 개, `before=<settleSeq>[&limit=M]` 은 그 정착 차례보다 앞의 정착 이력 M 개다. 인자는 운영 서비스가 먼저 검사하고
     * (`recent`·`limit` 은 정수 1~[MAX_PAGE], `before` 는 양의 정수, 빈 값·되풀이 없음, `recent` 와 `before`·`limit` 을 섞지 않음, `limit` 은
     * `before` 와 함께) 틀리면 호스트에 닿지 않은 400 `EXECUTIONS_BAD_REQUEST` 다. 그 밖의 인자(`jobOrderId` 등)는 전처럼 버린다.
     */
    @GetMapping("/api/executions")
    fun executions(@RequestParam params: MultiValueMap<String, String>): ResponseEntity<Any> {
        if (PAGE_PARAMS.none { params.containsKey(it) }) return forwarded(host.executions())
        val single = PAGE_PARAMS.associateWith { name ->
            val values = params[name] ?: return@associateWith null
            if (values.size != 1 || values[0].isBlank()) return badPage("$name 은 한 번만, 비어 있지 않은 값으로 준다")
            values[0]
        }
        val recent = single[RECENT]
        val before = single[BEFORE]
        if (recent != null) {
            if (before != null || single[LIMIT] != null) return badPage("recent 는 before·limit 과 함께 쓸 수 없다")
            return forwarded(host.executionsRecent(count(recent) ?: return badPage("recent 는 1~$MAX_PAGE 의 정수다: $recent")))
        }
        if (before == null) return badPage("limit 은 before 와 함께 준다")
        val cursor = before.toLongOrNull()?.takeIf { it > 0 } ?: return badPage("before 는 양의 정수다(종결 이력의 settleSeq·nextBefore): $before")
        val limit = single[LIMIT]?.let { count(it) ?: return badPage("limit 은 1~$MAX_PAGE 의 정수다: $it") }
        return forwarded(host.executionsBefore(cursor, limit))
    }

    /** 실행 수만(HA 설계 기록 §10). 운영 개요가 읽는다. 호스트 본문 그대로이고 호스트가 안 닿으면 503 이다. */
    @GetMapping("/api/executions/summary")
    fun executionSummary(): ResponseEntity<Any> = forwarded(host.executionSummary())

    private fun count(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 1..MAX_PAGE }

    private fun badPage(detail: String): ResponseEntity<Any> = reject(HttpStatus.BAD_REQUEST, EXECUTIONS_BAD_REQUEST, detail)

    @GetMapping("/api/cell")
    fun cell(): ResponseEntity<Any> = forwarded(host.cell())

    private inline fun drafted(body: ByteArray?, action: (JobOrderDraft) -> ResponseEntity<Any>): ResponseEntity<Any> {
        val node = body?.let { runCatching { json.readTree(it) }.getOrNull() }
        val draft = try {
            JobOrderForm.read(node)
        } catch (e: FormRejection) {
            return reject(HttpStatus.BAD_REQUEST, e.error, e.message ?: "")
        }
        return action(draft)
    }

    private fun forwarded(call: HostCall<JsonNode>): ResponseEntity<Any> = when (call) {
        is HostCall.Ok -> ResponseEntity.ok(call.value)
        is HostCall.Silent -> reject(HttpStatus.SERVICE_UNAVAILABLE, HOST_SILENT, "실행 호스트가 답하지 않는다: ${call.cause}")
    }

    companion object {
        const val HOST_SILENT = "HOST_SILENT"

        /** 판정을 거부하는 호스트 사유 가운데 제출 응답에 그대로 내는 것. 접수 상세가 «코드: 문구» 로 시작한다. */
        val HOST_REFUSAL_CODES: Set<String> = setOf(JobOrderOperations.FLEET_PORT_ABSENT, JobOrderOperations.FLEET_ONLY_CANDIDATES)

        /** 제출하지 않은 거부 접수(`submission` 없음)의 상세가 [HOST_REFUSAL_CODES] 의 코드로 시작하면 (코드, 문구). 아니면 널. */
        fun hostRefusalOf(receipt: dev.picasso.ops.service.requests.Receipt): Pair<String, String>? {
            if (receipt.submission != null) return null
            val detail = receipt.detail ?: return null
            val code = HOST_REFUSAL_CODES.firstOrNull { detail.startsWith("$it: ") } ?: return null
            return code to detail.removePrefix("$code: ")
        }

        /** 실행 목록 페이지 인자가 틀렸다. 호스트에 닿지 않은 사전 거부다. */
        const val EXECUTIONS_BAD_REQUEST = "EXECUTIONS_BAD_REQUEST"

        /** 화면 페이지 인자 이름과 상한(HA 설계 기록 §10). 실행 호스트와 같다. */
        const val RECENT = "recent"
        const val BEFORE = "before"
        const val LIMIT = "limit"
        val PAGE_PARAMS: List<String> = listOf(RECENT, BEFORE, LIMIT)
        const val MAX_PAGE = 200

        /** 판정에 싣는 작업 지시 id. 호스트는 판정에서 id 를 쓰지 않으며, 제출은 새 id 로 다시 판정한다. */
        const val DRAFT_ID = "JO-DRAFT"
    }
}
