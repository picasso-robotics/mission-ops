package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.StartOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.STANDARD_CELL
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기동 복원과 재기동 중복(S4b 스펙 T3·T5·T6, §6.3·§6.4). 호스트만 다시 띄우고 mimic 과 DB 는 그대로다(e2e 의 재기동과 같은 배선).
 */
class HostRestoreTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    @Test
    fun `도는 실행을 재기동하면 같은 작업 지시가 새 인스턴스의 실행으로 다시 서고 끝난 단위는 끝난 대로 새 태스크 없이 이어 끝까지 간다`() {
        HostBench().use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.driveUnits("exec-1") { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
            // mimic 은 RPC 마다 접수한 태스크를 집어 든다(ACCEPTED → RUNNING). 앞 인스턴스의 pump 가 그 RPC 를 낸 뒤에 읽는다.
            bench.idlePumps()
            val tasksBefore = bench.tasks(HUMANOID)
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1"), tasksBefore.keys)

            bench.restartHost()
            val after = bench.host.instanceId
            val body = bench.get("/host/executions")
            assertEquals(EXECUTIONS_FIELDS, body.fieldNames().asSequence().toList())
            val restore = body["restore"]
            assertEquals(listOf("at", "rows"), restore.fieldNames().asSequence().toList())
            val row = restore["rows"].single()
            assertEquals(RESTORE_ROW_FIELDS, row.fieldNames().asSequence().toList())
            assertEquals(
                listOf("JO-1", HUMANOID, before, "exec-1", "RESTORED", "exec-1"),
                listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "executionId").map { row[it].asText() },
            )
            assertTrue(row["reason"].isNull)
            assertEquals(listOf(listOf("RESTORED", after, "exec-1", null)), events("JO-1"))

            // 가상 시계를 밀지 않고 pump 가 기체를 다시 관측하면 끝난 단위는 끝난 대로, 도는 단위는 도는 대로 선다. 새 태스크는 없다.
            bench.eventually("단위 다시 관측") { units(bench.execution("exec-1")!!) == mapOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING") }
            val execution = bench.execution("exec-1")!!
            assertEquals(EXECUTION_FIELDS, execution.fieldNames().asSequence().toList())
            assertEquals(listOf("instanceId", "executionId"), execution["restoredFrom"].fieldNames().asSequence().toList())
            assertEquals(JSON.readTree("""{"instanceId":"$before","executionId":"exec-1"}"""), execution["restoredFrom"])
            assertTrue(execution["missionVersion"].isNull)
            assertEquals(tasksBefore, bench.tasks(HUMANOID))

            val done = bench.driveUntil("exec-1", settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            bench.eventually("정착 이벤트") { events("JO-1").size == 2 }
            assertEquals(listOf("SETTLED", after, "exec-1", "PHYSICALLY_DONE"), events("JO-1")[1])
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1", "JO-1#T2.travel", "JO-1#T2"), bench.tasks(HUMANOID).keys)

            // 정착한 일지 행은 다음 기동이 다시 짓지 않는다.
            bench.restartHost()
            val idle = bench.get("/host/executions")
            assertTrue(idle["restore"]["rows"].isEmpty && idle["executions"].isEmpty, idle.toString())
        }
    }

    @Test
    fun `받은 뒤 다른 임무 버전을 활성화하고 재기동해도 받은 때의 임무 정의로 다시 짓는다`() {
        HostBench().use { bench ->
            bench.mimic.server.advance(Duration.ofSeconds(1))
            bench.awaitPump()
            val accepted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            assertEquals(1, bench.activateHold())

            bench.restartHost()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            val execution = bench.execution("exec-1")!!
            assertTrue(execution["missionVersion"].isNull, execution.toString())
            assertEquals(listOf(S01), execution["units"].map { it["unitId"].asText() })
            // 일지에는 받은 때의 임무 버전(코드 정의라 null)이 있다.
            assertNull(journalVersion("JO-1"))
        }
    }

    @Test
    fun `운영자 보류에 선 실행을 두 번 다시 띄워도 새 인스턴스의 첫 보류 응답은 재기동 중복이고 같은 인스턴스의 다음 보류는 송신이다`() {
        HostBench().use { bench ->
            val first = bench.host.instanceId
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.eventually("첫 보류 송신") { log("JO-1").isNotEmpty() }
            assertEquals(listOf(first to "SENT"), log("JO-1").map { it[0] to it[1] })

            bench.restartHost()
            val second = bench.host.instanceId
            val restored = bench.execution("exec-1")!!
            assertEquals(1, restored["missionVersion"].asInt())
            assertEquals(listOf(WAIT, S01), restored["units"].map { it["unitId"].asText() })
            bench.driveToHold("exec-1")
            bench.eventually("둘째 인스턴스의 보류") { log("JO-1").size == 2 }
            assertEquals(second to "RESTART_DUPLICATE", log("JO-1")[1].let { it[0] to it[1] })
            assertEquals("OPERATOR_HOLD", log("JO-1")[1][2])

            bench.restartHost()
            val third = bench.host.instanceId
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(listOf(second, "exec-1"), listOf(row["previousInstanceId"].asText(), row["previousExecutionId"].asText()))
            assertEquals(JSON.readTree("""{"instanceId":"$second","executionId":"exec-1"}"""), bench.execution("exec-1")!!["restoredFrom"])
            bench.driveToHold("exec-1")
            bench.eventually("셋째 인스턴스의 보류") { log("JO-1").size == 3 }
            assertEquals(third to "RESTART_DUPLICATE", log("JO-1")[2].let { it[0] to it[1] })

            // 재작업 판단으로 보류가 걷히면 picasso 가 곧바로 응답 하나를 낸다(`RUNNING`, 운영자 불필요). 이 인스턴스가 이미 적은
            // 작업 지시라 송신이다. 시계를 밀지 않으므로 대기 단위는 기한 전이고 보류 응답은 아직 없다.
            assertEquals("Resolved", bench.resolve("exec-1", WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("재작업 판단 뒤 응답") { log("JO-1").size == 4 }
            val cleared = log("JO-1")[3]
            assertEquals(listOf(third, "SENT", "RUNNING", "false"), listOf(cleared[0], cleared[1], cleared[2], cleared[4]))
            assertEquals(log("JO-1")[0][3], cleared[3])
            assertEquals(log("JO-1")[0][5], cleared[5])

            // 같은 인스턴스 안에서 재작업 뒤 다시 선 보류는 같은 내용이어도 새 사건이라 송신한다.
            bench.driveToHold("exec-1")
            bench.eventually("재작업 뒤 보류") { log("JO-1").size == 5 }
            assertEquals(third to "SENT", log("JO-1")[4].let { it[0] to it[1] })
            assertEquals(log("JO-1")[0].drop(2), log("JO-1")[4].drop(2))

            val view = bench.get("/host/job-responses?jobOrderId=JO-1")["responses"]
            assertEquals(listOf("SENT", "RESTART_DUPLICATE", "RESTART_DUPLICATE", "SENT", "SENT"), view.map { it["disposition"].asText() }.reversed())
            assertEquals(listOf(first, second, third, third, third), view.map { it["instanceId"].asText() }.reversed())
            assertEquals(
                listOf("OPERATOR_HOLD", "OPERATOR_HOLD", "OPERATOR_HOLD", "RUNNING", "OPERATOR_HOLD"),
                view.map { it["physicalState"].asText() }.reversed(),
            )

            // 재기동 중복은 결과 피드에 들어가지 않는다. 피드의 JO-1 이벤트는 송신 행과 하나씩 맞는다.
            val sent = PostgresSupport.queryAll(
                "SELECT instance_id, job_response_id FROM mission.job_response_log WHERE job_order_id = 'JO-1' AND disposition = 'SENT' ORDER BY log_id",
            ) { it.getString(1) to it.getString(2) }
            val feed = bench.get("/host/result-events")["events"].filter { it["snapshot"]["jobOrderId"].asText() == "JO-1" }
            assertEquals(3, sent.size)
            assertEquals(sent, feed.map { it["snapshot"]["instanceId"].asText() to it["snapshot"]["jobResponse"]["jobResponseId"].asText() })
            assertEquals(listOf("OPERATOR_HOLD", "RUNNING", "OPERATOR_HOLD"), feed.map { it["snapshot"]["physicalState"].asText() })
        }
    }

    @Test
    fun `재기동이 다시 연 보류 인시던트는 REOPENED 이고 원래 보류의 열쇠와 처음 선 시각을 이으며 사본도 같은 열쇠를 들고 재작업 뒤 새로 선 보류는 새 열쇠다`() {
        HostBench().use { bench ->
            val first = bench.host.instanceId
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.eventually("첫 보류의 사본") { copies(first) == 1 }
            val opened = bench.get("/host/incidents")["incidents"].single()
            assertEquals("NEW", opened["openingKind"].asText(), opened.toString())
            assertTrue(opened["attempt"].isInt, opened.toString())
            val key = opened["incidentKey"].asText()
            val firstOpenedAt = opened["firstOpenedAt"].asText()
            // 처음 선 인시던트의 처음 선 시각은 그 봉인의 시각이다.
            assertEquals(opened["at"].asText(), firstOpenedAt, opened.toString())

            // 인스턴스 id 는 기동마다 새 값이다(열쇠의 유일성을 진다). 다시 지은 실행은 판단 기록의 보류 판정으로 그 보류를 다시 연다.
            bench.restartHost()
            val second = bench.host.instanceId
            assertNotEquals(first, second)
            bench.driveToHold("exec-1")
            bench.eventually("다시 연 보류") { bench.get("/host/incidents")["incidents"].any { it["held"].asBoolean() } }
            val list = bench.get("/host/incidents")
            val reopened = list["incidents"].single()
            assertEquals(
                listOf("REOPENED", key, firstOpenedAt),
                listOf(reopened["openingKind"].asText(), reopened["incidentKey"].asText(), reopened["firstOpenedAt"].asText()),
                reopened.toString(),
            )
            val detail = bench.get("/host/incidents/${reopened["incidentId"].asText()}")
            assertEquals(IDENTITY_FIELDS, detail.fieldNames().asSequence().toList().takeLast(4))
            assertEquals(listOf(key, firstOpenedAt, "REOPENED"), IDENTITY_FIELDS.take(3).map { detail[it].asText() }, detail.toString())
            // 앞 인스턴스의 사본도 같은 열쇠를 든다. 화면은 이 열쇠로 둘을 잇는다.
            val copy = list["earlier"].single()
            assertEquals(listOf(first, key, "NEW"), listOf(copy["instanceId"].asText(), copy["incidentKey"].asText(), copy["openingKind"].asText()))
            val copyDetail = bench.get("/host/incidents/${copy["incidentId"].asText()}?instanceId=$first")
            assertEquals(listOf(key, firstOpenedAt, "NEW"), IDENTITY_FIELDS.take(3).map { copyDetail[it].asText() }, copyDetail.toString())

            // 다시 연 보류를 재작업한 뒤 또 선 보류는 새 사건이다 — 새 열쇠이고 처음 선 것이다.
            assertEquals("Resolved", bench.resolve("exec-1", WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.driveToHold("exec-1")
            bench.eventually("재작업 뒤 보류") { bench.get("/host/incidents")["incidents"].size() == 2 }
            val again = bench.get("/host/incidents")["incidents"].first()
            assertEquals("NEW", again["openingKind"].asText(), again.toString())
            assertNotEquals(key, again["incidentKey"].asText(), again.toString())
            assertEquals(again["at"].asText(), again["firstOpenedAt"].asText(), again.toString())

            // 이 칸들이 생기기 전에 적은 사본(네 칸 없는 상세 글자)은 넷 다 null 로 읽는다 — 열림의 종류도 모른다.
            val stored = JSON.readTree(
                PostgresSupport.queryOne("SELECT detail::text FROM mission.incident_copy WHERE instance_id = '$first'") { it.getString(1) },
            ) as ObjectNode
            IDENTITY_FIELDS.forEach { stored.remove(it) }
            stored.put("instanceId", "mw-old")
            PostgresSupport.execute(
                """
                INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail)
                VALUES ('mw-old', 'incident-1', 'exec-1', 'JO-0', '$WAIT', '${JSON.writeValueAsString(stored).replace("'", "''")}'::json)
                """.trimIndent(),
            )
            val old = bench.get("/host/incidents/incident-1?instanceId=mw-old")
            assertTrue(IDENTITY_FIELDS.all { old[it].isNull }, old.toString())
            assertTrue(bench.get("/host/incidents")["earlier"].single { it["instanceId"].asText() == "mw-old" }.let { row -> IDENTITY_FIELDS.all { row[it].isNull } })
        }
    }

    @Test
    fun `기체 스냅숏을 못 읽으면 미루고 그 기체를 판정에서 빼며 pump 마다 다시 시도해 읽히면 다시 짓고 그 밖의 거부는 포기한다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.driveUnits("exec-1") { it["T1.travel"] == "RUNNING" }
            // InspectAsset 의 최고 근거 등급은 E0 이라 E2 를 요구하는 일지 행은 다시 짓지 못한다.
            insertJournal("JO-E2", QUADRUPED, evidence = "E2")

            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(listOf("JO-1" to "DEFERRED", "JO-E2" to "GAVE_UP"), rows.map { it["jobOrderId"].asText() to it["result"].asText() })
            assertTrue(rows[0]["reason"].asText().startsWith("기체 스냅숏을 못 읽어"), rows.toString())
            assertTrue(rows[0]["executionId"].isNull)
            assertTrue("요구 근거 등급" in rows[1]["reason"].asText(), rows.toString())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)

            fun reasons(robotId: String) =
                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()["reasons"].map { it.asText() }
            assertEquals(listOf("복원 못 한 실행이 있다: JO-1"), reasons(HUMANOID))
            // 포기한 행의 기체에 그 작업 지시의 태스크가 없으면 곧바로 풀린다.
            assertEquals(emptyList(), reasons(QUADRUPED))

            bench.idlePumps()
            assertEquals(listOf("DEFERRED"), events("JO-1").map { it[0] })
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"][0]["result"].asText())

            bench.blockedSnapshots -= HUMANOID
            bench.eventually("미룬 행 다시 짓기") { bench.get("/host/executions")["restore"]["rows"][0]["result"].asText() == "RESTORED" }
            val after = bench.host.instanceId
            assertEquals(listOf(listOf("DEFERRED", after, null), listOf("RESTORED", after, "exec-1")), events("JO-1").map { it.take(3) })
            val row = bench.get("/host/executions")["restore"]["rows"][0]
            assertEquals(listOf("exec-1", before, "exec-1"), listOf(row["executionId"].asText(), row["previousInstanceId"].asText(), row["previousExecutionId"].asText()))
            assertTrue(row["reason"].isNull)
            assertEquals(JSON.readTree("""{"instanceId":"$before","executionId":"exec-1"}"""), bench.execution("exec-1")!!["restoredFrom"])
            assertEquals(listOf("진행 중인 실행이 있다: exec-1"), reasons(HUMANOID))
        }
    }

    @Test
    fun `포기한 행의 작업 지시 태스크가 사람을 기다리는 NEEDS_INTERVENTION 이면 종료 상태가 아니라 그 기체를 계속 판정에서 뺀다`() {
        HostBench().use { bench ->
            // InspectAsset 의 최고 근거 등급은 E0 이라 E2 를 요구하는 일지 행은 포기한다. 그 작업 지시의 태스크를 기체에 직접 세운다.
            insertJournal("JO-E2", QUADRUPED, evidence = "E2")
            val taskId = "JO-E2#T1"
            val location = ParameterValue.newBuilder().setKey("location").setStringValue("bay-7").build()
            val started = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.start(taskId, 1, "navigate_to", listOf(location)) }
            assertTrue(started is StartOutcome.Accepted, started.toString())
            repeat(20) {
                if (bench.tasks(QUADRUPED)[taskId] != TaskState.RUNNING) {
                    bench.mimic.server.advance(Duration.ofSeconds(1))
                    bench.awaitPump()
                }
            }
            assertEquals(TaskState.RUNNING, bench.tasks(QUADRUPED)[taskId])
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", taskId) }
            assertEquals(TaskState.NEEDS_INTERVENTION, (outcome as ForceOutcome.Raised).taskState, outcome.toString())

            bench.restartHost()
            assertEquals(
                listOf("JO-E2" to "GAVE_UP"),
                bench.get("/host/executions")["restore"]["rows"].map { it["jobOrderId"].asText() to it["result"].asText() },
            )
            fun reasons() =
                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", QUADRUPED)).body!!["robots"].single()["reasons"].map { it.asText() }
            // 스냅숏을 읽을 때마다 같다. 사람을 기다리는 태스크는 종료하지 않은 태스크라 풀지 않는다.
            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-E2"), reasons())
            bench.idlePumps()
            assertEquals(TaskState.NEEDS_INTERVENTION, bench.tasks(QUADRUPED)[taskId])
            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-E2"), reasons())
        }
    }

    @Test
    fun `재작업한 로봇 단위를 재기동하면 @rN 태스크에 다시 붙고 새 태스크를 내지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val accepted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.runningTask(HUMANOID)
            // 슬롯이 근거 윈도우 안에 채워졌으므로 스킬 실패는 실패가 아니라 운영자 보류다(S4a JSON 계약 §8).
            bench.cellBody = STANDARD_CELL.replace(
                """{"id":"$S01","occupied":false,"material":null,"observedAt":null}""",
                """{"id":"$S01","occupied":true,"material":"$MATERIAL","observedAt":null}""",
            )
            bench.awaitPump()
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            bench.driveToHold("exec-1")
            assertEquals("Resolved", bench.resolve("exec-1", S01, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("재작업 태스크") { "JO-1#$S01@r1" in bench.tasks(HUMANOID).keys }
            bench.eventually("재작업 단위 RUNNING") { units(bench.execution("exec-1")!!)[S01] == "RUNNING" }
            val tasksBefore = bench.tasks(HUMANOID)
            assertEquals(setOf("JO-1#$S01", "JO-1#$S01@r1"), tasksBefore.keys)

            bench.restartHost()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            bench.eventually("@r1 에 다시 붙음") { units(bench.execution("exec-1")!!)[S01] == "RUNNING" }
            bench.idlePumps()
            // 같은 태스크 둘뿐이다. @r1 은 재기동 전에 받은 그 태스크가 이어 돈다(ACCEPTED 에서 RUNNING 으로 갔을 수 있다).
            val tasksAfter = bench.tasks(HUMANOID)
            assertEquals(tasksBefore.keys, tasksAfter.keys)
            assertEquals(tasksBefore["JO-1#$S01"], tasksAfter["JO-1#$S01"])
            assertTrue(tasksAfter.getValue("JO-1#$S01@r1") in setOf(TaskState.ACCEPTED, TaskState.RUNNING), tasksAfter.toString())
        }
    }

    private fun HostBench.runningTask(robotId: String): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    /** 실행의 단위가 [done] 을 만족할 때까지 가상 시계를 1초씩 민다(가상 120초 상한). */
    private fun HostBench.driveUnits(executionId: String, done: (Map<String, String>) -> Boolean) {
        repeat(120) {
            if (done(units(checkNotNull(execution(executionId))))) return
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
        }
        error("단위가 바라는 상태에 이르지 않았다: ${execution(executionId)}")
    }

    /** mimic 이 호스팅하는 태스크 id 와 상태. */
    private fun HostBench.tasks(robotId: String): Map<String, TaskState> =
        mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.associate { it.taskId to it.machine.state } }

    private companion object {
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

        val EXECUTIONS_FIELDS = listOf("instanceId", "pumpedAt", "executions", "restore", "journalBlockedExecutionIds")

        val EXECUTION_FIELDS = listOf(
            "executionId", "jobOrderId", "workMasterId", "missionVersion", "robotId", "physicalState", "units", "jobResponse", "restoredFrom", "cancel",
            "permitBlock", "blockedFaults", "releases",
        )

        /** 인시던트 목록 줄과 상세의 끝 네 칸(picasso §15.322). */
        val IDENTITY_FIELDS = listOf("incidentKey", "firstOpenedAt", "openingKind", "attempt")

        fun copies(instanceId: String): Int =
            PostgresSupport.queryOne("SELECT count(*) FROM mission.incident_copy WHERE instance_id = '$instanceId'") { it.getInt(1) }

        val RESTORE_ROW_FIELDS = listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "executionId", "reason", "close")

        fun units(execution: JsonNode): Map<String, String> = execution["units"].associate { it["unitId"].asText() to it["state"].asText() }

        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

        /** 송신 기록(인스턴스, 처분, 물리 상태, 미완 단위, 운영자 필요, 도달 근거 등급). 적은 순서다. */
        fun log(jobOrderId: String): List<List<String>> = PostgresSupport.queryAll(
            """
            SELECT instance_id, disposition, physical_state, incomplete_units::text, operator_required::text, reached_evidence
            FROM mission.job_response_log WHERE job_order_id = '$jobOrderId' ORDER BY log_id
            """.trimIndent(),
        ) { (1..6).map { i -> it.getString(i) } }

        fun journalVersion(jobOrderId: String): Int? =
            PostgresSupport.queryOne("SELECT mission_version FROM mission.execution_journal WHERE job_order_id = '$jobOrderId'") { it.getObject(1) as Int? }

        fun insertJournal(jobOrderId: String, robotId: String, evidence: String) {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7", evidence = evidence)))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', NULL, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
        }
    }
}
