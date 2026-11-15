package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.MissionBench.Companion.UNKNOWN_SKILLS
import dev.picasso.ops.service.MissionBench.Companion.WM
import dev.picasso.ops.service.MissionBench.Companion.judgment
import dev.picasso.ops.service.MissionBench.Companion.refusal
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.finding.Owner
import dev.picasso.ops.service.host.HostClient
import dev.picasso.ops.service.host.HostMissions
import dev.picasso.ops.service.host.HostRequery
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.missions.HostActivationResult
import dev.picasso.ops.service.missions.HostMockRunResult
import dev.picasso.ops.service.missions.MissionOperationOutcome
import dev.picasso.ops.service.missions.MissionOperations
import dev.picasso.ops.service.missions.MissionSubmission
import dev.picasso.ops.service.missions.MissionValidation
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.registry.RegistryCall
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 조작과 조작 기록(S3b 스펙 §7). 호스트 결과를 조작 기록 결과로 옮기고, 거부를 거부 카드로 옮기고, 응답이 없으면 요청
 * id 로 다시 찾는다. 호스트와 registry 는 대역이다.
 */
class MissionOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = MissionBench()
    private val operations = MissionOperations(bench.base.robotList, bench.host, log, requeryDelay = Duration.ZERO)
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }

    private fun mockRun(): MissionOperationOutcome = assertIs<MissionSubmission.Submitted>(operations.mockRun(lee, WM, 3)).outcome

    private fun activate(reason: String = "랙 도착 대기 도입"): MissionOperationOutcome =
        assertIs<MissionSubmission.Submitted>(operations.activate(lee, WM, 3, reason)).outcome

    @Test
    fun `모의 실행과 활성화의 호스트 결과를 조작 기록 결과로 옮기고 결과 이름은 본문에 그대로 남는다`() {
        val mockRuns = mapOf(
            HostMockRunResult.PASSED to OperationResult.SUCCEEDED,
            HostMockRunResult.FAILED to OperationResult.SUCCEEDED,
            HostMockRunResult.REFUSED to OperationResult.REJECTED,
            HostMockRunResult.INPUT_UNKNOWN to OperationResult.REJECTED,
        )
        assertEquals(HostMockRunResult.entries.toSet(), mockRuns.keys)
        mockRuns.forEach { (hostResult, logged) ->
            bench.mockRunAnswer = HostWrite.Answered(200, judgment(hostResult.name))
            val outcome = mockRun()
            assertEquals(logged, outcome.result, "$hostResult")
            assertEquals(MissionOperations.mockRunResultOf(hostResult), outcome.result)
            assertNull(outcome.confirmation)
            assertEquals(hostResult.name, outcome.outcome!!["result"].asText())
            assertEquals(logged, log.list().first().result, "$hostResult")
        }
        val activations = mapOf(
            HostActivationResult.ACTIVATED to OperationResult.SUCCEEDED,
            HostActivationResult.REFUSED to OperationResult.REJECTED,
            HostActivationResult.FLEET_PORT_ABSENT to OperationResult.REJECTED,
            HostActivationResult.MOCK_RUN_REQUIRED to OperationResult.REJECTED,
            HostActivationResult.INPUT_UNKNOWN to OperationResult.REJECTED,
        )
        assertEquals(HostActivationResult.entries.toSet(), activations.keys)
        activations.forEach { (hostResult, logged) ->
            bench.activateAnswer = HostWrite.Answered(200, judgment(hostResult.name))
            val outcome = activate()
            assertEquals(logged, outcome.result, "$hostResult")
            assertEquals(MissionOperations.activationResultOf(hostResult), outcome.result)
            assertEquals(hostResult.name, outcome.outcome!!["result"].asText())
            assertEquals(logged, log.list().first().result, "$hostResult")
        }
        assertEquals(mockRuns.size + activations.size, log.list().size)
    }

    @Test
    fun `INPUT_UNKNOWN 은 거부로 기록하지만 거부 카드가 아니라 모름이며 무엇을 몰랐는지 본문에 남는다`() {
        bench.activateAnswer = HostWrite.Answered(200, judgment("INPUT_UNKNOWN", unknown = UNKNOWN_SKILLS))
        val outcome = activate()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(emptyList(), outcome.findings)
        assertEquals("INPUT_UNKNOWN", outcome.outcome!!["result"].asText())
        assertEquals("SITE_SKILLS", outcome.outcome!!["unknown"]["inputs"][0].asText())
        assertEquals(OperationResult.REJECTED, log.list().single().result)
    }

    @Test
    fun `거부는 거부 카드로 옮기고 통과에는 거부 카드가 없다`() {
        bench.activateAnswer = HostWrite.Answered(
            200,
            judgment("REFUSED", listOf(refusal(), refusal(kind = "FLOOR_UNOWNED", nodeId = "place", observed = "RACK-204", owner = "OUTSIDE_CONSOLE"))),
        )
        val refused = activate()
        assertEquals(listOf("SIGNAL_NOT_IN_SPEC", "FLOOR_UNOWNED"), refused.findings.map { it.kind })
        assertEquals("노드 rack-arrival: rack_ready", refused.findings[0].observed)
        assertEquals(listOf(Owner.ENGINEER, Owner.SITE), refused.findings.map { it.owner })
        assertEquals(Instant.parse("2026-10-08T00:00:01Z"), refused.findings[0].checkedAt)

        bench.mockRunAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal())))
        assertEquals(listOf("SIGNAL_NOT_IN_SPEC"), mockRun().findings.map { it.kind })
        bench.mockRunAnswer = HostWrite.Answered(200, judgment("PASSED"))
        assertEquals(emptyList(), mockRun().findings)
    }

    @Test
    fun `초안 저장은 정의와 사용자와 정규형 요청 id 를 호스트에 넘기고 WorkMaster 를 대상으로 기록한다`() {
        val outcome = operations.saveDraft(lee, WM, "{\"schemaVersion\": 1")
        val call = bench.writes().single()
        assertEquals("saveDraft", call.op)
        assertEquals("{\"schemaVersion\": 1", call.definition)
        assertEquals("lee", call.actor)
        assertEquals(outcome.requestId, call.requestId)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(call.requestId.toString()))
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals(WM, outcome.workMasterId)
        assertEquals(3, outcome.outcome!!["draft"]["draftId"].asInt())
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals(WM, row.target)
        assertEquals(Mode.ENGINEER, row.mode)
        assertEquals("lee", row.user)
        assertNull(row.reason)
        val request = json.readTree(row.request)
        assertEquals(MissionOperations.OP_SAVE_DRAFT, request["op"].asText())
        assertEquals("{\"schemaVersion\": 1", request["definition"].asText())
        assertEquals(200, json.readTree(row.targetResponse)["status"].asInt())
    }

    @Test
    fun `활성화는 사유를 호스트와 조작 기록에 넘기고 요청 id 는 조작마다 새로 만든다`() {
        val first = activate("랙 도착 대기 도입")
        val second = activate("다시")
        val calls = bench.writes()
        assertEquals(listOf("랙 도착 대기 도입", "다시"), calls.map { it.reason })
        assertEquals(listOf(first.requestId, second.requestId), calls.map { it.requestId })
        assertTrue(first.requestId != second.requestId)
        val rows = log.list().sortedBy { it.recordedAt }
        assertEquals(listOf("랙 도착 대기 도입", "다시"), rows.map { it.reason })
        val request = json.readTree(rows.first().request)
        assertEquals(MissionOperations.OP_ACTIVATE, request["op"].asText())
        assertEquals(3, request["draftId"].asInt())
        assertEquals(json.readTree("""["$HUMANOID","$QUADRUPED"]"""), request["robotIds"])
    }

    @Test
    fun `검증과 모의 실행과 활성화는 시운전 완료 기체만 붙이고 하나도 없으면 빈 목록이다`() {
        bench.base.robots = RegistryCall.Ok(
            listOf(JobOrderBench.robot(HUMANOID), JobOrderBench.robot(QUADRUPED), JobOrderBench.robot("old-01", status = "RETIRED")),
        )
        bench.base.bindings = RegistryCall.Ok(
            listOf(JobOrderBench.binding(HUMANOID), JobOrderBench.binding(QUADRUPED, siteNames = "UNREGISTERED")),
        )
        operations.validate(WM, 3)
        mockRun()
        activate()
        assertEquals(listOf(listOf(HUMANOID), listOf(HUMANOID), listOf(HUMANOID)), bench.writes().map { it.robotIds })

        bench.calls.clear()
        bench.base.bindings = RegistryCall.Ok(emptyList())
        mockRun()
        assertEquals(listOf(emptyList<String>()), bench.writes().map { it.robotIds })
    }

    @Test
    fun `registry 가 답하지 않으면 시운전 완료 기체를 모르는 것이라 호스트를 부르지 않고 기록하지 않는다`() {
        // 한 번 읽어 직전 목록을 남긴 뒤 registry 를 끊는다. 직전 목록으로 대신하지 않아야 한다.
        bench.base.robotList.read()
        bench.base.robots = RegistryCall.Silent("응답 없음: ConnectException")
        // 모르는 원인이 사전 거부 detail 에 실린다(간헐 503 을 사후에 가를 수 있게).
        val detail = "registry 가 답하지 않아 시운전 완료 기체를 모른다: 응답 없음: ConnectException"
        assertEquals(MissionValidation.RobotsUnknown(detail), operations.validate(WM, 3))
        assertEquals(MissionSubmission.RobotsUnknown(detail), operations.mockRun(lee, WM, 3))
        assertEquals(MissionSubmission.RobotsUnknown(detail), operations.activate(lee, WM, 3, "사유"))

        bench.base.robots = RegistryCall.Unauthorized
        assertEquals(
            MissionSubmission.RobotsUnknown("운영자 토큰이 registry 와 맞지 않아 시운전 완료 기체를 모른다"),
            operations.mockRun(lee, WM, 3),
        )
        assertEquals(emptyList(), bench.writes())
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트가 닿지 않으면 응답 없음을 남기고 다시 보내지 않고 같은 요청 id 로 찾아 그 조작의 행이 있으면 반영됨이다`() {
        bench.mockRunAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.requery = { id ->
            HostRequery.Found(json.readTree("""{"requestId":"$id","draft":null,"mockRun":{"mockRunId":5,"passed":true},"version":null}"""))
        }
        val outcome = mockRun()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(1, bench.writes().size)
        assertEquals(listOf(outcome.requestId), bench.requeried)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals(setOf(WM), rows.map { it.target }.toSet())
        assertEquals("응답 없음: HttpTimeoutException", json.readTree(rows.last().targetResponse)["cause"].asText())
        assertEquals(5, json.readTree(rows.first().targetResponse)["observed"]["mockRun"]["mockRunId"].asInt())
    }

    @Test
    fun `재조회에서 남은 행이 없거나 다른 조작의 행만 있으면 반영 안 됨이다`() {
        bench.activateAnswer = HostWrite.Answered(503, "")
        bench.requery = { HostRequery.NotFound }
        val missing = activate()
        assertEquals(OperationResult.NO_RESPONSE, missing.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, missing.confirmation)
        assertTrue(json.readTree(log.list().first().targetResponse)["observed"].isNull)

        bench.requery = { id ->
            HostRequery.Found(json.readTree("""{"requestId":"$id","draft":{"draftId":3},"mockRun":null,"version":null}"""))
        }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, activate().confirmation)

        bench.saveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        assertEquals(OperationResult.CONFIRMED_APPLIED, operations.saveDraft(lee, WM, "{}").confirmation)
    }

    @Test
    fun `재조회도 못 읽으면 확인 행을 붙이지 않고 모름으로 둔다`() {
        bench.saveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        val outcome = operations.saveDraft(lee, WM, "{}")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    /**
     * 활성화가 호스트 잠금을 기다리는 사이에 운영 서비스가 먼저 끊고 재조회하는 경합이다. 호스트는 실제 클라이언트로 부르고
     * JDK HttpServer 가 대신한다. 활성화는 요청 제한보다 늦게 응답하고, 재조회에는 처리 중(409)으로 응답한다.
     */
    @Test
    fun `재조회에서 호스트가 그 요청을 아직 처리 중이면 확인하지 못한 것이라 확인 행을 붙이지 않는다`() {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requeried = CopyOnWriteArrayList<String>()
        server.createContext("/host/missions/drafts/") { exchange ->
            exchange.requestBody.readAllBytes()
            Thread.sleep(1000)
            runCatching { exchange.sendResponseHeaders(503, -1) }
            exchange.close()
        }
        server.createContext("/host/missions/requests/") { exchange ->
            requeried += exchange.requestURI.rawPath
            val body = """{"error":"REQUEST_IN_PROGRESS","detail":"그 요청 id 의 조작을 아직 처리 중이다"}""".toByteArray()
            exchange.sendResponseHeaders(409, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.executor = pool
        server.start()
        try {
            HostClient("http://127.0.0.1:${server.address.port}", requestTimeout = Duration.ofMillis(300)).use { client ->
                val raced = MissionOperations(bench.base.robotList, client, log, requeryDelay = Duration.ZERO)
                val outcome = assertIs<MissionSubmission.Submitted>(raced.activate(lee, WM, 3, "랙 도착 대기 도입")).outcome
                assertEquals(OperationResult.NO_RESPONSE, outcome.result)
                assertNull(outcome.confirmation)
                assertEquals(listOf("/host/missions/requests/${outcome.requestId}"), requeried.toList())
                assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
            }
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun `200 인데 결과 이름을 모르거나 거부를 읽지 못하면 응답 없음으로 남기고 다시 찾는다`() {
        bench.requery = { HostRequery.NotFound }
        bench.activateAnswer = HostWrite.Answered(200, judgment("PASSED"))
        assertEquals(OperationResult.NO_RESPONSE, activate().result)
        bench.mockRunAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal(owner = "OPERATOR"))))
        val unreadable = mockRun()
        assertEquals(OperationResult.NO_RESPONSE, unreadable.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, unreadable.confirmation)
        assertEquals(emptyList(), unreadable.findings)
        bench.saveAnswer = HostWrite.Answered(200, """{"draft":null}""")
        assertEquals(OperationResult.NO_RESPONSE, operations.saveDraft(lee, WM, "{}").result)
    }

    @Test
    fun `호스트의 4xx 는 거부로 남기고 오류 이름을 넘긴다`() {
        bench.activateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"초안 9 가 없다"}""")
        val outcome = activate()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertNull(outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(HostRejection(404, "DRAFT_NOT_FOUND", "초안 9 가 없다"), outcome.rejection)
        assertEquals(emptyList(), bench.requeried)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `검증은 기록하지 않고 호스트 본문과 거부 카드를 내며 4xx 와 불통을 가른다`() {
        val passed = assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply
        assertEquals("PASSED", passed.outcome["result"].asText())
        assertEquals(emptyList(), passed.findings)
        assertEquals(3L, bench.writes().single().draftId)

        bench.validateAnswer = HostWrite.Answered(200, judgment("REFUSED", listOf(refusal(kind = "UNREADABLE", nodeId = null, observed = "$.steps[0].skill: 빠졌다"))))
        val refused = assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply
        assertEquals("$.steps[0].skill: 빠졌다", refused.findings.single().observed)

        bench.validateAnswer = HostWrite.Answered(200, judgment("INPUT_UNKNOWN", unknown = UNKNOWN_SKILLS))
        assertEquals(emptyList(), assertIs<MissionValidation.Answered>(operations.validate(WM, 3)).reply.findings)

        bench.validateAnswer = HostWrite.Answered(404, """{"error":"DRAFT_NOT_FOUND","detail":"없다"}""")
        assertEquals(MissionValidation.HostRejected(HostRejection(404, "DRAFT_NOT_FOUND", "없다")), operations.validate(WM, 3))
        bench.validateAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        assertEquals(MissionValidation.HostSilent("응답 없음: ConnectException"), operations.validate(WM, 3))
        bench.validateAnswer = HostWrite.Answered(200, judgment("ACTIVATED"))
        assertIs<MissionValidation.HostSilent>(operations.validate(WM, 3))
        assertEquals(emptyList(), log.list())
        assertEquals(emptyList(), bench.requeried)
    }

    @Test
    fun `재조회는 기본 값으로 쓰면 응답 없음 뒤 1초 가까이 기다린 다음에 찾는다`() {
        var wrote = 0L
        var read = 0L
        bench.mockRunAnswer = HostWrite.NoResponse("응답 없음")
        bench.requery = { HostRequery.NotFound.also { read = System.nanoTime() } }
        val timed = object : HostMissions by bench.host {
            override fun mockRun(draftId: Long, robotIds: List<String>, requestId: UUID) =
                bench.host.mockRun(draftId, robotIds, requestId).also { wrote = System.nanoTime() }
        }
        MissionOperations(bench.base.robotList, timed, log).mockRun(lee, WM, 3)
        assertTrue(read > wrote && Duration.ofNanos(read - wrote) >= Duration.ofMillis(900), "응답 없음과 재조회 사이 ${(read - wrote) / 1_000_000} ms")
    }
}
