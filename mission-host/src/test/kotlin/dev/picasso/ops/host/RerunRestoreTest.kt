package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.OperatorDecision
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 정착 뒤 다시 돈 실행과 운영자 취소의 재기동(V10). 개정판 수락이나 운영자 판단으로 다시 돈 실행은 받은 가장 높은 판과 가장 최근 판단
 * 기록으로 다시 서고 그 기체는 도는 실행으로 판정에서 빠진다. 다시 돎은 그 실행의 로봇 명령보다 먼저 적힌다. 적힌 운영자 취소는 다시 지은
 * 실행에 한 번 다시 걸린다.
 */
class RerunRestoreTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private fun HostBench.tasks(): Map<String, TaskState> =
        mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.associate { it.taskId to it.machine.state } }

    private fun HostBench.runningTask(): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            tasks().entries.firstOrNull { it.value == TaskState.RUNNING }?.let { return it.key }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    private fun HostBench.judge(): JsonNode =
        post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", HUMANOID)).body!!["robots"].single()

    private fun HostBench.cancel(executionId: String): JsonNode =
        post(
            "/host/executions/$executionId/cancel",
            JSON.writeValueAsString(linkedMapOf("approverId" to "kim", "reason" to "현장 중단", "instanceId" to host.instanceId)),
        ).body!!

    /** 슬롯 하나 랙을 든 채 실패로 정착시키고 정착 기록과 잔여 파지 표시 해소까지 한다. 실행 id 를 돌려준다. */
    private fun HostBench.failedRack(jobOrderId: String): String {
        awaitPump()
        val first = post("/host/job-orders", request(rack(jobOrderId, S01), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", first["result"].asText(), first.toString())
        val executionId = first["executionId"].asText()
        val running = runningTask()
        val outcome = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
        assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
        assertEquals("FAILED", driveUntil(executionId, settledStates)["physicalState"].asText())
        eventually("정착 기록과 잔여 파지 표시") { events(jobOrderId).map { it[0] } == listOf("SETTLED") && get("/host/robots/residual-holds")["holds"].size() == 1 }
        val markerId = get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
        // mimic 손은 든 채라 첫 확인은 어긋남이다(B2). 현장 확인을 지목해 다시 확인한다.
        val cleared = clearHeldCiting(HUMANOID, markerId)
        assertEquals(200, cleared.status, cleared.body.toString())
        return executionId
    }

    @Test
    fun `정착 뒤 개정판으로 다시 돈 실행은 그 판과 판단 기록을 로봇 명령 전에 적고 재기동하면 그 판으로 다시 서며 그 기체는 도는 실행으로 판정에서 빠진다`() {
        HostBench().use { bench ->
            val executionId = bench.failedRack("JO-1")
            val before = bench.host.instanceId
            val revised = HostRequests.jobOrder(JSON.readTree(rackV2("JO-1")))
            // 호스트 잠금을 쥔 채 낸다. 그 사이 pump 가 돌지 않으므로 아래 기록은 제출 안에서 적힌 것이다.
            bench.host.exclusive {
                val outcome = bench.host.submit(revised, listOf(HUMANOID))
                assertEquals(SubmitResult.ACCEPTED to executionId, outcome.result to outcome.executionId, outcome.toString())
                assertEquals(listOf(2), revisions("JO-1"))
                assertEquals(listOf("SETTLED", before, executionId, "FAILED"), events("JO-1")[0])
                assertEquals(listOf("REOPENED", before, executionId, "RUNNING"), events("JO-1")[1])
                assertEquals(2, JSON.readTree(latestRecord("JO-1"))["orderVersion"].asInt())
            }
            bench.eventually("개정판의 새 슬롯 태스크") { bench.tasks()["JO-1#$S02"] == TaskState.RUNNING }
            val tasks = bench.tasks()

            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(
                listOf("JO-1", "RESTORED", before, executionId),
                listOf(row["jobOrderId"].asText(), row["result"].asText(), row["previousInstanceId"].asText(), row["previousExecutionId"].asText()),
                row.toString(),
            )
            val rebuilt = row["executionId"].asText()
            // 개정판의 계획(슬롯 둘)으로 다시 섰고, 앞 판에서 실패한 슬롯은 기록의 판정 그대로이며, 도는 슬롯에는 새 태스크 없이 다시 붙는다.
            bench.eventually("다시 붙음") {
                bench.execution(rebuilt)!!["units"].map { it["unitId"].asText() to it["state"].asText() } == listOf(S01 to "FAILED", S02 to "RUNNING")
            }
            assertEquals(tasks, bench.tasks())
            val judged = bench.judge()
            assertFalse(judged["passed"].asBoolean(), judged.toString())
            assertEquals(rebuilt, judged["runningExecutionId"].asText(), judged.toString())
            val other = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", other["result"].asText(), other.toString())

            val done = bench.driveUntil(rebuilt, settledStates)
            assertEquals(2, done["jobResponse"]["version"].asInt(), done.toString())
            assertFalse("JO-2#T1" in bench.tasks())
        }
    }

    @Test
    fun `취소한 보류 실행이 운영자 판단으로 같은 판에서 다시 돈 뒤 재기동하면 그 판단 기록과 취소를 싣고 다시 서서 새 명령 없이 멈춘다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            assertEquals("CANCELED", bench.cancel(executionId)["result"].asText())
            bench.driveUntil(executionId, setOf("ABORTED"))
            bench.eventually("첫 정착 기록") { events("JO-1").map { it[0] } == listOf("SETTLED") }
            val before = bench.host.instanceId
            val tasks = bench.tasks()
            // 다시 정착을 적지 못하게 해 다시 돈 채 재기동하는 자리를 만든다.
            refuseSettled()
            bench.host.exclusive {
                val resolved = bench.host.resolve(executionId, WAIT, OperatorDecision.CONFIRM_DONE, Approver("kim", ApproverKind.PERSON), null)
                assertEquals(MissionHost.RESOLVED, resolved.result, resolved.toString())
                assertEquals(listOf("REOPENED", before, executionId, "RUNNING"), events("JO-1")[1])
                assertTrue("CONFIRM_DONE" in latestRecord("JO-1"))
            }
            bench.idlePumps()

            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(listOf("JO-1", "RESTORED"), listOf(row["jobOrderId"].asText(), row["result"].asText()), row.toString())
            val rebuilt = row["executionId"].asText()
            PostgresSupport.execute("DROP TRIGGER refuse_settled ON mission.execution_journal_event")
            val after = bench.host.instanceId
            bench.eventually("다시 지은 실행의 정착 기록") { events("JO-1").last().take(3) == listOf("SETTLED", after, rebuilt) }
            val settled = JSON.readTree(
                PostgresSupport.queryOne(
                    "SELECT snapshot::text FROM mission.execution_journal_event WHERE kind = 'SETTLED' AND job_order_id = 'JO-1' ORDER BY event_id DESC LIMIT 1",
                ) { it.getString(1) },
            )
            assertEquals("ABORTED", settled["physicalState"].asText(), settled.toString())
            assertEquals("DONE", settled["units"].single { it["unitId"].asText() == WAIT }["state"].asText(), settled.toString())
            assertEquals("kim", settled["cancel"]["approverId"].asText(), settled.toString())
            assertEquals(listOf("SETTLED", "REOPENED", "RESTORED", "SETTLED"), events("JO-1").map { it[0] })
            assertEquals(tasks, bench.tasks())
        }
    }

    @Test
    fun `운영자 취소를 적고 반영하기 전에 재기동하면 다시 지은 실행이 도는 태스크에 StartTask 없이 붙어 취소를 한 번 다시 보내고 그 태스크가 끝난 뒤 새 태스크 없이 멈춘다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.runningTask()
            assertEquals("JO-1#T1.travel", running)
            // 가상 시계를 밀지 않으므로 기체의 태스크는 취소 중(CANCELLING)에 머물고 실행은 정착하지 않는다.
            assertEquals("CANCELED", bench.cancel("exec-1")["result"].asText())
            bench.idlePumps()
            assertEquals(mapOf(running to TaskState.CANCELLING), bench.tasks())

            bench.restartHost {
                bench.starts.clear()
                bench.cancels.clear()
            }
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(listOf("JO-1", "RESTORED"), listOf(row["jobOrderId"].asText(), row["result"].asText()), row.toString())
            val rebuilt = row["executionId"].asText()
            assertEquals("kim", bench.execution(rebuilt)!!["cancel"]["approverId"].asText())
            // 복원 직후 취소를 건다. 첫 pump 가 도는 태스크에 `StartTask` 없이 붙어 취소를 한 번 보내고, 실행은 그 태스크의 종료를 본 뒤에야
            // 멈춘다 — 붙기 전에 멈추면 취소 중인 태스크를 남긴 채 기체를 내준다. 다음 단위는 시작하지 않는다.
            val aborted = bench.driveUntil(rebuilt, settledStates)
            assertEquals("ABORTED", aborted["physicalState"].asText(), aborted.toString())
            assertEquals(listOf("T1.travel" to "ABORTED", "T1" to "PENDING"), aborted["units"].map { it["unitId"].asText() to it["state"].asText() }, aborted.toString())
            assertEquals(mapOf(running to TaskState.CANCELLED), bench.tasks())
            assertEquals(emptyList(), bench.starts.toList())
            assertEquals(listOf(HUMANOID to running), bench.cancels.toList())
            // 취소 행은 그대로 하나다. 다시 지은 실행에 다시 취소를 받지 않는다.
            assertEquals("NOT_CANCELABLE", bench.cancel(rebuilt)["result"].asText())
            assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_cancel") { it.getInt(1) })
            assertEquals(listOf("RESTORED", "SETTLED"), events("JO-1").map { it[0] })
            assertTrue(bench.judge()["passed"].asBoolean())
        }
    }

    /**
     * 복원과 첫 pump 사이에 기체가 취소를 끝낸 경우. 취소는 복원 직후 걸리므로, 첫 pump 는 그 태스크에 `StartTask` 없이 붙고 이미 끝난
     * 태스크에는 취소를 다시 보내지 않으며 그 종료를 요청한 취소의 중단으로 읽는다(picasso `attachForCancel`). 첫 pump 뒤로 미루면 첫
     * pump 가 끝난 태스크에 `StartTask` 를 다시 보내 붙는다 — 끝난 태스크를 들지 않는 하위에서는 새 접수가 되어 기체가 다시 움직인다.
     */
    @Test
    fun `운영자 취소를 적은 뒤 재기동하고 첫 pump 전에 기체가 취소를 끝내면 StartTask 도 취소도 다시 보내지 않고 보류와 인시던트 없이 중단으로 정착한다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.runningTask()
            assertEquals("CANCELED", bench.cancel("exec-1")["result"].asText())
            bench.idlePumps()
            assertEquals(mapOf(running to TaskState.CANCELLING), bench.tasks())

            // 복원은 기동 안에서 끝나고 첫 pump 는 셀 대역을 먼저 읽는다. 그 읽기에서 시계를 밀어 기체가 취소를 끝내게 한다.
            bench.restartHost {
                bench.starts.clear()
                bench.cancels.clear()
                bench.onCellFetch.set {
                    repeat(30) { if (bench.tasks()[running] != TaskState.CANCELLED) bench.mimic.server.advance(Duration.ofSeconds(1)) }
                }
            }
            val rebuilt = bench.get("/host/executions")["restore"]["rows"].single()["executionId"].asText()
            bench.eventually("중단 정착") { bench.execution(rebuilt)?.get("physicalState")?.asText() == "ABORTED" }
            val aborted = bench.execution(rebuilt)!!
            assertEquals(listOf("T1.travel" to "ABORTED", "T1" to "PENDING"), aborted["units"].map { it["unitId"].asText() to it["state"].asText() }, aborted.toString())
            assertEquals(mapOf(running to TaskState.CANCELLED), bench.tasks())
            assertEquals(emptyList(), bench.starts.toList())
            assertEquals(emptyList(), bench.cancels.toList())
            val incidents = bench.get("/host/incidents")["incidents"].filter { it["jobOrderId"].asText() == "JO-1" }
            assertEquals(emptyList(), incidents, bench.get("/host/incidents").toString())
            bench.eventually("정착 기록") { events("JO-1").map { it[0] } == listOf("RESTORED", "SETTLED") }
            val states = PostgresSupport.queryAll(
                "SELECT physical_state FROM mission.job_response_log WHERE job_order_id = 'JO-1' AND instance_id = '${bench.host.instanceId}' ORDER BY log_id",
            ) { it.getString(1) }
            assertEquals("ABORTED", states.last(), states.toString())
            assertFalse("OPERATOR_HOLD" in states, states.toString())
        }
    }

    @Test
    fun `정착 뒤 다시 돎이 마지막인 일지 행을 다시 짓지 못하면 포기하고 그 기체를 빼며 같은 작업 지시는 끝난 작업 지시가 아니라 일지에 있는 작업 지시로 거부한다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            // 이전 인스턴스가 정착을 적은 뒤 다시 돈 일지 행. 그 임무 버전 행이 없어 다시 짓지 못한다. 기체에는 그 작업 지시의 태스크가 돈다.
            val order = JSON.writeValueAsString(JSON.readTree(inspect("JO-X", "T1" to "bay-7")))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('JO-X', '$HUMANOID', '$order'::jsonb, 'InspectAsset', 9, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
            PostgresSupport.execute(
                "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES " +
                    "('JO-X', 'SETTLED', 'mw-before', 'exec-7', 'FAILED'), ('JO-X', 'REOPENED', 'mw-before', 'exec-7', 'RUNNING')",
            )
            val started = bench.clientPort("orphan").start(HUMANOID, "JO-X#T1.travel", 1, "navigate_to", mapOf("location" to "bay-7"))
            assertTrue(started.hasHandle(), started.toString())

            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(listOf("JO-X", "GAVE_UP", "mw-before", "exec-7"), listOf("jobOrderId", "result", "previousInstanceId", "previousExecutionId").map { row[it].asText() })
            assertEquals(listOf("복원 못 한 실행이 있다: JO-X"), bench.judge()["reasons"].map { it.asText() })
            val again = bench.post("/host/job-orders", request(inspect("JO-X", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertFalse(again["forgottenJobOrder"].asBoolean(), again.toString())
            assertTrue(again["rejectionReason"].asText().startsWith(MissionHost.JOURNALED_JOB_ORDER), again.toString())

            // 다음 기동도 다시 짓지 않고 포기한 채로 그 기체를 뺀다.
            bench.restartHost()
            assertTrue(bench.get("/host/executions")["restore"]["rows"].isEmpty)
            assertEquals(listOf("SETTLED", "REOPENED", "GAVE_UP"), events("JO-X").map { it[0] })
            assertEquals(listOf("복원 못 한 실행이 있다: JO-X"), bench.judge()["reasons"].map { it.asText() })
        }
    }

    @Test
    fun `다시 돎을 적지 못하면 제출은 500 이고 적을 때까지 pump 와 제출을 막아 개정판의 로봇 명령이 나가지 않으며 적으면 풀린다`() {
        HostBench().use { bench ->
            val executionId = bench.failedRack("JO-1")
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_revision() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'revision refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute(
                "CREATE TRIGGER refuse_revision BEFORE INSERT ON mission.execution_journal_revision FOR EACH ROW EXECUTE FUNCTION mission.refuse_revision()",
            )
            val revised = bench.post("/host/job-orders", request(rackV2("JO-1"), "candidates", HUMANOID))
            assertEquals(500, revised.status, revised.body.toString())
            val tasks = bench.tasks()
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.idlePumps(4)
            assertEquals(tasks, bench.tasks())
            assertEquals(listOf(executionId), bench.get("/host/executions")["journalBlockedExecutionIds"].map { it.asText() })
            assertEquals(listOf("SETTLED"), events("JO-1").map { it[0] })
            val blocked = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", blocked["result"].asText(), blocked.toString())

            PostgresSupport.execute("DROP TRIGGER refuse_revision ON mission.execution_journal_revision")
            bench.eventually("다시 돎 기록") { bench.get("/host/executions")["journalBlockedExecutionIds"].isEmpty }
            assertEquals(listOf(2), revisions("JO-1"))
            assertEquals(listOf("SETTLED", "REOPENED"), events("JO-1").map { it[0] })
            bench.eventually("개정판의 새 슬롯 태스크") { "JO-1#$S02" in bench.tasks() }
        }
    }

    private companion object {
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        const val WAIT = "rack-arrival"

        /** 슬롯을 하나 더한 판 2. 앞 판의 실패한 슬롯은 그대로 두고 새 슬롯이 돈다. */
        fun rackV2(jobOrderId: String) = rack(jobOrderId, S01, S02).replace("\"version\":1", "\"version\":2")

        /** 그 작업 지시의 일지 이벤트(종류, 인스턴스, 실행 id, 상세), 적은 순서. */
        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

        fun revisions(jobOrderId: String): List<Int> = PostgresSupport.queryAll(
            "SELECT version FROM mission.execution_journal_revision WHERE job_order_id = '$jobOrderId' ORDER BY revision_id",
        ) { it.getInt(1) }

        fun latestRecord(jobOrderId: String): String = PostgresSupport.queryOne(
            "SELECT record FROM mission.execution_judgment_record WHERE job_order_id = '$jobOrderId' ORDER BY record_id DESC LIMIT 1",
        ) { it.getString(1) }

        fun refuseSettled() {
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_settled() RETURNS trigger AS ${'$'}${'$'}
                BEGIN IF NEW.kind = 'SETTLED' THEN RAISE EXCEPTION 'settled refused'; END IF; RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute(
                "CREATE TRIGGER refuse_settled BEFORE INSERT ON mission.execution_journal_event FOR EACH ROW EXECUTE FUNCTION mission.refuse_settled()",
            )
        }
    }
}
