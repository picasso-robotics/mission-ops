package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostEligibility
import dev.picasso.ops.service.host.HostSkillFit
import dev.picasso.ops.service.host.HostWrite
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 실행 호스트 REST 클라이언트(S3a 스펙 §8). 호스트는 JDK HttpServer 대역이다. */
class HostClientTest {

    private var server: HttpServer? = null
    private val json = ObjectMapper()
    private val order = json.createObjectNode().put("jobOrderId", "JO-1").put("workMasterId", "InspectAsset")

    /** 경로마다 받은 요청(방법, Content-Type, 본문)을 남긴다. */
    private val seen = mutableMapOf<String, Triple<String, String?, String>>()

    /** 경로마다 받은 질의 문자열. */
    private val queries = mutableMapOf<String, String?>()

    private fun serve(vararg routes: Pair<String, Pair<Int, String>>): HostClient {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, answer) ->
            s.createContext(path) { exchange ->
                queries[path] = exchange.requestURI.rawQuery
                seen[path] = Triple(
                    exchange.requestMethod,
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = answer.second.toByteArray()
                exchange.sendResponseHeaders(answer.first, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        s.start()
        server = s
        return HostClient("http://127.0.0.1:${s.address.port}/")
    }

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `판정은 작업 지시 본문과 기체 id 를 JSON 으로 보내고 판정 행을 읽는다`() {
        val client = serve(
            "/host/eligibility" to (
                200 to """{"robots":[{"robotId":"quadruped-01","skillFit":"MISSING","missingSkills":["pick_place"],
                    "runningExecutionId":null,"passed":false,"reasons":["모자란 스킬: pick_place"],"later":1}]}"""
                ),
        )
        val row = assertIs<HostCall.Ok<List<HostEligibility>>>(
            client.eligibility(order, listOf("quadruped-01")),
        ).value.single()
        assertEquals(HostSkillFit.MISSING, row.skillFit)
        assertEquals(listOf("pick_place"), row.missingSkills)
        assertEquals(false, row.passed)
        val (method, contentType, body) = seen.getValue("/host/eligibility")
        assertEquals("POST", method)
        assertEquals("application/json", contentType)
        assertEquals(json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"robotIds":["quadruped-01"]}"""), json.readTree(body))
    }

    @Test
    fun `판정이 200 아님이나 모양 어긋남이면 모름이다`() {
        val client = serve("/host/eligibility" to (400 to """{"error":"BAD_REQUEST","detail":"x"}"""))
        assertEquals(HostCall.Silent("HTTP 400"), client.eligibility(order, listOf("r1")))
        stop()
        val odd = serve("/host/eligibility" to (200 to """{"robots":[{"robotId":"r1","skillFit":"MAYBE","passed":true}]}"""))
        assertIs<HostCall.Silent>(odd.eligibility(order, listOf("r1")))
    }

    @Test
    fun `제출은 후보를 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val client = serve("/host/job-orders" to (200 to """{"result":"UNASSIGNED"}"""))
        assertEquals(HostWrite.Answered(200, """{"result":"UNASSIGNED"}"""), client.submit(order, listOf("humanoid-01", "quadruped-01")))
        assertEquals(
            json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"candidates":["humanoid-01","quadruped-01"]}"""),
            json.readTree(seen.getValue("/host/job-orders").third),
        )
    }

