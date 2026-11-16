package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.middleware.mission.UnitStep
import dev.picasso.middleware.SourceObservation
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.SOURCE
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
 * 출발 관측 선언(picasso `sourceObservation`)과 출발 미관측(picasso `SourceUnobserved`)의 호스트 자리.
 *
 * - 관측을 요구한 버전(REQUIRED)은 설비가 말이 없거나 «있다» 고만 하면 보내지 않고, 기체별 미배정 사유에 출발 미관측을 싣는다. 결품 칸과 접지 않는다.
 * - 사람의 출발 자리 확인은 제출 본문의 `sourceConfirmations` 로 그 제출 한 번 동안만 셀 관측이 된다. 설비의 답을 뒤집지 않는다.
 * - 시작용 정의와 선언 전에 저장된 활성 버전은 오늘의 거동(OPTIONAL)을 지킨다.
 */
class SourceObservationHostTest {

    private val robots = listOf(HUMANOID)

    private fun HostBench.template(id: String): String =
        get("/host/missions/templates/$PSR")["templates"].single { it["id"].asText() == id }["definition"].asText()

    /** 시작용 정의 DATA_V1 의 출발 관측 선언만 [observation] 으로 바꿔 활성화한다. 모의 실행이 통과해야 한다. */
    private fun HostBench.activate(observation: String): Int {
        val definition = template("DATA_V1").replace("\"sourceObservation\": \"OPTIONAL\"", "\"sourceObservation\": \"$observation\"")
        check(observation == "OPTIONAL" || definition != template("DATA_V1")) { "선언을 바꾸지 못했다" }
        return activateDefinition(definition)
    }

