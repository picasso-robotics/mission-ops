package dev.picasso.ops.service.host

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 실행 호스트 읽기 한 번의 결과(S3a 스펙 §8). «없음» 과 «모름» 을 접지 않는다.
 *
 * 연결 실패·시간 초과·200 아님·해석 불가 어느 것이든 값을 모른다는 점이 같아 [Silent] 하나로 둔다. 호스트에는 토큰이 없어
 * registry 의 `Unauthorized` 같은 하위 범주가 없다. 제출은 [HostWrite] 로 따로 돌려준다.
 */
sealed interface HostCall<out T> {
    data class Ok<T>(val value: T) : HostCall<T>

    /**
     * [unreached] 는 호스트가 아무 답도 하지 않은 경우(연결 실패·시간 초과)만 참이다. 200 아닌 응답과 해석 못 한 본문은 호스트가
     * 답한 것이라 거짓이다. 읽는 쪽이 «닿지 못함» 과 «읽지 못함» 을 가를 때만 쓰고, 같음 비교는 그대로 [cause] 와 함께 한다.
     */
    data class Silent(val cause: String, val unreached: Boolean = false) : HostCall<Nothing>
}

/**
 * 제출 한 번의 결과. 응답이 오면 코드와 본문을 그대로 넘기고, 분류는 부르는 쪽이 한다(스펙 §8).
 * 응답이 오지 않으면 호스트가 받았는지 모르는 것이며, 거부와 섞지 않는다.
 */
sealed interface HostWrite {
    data class Answered(val status: Int, val body: String) : HostWrite

    data class NoResponse(val cause: String) : HostWrite
}

/** 호스트의 스킬 적합 판정. [UNKNOWN] 은 호스트가 기체 케이퍼빌리티를 못 물어봤다는 뜻이고 적합이 아니다. */
enum class HostSkillFit { FIT, MISSING, UNKNOWN }

/**
 * 호스트 판정 한 행(S3a JSON 계약 §2.2). 호스트는 자기 쪽 이유만 보고, 시운전·연결은 운영 서비스가 합친다(T3).
 *
 * @param runningExecutionId 이 기체를 쥔 실행. 정착하지 않은 실행과 부분 완료(`PARTIAL`) 실행이다. 없으면 널. 운영자 보류도 도는 실행이다
 * @param passed 호스트가 막을 이유가 하나도 없다([reasons] 가 비었다). 스킬 부적합, 도는 실행, 복원 못 한 실행, 잔여 파지 확인 필요, 시간값 미적용, 일지 차단이 그 이유다
 * @param reasons 통과하지 못한 이유(화면 표시용). 통과면 비어 있다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibility(
    val robotId: String,
    val skillFit: HostSkillFit,
    val missingSkills: List<String> = emptyList(),
    val runningExecutionId: String? = null,
    val passed: Boolean,
    val reasons: List<String> = emptyList(),
    val requiredSkills: List<String>? = null,
    val availableSkills: List<String>? = null,
    /** 관문 확인용으로만 넘기는 기체(남은 이유가 잔여 파지 표시뿐). [passed] 가 참이어도 일반 배정은 서지 않고 조치 제안만 얻는다. 칸이 없던 호스트 본문은 거짓이다. */
    val gateTrial: Boolean = false,
)

/** `POST /host/eligibility` 의 답. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostEligibilityList(val robots: List<HostEligibility>)

/** 미들웨어 제출 결과 넷의 이름(S3a JSON 계약 §4). */
enum class HostSubmitResult { ACCEPTED, IDEMPOTENT, REJECTED, UNASSIGNED }

