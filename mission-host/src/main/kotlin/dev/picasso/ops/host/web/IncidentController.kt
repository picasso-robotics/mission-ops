package dev.picasso.ops.host.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.cell.CellBandClient
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 인시던트 조회, 운영자 판단, 장애 주입 전달(S3c 스펙 §7.2, S4a 스펙 §6). 루프백이고 인증이 없다. 호출자는 운영 서비스뿐이다. 모드
 * 검사(주입은 엔지니어, 판단은 운영자)와 사유, 조작 기록은 운영 서비스가 한다.
 *
 * 조회와 판단은 호스트 잠금 아래에서 하고, 장애 주입은 신호 조작처럼 잠금 밖에서 현장에 넘긴다. POST 는 `application/json` 만
 * 받는다(S3a 와 같은 까닭). 판단의 결과(Resolved·NotHeld·Refused)는 늘 200 의 본문에 있다.
 */
@RestController
class IncidentController(
    private val host: MissionHost,
    private val cellBand: CellBandClient,
    private val json: ObjectMapper,
) {

    /** 최신부터 많아야 [limit] 개. 정수가 아니거나 1~[MAX_LIMIT] 밖이면 400 `BAD_REQUEST` 다. */
    @GetMapping("/host/incidents")
    fun incidents(@RequestParam(required = false) limit: String?): ResponseEntity<Any> {
        val count = if (limit == null) DEFAULT_LIMIT else limit.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(HostRejection(HostRequests.BAD_REQUEST, "limit 은 1~$MAX_LIMIT 의 정수다: $limit"))
        return ResponseEntity.ok(host.incidents(count))
    }

    /**
     * 인시던트 하나의 상세. 없으면 404 `INCIDENT_NOT_FOUND` 다. 질의 [instanceId] 가 없거나 지금 인스턴스면 지금 인스턴스의 것이고,
     * 이전 인스턴스면 그 인스턴스의 사본이다(S4b 스펙 T7). 빈 문자열이면 400 `BAD_REQUEST` 다.
     */
    @GetMapping("/host/incidents/{incidentId}")
    fun incident(@PathVariable incidentId: String, @RequestParam(required = false) instanceId: String?): ResponseEntity<Any> {
        if (instanceId != null && instanceId.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(HostRequests.BAD_REQUEST, "instanceId 가 비어 있다"))
        }
        val detail = host.incident(incidentId, instanceId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(HostRejection(INCIDENT_NOT_FOUND, "그 인시던트가 없다: $incidentId" + (instanceId?.let { " (인스턴스 $it)" } ?: "")))
        return ResponseEntity.ok(detail)
    }

    /**
     * 운영자 판단. 승인자는 늘 `Approver(approverId, PERSON)` 이다(ADR 43 에 따라 기본값이 없고 approverId 는 필수다). 대상 단위는 보류
     * 단위이거나 보류가 아닌 재작업 묶음의 단위다(picasso ADR 53·§15.323 — 건너뛴 대상, 시도 뒤 실패한 단위와 그 묶음). 실행이 없거나 그 단위가
     * 둘 다 아니면 NotHeld 이며 404 를 내지 않는다. 이 인스턴스가 이빅션한 실행이면 NotHeld 의 `detail` 이 [MissionHost.EVICTED] 이다.
     *
     * 묶음은 `REWORK` 만 받고 확인은 Refused 이며, 취소한 실행·종결 전 실행·같은 기체의 다른 활성 실행·발행 관문·물리 관문·묶음 규칙도 Refused 다.
     * 승인자가 늘 사람이어도 Refused 가 REST 로 나며 `detail` 은 picasso 사유 문장 그대로다(읽는 쪽은 뜯어 나누지 않는다). Resolved 의
     * `incidentId` 는 판단이 붙은 인시던트(묶음이면 붙은 것 가운데 마지막)이고 건너뛴 대상이면 요청한 단위가 아니라 선행 이동의 것이다.
     *
     * 본문의 `instanceId` 가 지금 인스턴스가 아니면 409 `INSTANCE_MISMATCH` 이고 판단하지 않는다(S4b 스펙 T8). `exec-N` 은 인스턴스마다
     * 다시 세므로 이전 인스턴스의 실행 id 가 지금 인스턴스의 다른 실행을 가리킬 수 있다.
     */
    @PostMapping("/host/executions/{executionId}/units/{unitId}/resolve", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun resolve(
        @PathVariable executionId: String,
        @PathVariable unitId: String,
        @RequestBody(required = false) body: ByteArray?,
    ): ResponseEntity<Any> {
        val request = try {
            HostRequests.resolution(body?.let { runCatching { json.readTree(it) }.getOrNull() })
        } catch (e: BadRequest) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(HostRejection(e.error, e.message ?: ""))
        }
        if (request.instanceId != host.instanceId) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                HostRejection(INSTANCE_MISMATCH, "판단 요청의 인스턴스(${request.instanceId})가 지금 인스턴스(${host.instanceId})가 아니다"),
            )
        }
        return ResponseEntity.ok(
            host.resolve(executionId, unitId, request.decision, Approver(request.approverId, ApproverKind.PERSON), request.requestId),
        )
    }

    /**
     * 장애 주입을 현장 `POST /faults` 에 그대로 넘긴다(T1). 현장의 응답(200·400·404·409·415)은 상태 코드와 본문 그대로 돌려준다.
     * 호스트는 종류를 해석하지 않고 잠금을 잡지 않는다. 현장이 안 닿으면 신호 조작과 같은 503 `CELL_SILENT` 다.
     */
    @PostMapping("/host/faults", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun injectFault(@RequestBody(required = false) body: ByteArray?): ResponseEntity<Any> {
        val relay = cellBand.injectFault(body ?: ByteArray(0))
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(HostRejection(MissionController.CELL_SILENT, "현장 셀 대역이 답하지 않는다"))
        val builder = ResponseEntity.status(relay.status)
        relay.contentType?.let { builder.contentType(MediaType.parseMediaType(it)) }
        return builder.body(relay.body)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500

        /** 오류 이름(S4a JSON 계약 §3). */
        const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"

        /** 판단 요청의 인스턴스가 지금 인스턴스가 아니다(S4b 스펙 T8). */
        const val INSTANCE_MISMATCH = "INSTANCE_MISMATCH"
    }
}
