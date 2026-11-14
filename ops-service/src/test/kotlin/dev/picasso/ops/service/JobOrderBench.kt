package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostSourceConfirmation
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.joborders.JobOrderEligibility
import dev.picasso.ops.service.registry.CommissioningSource
import dev.picasso.ops.service.registry.RegistryBinding
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.registry.RegistryRobot
import dev.picasso.ops.service.registry.RegistrySoftware
import dev.picasso.ops.service.robots.RobotListService
import dev.picasso.ops.service.settings.SiteSettingsValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 배정 가능·제출 시험의 대역 세트. registry 읽기와 실행 호스트를 기존 시험처럼 인터페이스 대역으로 끼운다.
 *
 * 기본은 기체 둘(humanoid-01, quadruped-01)이 다 시운전 완료·신선이고, 호스트가 둘 다 통과로 판정하고, 제출에 ACCEPTED 로
 * 답하는 것이다. 시험이 칸을 바꿔 한 칸씩 어긋나게 한다.
 */
class JobOrderBench {

    val at: Instant = Instant.parse("2026-10-08T00:00:00Z")
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    private val json = jacksonObjectMapper()

    var robots: RegistryCall<List<RegistryRobot>> = RegistryCall.Ok(listOf(robot(HUMANOID), robot(QUADRUPED)))
    var bindings: RegistryCall<List<RegistryBinding>> = RegistryCall.Ok(listOf(binding(HUMANOID), binding(QUADRUPED)))

