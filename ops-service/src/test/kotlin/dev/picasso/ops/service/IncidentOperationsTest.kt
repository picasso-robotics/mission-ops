package dev.picasso.ops.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.service.IncidentBench.Companion.resolution
import dev.picasso.ops.service.IncidentBench.Companion.row
import dev.picasso.ops.service.actor.Actor
import dev.picasso.ops.service.actor.Mode
import dev.picasso.ops.service.host.HostCall
import dev.picasso.ops.service.host.HostWrite
import dev.picasso.ops.service.incidents.FaultOperations
import dev.picasso.ops.service.incidents.HoldResolutions
import dev.picasso.ops.service.log.OperationLog
import dev.picasso.ops.service.log.OperationResult
import dev.picasso.ops.service.operations.HostRejection
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 장애 주입과 운영자 판단의 조작 기록(S4a 스펙 §7, T7). 결과 매김, 거부의 원래 이름, 응답 없음 뒤 판단 재조회의 대조를 본다.
 * 호스트는 대역이다.
 */
class IncidentOperationsTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val log = OperationLog(JdbcClient.create(dataSource))
    private val bench = IncidentBench()
    private val clock = MovableClock(T0)
    private val faults = FaultOperations(bench.host, log)
    private val resolutions = HoldResolutions(bench.host, log, requeryDelay = Duration.ZERO, clock = clock)
    private val lee = Actor(Mode.ENGINEER, "lee")
    private val kim = Actor(Mode.OPERATOR, "kim")
    private val json = ObjectMapper()

    /** 시험이 옮기는 시계. 호스트가 판단을 받는 순간 앞으로 옮겨 «요청 직전» 과 «요청 뒤» 를 가른다. */
    class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    @BeforeTest
    fun freshSchema() {
        OpsSchema.flyway(dataSource, cleanable = true).apply {
            clean()
            migrate()
        }
        // 호스트는 요청을 받은 뒤 판단하므로 판단의 실제 시각은 요청 직전 시각보다 늦다.
        bench.onResolve = { clock.now = T0.plusSeconds(10) }
    }

    @Test
    fun `받아들인 장애 주입은 성공이고 기체를 대상으로 종류와 값과 사유를 기록한다`() {
        val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "스킬 실패 시연")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        assertEquals("RETRIABLE", outcome.fault!!["taskState"].asText())
        assertEquals(json.readTree("""{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}"""), bench.writes().single().body)
        val row = log.list().single()
        assertEquals(outcome.requestId, row.requestId)
        assertEquals("humanoid-01", row.target)
        assertEquals(Mode.ENGINEER, row.mode)
        assertEquals("lee", row.user)
        assertEquals("스킬 실패 시연", row.reason)
        assertEquals(
            json.readTree("""{"op":"${FaultOperations.OP}","robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED","state":null}"""),
            json.readTree(row.request),
        )
        assertEquals("JO-1#RACK-204.S01", json.readTree(row.targetResponse)["body"]["taskId"].asText())

        bench.faultAnswer = HostWrite.Answered(200, """{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE","changed":true}""")
        assertEquals(OperationResult.SUCCEEDED, faults.inject(lee, "quadruped-01", "CONNECTION", "OFFLINE", "묵은 값 시연").result)
        assertEquals(json.readTree("""{"robotId":"quadruped-01","kind":"CONNECTION","state":"OFFLINE"}"""), bench.writes().last().body)
    }

    @Test
    fun `현장의 거부는 거부로 남기고 원래 오류 이름을 응답 칸과 응답에 그대로 넘긴다`() {
        val refusals = listOf(
            HostRejection(409, "NO_RUNNING_TASK", "humanoid-01 에 진행 중인 pick_place 태스크가 없다"),
            HostRejection(404, "UNKNOWN_ROBOT", "이 현장에 없는 기체다: ghost-01"),
            HostRejection(400, "UNSUPPORTED_FAULT", "받지 않는 장애다: PAYLOAD_LOST"),
        )
        refusals.forEach { refusal ->
            bench.faultAnswer = HostWrite.Answered(refusal.status, """{"error":"${refusal.error}","detail":"${refusal.detail}"}""")
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "거부 시연")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertNull(outcome.fault)
            assertEquals(refusal, outcome.rejection)
        }
        val rows = log.list()
        assertEquals(List(3) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(
            refusals.map { it.error }.reversed(),
            rows.map { json.readTree(it.targetResponse)["body"]["error"].asText() },
        )
    }

    @Test
    fun `현장이나 호스트가 안 닿으면 장애 주입은 응답 없음 하나만 남기고 재조회하지 않는다`() {
        listOf(
            HostWrite.Answered(503, """{"error":"CELL_SILENT","detail":"현장 셀 대역이 답하지 않는다"}"""),
            HostWrite.NoResponse("응답 없음: HttpTimeoutException"),
        ).forEach { answer ->
            bench.faultAnswer = answer
            val outcome = faults.inject(lee, "humanoid-01", "SKILL_EXECUTION_FAILED", null, "불통 시연")
            assertEquals(OperationResult.NO_RESPONSE, outcome.result)
            assertNull(outcome.confirmation)
            assertNull(outcome.rejection)
        }
        assertEquals(List(2) { OperationResult.NO_RESPONSE }, log.list().map { it.result })
        assertEquals(listOf("injectFault", "injectFault"), bench.calls.map { it.op })
        assertEquals("CELL_SILENT", json.readTree(log.list().last().targetResponse)["body"]["error"].asText())
    }

    @Test
    fun `판단 Resolved 는 성공이고 승인자와 결정과 실행과 단위와 사유와 결과 이름을 기록한다`() {
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "랙 재배치 뒤 재작업")
        assertEquals(OperationResult.SUCCEEDED, outcome.result)
        assertEquals("Resolved", outcome.outcome)
        assertNull(outcome.confirmation)
        assertNull(outcome.rejection)
        val call = bench.writes().single()
        assertEquals(
            IncidentBench.Call(
                "resolve", executionId = "exec-1", unitId = "rack-arrival", decision = "REWORK", approverId = "kim",
                requestId = outcome.requestId, instanceId = "i-1",
            ),
            call,
        )
        val row = log.list().single()
        assertEquals("exec-1/rack-arrival", row.target)
        assertEquals(Mode.OPERATOR, row.mode)
        assertEquals("랙 재배치 뒤 재작업", row.reason)
        assertEquals(
            json.readTree(
                """{"op":"${HoldResolutions.OP}","executionId":"exec-1","unitId":"rack-arrival","decision":"REWORK","approverId":"kim","instanceId":"i-1"}""",
            ),
            json.readTree(row.request),
        )
        assertEquals("Resolved", json.readTree(row.targetResponse)["body"]["result"].asText())
        assertEquals("incident-1", json.readTree(row.targetResponse)["body"]["incidentId"].asText())
    }

    @Test
    fun `판단 NotHeld 와 Refused 는 거부로 남기고 응답 칸에 원래 결과 이름이 있다`() {
        listOf("NotHeld" to null, "Refused" to "에이전트는 운영자 판단을 내지 못한다").forEach { (name, detail) ->
            bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, name, detail))
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "CONFIRM_DONE", "i-1", "확인")
            assertEquals(OperationResult.REJECTED, outcome.result)
            assertEquals(name, outcome.outcome)
            assertNull(outcome.rejection)
            assertNull(outcome.confirmation)
        }
        val rows = log.list()
        assertEquals(List(2) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(listOf("Refused", "NotHeld"), rows.map { json.readTree(it.targetResponse)["body"]["result"].asText() })
    }

    @Test
    fun `판단 응답이 없으면 같은 실행과 단위의 인시던트 하나라도 행위자의 같은 결정이 요청 뒤에 붙었으면 반영됨이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 반영된 재작업 뒤 대기가 기한을 다시 넘겨 새 보류가 가장 최근이다. 앞 인시던트의 판단으로 반영을 읽어야 한다.
        bench.incidents = bench.listed(
            row("incident-2"),
            row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        assertNull(outcome.outcome)
        assertEquals(500, bench.calls.single { it.op == "incidents" }.limit)
        val rows = log.list()
        assertEquals(listOf(OperationResult.CONFIRMED_APPLIED, OperationResult.NO_RESPONSE), rows.map { it.result })
        assertEquals(setOf(outcome.requestId), rows.map { it.requestId }.toSet())
        assertEquals("incident-1", json.readTree(rows.first().targetResponse)["observed"]["incidentId"].asText())
    }

    @Test
    fun `같은 사람이 요청 앞에 낸 판단이나 다른 사람 다른 결정 다른 단위 다른 실행의 판단은 반영 안 됨이다`() {
        bench.resolveAnswer = HostWrite.Answered(503, "<html>bad gateway</html>")
        // 판단 기록은 읽히고 그 단위에 새 판단이 없다. 인시던트가 맞지 않으면 판단 기록으로 반영 안 됨을 본다.
        bench.units = { IncidentBench.unitBody(0, unitId = "rack-arrival") }
        val misses = listOf(
            // 같은 사람, 같은 결정, 같은 단위지만 요청 직전 시각보다 앞선 판단.
            listOf(row("incident-1", resolution = resolution("REWORK", "kim", T0.minusMillis(1)))),
            listOf(row("incident-1", resolution = resolution("REWORK", "park", T0.plusSeconds(1)))),
            listOf(row("incident-1", resolution = resolution("CONFIRM_DONE", "kim", T0.plusSeconds(1)))),
            // 이 단위에는 판단 없는 인시던트가 있고 같은 실행의 다른 단위에 판단이 있다. 단위에 인시던트가 있으면 넓히지 않는다.
            listOf(row("incident-0"), row("incident-1", unitId = "RACK-204.S01", resolution = resolution("REWORK", "kim", T0.plusSeconds(1)))),
            listOf(row("incident-0"), row("incident-1", executionId = "exec-2", resolution = resolution("REWORK", "kim", T0.plusSeconds(1)))),
        )
        misses.forEach { miss ->
            clock.now = T0
            bench.incidents = bench.listed(*miss.toTypedArray())
            val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
            assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation, miss.joinToString())
        }
        // 요청 직전 시각과 같은 판단 시각은 반영이다(경계).
        clock.now = T0
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0)))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `대조 시각은 요청을 보내기 직전의 시각이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 호스트가 받은 뒤(T0+10초)가 아니라 보내기 직전(T0)과 비교해야 T0+1초의 판단이 반영으로 읽힌다.
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `판단 재조회가 목록을 못 읽으면 확인 행이 없고 모르는 결과 이름은 응답 없음이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: ConnectException")
        bench.incidents = HostCall.Silent("응답 없음: ConnectException")
        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)

        bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, "RESOLVED"))
        bench.incidents = bench.listed(row("incident-1"))
        clock.now = T0
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertEquals(
            listOf(OperationResult.CONFIRMED_NOT_APPLIED, OperationResult.NO_RESPONSE, OperationResult.NO_RESPONSE),
            log.list().map { it.result },
        )
    }

    @Test
    fun `이전 인스턴스를 실은 판단의 호스트 409 INSTANCE_MISMATCH 는 거부이고 원래 이름이 응답 칸과 응답에 있다`() {
        bench.resolveAnswer = HostWrite.Answered(
            409, """{"error":"INSTANCE_MISMATCH","detail":"판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"}""",
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-0", "재작업")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals(HostRejection(409, "INSTANCE_MISMATCH", "판단 요청의 인스턴스(i-0)가 지금 인스턴스(i-1)가 아니다"), outcome.rejection)
        assertNull(outcome.outcome)
        assertNull(outcome.confirmation)
        assertEquals("i-0", bench.writes().single().instanceId)
        val row = log.list().single()
        assertEquals(OperationResult.REJECTED, row.result)
        assertEquals("INSTANCE_MISMATCH", json.readTree(row.targetResponse)["body"]["error"].asText())
        assertEquals(409, json.readTree(row.targetResponse)["status"].asInt())
        assertEquals("i-0", json.readTree(row.request)["instanceId"].asText())
        // 재조회하지 않는다.
        assertEquals(emptyList(), bench.calls.filter { it.op == "incidents" })
    }

    @Test
    fun `판단 재조회는 목록의 인스턴스가 요청한 인스턴스와 다르면 incidents 가 아니라 earlier 의 그 인스턴스 사본만 본다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        val applied = resolution("REWORK", "kim", T0.plusSeconds(1))
        // 재기동 뒤 새 인스턴스의 exec-1 은 다른 실행이다. 같은 실행 id·단위·판단자·결정이어도 반영으로 읽지 않는다.
        // 요청한 인스턴스의 그 단위 사본이 하나도 없으면 대응하는 판단을 못 찾은 것이라 확인하지 못함(널)이다.
        bench.incidents = bench.listedAt("i-2", listOf(row("incident-1", resolution = applied)))
        val newInstance = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertNull(newInstance.confirmation)

        // 이전 인스턴스 사본 가운데 요청한 인스턴스(i-1)의 것만 본다. 다른 이전 인스턴스(i-0)의 판단은 반영이 아니다.
        clock.now = T0
        bench.incidents = bench.listedAt("i-2", emptyList(), listOf(IncidentBench.copy("i-1", "incident-2"), IncidentBench.copy("i-0", "incident-1", applied)))
        assertEquals(
            OperationResult.CONFIRMED_NOT_APPLIED,
            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
        )

        clock.now = T0
        bench.incidents = bench.listedAt(
            "i-2",
            listOf(row("incident-1")),
            listOf(IncidentBench.copy("i-1", "incident-2"), IncidentBench.copy("i-1", "incident-1", applied)),
        )
        val earlier = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_APPLIED, earlier.confirmation)
        val observed = json.readTree(log.list().first().targetResponse)["observed"]
        assertEquals("i-1", observed["instanceId"].asText())
        assertEquals("incident-1", observed["incidentId"].asText())

        // 같은 인스턴스면 지금 목록을 본다(앞 시험들과 같다).
        clock.now = T0
        bench.incidents = bench.listedAt("i-1", listOf(row("incident-1", resolution = applied)))
        assertEquals(
            OperationResult.CONFIRMED_APPLIED,
            resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation,
        )
    }

    @Test
    fun `판단 재조회의 반영 안 됨 관측은 요청한 인스턴스의 그 단위 가장 최근 사본이다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.incidents = bench.listedAt(
            "i-2",
            listOf(row("incident-9")),
            listOf(IncidentBench.copy("i-1", "incident-3"), IncidentBench.copy("i-1", "incident-2", executionId = "exec-2")),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        val observed = json.readTree(log.list().first().targetResponse)["observed"]
        assertEquals("incident-3", observed["incidentId"].asText())
        assertEquals("i-1", observed["instanceId"].asText())
    }

    @Test
    fun `건너뛴 대상 재조회는 같은 실행의 다른 단위 인시던트로 넓히지 않고 판단 기록이 없으면 확인하지 못한다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        val applied = resolution("REWORK", "kim", T0.plusSeconds(1))
        // 건너뛴 점검(T1)은 인시던트를 열지 않았고 판단은 선행 이동(T1.travel)의 인시던트에 붙는다. 그 판단으로 반영을 추정하지 않는다.
        bench.incidents = bench.listed(row("incident-1", unitId = "T1.travel", resolution = applied))
        val outcome = resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.NO_RESPONSE, outcome.result)
        assertNull(outcome.confirmation)
        // 보내기 직전에 떠 두기를 했고(모름), 그래서 재조회는 판단 기록을 다시 읽지 않는다.
        assertEquals(1, bench.calls.count { it.op == "executionUnits" })
        assertEquals(listOf(OperationResult.NO_RESPONSE), log.list().map { it.result })
    }

    @Test
    fun `판단 기록을 못 읽으면 그 단위에 판단 없는 인시던트가 있을 때만 반영 안 됨이고 판단이 붙은 인시던트뿐이면 확인하지 못한다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        // 운영 서비스 시계가 호스트보다 앞서 판단의 실제 시각이 요청 직전 시각보다 이르다. 반영됐을 수 있으므로 반영 안 됨으로 단정하지 않는다.
        bench.incidents = bench.listed(row("incident-1", resolution = resolution("REWORK", "kim", T0.minusSeconds(1))))
        assertNull(resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)

        // 건너뛴 점검(T1)은 인시던트가 없고 판단은 이동 인시던트에 붙었다. 판단 기록도 못 읽으면 확인하지 못한다.
        clock.now = T0
        bench.incidents = bench.listed(row("incident-1", unitId = "T1.travel", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
        assertNull(resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업").confirmation)

        // 그 단위에 아직 판단 없는 인시던트가 있으면 판단이 서지 않은 것이다.
        clock.now = T0
        bench.incidents = bench.listed(row("incident-2"), row("incident-1", resolution = resolution("REWORK", "kim", T0.minusSeconds(1))))
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업").confirmation)
        assertEquals("incident-2", json.readTree(log.list().first().targetResponse)["observed"]["incidentId"].asText())
    }

    @Test
    fun `떠 둔 판단과 같은 시도의 새 판단은 같은 틱이어도 반영이고 칸이 모두 같거나 시각이 앞서면 반영이 아니다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.incidents = bench.listed()
        // DONE 이던 이동(T1.travel)에 앞서 park 의 완료 확인이 시도 0 에 섰다. kim 의 묶음 재작업이 같은 시도 0 에 같은 호스트 시각 눈금으로 선다.
        val tick = "1970-01-01T00:01:05Z"
        val previous = IncidentBench.unitDecision("CONFIRM_DONE", "park", 0, at = tick)
        fun scene(after: String) = listOf(
            IncidentBench.unitBody(0, previous, unitId = "T1.travel"),
            IncidentBench.unitBody(1, after, unitId = "T1.travel"),
        )
        listOf(
            IncidentBench.unitDecision("REWORK", "kim", 0, at = tick) to OperationResult.CONFIRMED_APPLIED,
            IncidentBench.unitDecision("REWORK", "kim", 0, at = "1970-01-01T00:01:04Z") to OperationResult.CONFIRMED_NOT_APPLIED,
        ).forEach { (after, expected) ->
            bench.calls.clear()
            clock.now = T0
            val reads = scene(after)
            bench.units = { reads[it] }
            assertEquals(expected, resolutions.resolve(kim, "exec-1", "T1.travel", "REWORK", "i-1", "재작업").confirmation, after)
        }
        // 떠 둔 판단이 이 요청과 같은 칸(승인자·결정·시도·시각)이면 새 판단이 아니다.
        bench.calls.clear()
        clock.now = T0
        val same = IncidentBench.unitDecision("REWORK", "kim", 0, at = tick)
        val reads = listOf(IncidentBench.unitBody(0, same, unitId = "T1.travel"), IncidentBench.unitBody(0, same, unitId = "T1.travel"))
        bench.units = { reads[it] }
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, resolutions.resolve(kim, "exec-1", "T1.travel", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `묶음 재작업 재조회는 그 단위 판단 기록의 승인자 결정 시도 시각으로 대조한다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        bench.incidents = bench.listed(row("incident-1", unitId = "T1.travel", resolution = resolution("REWORK", "kim", T0.plusSeconds(1))))
        // 보내기 직전 시도 1, 앞 시도의 판단(kim REWORK 시도 0)이 있다. 받은 뒤에는 시도 2 와 그 시도 1 의 판단이다.
        val earlier = IncidentBench.unitDecision("REWORK", "kim", 0, at = "1970-01-01T00:01:00Z")
        val reads = listOf(IncidentBench.unitBody(1, earlier), IncidentBench.unitBody(2, IncidentBench.unitDecision("REWORK", "kim", 1)))
        bench.units = { reads[it] }
        val outcome = resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_APPLIED, outcome.confirmation)
        val observed = json.readTree(log.list().first().targetResponse)["observed"]
        assertEquals(listOf("T1", "1"), listOf(observed["unitId"].asText(), observed["decision"]["attempt"].asText()))

        // 다른 승인자·다른 결정·다른 시도·앞선 판단 그대로(받지 않음)는 반영 안 됨이다. 관측은 판단 기록으로 본 단위 줄이다.
        listOf(
            IncidentBench.unitDecision("REWORK", "park", 1),
            IncidentBench.unitDecision("CONFIRM_DONE", "kim", 1),
            IncidentBench.unitDecision("REWORK", "kim", 0, at = "1970-01-01T00:02:00Z"),
            earlier,
        ).forEach { after ->
            bench.calls.clear()
            val misses = listOf(IncidentBench.unitBody(1, earlier), IncidentBench.unitBody(1, after))
            bench.units = { misses[it] }
            val miss = resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업")
            assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, miss.confirmation, after)
        }

        // 다시 읽은 본문이 다른 인스턴스(재기동)면 판단 기록으로 대조하지 않는다 — 확인하지 못함이다.
        bench.calls.clear()
        val restarted = listOf(IncidentBench.unitBody(1, earlier), IncidentBench.unitBody(2, IncidentBench.unitDecision("REWORK", "kim", 1), instanceId = "i-2"))
        bench.units = { restarted[it] }
        assertNull(resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업").confirmation)

        // 앞서 판단이 없던 단위면 시각 조건 없이 승인자·결정·시도로 맞춘다.
        bench.calls.clear()
        val fresh = listOf(IncidentBench.unitBody(0), IncidentBench.unitBody(1, IncidentBench.unitDecision("REWORK", "kim", 0)))
        bench.units = { fresh[it] }
        assertEquals(OperationResult.CONFIRMED_APPLIED, resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업").confirmation)
    }

    @Test
    fun `그 실행의 그 단위에 인시던트가 있으면 같은 실행의 다른 단위 판단으로 넓히지 않는다`() {
        bench.resolveAnswer = HostWrite.NoResponse("응답 없음: HttpTimeoutException")
        val applied = resolution("REWORK", "kim", T0.plusSeconds(1))
        // 같은 사람이 같은 실행의 다른 단위를 요청 뒤에 판단했다. 이 단위(rack-arrival)에는 판단 없는 인시던트가 있어 넓히지 않는다.
        bench.incidents = bench.listed(
            row("incident-2", unitId = "rack-arrival"),
            row("incident-1", unitId = "RACK-204.S01", resolution = applied),
        )
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.CONFIRMED_NOT_APPLIED, outcome.confirmation)
        assertEquals("incident-2", json.readTree(log.list().first().targetResponse)["observed"]["incidentId"].asText())
    }

    @Test
    fun `보류가 아닌 재작업의 Refused 사유와 이빅션 NotHeld 는 거부로 남고 호스트의 사유가 응답과 응답 칸에 그대로 있다`() {
        // 사유는 picasso 의 문장 그대로, EVICTED 는 실행 호스트가 이빅션한 실행의 NotHeld 에 싣는 고정 코드다(화면 문구가 아니다).
        val answers = listOf(
            "Refused" to "점검을 하지 않은 단위는 확인할 것이 없다 — 재작업만 받는다: T1",
            "Refused" to "취소한 실행의 대상은 재작업하지 않는다 — 새 작업 지시로 낸다",
            "Refused" to "실행이 종결한 뒤 한 대상씩 재작업한다 — 아직 낼 단위나 도는 단위가 있다",
            "Refused" to "발행 관문이 막고 있다 — humanoid-01=STOPPED (재작업하지 않았다, 허가가 돌아온 뒤 다시 낸다)",
            "Refused" to "같은 기체에 다른 활성 실행이 있다 — 그 실행이 끝난 뒤 재작업한다: exec-2",
            "NotHeld" to "EVICTED",
        )
        answers.forEach { (name, detail) ->
            bench.resolveAnswer = HostWrite.Answered(200, IncidentBench.resolved(null, name, detail))
            val outcome = resolutions.resolve(kim, "exec-1", "T1", "REWORK", "i-1", "재작업")
            assertEquals(OperationResult.REJECTED, outcome.result, detail)
            assertEquals(name, outcome.outcome)
            assertEquals(detail, outcome.answer!!["detail"].asText())
            assertNull(outcome.answer["incidentId"].takeIf { !it.isNull })
            assertNull(outcome.rejection)
            assertNull(outcome.confirmation)
        }
        val rows = log.list()
        assertEquals(List(answers.size) { OperationResult.REJECTED }, rows.map { it.result })
        assertEquals(answers.map { it.second }.reversed(), rows.map { json.readTree(it.targetResponse)["body"]["detail"].asText() })
        assertEquals(List(answers.size) { "exec-1/T1" }, rows.map { it.target })
    }

    @Test
    fun `판단 본문이 틀렸다는 호스트 400 은 거부이고 오류 이름을 넘긴다`() {
        bench.resolveAnswer = HostWrite.Answered(400, """{"error":"BAD_REQUEST","detail":"decision 이 두 값이 아니다"}""")
        val outcome = resolutions.resolve(kim, "exec-1", "rack-arrival", "REWORK", "i-1", "재작업")
        assertEquals(OperationResult.REJECTED, outcome.result)
        assertEquals("BAD_REQUEST", outcome.rejection!!.error)
        assertNull(outcome.outcome)
    }

    companion object {
        val T0: Instant = Instant.parse("2026-10-09T02:31:00Z")
    }
}
