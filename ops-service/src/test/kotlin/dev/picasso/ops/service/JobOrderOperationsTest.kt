package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.JobOrderBench.Companion.HUMANOID
import dev.picasso.ops.service.JobOrderBench.Companion.QUADRUPED
import dev.picasso.ops.service.JobOrderBench.Companion.missing
import dev.picasso.ops.service.JobOrderBench.Companion.pass
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostJudgmentRefusal
import dev.picasso.ops.service.host.HostReads
import dev.picasso.ops.service.host.HostSubmitResult
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.host.HostWrites
import dev.picasso.ops.service.joborders.InspectAssetDraft
import dev.picasso.ops.service.joborders.InspectionTarget
import dev.picasso.ops.service.joborders.JobOrderOperations
import dev.picasso.ops.service.joborders.JobOrderOutcome
import dev.picasso.ops.service.joborders.JobOrderSubmission
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 작업 지시 제출과 조작 기록(S3a 스펙 §8). 호스트 결과를 조작 기록 결과로 옮기고, 응답이 없으면 실행 목록을 다시 읽어 반영 여부를
 * 붙인다. 호스트와 registry 는 대역이다.
 */
class JobOrderOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = JobOrderBench()
    private val operations =
        JobOrderOperations(bench.eligibility, bench.host, bench.host, log, bench.clock, requeryDelay = Duration.ZERO)
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val draft = InspectAssetDraft(listOf(InspectionTarget("T1", "bay-7")))
    private val json = ObjectMapper()

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
    }


    @Test fun `일지가 없는 메모리 실행을 반영 완료로 확정하지 않는다`() {
        bench.answer = HostWrite.NoResponse("journal write failed")
        val jobId = "JO-blocked"
        val view = (bench.executionsWith(jobId) as HostCall.Ok).value.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        view.putArray("journalBlockedExecutionIds").add("exec-1")
        bench.executions = HostCall.Ok(view)
        val sent = assertIs<JobOrderSubmission.Submitted>(operations.submit(kim, draft, stableJobOrderId = jobId)).outcome
        assertEquals(OperationResult.NO_RESPONSE, sent.result)
        assertNull(sent.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    private fun submitted(): JobOrderOutcome = assertIs<JobOrderSubmission.Submitted>(operations.submit(kim, draft)).outcome

    private fun submittedId(): String = bench.submitted.last().first["jobOrderId"].asText()

    @Test
    fun `호스트 결과 넷을 조작 기록 결과로 옮긴다`() {
        val expected = mapOf(
            HostSubmitResult.ACCEPTED to OperationResult.SUCCEEDED,
            HostSubmitResult.IDEMPOTENT to OperationResult.SUCCEEDED,
            HostSubmitResult.REJECTED to OperationResult.REJECTED,
            HostSubmitResult.UNASSIGNED to OperationResult.REJECTED,
        )
        expected.forEach { (hostResult, logged) ->
            bench.answer = HostWrite.Answered(200, """{"result":"$hostResult","refusals":[],"excluded":[]}""")
            val outcome = submitted()
            assertEquals(logged, outcome.result, "$hostResult")
            assertNull(outcome.confirmation)
            assertEquals(hostResult, outcome.outcome!!.result)
            val row = log.list().first()
            assertEquals(outcome.requestId, row.requestId)
            assertEquals(logged, row.result, "$hostResult")
        }
        assertEquals(4, log.list().size)
    }

    @Test
    fun `제출은 배정 가능한 기체만 후보로 넘기고 작업 지시 id 를 대상으로 기록한다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { if (it == QUADRUPED) missing(it, "inspect") else pass(it) }) }
        bench.answer = HostWrite.Answered(
            200,
            """{"result":"ACCEPTED","executionId":"exec-1","robotId":"$HUMANOID","rejectionReason":null,"refusals":[],"excluded":[]}""",
        )
        val outcome = submitted()
        val (order, candidates) = bench.submitted.single()
        assertEquals(listOf(HUMANOID), candidates)
        assertEquals("exec-1", outcome.outcome!!.executionId)
        assertEquals(HUMANOID, outcome.outcome!!.robotId)
        // 판정과 제출이 같은 본문(새 작업 지시 id)을 쓴다.
        assertEquals(order, bench.judged.single().first)
        val row = log.list().single()
        assertEquals(order["jobOrderId"].asText(), row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals("kim", row.user)
        assertNull(row.reason)
        val request = json.readTree(row.request)
        assertEquals(JobOrderOperations.OP, request["op"].asText())
        assertEquals(order, request["jobOrder"])
        assertEquals(json.readTree("""["$HUMANOID"]"""), request["candidates"])
        val response = json.readTree(row.targetResponse)
        assertEquals(200, response["status"].asInt())
        assertEquals("exec-1", response["body"]["executionId"].asText())
    }

    @Test
    fun `작업 지시 id 는 JO 접두와 사이트 날짜와 요청 id 앞 8자리이고 응답에도 실린다`() {
        val outcome = submitted()
        assertEquals("JO-20261008-${outcome.requestId.toString().take(8)}", submittedId())
        assertEquals(submittedId(), outcome.jobOrderId)
        assertEquals("InspectAsset", bench.submitted.single().first["workMasterId"].asText())
    }

    @Test
    fun `배정 가능한 기체가 없으면 호스트를 부르지 않고 기록하지 않는다`() {
        bench.judge = { ids -> HostCall.Ok(ids.map { missing(it, "inspect") }) }
        val submission = assertIs<JobOrderSubmission.NoEligibleRobot>(operations.submit(kim, draft))
        assertEquals("$HUMANOID: 모자란 스킬: inspect / $QUADRUPED: 모자란 스킬: inspect", submission.detail)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트가 판정을 FLEET_PORT_ABSENT 로 거부하면 그 사유 그대로 답하고 제출하지 않고 기록하지 않는다`() {
        bench.judge = { HostCall.Silent("HTTP 400", refusal = HostJudgmentRefusal(JobOrderOperations.FLEET_PORT_ABSENT, "이 실행 호스트에는 플릿 포트가 없어 플릿 운반 단위가 든 작업 지시를 받지 않습니다: ContainerSupply 의 플릿 운반 단위 HU-1")) }
        val submission = assertIs<JobOrderSubmission.HostRefused>(operations.submit(kim, draft))
        assertEquals(JobOrderSubmission.HostRefused(JobOrderOperations.FLEET_PORT_ABSENT, "이 실행 호스트에는 플릿 포트가 없어 플릿 운반 단위가 든 작업 지시를 받지 않습니다: ContainerSupply 의 플릿 운반 단위 HU-1"), submission)
        assertEquals(emptyList(), bench.submitted)
        assertEquals(emptyList(), log.list())
    }

    @Test
    fun `호스트의 4xx 는 거부로 남기고 사유를 결과에 옮긴다`() {
        bench.answer = HostWrite.Answered(400, """{"error":"UNKNOWN_WORK_MASTER","detail":"받지 않는 WorkMaster 다"}""")
        val outcome = submitted()
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(HostSubmitResult.REJECTED, outcome.outcome!!.result)
        assertEquals("실행 호스트가 거부했다(HTTP 400): UNKNOWN_WORK_MASTER: 받지 않는 WorkMaster 다", outcome.outcome!!.rejectionReason)
        assertEquals(listOf(OperationResult.REJECTED), log.list().map { it.result })
    }

    @Test
    fun `호스트가 닿지 않으면 응답 없음을 남기고 다시 읽어 그 작업 지시의 실행이 있으면 반영됨을 붙인다`() {
        bench.answer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 재조회 대역은 제출이 만든 id 를 알아야 하므로 실행 목록을 제출 뒤에 고른다.
        val host = bench.host
        val asked = mutableListOf<Collection<String>>()
        val reads = object : HostReads by host {
            override fun executions() = error("재조회는 전체 목록을 읽지 않는다")
            override fun executionsOf(jobOrderIds: Collection<String>, executionIds: Collection<String>) =
                bench.executionsWith("JO-다른-것", submittedId()).also { asked += jobOrderIds; assertEquals(emptyList(), executionIds.toList()) }
        }
        val outcome = assertIs<JobOrderSubmission.Submitted>(
            JobOrderOperations(bench.eligibility, host, reads, log, bench.clock, Duration.ZERO).submit(kim, draft),
        ).outcome
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals(setOf(submittedId()), rows.map { it.target }.toSet())
        assertEquals("응답 없음: HttpTimeoutException", json.readTree(rows.last().targetResponse)["cause"].asText())
        val observed = json.readTree(rows.first().targetResponse)["observed"]
        assertEquals("exec-2", observed["executionId"].asText())
        assertEquals("mw-1", observed["instanceId"].asText())
        assertEquals(HUMANOID, observed["robotId"].asText())
        assertEquals(listOf(listOf(submittedId())), asked.map { it.toList() }, "재조회는 그 작업 지시로 좁혀 한 번 읽는다")
    }

    @Test
    fun `5xx 는 응답 없음과 같이 다루고 실행이 없으면 반영 안 됨을 붙인다`() {
        bench.answer = HostWrite.Answered(503, "")
        bench.executions = bench.executionsWith("JO-다른-것")
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertTrue(json.readTree(rows.first().targetResponse)["observed"].isNull)
        assertEquals(503, json.readTree(rows.last().targetResponse)["status"].asInt())
    }

    @Test
    fun `200 인데 본문을 못 읽으면 응답 없음으로 남기고 다시 읽는다`() {
        bench.answer = HostWrite.Answered(200, """{"result":"ASSIGNED"}""")
        bench.executions = bench.executionsWith()
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
    }

    @Test
    fun `재조회도 실패하면 확인 행을 붙이지 않고 모름으로 둔다`() {
        bench.answer = HostWrite.NoResponse("응답 없음: ConnectException")
        bench.executions = HostCall.Silent("응답 없음: ConnectException")
        val outcome = submitted()
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    @Test
    fun `재조회는 기본 값으로 쓰면 제출 뒤 1초 가까이 기다린 다음에 읽는다`() {
        var wrote = 0L
        var read = 0L
        val host = bench.host
        val writes = HostWrites { order, candidates ->
            host.submit(order, candidates)
            HostWrite.NoResponse("응답 없음").also { wrote = System.nanoTime() }
        }
        val reads = object : HostReads by host {
            override fun executionsOf(jobOrderIds: Collection<String>, executionIds: Collection<String>) =
                bench.executionsWith().also { read = System.nanoTime() }
        }
        JobOrderOperations(bench.eligibility, writes, reads, log, bench.clock).submit(kim, draft)
        assertTrue(read > wrote && Duration.ofNanos(read - wrote) >= Duration.ofMillis(900), "제출과 재조회 사이 ${(read - wrote) / 1_000_000} ms")
    }
}