    private fun HostBench.activateDefinition(definition: String): Int {
        val saved = post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to UUID.randomUUID().toString())))
        assertEquals(200, saved.status, saved.body.toString())
        val draftId = saved.body!!["draft"]["draftId"].asLong()
        val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to UUID.randomUUID().toString()))).body!!
        assertEquals("PASSED", mock["result"].asText(), mock.toString())
        val activation = post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "출발 관측 선언", "robotIds" to robots, "requestId" to UUID.randomUUID().toString())),
        ).body!!
        assertEquals("ACTIVATED", activation["result"].asText(), activation.toString())
        return activation["version"].asInt()
    }

    /** 출발 자리의 셀 대역 답을 [place] 로 바꾸고 호스트가 읽을 때까지 기다린다. `null` 이면 그 자리를 셀에서 뺀다(말이 없다). */
    private fun HostBench.source(place: String?) {
        val standard = """{"id":"$SOURCE","occupied":true,"material":"$MATERIAL","observedAt":null}"""
        cellBody = if (place == null) {
            HostBench.STANDARD_CELL.replace("\"presentations\":[$standard]", "\"presentations\":[]")
        } else {
            HostBench.STANDARD_CELL.replace(standard, place)
        }
        check(cellBody != HostBench.STANDARD_CELL) { "셀 본문을 바꾸지 못했다" }
        eventually("출발 자리 답을 읽는다") {
            val presentations = get("/host/cell")["cell"]?.get("presentations") ?: return@eventually false
            if (place == null) presentations.none { it["id"].asText() == SOURCE } else presentations.any { it.toString() == JSON.readTree(place).toString() }
        }
    }

    private fun confirmations(vararg confirmed: Pair<String, String>): String = JSON.writeValueAsString(
        confirmed.map { (source, identity) -> mapOf("source" to source, "identity" to identity, "confirmedBy" to "operator/kim", "confirmedAt" to "2026-10-14T00:00:00Z") },
    )

    private fun HostBench.submit(jobOrderId: String, confirmations: String? = null): JsonNode {
        val body = request(rack(jobOrderId, "RACK-204.S01"), "candidates", HUMANOID)
        val sent = if (confirmations == null) body else body.removeSuffix("}") + ",\"sourceConfirmations\":$confirmations}"
        val reply = post("/host/job-orders", sent)
        assertEquals(200, reply.status, reply.body.toString())
        return reply.body!!
    }

    @Test
    fun `출발 관측을 요구한 버전은 설비가 말이 없거나 있다고만 하면 미배정이고 기체별 사유에 출발 미관측이 실리며 결품 칸은 비었다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            assertEquals(1, bench.activate("REQUIRED"))

            bench.source(null)
            val silent = bench.submit("JO-1")
            assertEquals("UNASSIGNED", silent["result"].asText(), silent.toString())
            val refusal = silent["refusals"].single()
            assertEquals(HUMANOID, refusal["robotId"].asText())
            val unobserved = refusal["sourceUnobserved"]
            assertEquals(SOURCE, unobserved["source"].asText(), refusal.toString())
            assertEquals(MATERIAL, unobserved["material"].asText())
            // 말이 없었다. «있다» 와 접지 않는다.
            assertTrue(unobserved.has("occupied") && unobserved["occupied"].isNull, unobserved.toString())
            // 결품이 아니다: 결품 칸도 대체 자리도 없다.
            assertTrue(refusal.has("sourceMissing") && refusal["sourceMissing"].isNull, refusal.toString())
            assertTrue(refusal.has("alternativeLocations") && refusal["alternativeLocations"].isNull, refusal.toString())
            // 맨 위 칸은 REJECTED 의 것이다. UNASSIGNED 에서는 칸만 있고 비었다.
            assertTrue(silent.has("sourceUnobserved") && silent["sourceUnobserved"].isNull, silent.toString())
            assertEquals(0, bench.get("/host/executions")["executions"].size())

            bench.source("""{"id":"$SOURCE","occupied":true,"material":null,"observedAt":null}""")
            val bare = bench.submit("JO-2")["refusals"].single()
            assertEquals(true, bare["sourceUnobserved"]["occupied"].asBoolean(), bare.toString())
            assertTrue(bare["sourceMissing"].isNull, bare.toString())
        }
    }

    @Test
    fun `사람의 출발 자리 확인을 실은 제출은 그 한 번만 셀 관측이 되어 게이트를 지나고 다음 제출은 다시 설비만 본다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            bench.activate("REQUIRED")
            bench.source(null)

            // 확인이 관측이 된다: 다른 신원을 확인하면 그 신원으로 판정한다(결품).
            val other = bench.submit("JO-1", confirmations(SOURCE to "ENGINE-COVER-B"))["refusals"].single()
            assertEquals("ENGINE-COVER-B", other["sourceMissing"]["observed"].asText(), other.toString())

            // 호스트는 확인을 저장하지 않는다. 확인을 싣지 않은 다음 제출은 설비의 침묵을 다시 본다.
            val again = bench.submit("JO-2")
            assertEquals("UNASSIGNED", again["result"].asText(), again.toString())
            assertEquals(SOURCE, again["refusals"].single()["sourceUnobserved"]["source"].asText(), again.toString())

            val confirmed = bench.submit("JO-3", confirmations(SOURCE to MATERIAL))
            assertEquals("ACCEPTED", confirmed["result"].asText(), confirmed.toString())
            assertEquals(HUMANOID, confirmed["robotId"].asText())
        }
    }

    @Test
    fun `확인한 신원이 다르면 결품이고 설비가 비었다고 말하면 사람의 확인이 그 관측을 뒤집지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            bench.activate("REQUIRED")

            bench.source(null)
            val other = bench.submit("JO-1", confirmations(SOURCE to "ENGINE-COVER-B"))["refusals"].single()
            assertEquals("ENGINE-COVER-B", other["sourceMissing"]["observed"].asText(), other.toString())
            assertTrue(other["sourceUnobserved"].isNull, other.toString())

            bench.source("""{"id":"$SOURCE","occupied":false,"material":null,"observedAt":null}""")
            val empty = bench.submit("JO-2", confirmations(SOURCE to MATERIAL))
            assertEquals("UNASSIGNED", empty["result"].asText(), empty.toString())
            val refusal = empty["refusals"].single()
            assertTrue(refusal["sourceMissing"].has("observed") && refusal["sourceMissing"]["observed"].isNull, refusal.toString())
            assertTrue(refusal["sourceUnobserved"].isNull, refusal.toString())
        }
    }

    @Test
    fun `시작용 정의는 출발 관측을 OPTIONAL 로 선언하고 그 버전은 설비가 말이 없어도 오늘처럼 보낸다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            for (id in listOf("DATA_V1", "ARRIVAL_WAIT", "ARRIVAL_WAIT_HOLD")) {
                val parsed = MissionDefinitionParser.parse(bench.template(id)) as MissionParse.Parsed
                val unit = parsed.definition.steps.filterIsInstance<UnitStep>().single()
                assertEquals(SourceObservation.OPTIONAL, unit.sourceObservation, id)
            }
            assertEquals(1, bench.activate("OPTIONAL"))
            bench.source(null)
            val sent = bench.submit("JO-1")
            assertEquals("ACCEPTED", sent["result"].asText(), sent.toString())
        }
    }

    @Test
    fun `출발 자리 확인 칸이 틀리면 400 이다`() {
        HostBench().use { bench ->
            val body = request(rack("JO-1", "RACK-204.S01"), "candidates", HUMANOID).removeSuffix("}")
            fun post(field: String) = bench.post("/host/job-orders", "$body,\"sourceConfirmations\":$field}")
            val repeated = post(confirmations(SOURCE to MATERIAL, SOURCE to MATERIAL))
            assertEquals(400, repeated.status, repeated.body.toString())
            assertTrue("같은 자리가 두 번" in repeated.body!!["detail"].asText(), repeated.body.toString())
            val at = post("""[{"source":"$SOURCE","identity":"$MATERIAL","confirmedBy":"operator/kim","confirmedAt":"어제"}]""")
            assertEquals(400, at.status, at.body.toString())
            assertEquals(400, post("""[{"source":"$SOURCE","identity":"","confirmedBy":"operator/kim","confirmedAt":"2026-10-14T00:00:00Z"}]""").status)
            assertEquals(400, post("\"x\"").status)
            assertEquals(0, bench.get("/host/executions")["executions"].size())
        }
    }

    @Test
    fun `출발 관측 선언 전에 활성화한 저장 버전은 OPTIONAL 로 되살아나고 새 정의는 선언 없이 활성화되지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val legacy = bench.template("DATA_V1").replace("\"sourceObservation\": \"OPTIONAL\",\n", "")
            check(legacy != bench.template("DATA_V1")) { "선언을 빼지 못했다" }
            val saved = bench.post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to legacy, "actor" to "lee", "requestId" to UUID.randomUUID().toString())))
            val draftId = saved.body!!["draft"]["draftId"].asLong()

            // 새 정의로는 선언 없이 활성화되지 않는다.
            val validated = bench.post("/host/missions/drafts/$draftId/validate", JSON.writeValueAsString(mapOf("robotIds" to robots))).body!!
            assertEquals("REFUSED", validated["result"].asText(), validated.toString())
            val refusal = validated["refusals"].single()
            assertEquals("SOURCE_OBSERVATION_UNDECLARED", refusal["kind"].asText())
            assertEquals("place", refusal["nodeId"].asText())
            assertEquals("ENGINEER", refusal["owner"].asText())

            // 선언 전에 활성화한 버전이 저장돼 있다(트리거는 INSERT 를 막지 않는다).
            Fenced.execute(
                "INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id) " +
                    "VALUES ('$PSR', 1, $draftId, '${legacy.replace("'", "''")}', 'lee', '선언 전 활성화', '${UUID.randomUUID()}')",
            )
            bench.restartHost()
            bench.awaitPump()
            assertEquals(1, bench.get("/host/missions/$PSR")["active"]["version"].asInt())
            bench.source(null)
            val sent = bench.submit("JO-1")
            assertEquals("ACCEPTED", sent["result"].asText(), sent.toString())
            assertEquals(1, bench.execution(sent["executionId"].asText())!!["missionVersion"].asInt())
        }
    }

    @Test
    fun `선언을 채우는 것은 신원을 대는 출발 자리에 선언이 없는 노드뿐이고 고칠 것이 없으면 같은 정의다`() {
        fun parse(text: String) = (MissionDefinitionParser.parse(text) as MissionParse.Parsed).definition
        val template = javaClass.getResourceAsStream("/mission-templates/PrepareSequencedRack.data-v1.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val declared = parse(template.replace("\"OPTIONAL\"", "\"REQUIRED\""))
        assertSame(declared, StoredMissionCatalog.undeclaredAsOptional(declared))
        val legacy = parse(template.replace("\"sourceObservation\": \"OPTIONAL\",\n", ""))
        val filled = StoredMissionCatalog.undeclaredAsOptional(legacy).steps.filterIsInstance<UnitStep>().single()
        assertEquals(SourceObservation.OPTIONAL, filled.sourceObservation)
    }

    companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
