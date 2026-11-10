package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.ApprovedStep
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.JudgmentRecord
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.OperatorDecision
import dev.picasso.middleware.RecordedApproval
import dev.picasso.middleware.UnitState
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.STANDARD_CELL
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.JudgmentRecordRow
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.registry.PostgresSupport
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 판단 기록의 영속과 복원(picasso 한계 §15.221 · 판단 기록 영속, HA 설계 기록 §8 단계 0). 실행 호스트가 수락·판정·운영자 판단·정착
 * 뒤에 `Middleware.judgmentRecord` 를 `mission.execution_judgment_record` 에 덧붙이고, 재기동 때 가장 최근 기록을 `resume` 에 넘긴다.
 *
 * 호스트만 다시 띄우고 mimic 과 DB 는 그대로다(S4b 의 재기동과 같은 배선). pump 를 멈추고 볼 때는 호스트 잠금을 쥔다
 * ([MissionHost.exclusive]) — 그 안에서 부른 제출·판단은 같은 스레드라 잠금을 다시 잡고, pump 는 그동안 기다린다.
 *
 * 조치 승인은 이 호스트가 부르지 않으므로(그 REST 가 없다) 이전 인스턴스가 적었을 기록을 DB 에 직접 넣어 본다. 결함 감수는 호스트 REST
 * (`POST /host/executions/{executionId}/release`)로 건다.
 */
class JudgmentRecordHostTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED")

    @Test
    fun `판단 기록은 수락 직후와 판정 뒤와 운영자 판단 직후에 적고 바뀌지 않으면 더 적지 않으며 정착하는 pump 가 마지막 판정을 적는다`() {
        HostBench().use { bench ->
            bench.mimic.server.advance(Duration.ofSeconds(1))
            bench.awaitPump()
            bench.activateHold()
            val executionId = bench.host.exclusive {
                val outcome = bench.host.submit(HostRequests.jobOrder(JSON.readTree(rack("JO-1", S01))), listOf(HUMANOID))
                assertEquals(SubmitResult.ACCEPTED, outcome.result, outcome.toString())
                // pump 가 잠금을 기다리는 동안이다. 수락이 직접 적었다.
                val accepted = bench.records("JO-1").single()
                assertEquals(
                    listOf(bench.host.instanceId, outcome.executionId, JudgmentRecordWire.SCHEMA_VERSION),
                    listOf(accepted.instanceId, accepted.executionId, accepted.wireVersion),
                )
                val first = decode(accepted)
                assertEquals("JO-1" to 1, first.jobOrderId to first.orderVersion)
                assertEquals(listOf(WAIT, S01), first.units.map { it.unitId })
                assertTrue(first.units.all { it.verdict == null && it.decision == null }, first.toString())
                outcome.executionId!!
            }

            bench.driveToHold(executionId)
            bench.eventually("보류 판정 기록") { bench.latest("JO-1").units.first().verdict?.state == UnitState.OPERATOR_HOLD }
            val held = bench.records("JO-1").size
            bench.idlePumps()
            assertEquals(held, bench.records("JO-1").size, "바뀌지 않은 기록을 다시 적었다")

            bench.host.exclusive {
                val resolved = bench.host.resolve(executionId, WAIT, OperatorDecision.CONFIRM_DONE, Approver("kim", ApproverKind.PERSON), null)
                assertEquals(MissionHost.RESOLVED, resolved.result, resolved.toString())
                assertEquals(held + 1, bench.records("JO-1").size, "운영자 판단 직후 pump 없이 적는다")
                val decided = bench.latest("JO-1").units.first()
                val decision = assertNotNull(decided.decision)
                assertEquals(listOf(OperatorDecision.CONFIRM_DONE, "kim", ApproverKind.PERSON), listOf(decision.decision, decision.approver.id, decision.approver.kind))
                assertEquals(UnitState.DONE, decided.verdict?.state)
            }

            val done = bench.driveUntil(executionId, settled)
            bench.eventually("정착 이벤트") {
                PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event WHERE job_order_id = 'JO-1' AND kind = 'SETTLED'") { it.getInt(1) } == 1
            }
            // 정착한 pump 가 같은 트랜잭션에서 마지막 판정을 적었다.
            assertEquals(done["units"].map { it["state"].asText() }, bench.latest("JO-1").units.map { it.verdict?.state?.name }, done.toString())
            val count = bench.records("JO-1").size
            bench.idlePumps()
            assertEquals(count, bench.records("JO-1").size, "정착 뒤에 더 적었다")
        }
    }

    @Test
    fun `운영자가 확인한 로봇 단위의 보류는 재기동 뒤 다시 묻지 않고 확인한 근거 등급 그대로다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val accepted = bench.post("/host/job-orders", request(rack("JO-1", S01, S02), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.runningTask(HUMANOID)
            assertEquals("JO-1#$S01", running)
            // 슬롯이 근거 윈도우 안에 채워졌으므로 스킬 실패는 실패가 아니라 운영자 보류다(S4a JSON 계약 §8).
            bench.cellBody = STANDARD_CELL.replace(
                """{"id":"$S01","occupied":false,"material":null,"observedAt":null}""",
                """{"id":"$S01","occupied":true,"material":"$MATERIAL","observedAt":null}""",
            )
            bench.awaitPump()
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            bench.driveToHold("exec-1")
            assertEquals("Resolved", bench.resolve("exec-1", S01, "CONFIRM_DONE", "kim").body!!["result"].asText())
            bench.eventually("S02 태스크") { "JO-1#$S02" in bench.tasks(HUMANOID) }
            assertEquals(mapOf(S01 to ("DONE" to "E2")), units(bench.execution("exec-1")!!).filterKeys { it == S01 })

            bench.restartHost()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            bench.idlePumps(6)
            val restored = bench.execution("exec-1")!!
            // 기록 없이 다시 지으면 S01 의 실패한 태스크와 채워진 슬롯으로 다시 보류에 선다. 기록의 판정은 다시 판정하지 않는다.
            assertEquals("DONE" to "E2", units(restored).getValue(S01), restored.toString())
            assertTrue(restored["physicalState"].asText() != "OPERATOR_HOLD", restored.toString())
            assertTrue(bench.get("/host/incidents")["incidents"].none { it["unitId"].asText() == S01 }, "확인한 단위의 사건을 다시 열었다")
            val decision = assertNotNull(bench.latestAny("JO-1").units.first { it.unitId == S01 }.decision, "기록에서 판단이 사라졌다")
            assertEquals(OperatorDecision.CONFIRM_DONE, decision.decision)
        }
    }

    @Test
    fun `재작업 판단 뒤 재기동해도 설비 대기 기한은 재기동 때가 아니라 재작업으로 다시 기다리기 시작한 때부터 이어 잰다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            bench.eventually("재작업 대기 시작") { units(bench.execution(executionId)!!)[WAIT]?.first == "RUNNING" }
            val waitedFrom = assertNotNull(bench.latest("JO-1").units.first().requestedAt, "재작업한 대기의 시작 시각이 기록에 없다")
            assertEquals(1, bench.latest("JO-1").units.first().attempt)
            bench.mimic.server.advance(Duration.ofSeconds(10))
            bench.awaitPump()
            assertEquals("RUNNING", units(bench.execution(executionId)!!).getValue(WAIT).first)

            bench.restartHost()
            val restartedAt = bench.now()
            assertEquals("RESTORED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            bench.eventually("대기 다시 섬") { units(bench.execution("exec-1")!!)[WAIT]?.first == "RUNNING" }
            // 재작업 뒤 22초, 재기동 뒤 12초. 기한(20초)을 재기동 때부터 쟀다면 아직 돈다.
            bench.mimic.server.advance(Duration.ofSeconds(12))
            bench.awaitPump()
            bench.eventually("기한 보류") { units(bench.execution("exec-1")!!).getValue(WAIT).first == "OPERATOR_HOLD" }
            assertTrue(Duration.between(restartedAt, bench.now()) < Duration.ofSeconds(20), "${bench.now()}")
            assertTrue(Duration.between(waitedFrom, bench.now()) >= Duration.ofSeconds(20), "$waitedFrom ${bench.now()}")
        }
    }

    @Test
    fun `감수한 결함을 실은 기록으로 다시 지으면 그 결함이 다음 단위를 다시 막지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val running = bench.runningTask(QUADRUPED)
            // 지울 때까지 유지되고 새 태스크를 받지 못하게 하는 로봇 수준 결함이다.
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            val blocked = bench.driveUntil("exec-1", settled + "OPERATOR_HOLD", rounds = 4)
            assertEquals("OPERATOR_HOLD", blocked["physicalState"].asText(), blocked.toString())
            val fault = bench.get("/host/incidents/${bench.get("/host/incidents")["incidents"].first()["incidentId"].asText()}")["blockedBy"].single()
            val key = fault["errorType"].asText() + fault["references"].joinToString(",", "[", "]") { "${it["key"].asText()}=${it["value"].asText()}" }

            // 대조: 감수 없는 기록으로 다시 지으면 같은 결함이 다시 막는다.
            bench.restartHost()
            bench.eventually("다시 막힘") { bench.execution("exec-1")?.get("physicalState")?.asText() == "OPERATOR_HOLD" }

            // 운영자가 이 인스턴스에서 감수한다(호스트 REST). 감수 직후의 판단 기록이 감수한 결함의 열쇠를 싣는다.
            val again = bench.execution("exec-1")!!
            val released = bench.post(
                "/host/executions/exec-1/release",
                JSON.writeValueAsString(
                    linkedMapOf(
                        "approverId" to "kim", "reason" to "재측위 확인", "instanceId" to bench.host.instanceId,
                        "sawFaults" to again["blockedFaults"].map { mapOf("errorType" to it["errorType"].asText(), "references" to it["references"]) },
                    ),
                ),
            ).body!!
            assertEquals("RELEASED", released["result"].asText(), released.toString())
            val releasedBy = bench.host.instanceId
            bench.restartHost()
            bench.idlePumps(6)
            val resumed = bench.execution("exec-1")!!
            assertEquals("RUNNING", resumed["physicalState"].asText(), resumed.toString())
            assertEquals(listOf(key), bench.latestAny("JO-1").acknowledgedFaults)
            // 감수 이력은 작업 지시 id 로 읽어 앞 인스턴스의 감수도 싣는다.
            assertEquals(listOf("kim" to "재측위 확인"), resumed["releases"].map { it["approverId"].asText() to it["reason"].asText() }, resumed.toString())
            assertEquals(
                listOf(releasedBy),
                PostgresSupport.queryAll("SELECT instance_id FROM mission.execution_fault_release WHERE job_order_id = 'JO-1'") { it.getString(1) },
            )
        }
    }

    @Test
    fun `승인 소모를 실은 기록으로 다시 지으면 승인한 조치 열이 계획 앞에 다시 선다`() {
        HostBench().use { bench ->
            insertJournal("JO-A", HUMANOID)
            val place = mapOf("object_id" to MATERIAL, "destination" to S01)
            val record = JudgmentRecord(
                jobOrderId = "JO-A",
                orderVersion = 1,
                acknowledgedFaults = emptyList(),
                approval = RecordedApproval(
                    HUMANOID, Approver("kim", ApproverKind.PERSON), Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-14T00:00:01Z"),
                    listOf(ApprovedStep("pick_place", place, REMEDY)),
                ),
                units = emptyList(),
                cancelRequested = false,
            )
            insertRecord("JO-A", "mw-before", "exec-7", JudgmentRecordWire.encode(record))

            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("RESTORED", row["result"].asText(), row.toString())
            val execution = bench.execution(row["executionId"].asText())!!
            assertEquals(listOf(REMEDY to "pick_place", "T1.travel" to "navigate_to", "T1" to "inspect"), execution["units"].map { it["unitId"].asText() to it["skillType"].asText() })
            val approval = assertNotNull(bench.latestAny("JO-A").approval, "새 인스턴스의 기록에서 승인 소모가 사라졌다")
            assertEquals("kim" to listOf(REMEDY), approval.approver.id to approval.steps.map { it.unitId })
        }
    }

    @Test
    fun `판단 기록을 읽지 못하면 기록 없이 다시 짓지 않고 기록 행 번호와 함께 포기로 보고한다`() {
        HostBench().use { bench ->
            insertJournal("JO-1", HUMANOID)
            val broken = insertRecord("JO-1", "mw-before", "exec-7", "{")
            insertJournal("JO-2", QUADRUPED)
            val future = insertRecord("JO-2", "mw-before", "exec-8", """{"schemaVersion":"9"}""")

            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(listOf("JO-1" to "GAVE_UP", "JO-2" to "GAVE_UP"), rows.map { it["jobOrderId"].asText() to it["result"].asText() }, rows.toString())
            assertTrue(rows[0]["reason"].asText().startsWith("${MissionHost.RECORD_UNREADABLE}: record=$broken, "), rows.toString())
            assertTrue(rows[1]["reason"].asText().startsWith("${MissionHost.RECORD_UNREADABLE}: record=$future, "), rows.toString())
            assertTrue("판" in rows[1]["reason"].asText(), rows.toString())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            assertEquals(
                listOf("GAVE_UP"),
                PostgresSupport.queryAll("SELECT kind FROM mission.execution_journal_event WHERE job_order_id = 'JO-1' ORDER BY event_id") { it.getString(1) },
            )
        }
    }

    @Test
    fun `다른 버전의 작업 지시에 선 기록이면 picasso 의 RESUME_RECORD_MISMATCH 거부를 기록 행 번호와 함께 포기로 보고한다`() {
        HostBench().use { bench ->
            insertJournal("JO-1", HUMANOID)
            val other = JudgmentRecord("JO-1", 2, emptyList(), null, emptyList(), cancelRequested = false)
            val id = insertRecord("JO-1", "mw-before", "exec-7", JudgmentRecordWire.encode(other))

            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("GAVE_UP", row["result"].asText(), row.toString())
            assertTrue(row["reason"].asText().startsWith(Middleware.RESUME_RECORD_MISMATCH), row.toString())
            assertTrue(row["reason"].asText().endsWith("(record=$id)"), row.toString())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
        }
    }

    @Test
    fun `판단 기록 표는 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        HostBench().use { _ ->
            insertJournal("JO-1", HUMANOID)
            insertRecord("JO-1", "mw-before", "exec-7", JudgmentRecordWire.encode(JudgmentRecord("JO-1", 1, emptyList(), null, emptyList(), cancelRequested = false)))
            listOf(
                "UPDATE mission.execution_judgment_record SET record = 'x'",
                "DELETE FROM mission.execution_judgment_record",
                "TRUNCATE mission.execution_judgment_record",
            ).forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
        }
    }

    private fun HostBench.records(jobOrderId: String): List<JudgmentRecordRow> = bean(HostRecords::class.java).judgmentRecords(jobOrderId)

    /** 이 인스턴스가 적은 가장 최근 기록. */
    private fun HostBench.latest(jobOrderId: String): JudgmentRecord = decode(records(jobOrderId).last { it.instanceId == host.instanceId })

    /** 인스턴스와 무관한 가장 최근 기록. 다시 지은 뒤 아직 바뀐 것이 없으면 앞 인스턴스의 것이다. */
    private fun HostBench.latestAny(jobOrderId: String): JudgmentRecord = decode(records(jobOrderId).last())

    private fun HostBench.runningTask(robotId: String): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    private fun HostBench.tasks(robotId: String): Set<String> =
        mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.map { it.taskId }.toSet() }

    private companion object {
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        const val REMEDY = "remedy-1-pick_place"

        fun decode(row: JudgmentRecordRow): JudgmentRecord = JudgmentRecordWire.decode(row.record)

        /** 단위 id 에서 (상태, 이른 근거 등급). */
        fun units(execution: JsonNode): Map<String, Pair<String, String>> =
            execution["units"].associate { it["unitId"].asText() to (it["state"].asText() to it["reached"].asText()) }

        /** 이전 인스턴스(`mw-before`, 심장박동 행 없음)가 받은 InspectAsset 일지 행을 넣는다. */
        fun insertJournal(jobOrderId: String, robotId: String) {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7")))
            PostgresSupport.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', NULL, 'mw-before', 'exec-7')
                """.trimIndent(),
            )
        }

        /** 판단 기록 한 행을 글자 그대로 넣고 행 번호를 낸다. */
        fun insertRecord(jobOrderId: String, instanceId: String, executionId: String, record: String): Long =
            PostgresSupport.connection().use { c ->
                c.prepareStatement(
                    "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) VALUES (?, ?, ?, ?, ?) RETURNING record_id",
                ).use { s ->
                    s.setString(1, jobOrderId)
                    s.setString(2, instanceId)
                    s.setString(3, executionId)
                    s.setString(4, JudgmentRecordWire.SCHEMA_VERSION)
                    s.setString(5, record)
                    s.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
    }
}
