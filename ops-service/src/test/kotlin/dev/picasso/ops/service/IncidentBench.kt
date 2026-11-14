package dev.picasso.ops.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostFaults
import dev.picasso.ops.service.host.HostIncident
import dev.picasso.ops.service.host.HostIncidents
import dev.picasso.ops.service.host.HostWrite
import java.time.Instant
import java.util.UUID

/**
 * 장애 주입·인시던트·운영자 판단 시험의 호스트 대역(S4a 스펙 §7). 기본은 호스트가 주입을 받아들이고(스킬 실패) 판단을
 * `Resolved` 로 답하며, 인시던트 목록이 비어 있는 것이다. 시험이 칸을 바꿔 한 칸씩 어긋나게 한다.
 */
class IncidentBench {

    private val json = jacksonObjectMapper()

    var faultAnswer: HostWrite = HostWrite.Answered(200, SKILL_RAISED)
    var resolveAnswer: HostWrite = HostWrite.Answered(200, resolved("incident-1"))
    var incidents: HostCall<JsonNode> = HostCall.Ok(json.readTree("""{"instanceId":"i-1","total":0,"incidents":[]}"""))
    var incident: HostIncident = HostIncident.Silent("응답 없음: ConnectException")

    /**
     * 판단 재조회용 실행 본문(`executionUnits`). 부를 때마다 몇 번째 호출인지(0부터)를 받는다 — 보내기 직전 떠 두기가 0, 재조회가 1 이다.
     * 기본은 읽을 줄 모르는 호스트(모름)다.
     */
    var units: (Int) -> HostCall<JsonNode> = { HostCall.Silent("판단 재조회용 실행 조회를 지원하지 않는 호스트 읽기다") }

    /** 판단 호출을 받을 때 부른다. 시험이 «호스트가 받았을 때» 의 시각을 정하는 데 쓴다. */
    var onResolve: () -> Unit = {}

    data class Call(
        val op: String,
        val body: JsonNode? = null,
        val executionId: String? = null,
        val unitId: String? = null,
        val decision: String? = null,
        val approverId: String? = null,
        val requestId: UUID? = null,
        val limit: Int? = null,
        val incidentId: String? = null,
        val instanceId: String? = null,
    )

    val calls = mutableListOf<Call>()

    inner class FakeHost : HostIncidents, HostFaults {
        override fun incidents(limit: Int?): HostCall<JsonNode> {
            calls += Call("incidents", limit = limit)
            return this@IncidentBench.incidents
        }

        override fun incident(incidentId: String, instanceId: String?): HostIncident {
            calls += Call("incident", incidentId = incidentId, instanceId = instanceId)
            return this@IncidentBench.incident
        }

        override fun resolve(
            executionId: String,
            unitId: String,
            decision: String,
            approverId: String,
            instanceId: String,
            requestId: UUID,
        ): HostWrite {
            calls += Call(
                "resolve", executionId = executionId, unitId = unitId, decision = decision, approverId = approverId,
                requestId = requestId, instanceId = instanceId,
            )
            onResolve()
            return resolveAnswer
        }

        override fun executionUnits(executionId: String): HostCall<JsonNode> {
            val index = calls.count { it.op == "executionUnits" }
            calls += Call("executionUnits", executionId = executionId)
            return units(index)
        }

        override fun injectFault(body: ObjectNode): HostWrite {
            calls += Call("injectFault", body = body.deepCopy())
            return faultAnswer
        }
    }

    val host = FakeHost()

    /** 호스트가 받은 쓰기 호출(읽기 둘을 뺀 것). */
    fun writes(): List<Call> = calls.filter { it.op in setOf("resolve", "injectFault") }

    /** 인시던트 목록 본문. 줄은 [row] 로 만든다. 이전 인스턴스 사본은 없다. */
    fun listed(vararg rows: String): HostCall<JsonNode> = listedAt("i-1", rows.toList())

