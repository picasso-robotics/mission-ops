package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.Middleware
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.registry.PostgresSupport
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 운영자 결함 감수(결함 감수·조치 승인 노출 설계 제안 §2.1). 호스트 REST `POST /host/executions/{executionId}/release` 가 사람 승인자를
 * 판정하고, 운영자가 본 결함과 지금 막는 결함을 호스트 잠금 아래에서 대조한 뒤 감수 행(V11)을 먼저 적고 picasso `release` 를 부른다.
 *
 * 막힘은 사족 기체의 첫 태스크(대상 T1 으로 가는 이동 `T1.travel`)에 로봇 수준 결함(`LOCALIZATION_LOST`, 지울 때까지 유지되고 새
 * 태스크를 못 받는다)을 대역 mimic 에 강제해 만든다. 첫 단위가 실패하고 다음 단위(T1 점검)의 시작 전 게이트가 실행을 `OPERATOR_HOLD` 에
 * 세운다. 단위 보류가 아니므로 운영자 판단 대상이 아니다. 수용하면 T1 점검은 선행 이동이 완료되지 않아 태스크 없이 `FAILED`
 * (`PREDECESSOR_NOT_DONE`)로 걸러지고 다음 대상 T2 의 단위가 나간다(picasso §15.234).
 */
class FaultReleaseHostTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    /** 사족 기체로 두 지점 점검을 내고 첫 태스크에 로봇 수준 결함을 강제해 다음 단위가 막힌 실행을 돌려준다. */
    private fun HostBench.blocked(jobOrderId: String = "JO-1"): JsonNode {
        awaitPump()
        val submitted = post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7", "T2" to "dock-3"), "candidates", QUADRUPED)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        val executionId = submitted["executionId"].asText()
        val running = runningTask()
        val outcome = mimic.server.exclusive { mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
        assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
        val blocked = driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 4)
        assertEquals("OPERATOR_HOLD", blocked["physicalState"].asText(), blocked.toString())
        return blocked
    }

    private fun HostBench.runningTask(): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(QUADRUPED)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    /** 실행 목록의 막는 결함을 운영자가 본 결함으로(`sawFaults`). */
    private fun saw(execution: JsonNode): List<Map<String, Any>> =
        execution["blockedFaults"].map { mapOf("errorType" to it["errorType"].asText(), "references" to it["references"]) }

    private fun HostBench.release(
        executionId: String,
        sawFaults: Any,
        requestId: String? = null,
        instanceId: String = host.instanceId,
        reason: String = "현장에서 기체 상태를 확인했다",
    ): HostBench.Reply = post(
        "/host/executions/$executionId/release",
        JSON.writeValueAsString(
            linkedMapOf("approverId" to "kim", "reason" to reason, "instanceId" to instanceId, "sawFaults" to sawFaults, "requestId" to requestId),
        ),
    )

    private fun HostBench.recordCount(jobOrderId: String): Int = bean(HostRecords::class.java).judgmentRecords(jobOrderId).size

    private fun HostBench.latestRecord(jobOrderId: String) = JudgmentRecordWire.decode(bean(HostRecords::class.java).judgmentRecords(jobOrderId).last().record)

    @Test
    fun `본 결함대로 감수하면 감수 행과 판단 기록을 곧바로 적고 막힘이 걷힌 작업 응답 하나를 곧바로 내며 이동이 실패한 대상의 점검은 거르고 다음 대상의 단위를 낸다`() {
        HostBench().use { bench ->
            val blocked = bench.blocked()
            val executionId = blocked["executionId"].asText()
            val incident = bench.get("/host/incidents/${bench.get("/host/incidents")["incidents"].single()["incidentId"].asText()}")
            // 실행 목록의 막는 결함은 사건 번들이 실은 결함과 칸마다 같고, 작업 응답의 blockedBy 는 그 분류 이름이다.
            assertEquals(incident["blockedBy"], blocked["blockedFaults"], blocked.toString())
            assertEquals(blocked["blockedFaults"].map { it["failureClass"].asText() }, blocked["jobResponse"]["blockedBy"].map { it.asText() })
            assertEquals(listOf("LOCALIZATION_LOST"), blocked["blockedFaults"].map { it["errorType"].asText() })
            val heldResponse = blocked["jobResponse"]
            val requestId = UUID.randomUUID().toString()

            bench.host.exclusive {
                // pump 가 잠금을 기다리는 동안이다. 감수가 판단 기록을 직접 적었다.
                val before = bench.recordCount("JO-1")
                val sightings = blocked["blockedFaults"].map { fault ->
                    FaultSighting(fault["errorType"].asText(), fault["references"].map { FaultReferenceView(it["key"].asText(), it["value"].asText()) })
                }
                val released = bench.host.release(executionId, Approver("kim", ApproverKind.PERSON), "현장에서 기체 상태를 확인했다", sightings, requestId)
                assertEquals(ReleaseView(ReleaseResult.RELEASED, executionId, "RUNNING", null, requestId), released)
                assertEquals(before + 1, bench.recordCount("JO-1"), "감수 직후 pump 없이 적는다")
                assertEquals(listOf("LOCALIZATION_LOST[]"), bench.latestRecord("JO-1").acknowledgedFaults)
                // 막는 결함이 없어진 뒤 다시 감수하면 NOT_BLOCKED 이고 작업 응답을 더 내지 않는다(아래 outbox 가 한 줄뿐이다).
                val repeated = bench.host.release(executionId, Approver("kim", ApproverKind.PERSON), "다시", sightings, UUID.randomUUID().toString())
                assertEquals(ReleaseResult.NOT_BLOCKED, repeated.result, repeated.toString())
            }

            val running = bench.execution(executionId)!!
            assertEquals("RUNNING", running["physicalState"].asText(), running.toString())
            assertTrue(running["blockedFaults"].isEmpty, running.toString())
            val release = running["releases"].single()
            assertEquals(listOf("requestId", "approverId", "reason", "faults", "recordedAt"), release.fieldNames().asSequence().toList())
            assertEquals(listOf(requestId, "kim", "현장에서 기체 상태를 확인했다"), listOf("requestId", "approverId", "reason").map { release[it].asText() })
            assertEquals(blocked["blockedFaults"], release["faults"])
            assertEquals(
                listOf(listOf(bench.host.instanceId, executionId, "JO-1", "kim", "PERSON", requestId)),
                PostgresSupport.queryAll(
                    "SELECT instance_id, execution_id, job_order_id, approver_id, approver_kind, request_id::text FROM mission.execution_fault_release",
                ) { rs -> (1..6).map { rs.getString(it) } },
            )

            // picasso release 가 작업 응답 하나를 곧바로 낸다(RUNNING, 빈 blockedBy). 단위 시작·완료는 작업 응답을 내지 않는다.
            val releasedResponse = running["jobResponse"]
            assertTrue(releasedResponse["jobResponseId"] != heldResponse["jobResponseId"], running.toString())
            assertEquals(
                listOf("RUNNING", "[]", "false"),
                listOf(releasedResponse["physicalState"].asText(), releasedResponse["blockedBy"].toString(), releasedResponse["operatorRequired"].asText()),
            )
            // 수용 뒤 T1 점검은 선행 이동(T1.travel)이 실패로 종료해 태스크 없이 거른다. 다음 대상 T2 의 이동이 나가 완료된다.
            val midway = bench.driveUntilUnit(executionId, "T2.travel", "DONE")
            assertEquals("RUNNING", midway["physicalState"].asText(), midway.toString())
            assertEquals(listOf("FAILED", "FAILED"), midway["units"].take(2).map { it["state"].asText() }, midway.toString())
            // 거른 것은 작업 응답을 내지 않는다.
            assertEquals(releasedResponse, midway["jobResponse"], midway.toString())
            // 송신 기록과 결과 outbox 에 그 응답이 한 줄로 들고, 막힌 줄 뒤에 다른 줄은 없다.
            assertEquals(listOf("OPERATOR_HOLD" to "[\"LOCALIZATION_LOST\"]", "RUNNING" to "[]"), outbox("JO-1").takeLast(2))
            assertEquals(listOf("SENT", "RUNNING"), sentLog(releasedResponse["jobResponseId"].asText()))
            // 정착하면 picasso 가 새 작업 응답을 낸다. 감수한 결함은 더 막지 않는다.
            val done = bench.driveUntil(executionId, settled)
            assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("PARTIAL", "[]"), listOf(done["jobResponse"]["physicalState"].asText(), done["jobResponse"]["blockedBy"].toString()))
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), done["units"].map { it["unitId"].asText() })
            assertEquals(listOf("FAILED", "FAILED", "DONE", "DONE"), done["units"].map { it["state"].asText() })
            assertEquals(Middleware.PREDECESSOR_NOT_DONE, done["jobResponse"]["incompleteUnits"].path("T1").asText(), done.toString())
            assertEquals(listOf("T1", "T1.travel"), done["jobResponse"]["incompleteUnits"].fieldNames().asSequence().toList().sorted(), done.toString())
            // 거른 점검은 인시던트를 열지 않는다. 이동의 실패가 연 하나뿐이다.
            assertEquals(1, bench.get("/host/incidents")["incidents"].size(), bench.get("/host/incidents").toString())
            assertEquals(
                listOf("OPERATOR_HOLD" to "[\"LOCALIZATION_LOST\"]", "RUNNING" to "[]", "PARTIAL" to "[]"),
                outbox("JO-1").dropWhile { it.first != "OPERATOR_HOLD" },
            )

            // 더 막는 결함이 없다. 같은 요청 id 가 아니면 NOT_BLOCKED 이고 아무것도 적지 않는다.
            val again = bench.release(executionId, saw(blocked), UUID.randomUUID().toString()).body!!
            assertEquals(listOf("NOT_BLOCKED", "PARTIAL", MissionHost.NO_BLOCKING_FAULT), listOf("result", "physicalState", "detail").map { again[it].asText() })
            assertEquals(1, releaseRows())
        }
    }

    @Test
    fun `감수 행을 못 적으면 500 RECORD_WRITE_FAILED 이고 picasso 를 부르지 않아 실행은 막힌 그대로이며 판단 기록도 늘지 않는다`() {
        HostBench().use { bench ->
            val blocked = bench.blocked()
            val executionId = blocked["executionId"].asText()
            Fenced.execute(
                """
                CREATE FUNCTION mission.refuse_release() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'release refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            Fenced.execute("CREATE TRIGGER refuse_release BEFORE INSERT ON mission.execution_fault_release FOR EACH ROW EXECUTE FUNCTION mission.refuse_release()")
            val records = bench.recordCount("JO-1")

            val failed = bench.release(executionId, saw(blocked))
            assertEquals(500, failed.status, failed.body.toString())
            assertEquals("RECORD_WRITE_FAILED", failed.body!!["error"].asText())
            bench.idlePumps()
            val still = bench.execution(executionId)!!
            assertEquals("OPERATOR_HOLD", still["physicalState"].asText(), still.toString())
            assertEquals(blocked["blockedFaults"], still["blockedFaults"])
            assertTrue(still["releases"].isEmpty)
            assertEquals(records, bench.recordCount("JO-1"))
            assertEquals(emptyList(), bench.latestRecord("JO-1").acknowledgedFaults)

            Fenced.execute("DROP TRIGGER refuse_release ON mission.execution_fault_release")
            assertEquals("RELEASED", bench.release(executionId, saw(blocked)).body!!["result"].asText())
        }
    }

    @Test
    fun `본 결함과 지금 막는 결함이 다르면 BLOCK_CHANGED 이고 감수 행 없이 막힌 그대로다`() {
        HostBench().use { bench ->
            val blocked = bench.blocked()
            val executionId = blocked["executionId"].asText()
            val seen = saw(blocked).single()
            listOf(
                // 참조가 다르다.
                listOf(seen + ("references" to listOf(mapOf("key" to "KEY_TASK_ID", "value" to "JO-1#T1.travel")))),
                // 오류 종류가 다르다.
                listOf(seen + ("errorType" to "OBSTACLE_BLOCKED")),
                // 같은 결함을 둘 보았다(지금은 하나다).
                listOf(seen, seen),
            ).forEach { sawFaults ->
                val reply = bench.release(executionId, sawFaults)
                assertEquals(200, reply.status, reply.body.toString())
                val body = reply.body!!
                assertEquals(listOf("BLOCK_CHANGED", "OPERATOR_HOLD"), listOf(body["result"].asText(), body["physicalState"].asText()), "$sawFaults")
                assertEquals("${MissionHost.BLOCK_CHANGED_DETAIL}: LOCALIZATION_LOST[]", body["detail"].asText())
            }
            assertEquals(0, releaseRows())
            val still = bench.execution(executionId)!!
            assertEquals("OPERATOR_HOLD", still["physicalState"].asText(), still.toString())
            assertEquals(blocked["blockedFaults"], still["blockedFaults"])
        }
    }

    @Test
    fun `같은 requestId 로 다시 보낸 감수는 409 REQUEST_ID_REUSED 이고 감수 행은 하나다`() {
        HostBench().use { bench ->
            val blocked = bench.blocked()
            val executionId = blocked["executionId"].asText()
            val requestId = UUID.randomUUID().toString()
            val first = bench.release(executionId, saw(blocked), requestId)
            assertEquals(
                JSON.readTree("""{"result":"RELEASED","executionId":"$executionId","physicalState":"RUNNING","detail":null,"requestId":"$requestId"}"""),
                first.body,
            )
            // 응답 없음 뒤 같은 요청을 다시 보낸 것이다. 실행은 이미 막히지 않았지만 요청 id 로 먼저 가른다.
            val repeated = bench.release(executionId, saw(blocked), requestId)
            assertEquals(409, repeated.status, repeated.body.toString())
            assertEquals("REQUEST_ID_REUSED", repeated.body!!["error"].asText())
            assertEquals(1, releaseRows())
            assertEquals(listOf(requestId), bench.execution(executionId)!!["releases"].map { it["requestId"].asText() })
        }
    }

    @Test
    fun `다른 인스턴스의 감수 요청은 409 INSTANCE_MISMATCH 이고 에이전트 승인자는 REFUSED 이며 둘 다 아무것도 바꾸지 않는다`() {
        HostBench().use { bench ->
            val blocked = bench.blocked()
            val executionId = blocked["executionId"].asText()
            val mismatch = bench.release(executionId, saw(blocked), instanceId = "mw-earlier")
            assertEquals(409, mismatch.status, mismatch.body.toString())
            assertEquals("INSTANCE_MISMATCH", mismatch.body!!["error"].asText())

            val sightings = listOf(FaultSighting("LOCALIZATION_LOST", emptyList()))
            val refused = bench.host.release(executionId, Approver("planner-bot", ApproverKind.AGENT), "계속", sightings, null)
            assertEquals(ReleaseResult.REFUSED, refused.result)
            assertNull(refused.physicalState)
            assertTrue(refused.detail!!.startsWith(MissionHost.AGENT_RELEASE_REFUSED), refused.toString())

            assertEquals(0, releaseRows())
            val still = bench.execution(executionId)!!
            assertEquals("OPERATOR_HOLD", still["physicalState"].asText(), still.toString())
            assertEquals(blocked["blockedFaults"], still["blockedFaults"])
        }
    }

    @Test
    fun `작업 허가만 막고 있으면 NOT_BLOCKED 이고 허가 대기 사실을 실으며 허가 막음은 그대로다`() {
        HostBench(permitMapping = permitMapping(HUMANOID, QUADRUPED)).use { bench ->
            bench.cellBody = guardOpen()
            repeat(2) {
                bench.mimic.server.advance(Duration.ofSeconds(1))
                bench.awaitPump()
            }
            val submitted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val executionId = submitted["executionId"].asText()
            bench.eventually("허가 막음") { !bench.execution(executionId)!!["permitBlock"].isNull }
            val waiting = bench.execution(executionId)!!
            assertTrue(waiting["blockedFaults"].isEmpty, waiting.toString())

            val reply = bench.release(executionId, listOf(mapOf("errorType" to "LOCALIZATION_LOST", "references" to emptyList<Any>()))).body!!
            assertEquals(listOf("NOT_BLOCKED", waiting["physicalState"].asText()), listOf(reply["result"].asText(), reply["physicalState"].asText()), reply.toString())
            assertEquals("${MissionHost.PERMIT_WAIT}: T1.travel (START)", reply["detail"].asText())
            assertEquals(0, releaseRows())
            assertEquals(waiting["permitBlock"]["unitId"], bench.execution(executionId)!!["permitBlock"]["unitId"])
        }
    }

    @Test
    fun `이 인스턴스가 놓은 실행은 정착 상태로 NOT_BLOCKED 이고 없는 실행은 NOT_FOUND 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", QUADRUPED)).body!!
            val executionId = submitted["executionId"].asText()
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settled)["physicalState"].asText())
            bench.eventually("놓기") { bench.host.footprint().executions == 0 }
            val sawFaults = listOf(mapOf("errorType" to "LOCALIZATION_LOST", "references" to emptyList<Any>()))

            val forgotten = bench.release(executionId, sawFaults).body!!
            assertEquals(
                listOf("NOT_BLOCKED", "PHYSICALLY_DONE", "${MissionHost.SETTLED_NOT_BLOCKED}: PHYSICALLY_DONE"),
                listOf("result", "physicalState", "detail").map { forgotten[it].asText() },
            )
            val missing = bench.release("exec-999", sawFaults).body!!
            assertEquals("NOT_FOUND", missing["result"].asText(), missing.toString())
            assertTrue(missing["physicalState"].isNull)
            assertEquals(0, releaseRows())
        }
    }

    @Test
    fun `감수 요청 본문이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val instance = bench.host.instanceId
            val fault = """{"errorType":"LOCALIZATION_LOST","references":[]}"""
            listOf(
                """{"reason":"r","instanceId":"$instance","sawFaults":[$fault]}""",
                """{"approverId":"kim","reason":" ","instanceId":"$instance","sawFaults":[$fault]}""",
                """{"approverId":"kim","reason":"r","sawFaults":[$fault]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance"}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":["LOCALIZATION_LOST"]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[{"errorType":"LOCALIZATION_LOST"}]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[{"errorType":" ","references":[]}]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[{"errorType":"E","references":[{"key":"","value":"v"}]}]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[{"errorType":"E","references":[{"key":"K","value":7}]}]}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[$fault],"requestId":"not-a-uuid"}""",
                """["kim"]""",
            ).forEach { body ->
                val reply = bench.post("/host/executions/exec-1/release", body)
                assertEquals(400, reply.status, body)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
            }
            assertEquals(
                415,
                bench.post(
                    "/host/executions/exec-1/release", """{"approverId":"kim","reason":"r","instanceId":"$instance","sawFaults":[$fault]}""", contentType = "text/plain",
                ).status,
            )
        }
    }

    @Test
    fun `감수 표는 덧붙이기만 하고 승인자는 사람뿐이며 요청 id 는 한 번이고 감수한 결함은 비지 않는다`() {
        HostBench().use { _ ->
            Fenced.execute(
                "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                    "VALUES ('JO-1', 'r', '{}', 'InspectAsset', 'mw-1', 'exec-1')",
            )
            val requestId = UUID.randomUUID()
            fun insert(faults: String = """[{"errorType":"E"}]""", kind: String = "PERSON", request: String = "'$requestId'") =
                "INSERT INTO mission.execution_fault_release (instance_id, execution_id, job_order_id, faults, approver_id, approver_kind, reason, request_id) " +
                    "VALUES ('mw-1', 'exec-1', 'JO-1', '$faults', 'kim', '$kind', '확인', $request)"
            Fenced.execute(insert())
            Fenced.execute(insert(request = "NULL"))
            Fenced.execute(insert(request = "NULL"))
            listOf(
                "UPDATE mission.execution_fault_release SET reason = 'x'",
                "DELETE FROM mission.execution_fault_release",
                "TRUNCATE mission.execution_fault_release",
            ).forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { Fenced.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
            listOf(
                // 같은 요청 id 는 두 번 적지 못한다.
                insert(),
                insert(kind = "AGENT", request = "NULL"),
                insert(faults = "[]", request = "NULL"),
                insert(faults = """{"errorType":"E"}""", request = "NULL"),
                insert(request = "NULL").replace("'JO-1'", "'JO-X'"),
            ).forEach { sql -> assertFailsWith<SQLException>(sql) { Fenced.execute(sql) } }
            assertEquals(3, releaseRows())
        }
    }

    /** 단위 [unitId] 가 [state] 가 될 때까지 가상 시계를 5초씩 민다. */
    private fun HostBench.driveUntilUnit(executionId: String, unitId: String, state: String): JsonNode {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(5))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
            val execution = checkNotNull(execution(executionId)) { "실행이 없다: $executionId" }
            if (execution["units"].any { it["unitId"].asText() == unitId && it["state"].asText() == state }) return execution
        }
        error("단위 $unitId 가 $state 에 이르지 않았다: ${execution(executionId)}")
    }

    private companion object {
        fun releaseRows(): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_fault_release") { it.getInt(1) }

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

        /** `guard_closed` 가 [robots] 를 맡는 작업 허가 대응 파일. */
        fun permitMapping(vararg robots: String): Path = Files.createTempFile("work-permits", ".json").also {
            Files.writeString(it, """{"signals":[{"signal":"guard_closed","permittedWhen":"true","robots":[${robots.joinToString(",") { r -> "\"$r\"" }}]}]}""")
            it.toFile().deleteOnExit()
        }

        /** 안전 신호 `guard_closed` 가 열린(false) 셀 대역 본문. */
        fun guardOpen(): String {
            val closed = "\"name\":\"guard_closed\",\"location\":null,\"kind\":\"BOOLEAN\",\"safety\":true,\"value\":\"true\""
            check(closed in HostBench.STANDARD_CELL) { "대역 본문의 guard_closed 를 못 찾았다" }
            return HostBench.STANDARD_CELL.replace(closed, closed.replace("\"value\":\"true\"", "\"value\":\"false\""))
        }
    }
}