    @Test
    fun `출발 자리 확인은 있을 때만 제출 본문에 싣고 출발 미관측 답은 결품 칸과 따로 읽는다`() {
        val client = serve("/host/job-orders" to (200 to "{}"))
        val confirmation = dev.picasso.ops.service.host.HostSourceConfirmation("SEQ-IN-02.BIN-A", "ENGINE-COVER-A", "operator/kim", "2026-10-14T00:00:00Z")
        client.submit(order, listOf("humanoid-01"), listOf(confirmation))
        assertEquals(
            json.readTree("""{"jobOrder":{"jobOrderId":"JO-1","workMasterId":"InspectAsset"},"candidates":["humanoid-01"],
              "sourceConfirmations":[{"source":"SEQ-IN-02.BIN-A","identity":"ENGINE-COVER-A","confirmedBy":"operator/kim","confirmedAt":"2026-10-14T00:00:00Z"}]}"""),
            json.readTree(seen.getValue("/host/job-orders").third),
        )
        client.submit(order, listOf("humanoid-01"), emptyList())
        assertFalse(json.readTree(seen.getValue("/host/job-orders").third).has("sourceConfirmations"))

        val outcome = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(
            """{"result":"UNASSIGNED","refusals":[{"robotId":"humanoid-01","reason":"r","sourceUnobserved":{"material":"ENGINE-COVER-A","source":"SEQ-IN-02.BIN-A","occupied":null}},
              {"robotId":"humanoid-02","reason":"r","sourceUnobserved":{"material":null,"source":"SEQ-IN-02.BIN-A","occupied":true}},{"robotId":"humanoid-03","reason":"r"}],
              "sourceUnobserved":null}""",
            dev.picasso.ops.service.host.HostSubmitOutcome::class.java,
        )
        assertEquals(dev.picasso.ops.service.host.HostSourceUnobserved("ENGINE-COVER-A", "SEQ-IN-02.BIN-A", null), outcome.refusals[0].sourceUnobserved)
        assertEquals(true, outcome.refusals[1].sourceUnobserved!!.occupied)
        assertEquals(null, outcome.refusals[0].sourceMissing)
        assertEquals(null, outcome.refusals[2].sourceUnobserved)
        assertEquals(null, outcome.sourceUnobserved)
    }

