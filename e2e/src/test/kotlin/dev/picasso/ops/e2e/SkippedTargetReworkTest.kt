package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.e2e.Commissioned.HUMANOID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 건너뛴 대상 재작업의 통합 쪽(설계 `design-ops-rework-skipped` §3.6 E1). 두 기체를 시운전 완료로 만든 뒤 운영 서비스 REST 만으로
 * 첫 대상의 이동 중에 제어권 상실을 넣고 그 결함을 수용해 `PARTIAL` 로 종료시키고, 실행 목록의 재작업 대상에서 건너뛴 점검을 골라 `REWORK` 로 판단한다.
 *
 * 장애 주입은 [FaultIncidentTest] 와 같다. 운영 서비스 `POST /api/faults` 가 실행 호스트와 현장을 거쳐 mimic 엔진에 닿고, 현장은 엔진을
 * 부른 뒤 시계를 밀지 않으므로 다음 시계 진행에서 미들웨어가 실패를 본다. 장애 주입 종류에는 `navigate_to` 의 스킬 실패가 없어(스킬 실패는
 * `pick_place` 만이다) 이동을 실패시키는 것은 제어권 상실이다. 이 결함은 이동을 실패로 보내고 다음 로봇 단위를 막으므로 운영자가 수용해야
 * 실행이 이어지고, 그때 점검이 건너뛴 모양이 된다. 재작업한 이동은 새 태스크 이름(`<작업 지시>#<단위>@r1`)으로 나가며 수용한 결함은 더 막지 않는다.
 *
 * 결과 버전은 호스트의 결과 outbox 를 운영 서비스의 수집기가 가져와 쌓는다. 현장 시계를 밀지 않는 세 시점(`PARTIAL`, 재작업 직후 `RUNNING`, 종결)에 최근 저장 결과가
 * 그 물리 상태가 되기를 기다려 세 버전의 번호가 이 순서로 오르는지 본다. 상위가 `PARTIAL`·`FAILED` 결과 뒤에 `RUNNING` 결과를 받는 조합이다.
 */
class SkippedTargetReworkTest {