/**
 * `assign` 이 기체마다 낸 미배정 사유(S3a JSON 계약 §4).
 *
 * 가운데 두 칸은 그 기체가 출발 결품으로 떨어졌을 때만 값이 있고 모양은 [HostSubmitOutcome] 의 같은 이름 칸과 같다. 다른 사유로 떨어진
 * 기체와 이 칸이 없던 호스트의 본문은 둘 다 `null` 이다. 마지막 칸은 출발 미관측으로 떨어진 기체에만 있고 출발 결품과 접지 않는다.
 *
 * @param alternativeLocations 그 자재를 든 다른 자리. 빈 목록(든 자리 없음)과 `null`(답 없음 또는 출발 결품 아님)을 접지 않는다
 * @param sourceMissing 이 기체가 출발 결품으로 떨어졌으면 그 자리와 관측. 아니면 `null`
 * @param sourceUnobserved 이 기체가 출발 미관측으로 떨어졌으면 그 자리와 설비가 말한 것. 아니면 `null`(이 칸이 없던 호스트 본문 포함)
 * @param remedy 이 기체에서 이번 제출이 얻은 조치 탐색 응답(`kind` 가 `FOUND`·`NONE`). 해석하지 않고 그대로 옮긴다. 없으면 `null`
 * @param remedyWithheld 이 기체의 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류했다
 * @param residualHoldMarkers 관문 확인용으로 넘긴 표시 기체면 그때 열려 있던 잔여 파지 표시 id. 표시는 이 거절로 풀리지 않는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostRefusal(
    val robotId: String,
    val reason: String,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: HostSourceMissing? = null,
    val sourceUnobserved: HostSourceUnobserved? = null,
    val remedy: JsonNode? = null,
    val remedyWithheld: Boolean = false,
    val residualHoldMarkers: List<Long> = emptyList(),
)

/**
 * `POST /host/job-orders` 의 200 본문(S3a JSON 계약 §4). 결과가 무엇이든 200 이며 결과는 [result] 에 있다.
 *
 * @param executionId·robotId ACCEPTED·IDEMPOTENT 일 때만 있다
 * @param rejectionReason REJECTED 일 때만 있다
 * @param refusals UNASSIGNED 일 때 `assign` 이 기체마다 낸 사유. 출발 결품의 대체 자리와 출발 자리는 기체마다 [HostRefusal] 에 있다
 * @param excluded 호스트가 판정에서 빼 `assign` 에 넘기지 않은 기체의 판정 행
 * @param remedy 미들웨어 거부의 조치 탐색 응답(`kind` 가 `FOUND`·`NONE`). 운영 서비스는 해석하지 않고 그대로 옮긴다. 없으면 `null`
 * @param remedyWithheld 조치 시퀀스를 계산했으나 사람이 먼저 진단하도록 보류했다
 * @param alternativeLocations 출발 결품 REJECTED 일 때 그 자재를 든 다른 자리. 빈 목록(든 자리 없음)과 `null`(답 없음 또는 출발 결품
 *   아님)을 접지 않는다. 이 칸이 없던 호스트의 본문과 UNASSIGNED 는 `null` 이다
 * @param sourceMissing 출발 결품 REJECTED 이면 그 자리와 관측. 아니면 `null`(UNASSIGNED 포함)
 * @param sourceUnobserved 출발 미관측 REJECTED 이면 그 자리와 설비가 말한 것. 아니면 `null`(UNASSIGNED 포함, 기체별 칸은 [HostRefusal])
 * @param forgottenJobOrder 이미 끝나 이빅션한 작업 지시라 받지 않은 REJECTED(picasso `FORGOTTEN_JOB_ORDER`). 다시 내도 같고 다시 할 일은 새 작업
 *   지시로 낸다. 이 칸이 없던 호스트의 본문은 거짓이다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostSubmitOutcome(
    val result: HostSubmitResult,
    val executionId: String? = null,
    val robotId: String? = null,
    val rejectionReason: String? = null,
    val refusals: List<HostRefusal> = emptyList(),
    val excluded: List<HostEligibility> = emptyList(),
    val remedy: JsonNode? = null,
    val remedyWithheld: Boolean = false,
    val alternativeLocations: List<String>? = null,
    val sourceMissing: HostSourceMissing? = null,
    val sourceUnobserved: HostSourceUnobserved? = null,
    val forgottenJobOrder: Boolean = false,
)

/** 출발 결품 거부의 자리. [observed] 가 `null` 이면 빈 자리이고 아니면 거기 있던 다른 식별 정보다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostSourceMissing(val material: String? = null, val source: String, val observed: String? = null)

/**
 * 출발 미관측 거부의 자리(S3a JSON 계약 §4). 관측을 요구한 단위의 출발 자리를 설비가 확인해 주지 않았다. 결품이 아니다 — 다음 행동은
 * 사람의 출발 자리 확인이다. [occupied] 가 `null` 이면 설비가 말이 없었고 참이면 «있다» 고만 했다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostSourceUnobserved(val material: String? = null, val source: String, val occupied: Boolean? = null)

/**
 * 제출 본문의 `sourceConfirmations` 원소(S3a JSON 계약 §4). 사람이 기록한 출발 자리 확인이고 호스트가 그 제출 한 번 동안 셀 관측으로 쓴다.
 *
 * @param confirmedAt ISO-8601 시각
 */
data class HostSourceConfirmation(val source: String, val identity: String, val confirmedBy: String, val confirmedAt: String)

/** `GET /host/executions` 의 실행 한 줄 중 재조회가 쓰는 칸. 나머지는 버린다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutionRef(
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val physicalState: String,
)

/** `GET /host/executions` 를 재조회로 읽은 모양. 화면에 넘길 때는 해석하지 않고 그대로 넘긴다([HostReads.executions]). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HostExecutions(val instanceId: String, val executions: List<HostExecutionRef>, val journalBlockedExecutionIds: List<String> = emptyList())

/** 호스트 읽기 셋. 시험이 호스트 없이 대신 끼운다. */
interface HostReads {
    /** 작업 지시 본문 [jobOrder] 를 [robotIds] 의 기체마다 판정한다. */
    fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>>

    /** `GET /host/executions` 본문 그대로. 객체가 아니면 모름이다. */
    fun resultEvents(after:Long,limit:Int=100): HostCall<JsonNode> = HostCall.Silent("result outbox unsupported")
    fun executions(): HostCall<JsonNode>

    /**
     * `GET /host/executions` 를 작업 지시 id [jobOrderIds] 나 실행 id [executionIds] 의 실행으로 좁힌 본문 그대로(HA 설계 기록 §10).
     * 모양은 [executions] 와 같고 실행과 일지 막힘 목록만 그 실행으로 줄어든다. id 가 하나는 있어야 한다. 좁혀 읽을 줄 모르는
     * 구현은 전체 목록을 준다. 고른 실행이 다 들어 있으니 id 로 찾는 쪽에는 같다.
     */
    fun executionsOf(jobOrderIds: Collection<String> = emptyList(), executionIds: Collection<String> = emptyList()): HostCall<JsonNode> = executions()

    /**
     * `GET /host/executions?recent=` 본문 그대로(HA 설계 기록 §10). 지금 인스턴스의 남은 실행 모두와 최근 정착 이력 [recent] 개, 다음 페이지
     * 커서 `nextBefore`. 읽을 줄 모르는 구현은 모름이다.
     */
    fun executionsRecent(recent: Int): HostCall<JsonNode> = HostCall.Silent("실행 목록 페이지를 지원하지 않는 호스트 읽기다")

