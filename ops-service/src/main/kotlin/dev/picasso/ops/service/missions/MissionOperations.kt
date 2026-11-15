package dev.picasso.ops.service.missions

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.finding.Finding
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.joborders.JobOrderForm
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostOperationResult
import dev.picasso.ops.service.operations.HostOperationRunner
import dev.picasso.ops.service.operations.HostRecheck
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.robots.CommissioningState
import dev.picasso.ops.service.robots.RegistryState
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.robots.RobotListView
import java.time.Duration
import java.util.UUID

/** 호스트 검증 결과의 이름(S3b JSON 계약 §4.4). */
enum class HostValidationResult { PASSED, REFUSED, INPUT_UNKNOWN }

/** 호스트 모의 실행 결과의 이름(S3b JSON 계약 §4.5). */
enum class HostMockRunResult { PASSED, FAILED, REFUSED, INPUT_UNKNOWN }

/** 호스트 활성화 결과의 이름(S3b JSON 계약 §4.6). `FLEET_PORT_ABSENT` 는 플릿 경로 정의를 플릿 포트 없는 실행 호스트가 받지 않은 것이다. */
enum class HostActivationResult { ACTIVATED, REFUSED, FLEET_PORT_ABSENT, MOCK_RUN_REQUIRED, INPUT_UNKNOWN }

/**
 * 임무 조작(초안 저장, 모의 실행, 활성화)의 200 응답(S3b 스펙 §7). 작업 지시 제출의 응답과 같은 자리에 같은 칸을 둔다.
 *
 * @param workMasterId 조작 기록의 대상
 * @param result 처음 남긴 행의 결과. 성공, 거부, 응답 없음 중 하나다
 * @param confirmation 응답 없음 뒤 재조회의 결과. 재조회도 실패했으면 널이다
 * @param outcome 호스트의 200 본문 그대로(S3b JSON 계약 §4.3·§4.5·§4.6). 호스트가 안 닿았거나 4xx 거나 본문을 못 읽었으면
 *   널이다. 결과 이름(`INPUT_UNKNOWN` 포함)은 이 본문의 `result` 에 있다
 * @param findings 호스트가 거부(`REFUSED`)했을 때 그 거부 목록을 거부 카드 모양으로 옮긴 것. 그 밖에는 빈 목록이다.
 *   `INPUT_UNKNOWN` 은 거부가 아니라 «모름» 이라 여기에 오지 않는다
 * @param rejection 호스트가 4xx 로 막았을 때(예: 없는 초안 404 `DRAFT_NOT_FOUND`)만 있다
 */
data class MissionOperationOutcome(
    val requestId: UUID,
    val workMasterId: String,
    val result: OperationResult,
    val confirmation: OperationResult?,
    val outcome: JsonNode?,
    val findings: List<Finding>,
    val rejection: HostRejection?,
)

/** 검증의 200 응답. 검증은 아무것도 남기지 않아 조작 기록과 요청 id 가 없다. [outcome] 은 호스트 본문 그대로다. */
data class MissionValidationReply(val workMasterId: String, val outcome: JsonNode, val findings: List<Finding>)

/** 시운전 완료 기체(T7). «없음»(빈 목록)과 «모름» 을 접지 않는다. */
sealed interface CommissionedRobots {
    data class Known(val robotIds: List<String>) : CommissionedRobots

    /** [detail] 은 사전 거부의 detail 이다. */
    data class Unknown(val detail: String) : CommissionedRobots
}

/** 모의 실행·활성화 한 번의 응답. 시운전 완료 기체를 모르면 호스트를 부르지 않는다. */
sealed interface MissionSubmission {
    data class Submitted(val outcome: MissionOperationOutcome) : MissionSubmission

    /** registry 에서 시운전 완료 기체를 지금 읽지 못했다. 조작 기록에 남지 않는다. */
    data class RobotsUnknown(val detail: String) : MissionSubmission
}

/** 검증 한 번의 응답. 검증은 조작이 아니라 응답 없음과 재조회가 없다. */
sealed interface MissionValidation {
    data class Answered(val reply: MissionValidationReply) : MissionValidation

    /** 호스트가 4xx 로 막았다. 운영 서비스가 같은 상태 코드로 넘긴다. */
    data class HostRejected(val rejection: HostRejection) : MissionValidation

    /** 호스트가 안 닿았거나 5xx 거나 200 본문을 못 읽었다. */
    data class HostSilent(val cause: String) : MissionValidation

    data class RobotsUnknown(val detail: String) : MissionValidation
}