    /** 기체 id 목록을 받아 호스트 판정을 낸다. 기본은 모두 통과다. */
    var judge: (List<String>) -> HostCall<List<HostEligibility>> = { ids -> HostCall.Ok(ids.map(::pass)) }
    var answer: HostWrite = HostWrite.Answered(200, accepted(HUMANOID))
    var executions: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""))
    var cell: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"cell":null}"""))

    /** 화면 실행 페이지·실행 수의 호스트 답(HA 설계 기록 §10). 받은 인자는 [pageCalls] 에 남는다. */
    var recentPage: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"mw-1","executions":[],"nextBefore":null}"""))
    var beforePage: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"settled":[],"nextBefore":null,"olderTotal":0}"""))
    var summary: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"mw-1","total":0,"active":0,"states":{}}"""))
    val pageCalls = mutableListOf<String>()

    /** 좁힌 실행 목록 읽기의 인자(작업 지시 id, 실행 id). 답은 [executions] 다. */
    val narrowed = mutableListOf<Pair<List<String>, List<String>>>()

    /** `GET /host/work-permits` 본문. 기본은 허가 대응을 선언하지 않은 현장이다(ADR 52). */
    var workPermits: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"declared":false,"evaluatedAt":"t","session":null,"validFor":null,"mappings":[],"robots":[]}"""))

    /** 호스트가 받은 판정 요청(작업 지시 본문, 기체 id). */
    val judged = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 호스트가 받은 제출(작업 지시 본문, 후보). */
    val submitted = mutableListOf<Pair<ObjectNode, List<String>>>()

    /** 제출마다 실린 출발 자리 확인. [submitted] 와 같은 순서다. */
    val confirmed = mutableListOf<List<HostSourceConfirmation>>()

    /**
     * 작업 지시 철회(B3)의 호스트 답. `null` 이면 실행 호스트처럼 작업 지시마다 처음 요청 id 의 WITHDRAWN 이다. 받은 (작업 지시, 요청 id)는
     * [withdrawCalls] 에 남는다.
     */
    var withdraw: ((String, UUID) -> HostWrite)? = null
    val withdrawCalls: MutableList<Pair<String, UUID>> = java.util.Collections.synchronizedList(mutableListOf())

    /** 철회마다 실린 (부른 쪽, 까닭). [withdrawCalls] 와 같은 순서다. */
    val withdrawAsked: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
    private val withdrawn = java.util.concurrent.ConcurrentHashMap<String, UUID>()

    /** 호스트 대역. 읽기와 제출이 이 벤치의 칸을 쓴다. */
    inner class FakeHost : HostReads, HostWrites {
        override fun eligibility(jobOrder: ObjectNode, robotIds: List<String>): HostCall<List<HostEligibility>> {
            judged += jobOrder to robotIds
            return judge(robotIds)
        }

        override fun executions(): HostCall<JsonNode> = this@JobOrderBench.executions

        override fun executionsOf(jobOrderIds: Collection<String>, executionIds: Collection<String>): HostCall<JsonNode> {
            narrowed += jobOrderIds.toList() to executionIds.toList()
            return this@JobOrderBench.executions
        }

        override fun executionsRecent(recent: Int): HostCall<JsonNode> = recentPage.also { pageCalls += "recent=$recent" }

        override fun executionsBefore(before: Long, limit: Int?): HostCall<JsonNode> =
            beforePage.also { pageCalls += "before=$before limit=$limit" }

        override fun executionSummary(): HostCall<JsonNode> = summary.also { pageCalls += "summary" }

        override fun cell(): HostCall<JsonNode> = this@JobOrderBench.cell

        override fun withdrawJobOrder(jobOrderId: String, requestId: UUID, reason: String, requestedBy: String): HostWrite {
            withdrawCalls += jobOrderId to requestId
            withdrawAsked += requestedBy to reason
            withdraw?.let { return it(jobOrderId, requestId) }
            return HostWrite.Answered(200, withdrawnBody(jobOrderId, withdrawn.getOrPut(jobOrderId) { requestId }, requestId))
        }

        override fun workPermits(): HostCall<JsonNode> = this@JobOrderBench.workPermits

        override fun submit(jobOrder: ObjectNode, candidates: List<String>): HostWrite = submit(jobOrder, candidates, emptyList())

        override fun submit(jobOrder: ObjectNode, candidates: List<String>, sourceConfirmations: List<HostSourceConfirmation>): HostWrite {
            submitted += jobOrder to candidates
            confirmed += sourceConfirmations
            return answer
        }
    }

    val host = FakeHost()

    val robotList = RobotListService(
        { robots },
        { RegistryCall.Ok(Unit) },
        SITE,
        clock,
        { SiteSettingsValues(SETTINGS_VERSION, Duration.ofSeconds(90)) },
        commissioning = object : CommissioningSource {
            override fun bindings(siteId: String) = bindings

            override fun software(siteId: String): RegistryCall<List<RegistrySoftware>> = RegistryCall.Ok(emptyList())
        },
    )

    val eligibility = JobOrderEligibility(robotList, host, clock)

    /** 실행 목록 본문. [jobOrderIds] 마다 humanoid-01 의 실행이 하나씩 있다. */
    fun executionsWith(vararg jobOrderIds: String): HostCall<JsonNode> {
        val rows = jobOrderIds.mapIndexed { i, id ->
            """{"executionId":"exec-${i + 1}","jobOrderId":"$id","workMasterId":"InspectAsset","missionVersion":null,
               "robotId":"$HUMANOID","physicalState":"RUNNING","units":[],"jobResponse":null}"""
        }
        return HostCall.Ok(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[${rows.joinToString(",")}]}"""))
    }

    companion object {
        const val SITE = "site-01"
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SETTINGS_VERSION = 3L

        /** 기준 90초 안에 보고한 시운전 원장 상태의 기체. */
        fun robot(id: String, status: String = "CONFIRMED", lastReportedAt: String? = "2026-10-07T23:59:50Z") =
            RegistryRobot(robotId = id, siteId = SITE, status = status, lastReportedAt = lastReportedAt)

        fun binding(id: String, siteNames: String = "CONFIRMED") = RegistryBinding(
            robotId = id, vendor = "v", model = "m", profileRevisionId = 2, revision = 1, adapterName = "acme/fleet",
            adapterVersion = "1.0.0", conformanceStatus = "UNTESTED", active = true, siteNames = siteNames,
            adapterVersionId = 7, boundBy = "engineer/kim", boundAt = "t",
        )

        fun pass(id: String) = HostEligibility(id, HostSkillFit.FIT, emptyList(), null, true, emptyList())

        fun missing(id: String, vararg skills: String) = HostEligibility(
            id, HostSkillFit.MISSING, skills.toList(), null, false, listOf("모자란 스킬: ${skills.joinToString(", ")}"),
        )

        fun running(id: String, executionId: String) =
            HostEligibility(id, HostSkillFit.FIT, emptyList(), executionId, false, listOf("진행 중인 실행이 있다: $executionId"))

        /** 실행 호스트의 철회 WITHDRAWN 본문. [stored] 는 저장된 행의 요청 id, [requestId] 는 이번 요청의 것이다. */
        fun withdrawnBody(jobOrderId: String, stored: UUID, requestId: UUID = stored) =
            """{"result":"WITHDRAWN","jobOrderId":"$jobOrderId","instanceId":null,"executionId":null,"robotId":null,"physicalState":null,""" +
                """"withdrawal":{"requestId":"$stored","requestedBy":"ops","reason":"r","withdrawnAt":"2026-10-08T00:00:00Z"},"requestId":"$requestId"}"""

        /** 실행 호스트의 철회 EXECUTION_EXISTS 본문. */
        fun executionExistsBody(jobOrderId: String, executionId: String = "exec-9", robotId: String = HUMANOID) =
            """{"result":"EXECUTION_EXISTS","jobOrderId":"$jobOrderId","instanceId":"mw-1","executionId":"$executionId","robotId":"$robotId",""" +
                """"physicalState":"RUNNING","withdrawal":null,"requestId":"${UUID.randomUUID()}"}"""

        fun accepted(robotId: String) =
            """{"result":"ACCEPTED","executionId":"exec-1","robotId":"$robotId","rejectionReason":null,"refusals":[],"excluded":[]}"""

        val INSPECT_FORM = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"}]}"""
    }
}
