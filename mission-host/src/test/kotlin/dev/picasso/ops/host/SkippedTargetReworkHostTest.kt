package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.OperatorDecision
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 건너뛴 대상 재작업(picasso ADR 53, 설계 `design-ops-rework-skipped` §3.3·3.4). 선행 이동이 시도 뒤 실패해 점검을 내지 않은 단위(건너뛴
 * 점검)를 운영자가 `REWORK` 로 판단하면 picasso 가 점검과 이동을 한 쌍으로 새 시도로 되돌린다. 보류 단위가 아니므로 호스트 판단 경로의
 * 몇 가지 가정이 달라진다. 판단은 건너뛴 점검이 아니라 선행 이동의 인시던트에 붙고, `PARTIAL` 은 종결로 적은 적이 없어 재실행 이벤트를
 * 적지 않으며, `FAILED` 는 보존 기간 안에서만 받고 이빅션한 뒤에는 NotHeld 다.
 *
 * 바탕 장면은 [FaultReleaseHostTest] 와 같다. 사족 기체의 첫 이동(`T1.travel`)에 로봇 수준 결함을 강제해 실행을 `OPERATOR_HOLD` 에 세우고
 * 수용하면 `T1` 점검은 태스크 없이 `FAILED`(`PREDECESSOR_NOT_DONE`)로 걸러진다. 대상이 둘이면 `T2` 가 이어 돌아 `PARTIAL`, 하나면 `FAILED` 다.
 */
class SkippedTargetReworkHostTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")
    private val kim = Approver("kim", ApproverKind.PERSON)

    private fun HostBench.runningTask(unit: (String) -> Boolean = { true }): String {
        repeat(40) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive {
                mimic.instance(QUADRUPED)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING && unit(it.taskId) }?.taskId
            }?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    /** 사족 기체로 [targets] 점검을 내고 첫 이동에 로봇 수준 결함을 강제해 결함이 막은 실행(`OPERATOR_HOLD`)의 id 를 돌려준다. */
    private fun HostBench.blocked(vararg targets: Pair<String, String>, unit: (String) -> Boolean = { true }): String {
        awaitPump()
        val submitted = post("/host/job-orders", request(inspect("JO-1", *targets), "candidates", QUADRUPED)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        val executionId = submitted["executionId"].asText()
        val running = runningTask(unit)
        val outcome = mimic.server.exclusive { mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
        assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
        val held = driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 4)
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), held.toString())
        return executionId
    }

    /** 막는 결함을 본 그대로 수용한다. */
    private fun HostBench.accept(executionId: String) {
        val saw = execution(executionId)!!["blockedFaults"].map { mapOf("errorType" to it["errorType"].asText(), "references" to it["references"]) }
        val reply = post(
            "/host/executions/$executionId/release",
            JSON.writeValueAsString(
                linkedMapOf("approverId" to "kim", "reason" to "현장에서 기체 상태를 확인했다", "instanceId" to host.instanceId, "sawFaults" to saw, "requestId" to null),
            ),
        )
        assertEquals("RELEASED", reply.body!!["result"].asText(), reply.body.toString())
    }

    /** 대상 둘 가운데 첫 대상만 건너뛴 채 `PARTIAL` 로 종료한 실행의 id. 이 호스트는 기체를 쥔 채 재작업을 기다린다. */
    private fun HostBench.partial(): String {
        val executionId = blocked("T1" to "bay-7", "T2" to "dock-3")
        accept(executionId)
        val done = driveUntil(executionId, settled)
        assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())
        assertEquals(listOf("FAILED", "FAILED", "DONE", "DONE"), done["units"].map { it["state"].asText() }, done.toString())
        return executionId
    }

    /** 대상 하나가 건너뛴 채 `FAILED` 로 종결하고 그 종결을 적은 실행의 id. 보존 기간 동안 picasso 메모리에 있다. */
    private fun HostBench.failed(): String {
        val executionId = blocked("T1" to "bay-7")
        accept(executionId)
        val done = driveUntil(executionId, settled)
        assertEquals("FAILED", done["physicalState"].asText(), done.toString())
        assertEquals(listOf("FAILED", "FAILED"), done["units"].map { it["state"].asText() }, done.toString())
        eventually("종결 기록") { host.footprint().settleMarks == 1 }
        return executionId
    }

    private fun HostBench.travelIncident(): String = get("/host/incidents")["incidents"].single()["incidentId"].asText()

    private fun HostBench.rework(executionId: String, unitId: String = "T1", decision: String = "REWORK", requestId: String? = null): HostBench.Reply =
        post(
            "/host/executions/$executionId/units/$unitId/resolve",
            JSON.writeValueAsString(linkedMapOf("decision" to decision, "approverId" to "kim", "instanceId" to host.instanceId, "requestId" to requestId)),
        )

    private fun HostBench.recordCount(): Int = bean(HostRecords::class.java).judgmentRecords("JO-1").size

    private fun HostBench.latestRecord() = JudgmentRecordWire.decode(bean(HostRecords::class.java).judgmentRecords("JO-1").last().record)

    @Test
    fun `PARTIAL 실행의 건너뛴 대상을 재작업하면 Resolved 이고 판단이 선행 이동의 인시던트에 붙으며 두 단위가 새 시도로 돌아가 RUNNING 응답 하나를 낸다`() {
        HostBench().use { bench ->
            val executionId = bench.partial()
            val travelIncident = bench.travelIncident()
            val before = bench.recordCount()
            val requestId = UUID.randomUUID().toString()

            bench.host.exclusive {
                // pump 가 잠금을 기다리는 동안이다. 판단이 판단 기록을 직접 적었다.
                val view = bench.host.resolve(executionId, "T1", OperatorDecision.REWORK, kim, requestId)
                // 건너뛴 점검 T1 에는 인시던트가 없다. 판단은 선행 이동의 인시던트에 붙었고 응답이 그것을 가리킨다.
                assertEquals(ResolveView(MissionHost.RESOLVED, null, travelIncident, requestId), view)
                val execution = bench.host.executions().executions.single { it.executionId == executionId }
                assertEquals("RUNNING", execution.physicalState)
                assertEquals(
                    listOf("T1.travel" to "PENDING", "T1" to "PENDING", "T2.travel" to "DONE", "T2" to "DONE"),
                    execution.units.map { it.unitId to it.state },
                )
                assertEquals(before + 1, bench.recordCount(), "판단 직후 pump 없이 적는다")
                val record = bench.latestRecord().units.associateBy { it.unitId }
                listOf("T1.travel", "T1").forEach { unitId ->
                    val unit = record.getValue(unitId)
                    val decision = unit.decision!!
                    assertEquals(
                        listOf(OperatorDecision.REWORK, "kim", unit.attempt - 1),
                        listOf(decision.decision, decision.approver.id, decision.attempt),
                        unitId,
                    )
                }
                // 이어 돈 대상은 판단을 싣지 않는다.
                assertNull(record.getValue("T2").decision)
            }

            // 다음 pump 가 picasso 가 낸 RUNNING 응답을 송신 기록과 결과 outbox 에 한 줄로 적는다.
            bench.eventually("RUNNING 응답") { outbox("JO-1").last().first == "RUNNING" }
            assertEquals(listOf("PARTIAL" to "[]", "RUNNING" to "[]"), outbox("JO-1").takeLast(2))
            val response = bench.execution(executionId)!!["jobResponse"]
            assertEquals("RUNNING", response["physicalState"].asText(), response.toString())
            assertEquals(listOf("SENT", "RUNNING"), sentLog(response["jobResponseId"].asText()))
            // PARTIAL 은 종결로 적은 적이 없다. 다시 돎이 아니라 재실행 이벤트도 없다.
            assertFalse("REOPENED" in journalKinds("JO-1"), journalKinds("JO-1").toString())
            // 판단은 그 인시던트 상세에 선다.
            val detail = bench.get("/host/incidents/$travelIncident")
            assertEquals(listOf("REWORK", "kim"), listOf(detail["resolution"]["decision"].asText(), detail["resolution"]["decidedBy"]["id"].asText()))
        }
    }

    @Test
    fun `FAILED 로 종결한 실행의 건너뛴 대상은 보존 기간 안에서 재작업하면 재실행 이벤트를 적는다`() {
        HostBench().use { bench ->
            val executionId = bench.failed()
            val travelIncident = bench.travelIncident()
            assertEquals(listOf("SETTLED"), journalKinds("JO-1"))

            // 메모리에 있는 실행이 모양이 아닌 단위를 판단하면 NotHeld 이고 이빅션이 아니라서 detail 이 없다.
            val missing = bench.rework(executionId, unitId = "T9").body!!
            assertEquals(listOf("NotHeld", true), listOf(missing["result"].asText(), missing["detail"].isNull), missing.toString())

            val requestId = UUID.randomUUID().toString()
            val resolved = bench.rework(executionId, requestId = requestId).body!!
            assertEquals(
                JSON.readTree("""{"result":"Resolved","detail":null,"incidentId":"$travelIncident","requestId":"$requestId"}"""),
                resolved,
            )
            // 종결을 적은 실행이 다시 도는 것이라 재실행 이벤트가 판단 안에서 곧바로 적힌다.
            assertEquals(listOf("SETTLED", "REOPENED"), journalKinds("JO-1"))
            assertEquals("RUNNING", bench.execution(executionId)!!["physicalState"].asText())
        }
    }

    @Test
    fun `보존 기간이 지나 이빅션한 FAILED 실행의 재작업은 NotHeld 이고 detail 이 이빅션 코드이며 없는 실행은 detail 이 없다`() {
        HostBench().use { bench ->
            val executionId = bench.failed()
            bench.mimic.server.advance(MissionHost.DEFAULT_REVISABLE_RETENTION.plusMinutes(1))
            bench.awaitPump()
            bench.eventually("이빅션") { bench.host.footprint().executions == 0 }
            val before = bench.recordCount()

            val evicted = bench.rework(executionId).body!!
            assertEquals(
                JSON.readTree("""{"result":"NotHeld","detail":"${MissionHost.EVICTED}","incidentId":null,"requestId":null}"""),
                evicted,
            )
            assertEquals("EVICTED", MissionHost.EVICTED, "화면과 운영 서비스가 이 글자로 가른다")
            // 확인 판단도 같은 길이다.
            assertEquals(MissionHost.EVICTED, bench.rework(executionId, decision = "CONFIRM_DONE").body!!["detail"].asText())
            // 이 인스턴스에 종결 기록이 없는 실행은 이빅션이 아니라 그냥 NotHeld 다.
            val unknown = bench.rework("exec-999").body!!
            assertEquals(listOf("NotHeld", true), listOf(unknown["result"].asText(), unknown["detail"].isNull), unknown.toString())
            // 아무것도 바뀌지 않았다.
            assertEquals(before, bench.recordCount())
            assertEquals(listOf("SETTLED"), journalKinds("JO-1"))
        }
    }

    @Test
    fun `종결 전 재작업과 건너뛴 점검의 확인은 Refused 이고 picasso 사유를 싣고 incidentId 가 없으며 아무것도 바꾸지 않는다`() {
        HostBench().use { bench ->
            // 수용 직후다. 가상 시계를 밀지 않으므로 T2.travel 은 끝나지 못하고 T1 만 걸러진다.
            val executionId = bench.blocked("T1" to "bay-7", "T2" to "dock-3")
            bench.accept(executionId)
            bench.eventually("T1 점검 걸러짐") {
                bench.execution(executionId)!!["units"].any { it["unitId"].asText() == "T1" && it["state"].asText() == "FAILED" }
            }
            val travelIncident = bench.travelIncident()
            val running = bench.execution(executionId)!!
            assertEquals("RUNNING", running["physicalState"].asText(), running.toString())
            val records = bench.recordCount()

            val early = bench.rework(executionId).body!!
            assertEquals("Refused", early["result"].asText(), early.toString())
            assertTrue(early["detail"].asText().startsWith("실행이 종결한 뒤 한 대상씩 재작업한다"), early.toString())
            assertTrue(early["incidentId"].isNull, early.toString())

            val done = bench.driveUntil(executionId, settled)
            assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())
            val confirm = bench.rework(executionId, decision = "CONFIRM_DONE").body!!
            assertEquals("Refused", confirm["result"].asText(), confirm.toString())
            assertEquals("점검을 하지 않은 단위는 확인할 것이 없다 — 재작업만 받는다: T1", confirm["detail"].asText())
            assertTrue(confirm["incidentId"].isNull, confirm.toString())

            // 거부는 인시던트도 단위도 판단 기록도 바꾸지 않는다.
            assertTrue(bench.get("/host/incidents/$travelIncident")["resolution"].isNull)
            assertEquals(done["units"], bench.execution(executionId)!!["units"])
            assertTrue(bench.recordCount() >= records)
            assertTrue(bench.latestRecord().units.all { it.decision == null })
        }
    }

    @Test
    fun `건너뛴 대상 재작업의 판단은 선행 이동의 인시던트 사본에 붙고 사본의 unresolved 는 봉인 값 그대로다`() {
        HostBench().use { bench ->
            val executionId = bench.partial()
            val travelIncident = bench.travelIncident()
            assertEquals("Resolved", bench.rework(executionId).body!!["result"].asText())

            // 다음 pump 가 인시던트 사본에 판단 행을 적는다.
            bench.eventually("판단 사본") { copyResolutions().isNotEmpty() }
            assertEquals(listOf(listOf(travelIncident, "REWORK", "kim", "PERSON")), copyResolutions())
            // 열 때 봉인한 상세는 판단이 붙어도 그대로다. 이동 실패는 보류가 아니라 unresolved 가 거짓이다.
            val sealed = JSON.readTree(
                PostgresSupport.queryOne("SELECT detail::text FROM mission.incident_copy WHERE incident_id = '$travelIncident'") { it.getString(1) },
            )
            assertEquals(false, sealed["unresolved"].asBoolean(), sealed.toString())
            assertTrue(sealed["resolution"].isNull, sealed.toString())
            val live = bench.get("/host/incidents/$travelIncident")
            assertEquals(listOf(false, false), listOf(live["unresolved"].asBoolean(), live["held"].asBoolean()))
            assertEquals("REWORK", live["resolution"]["decision"].asText())
        }
    }

    /** 화면 목록 본문의 «재작업할 수 있는 건너뛴 대상». */
    private fun HostBench.reworkable(): JsonNode = get("/host/executions?recent=5")["reworkableTargets"]

    private fun JsonNode.rows(): List<List<String>> =
        map { listOf("executionId", "jobOrderId", "robotId", "unitId", "physicalState").map { name -> it[name].asText() } }

    @Test
    fun `화면 목록은 PARTIAL 실행의 건너뛴 대상을 재작업할 수 있는 대상으로 싣고 보존 만료 시각은 없으며 재작업하면 빠진다`() {
        HostBench().use { bench ->
            val held = bench.blocked("T1" to "bay-7", "T2" to "dock-3")
            // 결함이 막고 있는 실행은 아직 종결하지 않았다. 건너뛴 점검도 없다.
            assertTrue(bench.reworkable().isEmpty, bench.reworkable().toString())
            bench.accept(held)
            val done = bench.driveUntil(held, settled)
            assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())

            val targets = bench.reworkable()
            assertEquals(listOf(listOf(held, "JO-1", QUADRUPED, "T1", "PARTIAL")), targets.rows(), targets.toString())
            assertEquals(
                listOf("executionId", "jobOrderId", "robotId", "unitId", "physicalState", "retainedUntil", "attempt", "bundle", "kind", "incidentUnitId", "recheckFailed"),
                targets.single().fieldNames().asSequence().toList(),
            )
            // picasso 후보(§15.323) 그대로다. 건너뛴 쌍은 SKIPPED 이고 묶음은 실패한 이동과 건너뛴 점검, 판단은 이동의 인시던트에 붙는다.
            val target = targets.single()
            assertEquals(
                listOf("0", "[\"T1.travel\",\"T1\"]", "SKIPPED", "T1.travel", "false"),
                listOf(target["attempt"].asText(), target["bundle"].toString(), target["kind"].asText(), target["incidentUnitId"].asText(), target["recheckFailed"].asText()),
                target.toString(),
            )
            // PARTIAL 은 기체를 쥔 채 기다리며 보존 기간이 없다.
            assertTrue(targets.single()["retainedUntil"].isNull, targets.toString())
            assertEquals("Resolved", bench.rework(held).body!!["result"].asText())
            // 재작업하면 실행이 다시 돌아 종결 상태가 아니므로 목록에서 빠진다.
            assertTrue(bench.reworkable().isEmpty, bench.reworkable().toString())
        }
    }

    @Test
    fun `화면 목록은 메모리에 있는 FAILED 실행의 건너뛴 대상에만 보존 만료 시각을 싣고 이빅션하면 빠진다`() {
        HostBench().use { bench ->
            val before = bench.now()
            val executionId = bench.failed()
            val after = bench.now()

            val targets = bench.reworkable()
            assertEquals(listOf(listOf(executionId, "JO-1", QUADRUPED, "T1", "FAILED")), targets.rows(), targets.toString())
            // 보존 만료는 종결 기록 시각(호스트 시계) + 보존 기간이다.
            val expiry = Instant.parse(targets.single()["retainedUntil"].asText())
            val settledAt = expiry.minus(MissionHost.DEFAULT_REVISABLE_RETENTION)
            assertTrue(!settledAt.isBefore(before) && !settledAt.isAfter(after), "$before <= $settledAt <= $after")

            bench.mimic.server.advance(MissionHost.DEFAULT_REVISABLE_RETENTION.plusMinutes(1))
            bench.awaitPump()
            bench.eventually("이빅션") { bench.host.footprint().executions == 0 }
            assertTrue(bench.reworkable().isEmpty, bench.reworkable().toString())
        }
    }

    @Test
    fun `FAILED 실행의 건너뛴 대상을 보존 기간 안에 재작업하면 목록에서 빠진다`() {
        HostBench().use { bench ->
            val executionId = bench.failed()
            assertEquals(1, bench.reworkable().size())
            assertEquals("Resolved", bench.rework(executionId).body!!["result"].asText())
            assertTrue(bench.reworkable().isEmpty, bench.reworkable().toString())
        }
    }

    @Test
    fun `재기동으로 리빌드한 PARTIAL 실행에서도 건너뛴 대상이 재작업 대상으로 서고 재작업할 수 있다`() {
        HostBench().use { bench ->
            val executionId = bench.partial()
            val before = bench.host.instanceId

            bench.restartHost()
            assertTrue(bench.host.instanceId != before)
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals(
                listOf("JO-1", "RESTORED", before, executionId),
                listOf(row["jobOrderId"].asText(), row["result"].asText(), row["previousInstanceId"].asText(), row["previousExecutionId"].asText()),
                row.toString(),
            )
            val rebuilt = row["executionId"].asText()
            // 리빌드한 실행에서 건너뛴 점검이 건너뛴 모양(FAILED, PREDECESSOR_NOT_DONE, 태스크 없음)으로 다시 선다.
            bench.eventually("리빌드한 PARTIAL") { bench.execution(rebuilt)?.get("physicalState")?.asText() == "PARTIAL" }
            val targets = bench.reworkable()
            assertEquals(listOf(listOf(rebuilt, "JO-1", QUADRUPED, "T1", "PARTIAL")), targets.rows(), targets.toString())

            val resolved = bench.rework(rebuilt).body!!
            assertEquals("Resolved", resolved["result"].asText(), resolved.toString())
            assertEquals("RUNNING", bench.execution(rebuilt)!!["physicalState"].asText())
        }
    }

    @Test
    fun `시도 뒤 실패한 점검은 ATTEMPTED_INSPECTION 후보로 앞 이동과 묶여 서고 재작업하면 묶음의 단위마다 판단이 서며 실행 본문 단위가 시도와 판단을 싣는다`() {
        HostBench().use { bench ->
            // 이동이 끝나고 점검 태스크(T1)가 도는 중에 로봇 수준 결함을 강제한다. 점검은 태스크가 나간 채 실패하고(시도된 실패), 결함이 다음 대상의
            // 이동을 막아 보류에 선다. 수용하면(재작업 발행 관문이 수용한 결함은 막지 않는다) T2 가 이어 돌아 PARTIAL 이다.
            val executionId = bench.blocked("T1" to "bay-7", "T2" to "dock-3") { it.substringAfter('#').substringBefore('@') == "T1" }
            bench.accept(executionId)
            val done = bench.driveUntil(executionId, settled)
            assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("DONE", "FAILED", "DONE", "DONE"), done["units"].map { it["state"].asText() }, done.toString())

            val target = bench.reworkable().single()
            assertEquals(
                listOf(executionId, "T1", "0", "[\"T1.travel\",\"T1\"]", "ATTEMPTED_INSPECTION", "PARTIAL"),
                listOf("executionId", "unitId", "attempt").map { target[it].asText() } +
                    listOf(target["bundle"].toString(), target["kind"].asText(), target["physicalState"].asText()),
                target.toString(),
            )
            assertTrue(target["retainedUntil"].isNull, target.toString())

            // 묶음의 어느 단위로 내도 같은 묶음이다 — 앞 버전에서 NotHeld 였던 DONE 이동 id 요청이 이제 받힌다.
            val resolved = bench.rework(executionId, unitId = "T1.travel").body!!
            assertEquals("Resolved", resolved["result"].asText(), resolved.toString())
            assertTrue(bench.reworkable().isEmpty, bench.reworkable().toString())
            val units = bench.execution(executionId)!!["units"].associateBy { it["unitId"].asText() }
            listOf("T1.travel", "T1").forEach { unitId ->
                val unit = units.getValue(unitId)
                assertEquals(1, unit["attempt"].asInt(), unit.toString())
                val decision = unit["decision"]
                assertEquals(
                    listOf("REWORK", "kim", "PERSON", "0"),
                    listOf("decision", "approverId", "approverKind", "attempt").map { decision[it].asText() },
                    unit.toString(),
                )
            }
            // 판단 기록 버전 6: 뿌리(T1)의 판단에 사건 정체성과 근거 표지가 실린다. DONE 이던 이동에도 REWORK 판단이 선다.
            val record = bench.latestRecord().units.associateBy { it.unitId }
            assertEquals(
                listOf(OperatorDecision.REWORK, OperatorDecision.REWORK),
                listOf("T1.travel", "T1").map { record.getValue(it).decision!!.decision },
            )
            val basis = record.getValue("T1").decision!!.reworkBasis!!
            assertEquals(QUADRUPED to null, basis.robotId to basis.hold, "점검 대상은 손을 묻지 않은 표지다")
            assertEquals(JudgmentRecordWire.SCHEMA_VERSION, bench.bean(HostRecords::class.java).judgmentRecords("JO-1").last().wireVersion)
        }
    }

    @Test
    fun `재작업 판단 뒤 판단 기록을 적지 못하면 적을 때까지 pump 와 제출을 막고 적은 뒤에야 새 시도를 낸다`() {
        HostBench().use { bench ->
            val executionId = bench.partial()
            val before = bench.recordCount()
            PostgresSupport.execute(
                """
                CREATE FUNCTION mission.refuse_record() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'record refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            PostgresSupport.execute(
                "CREATE TRIGGER refuse_record BEFORE INSERT ON mission.execution_judgment_record FOR EACH ROW EXECUTE FUNCTION mission.refuse_record()",
            )
            // 판단은 picasso 에 섰다. 기록을 못 적었으므로 그 실행이 일지 막힘에 들고 pump 가 새 시도를 내지 않는다.
            assertEquals("Resolved", bench.rework(executionId).body!!["result"].asText())
            assertEquals(before, bench.recordCount())
            assertEquals(listOf(executionId), bench.get("/host/executions")["journalBlockedExecutionIds"].map { it.asText() })
            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.idlePumps()
            val units = bench.execution(executionId)!!["units"].associate { it["unitId"].asText() to it["state"].asText() }
            assertEquals("PENDING", units["T1.travel"], units.toString())
            val refused = bench.post("/host/job-orders", request(inspect("JO-2", "T9" to "bay-9"), "candidates", QUADRUPED)).body!!
            assertEquals("REJECTED", refused["result"].asText(), refused.toString())

            PostgresSupport.execute("DROP TRIGGER refuse_record ON mission.execution_judgment_record")
            bench.eventually("판단 기록") { bench.recordCount() > before }
            assertTrue(bench.latestRecord().units.single { it.unitId == "T1" }.decision?.decision == OperatorDecision.REWORK)
            bench.eventually("막힘 풀림") { bench.get("/host/executions")["journalBlockedExecutionIds"].isEmpty }
            bench.mimic.server.advance(Duration.ofSeconds(1))
            bench.awaitPump()
            bench.eventually("새 시도") { bench.execution(executionId)!!["units"].first()["state"].asText() != "PENDING" }
        }
    }

    private companion object {
        /** 결과 outbox 의 그 작업 지시 줄(작업 응답의 물리 상태, `blockedBy`), 적은 순서. */
        fun outbox(jobOrderId: String): List<Pair<String, String>> = PostgresSupport.queryAll(
            """
            SELECT snapshot->'jobResponse'->>'physicalState', (snapshot->'jobResponse'->'blockedBy')::text
            FROM mission.result_outbox WHERE snapshot->>'jobOrderId' = '$jobOrderId' ORDER BY sequence
            """.trimIndent(),
        ) { it.getString(1) to it.getString(2).replace(" ", "") }

        /** 그 작업 응답의 송신 기록 줄(처분, 물리 상태). 줄마다 이어 붙인다. */
        fun sentLog(jobResponseId: String): List<String> = PostgresSupport.queryAll(
            "SELECT disposition, physical_state FROM mission.job_response_log WHERE job_response_id = '$jobResponseId' ORDER BY log_id",
        ) { listOf(it.getString(1), it.getString(2)) }.flatten()

        /** 실행 일지 이벤트의 종류, 적은 순서. */
        fun journalKinds(jobOrderId: String): List<String> = PostgresSupport.queryAll(
            "SELECT kind FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { it.getString(1) }

        /** 인시던트 사본의 판단 행(인시던트 id, 결정, 결정자 id, 결정자 종류). */
        fun copyResolutions(): List<List<String>> = PostgresSupport.queryAll(
            "SELECT incident_id, decision, decided_by_id, decided_by_kind FROM mission.incident_copy_resolution ORDER BY incident_id",
        ) { rs -> (1..4).map { rs.getString(it) } }
    }
}