/** 호스트 판정 본문(검증·모의 실행·활성화) 중 운영 서비스가 읽는 칸. 나머지는 [MissionOperationOutcome.outcome] 으로 그대로 넘긴다. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class HostJudgment(val result: String, val refusals: List<HostMissionRefusal>)

/**
 * 임무 버전 조작(S3b 스펙 §7). 초안 저장, 모의 실행, 활성화는 조작 기록에 남기고, 검증은 남기지 않는다(호스트도 아무것도
 * 남기지 않는다). 모드 검사와 사유 검사는 컨트롤러의 몫이다.
 *
 * 검증·모의 실행·활성화에는 시운전 완료 기체 id 를 붙인다(현장 스킬 계산용, T7). registry 에서 지금 읽은 기체 목록만 쓴다.
 * registry 가 답하지 않거나 시운전 판정이 없는 기체가 있으면 «모름» 이며 호스트를 부르지 않는다. 빈 목록이나 직전 목록으로
 * 대신하지 않는다. 빈 목록을 보내면 호스트가 현장 스킬을 «없음» 으로 읽어 엉뚱한 `SKILL_NOT_ON_SITE` 를 낸다(3값 원칙).
 *
 * 응답 없음 뒤 재조회는 요청 id 로 한다(T9). 호스트가 그 요청 id 로 남긴 행이 있고 그 행이 이 조작의 것(초안 저장은
 * `draft`, 모의 실행은 `mockRun`, 활성화는 `version`)이면 반영됨, 호스트가 남은 행이 없다고 답하면 반영 안 됨이다.
 * 모의 실행의 거부·모름과 활성화의 거부·플릿 포트 없음·모의 실행 없음·모름은 행을 남기지 않아 재조회에서 반영 안 됨으로 보인다.
 *
 * 호스트 결과 → 조작 기록 결과(S3b JSON 계약 §9.6): 초안 저장 200 은 성공, 모의 실행 PASSED·FAILED 는 돌았으므로 성공,
 * REFUSED·INPUT_UNKNOWN 은 거부, 활성화 ACTIVATED 는 성공, 나머지 넷(REFUSED·FLEET_PORT_ABSENT·MOCK_RUN_REQUIRED·INPUT_UNKNOWN)은 거부다.
 */