    companion object {
        private lateinit var stack: E2eStack
        private lateinit var driver: ExecutionDriver
        private val json = ObjectMapper()

        /** 수집기가 결과를 쌓기를 기다리는 상한(실제 시간). */
        private val RESULT_WAIT: Duration = Duration.ofSeconds(30)

        /** 시계를 밀지 않고 단위 상태를 기다리는 상한(실제 시간). pump 주기는 250ms 다. */
        private val UNIT_WAIT: Duration = Duration.ofSeconds(10)

        private val INSPECT_TWO = """{"workMasterId":"InspectAsset","targets":[{"id":"T1","location":"bay-7"},{"id":"T2","location":"dock-3"}]}"""

        @BeforeAll
        @JvmStatic
        fun up() {
            stack = E2eStack.start()
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
    fun `첫 대상의 이동 실패로 PARTIAL 이 된 실행의 건너뛴 점검을 실행 목록 대상에서 골라 재작업하면 판단이 이동 인시던트에 붙고 결과 버전이 RUNNING 을 거쳐 종결로 오른다`() {
        // 작업 요청으로 낸다. 접수증이 있어야 결과 버전을 읽을 수 있다. 필수 자원이 없으므로 곧바로 배정된다.
        val submitted = stack.send("POST", "/api/work-requests/manual", "operator", body = """{"requestId":"${UUID.randomUUID()}","form":$INSPECT_TWO}""")
        assertEquals(202, submitted.status, "${submitted.body}")
        val receipt = submitted.body!!["receipt"]
        val jobOrderId = receipt["jobOrderId"].asText()
        val receiptId = receipt["receiptId"].asText()
        val outcome = receipt["submission"]["outcome"]
        assertEquals("ACCEPTED" to HUMANOID, outcome["result"].asText() to outcome["robotId"].asText(), "${submitted.body}")
        val executionId = outcome["executionId"].asText()

        // 작업 지시 직후의 태스크는 ACCEPTED 라 한 번 밀어야 RUNNING 이다. 첫 이동(T1.travel)이 도는 중에 제어권 상실을 넣는다.
        driver.push(ExecutionDriver.STEP)
        assertEquals("RUNNING", unit(executionId, "T1.travel")["state"].asText(), "${driver.execution(executionId)}")
        val lost = stack.send("POST", "/api/faults", "engineer", body = """{"robotId":"$HUMANOID","kind":"CONTROL_AUTHORITY_LOST","reason":"제어권 상실 시연"}""")
        assertEquals(200, lost.status, "${lost.body}")
        assertEquals("SUCCEEDED" to true, lost.body!!["result"].asText() to lost.body["fault"]["raised"].asBoolean(), "${lost.body}")
        assertEquals(listOf("$jobOrderId#T1.travel"), lost.body["fault"]["terminated"].map { it.asText() }, "${lost.body}")

        // 이동이 실패로 끝나고 새 태스크를 못 받는 결함이 서 있어 다음 로봇 단위(T1 점검)의 시작 전에 실행이 막힌다.
        val held = pushUntil(executionId, "OPERATOR_HOLD")
        val blocked = held["blockedFaults"].map { mapOf("errorType" to it["errorType"].asText(), "references" to it["references"]) }
        assertEquals(listOf("CONTROL_AUTHORITY_LOST"), blocked.map { it["errorType"] }, "$held")
        val instanceId = stack.get("/api/executions?recent=20")["instanceId"].asText()
        val released = stack.send(
            "POST", "/api/executions/$executionId/release", "operator",
            body = json.writeValueAsString(linkedMapOf("instanceId" to instanceId, "reason" to "현장에서 기체 상태 확인", "sawFaults" to blocked)),
        )
        assertEquals(200, released.status, "${released.body}")
        assertEquals("RELEASED", released.body!!["outcome"].asText(), "${released.body}")

        // 이동 실패로 첫 대상의 점검은 태스크 없이 걸러지고 둘째 대상은 이어 돌아 PARTIAL 로 종료한다.
        val partial = driver.drive(executionId)
        assertEquals("PARTIAL", partial["physicalState"].asText(), "$partial")
        assertEquals(
            listOf("T1.travel" to "FAILED", "T1" to "FAILED", "T2.travel" to "DONE", "T2" to "DONE"),
            partial["units"].map { it["unitId"].asText() to it["state"].asText() },
            "$partial",
        )
        assertEquals("PREDECESSOR_NOT_DONE", partial["jobResponse"]["incompleteUnits"]["T1"].asText(), "$partial")
        val travelIncident = incidents()["incidents"].single { it["executionId"].asText() == executionId && it["unitId"].asText() == "T1.travel" }
        assertEquals(listOf(false, false), listOf(travelIncident["held"], travelIncident["unresolved"]).map { it.asBoolean() }, "$travelIncident")
        assertTrue(travelIncident["resolution"].isNull, "$travelIncident")
        val partialResult = awaitResult(receiptId, "PARTIAL")

        // 실행 호스트가 계산한 재작업 대상이 운영 서비스를 그대로 지나 화면 목록에 온다. 건너뛴 점검 하나뿐이고 PARTIAL 은 보존 만료가 없다.
        val page = stack.get("/api/executions?recent=20")
        val target = page["reworkableTargets"].single()
        assertEquals(
            listOf(executionId, jobOrderId, HUMANOID, "T1", "PARTIAL"),
            listOf("executionId", "jobOrderId", "robotId", "unitId", "physicalState").map { target[it].asText() },
            "$target",
        )
        assertTrue(target["retainedUntil"].isNull, "$target")
        // picasso 후보(§15.323) 칸도 그대로 지난다. 건너뛴 쌍은 SKIPPED 이고 묶음은 실패한 이동과 건너뛴 점검이다.
        assertEquals(
            listOf("0", "[\"T1.travel\",\"T1\"]", "SKIPPED", "T1.travel", "false"),
            listOf(target["attempt"].asText(), target["bundle"].toString(), target["kind"].asText(), target["incidentUnitId"].asText(), target["recheckFailed"].asText()),
            "$target",
        )

        // 운영자 모드로 건너뛴 점검 T1 을 재작업한다. 인스턴스는 목록의 것이다.
        val reason = "경로 막힘이 걷힌 것을 현장에서 확인"
        val reply = stack.send(
            "POST", "/api/executions/$executionId/units/T1/resolve", "operator",
            body = """{"decision":"REWORK","instanceId":"${page["instanceId"].asText()}","reason":"$reason"}""",
        )
        assertEquals(200, reply.status, "${reply.body}")
        val body = reply.body!!
        assertEquals(listOf("SUCCEEDED", "Resolved", "REWORK"), listOf(body["result"], body["outcome"], body["decision"]).map { it.asText() }, "$body")
        // 판단은 건너뛴 점검이 아니라 선행 이동의 인시던트에 붙고 응답이 그것을 가리킨다.
        assertEquals(travelIncident["incidentId"].asText(), body["answer"]["incidentId"].asText(), "$body")

        val logged = operations("RESOLVE_OPERATOR_HOLD").single()
        assertEquals(
            listOf("OPERATOR", "kim", "$executionId/T1", "SUCCEEDED", reason),
            listOf("mode", "user", "target", "result", "reason").map { logged[it].asText() },
            "$logged",
        )
        val request = json.readTree(logged["request"].asText())
        assertEquals("REWORK" to "kim", request["decision"].asText() to request["approverId"].asText(), "$logged")
        assertEquals("Resolved", json.readTree(logged["targetResponse"].asText())["body"]["result"].asText(), "$logged")

        val row = incidents()["incidents"].single { it["incidentId"].asText() == travelIncident["incidentId"].asText() }
        assertEquals(listOf("REWORK", "kim", "PERSON"), listOf(row["resolution"]["decision"], row["resolution"]["decidedBy"]["id"], row["resolution"]["decidedBy"]["kind"]).map { it.asText() }, "$row")
        assertEquals(listOf(false, false), listOf(row["held"], row["unresolved"]).map { it.asBoolean() }, "$row")

        // 두 단위가 새 시도로 돌아가고 실행은 곧바로 RUNNING 이다. 호스트 pump 는 시계를 밀지 않아도 실제 시간으로 돌아 되돌린 이동을
        // 다음 주기에 새 태스크로 내므로, 두 단위가 함께 PENDING 인 모양은 한 주기 동안만 보인다. 그래서 그 뒤의 모양을 기다린다.
        // 시도된 실패로 끝난 이동은 재작업 없이 다시 나가지 않으므로 이동의 RUNNING 이 되돌림을 보인다. 도는 실행은 더는 재작업 대상이 아니다.
        val running = awaitUnits(executionId, listOf("T1.travel" to "RUNNING", "T1" to "PENDING", "T2.travel" to "DONE", "T2" to "DONE"))
        assertEquals("RUNNING", running["physicalState"].asText(), "$running")
        assertTrue(stack.get("/api/executions?recent=20")["reworkableTargets"].isEmpty)
        val runningResult = awaitResult(receiptId, "RUNNING")

        val done = driver.drive(executionId)
        assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), "$done")
        assertEquals(listOf("DONE", "DONE", "DONE", "DONE"), done["units"].map { it["state"].asText() }, "$done")
        val doneResult = awaitResult(receiptId, "PHYSICALLY_DONE")

        // 결함은 기체에 그대로 서 있었다. 지운다.
        val regained = stack.send("POST", "/api/faults", "engineer", body = """{"robotId":"$HUMANOID","kind":"CONTROL_AUTHORITY_REGAINED","reason":"제어권 회복"}""")
        assertEquals("SUCCEEDED", regained.body!!["result"].asText(), "${regained.body}")

        // 결과 버전은 PARTIAL, RUNNING, 종결의 차례로 오른다.
        val versions = listOf(partialResult, runningResult, doneResult).map { it["version"].asInt() }
        assertTrue(versions.zipWithNext().all { (a, b) -> a < b }, "$versions")
        // 호스트의 송신 기록도 같은 차례다. 결함 차단과 수용 뒤의 RUNNING 다음에 PARTIAL, 재작업의 RUNNING, 종결이 이어진다(최근부터 적혀 있다).
        val sent = stack.get("/api/job-responses?jobOrderId=$jobOrderId")["responses"].map { it["physicalState"].asText() }.reversed()
        assertEquals(listOf("OPERATOR_HOLD", "RUNNING", "PARTIAL", "RUNNING", "PHYSICALLY_DONE"), sent, "$sent")
    }

