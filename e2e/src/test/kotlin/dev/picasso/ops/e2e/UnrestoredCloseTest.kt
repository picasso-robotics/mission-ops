package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import dev.picasso.ops.host.MissionHost
import dev.picasso.registry.PostgresSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 복원 못 한 실행의 운영자 종결(결함 14)을 실제 세 프로세스(현장·실행 호스트·운영 서비스)와 외부 주문 API 로 한 번 돈다. 순서가 있는 [RestartRecoveryTest]
 * 와 따로 둔다(그 시험은 호스트 재기동의 정상 경로를 이어 쓴다).
 *
 * 상위 시스템의 외부 주문이 도는 실행으로 서고, 그 작업 지시의 판단 기록 행이 읽히지 않게 된 채 호스트를 다시 띄우면 호스트가 그 실행을 포기한다
 * (`RECORD_UNREADABLE`). 기체가 그 작업 지시의 태스크를 끝낸 뒤 운영자가 운영 서비스 REST 로 종결하면(스냅숏이 정지를 보였다) 상위 시스템은 같은
 * 외부 주문의 결과 버전으로 `UNVERIFIED`(완료 단위 없음, `closure` 표시)를 받고, 앞선 주문을 잇는 새 외부 주문이 받아들여져 연결된다.
 */
class UnrestoredCloseTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()
        private const val TOKEN = "unrestored-close-token"
        private const val WORK_MASTER = "PrepareSequencedRack"
        private const val MATERIAL = "ENGINE-COVER-A"
        private const val HELD_SOURCE = "SEQ-IN-02.BIN-A"
        private const val SAMPLE = """{"slots":["RACK-204.S01"],"material":"$MATERIAL","presentation":"$HELD_SOURCE"}"""

        /** 호스트가 응답을 채우는 데 걸리는 실제 시간 상한. 운영 서비스의 결과 수집 주기가 이 안에 한 바퀴 돈다. */
        private val COLLECT_WAIT: Duration = Duration.ofSeconds(30)

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
    fun `복원 못 한 실행을 기체가 멈춘 뒤 운영자가 종결하면 상위 시스템은 UNVERIFIED 와 closure 를 받고 앞선 주문을 잇는 새 주문이 받아들여진다`() {
        val template = stack.get("/api/missions/templates/$WORK_MASTER")["templates"].single { it["id"].asText() == "DATA_V1" }["definition"].asText()
        val draft = engineer("/api/missions/$WORK_MASTER/drafts", json.createObjectNode().put("definition", template).toString())["outcome"]["draft"]["draftId"].asLong()
        assertEquals("PASSED", engineer("/api/missions/$WORK_MASTER/drafts/$draft/mock-run", "{}")["outcome"]["result"].asText())
        assertEquals("ACTIVATED", engineer("/api/missions/$WORK_MASTER/drafts/$draft/activate", """{"reason":"종결 통합 검증"}""")["outcome"]["result"].asText())
        val created = engineer(
            "/api/mission-interfaces",
            """{"interfaceId":"sequenced-supply","version":1,"name":"순서 공급","workMasterId":"$WORK_MASTER","mapping":{"slots":"/slots","material":"/material","presentation":"/presentation"},"sample":$SAMPLE}""",
        )
        val expected = created["deployment"]?.get("deploymentVersion")?.asInt() ?: 0
        engineer("/api/mission-interfaces/sequenced-supply/1/deploy", """{"missionVersion":"1","expectedDeploymentVersion":$expected,"reason":"검증 버전 고정"}""")
        engineer("/api/mission-interfaces/sequenced-supply/1/state", """{"expectedStatus":"DRAFT","status":"ENABLED","reason":"검증 호출 공개"}""")

        // 1. 상위 시스템의 외부 주문이 도는 실행으로 선다. 기체가 그 작업 지시의 태스크를 내 놓을 때까지 민다.
        val first = order("""{"externalOrderId":"MES-1","interfaceId":"sequenced-supply","interfaceVersion":1,"input":$SAMPLE}""")
        assertEquals("ACCEPTED", first["state"].asText(), "$first")
        val receiptId = first["receiptId"].asText()
        val jobOrderId = first["jobOrderId"].asText()
        val executionId = first["submission"]["outcome"]["executionId"].asText()
        val instance = driver.executions()["instanceId"].asText()
        awaitTasks(jobOrderId)

        // 2. 판단 기록이 읽히지 않게 된 채 호스트를 다시 띄운다. 호스트는 그 실행을 포기한다.
        // 시험이 표에 직접 쓴다: 세션 표식을 세워 쓰기 펜싱 트리거(V19)를 지난다. 운영 경로는 리스를 확인한 트랜잭션에서만 표식을 세운다.
        PostgresSupport.connection().use { c ->
            c.createStatement().use { s ->
                s.execute("SET mission.fenced_generation = 'test'")
                s.execute(
                    "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) " +
                        "VALUES ('$jobOrderId', '$instance', '$executionId', '1', '{')",
                )
            }
        }
        stack.restartHost()
        val restore = driver.executions()["restore"]["rows"].single()
        assertEquals(
            listOf(jobOrderId, "GAVE_UP", instance, executionId),
            listOf("jobOrderId", "result", "previousInstanceId", "previousExecutionId").map { restore[it].asText() },
            "$restore",
        )
        assertTrue(restore["reason"].asText().startsWith(MissionHost.RECORD_UNREADABLE), "$restore")
        // 재기동으로 사라지지 않는 목록에 닫을 수 있는 행으로 선다(운영 서비스가 호스트 본문을 그대로 넘긴다).
        val listed = stack.get("/api/executions?recent=20")["unrestored"].single()
        assertEquals(listOf(jobOrderId, "GAVE_UP", true), listOf("jobOrderId", "result", "closable").map { listed[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, "$listed")

        // 3. 기체가 그 작업 지시의 태스크를 끝낼 때까지 가상 시계를 민다. 그 전에는 증언 없이 종결하지 않는다.
        val refused = close(jobOrderId, instance, executionId, attested = false)
        assertEquals("TASK_LIVE", refused["outcome"].asText(), "$refused")
        driver.push(Duration.ofSeconds(1))
        repeat(ExecutionDriver.ROUNDS) {
            if (!orphanTasksTerminal(jobOrderId)) driver.push(ExecutionDriver.STEP)
        }
        assertTrue(orphanTasksTerminal(jobOrderId), "${stack.site.taskHistory(HUMANOID)}")

        // 4. 운영자가 종결한다. 스냅숏이 정지를 보이므로 증언이 필요 없다.
        val closed = close(jobOrderId, instance, executionId, attested = false)
        assertEquals(listOf("SUCCEEDED", "CLOSED", "SNAPSHOT"), listOf(closed["result"].asText(), closed["outcome"].asText(), closed["answer"]["basis"].asText()), "$closed")
        assertEquals(0, stack.get("/api/executions?recent=20")["unrestored"].size())

        // 5. 상위 시스템은 같은 외부 주문의 최신 결과로 UNVERIFIED 를 받는다. 완료 단위는 없고 closure 가 붙는다.
        val result = awaitLatestResult(receiptId)
        val snapshot = result["snapshot"]
        assertEquals(
            listOf(instance, executionId, "UNVERIFIED", true),
            listOf(snapshot["instanceId"].asText(), snapshot["executionId"].asText(), snapshot["jobResponse"]["physicalState"].asText(), snapshot["jobResponse"]["operatorRequired"].asBoolean()),
            "$snapshot",
        )
        assertTrue(snapshot["jobResponse"]["completedUnits"].isEmpty, "$snapshot")
        assertTrue(snapshot["jobResponse"]["incompleteUnits"].size() > 0, "$snapshot")
        snapshot["jobResponse"]["incompleteUnits"].forEach { assertEquals("UNRESTORED_CLOSED", it.asText(), "$snapshot") }
        assertEquals(json.readTree("""{"kind":"UNRESTORED_CLOSED","basis":"SNAPSHOT"}"""), snapshot["closure"], "$snapshot")
        assertEquals("ACCEPTED", status(receiptId)["receipt"]["state"].asText())

        // 6. 앞선 주문을 잇는 새 외부 주문이 받아들여지고 연결이 양쪽에 보인다.
        val second = order("""{"externalOrderId":"MES-2","replacesExternalOrderId":"MES-1","interfaceId":"sequenced-supply","interfaceVersion":1,"input":$SAMPLE}""")
        assertEquals("ACCEPTED", second["state"].asText(), "$second")
        val original = status(receiptId)
        assertEquals(listOf("MES-2", second["receiptId"].asText()), listOf(original["supersededBy"]["externalOrderId"].asText(), original["supersededBy"]["receiptId"].asText()), "$original")
        val replacement = status(second["receiptId"].asText())
        assertEquals(listOf("MES-1", receiptId, "ACCEPTED"), listOf("externalOrderId", "receiptId", "state").map { replacement["replaces"][it].asText() }, "$replacement")
    }

    /** 기체가 그 작업 지시의 태스크를 하나라도 낼 때까지 실제 시간으로 기다린다. */
    private fun awaitTasks(jobOrderId: String) {
        val deadline = Instant.now().plusSeconds(10)
        while (stack.site.taskHistory(HUMANOID).keys.none { it.startsWith("$jobOrderId#") }) {
            check(Instant.now().isBefore(deadline)) { "기체가 $jobOrderId 의 태스크를 받지 않았다: ${stack.site.taskHistory(HUMANOID)}" }
            Thread.sleep(100)
        }
    }

    private fun orphanTasksTerminal(jobOrderId: String): Boolean =
        stack.site.taskHistory(HUMANOID).filterKeys { it.startsWith("$jobOrderId#") }
            .let { tasks -> tasks.isNotEmpty() && tasks.values.all { it.lastOrNull() in TERMINAL } }

    private fun close(jobOrderId: String, instance: String, executionId: String, attested: Boolean): JsonNode {
        val reply = stack.send(
            "POST", "/api/job-orders/$jobOrderId/close", "operator",
            body = """{"executionInstanceId":"$instance","executionId":"$executionId","reason":"판단 기록을 못 읽어 복원하지 못한 실행을 현장에서 확인하고 종결","attestedStopped":$attested}""",
        )
        assertEquals(200, reply.status, "${reply.body}")
        return reply.body!!
    }

    /**
     * 운영 서비스의 결과 수집 주기가 종결 응답을 결과 버전으로 적을 때까지 기다린다. 최신 결과가 먼저 200 이어도 종결 전의 결과일 수 있으므로
     * 그 결과의 스냅숏에 `closure` 가 실릴 때까지 기다린다.
     */
    private fun awaitLatestResult(receiptId: String): JsonNode {
        val deadline = Instant.now().plus(COLLECT_WAIT)
        while (true) {
            val reply = request("GET", "/integration/v1/job-orders/$receiptId/results/latest", null)
            if (reply.statusCode() == 200) {
                val result = json.readTree(reply.body())
                if (!result.path("snapshot").path("closure").isMissingNode && !result.path("snapshot").path("closure").isNull) return result
            }
            check(Instant.now().isBefore(deadline)) { "종결 결과 버전이 나오지 않았다: ${reply.statusCode()} ${reply.body()}" }
            Thread.sleep(500)
        }
    }

    private fun order(body: String): JsonNode {
        val reply = request("POST", "/integration/v1/job-orders", body)
        assertEquals(202, reply.statusCode(), reply.body())
        return json.readTree(reply.body())["receipt"]
    }

    private fun status(id: String): JsonNode {
        val reply = request("GET", "/integration/v1/job-orders/$id", null)
        assertEquals(200, reply.statusCode(), reply.body())
        return json.readTree(reply.body())
    }

    private fun request(method: String, path: String, body: String?): HttpResponse<String> {
        val call = HttpRequest.newBuilder(URI.create("${stack.opsUrl}$path")).header("Authorization", "Bearer $TOKEN")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) call.header("Content-Type", "application/json")
        return HttpClient.newHttpClient().send(call.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun engineer(path: String, body: String): JsonNode {
        val reply = stack.send("POST", path, "engineer", body = body)
        assertEquals(200, reply.status, "$path → ${reply.body}")
        return reply.body!!
    }

    private val TERMINAL = setOf("SUCCEEDED", "FAILED", "CANCELLED", "CANCELLED_RECOVERY_FAILED")
}
