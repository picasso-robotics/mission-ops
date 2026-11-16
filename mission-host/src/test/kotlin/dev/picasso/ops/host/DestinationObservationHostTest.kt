package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.SourceObservation
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.middleware.mission.UnitStep
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.registry.PostgresSupport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 목적지 관측 선언(picasso `destinationObservation`, §15.235)의 호스트 자리.
 *
 * - 관측을 요구한 버전(REQUIRED)은 모의 실행을 지나 활성화된다. 설비가 목적지를 비었다고 하면 보내고, «있다» 고 하거나 말이 없으면
 *   보내지 않으며 기체별 미배정 사유에 텍스트로만 싣는다(출발 쪽 구조 칸은 비었다).
 * - 시작용 정의와 선언 전에 저장된 활성 버전은 오늘의 거동(OPTIONAL, 목적지를 설비에 묻지 않는다)을 지킨다.
 */
class DestinationObservationHostTest {

    private val robots = listOf(HUMANOID)

    private fun HostBench.template(id: String): String =
        get("/host/missions/templates/$PSR")["templates"].single { it["id"].asText() == id }["definition"].asText()

    /** 시작용 정의 DATA_V1 의 목적지 관측 선언만 [observation] 으로 바꿔 활성화한다. 모의 실행이 통과해야 한다. */
    private fun HostBench.activate(observation: String): Int {
        val definition = template("DATA_V1").replace(DECLARED, "\"destinationObservation\": \"$observation\"")
        check(observation == "OPTIONAL" || definition != template("DATA_V1")) { "선언을 바꾸지 못했다" }
        val saved = post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to UUID.randomUUID().toString())))
        assertEquals(200, saved.status, saved.body.toString())
        val draftId = saved.body!!["draft"]["draftId"].asLong()
        val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to UUID.randomUUID().toString()))).body!!
        assertEquals("PASSED", mock["result"].asText(), mock.toString())
        val activation = post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "목적지 관측 선언", "robotIds" to robots, "requestId" to UUID.randomUUID().toString())),
        ).body!!
        assertEquals("ACTIVATED", activation["result"].asText(), activation.toString())
        return activation["version"].asInt()
    }

    /** 목적지 [SLOT] 의 셀 대역 답을 [place] 로 바꾸고 호스트가 읽을 때까지 기다린다. `null` 이면 그 자리를 셀에서 뺀다(말이 없다). */
    private fun HostBench.slot(place: String?) {
        val standard = """{"id":"$SLOT","occupied":false,"material":null,"observedAt":null}"""
        cellBody = if (place == null) {
            HostBench.STANDARD_CELL.replace("$standard,", "")
        } else {
            HostBench.STANDARD_CELL.replace(standard, place)
        }
        check(cellBody != HostBench.STANDARD_CELL) { "셀 본문을 바꾸지 못했다" }
        eventually("목적지 답을 읽는다") {
            val slots = get("/host/cell")["cell"]?.get("slots") ?: return@eventually false
            if (place == null) slots.none { it["id"].asText() == SLOT } else slots.any { it.toString() == JSON.readTree(place).toString() }
        }
    }

    /** 셀 대역을 표준 본문으로 되돌리고 목적지 [SLOT] 이 비었다고 읽힐 때까지 기다린다. */
    private fun HostBench.standardSlot() {
        cellBody = HostBench.STANDARD_CELL
        eventually("목적지 답을 읽는다") {
            get("/host/cell")["cell"]?.get("slots")?.any { it["id"].asText() == SLOT && !it["occupied"].asBoolean() } == true
        }
    }

    private fun HostBench.submit(jobOrderId: String): JsonNode {
        val reply = post("/host/job-orders", request(rack(jobOrderId, SLOT), "candidates", HUMANOID))
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    /** 미배정이고 기체 하나의 사유가 텍스트뿐이다. 출발 쪽 구조 칸(결품·미관측·대체 자리)은 비었다. */
    private fun textOnlyRefusal(reply: JsonNode): String {
        assertEquals("UNASSIGNED", reply["result"].asText(), reply.toString())
        val refusal = reply["refusals"].single()
        assertEquals(HUMANOID, refusal["robotId"].asText())
        listOf("sourceMissing", "sourceUnobserved", "alternativeLocations").forEach { field ->
            assertTrue(refusal.has(field) && refusal[field].isNull, "$field: $refusal")
        }
        return refusal["reason"].asText()
    }

    @Test
    fun `목적지 관측을 요구한 버전은 모의 실행을 지나 활성화되고 설비가 목적지를 비었다고 하면 보내며 있다고 하거나 말이 없으면 텍스트 사유로 미배정이다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activate("REQUIRED"))

            // 진행 중인 실행이 없다. 예약이 아니라 설비의 지금 관측으로 거부한다.
            bench.slot("""{"id":"$SLOT","occupied":true,"material":"$MATERIAL","observedAt":null}""")
            val occupied = textOnlyRefusal(bench.submit("JO-1"))
            assertTrue("목적지 $SLOT 가 비어 있지 않다" in occupied && "'$MATERIAL' 이 있다" in occupied && "목적지 점유" in occupied, occupied)

            bench.slot(null)
            val silent = textOnlyRefusal(bench.submit("JO-2"))
            assertTrue("목적지 $SLOT 를 확인하지 못했다" in silent && "목적지 미관측" in silent, silent)
            assertEquals(0, bench.get("/host/executions")["executions"].size())

            // 표준 셀은 목적지가 비었다고 말한다.
            bench.standardSlot()
            val sent = bench.submit("JO-3")
            assertEquals("ACCEPTED", sent["result"].asText(), sent.toString())
            assertEquals(1, bench.execution(sent["executionId"].asText())!!["missionVersion"].asInt())
        }
    }

    @Test
    fun `시작용 정의는 목적지 관측을 OPTIONAL 로 선언하고 그 버전은 설비가 목적지를 있다고 해도 오늘처럼 보낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            for (id in listOf("DATA_V1", "ARRIVAL_WAIT", "ARRIVAL_WAIT_HOLD")) {
                val parsed = MissionDefinitionParser.parse(bench.template(id)) as MissionParse.Parsed
                val unit = parsed.definition.steps.filterIsInstance<UnitStep>().single()
                assertEquals(DestinationObservation.OPTIONAL, unit.destinationObservation, id)
            }
            assertEquals(1, bench.activate("OPTIONAL"))
            bench.slot("""{"id":"$SLOT","occupied":true,"material":"$MATERIAL","observedAt":null}""")
            val sent = bench.submit("JO-1")
            assertEquals("ACCEPTED", sent["result"].asText(), sent.toString())
        }
    }

    @Test
    fun `목적지 관측 선언 전에 활성화한 저장 버전은 OPTIONAL 로 되살아나 기동이 멈추지 않고 새 정의는 선언 없이 활성화되지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val legacy = bench.template("DATA_V1").replace("$DECLARED,\n", "")
            check(legacy != bench.template("DATA_V1")) { "선언을 빼지 못했다" }
            val saved = bench.post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to legacy, "actor" to "lee", "requestId" to UUID.randomUUID().toString())))
            val draftId = saved.body!!["draft"]["draftId"].asLong()

            // 새 정의로는 선언 없이 활성화되지 않는다.
            val validated = bench.post("/host/missions/drafts/$draftId/validate", JSON.writeValueAsString(mapOf("robotIds" to robots))).body!!
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("DESTINATION_OBSERVATION_UNDECLARED", refusal["kind"].asText())
            assertEquals("place", refusal["nodeId"].asText())
            assertEquals("ENGINEER", refusal["owner"].asText())

            // 선언 전에 활성화한 버전이 저장돼 있다(트리거는 INSERT 를 막지 않는다). 채우지 않으면 재기동이 멈춘다.
            Fenced.execute(
                "INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id) " +
                    "VALUES ('$PSR', 1, $draftId, '${legacy.replace("'", "''")}', 'lee', '선언 전 활성화', '${UUID.randomUUID()}')",
            )
            bench.restartHost()
            bench.awaitPump()
            assertEquals(1, bench.get("/host/missions/$PSR")["active"]["version"].asInt())
            // OPTIONAL 이므로 설비가 목적지를 있다고 해도 묻지 않고 보낸다.
            bench.slot("""{"id":"$SLOT","occupied":true,"material":"$MATERIAL","observedAt":null}""")
            val sent = bench.submit("JO-1")
            assertEquals("ACCEPTED", sent["result"].asText(), sent.toString())
            assertEquals(1, bench.execution(sent["executionId"].asText())!!["missionVersion"].asInt())
        }
    }

    @Test
    fun `목적지 선언을 채우는 것은 식별 정보를 대는 목적지에 선언이 없는 노드뿐이고 두 선언이 다 없으면 둘 다 채운다`() {
        fun parse(text: String) = (MissionDefinitionParser.parse(text) as MissionParse.Parsed).definition
        fun unit(text: String) = StoredMissionCatalog.undeclaredAsOptional(parse(text)).steps.filterIsInstance<UnitStep>().single()
        val template = javaClass.getResourceAsStream("/mission-templates/PrepareSequencedRack.data-v1.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val required = parse(template.replace(DECLARED, "\"destinationObservation\": \"REQUIRED\""))
        assertSame(required, StoredMissionCatalog.undeclaredAsOptional(required))

        val withoutDestination = template.replace("$DECLARED,\n", "")
        check(withoutDestination != template) { "선언을 빼지 못했다" }
        val filled = unit(withoutDestination.replace("\"sourceObservation\": \"OPTIONAL\"", "\"sourceObservation\": \"REQUIRED\""))
        assertEquals(DestinationObservation.OPTIONAL, filled.destinationObservation)
        // 선언한 출발 관측은 건드리지 않는다.
        assertEquals(SourceObservation.REQUIRED, filled.sourceObservation)

        val neither = unit(withoutDestination.replace("\"sourceObservation\": \"OPTIONAL\",\n", ""))
        assertEquals(listOf(SourceObservation.OPTIONAL, DestinationObservation.OPTIONAL), listOf(neither.sourceObservation, neither.destinationObservation))

        // 식별 정보를 대지 않는 목적지에는 채우지 않는다. 검증기가 선언을 거부하는 노드다(DESTINATION_OBSERVATION_UNUSED).
        val noIdentity = withoutDestination
            .replace("\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },\n", "")
            .replace("\"sourceObservation\": \"OPTIONAL\",\n", "")
        check(noIdentity.length < withoutDestination.length - 60) { "기대 식별 정보를 빼지 못했다" }
        val untouched = parse(noIdentity)
        assertSame(untouched, StoredMissionCatalog.undeclaredAsOptional(untouched))
    }

    companion object {
        const val PSR = "PrepareSequencedRack"
        const val SLOT = "RACK-204.S01"
        const val DECLARED = "\"destinationObservation\": \"OPTIONAL\""
    }
}