    /**
     * 그 접수의 가장 최근 저장 결과가 물리 상태 [state] 가 되기를 실제 시간으로 기다린다. 결과는 호스트의 결과 outbox 를 운영 서비스의 수집기가
     * 주기로 가져와 쌓으므로 바로 보이지 않는다. 현장 시계를 밀지 않는 동안 호스트의 작업 응답은 그대로라 기다리는 값이 바뀌지 않는다.
     */
    private fun awaitResult(receiptId: String, state: String): JsonNode {
        val until = Instant.now().plus(RESULT_WAIT)
        var detail = stack.get("/api/work-requests/$receiptId")
        while (detail["latestResult"]?.takeUnless { it.isNull }?.get("snapshot")?.get("jobResponse")?.get("physicalState")?.asText() != state) {
            check(Instant.now().isBefore(until)) { "${RESULT_WAIT.seconds}초 안에 결과가 $state 가 되지 않았다: ${detail["latestResult"]}" }
            Thread.sleep(250)
            detail = stack.get("/api/work-requests/$receiptId")
        }
        return detail["latestResult"]
    }

    /** 실행의 물리 상태가 [state] 가 될 때까지 [ExecutionDriver.STEP] 씩 민다. */
    private fun pushUntil(executionId: String, state: String): JsonNode {
        repeat(8) {
            val seen = driver.execution(executionId)
            if (seen["physicalState"].asText() == state) return seen
            driver.push(ExecutionDriver.STEP)
        }
        error("실행이 $state 가 되지 않았다: ${driver.execution(executionId)}")
    }