    /**
     * `GET /host/executions?before=[&limit=]` 본문 그대로(HA 설계 기록 §10). 정착 차례가 [before] 보다 앞인 정착 이력. [limit] 이 널이면 싣지
     * 않는다(호스트 기본 20). 읽을 줄 모르는 구현은 모름이다.
     */
    fun executionsBefore(before: Long, limit: Int?): HostCall<JsonNode> = HostCall.Silent("실행 목록 페이지를 지원하지 않는 호스트 읽기다")

    /** `GET /host/executions/summary` 본문 그대로(HA 설계 기록 §10). 읽을 줄 모르는 구현은 모름이다. */
    fun executionSummary(): HostCall<JsonNode> = HostCall.Silent("실행 수 조회를 지원하지 않는 호스트 읽기다")

    /** `GET /host/cell` 본문 그대로. 셀 대역을 못 읽은 호스트는 `{"cell": null}` 을 200 으로 준다. */
    fun cell(): HostCall<JsonNode>

    /**
     * `GET /host/work-permits` 본문 그대로(ADR 52). 현장이 작업 허가 대응을 선언하지 않았으면 `declared` 가 거짓이다. 읽을 줄 모르는
     * 구현은 모름이고, 모름을 받은 스테이션 조정은 닫힌 쪽(보류)으로 읽는다.
     */
    fun workPermits(): HostCall<JsonNode> = HostCall.Silent("작업 허가 조회를 지원하지 않는 호스트 읽기다")

    /**
     * `POST /host/job-orders/{jobOrderId}/withdraw`(B3). 그 작업 지시를 영구히 철회한다 — 그 뒤로 실행 호스트는 그 작업 지시를 값 없는 승인으로도
     * 새 접수로도 실행하지 않는다. 결과(WITHDRAWN·EXECUTION_EXISTS)는 200 본문의 `result` 에 있고, 실행 일지가 막혀 있으면 500 `JOURNAL_WRITE_FAILED`
     * 다. 응답은 코드와 본문 그대로이고 분류는 부르는 쪽이 한다. 대체 접수가 원 요청 행 잠금을 쥔 채 부르므로 요청 제한은 이 호출 전용의 짧은 값이다
     * ([HostClient.WITHDRAW_TIMEOUT]). 철회할 줄 모르는 구현은 응답 없음이다 — 응답 없음은 미전송의 증거가 아니다.
     */
    fun withdrawJobOrder(jobOrderId: String, requestId: UUID, reason: String, requestedBy: String): HostWrite =
        HostWrite.NoResponse("작업 지시 철회를 지원하지 않는 호스트다")
}

/** 호스트 제출. 시험이 호스트 없이 대신 끼운다. */
fun interface HostWrites {
    fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite

    /**
     * 사람이 기록한 출발 자리 확인을 함께 싣는 제출. 확인이 없으면 [submit] 과 같다. 확인을 실을 줄 모르는 구현은 확인을 버리고 보내지
     * 않고 예외를 던진다 — 버리면 사람이 확인한 자리가 다시 출발 미관측으로 돌아온다.
     */
    fun submit(jobOrder: ObjectNode, candidates: List<String>, sourceConfirmations: List<HostSourceConfirmation>): HostWrite =
        if (sourceConfirmations.isEmpty()) submit(jobOrder, candidates)
        else throw UnsupportedOperationException("출발 자리 확인을 실을 수 없는 제출 구현이다")
}

/**
 * 요청 id 재조회 한 번의 결과(S3b 스펙 T9). «남은 행이 없다» 는 호스트의 응답(404 `REQUEST_NOT_FOUND`)이고 «못 읽음» 과
 * 다르다. 앞의 것은 반영 안 됨으로 확인되고, 뒤의 것은 확인하지 못한 것이다. 호스트가 그 조작을 아직 처리 중이라는 응답
 * (409 `REQUEST_IN_PROGRESS`)도 확인하지 못한 것이라 [Silent] 다.
 */
sealed interface HostRequery {
    /** 그 요청 id 로 남은 행. 본문은 S3b JSON 계약 §4.7 의 모양 그대로다. */
    data class Found(val body: JsonNode) : HostRequery

    data object NotFound : HostRequery

    data class Silent(val cause: String) : HostRequery
}

/**
 * 호스트의 임무 버전 REST(S3b 스펙 §6.6). 시험이 호스트 없이 대신 끼운다.
 *
 * 두 읽기는 본문을 해석하지 않고 넘긴다. 쓰기 넷은 응답이 오면 코드와 본문을 그대로 넘기고 분류는 부르는 쪽이 한다. 검증은
 * 아무것도 남기지 않지만 판정 결과가 200 본문에 있어 같은 모양으로 받는다. `requestId` 는 운영 서비스가 조작마다 만든
 * 요청 id 이며 호스트가 행에 남긴다(T9).
 */
interface HostMissions {
    /** `GET /host/missions/{workMasterId}` 본문 그대로. 200 아님은 모름이다. */
    fun draft(draftId: Long): HostCall<JsonNode> = HostCall.Silent("초안 조회 미지원")
    fun overview(workMasterId: String): HostCall<JsonNode>

    /** `GET /host/missions/templates/{workMasterId}` 본문 그대로. */
    fun templates(workMasterId: String): HostCall<JsonNode>

    fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite

    fun saveDraftWithSample(workMasterId:String,definition:String,sample:JsonNode,actor:String,requestId:UUID):HostWrite = HostWrite.NoResponse("표본 작업 저장을 지원하지 않는 호스트입니다")

    fun validate(draftId: Long, robotIds: List<String>): HostWrite

    /** 호스트가 요청 안에서 동기로 돌리므로 요청 제한이 따로 길다(T10). */
    fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite

    fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite

    /** `GET /host/missions/requests/{requestId}`. 응답 없음 뒤 재조회에만 쓴다. */
    fun missionRequest(requestId: UUID): HostRequery
}

/** 셀 대역 신호 조작 전달(S3b 스펙 §7, 결정 3). 시험이 호스트 없이 대신 끼운다. */
fun interface HostSignals {
    /** `POST /host/cell/signals/{name}`. 호스트는 현장의 상태 코드와 본문을 그대로 돌려준다. */
    fun writeSignal(name: String, value: String): HostWrite
}

/** 호스트의 현장 시간값 적용 상태(S3c 스펙 §8). 시험이 호스트 없이 대신 끼운다. */
fun interface HostSiteTimings {
    /** `GET /host/site-timings` 본문 그대로. 200 아님과 닿지 않음은 모름이다. */
    fun siteTimings(): HostCall<JsonNode>
}

/**
 * 인시던트 단건 읽기 한 번의 결과(S4a JSON 계약 §4). «그런 인시던트가 없다» 는 호스트의 응답(404 `INCIDENT_NOT_FOUND`)이고
 * «못 읽음» 과 다르다. 앞의 것은 화면에 404 로 그대로 넘기고, 뒤의 것은 503 이다.
 */
sealed interface HostIncident {
    /** 상세 본문(S4a JSON 계약 §4) 그대로. */
    data class Found(val body: JsonNode) : HostIncident

    /** 호스트의 404 본문 `{error, detail}` 그대로. */
    data class NotFound(val body: JsonNode) : HostIncident

    data class Silent(val cause: String) : HostIncident
}

/**
 * 호스트의 인시던트 REST(S4a 스펙 §6·§7). 시험이 호스트 없이 대신 끼운다.
 *
 * 두 읽기는 본문을 해석하지 않고 넘긴다. 판단은 응답이 오면 코드와 본문을 그대로 넘기고 분류는 부르는 쪽이 한다.
 */
interface HostIncidents {
    /** `GET /host/incidents[?limit=]` 본문 그대로. [limit] 이 널이면 쿼리를 싣지 않는다(호스트 기본 50). */
    fun incidents(limit: Int? = null): HostCall<JsonNode>

    /**
     * `GET /host/incidents/{incidentId}[?instanceId=]`. [instanceId] 가 널이면 쿼리를 싣지 않는다(지금 인스턴스). 이전 인스턴스면
     * 호스트가 그 인스턴스의 사본을 준다(S4b 계약 H4).
     */
    fun incident(incidentId: String, instanceId: String? = null): HostIncident

    /**
     * `POST /host/executions/{executionId}/units/{unitId}/resolve`(S4a JSON 계약 §5.1). [instanceId] 는 상세에서 받은 인스턴스이고
     * 지금 인스턴스가 아니면 호스트가 409 `INSTANCE_MISMATCH` 로 막는다(S4b 계약 H5).
     */
    fun resolve(executionId: String, unitId: String, decision: String, approverId: String, instanceId: String, requestId: UUID): HostWrite

    /**
     * 판단 재조회가 읽는 그 실행의 본문(`GET /host/executions?executionId=`, HA 설계 기록 §10) 그대로. 단위마다 지금 시도(`attempt`)와 판단 기록의
     * 판단(`decision`)을 든다(picasso §15.323). 읽을 줄 모르는 구현은 모름이다 — 그러면 판단 재조회는 단위의 인시던트로만 대조한다.
     */
    fun executionUnits(executionId: String): HostCall<JsonNode> = HostCall.Silent("판단 재조회용 실행 조회를 지원하지 않는 호스트 읽기다")
}

/** 작업 응답 송신 기록 읽기(S4b 스펙 T6·T9). 시험이 호스트 없이 대신 끼운다. */
fun interface HostJobResponses {
    /**
     * `GET /host/job-responses[?jobOrderId=&limit=]` 본문 그대로. 널인 인자는 쿼리에 싣지 않는다(호스트 기본은 전체, 50건).
     * 200 아님과 닿지 않음은 모름이다.
     */
    fun jobResponses(jobOrderId: String?, limit: Int?): HostCall<JsonNode>
}

/**
 * 운영자 실행 취소와 잔여 파지 표시(P0-b2), 운영자 결함 감수(S3a JSON 계약 §5.10). 시험이 호스트 없이 대신 끼운다.
 *
 * 읽기 둘은 본문을 해석하지 않고 넘긴다. 쓰기 셋은 응답이 오면 코드와 본문을 그대로 넘기고 분류는 부르는 쪽이 한다.
 */
interface HostExecutionControls {
    /** `GET /host/executions` 본문 그대로. 좁혀 읽을 줄 모르는 구현의 [executionsOf] 가 쓴다. */
    fun executions(): HostCall<JsonNode>

    /**
     * 취소의 응답 없음 뒤 재조회가 쓴다. `GET /host/executions?executionId=` 로 그 실행 하나만 좁힌 본문 그대로(HA 설계 기록 §10). 모양은 [executions] 와 같다. 좁혀 읽을 줄
     * 모르는 구현은 전체 목록을 준다. 그 실행이 다 들어 있으니 id 로 찾는 재조회에는 같다.
     */
    fun executionsOf(executionId: String): HostCall<JsonNode> = executions()

    /**
     * `POST /host/executions/{executionId}/cancel`. [instanceId] 는 실행 목록에서 받은 인스턴스이고 지금 인스턴스가 아니면 호스트가
     * 409 `INSTANCE_MISMATCH` 로 막는다. 결과(CANCELED·NOT_CANCELABLE·NOT_FOUND·REFUSED)는 200 본문의 `result` 에 있다.
     */
    fun cancelExecution(executionId: String, approverId: String, reason: String, instanceId: String, requestId: UUID): HostWrite