    @Test
    fun `놓은 작업 지시 거부는 그 칸을 읽고 칸이 없던 본문은 거짓이다`() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val type = dev.picasso.ops.service.host.HostSubmitOutcome::class.java
        val forgotten = mapper.readValue("""{"result":"REJECTED","rejectionReason":"이미 끝나 이빅션한 작업 지시라 다시 받지 않는다: JO-1","forgottenJobOrder":true}""", type)
        assertTrue(forgotten.forgottenJobOrder)
        assertFalse(mapper.readValue("""{"result":"REJECTED","rejectionReason":"r"}""", type).forgottenJobOrder)
    }

    @Test
    fun `닿지 않는 호스트는 판정과 조회가 모름이고 제출이 응답 없음이다`() {
        val client = serve()
        stop()
        server = null
        assertIs<HostCall.Silent>(client.eligibility(order, listOf("r1")))
        assertIs<HostCall.Silent>(client.executions())
        assertIs<HostCall.Silent>(client.cell())
        assertIs<HostWrite.NoResponse>(client.submit(order, listOf("r1")))
    }

    @Test
    fun `실행 목록과 셀은 객체 본문 그대로이고 200 아님과 객체 아님과 해석 불가는 모름이다`() {
        val client = serve(
            "/host/executions" to (200 to """{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""),
            "/host/cell" to (200 to """{"cell":null}"""),
        )
        assertEquals(json.readTree("""{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""), (client.executions() as HostCall.Ok).value)
        assertEquals(json.readTree("""{"cell":null}"""), (client.cell() as HostCall.Ok).value)
        stop()
        val broken = serve("/host/executions" to (200 to "[]"), "/host/cell" to (503 to ""))
        assertIs<HostCall.Silent>(broken.executions())
        assertEquals(HostCall.Silent("HTTP 503"), broken.cell())
        stop()
        assertIs<HostCall.Silent>(serve("/host/executions" to (200 to "not json")).executions())
    }

    @Test
    fun `좁힌 실행 목록은 작업 지시 id 와 실행 id 를 값마다 되풀이해 인코딩해 보내고 본문을 그대로 넘기며 id 없이는 보내지 않는다`() {
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val answer = """{"instanceId":"mw-1","pumpedAt":null,"executions":[]}"""
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/host/executions") { exchange ->
            queries += exchange.requestURI.rawQuery ?: ""
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        val client = HostClient("http://127.0.0.1:${s.address.port}/")
        assertEquals(json.readTree(answer), (client.executionsOf(jobOrderIds = listOf("JO-1", "JO 2,&3")) as HostCall.Ok).value)
        assertEquals(json.readTree(answer), (client.executionsOf(executionIds = listOf("exec-1")) as HostCall.Ok).value)
        client.executionsOf(listOf("JO-1"), listOf("exec-2"))
        assertEquals(listOf("jobOrderId=JO-1&jobOrderId=JO+2%2C%263", "executionId=exec-1", "jobOrderId=JO-1&executionId=exec-2"), queries)
        assertFailsWith<IllegalArgumentException> { client.executionsOf() }
        assertEquals(3, queries.size)
    }

    @Test
    fun `화면 실행 페이지와 실행 수는 인자를 실어 보내고 본문을 그대로 넘기며 200 아님은 모름이다`() {
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val page = """{"instanceId":"mw-1","pumpedAt":null,"executions":[],"restore":null,"journalBlockedExecutionIds":[],"settled":[],"nextBefore":null,"olderTotal":0}"""
        val older = """{"settled":[],"nextBefore":null,"olderTotal":0}"""
        val summary = """{"instanceId":"mw-1","pumpedAt":null,"live":0,"states":{},"journalBlockedExecutionIds":[]}"""
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/host/executions") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            queries += exchange.requestURI.rawPath + "?" + query
            val (status, body) = when {
                exchange.requestURI.rawPath == "/host/executions/summary" -> 200 to summary
                query.startsWith("before=400") -> 400 to """{"error":"BAD_REQUEST","detail":"x"}"""
                query.startsWith("before=") -> 200 to older
                else -> 200 to page
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        val client = HostClient("http://127.0.0.1:${s.address.port}/")
        assertEquals(json.readTree(page), assertIs<HostCall.Ok<*>>(client.executionsRecent(20)).value)
        assertEquals(json.readTree(older), assertIs<HostCall.Ok<*>>(client.executionsBefore(17, 5)).value)
        assertEquals(json.readTree(older), assertIs<HostCall.Ok<*>>(client.executionsBefore(9, null)).value)
        assertEquals(json.readTree(summary), assertIs<HostCall.Ok<*>>(client.executionSummary()).value)
        assertEquals(HostCall.Silent("HTTP 400"), client.executionsBefore(400, 1))
        // 취소 재조회는 그 실행 하나로 좁혀 읽는다.
        client.executionsOf("exec-3")
        assertEquals(
            listOf(
                "/host/executions?recent=20", "/host/executions?before=17&limit=5", "/host/executions?before=9", "/host/executions/summary?",
                "/host/executions?before=400&limit=1", "/host/executions?executionId=exec-3",
            ),
            queries,
        )
        stop()
        assertIs<HostCall.Silent>(client.executionsBefore(1, 1))
        assertIs<HostCall.Silent>(client.executionsRecent(1))
        assertIs<HostCall.Silent>(client.executionSummary())
    }

    @Test
    fun `결함 감수는 승인자와 사유와 인스턴스와 본 결함과 요청 id 를 실어 보내고 상태 코드와 본문을 그대로 돌려준다`() {
        val answer = """{"result":"RELEASED","executionId":"exec-1","physicalState":"RUNNING","detail":null,"requestId":"6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}"""
        val path = "/host/executions/exec-1/release"
        val client = serve(path to (200 to answer))
        val saw = json.readTree("""[{"errorType":"LOCALIZATION_LOST","references":[{"key":"KEY_TASK_ID","value":"t-1"}]}]""") as ArrayNode
        val requestId = UUID.fromString("6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a")
        assertEquals(HostWrite.Answered(200, answer), client.releaseFaults("exec-1", "kim", "현장 확인", "mw-1", saw, requestId))
        val (method, contentType, body) = seen.getValue(path)
        assertEquals(listOf("POST", "application/json"), listOf(method, contentType))
        assertEquals(
            json.readTree(
                """{"approverId":"kim","reason":"현장 확인","instanceId":"mw-1","sawFaults":[{"errorType":"LOCALIZATION_LOST",""" +
                    """"references":[{"key":"KEY_TASK_ID","value":"t-1"}]}],"requestId":"6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a"}""",
            ),
            json.readTree(body),
        )
    }

    @Test
    fun `종결은 승인자와 사유와 본 실행과 증언과 요청 id 를 실어 보내고 종결 조회는 404 NOT_CLOSED 만 없음으로 읽는다`() {
        val requestId = UUID.fromString("6f1c2a9e-0b7d-4c55-9a51-2f3e4d5c6b7a")
        val answer = """{"result":"CLOSED","jobOrderId":"JO-1","basis":"SNAPSHOT","responseId":"close-1","detail":null,"requestId":"$requestId"}"""
        val closure = """{"requestId":"$requestId","approverId":"kim","reason":"r","basis":"SNAPSHOT","attestedStopped":false,"closedInstanceId":"mw-0","closedExecutionId":"exec-7"}"""
        val client = serve(
            "/host/job-orders/JO-1/close" to (200 to answer),
            "/host/job-orders/JO-1/closure" to (200 to closure),
            "/host/job-orders/JO-2/closure" to (404 to """{"error":"NOT_CLOSED","detail":"x"}"""),
            "/host/job-orders/JO-3/closure" to (404 to """{"error":"OTHER","detail":"x"}"""),
            "/host/job-orders/JO-4/closure" to (500 to ""),
            "/host/job-orders/JO-5/closure" to (200 to "[]"),
            "/host/job-orders/JO-6/closure" to (409 to """{"error":"REQUEST_IN_PROGRESS","detail":"x"}"""),
        )
        assertEquals(HostWrite.Answered(200, answer), client.closeUnrestored("JO-1", "kim", "현장 확인", "mw-0", "exec-7", true, requestId))
        val (method, contentType, body) = seen.getValue("/host/job-orders/JO-1/close")
        assertEquals(listOf("POST", "application/json"), listOf(method, contentType))
        assertEquals(
            json.readTree(
                """{"approverId":"kim","reason":"현장 확인","executionInstanceId":"mw-0","executionId":"exec-7","attestedStopped":true,"requestId":"$requestId"}""",
            ),
            json.readTree(body),
        )
        assertEquals(json.readTree(closure), assertIs<HostCall.Ok<*>>(client.closure("JO-1", requestId)).value)
        assertEquals("GET", seen.getValue("/host/job-orders/JO-1/closure").first)
        // 재조회는 요청 id 를 실어 보낸다. 호스트가 그 요청을 처리 중이면 409 로 답한다.
        assertEquals("requestId=$requestId", queries.getValue("/host/job-orders/JO-1/closure"))
        // 종결하지 않았다는 호스트의 답(404 와 NOT_CLOSED)만 없음이다. 다른 404·500·모양 어긋남은 모름이다.
        assertEquals(HostCall.Ok(null), client.closure("JO-2", requestId))
        assertIs<HostCall.Silent>(client.closure("JO-3", requestId))
        assertIs<HostCall.Silent>(client.closure("JO-4", requestId))
        assertIs<HostCall.Silent>(client.closure("JO-5", requestId))
        // 409 REQUEST_IN_PROGRESS 는 «종결 없음» 이 아니라 모름이다.
        assertEquals(HostCall.Silent("호스트가 그 요청을 아직 처리 중이다"), client.closure("JO-6", requestId))
    }

    @Test
    fun `빈손 확인은 지목이 있을 때만 본문에 싣고 표시 조회는 404 RESIDUAL_HOLD_NOT_FOUND 만 없음으로 읽는다`() {
        val requestId = UUID.randomUUID()
        val record = """{"hold":{"markerId":7},"clearing":null}"""
        val client = serve(
            "/host/robots/humanoid-01/residual-holds/7/clear" to (200 to """{"hold":{},"clearing":{},"requestId":"$requestId"}"""),
            "/host/robots/humanoid-01/residual-holds/8/clear" to (409 to """{"error":"RESIDUAL_HOLD_OBSERVATION_MISMATCH","detail":"x"}"""),
            "/host/robots/humanoid-01/residual-holds/7" to (200 to record),
            "/host/robots/humanoid-01/residual-holds/9" to (404 to """{"error":"RESIDUAL_HOLD_NOT_FOUND","detail":"x"}"""),
            "/host/robots/humanoid-01/residual-holds/10" to (404 to """{"error":"OTHER","detail":"x"}"""),
            "/host/robots/humanoid-01/residual-holds/11" to (500 to ""),
        )
        assertEquals(200, assertIs<HostWrite.Answered>(client.clearResidualHold("humanoid-01", 7, "kim", "r", requestId)).status)
        assertEquals(
            json.readTree("""{"approverId":"kim","reason":"r","requestId":"$requestId"}"""),
            json.readTree(seen.getValue("/host/robots/humanoid-01/residual-holds/7/clear").third),
        )
        val cited = json.readTree("""{"conflictRequestId":"$requestId","kind":"HOLDING","objectRef":null,"session":"s","sequence":3}""")
        assertEquals(409, assertIs<HostWrite.Answered>(client.clearResidualHold("humanoid-01", 8, "kim", "r", requestId, cited)).status)
        assertEquals(cited, json.readTree(seen.getValue("/host/robots/humanoid-01/residual-holds/8/clear").third)["cited"])

        assertEquals(json.readTree(record), assertIs<HostCall.Ok<*>>(client.residualHold("humanoid-01", 7)).value)
        assertEquals("GET", seen.getValue("/host/robots/humanoid-01/residual-holds/7").first)
        assertEquals(HostCall.Ok(null), client.residualHold("humanoid-01", 9))
        assertIs<HostCall.Silent>(client.residualHold("humanoid-01", 10))
        assertIs<HostCall.Silent>(client.residualHold("humanoid-01", 11))
    }

    @Test
    fun `작업 허가는 GET host work-permits 본문 그대로이고 200 아님은 모름이다`() {
        val body = """{"declared":true,"robots":[{"robotId":"r1","permit":"INHIBITED","reasons":[]}]}"""
        val client = serve("/host/work-permits" to (200 to body))
        assertEquals(json.readTree(body), assertIs<HostCall.Ok<*>>(client.workPermits()).value)
        assertEquals("GET", seen.getValue("/host/work-permits").first)
        stop()
        assertEquals(HostCall.Silent("HTTP 503"), serve("/host/work-permits" to (503 to "")).workPermits())
    }

    @Test
    fun `현장 시간값 적용 상태는 GET host site-timings 본문 그대로이고 200 아님과 닿지 않음은 모름이다`() {
        val body = """{"applied":{"version":2,"evidenceBeforeSeconds":30},"readError":null}"""
        val client = serve("/host/site-timings" to (200 to body))
        assertEquals(json.readTree(body), assertIs<HostCall.Ok<*>>(client.siteTimings()).value)
        assertEquals("GET", seen.getValue("/host/site-timings").first)
        stop()
        assertEquals(HostCall.Silent("HTTP 404"), serve("/host/site-timings" to (404 to "")).siteTimings())
        stop()
        val gone = serve("/host/site-timings" to (200 to body))
        stop()
        assertIs<HostCall.Silent>(gone.siteTimings())
    }

    @Test
    fun `호스트 주소 형식이 틀리면 기동에서 멈춘다`() {
        assertFailsWith<IllegalArgumentException> { HostClient("127.0.0.1:8785") }
        assertEquals("http://127.0.0.1:8785", HostClient.checkBaseUrl("http://127.0.0.1:8785/"))
    }
}