    /** 시계를 밀지 않고 실제 시간으로 실행의 단위가 [expected] (단위 id, 상태) 가 되기를 기다린다. */
    private fun awaitUnits(executionId: String, expected: List<Pair<String, String>>): JsonNode {
        val clock = stack.site.now()
        val until = Instant.now().plus(UNIT_WAIT)
        var seen = driver.execution(executionId)
        while (seen["units"].map { it["unitId"].asText() to it["state"].asText() } != expected) {
            check(Instant.now().isBefore(until)) { "${UNIT_WAIT.seconds}초 안에 $expected 가 되지 않았다: $seen" }
            Thread.sleep(100)
            seen = driver.execution(executionId)
        }
        assertEquals(clock, stack.site.now(), "단위 상태를 기다리는 동안 시계를 밀지 않는다")
        return seen
    }

    private fun unit(executionId: String, unitId: String): JsonNode =
        driver.execution(executionId)["units"].single { it["unitId"].asText() == unitId }

    private fun incidents(): JsonNode = stack.get("/api/incidents")

    /** 조작 기록에서 [op] 의 행만 최신부터 낸다. */
    private fun operations(op: String): List<JsonNode> =
        stack.get("/api/operations").filter { json.readTree(it["request"].asText())["op"].asText() == op }
}
