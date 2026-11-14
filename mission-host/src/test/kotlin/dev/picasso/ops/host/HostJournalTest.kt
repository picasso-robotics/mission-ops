package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.web.HostController
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 실행 일지, 송신 기록, 인시던트 사본, 판단의 인스턴스, 다시 짓지 못한 기체의 판정 제외(S4b 스펙 §6, T2·T4·T6·T7·T8).
 * 기동 복원 자체(RESTORED·DEFERRED 와 재기동 중복)는 [HostRestoreTest] 가 본다.
 */
class HostJournalTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    @Test
    fun `새 실행의 ACCEPTED 는 응답 전에 일지 행을 적고 UNASSIGNED 와 놓은 작업 지시의 REJECTED 는 적지 않으며 정착하면 정착 이벤트를 하나 적는다`() {
        HostBench().use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            // 응답을 받은 때 이미 있다.
            val row = journal().single()
            assertEquals(
                listOf("JO-1", HUMANOID, "InspectAsset", null, bench.host.instanceId, "exec-1"),
                listOf(row["job_order_id"], row["robot_id"], row["work_master_id"], row["mission_version"], row["instance_id"], row["execution_id"]),
            )
            assertEquals(JSON.readTree(inspect("JO-1", "T1" to "bay-7")), JSON.readTree(row["job_order"] as String))
            assertTrue(events("JO-1").isEmpty())

            val unassigned = bench.post("/host/job-orders", request(rack("JO-2", "RACK-204.S01"), "candidates", QUADRUPED)).body!!
            assertEquals("UNASSIGNED", unassigned["result"].asText())

            val done = bench.driveUntil("exec-1", settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            bench.eventually("정착 이벤트") { events("JO-1").isNotEmpty() }
            assertEquals(listOf(listOf("SETTLED", bench.host.instanceId, "exec-1", "PHYSICALLY_DONE")), events("JO-1"))

            val again = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
            bench.idlePumps()
            assertEquals(listOf("JO-1"), journal().map { it["job_order_id"] })
            assertEquals(1, events("JO-1").size)

            // 송신 기록: 응답마다 한 행이고 모두 송신이다. 최신 행이 실행의 마지막 작업 응답이다(ack 는 트랜잭션 시험이 본다).
            val log = bench.get("/host/job-responses?jobOrderId=JO-1")
            assertEquals(listOf("instanceId", "total", "responses"), log.fieldNames().asSequence().toList())
            val rows = log["responses"].toList()
            assertTrue(rows.isNotEmpty())
            assertEquals(rows.size, log["total"].asInt())
            assertEquals(rows.size, rows.map { it["jobResponseId"].asText() }.distinct().size)
            assertTrue(rows.all { it["disposition"].asText() == "SENT" && it["instanceId"].asText() == bench.host.instanceId }, rows.toString())
            assertEquals(LOG_FIELDS, rows[0].fieldNames().asSequence().toList())
            assertEquals(bench.execution("exec-1")!!["jobResponse"]["jobResponseId"], rows[0]["jobResponseId"])
            assertEquals(
                listOf("PHYSICALLY_DONE", "E0", "E0", listOf("T1", "T1.travel"), false, "exec-1", 1),
                listOf(
                    rows[0]["physicalState"].asText(), rows[0]["requiredEvidence"].asText(), rows[0]["reachedEvidence"].asText(),
                    rows[0]["completedUnits"].map { it.asText() }, rows[0]["operatorRequired"].asBoolean(), rows[0]["executionId"].asText(),
                    rows[0]["version"].asInt(),
                ),
            )
            assertTrue(rows[0]["incompleteUnits"].isArray && rows[0]["incompleteUnits"].isEmpty)
            assertTrue(rows[0]["recordedAt"].isTextual)
            val feed=bench.get("/host/result-events")
            assertEquals(rows.size,feed["events"].size())
            assertEquals(rows[0]["jobResponseId"],feed["events"].last()["snapshot"]["jobResponse"]["jobResponseId"])
            assertEquals(HUMANOID,feed["events"].last()["snapshot"]["robotId"].asText())
            bench.restartHost()
            assertEquals(feed,bench.get("/host/result-events"))
            assertEquals(0, bench.get("/host/job-responses?jobOrderId=JO-2")["total"].asInt())
            assertEquals(1, bench.get("/host/job-responses?limit=1")["responses"].size())
            listOf("?limit=0", "?limit=501", "?limit=x", "?jobOrderId=").forEach { query ->
                val reply = bench.fetch("/host/job-responses$query")
                assertEquals(400, reply.status, query)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), query)
            }
        }
    }

    @Test
    fun `실패한 실행에 새 버전을 내 revise 로 ACCEPTED 여도 일지 행을 더 적지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val first = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", first["result"].asText(), first.toString())
            val running = bench.runningTask(HUMANOID)
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            assertEquals("FAILED", bench.driveUntil("exec-1", settled)["physicalState"].asText())
            // 든 채 실패라 그 기체에 잔여 파지 표시가 서고(P0-b2) 같은 작업 지시의 리비전도 배정에서 빠진다. 사람이 빈손을 확인한 뒤 낸다.
            bench.eventually("잔여 파지 표시") { bench.get("/host/robots/residual-holds")["holds"].size() == 1 }
            val markerId = bench.get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
            // mimic 손은 든 채라 첫 확인은 어긋남이다(B2). 현장 확인을 지목해 다시 확인한다.
            val cleared = bench.clearHeldCiting(HUMANOID, markerId)
            assertEquals(200, cleared.status, cleared.body.toString())

            val revised =bench.post("/host/job-orders", request(rack("JO-1", S01).replace("\"version\":1", "\"version\":2"), "candidates", HUMANOID))
            assertEquals(200, revised.status, revised.body.toString())
            assertEquals("ACCEPTED", revised.body!!["result"].asText(), revised.body.toString())
            assertEquals("exec-1", revised.body["executionId"].asText())
            assertEquals(1, journal().size)
            assertEquals("exec-1", journal().single()["execution_id"])
        }
    }

    @Test
    fun `일지 쓰기가 실패하면 제출은 500이고 pump와 신규 제출을 차단해 로봇 태스크를 시작하지 않는다`() {
        HostBench().use { bench ->
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_journal() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'journal refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute("CREATE TRIGGER refuse_journal BEFORE INSERT ON mission.execution_journal FOR EACH ROW EXECUTE FUNCTION mission.refuse_journal()")
            val failed = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals(500, failed.status, failed.body.toString())
            assertEquals(HostController.JOURNAL_WRITE_FAILED, failed.body!!["error"].asText(), failed.body.toString())
            assertTrue("exec-1" in failed.body["detail"].asText(), failed.body.toString())
            assertTrue(journal().isEmpty())
            // 실행은 미들웨어에 남는다(스펙 §9, 한계). 일지에 없으니 다음 기동은 다시 짓지 않는다.
            val execution = checkNotNull(bench.execution("exec-1")) { bench.get("/host/executions").toString() }
            assertEquals(listOf("JO-1", HUMANOID), listOf(execution["jobOrderId"].asText(), execution["robotId"].asText()))
            bench.idlePumps(5)
            assertEquals(listOf("exec-1"), bench.get("/host/executions")["journalBlockedExecutionIds"].map { it.asText() })
            assertTrue(bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.isEmpty() })
            val blocked = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("REJECTED", blocked["result"].asText())
            bench.restartHost()
            assertTrue(bench.get("/host/executions")["restore"]["rows"].isEmpty)
        }
    }

    @Test
    fun `pump 뒤 기록은 한 트랜잭션이라 사본 쓰기가 실패하면 송신 기록도 남지 않고 ack 하지 않아 다음 pump 에 한 번씩 적는다`() {
        HostBench().use { bench ->
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_copy() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'copy refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute("CREATE TRIGGER refuse_copy BEFORE INSERT ON mission.incident_copy FOR EACH ROW EXECUTE FUNCTION mission.refuse_copy()")
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.idlePumps()
            assertEquals(0, count("job_response_log"))
            assertEquals(0, count("result_outbox"))
            assertEquals(0, count("incident_copy"))

            PostgresSupport.execute("DROP TRIGGER refuse_copy ON mission.incident_copy")
            bench.eventually("사본") { count("incident_copy") == 1 }
            val incidentId = bench.get("/host/incidents")["incidents"].single()["incidentId"].asText()
            val copy = PostgresSupport.queryAll("SELECT instance_id, incident_id, execution_id, job_order_id, unit_id, detail::text FROM mission.incident_copy") {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6))
            }.single()
            assertEquals(listOf(bench.host.instanceId, incidentId, executionId, "JO-1", WAIT), copy.take(5))
            assertEquals(bench.get("/host/incidents/$incidentId"), JSON.readTree(copy[5]))
            val logged = bench.get("/host/job-responses?jobOrderId=JO-1")["responses"].map { it["jobResponseId"].asText() }
            assertTrue(logged.isNotEmpty())
            assertEquals(logged.distinct(), logged)
            assertEquals(bench.execution(executionId)!!["jobResponse"]["jobResponseId"].asText(), logged.first())
            assertEquals(0, count("incident_copy_resolution"))

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("판단 행") { count("incident_copy_resolution") == 1 }
            val resolution = PostgresSupport.queryAll(
                "SELECT instance_id, incident_id, decision, decided_by_id, decided_by_kind FROM mission.incident_copy_resolution",
            ) { (1..5).map { i -> it.getString(i) } }.single()
            assertEquals(listOf(bench.host.instanceId, incidentId, "REWORK", "kim", "PERSON"), resolution)
            bench.idlePumps()
            assertEquals(1, count("incident_copy"))
            assertEquals(1, count("incident_copy_resolution"))
        }
    }

    @Test
    fun `재기동 뒤 이전 인스턴스의 인시던트와 판단은 earlier 와 instanceId 상세에만 있고 그 인스턴스를 실은 판단은 409 다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("판단 행") { count("incident_copy_resolution") == 1 }
            val before = bench.host.instanceId
            val beforeList = bench.get("/host/incidents")
            assertEquals(0, beforeList["earlierTotal"].asInt())
            assertTrue(beforeList["earlier"].isArray && beforeList["earlier"].isEmpty)
            val sealed = bench.get("/host/incidents/incident-1")

            bench.restartHost()
            val after = bench.host.instanceId
            assertNotEquals(before, after)
            val list = bench.get("/host/incidents")
            assertEquals(listOf("instanceId", "total", "incidents", "earlierTotal", "earlier"), list.fieldNames().asSequence().toList())
            assertEquals(after, list["instanceId"].asText())
            assertEquals(1, list["earlierTotal"].asInt())
            val earlier = list["earlier"].single()
            assertEquals(listOf("instanceId") + LIST_FIELDS, earlier.fieldNames().asSequence().toList())
            assertEquals(listOf(before, "incident-1", executionId, "JO-1"), listOf("instanceId", "incidentId", "executionId", "jobOrderId").map { earlier[it].asText() })
            assertEquals("REWORK", earlier["resolution"]["decision"].asText())
            assertEquals(JSON.readTree("""{"id":"kim","kind":"PERSON"}"""), earlier["resolution"]["decidedBy"])
            assertEquals(listOf(true, false, false), listOf(earlier["unresolved"].asBoolean(), earlier["held"].asBoolean(), earlier["confirmedWithoutEvidence"].asBoolean()))
            assertEquals(1, earlier["missionVersion"].asInt())
            assertEquals(30L, earlier["evidenceBeforeSeconds"].asLong())

            val detail = bench.get("/host/incidents/incident-1?instanceId=$before")
            assertEquals(DETAIL_FIELDS, detail.fieldNames().asSequence().toList())
            assertEquals(before, detail["instanceId"].asText())
            assertEquals(false, detail["held"].asBoolean())
            assertTrue(detail["unitState"].isNull, detail.toString())
            assertEquals("REWORK", detail["resolution"]["decision"].asText())
            assertEquals(sealed["intent"], detail["intent"])
            assertEquals(sealed["evidenceWindow"], detail["evidenceWindow"])

            listOf("", "?instanceId=$after", "?instanceId=mw-unknown").forEach { query ->
                val missing = bench.fetch("/host/incidents/incident-1$query")
                assertEquals(404, missing.status, query)
                assertEquals("INCIDENT_NOT_FOUND", missing.body!!["error"].asText())
            }
            assertEquals(400, bench.fetch("/host/incidents/incident-1?instanceId=").status)

            val stale = bench.post(
                "/host/executions/$executionId/units/$WAIT/resolve",
                JSON.writeValueAsString(linkedMapOf("decision" to "CONFIRM_DONE", "approverId" to "kim", "instanceId" to before)),
            )
            assertEquals(409, stale.status)
            assertEquals("INSTANCE_MISMATCH", stale.body!!["error"].asText())
        }
    }

    @Test
    fun `포기한 일지 행의 기체는 그 작업 지시의 고아 태스크가 끝날 때까지 판정에서 빠지고 스냅숏을 못 읽는 동안은 계속 빠진다`() {
        HostBench(gated = true).use { bench ->
            // 이전 인스턴스가 받았으나 그 임무 버전 행이 없어 다시 짓지 못하는 일지 행 둘. 하나는 고아 태스크가 도는 humanoid-01 이다.
            listOf("JO-ORPHAN" to HUMANOID, "JO-GHOST" to GHOST).forEach { (jobOrderId, robotId) -> insertJournal(jobOrderId, robotId, missionVersion = 9) }
            val started = bench.clientPort("orphan").start(HUMANOID, "JO-ORPHAN#T1.travel", 1, "navigate_to", mapOf("location" to "bay-7"))
            assertTrue(started.hasHandle(), started.toString())

            bench.restartHost()
            val restore = bench.get("/host/executions")["restore"]
            assertEquals(listOf("JO-ORPHAN", "JO-GHOST"), restore["rows"].map { it["jobOrderId"].asText() })
            assertTrue(restore["rows"].all { it["result"].asText() == "GAVE_UP" && "임무 버전 행이 없다" in it["reason"].asText() }, restore.toString())
            assertEquals(listOf("mw-before", "exec-7"), listOf(restore["rows"][0]["previousInstanceId"].asText(), restore["rows"][0]["previousExecutionId"].asText()))
            assertEquals(listOf("GAVE_UP"), events("JO-ORPHAN").map { it[0] })

            fun judged(robotId: String): JsonNode =
                bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()

            val orphaned = judged(HUMANOID)
            assertEquals(false, orphaned["passed"].asBoolean())
            assertEquals(listOf("복원 못 한 실행이 있다: JO-ORPHAN"), orphaned["reasons"].map { it.asText() })
            assertTrue(orphaned["runningExecutionId"].isNull)
            assertTrue(judged(GHOST)["reasons"].any { it.asText() == "복원 못 한 실행이 있다: JO-GHOST" })

            // 고아 태스크가 끝나도 스냅숏을 못 읽는 동안은 계속 뺀다.
            bench.blockedSnapshots += HUMANOID
            repeat(6) { bench.mimic.server.advance(Duration.ofSeconds(5)) }
            val terminal = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.single().machine.state }
            assertEquals(TaskState.SUCCEEDED, terminal)
            assertEquals(listOf("복원 못 한 실행이 있다: JO-ORPHAN"), judged(HUMANOID)["reasons"].map { it.asText() })

            bench.blockedSnapshots -= HUMANOID
            val released = judged(HUMANOID)
            assertEquals(true, released["passed"].asBoolean(), released.toString())
            // 풀린 행은 다시 빼지 않는다. 스냅숏을 다시 못 읽어도 그렇다.
            bench.blockedSnapshots += HUMANOID
            assertEquals(true, judged(HUMANOID)["passed"].asBoolean())
            val submitted = bench.post("/host/job-orders", request(inspect("JO-9", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())

            // 다음 기동은 포기한 행을 다시 짓지 않는다.
            bench.restartHost()
            assertEquals(listOf("JO-9"), bench.get("/host/executions")["restore"]["rows"].map { it["jobOrderId"].asText() })
            assertEquals(listOf("GAVE_UP"), events("JO-ORPHAN").map { it[0] })
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

    private companion object {
        const val PSR = "PrepareSequencedRack"
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

        val LOG_FIELDS = listOf(
            "instanceId", "jobResponseId", "jobOrderId", "executionId", "version", "physicalState", "requiredEvidence", "reachedEvidence",
            "completedUnits", "unverifiedUnits", "incompleteUnits", "inDoubtUnits", "operatorRequired", "residualHold", "blockedBy",
            "disposition", "recordedAt",
        )

        val LIST_FIELDS = listOf(
            "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "failureClass", "route", "missionVersion",
            "siteSettingsVersion", "evidenceBeforeSeconds", "evidenceAfterSeconds", "inDoubtGraceSeconds", "stallWindowSeconds",
            "unresolved", "resolution", "fault", "held", "confirmedWithoutEvidence",
            "incidentKey", "firstOpenedAt", "openingKind", "attempt",
        )

        val DETAIL_FIELDS = listOf(
            "instanceId", "incidentId", "executionId", "jobOrderId", "robotId", "unitId", "at", "wallClockAt", "failureClass", "route",
            "unresolved", "resolution", "held", "confirmedWithoutEvidence", "unitState", "fault", "blockedBy", "requiredEvidence",
            "reachedEvidence", "verification", "step", "evidenceWindow", "windowTruncated", "preconditionSubjects", "expectedHold",
            "observedHold", "effectMismatch", "linkBroken", "intent",
            "incidentKey", "firstOpenedAt", "openingKind", "attempt",
        )

        fun journal(): List<Map<String, Any?>> = PostgresSupport.queryAll(
            "SELECT job_order_id, robot_id, job_order::text, work_master_id, mission_version, instance_id, execution_id FROM mission.execution_journal ORDER BY journal_id",
        ) {
            linkedMapOf(
                "job_order_id" to it.getString(1), "robot_id" to it.getString(2), "job_order" to it.getString(3),
                "work_master_id" to it.getString(4), "mission_version" to it.getObject(5), "instance_id" to it.getString(6),
                "execution_id" to it.getString(7),
            )
        }

        /** 그 작업 지시의 일지 이벤트(종류, 인스턴스, 실행 id, 사유). */
        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

        fun count(table: String): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.$table") { it.getInt(1) }

        /** 이전 인스턴스가 받은 일지 행을 직접 넣는다. 작업 지시는 InspectAsset 하나다. */
        fun insertJournal(jobOrderId: String, robotId: String, missionVersion: Int?, evidence: String = "E0") {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7", evidence = evidence)))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', ${missionVersion ?: "NULL"}, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
        }
    }
}

/** 운영자 보류 대기 템플릿을 초안 저장, 모의 실행, 활성화한다. 버전 번호를 돌려준다. */
internal fun HostBench.activateHold(robots: List<String> = listOf(HUMANOID, QUADRUPED)): Int {
    val definition = get("/host/missions/templates/PrepareSequencedRack")["templates"].single { it["id"].asText() == "ARRIVAL_WAIT_HOLD" }["definition"].asText()
    val saved = post("/host/missions/PrepareSequencedRack/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to "${UUID.randomUUID()}")))
    val draftId = saved.body!!["draft"]["draftId"].asLong()
    val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to "${UUID.randomUUID()}")))
    assertEquals("PASSED", mock.body!!["result"].asText(), mock.body.toString())
    val activation = post(
        "/host/missions/drafts/$draftId/activate",
        JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "보류 시연", "robotIds" to robots, "requestId" to "${UUID.randomUUID()}")),
    )
    assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
    return activation.body["version"].asInt()
}

