package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 출발 결품의 대체 위치를 상위 시스템이 고르는 길을 실제 세 프로세스(현장·실행 호스트·운영 서비스)와 외부 주문 API 로 한 번 돈다.
 *
 * 상위 시스템이 빈 출발 자리(현장 셀 대역의 빈 슬롯)로 외부 주문을 낸다. 실행 호스트의 게이트가 셀을 보고 보내지 않으며 기체별
 * 미배정 사유에 대체 위치를 싣는다. 상위 시스템은 외부 주문 상태 조회로 그 대체 위치를 읽고, 그 자리로 고친 새 외부 주문을 앞선
 * 외부 주문 ID 와 함께 보낸다. 새 주문은 같은 게이트를 다시 지나 실행이 서고, 두 작업 요청의 상태 조회에 연결이 양쪽으로 보인다.
 */
class UpstreamReplacementTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()
        private const val TOKEN = "replacement-test-token"
        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MATERIAL = "ENGINE-COVER-A"

        /** 현장 셀 대역의 슬롯이며 처음에 비어 있다. 셀은 이 자리를 «비었다» 고 말한다. */
        private const val EMPTY_SOURCE = "RACK-204.S04"

        /** 현장 셀 대역의 제시 자리이며 늘 [MATERIAL] 을 든다. */
        private const val HELD_SOURCE = "SEQ-IN-02.BIN-A"

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start(integrationToken = TOKEN)
            driver = ExecutionDriver(stack)
            Commissioned.complete(stack)
        }

        @AfterAll
        @JvmStatic
        fun down() {
            if (::stack.isInitialized) stack.close()
        }
    }

    @Test
    fun `상위 시스템은 출발 결품의 대체 위치를 상태 조회로 읽고 앞선 외부 주문을 이은 새 외부 주문으로 고쳐 보내 실행이 서고 연결이 양쪽에 보인다`() {
        val template = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == "DATA_V1" }["definition"].asText()
        val draft = engineer("/api/missions/$WORK_MASTER/drafts", json.createObjectNode().put("definition", template).toString())["outcome"]["draft"]["draftId"].asLong()
        assertEquals("PASSED", engineer("/api/missions/$WORK_MASTER/drafts/$draft/mock-run", "{}")["outcome"]["result"].asText())
        assertEquals("ACTIVATED", engineer("/api/missions/$WORK_MASTER/drafts/$draft/activate", """{"reason":"대체 위치 통합 검증"}""")["outcome"]["result"].asText())
        val sample = """{"slots":["RACK-204.S01"],"material":"$MATERIAL","presentation":"$HELD_SOURCE"}"""
        val created = engineer(
            "/api/mission-interfaces",
            """{"interfaceId":"sequenced-supply","version":1,"name":"순서 공급","workMasterId":"$WORK_MASTER","mapping":{"slots":"/slots","material":"/material","presentation":"/presentation"},"sample":$sample}""",
        )
        val expected = created["deployment"]?.get("deploymentVersion")?.asInt() ?: 0
        engineer("/api/mission-interfaces/sequenced-supply/1/deploy", """{"missionVersion":"1","expectedDeploymentVersion":$expected,"reason":"검증 버전 고정"}""")
        engineer("/api/mission-interfaces/sequenced-supply/1/state", """{"expectedStatus":"DRAFT","status":"ENABLED","reason":"검증 호출 공개"}""")

        val first = order("""{"externalOrderId":"MES-SUPPLY-1","interfaceId":"sequenced-supply","interfaceVersion":1,"input":${sample.replace(HELD_SOURCE, EMPTY_SOURCE)}}""")
        assertEquals("UNASSIGNED", first["state"].asText(), "$first")
        val firstId = first["receiptId"].asText()
        val shortage = status(firstId)
        assertTrue(shortage["rejection"].isNull, "$shortage")
        val refusal = shortage["unassigned"]["refusals"].single { it["robotId"].asText() == HUMANOID }
        assertEquals(EMPTY_SOURCE, refusal["sourceMissing"]["source"].asText(), "$refusal")
        assertEquals(listOf(HELD_SOURCE), refusal["alternativeLocations"].map { it.asText() }, "$refusal")
        assertEquals(0, driver.executions()["executions"].size())

        // 상위 시스템이 대체 위치를 골라 새 외부 주문으로 보낸다. 운영 서비스는 앞선 주문을 고치지 않는다.
        val second = order(
            """{"externalOrderId":"MES-SUPPLY-2","replacesExternalOrderId":"MES-SUPPLY-1","interfaceId":"sequenced-supply","interfaceVersion":1,"input":$sample}""",
        )
        assertEquals("ACCEPTED", second["state"].asText(), "$second")
        val execution = driver.execution(second["submission"]["outcome"]["executionId"].asText())
        assertEquals(second["jobOrderId"].asText() to HUMANOID, execution["jobOrderId"].asText() to execution["robotId"].asText(), "$execution")

        val original = status(firstId)
        assertEquals("UNASSIGNED", original["receipt"]["state"].asText())
        assertEquals(listOf("MES-SUPPLY-2", second["receiptId"].asText()), listOf(original["supersededBy"]["externalOrderId"].asText(), original["supersededBy"]["receiptId"].asText()), "$original")
        assertEquals(listOf(HELD_SOURCE), original["unassigned"]["refusals"].single { it["robotId"].asText() == HUMANOID }["alternativeLocations"].map { it.asText() })
        val replacement = status(second["receiptId"].asText())
        assertEquals(listOf("MES-SUPPLY-1", firstId, "UNASSIGNED"), listOf("externalOrderId", "receiptId", "state").map { replacement["replaces"][it].asText() }, "$replacement")
        assertTrue(replacement["supersededBy"].isNull, "$replacement")

        // B3: 대체를 접수하기 전에 운영 서비스가 앞선 작업 지시를 실행 호스트에서 철회했다. 다시 물어도 그 철회(새 작업 요청의 id)이고, 그 작업 지시는
        // 실행 호스트가 값 없는 승인으로도 새 접수로도 짓지 않는다(실행 호스트 시험 `IncidentLoopHostTest`).
        val again = host("/host/job-orders/${first["jobOrderId"].asText()}/withdraw", """{"requestId":"${java.util.UUID.randomUUID()}","reason":"e2e 재확인","requestedBy":"e2e"}""")
        assertEquals(listOf("WITHDRAWN", second["receiptId"].asText()), listOf(again["result"].asText(), again["withdrawal"]["requestId"].asText()), "$again")
        // 대체한 새 작업 지시는 실행이 섰으므로 철회되지 않는다.
        val replaced = host("/host/job-orders/${second["jobOrderId"].asText()}/withdraw", """{"requestId":"${java.util.UUID.randomUUID()}","reason":"e2e 재확인","requestedBy":"e2e"}""")
        assertEquals(listOf("EXECUTION_EXISTS", execution["executionId"].asText()), listOf(replaced["result"].asText(), replaced["executionId"].asText()), "$replaced")
    }

    private fun host(path: String, body: String): JsonNode {
        val call = HttpRequest.newBuilder(URI.create("${stack.hostUrl}$path")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val reply = HttpClient.newHttpClient().send(call, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reply.statusCode(), reply.body())
        return json.readTree(reply.body())
    }

    private fun order(body: String): JsonNode {
        val call = HttpRequest.newBuilder(URI.create("${stack.opsUrl}/integration/v1/job-orders"))
            .header("Authorization", "Bearer $TOKEN").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val reply = HttpClient.newHttpClient().send(call, HttpResponse.BodyHandlers.ofString())
        assertEquals(202, reply.statusCode(), reply.body())
        return json.readTree(reply.body())["receipt"]
    }

    private fun status(id: String): JsonNode {
        val call = HttpRequest.newBuilder(URI.create("${stack.opsUrl}/integration/v1/job-orders/$id")).header("Authorization", "Bearer $TOKEN").GET().build()
        val reply = HttpClient.newHttpClient().send(call, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reply.statusCode(), reply.body())
        return json.readTree(reply.body())
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }
}