class MissionOperations(
    private val robots: RobotListService,
    private val host: HostMissions,
    log: OperationLog,
    requeryDelay: Duration = Duration.ofSeconds(1),
    private val json: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule()),
) {
    private val runner = HostOperationRunner(log, requeryDelay, json)

    fun saveDraft(actor: Actor, workMasterId: String, definition: String,sample:JsonNode?=null): MissionOperationOutcome {
        val request = json.createObjectNode().put("op", OP_SAVE_DRAFT).put("workMasterId", workMasterId).put("definition", definition)
        val ran = runner.run(
            actor, workMasterId, request, null,
            accepted = { body -> if (body.path("draft").path("draftId").isIntegralNumber) OperationResult.SUCCEEDED else null },
            recheck = { id -> recheck(id, "draft") },
        ) { id -> if(sample==null)host.saveDraft(workMasterId, definition, actor.user, id) else host.saveDraftWithSample(workMasterId,definition,sample,actor.user,id) }
        return outcome(workMasterId, ran, emptyList())
    }

    fun validate(workMasterId: String, draftId: Long): MissionValidation {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionValidation.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val answered = when (val write = host.validate(draftId, robotIds)) {
            is HostWrite.NoResponse -> return MissionValidation.HostSilent(write.cause)
            is HostWrite.Answered -> write
        }
        val body = objectOrNull(answered.body)
        return when (answered.status) {
            in 200..299 -> {
                val judgment = body?.let(::judgment)?.takeIf { enumOrNull<HostValidationResult>(it.result) != null }
                    ?: return MissionValidation.HostSilent("본문 모양이 다르다")
                MissionValidation.Answered(MissionValidationReply(workMasterId, body, findings(judgment)))
            }
            in 400..499 -> MissionValidation.HostRejected(
                HostRejection(answered.status, body?.get("error")?.asText(), body?.get("detail")?.asText()),
            )
            else -> MissionValidation.HostSilent("HTTP ${answered.status}")
        }
    }

    fun mockRun(actor: Actor, workMasterId: String, draftId: Long): MissionSubmission {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionSubmission.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val request = json.createObjectNode().put("op", OP_MOCK_RUN).put("workMasterId", workMasterId).put("draftId", draftId)
        request.putArray("robotIds").apply { robotIds.forEach(::add) }
        val ran = runner.run(
            actor, workMasterId, request, null,
            accepted = { body -> judged<HostMockRunResult>(body)?.let(::mockRunResultOf) },
            recheck = { id -> recheck(id, "mockRun") },
        ) { id -> host.mockRun(draftId, robotIds, id) }
        return MissionSubmission.Submitted(outcome(workMasterId, ran, ran.body?.let(::judgment)?.let(::findings).orEmpty()))
    }

    /** [reason] 은 컨트롤러가 앞뒤 공백을 깎고 비어 있지 않음을 본 값이다. 조작 기록의 사유 칸에 남는다. */
    fun activate(actor: Actor, workMasterId: String, draftId: Long, reason: String): MissionSubmission {
        val robotIds = when (val known = commissioned(robots.read())) {
            is CommissionedRobots.Unknown -> return MissionSubmission.RobotsUnknown(known.detail)
            is CommissionedRobots.Known -> known.robotIds
        }
        val request = json.createObjectNode().put("op", OP_ACTIVATE).put("workMasterId", workMasterId).put("draftId", draftId)
        request.putArray("robotIds").apply { robotIds.forEach(::add) }
        val ran = runner.run(
            actor, workMasterId, request, reason,
            accepted = { body -> judged<HostActivationResult>(body)?.let(::activationResultOf) },
            recheck = { id -> recheck(id, "version") },
        ) { id -> host.activate(draftId, actor.user, reason, robotIds, id) }
        return MissionSubmission.Submitted(outcome(workMasterId, ran, ran.body?.let(::judgment)?.let(::findings).orEmpty()))
    }

    /** 재조회 본문에서 이 조작의 행 칸([field])이 객체면 반영됨이다. 호스트가 못 찾았다고 답하면 반영 안 됨, 못 읽으면 널이다. */
    private fun recheck(requestId: UUID, field: String): HostRecheck? = when (val found = host.missionRequest(requestId)) {
        is HostRequery.Found -> HostRecheck(found.body.get(field)?.isObject == true, found.body)
        HostRequery.NotFound -> HostRecheck(false, null)
        is HostRequery.Silent -> null
    }

    private fun outcome(workMasterId: String, ran: HostOperationResult, findings: List<Finding>) =
        MissionOperationOutcome(ran.requestId, workMasterId, ran.result, ran.confirmation, ran.body, findings, ran.rejection)

    /** 결과 이름이 [E] 의 것이고 거부 목록을 읽을 수 있을 때만 그 결과다. 하나라도 어긋나면 본문을 못 읽은 것이다. */
    private inline fun <reified E : Enum<E>> judged(body: JsonNode): E? = judgment(body)?.let { enumOrNull<E>(it.result) }

    private fun judgment(body: JsonNode): HostJudgment? = try {
        json.treeToValue(body, HostJudgment::class.java)
    } catch (e: JacksonException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 거부만 거부 카드다. 모름(`INPUT_UNKNOWN`)과 플릿 포트 없음·모의 실행 없음은 거부 목록이 비어 있지만, 비어 있지 않아도 옮기지 않는다. */
    private fun findings(judgment: HostJudgment): List<Finding> =
        if (judgment.result == REFUSED) judgment.refusals.map(MissionFindings::of) else emptyList()

    private fun objectOrNull(body: String): JsonNode? = try {
        json.readTree(body)?.takeIf { it.isObject }
    } catch (e: JacksonException) {
        null
    }

    companion object {
        const val OP_SAVE_DRAFT = "SAVE_MISSION_DRAFT"
        const val OP_MOCK_RUN = "MOCK_RUN_MISSION_DRAFT"
        const val OP_ACTIVATE = "ACTIVATE_MISSION_VERSION"
        private const val REFUSED = "REFUSED"

        /** 편집 대상 WorkMaster(T6). 호스트가 받는 것과 같다. 그 밖은 호스트를 부르기 전에 막는다. */
        val EDITABLE: Set<String> = setOf(JobOrderForm.PREPARE_SEQUENCED_RACK)

        /** 시운전 완료 기체를 모르는 사전 거부(503). */
        const val COMMISSIONED_ROBOTS_UNKNOWN = "COMMISSIONED_ROBOTS_UNKNOWN"

        fun mockRunResultOf(result: HostMockRunResult): OperationResult = when (result) {
            HostMockRunResult.PASSED, HostMockRunResult.FAILED -> OperationResult.SUCCEEDED
            HostMockRunResult.REFUSED, HostMockRunResult.INPUT_UNKNOWN -> OperationResult.REJECTED
        }

        fun activationResultOf(result: HostActivationResult): OperationResult = when (result) {
            HostActivationResult.ACTIVATED -> OperationResult.SUCCEEDED
            HostActivationResult.REFUSED, HostActivationResult.FLEET_PORT_ABSENT, HostActivationResult.MOCK_RUN_REQUIRED,
            HostActivationResult.INPUT_UNKNOWN ->
                OperationResult.REJECTED
        }

        /**
         * 시운전 완료 기체 id(T7). 기체 목록을 지금 registry 에서 읽었고 모든 기체의 시운전 판정이 있을 때만 안다. 시운전
         * 완료 기체가 하나도 없으면 빈 목록이다(«없음»).
         */
        fun commissioned(list: RobotListView): CommissionedRobots {
            val unknown = when {
                list.registry == RegistryState.REGISTRY_UNAUTHORIZED -> "운영자 토큰이 registry 와 맞지 않아 시운전 완료 기체를 모른다"
                list.registry != RegistryState.OK || list.robots == null ->
                    "registry 가 답하지 않아 시운전 완료 기체를 모른다" + list.registryCause?.let { ": $it" }.orEmpty()
                list.robots.any { it.commissioning == null } -> "시운전 판정이 없는 기체가 있어 시운전 완료 기체를 모른다"
                else -> null
            }
            if (unknown != null) return CommissionedRobots.Unknown(unknown)
            return CommissionedRobots.Known(
                list.robots!!.filter { it.commissioning?.state == CommissioningState.COMPLETE }.map { it.robot.robotId },
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
    }
}