/** 셀 대역을 한 번 읽힌 뒤 보류 버전을 활성화하고 슬롯 하나의 작업 지시를 낸다. 실행 id 를 돌려준다. */
internal fun HostBench.submitHeldOrder(jobOrderId: String): String {
    mimic.server.advance(Duration.ofSeconds(1))
    awaitPump()
    activateHold()
    val submitted = post("/host/job-orders", request(rack(jobOrderId, "RACK-204.S01"), "candidates", HUMANOID)).body!!
    assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
    return submitted["executionId"].asText()
}

/** 실행이 운영자 보류가 될 때까지 민다. 기한 20초이므로 가상 시간 30초(5초씩 여섯 번) 안에 서야 한다. */
internal fun HostBench.driveToHold(executionId: String): JsonNode {
    val held = driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL", "OPERATOR_HOLD"), rounds = 6)
    assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), held.toString())
    return held
}

/** 지금 인스턴스를 실은 판단 요청. */
internal fun HostBench.resolve(executionId: String, unitId: String, decision: String, approverId: String): HostBench.Reply =
    post(
        "/host/executions/$executionId/units/$unitId/resolve",
        JSON.writeValueAsString(linkedMapOf("decision" to decision, "approverId" to approverId, "instanceId" to host.instanceId)),
    )
