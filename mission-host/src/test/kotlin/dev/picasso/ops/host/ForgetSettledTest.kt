package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.ForgetOutcome
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PhysicalState
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 정착한 실행을 picasso 메모리에서 놓는다(picasso `Middleware.forget`, 한계 §15.224 · 놓기 소비자). 놓는 때(정착 기록 커밋과 `ack` 뒤),
 * 놓은 뒤 호스트 맵, 거절의 다시 시도, 개정판을 받을 `FAILED` 를 두는 폭, 다시 정착할 때마다 적는 정착 이력, 놓은 작업 지시의 거부(재기동
 * 전후), 메모리 크기를 본다.
 */
class ForgetSettledTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private val empty = HostFootprint(0, 0, 0, 0, 0, 0, 0)

    private fun HostBench.submitInspect(jobOrderId: String): String {
        val reply = post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", reply["result"].asText(), reply.toString())
        return reply["executionId"].asText()
    }

    private fun HostBench.tasks(): Int = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.size }

    private fun HostBench.runningTask(): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    /** 슬롯 하나 랙을 든 채 실패로 정착시키고(`FAILED`) 잔여 파지 표시를 사람이 해소한다. 실행 id 를 돌려준다. */
    private fun HostBench.failedRack(jobOrderId: String): String {
        awaitPump()
        val first = post("/host/job-orders", request(rack(jobOrderId, S01), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", first["result"].asText(), first.toString())
        val executionId = first["executionId"].asText()
        val running = runningTask()
        val outcome = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
        assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
        assertEquals("FAILED", driveUntil(executionId, settledStates)["physicalState"].asText())
        eventually("잔여 파지 표시") { get("/host/robots/residual-holds")["holds"].size() == 1 }
        val markerId = get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
        // mimic 손은 든 채라 첫 확인은 어긋남이다(B2). 현장 확인을 지목해 다시 확인한다.
        val cleared = clearHeldCiting(HUMANOID, markerId)
        assertEquals(200, cleared.status, cleared.body.toString())
        return executionId
    }

    private fun rackVersion(jobOrderId: String, version: Int) = rack(jobOrderId, S01).replace("\"version\":1", "\"version\":$version")

    @Test
    fun `정착을 적고 응답을 ack 한 뒤 끝난 실행을 놓으면 호스트 메모리가 비고 목록과 좁힌 목록은 정착 본문으로 같은 줄을 싣는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitInspect("JO-1")
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settledStates)["physicalState"].asText())
            bench.eventually("놓기") { bench.host.footprint() == empty }

            val (snapshot, responseId) = PostgresSupport.queryAll(
                "SELECT snapshot::text, execution_id FROM mission.execution_journal_event WHERE kind = 'SETTLED' AND job_order_id = 'JO-1'",
            ) { it.getString(1) to it.getString(2) }.single().let { (text, id) ->
                assertEquals(executionId, id)
                JSON.readTree(text).let { it to it["jobResponse"]["jobResponseId"].asText() }
            }
            // 놓은 실행의 줄은 정착할 때 적은 본문 그대로다. 전체·작업 지시·실행 id 어느 읽기로도 같다.
            assertEquals(listOf(snapshot), bench.get("/host/executions")["executions"].toList())
            assertEquals(listOf(snapshot), bench.get("/host/executions?jobOrderId=JO-1")["executions"].toList())
            assertEquals(listOf(snapshot), bench.get("/host/executions?executionId=$executionId")["executions"].toList())
            assertTrue(bench.get("/host/executions?jobOrderId=JO-2")["executions"].isEmpty)
            assertEquals(0, bench.get("/host/executions/summary")["live"].asInt())
            val page = bench.get("/host/executions?recent=5")
            assertTrue(page["executions"].isEmpty, page.toString())
            assertEquals(snapshot, page["settled"].single()["execution"])
            assertFalse(page["settled"].single()["superseded"].asBoolean())

            // 그 정착의 송신 기록·결과 통보·판단 기록이 커밋돼 있다.
            assertEquals(responseId, bench.get("/host/job-responses?jobOrderId=JO-1")["responses"][0]["jobResponseId"].asText())
            assertEquals(responseId, bench.get("/host/result-events")["events"].last()["snapshot"]["jobResponse"]["jobResponseId"].asText())
            assertTrue(count("execution_judgment_record WHERE job_order_id = 'JO-1'") >= 1)

            // 놓은 실행은 정착한 실행이라 취소하지 않는다.
            val cancel = bench.post(
                "/host/executions/$executionId/cancel",
                JSON.writeValueAsString(linkedMapOf("approverId" to "kim", "reason" to "닫기", "instanceId" to bench.host.instanceId)),
            ).body!!
            assertEquals(listOf("NOT_CANCELABLE", "PHYSICALLY_DONE"), listOf(cancel["result"].asText(), cancel["physicalState"].asText()), cancel.toString())

            // 같은 작업 지시를 다시 내면 이미 끝나 이빅션한 작업 지시다.
            val tasks = bench.tasks()
            val again = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
            assertTrue(again["rejectionReason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER), again.toString())
            bench.idlePumps()
            assertEquals(tasks, bench.tasks())
            assertEquals(empty, bench.host.footprint())
        }
    }

    @Test
    fun `정착 기록이 실패하는 동안은 놓지 않고 기록이 커밋된 다음 pump 에 놓는다`() {
        HostBench().use { bench ->
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_settled() RETURNS trigger AS ${'$'}${'$'}
                BEGIN IF NEW.kind = 'SETTLED' THEN RAISE EXCEPTION 'settled refused'; END IF; RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute(
                "CREATE TRIGGER refuse_settled BEFORE INSERT ON mission.execution_journal_event FOR EACH ROW EXECUTE FUNCTION mission.refuse_settled()",
            )
            val executionId = bench.submitInspect("JO-1")
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settledStates)["physicalState"].asText())
            bench.idlePumps(4)
            val held = bench.host.footprint()
            assertEquals(1, held.executions, held.toString())
            assertEquals(0, held.settleMarks, held.toString())
            assertEquals(0, count("execution_journal_event WHERE kind = 'SETTLED'"))
            // 기록이 실패한 pump 는 ack 하지 않는다. 확인되지 않은 통보가 아웃박스에 있다.
            assertTrue(held.responses > 0, held.toString())

            PostgresSupport.execute("DROP TRIGGER refuse_settled ON mission.execution_journal_event")
            bench.eventually("놓기") { bench.host.footprint() == empty }
            assertEquals(1, count("execution_journal_event WHERE kind = 'SETTLED'"))
        }
    }

    @Test
    fun `놓기가 거절되면 메모리와 맵을 남기고 다음 pump 에 다시 놓으며 모름이면 아무것도 지우지 않는다`() {
        HostBench().use { bench ->
            val host = bench.host
            val real = host.forget
            val refusals = AtomicInteger()
            host.forget = { refusals.incrementAndGet(); ForgetOutcome.Refused("주입한 거절") }
            val executionId = bench.submitInspect("JO-1")
            bench.driveUntil(executionId, settledStates)
            bench.eventually("거절 둘") { refusals.get() >= 2 }
            assertEquals(HostFootprint(1, host.footprint().responses, 1, 1, 1, 0, 0), host.footprint())
            assertTrue(host.footprint().responses > 0)

            host.forget = { ForgetOutcome.Unknown }
            bench.idlePumps()
            assertEquals(1, host.footprint().settleMarks, "모름에 맵을 지웠다")

            host.forget = real
            bench.eventually("놓기") { host.footprint() == empty }
            assertTrue(bench.get("/host/executions?executionId=$executionId")["executions"].single()["physicalState"].asText() == "PHYSICALLY_DONE")
        }
    }

    @Test
    fun `FAILED 는 두는 폭 동안 메모리에 남아 같은 판은 IDEMPOTENT 이고 폭이 지나면 놓아 같은 작업 지시는 판과 무관하게 놓은 작업 지시로 거부한다`() {
        HostBench().use { bench ->
            val executionId = bench.failedRack("JO-1")
            bench.eventually("정착 기록") { bench.host.footprint().settleMarks == 1 }
            bench.idlePumps()
            assertEquals(1, bench.host.footprint().executions, "두는 폭 안에 FAILED 를 놓았다")

            val same = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals(listOf("IDEMPOTENT", executionId), listOf(same["result"].asText(), same["executionId"].asText()), same.toString())

            bench.mimic.server.advance(MissionHost.DEFAULT_REVISABLE_RETENTION.plusMinutes(1))
            bench.awaitPump()
            bench.eventually("놓기") { bench.host.footprint() == HostFootprint(0, 0, 0, 0, 0, 0, 0) }
            assertEquals("FAILED", bench.get("/host/executions?executionId=$executionId")["executions"].single()["physicalState"].asText())
            // 놓은 실행의 인시던트는 남고, 그 단위의 상태는 마지막 정착 때의 것이다.
            val item = checkNotNull(bench.get("/host/incidents")["incidents"].firstOrNull { it["executionId"].asText() == executionId }) {
                "실패한 단위가 인시던트를 열지 않았다: ${bench.get("/host/incidents")}"
            }
            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals("FAILED", detail["unitState"].asText(), detail.toString())

            val tasks = bench.tasks()
            for (version in listOf(1, 2)) {
                val again = bench.post("/host/job-orders", request(rackVersion("JO-1", version), "candidates", HUMANOID)).body!!
                assertEquals("REJECTED", again["result"].asText(), again.toString())
                assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
                assertTrue(again["rejectionReason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER), again.toString())
            }
            bench.idlePumps()
            assertEquals(tasks, bench.tasks())
            assertEquals(1, count("execution_journal"))
        }
    }

    @Test
    fun `실패 뒤 개정판으로 다시 정착하면 그 판의 정착 이력 줄을 하나 더 적고 앞 줄은 다시 정착함으로 표시하며 pump 를 거듭해도 정착마다 한 줄이다`() {
        HostBench().use { bench ->
            val executionId = bench.failedRack("JO-1")
            bench.eventually("첫 정착 기록") { settled("JO-1").size == 1 }
            val revised = bench.post("/host/job-orders", request(rackVersion("JO-1", 2), "candidates", HUMANOID)).body!!
            assertEquals(listOf("ACCEPTED", executionId), listOf(revised["result"].asText(), revised["executionId"].asText()), revised.toString())
            bench.eventually("다시 정착 기록") { settled("JO-1").size == 2 }
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.awaitPump()
            bench.idlePumps(4)

            val rows = settled("JO-1")
            assertEquals(listOf(1, 2), rows.map { it["jobResponse"]["version"].asInt() }, rows.toString())
            assertEquals(listOf("FAILED", "FAILED"), rows.map { it["physicalState"].asText() })
            assertTrue(rows[0]["jobResponse"]["jobResponseId"] != rows[1]["jobResponse"]["jobResponseId"], rows.toString())
            val page = bench.get("/host/executions?recent=5")["settled"].filter { it["execution"]["jobOrderId"].asText() == "JO-1" }
            assertEquals(listOf(2 to false, 1 to true), page.map { it["execution"]["jobResponse"]["version"].asInt() to it["superseded"].asBoolean() })
            // 판단 기록도 다시 돈 실행의 것을 적는다(개정판의 판).
            val record = PostgresSupport.queryOne(
                "SELECT record FROM mission.execution_judgment_record WHERE job_order_id = 'JO-1' ORDER BY record_id DESC LIMIT 1",
            ) { it.getString(1) }
            assertEquals(2, JSON.readTree(record)["orderVersion"].asInt(), record)
        }
    }

    @Test
    fun `취소한 보류 실행은 판단을 기다리는 동안 놓지 않고 판단으로 같은 판에서 다시 정착하면 정착 줄을 하나 더 적고 판단 기록을 적은 뒤 놓는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            val canceled = bench.post(
                "/host/executions/$executionId/cancel",
                JSON.writeValueAsString(linkedMapOf("approverId" to "kim", "reason" to "보류 중 중단", "instanceId" to bench.host.instanceId)),
            ).body!!
            assertEquals("CANCELED", canceled["result"].asText(), canceled.toString())
            bench.driveUntil(executionId, setOf("ABORTED"))
            bench.eventually("첫 정착 기록") { settled("JO-1").size == 1 }
            bench.idlePumps(4)
            // 정착했지만 보류 단위가 판단을 기다린다(활성). 놓지 않는다.
            val held = bench.host.footprint()
            assertEquals(listOf(1, 1), listOf(held.executions, held.settleMarks), held.toString())
            assertEquals("OPERATOR_HOLD", settled("JO-1").single()["units"].single { it["unitId"].asText() == WAIT }["state"].asText())
            assertEquals(listOf(executionId), bench.get("/host/executions?recent=5")["executions"].map { it["executionId"].asText() })

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "CONFIRM_DONE", "kim").body!!["result"].asText())
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.awaitPump()
            bench.eventually("다시 정착 기록과 놓기") { settled("JO-1").size == 2 && bench.host.footprint() == empty }

            val rows = settled("JO-1")
            assertEquals(listOf(1, 1), rows.map { it["jobResponse"]["version"].asInt() }, rows.toString())
            assertEquals(listOf("ABORTED", "ABORTED"), rows.map { it["physicalState"].asText() })
            assertTrue(rows[0]["jobResponse"]["jobResponseId"] != rows[1]["jobResponse"]["jobResponseId"], rows.toString())
            assertEquals("DONE", rows[1]["units"].single { it["unitId"].asText() == WAIT }["state"].asText())
            val page = bench.get("/host/executions?recent=5")["settled"].filter { it["execution"]["jobOrderId"].asText() == "JO-1" }
            assertEquals(listOf(false, true), page.map { it["superseded"].asBoolean() })
            // 다시 돈 실행의 판단 기록(운영자 판단)이 놓기 전에 적혔다.
            val record = PostgresSupport.queryOne(
                "SELECT record FROM mission.execution_judgment_record WHERE job_order_id = 'JO-1' ORDER BY record_id DESC LIMIT 1",
            ) { it.getString(1) }
            assertTrue("CONFIRM_DONE" in record, record)
        }
    }

    @Test
    fun `재기동해 picasso 가 놓은 작업 지시를 몰라도 실행 일지가 같은 작업 지시를 놓은 작업 지시로 거부하고 일지 막힘이 없다`() {
        HostBench().use { bench ->
            val executionId = bench.submitInspect("JO-1")
            bench.driveUntil(executionId, settledStates)
            bench.eventually("놓기") { bench.host.footprint() == empty }
            bench.restartHost()
            val tasks = bench.tasks()

            val again = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
            assertTrue(again["rejectionReason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER), again.toString())
            bench.idlePumps()
            val view = bench.get("/host/executions")
            assertTrue(view["executions"].isEmpty, view.toString())
            assertTrue(view["journalBlockedExecutionIds"].isEmpty, view.toString())
            assertEquals(tasks, bench.tasks())
            assertEquals(1, count("execution_journal"))
            // 다른 작업 지시는 그대로 받는다.
            bench.submitInspect("JO-2")
        }
    }

    @Test
    fun `정착하지 않은 일지 행이 있는 작업 지시를 다시 내면 일지 쓰기 전에 거부해 호스트가 막히지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val order = JSON.writeValueAsString(JSON.readTree(inspect("JO-9", "T1" to "bay-7")))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('JO-9', '$HUMANOID', '$order'::jsonb, 'InspectAsset', NULL, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
            val again = bench.post("/host/job-orders", request(inspect("JO-9", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals(200, again.status, again.body.toString())
            assertEquals("REJECTED", again.body!!["result"].asText(), again.body.toString())
            assertFalse(again.body["forgottenJobOrder"].asBoolean(), again.body.toString())
            assertTrue(again.body["rejectionReason"].asText().startsWith(MissionHost.JOURNALED_JOB_ORDER), again.body.toString())
            val view = bench.get("/host/executions")
            assertTrue(view["executions"].isEmpty && view["journalBlockedExecutionIds"].isEmpty, view.toString())
            bench.submitInspect("JO-10")
        }
    }

    @Test
    fun `정착을 거듭해도 호스트 메모리는 남은 실행만큼이다`() {
        HostBench().use { bench ->
            (1..4).forEach { n ->
                val executionId = bench.submitInspect("JO-$n")
                val running = bench.host.footprint()
                assertEquals(listOf(1, 1), listOf(running.executions, running.journaled), running.toString())
                bench.driveUntil(executionId, settledStates)
                bench.eventually("${n}번째 놓기") { bench.host.footprint() == empty }
            }
            assertEquals((1..4).map { "exec-$it" }, bench.get("/host/executions")["executions"].map { it["executionId"].asText() })
            assertEquals(4, count("execution_journal_event WHERE kind = 'SETTLED'"))
        }
    }

    @Test
    fun `놓을 때는 끝난 실행은 곧이고 개정판을 받을 실행은 두는 폭이 지난 뒤이며 구동이 돌리는 실행은 놓지 않는다`() {
        val at = Instant.parse("2026-10-15T00:00:00Z")
        val retention = Duration.ofMinutes(5)
        fun due(state: PhysicalState, after: Duration) = MissionHost.forgetDue(state, at, at.plus(after), retention)
        assertEquals(
            PhysicalState.entries.associateWith { it in setOf(PhysicalState.PHYSICALLY_DONE, PhysicalState.ABORTED) },
            PhysicalState.entries.associateWith { due(it, Duration.ZERO) },
        )
        assertEquals(
            PhysicalState.entries.associateWith { !it.pumps },
            PhysicalState.entries.associateWith { due(it, retention) },
        )
        assertFalse(due(PhysicalState.UNVERIFIED, retention.minusMillis(1)))
        assertFalse(due(PhysicalState.FAILED, retention.minusMillis(1)))
    }

    private companion object {
        const val S01 = "RACK-204.S01"
        const val WAIT = "rack-arrival"

        fun count(tableAndWhere: String): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.$tableAndWhere") { it.getInt(1) }

        /** 그 작업 지시의 정착 이벤트 본문, 적은 순서. */
        fun settled(jobOrderId: String): List<JsonNode> = PostgresSupport.queryAll(
            "SELECT snapshot::text FROM mission.execution_journal_event WHERE kind = 'SETTLED' AND job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { JSON.readTree(it.getString(1)) }
    }
}