    /**
     * `POST /host/executions/{executionId}/release`(S3a JSON 계약 §5.10). [sawFaults] 는 운영자가 본 막는 결함
     * `[{errorType, references:[{key, value}]}]` 이고 호스트가 지금 막는 결함과 대조한다. [instanceId] 와 요청 id 규칙은
     * [cancelExecution] 과 같고, 호스트는 요청 id 를 감수 행에 적는다. 결과(RELEASED·NOT_BLOCKED·BLOCK_CHANGED·NOT_FOUND·REFUSED)는
     * 200 본문의 `result` 에 있다.
     */
    fun releaseFaults(executionId: String, approverId: String, reason: String, instanceId: String, sawFaults: ArrayNode, requestId: UUID): HostWrite

    /**
     * `POST /host/job-orders/{jobOrderId}/close`(결함 14). 복원 못 한 실행의 운영자 종결이다. [executionInstanceId]·[executionId] 는 운영자가 본 종결할
     * 실행이고 지금 호스트 일지와 다르면 호스트가 `TARGET_CHANGED` 로 답한다. 호스트는 요청 id 를 종결 행에 적는다. 결과(CLOSED·ALREADY_CLOSED·
     * NOT_CLOSABLE·TASK_LIVE·STOP_UNCONFIRMED·TARGET_CHANGED·NOT_FOUND·REFUSED)는 200 본문의 `result` 에 있다.
     */
    fun closeUnrestored(
        jobOrderId: String,
        approverId: String,
        reason: String,
        executionInstanceId: String,
        executionId: String,
        attestedStopped: Boolean,
        requestId: UUID,
    ): HostWrite

    /**
     * 종결의 응답 없음 뒤 재조회가 쓴다. `GET /host/job-orders/{jobOrderId}/closure?requestId=` 본문 그대로이고, 종결하지 않았다는 호스트의 답(404
     * `NOT_CLOSED`)은 `Ok(null)` 이다. 호스트가 그 [requestId] 의 종결을 아직 처리 중이면(409 `REQUEST_IN_PROGRESS`) 모름이다 — 잠금을 기다리는
     * 종결이 곧 커밋될 수 있다. 다른 404·200 아님·닿지 않음도 모름이다.
     */
    fun closure(jobOrderId: String, requestId: UUID): HostCall<JsonNode?>

    /** `GET /host/robots/residual-holds` 본문 그대로. */
    fun residualHolds(): HostCall<JsonNode>

    /**
     * `POST /host/robots/{robotId}/residual-holds/{markerId}/clear`. 없는 표시는 404, 이미 해소한 표시는 409 다. 발신자가 지금 든 채라고 말하면 409
     * `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 이고 호스트가 충돌 행을 적는다(B2). [cited] 는 다시 확인할 때 지목하는 저장된 충돌이며 운영자 요청 본문에서만
     * 온다. 있으면 본문의 `cited` 로 그대로 싣는다. 저장된 충돌과 다르면 400 `RESIDUAL_HOLD_CITATION_INVALID` 다.
     */
    fun clearResidualHold(robotId: String, markerId: Long, approverId: String, reason: String, requestId: UUID, cited: JsonNode? = null): HostWrite

    /**
     * 빈손 확인의 응답 없음 뒤 재조회가 쓴다(B2). `GET /host/robots/{robotId}/residual-holds/{markerId}` 본문(표시와 해소 행) 그대로이고, 그 기체에 그 표시가
     * 없다는 호스트의 답(404 `RESIDUAL_HOLD_NOT_FOUND`)은 `Ok(null)` 이다. 다른 404·200 아님·닿지 않음은 모름이다.
     */
    fun residualHold(robotId: String, markerId: Long): HostCall<JsonNode?>
}

/** 장애 주입 전달(S4a 스펙 §7, T1). 시험이 호스트 없이 대신 끼운다. */
fun interface HostFaults {
    /** `POST /host/faults`. 호스트는 [body] 를 해석하지 않고 현장에 넘기며 현장의 상태 코드와 본문을 그대로 돌려준다. */
    fun injectFault(body: ObjectNode): HostWrite
}

/**
 * 실행 호스트 REST 클라이언트(S3a 스펙 §8, S3b 스펙 §7, S3c 스펙 §8, S4a 스펙 §7, S4b 스펙 §7, P0-b2). 호스트는 루프백·무인증이라 토큰을 싣지
 * 않는다.
 *
 * 연결 제한은 registry 와 같고 요청 제한은 더 길다. 호스트는 판정과 제출을 자기 잠금 아래에서 하며, 그 안에서 mimic 에
 * gRPC 를 부르고, mimic 은 엔진 잠금 아래에서 registry 로 태스크 관측을 동기 HTTP 로 적재한다(요청 제한 3초, 스펙 §5.3).
 * 호스트가 registry 장애 한 번을 기다리는 동안 운영 서비스가 먼저 끊으면, 받은 제출을 응답 없음으로 남기게 된다.
 *
 * 작업 지시 철회만 요청 제한이 [withdrawTimeout](기본 3초, `ops.host.withdraw-timeout`)이다(B3). 대체 접수가 원 요청 행 잠금을 쥔 채 부르므로 그 행을
 * 기다리는 결과 수집이 이만큼(호스트가 연결을 받지 않으면 따로 세는 연결 제한 2초를 더해) 멈춘다. 철회는 호스트 잠금 아래 DB 한두 문장이라 짧게 끝난다.
 *
 * 모의 실행만 요청 제한이 [mockRunTimeout](60초)이다(S3b 스펙 T10). 호스트가 요청 안에서 동기로 돌리고 실제 시간 상한이
 * 30초라, 5초에서 끊으면 돌고 있는 모의 실행을 응답 없음으로 남기게 된다. 60초를 기다리면 호스트는 이미 끝냈으므로 그래도
 * 응답이 없을 때 재조회가 그 결과를 본다.
 */