    /**
     * 인스턴스 [instanceId] 의 인시던트 목록 본문(S4b 계약 H3). [earlier] 는 이전 인스턴스 사본이고 줄은 [copy] 로 만든다.
     */
    fun listedAt(instanceId: String, rows: List<String>, earlier: List<String> = emptyList()): HostCall<JsonNode> =
        HostCall.Ok(
            json.readTree(
                """{"instanceId":"$instanceId","total":${rows.size},"incidents":[${rows.joinToString(",")}],""" +
                    """"earlierTotal":${earlier.size},"earlier":[${earlier.joinToString(",")}]}""",
            ),
        )

    companion object {
        const val SKILL_RAISED =
            """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","taskId":"JO-1#RACK-204.S01","taskState":"RETRIABLE","raised":true}"""

        fun resolved(incidentId: String?, result: String = "Resolved", detail: String? = null): String =
            """{"result":"$result","detail":${detail?.let { "\"$it\"" } ?: "null"},"incidentId":${incidentId?.let { "\"$it\"" } ?: "null"},"requestId":null}"""

        /** 판단 하나. [wallClockAt] 은 실제 시각, `at` 은 호스트 가상 시각이라 1970 근처다. */
        fun resolution(decision: String, by: String, wallClockAt: Instant): String =
            """{"decision":"$decision","at":"1970-01-01T00:01:05Z","wallClockAt":"$wallClockAt","decidedBy":{"id":"$by","kind":"PERSON"}}"""

        /** 판단 기록의 단위 판단 하나(실행 본문 단위 줄의 `decision`). [at] 은 호스트 시계다. */
        fun unitDecision(decision: String, by: String, attempt: Int, at: String = "1970-01-01T00:01:05Z"): String =
            """{"decision":"$decision","approverId":"$by","approverKind":"PERSON","at":"$at","attempt":$attempt}"""

        /** 실행 하나의 `GET /host/executions?executionId=` 본문. 단위 [unitId] 의 시도와 판단만 싣는다. */
        fun unitBody(
            attempt: Int,
            decision: String? = null,
            instanceId: String = "i-1",
            executionId: String = "exec-1",
            unitId: String = "T1",
        ): HostCall<JsonNode> = HostCall.Ok(
            jacksonObjectMapper().readTree(
                """{"instanceId":"$instanceId","executions":[{"executionId":"$executionId","units":[""" +
                    """{"unitId":"$unitId","skillType":"inspect","state":"FAILED","reached":"E0","attempt":$attempt,"decision":${decision ?: "null"}}]}]}""",
            ),
        )

        /** 목록 한 줄(S4a JSON 계약 §3, 19칸). */
        fun row(
            incidentId: String,
            executionId: String = "exec-1",
            unitId: String = "rack-arrival",
            resolution: String? = null,
            held: Boolean = resolution == null,
        ): String =
            """{"incidentId":"$incidentId","executionId":"$executionId","jobOrderId":"JO-1","robotId":"humanoid-01","unitId":"$unitId",
               "at":"1970-01-01T00:00:25Z","failureClass":"SIGNAL_DEADLINE","route":"SIGNAL","missionVersion":2,"siteSettingsVersion":1,
               "evidenceBeforeSeconds":30,"evidenceAfterSeconds":10,"inDoubtGraceSeconds":null,"stallWindowSeconds":null,
               "unresolved":true,"resolution":${resolution ?: "null"},"fault":null,"held":$held,"confirmedWithoutEvidence":false}"""

        /** 이전 인스턴스 사본 한 줄(S4b 계약 H3, 20칸). `instanceId` 를 앞에 두고 보류가 아니다. */
        fun copy(
            instanceId: String,
            incidentId: String,
            resolution: String? = null,
            executionId: String = "exec-1",
            unitId: String = "rack-arrival",
        ): String =
            """{"instanceId":"$instanceId",""" +
                row(incidentId, executionId = executionId, unitId = unitId, resolution = resolution, held = false).trimStart().removePrefix("{")
    }
}