class HostClient(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
    private val json: ObjectMapper = jacksonObjectMapper(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
    private val mockRunTimeout: Duration = MOCK_RUN_TIMEOUT,
    private val withdrawTimeout: Duration = WITHDRAW_TIMEOUT,
) : HostReads, HostWrites, HostMissions, HostSignals, HostSiteTimings, HostIncidents, HostFaults, HostJobResponses,
    HostExecutionControls, AutoCloseable {

    private val base = checkBaseUrl(baseUrl)

    /** JDK 21 의 HttpClient 는 닫아야 셀렉터 스레드가 끝난다. 스프링이 빈을 내릴 때 부른다. */
    override fun close() = http.close()

    override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("robotIds").apply { robotIds.forEach(::add) }
        val response = when (val write = post("/host/eligibility", body)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        if (response.status != 200) return HostCall.Silent("HTTP ${response.status}")
        return read(response.body) { json.readValue<HostEligibilityList?>(it)?.robots }
    }

    override fun resultEvents(after:Long,limit:Int):HostCall<JsonNode> = get("/host/result-events?after=$after&limit=$limit")

    override fun executions(): HostCall<JsonNode> = get("/host/executions")

    /** 값마다 `jobOrderId=`·`executionId=` 를 되풀이한다. 호스트는 값을 쉼표로 가르지 않는다. */
    override fun executionsOf(jobOrderIds: Collection<String>, executionIds: Collection<String>): HostCall<JsonNode> {
        require(jobOrderIds.isNotEmpty() || executionIds.isNotEmpty()) { "좁힐 실행 id 가 없다" }
        val query = jobOrderIds.map { "jobOrderId=" + URLEncoder.encode(it, Charsets.UTF_8) } +
            executionIds.map { "executionId=" + URLEncoder.encode(it, Charsets.UTF_8) }
        return get("/host/executions?" + query.joinToString("&"))
    }

    override fun executionsRecent(recent: Int): HostCall<JsonNode> = get("/host/executions?recent=$recent")

    override fun executionsBefore(before: Long, limit: Int?): HostCall<JsonNode> =
        get("/host/executions?before=$before" + (limit?.let { "&limit=$it" } ?: ""))

    override fun executionSummary(): HostCall<JsonNode> = get("/host/executions/summary")

    /** 취소 재조회는 그 실행 하나로 좁혀 읽는다(HA 설계 기록 §10). */
    override fun executionsOf(executionId: String): HostCall<JsonNode> = executionsOf(executionIds = listOf(executionId))

    override fun cell(): HostCall<JsonNode> = get("/host/cell")

    override fun workPermits(): HostCall<JsonNode> = get("/host/work-permits")

    override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite = submit(jobOrder, candidates, emptyList())

    /** 확인이 없으면 본문에 `sourceConfirmations` 칸을 두지 않는다(그 칸이 없던 때의 본문 그대로). */
    override fun submit(jobOrder: ObjectNode, candidates: List<String>, sourceConfirmations: List<HostSourceConfirmation>): HostWrite {
        val body = json.createObjectNode()
        body.set<JsonNode>("jobOrder", jobOrder)
        body.putArray("candidates").apply { candidates.forEach(::add) }
        if (sourceConfirmations.isNotEmpty()) body.set<JsonNode>("sourceConfirmations", json.valueToTree(sourceConfirmations))
        return post("/host/job-orders", body)
    }

    override fun withdrawJobOrder(jobOrderId: String, requestId: UUID, reason: String, requestedBy: String): HostWrite {
        val body = json.createObjectNode()
            .put("requestId", requestId.toString())
            .put("reason", reason)
            .put("requestedBy", requestedBy)
        return post("/host/job-orders/${segment(jobOrderId)}/withdraw", body, withdrawTimeout)
    }

    override fun draft(draftId: Long): HostCall<JsonNode> = get("/host/missions/drafts/$draftId")

    override fun overview(workMasterId: String): HostCall<JsonNode> = get("/host/missions/${segment(workMasterId)}")

    override fun templates(workMasterId: String): HostCall<JsonNode> = get("/host/missions/templates/${segment(workMasterId)}")

    override fun saveDraft(workMasterId: String, definition: String, actor: String, requestId: UUID): HostWrite {
        val body = json.createObjectNode()
            .put("definition", definition)
            .put("actor", actor)
            .put("requestId", requestId.toString())
        return post("/host/missions/${segment(workMasterId)}/drafts", body)
    }

    override fun saveDraftWithSample(workMasterId:String,definition:String,sample:JsonNode,actor:String,requestId:UUID):HostWrite {
        val body=json.createObjectNode().put("definition",definition).put("actor",actor).put("requestId",requestId.toString())
        body.set<JsonNode>("sampleOrder",sample)
        return post("/host/missions/${segment(workMasterId)}/drafts",body)
    }

    override fun validate(draftId: Long, robotIds: List<String>): HostWrite =
        post("/host/missions/drafts/$draftId/validate", robots(json.createObjectNode(), robotIds))

    override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID): HostWrite {
        val body = robots(json.createObjectNode(), robotIds).put("requestId", requestId.toString())
        return post("/host/missions/drafts/$draftId/mock-run", body, mockRunTimeout)
    }

    override fun activate(draftId: Long, actor: String, reason: String, robotIds: List<String>, requestId: UUID): HostWrite {
        val body = robots(json.createObjectNode().put("actor", actor).put("reason", reason), robotIds)
            .put("requestId", requestId.toString())
        return post("/host/missions/drafts/$draftId/activate", body)
    }

    /**
     * 남은 행이 없다는 응답은 404 와 `REQUEST_NOT_FOUND` 가 함께일 때만이다. 다른 404(그 경로가 없는 서버 등)는 호스트의
     * 판단이 아니므로 못 읽음이다. 409 `REQUEST_IN_PROGRESS` 는 호스트가 그 조작을 아직 처리 중이라는 응답이다. 행이 없다는
     * 응답으로 접으면, 처리가 끝나 행이 남기 전에 반영 안 됨으로 확인하게 된다.
     */
    override fun missionRequest(requestId: UUID): HostRequery {
        val request = HttpRequest.newBuilder(URI.create("$base/host/missions/requests/$requestId")).GET()
        val response = when (val write = send(request, requestTimeout)) {
            is HostWrite.NoResponse -> return HostRequery.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(response.body)
        return when {
            response.status == 200 && body != null -> HostRequery.Found(body)
            response.status == 404 && body?.get("error")?.asText() == REQUEST_NOT_FOUND -> HostRequery.NotFound
            response.status == 409 && body?.get("error")?.asText() == REQUEST_IN_PROGRESS -> HostRequery.Silent("호스트가 그 요청을 아직 처리 중이다")
            response.status == 200 -> HostRequery.Silent("본문 모양이 다르다")
            else -> HostRequery.Silent("HTTP ${response.status}")
        }
    }

    override fun siteTimings(): HostCall<JsonNode> = get("/host/site-timings")

    override fun writeSignal(name: String, value: String): HostWrite =
        post("/host/cell/signals/${segment(name)}", json.createObjectNode().put("value", value))

    override fun injectFault(body: ObjectNode): HostWrite = post("/host/faults", body)

    override fun incidents(limit: Int?): HostCall<JsonNode> = get("/host/incidents" + (limit?.let { "?limit=$it" } ?: ""))

    /**
     * 없다는 응답은 404 와 `INCIDENT_NOT_FOUND` 가 함께일 때만이다. 다른 404(그 경로가 없는 서버 등)는 호스트의 판단이
     * 아니므로 못 읽음이다.
     */
    override fun incident(incidentId: String, instanceId: String?): HostIncident {
        val query = instanceId?.let { "?instanceId=${segment(it)}" } ?: ""
        val request = HttpRequest.newBuilder(URI.create("$base/host/incidents/${segment(incidentId)}$query")).GET()
        val response = when (val write = send(request, requestTimeout)) {
            is HostWrite.NoResponse -> return HostIncident.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(response.body)
        return when {
            response.status == 200 && body != null -> HostIncident.Found(body)
            response.status == 404 && body?.get("error")?.asText() == INCIDENT_NOT_FOUND -> HostIncident.NotFound(body)
            response.status == 200 -> HostIncident.Silent("본문 모양이 다르다")
            else -> HostIncident.Silent("HTTP ${response.status}")
        }
    }

    override fun executionUnits(executionId: String): HostCall<JsonNode> = executionsOf(executionIds = listOf(executionId))

    override fun resolve(
        executionId: String,
        unitId: String,
        decision: String,
        approverId: String,
        instanceId: String,
        requestId: UUID,
    ): HostWrite {
        val body = json.createObjectNode()
            .put("decision", decision)
            .put("approverId", approverId)
            .put("requestId", requestId.toString())
            .put("instanceId", instanceId)
        return post("/host/executions/${segment(executionId)}/units/${segment(unitId)}/resolve", body)
    }

    override fun cancelExecution(executionId: String, approverId: String, reason: String, instanceId: String, requestId: UUID): HostWrite {
        val body = json.createObjectNode()
            .put("approverId", approverId)
            .put("reason", reason)
            .put("instanceId", instanceId)
            .put("requestId", requestId.toString())
        return post("/host/executions/${segment(executionId)}/cancel", body)
    }

    override fun releaseFaults(
        executionId: String,
        approverId: String,
        reason: String,
        instanceId: String,
        sawFaults: ArrayNode,
        requestId: UUID,
    ): HostWrite {
        val body = json.createObjectNode()
            .put("approverId", approverId)
            .put("reason", reason)
            .put("instanceId", instanceId)
        body.set<JsonNode>("sawFaults", sawFaults)
        body.put("requestId", requestId.toString())
        return post("/host/executions/${segment(executionId)}/release", body)
    }

    override fun closeUnrestored(
        jobOrderId: String,
        approverId: String,
        reason: String,
        executionInstanceId: String,
        executionId: String,
        attestedStopped: Boolean,
        requestId: UUID,
    ): HostWrite {
        val body = json.createObjectNode()
            .put("approverId", approverId)
            .put("reason", reason)
            .put("executionInstanceId", executionInstanceId)
            .put("executionId", executionId)
            .put("attestedStopped", attestedStopped)
            .put("requestId", requestId.toString())
        return post("/host/job-orders/${segment(jobOrderId)}/close", body)
    }

    override fun closure(jobOrderId: String, requestId: UUID): HostCall<JsonNode?> {
        val request = HttpRequest.newBuilder(URI.create("$base/host/job-orders/${segment(jobOrderId)}/closure?requestId=$requestId")).GET()
        val response = when (val write = send(request, requestTimeout)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(response.body)
        return when {
            response.status == 200 && body != null -> HostCall.Ok(body)
            response.status == 404 && body?.get("error")?.asText() == NOT_CLOSED -> HostCall.Ok(null)
            response.status == 409 && body?.get("error")?.asText() == REQUEST_IN_PROGRESS -> HostCall.Silent("호스트가 그 요청을 아직 처리 중이다")
            response.status == 200 -> HostCall.Silent("본문 모양이 다르다")
            else -> HostCall.Silent("HTTP ${response.status}")
        }
    }

    override fun residualHolds(): HostCall<JsonNode> = get("/host/robots/residual-holds")

    override fun clearResidualHold(robotId: String, markerId: Long, approverId: String, reason: String, requestId: UUID, cited: JsonNode?): HostWrite {
        val body = json.createObjectNode()
            .put("approverId", approverId)
            .put("reason", reason)
            .put("requestId", requestId.toString())
        if (cited != null) body.set<JsonNode>("cited", cited)
        return post("/host/robots/${segment(robotId)}/residual-holds/$markerId/clear", body)
    }

    override fun residualHold(robotId: String, markerId: Long): HostCall<JsonNode?> {
        val request = HttpRequest.newBuilder(URI.create("$base/host/robots/${segment(robotId)}/residual-holds/$markerId")).GET()
        val response = when (val write = send(request, requestTimeout)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(response.body)
        return when {
            response.status == 200 && body != null -> HostCall.Ok(body)
            response.status == 404 && body?.get("error")?.asText() == RESIDUAL_HOLD_NOT_FOUND -> HostCall.Ok(null)
            response.status == 200 -> HostCall.Silent("본문 모양이 다르다")
            else -> HostCall.Silent("HTTP ${response.status}")
        }
    }

    override fun jobResponses(jobOrderId: String?, limit: Int?): HostCall<JsonNode> {
        val query = listOfNotNull(jobOrderId?.let { "jobOrderId=${segment(it)}" }, limit?.let { "limit=$it" })
        return get("/host/job-responses" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?"))
    }

    /** 객체 본문만 받는다. 호스트의 GET 은 늘 객체를 준다(S3a JSON 계약 §5·§6, S3b JSON 계약 §4). */
    private fun get(path: String): HostCall<JsonNode> {
        val response = when (val write = send(HttpRequest.newBuilder(URI.create(base + path)).GET(), requestTimeout)) {
            is HostWrite.NoResponse -> return HostCall.Silent(write.cause, unreached = true)
            is HostWrite.Answered -> write
        }
        if (response.status != 200) return HostCall.Silent("HTTP ${response.status}")
        return read(response.body) { body -> json.readTree(body).takeIf { it.isObject } }
    }

    private fun post(path: String, body: ObjectNode, timeout: Duration = requestTimeout): HostWrite {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        return send(request, timeout)
    }

    private fun send(request: HttpRequest.Builder, timeout: Duration): HostWrite = try {
        val response = http.send(request.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())
        HostWrite.Answered(response.statusCode(), response.body())
    } catch (e: IOException) {
        HostWrite.NoResponse("응답 없음: ${e.javaClass.simpleName}")
    }

    private fun robots(body: ObjectNode, robotIds: List<String>): ObjectNode {
        body.putArray("robotIds").apply { robotIds.forEach(::add) }
        return body
    }

    private fun objectOrNull(body: String): JsonNode? = try {
        json.readTree(body)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
        null
    }

    /** 경로 조각 하나로 인코딩한다. 신호 이름은 화면이 경로로 실어 온 값이라 그대로 붙이면 경로가 바뀔 수 있다. */
    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    /** [parse] 가 널을 내면(본문 `null`, 모양 어긋남) 값을 모르는 것이다. */
    private fun <T : Any> read(body: String, parse: (String) -> T?): HostCall<T> = try {
        parse(body)?.let { HostCall.Ok(it) } ?: HostCall.Silent("본문 모양이 다르다")
    } catch (e: JacksonException) {
        HostCall.Silent("본문 해석 실패: ${e.originalMessage}")
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * 작업 지시 철회 요청 제한(B3). 대체 접수가 원 요청 행 잠금을 쥔 채 기다리는 한도라 일반 요청 제한([REQUEST_TIMEOUT])보다 짧다. 넘으면 대체를
         * 접수하지 않고 503 이다(철회는 반영됐을 수 있다 — 상위 시스템의 재전송이 멱등 호출로 이어 간다).
         */
        val WITHDRAW_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** 모의 실행 요청 제한(T10). 호스트의 실제 시간 상한 30초보다 길어야 한다. */
        val MOCK_RUN_TIMEOUT: Duration = Duration.ofSeconds(60)

        /** 재조회에서 남은 행이 없다는 호스트 오류 이름(S3b JSON 계약 §3). */
        const val REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND"

        /** 재조회에서 호스트가 그 요청을 아직 처리 중이라는 오류 이름(S3b JSON 계약 §3). */
        const val REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS"

        /** 종결 조회에서 종결하지 않았다는 호스트 오류 이름(결함 14). */
        const val NOT_CLOSED = "NOT_CLOSED"

        /** 잔여 파지 표시 조회에서 그 기체에 그 표시가 없다는 호스트 오류 이름(P0-b2). */
        const val RESIDUAL_HOLD_NOT_FOUND = "RESIDUAL_HOLD_NOT_FOUND"

        /** 인시던트 단건에서 그런 인시던트가 없다는 호스트 오류 이름(S4a JSON 계약 §4). */
        const val INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"

        /** 형식이 틀린 주소를 기동에서 잡는다. 그대로 두면 요청마다 호스트 불통으로 보인다. */
        fun checkBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "실행 호스트 주소가 http(s)://호스트[:포트] 꼴이 아니다: '$baseUrl'"
            }
            return trimmed
        }
    }
}
