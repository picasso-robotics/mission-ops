package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.StartOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.Middleware
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 복원 못 한 실행의 운영자 종결(결함 14). 호스트가 일지 행을 GAVE_UP 이나 DEFERRED 로 남긴 실행을 사람이 끝낸다. 일지 이벤트 CLOSED, 종결 행, 상위로
 * 가는 UNVERIFIED 결과가 한 트랜잭션이고, 재기동을 넘어 닫을 수 있는 행이 실행 목록에 남는다. 앞 인스턴스(`mw-before`)가 받은 일지 행을 SQL 로 넣고
 * 호스트만 다시 띄운다(판단 기록이 JSON 이 아니면 GAVE_UP, [RerunRestoreTest]·[JudgmentRecordHostTest] 와 같은 배선).
 */
class CloseUnrestoredTest {

    @Test
    fun `종결 표는 덧붙이기만 하고 승인자는 사람뿐이며 작업 지시마다 하나이고 요청 id 는 한 번이다`() {
        HostBench().use { _ ->
            insertJournal("JO-1", HUMANOID)
            insertJournal("JO-2", QUADRUPED)
            val first = insertClose("JO-1", "SNAPSHOT")
            assertEquals(1, closeRows())
            listOf(
                "UPDATE mission.execution_close SET reason = 'x'",
                "DELETE FROM mission.execution_close",
                // V13 의 확인 표가 이 표를 가리키므로 CASCADE 가 없으면 외래 키가 먼저 막는다. CASCADE 는 두 표의 TRUNCATE 트리거를 탄다.
                "TRUNCATE mission.execution_close CASCADE",
            ).forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { Fenced.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
            // 승인자는 사람뿐이다.
            assertFailsWith<SQLException>("AGENT 승인자") { insertClose("JO-2", "SNAPSHOT", approverKind = "AGENT") }
            // 작업 지시마다 하나다.
            assertFailsWith<SQLException>("같은 작업 지시") { insertClose("JO-1", "SNAPSHOT") }
            // 요청 id 는 한 번이다.
            assertFailsWith<SQLException>("같은 요청 id") { insertClose("JO-2", "SNAPSHOT", requestId = first) }
            // 근거는 둘뿐이고 사유는 비면 안 된다.
            assertFailsWith<SQLException>("근거") { insertClose("JO-2", "WITNESS") }
            assertFailsWith<SQLException>("빈 사유") { insertClose("JO-2", "SNAPSHOT", reason = "  ") }
            assertEquals(1, closeRows())
            // CLOSED 이벤트는 실행 id 를 싣지 않는다(종결한 쌍은 종결 행에 있다).
            assertFailsWith<SQLException>("실행 id 를 실은 CLOSED") {
                Fenced.execute(
                    "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) " +
                        "VALUES ('JO-2', 'CLOSED', 'mw-now', 'exec-7', 'SNAPSHOT')",
                )
            }
        }
    }

    @Test
    fun `포기한 행의 기체 스냅숏에 그 작업 지시의 살아 있는 태스크가 없으면 증언 없이 종결하고 CLOSED 이벤트와 종결 행과 UNVERIFIED 결과를 한 번 낸다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val watermark = bench.get("/host/result-events")["highWatermark"].asLong()
            assertEquals(listOf("GAVE_UP"), kinds("JO-1"))

            val requestId = UUID.randomUUID().toString()
            val reply = bench.close("JO-1", requestId = requestId)
            assertEquals(200, reply.status, reply.body.toString())
            val body = reply.body!!
            assertEquals(listOf("CLOSED", "JO-1", "SNAPSHOT", requestId), listOf("result", "jobOrderId", "basis", "requestId").map { body[it].asText() }, body.toString())
            assertTrue(body["detail"].isNull, body.toString())

            // 일지 이벤트: 종결 이벤트는 실행 id 가 없고 근거가 detail 이다.
            assertEquals(listOf("GAVE_UP", "CLOSED"), kinds("JO-1"))
            val event = PostgresSupport.queryOne(
                "SELECT event_id, instance_id, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = 'JO-1' AND kind = 'CLOSED'",
            ) { listOf(it.getLong(1).toString(), it.getString(2), it.getString(3), it.getString(4)) }
            assertEquals(listOf(bench.host.instanceId, null, "SNAPSHOT"), event.drop(1))
            val responseId = "close-${event[0]}"
            assertEquals(responseId, body["responseId"].asText())

            // 종결 행.
            val close = PostgresSupport.queryOne(
                """
                SELECT instance_id, closed_instance_id, closed_execution_id, robot_id, restore_result, basis, approver_id, approver_kind, reason, request_id::text, response_id
                FROM mission.execution_close WHERE job_order_id = 'JO-1'
                """.trimIndent(),
            ) { (1..11).map { i -> it.getString(i) } }
            assertEquals(
                listOf(bench.host.instanceId, "mw-before", "exec-7", HUMANOID, "GAVE_UP", "SNAPSHOT", "kim", "PERSON", "현장 확인", requestId, responseId),
                close,
            )

            // 결과 이벤트: 종결한 쌍(앞 인스턴스의 것)의 UNVERIFIED 하나. 성공을 추정하지 않는다.
            val feed = bench.get("/host/result-events?after=$watermark")
            val events = feed["events"]
            assertEquals(1, events.size(), feed.toString())
            val snapshot = events[0]["snapshot"]
            assertEquals(
                listOf("mw-before", "exec-7", "JO-1", HUMANOID, "InspectAsset", "UNVERIFIED"),
                listOf("instanceId", "executionId", "jobOrderId", "robotId", "workMasterId", "physicalState").map { snapshot[it].asText() },
                snapshot.toString(),
            )
            assertTrue(snapshot["missionVersion"].isNull, snapshot.toString())
            assertEquals(JSON.readTree("""{"kind":"UNRESTORED_CLOSED","basis":"SNAPSHOT"}"""), snapshot["closure"])
            val response = snapshot["jobResponse"]
            assertEquals(responseId, response["jobResponseId"].asText())
            assertEquals(
                listOf(1, "UNVERIFIED", "E0", "E0", true, "HOLD_KIND_UNSPECIFIED", "CONNECTION_STATE_UNSPECIFIED"),
                listOf(
                    response["version"].asInt(), response["physicalState"].asText(), response["requiredEvidence"].asText(), response["reachedEvidence"].asText(),
                    response["operatorRequired"].asBoolean(), response["residualHold"].asText(), response["connection"].asText(),
                ),
                response.toString(),
            )
            listOf("completedUnits", "unverifiedUnits", "inDoubtUnits", "blockedBy").forEach { assertTrue(response[it].isArray && response[it].isEmpty, "$it: $response") }
            assertEquals(listOf("T1", "T1.travel"), response["incompleteUnits"].fieldNames().asSequence().toList().sorted(), response.toString())
            response["incompleteUnits"].forEach { assertEquals("UNRESTORED_CLOSED", it.asText(), response.toString()) }

            // 송신 기록에도 같은 응답 id 가 한 번 있다.
            val sent = bench.get("/host/job-responses?jobOrderId=JO-1")["responses"]
            assertEquals(
                listOf(listOf("mw-before", responseId, "exec-7", "UNVERIFIED", "SENT")),
                sent.map { listOf("instanceId", "jobResponseId", "executionId", "physicalState", "disposition").map { f -> it[f].asText() } },
                sent.toString(),
            )
            // 이번 기동의 복원 보고는 그 행이 종결됐다고 알린다. 결과는 종결 전의 복원 결과 그대로다.
            val reported = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("GAVE_UP", reported["result"].asText(), reported.toString())
            assertEquals(listOf(requestId, "kim", "SNAPSHOT"), listOf("requestId", "approverId", "basis").map { reported["close"][it].asText() }, reported.toString())
            // 한 번만 낸다. 호스트가 더 돌아도 결과가 늘지 않는다.
            bench.idlePumps()
            assertEquals(watermark + 1, bench.get("/host/result-events")["highWatermark"].asLong())
        }
    }

    @Test
    fun `일지의 작업 지시를 못 읽어도 종결하고 단위는 마지막 송신 기록의 것이며 없으면 비고 판과 근거 등급은 그 행의 것이다`() {
        HostBench().use { bench ->
            // 작업 지시 칸이 모자라 GAVE_UP 이 된 일지 행. 종결 응답은 읽히는 칸과 송신 기록만으로 짓는다.
            insertRawJournal("JO-1", HUMANOID)
            insertRawJournal("JO-2", QUADRUPED)
            Fenced.execute(
                """
                INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence,
                    reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition)
                VALUES ('mw-before', 'resp-3', 'JO-1', 'exec-7', 3, 'RUNNING', 'E1', 'E0', '["A1"]'::jsonb, '["A2"]'::jsonb, '["A3"]'::jsonb, '["A4"]'::jsonb,
                    false, 'HOLD_KIND_NONE', '[]'::jsonb, 'SENT')
                """.trimIndent(),
            )
            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(listOf("JO-1" to "GAVE_UP", "JO-2" to "GAVE_UP"), rows.map { it["jobOrderId"].asText() to it["result"].asText() }, rows.toString())

            assertEquals("CLOSED", bench.close("JO-1").body!!["result"].asText())
            assertEquals("CLOSED", bench.close("JO-2").body!!["result"].asText())
            val snapshots = bench.get("/host/result-events")["events"].associate { it["snapshot"]["jobOrderId"].asText() to it["snapshot"]["jobResponse"] }
            val first = snapshots.getValue("JO-1")
            assertEquals(listOf("A1", "A2", "A3", "A4"), first["incompleteUnits"].fieldNames().asSequence().toList().sorted(), first.toString())
            assertEquals(listOf(3, "E1"), listOf(first["version"].asInt(), first["requiredEvidence"].asText()), first.toString())
            assertTrue(first["completedUnits"].isEmpty, first.toString())
            // 송신 기록도 읽히는 계획도 없으면 단위가 비어도 응답은 선다.
            val second = snapshots.getValue("JO-2")
            assertTrue(second["incompleteUnits"].isEmpty, second.toString())
            assertEquals(listOf(1, "E0"), listOf(second["version"].asInt(), second["requiredEvidence"].asText()), second.toString())
        }
    }

    @Test
    fun `종결한 작업 지시를 다시 내면 FORGOTTEN_JOB_ORDER 로 거부하고 재기동 뒤에도 같다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            assertEquals("CLOSED", bench.close("JO-1").body!!["result"].asText())
            fun resubmit(): JsonNode = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            val before = resubmit()
            assertEquals("REJECTED", before["result"].asText(), before.toString())
            assertTrue(before["forgottenJobOrder"].asBoolean(), before.toString())
            assertTrue(before["rejectionReason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER), before.toString())
            assertTrue("mw-before exec-7" in before["rejectionReason"].asText(), before.toString())
            assertTrue("UNVERIFIED" in before["rejectionReason"].asText(), before.toString())

            bench.restartHost()
            val after = resubmit()
            assertTrue(after["forgottenJobOrder"].asBoolean(), after.toString())
            assertEquals(before["rejectionReason"], after["rejectionReason"])
        }
    }

    @Test
    fun `종결한 행은 재기동해도 복원 대상도 포기 행도 아니고 판정 사유도 없다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            assertEquals("CLOSED", bench.close("JO-1").body!!["result"].asText())
            bench.restartHost()
            val executions = bench.get("/host/executions")
            assertTrue(executions["restore"]["rows"].isEmpty, executions.toString())
            val page = bench.get("/host/executions?recent=20")
            assertTrue(page["unrestored"].isEmpty, page["unrestored"].toString())
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(listOf("GAVE_UP", "CLOSED"), kinds("JO-1"))
        }
    }

    @Test
    fun `포기 행은 두 번 재기동해도 실행 목록 unrestored 에 닫을 수 있는 행으로 남는다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val first = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(
                listOf("JO-1", HUMANOID, "mw-before", "exec-7", "GAVE_UP", true),
                listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result", "closable").map { first[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } },
                first.toString(),
            )
            assertTrue(first["reason"].asText().startsWith(MissionHost.RECORD_UNREADABLE), first.toString())
            assertTrue(first["robotExcluded"].asBoolean(), first.toString())
            assertTrue(first["close"].isNull && first["notClosableReason"].isNull, first.toString())

            // 스냅숏이 동의하면 기체 제외는 풀리지만 행은 닫을 수 있는 채로 남는다.
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertTrue(!bench.get("/host/executions?recent=20")["unrestored"].single()["robotExcluded"].asBoolean())

            bench.restartHost()
            bench.restartHost()
            assertTrue(bench.get("/host/executions")["restore"]["rows"].isEmpty)
            val later = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("JO-1", "GAVE_UP", true, "mw-before", "exec-7"), listOf("jobOrderId", "result", "closable", "previousInstanceId", "previousExecutionId").map { later[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, later.toString())
            assertTrue(later["since"].isTextual, later.toString())

            // 두 번 재기동한 행도 그 쌍을 보고 닫을 수 있다. 닫으면 목록에서 빠진다.
            assertEquals("CLOSED", bench.close("JO-1").body!!["result"].asText())
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
        }
    }

    @Test
    fun `살아 있는 태스크가 돌면 증언이 있어도 TASK_LIVE 이고 사람을 기다리는 태스크면 증언 없이는 STOP_UNCONFIRMED 증언하면 ATTESTED 로 종결하며 그 기체는 재기동을 넘어 스냅숏이 동의할 때까지 판정에서 빠진다`() {
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
            bench.restartHost()
            assertEquals(listOf("JO-E2" to "GAVE_UP"), bench.get("/host/executions")["restore"]["rows"].map { it["jobOrderId"].asText() to it["result"].asText() })
            val watermark = bench.get("/host/result-events")["highWatermark"].asLong()
            val events = kinds("JO-E2")

            // 기체가 도는 중이다. 증언으로도 닫지 않는다.
            val live = bench.close("JO-E2", attested = true)
            assertEquals("TASK_LIVE", live.body!!["result"].asText(), live.body.toString())
            assertTrue(taskId in live.body["detail"].asText() && "RUNNING" in live.body["detail"].asText(), live.body.toString())

            // 사람을 기다리는 태스크는 증언 없이는 닫지 않는다.
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", taskId) }
            assertEquals(TaskState.NEEDS_INTERVENTION, (outcome as ForceOutcome.Raised).taskState, outcome.toString())
            val unconfirmed = bench.close("JO-E2", attested = false)
            assertEquals("STOP_UNCONFIRMED", unconfirmed.body!!["result"].asText(), unconfirmed.body.toString())
            assertTrue(taskId in unconfirmed.body["detail"].asText(), unconfirmed.body.toString())
            // 거부는 아무것도 적지 않는다.
            assertEquals(events, kinds("JO-E2"))
            assertEquals(0, closeRows())
            assertEquals(watermark, bench.get("/host/result-events")["highWatermark"].asLong())

            val attested = bench.close("JO-E2", attested = true)
            assertEquals(listOf("CLOSED", "ATTESTED"), listOf("result", "basis").map { attested.body!![it].asText() }, attested.body.toString())
            assertEquals(listOf("GAVE_UP", "CLOSED"), kinds("JO-E2"))
            val snapshot = bench.get("/host/result-events?after=$watermark")["events"].single()["snapshot"]
            assertEquals(JSON.readTree("""{"kind":"UNRESTORED_CLOSED","basis":"ATTESTED"}"""), snapshot["closure"])

            // 그 기체는 스냅숏이 동의할 때까지 판정에서 빠지고, 재기동을 넘어도 그렇다.
            val reason = "${MissionHost.CLOSED_UNCONFIRMED_REASON}: JO-E2"
            assertEquals(listOf(reason), reasons(bench, QUADRUPED))
            val page = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("JO-E2", "CLOSED", true), listOf("jobOrderId", "result", "robotExcluded").map { page[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, page.toString())
            assertEquals("kim", page["close"]["approverId"].asText())
            assertEquals("ATTESTED", page["close"]["basis"].asText())

            bench.restartHost()
            assertEquals(listOf(reason), reasons(bench, QUADRUPED))
            assertEquals("CLOSED", bench.get("/host/executions?recent=20")["unrestored"].single()["result"].asText())
            assertEquals(listOf("GAVE_UP", "CLOSED"), kinds("JO-E2"))
        }
    }

    @Test
    fun `스냅숏을 못 읽어 미룬 행은 증언으로만 종결하고 종결 뒤에는 스냅숏이 읽혀도 리빌드하지 않는다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            // 앞 인스턴스의 pump 가 하위에 태스크를 낸 뒤에 다시 띄운다. 그 태스크가 스냅숏이 읽히는 뒤에도 기체를 쥔다.
            bench.eventually("앞 인스턴스의 태스크") { bench.tasks(HUMANOID).isNotEmpty() }
            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("DEFERRED", row["result"].asText(), row.toString())
            assertTrue(row["reason"].asText().startsWith(Middleware.RESUME_SNAPSHOT_UNREADABLE), row.toString())
            val listed = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("JO-1", "DEFERRED", true), listOf("jobOrderId", "result", "closable").map { listed[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, listed.toString())

            val seen = before to "exec-1"
            val unconfirmed = bench.close("JO-1", seen = seen, attested = false)
            assertEquals("STOP_UNCONFIRMED", unconfirmed.body!!["result"].asText(), unconfirmed.body.toString())
            assertEquals(listOf("DEFERRED"), kinds("JO-1"))
            assertEquals(0, closeRows())

            val attested = bench.close("JO-1", seen = seen, attested = true)
            assertEquals(listOf("CLOSED", "ATTESTED"), listOf("result", "basis").map { attested.body!![it].asText() }, attested.body.toString())
            assertEquals(listOf("DEFERRED", "CLOSED"), kinds("JO-1"))
            assertEquals("DEFERRED", PostgresSupport.queryOne("SELECT restore_result FROM mission.execution_close WHERE job_order_id = 'JO-1'") { it.getString(1) })
            assertEquals(
                listOf(before, "exec-1"),
                bench.get("/host/result-events")["events"].last()["snapshot"].let { listOf(it["instanceId"].asText(), it["executionId"].asText()) },
            )

            // 스냅숏이 읽혀도 종결한 행은 다시 짓지 않는다. 이 이벤트 열에 RESTORED 가 없다.
            bench.blockedSnapshots -= HUMANOID
            bench.idlePumps(6)
            assertEquals(listOf("DEFERRED", "CLOSED"), kinds("JO-1"))
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            // 그 기체는 앞 인스턴스가 낸 태스크가 끝날 때까지 판정에서 빠진다(증언 종결). 스냅숏이 동의하면 풀리고 재기동 뒤에도 더 빼지 않는다.
            val reason = "${MissionHost.CLOSED_UNCONFIRMED_REASON}: JO-1"
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            assertEquals("CLOSED", bench.get("/host/executions?recent=20")["unrestored"].single()["result"].asText())
            repeat(60) {
                if (bench.tasks(HUMANOID).values.any { !it.isTerminal }) {
                    bench.mimic.server.advance(Duration.ofSeconds(5))
                    bench.awaitPump()
                }
            }
            assertTrue(bench.tasks(HUMANOID).values.all { it.isTerminal }, bench.tasks(HUMANOID).toString())
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
            bench.restartHost()
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(listOf("DEFERRED", "CLOSED"), kinds("JO-1"))
        }
    }

    @Test
    fun `에이전트 승인자는 REFUSED 이고 스냅숏이 읽히는 미룬 행은 다음 pump 가 리빌드하므로 NOT_CLOSABLE 이며 둘 다 아무것도 적지 않는다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.eventually("앞 인스턴스의 태스크") { bench.tasks(HUMANOID).isNotEmpty() }
            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            val seen = before to "exec-1"

            val agent = bench.host.closeUnrestored("JO-1", Approver("bot-1", ApproverKind.AGENT), "r", UUID.randomUUID().toString(), seen, true)
            assertEquals(CloseResult.REFUSED, agent.result, agent.toString())
            assertTrue(agent.detail!!.startsWith(MissionHost.AGENT_CLOSE_REFUSED), agent.toString())
            assertEquals(listOf("DEFERRED"), kinds("JO-1"))
            assertEquals(0, closeRows())

            // 호스트 잠금을 쥔 동안은 pump 가 미룬 행을 다시 시도하지 못한다. 그 사이 스냅숏이 읽히면 다음 pump 가 리빌드할 행이다.
            // 잠금을 놓으면 pump 가 곧바로 리빌드해 RESTORED 를 적으므로 기록은 잠금 안에서 읽는다.
            val readable = bench.host.exclusive {
                bench.blockedSnapshots -= HUMANOID
                val answer = bench.host.closeUnrestored("JO-1", Approver("kim", ApproverKind.PERSON), "r", UUID.randomUUID().toString(), seen, true)
                assertEquals(listOf("DEFERRED"), kinds("JO-1"))
                assertEquals(0, closeRows())
                answer
            }
            assertEquals(CloseResult.NOT_CLOSABLE, readable.result, readable.toString())
            assertTrue("리빌드" in readable.detail!!, readable.toString())
        }
    }

    @Test
    fun `심장박동이 살아 있는 다른 인스턴스가 든 행도 리스를 쥔 호스트는 다시 짓고 그 실행은 운영자 종결 대상이 아니다`() {
        HostBench().use { bench ->
            insertJournal("JO-1", HUMANOID, instanceId = "mw-other")
            liveInstance("mw-other")
            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("RESTORED", row["result"].asText(), row.toString())
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)

            val reply = bench.close("JO-1", seen = "mw-other" to "exec-7", attested = true)
            assertEquals(200, reply.status, reply.body.toString())
            assertEquals("NOT_CLOSABLE", reply.body!!["result"].asText(), reply.body.toString())
            assertTrue(reply.body["detail"].asText().startsWith("진행 중인 실행이다"), reply.body.toString())
            assertEquals(listOf("RESTORED"), kinds("JO-1"))
            assertEquals(0, closeRows())
        }
    }

    @Test
    fun `작업 지시를 못 읽는 행은 심장박동이 살아 있는 다른 인스턴스가 들었어도 리스를 쥔 호스트가 곧바로 포기하고 닫을 수 있다`() {
        HostBench().use { bench ->
            insertRawJournal("JO-1", HUMANOID, instanceId = "mw-other")
            liveInstance("mw-other")
            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("GAVE_UP", row["result"].asText(), row.toString())
            assertEquals(listOf("GAVE_UP"), kinds("JO-1"))
            val listed = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("GAVE_UP", true), listOf("result", "closable").map { listed[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, listed.toString())
            assertTrue(listed["notClosableReason"].isNull, listed.toString())
            assertEquals("CLOSED", bench.close("JO-1", seen = "mw-other" to "exec-7").body!!["result"].asText())
        }
    }

    @Test
    fun `이전 기동에서 포기한 행은 그 일지 행을 든 인스턴스의 심장박동이 살아 있어도 리스를 쥔 호스트가 닫을 수 있다`() {
        HostBench().use { bench ->
            insertRawJournal("JO-1", HUMANOID, instanceId = "mw-other")
            insertGaveUpEvent("JO-1")
            liveInstance("mw-other")
            bench.restartHost()
            val listed = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("GAVE_UP", true), listOf("result", "closable").map { listed[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, listed.toString())
            assertTrue(listed["notClosableReason"].isNull, listed.toString())
            assertEquals("CLOSED", bench.close("JO-1", seen = "mw-other" to "exec-7").body!!["result"].asText())
        }
    }

    @Test
    fun `다른 인스턴스가 종결한 미룬 행에 종결을 요청하면 ALREADY_CLOSED 이고 이 인스턴스의 목록에서 빠진다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val before = bench.host.instanceId
            bench.eventually("앞 인스턴스의 태스크") { bench.tasks(HUMANOID).isNotEmpty() }
            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            assertEquals(1, bench.get("/host/executions?recent=20")["unrestored"].size())

            // 다른 인스턴스가 그 행을 종결했다. 잠금을 쥔 동안은 이 인스턴스의 pump 가 그것을 보지 못하고 메모리는 아직 그 행을 든다.
            val answer = bench.host.exclusive {
                insertClose("JO-1", "SNAPSHOT")
                val reply = bench.host.closeUnrestored("JO-1", Approver("kim", ApproverKind.PERSON), "r", UUID.randomUUID().toString(), before to "exec-1", true)
                assertTrue(bench.host.executionsRecent(20).unrestored.isEmpty(), "다른 인스턴스의 종결을 본 뒤에는 목록에서 뺀다")
                reply
            }
            assertEquals(CloseResult.ALREADY_CLOSED, answer.result, answer.toString())
        }
    }

    @Test
    fun `미룬 행을 다른 인스턴스가 종결하면 스냅숏이 읽혀도 리빌드하지 않고 RESTORED 를 적지 않는다`() {
        HostBench(gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.eventually("앞 인스턴스의 태스크") { bench.tasks(HUMANOID).isNotEmpty() }
            bench.blockedSnapshots += HUMANOID
            bench.restartHost()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())

            // 이 인스턴스의 pump 가 돌지 못하는 사이(잠금) 다른 인스턴스가 종결하고 스냅숏이 읽히게 된다.
            bench.host.exclusive {
                insertClose("JO-1", "SNAPSHOT")
                bench.blockedSnapshots -= HUMANOID
            }
            bench.idlePumps(6)
            assertEquals(listOf("DEFERRED", "CLOSED"), kinds("JO-1"))
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
            val reported = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("DEFERRED", reported["result"].asText(), reported.toString())
            assertTrue(reported["close"].isObject, reported.toString())
        }
    }

    @Test
    fun `도는 실행 종결한 작업 지시 없는 작업 지시 이미 종결 본 실행이 다름 은 각각 NOT_CLOSABLE NOT_CLOSABLE NOT_FOUND ALREADY_CLOSED TARGET_CHANGED 이고 같은 requestId 는 409 REQUEST_ID_REUSED 이며 본문이 틀리면 400 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            // 도는 실행.
            val accepted = bench.post("/host/job-orders", request(inspect("JO-RUN", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val running = bench.close("JO-RUN", seen = bench.host.instanceId to "exec-1").body!!
            assertEquals("NOT_CLOSABLE", running["result"].asText(), running.toString())
            assertTrue("exec-1" in running["detail"].asText(), running.toString())

            // 종결한 작업 지시(SETTLED).
            insertJournal("JO-DONE", QUADRUPED)
            Fenced.execute(
                "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-DONE', 'SETTLED', 'mw-before', 'exec-7', 'PHYSICALLY_DONE')",
            )
            val settled = bench.close("JO-DONE").body!!
            assertEquals("NOT_CLOSABLE", settled["result"].asText(), settled.toString())

            // 없는 작업 지시.
            val missing = bench.close("JO-NONE").body!!
            assertEquals("NOT_FOUND", missing["result"].asText(), missing.toString())

            // 본 실행이 다르다. 아무것도 적지 않는다.
            gaveUp(bench, "JO-1", HUMANOID)
            val changed = bench.close("JO-1", seen = "mw-before" to "exec-1").body!!
            assertEquals("TARGET_CHANGED", changed["result"].asText(), changed.toString())
            assertEquals(listOf("GAVE_UP"), kinds("JO-1"))
            assertEquals(0, closeRows())
            val otherInstance = bench.close("JO-1", seen = "mw-other" to "exec-7").body!!
            assertEquals("TARGET_CHANGED", otherInstance["result"].asText(), otherInstance.toString())

            // 닫는다. 같은 작업 지시를 또 닫으면 ALREADY_CLOSED 이고 앞선 종결을 싣는다.
            val first = UUID.randomUUID().toString()
            assertEquals("CLOSED", bench.close("JO-1", requestId = first).body!!["result"].asText())
            val again = bench.close("JO-1").body!!
            assertEquals("ALREADY_CLOSED", again["result"].asText(), again.toString())
            assertEquals(first, again["closure"]["requestId"].asText(), again.toString())
            assertEquals(1, closeRows())

            // 같은 requestId 는 409 다.
            val reused = bench.close("JO-1", requestId = first)
            assertEquals(409, reused.status, reused.body.toString())
            assertEquals("REQUEST_ID_REUSED", reused.body!!["error"].asText())
            val reusedElsewhere = bench.close("JO-NONE", requestId = first)
            assertEquals(409, reusedElsewhere.status, reusedElsewhere.body.toString())
            assertEquals(1, closeRows())

            // 본문이 틀리면 400 이다.
            val path = "/host/job-orders/JO-1/close"
            val valid: Map<String, Any?> = mapOf(
                "approverId" to "kim", "reason" to "r", "requestId" to UUID.randomUUID().toString(),
                "executionInstanceId" to "mw-before", "executionId" to "exec-7", "attestedStopped" to false,
            )
            fun post(vararg changes: Pair<String, Any?>, drop: String? = null) =
                bench.post(path, JSON.writeValueAsString((valid + changes).filterKeys { it != drop }))
            listOf(
                post(drop = "requestId"), post("requestId" to "not-a-uuid"), post("requestId" to null),
                post(drop = "approverId"), post("reason" to "  "), post(drop = "reason"),
                post(drop = "executionInstanceId"), post(drop = "executionId"),
                post("attestedStopped" to "true"), post(drop = "attestedStopped"), post("attestedStopped" to 1),
                bench.post(path, "[]"), bench.post(path, "{"),
            ).forEachIndexed { index, reply ->
                assertEquals(400, reply.status, "$index: ${reply.body}")
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), "$index")
            }
            // JSON 이 아니면 415 다.
            assertEquals(415, bench.post(path, JSON.writeValueAsString(valid), contentType = "text/plain").status)
            assertEquals(1, closeRows())
        }
    }

    @Test
    fun `종결 행을 못 적으면 500 RECORD_WRITE_FAILED 이고 이벤트 결과 송신 기록이 남지 않으며 다시 닫을 수 있다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val watermark = bench.get("/host/result-events")["highWatermark"].asLong()
            // 결과 아웃박스 쓰기가 마지막이다. 거기서 실패하면 앞의 이벤트·종결 행·송신 기록이 같이 돌아가야 한다.
            Fenced.execute("ALTER TABLE mission.result_outbox ADD CONSTRAINT refuse_close CHECK (response_id NOT LIKE 'close-%')")
            val requestId = UUID.randomUUID().toString()
            val failed = bench.close("JO-1", requestId = requestId)
            assertEquals(500, failed.status, failed.body.toString())
            assertEquals("RECORD_WRITE_FAILED", failed.body!!["error"].asText())
            assertEquals(listOf("GAVE_UP"), kinds("JO-1"))
            assertEquals(0, closeRows())
            assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.job_response_log WHERE job_order_id = 'JO-1'") { it.getInt(1) })
            assertEquals(watermark, bench.get("/host/result-events")["highWatermark"].asLong())
            // 메모리도 그대로다. 닫을 수 있는 행이 목록에 남는다.
            val listed = bench.get("/host/executions?recent=20")["unrestored"].single()
            assertEquals(listOf("JO-1", "GAVE_UP", true), listOf("jobOrderId", "result", "closable").map { listed[it].let { v -> if (v.isBoolean) v.asBoolean() else v.asText() } }, listed.toString())

            Fenced.execute("ALTER TABLE mission.result_outbox DROP CONSTRAINT refuse_close")
            val retried = bench.close("JO-1", requestId = requestId)
            assertEquals("CLOSED", retried.body!!["result"].asText(), retried.body.toString())
            assertEquals(watermark + 1, bench.get("/host/result-events")["highWatermark"].asLong())
        }
    }

    @Test
    fun `종결 조회는 종결 전 404 NOT_CLOSED 종결 뒤 그 requestId 를 싣는다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val before = bench.fetch("/host/job-orders/JO-1/closure")
            assertEquals(404, before.status, before.body.toString())
            assertEquals("NOT_CLOSED", before.body!!["error"].asText())
            assertEquals(404, bench.fetch("/host/job-orders/JO-NONE/closure").status)

            val requestId = UUID.randomUUID().toString()
            assertEquals("CLOSED", bench.close("JO-1", requestId = requestId, reason = "현장에서 정지를 봤다").body!!["result"].asText())
            val after = bench.fetch("/host/job-orders/JO-1/closure")
            assertEquals(200, after.status, after.body.toString())
            val body = after.body!!
            assertEquals(
                listOf(requestId, "kim", "현장에서 정지를 봤다", "SNAPSHOT", "mw-before", "exec-7", "GAVE_UP"),
                listOf("requestId", "approverId", "reason", "basis", "closedInstanceId", "closedExecutionId", "restoreResult").map { body[it].asText() },
                body.toString(),
            )
            assertTrue(!body["attestedStopped"].asBoolean(), body.toString())
            assertTrue(body["responseId"].asText().startsWith("close-"), body.toString())
            assertTrue(body["restoreReason"].asText().startsWith(MissionHost.RECORD_UNREADABLE), body.toString())
            assertTrue(body["recordedAt"].isTextual, body.toString())
        }
    }

    @Test
    fun `종결이 잠금을 기다리는 동안 그 requestId 의 종결 조회는 409 REQUEST_IN_PROGRESS 이고 끝난 뒤에는 200 이다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val requestId = UUID.randomUUID().toString()
            val path = "/host/job-orders/JO-1/closure"
            val pending = bench.host.exclusive {
                val future = CompletableFuture.supplyAsync { bench.close("JO-1", requestId = requestId) }
                bench.eventually("종결을 처리 중으로 둔다") { bench.fetch("$path?requestId=$requestId").status == 409 }
                val busy = bench.fetch("$path?requestId=$requestId")
                assertEquals("REQUEST_IN_PROGRESS", busy.body!!["error"].asText(), busy.body.toString())
                // 다른 요청 id 로 읽거나 요청 id 없이 읽으면 처리 중이 아니라 종결 없음이다.
                assertEquals(404, bench.fetch("$path?requestId=${UUID.randomUUID()}").status)
                assertEquals(404, bench.fetch(path).status)
                assertEquals(0, closeRows())
                future
            }
            assertEquals("CLOSED", pending.get(10, TimeUnit.SECONDS).body!!["result"].asText())
            val done = bench.fetch("$path?requestId=$requestId")
            assertEquals(200, done.status, done.body.toString())
            assertEquals(requestId, done.body!!["requestId"].asText())
            assertEquals(400, bench.fetch("$path?requestId=not-a-uuid").status)
        }
    }

    @Test
    fun `요청 id 는 정규형 UUID 로 적고 응답하며 대문자로 와도 같은 요청으로 읽는다`() {
        HostBench().use { bench ->
            gaveUp(bench, "JO-1", HUMANOID)
            val canonical = UUID.randomUUID().toString()
            val body = bench.close("JO-1", requestId = canonical.uppercase()).body!!
            assertEquals("CLOSED", body["result"].asText(), body.toString())
            assertEquals(canonical, body["requestId"].asText(), body.toString())
            val closure = bench.fetch("/host/job-orders/JO-1/closure?requestId=${canonical.uppercase()}")
            assertEquals(200, closure.status, closure.body.toString())
            assertEquals(canonical, closure.body!!["requestId"].asText())
        }
    }

    @Test
    fun `증언 종결은 스냅숏이 동의하는 판정에서 확인 행을 적고 재기동 뒤에는 실행 목록에도 판정에도 다시 오르지 않는다`() {
        HostBench(gated = true).use { bench ->
            val reason = "${MissionHost.CLOSED_UNCONFIRMED_REASON}: JO-1"
            attestedDeferredClose(bench)
            assertEquals(0, confirmedRows())
            // 스냅숏이 읽혀도 앞 인스턴스의 태스크가 도는 동안은 동의하지 않는다. 확인 행도 없다.
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            assertEquals(0, confirmedRows())
            finishTasks(bench)
            // 판정이 스냅숏을 읽고 동의하면 풀고 확인 행을 하나 적는다. 같은 판정을 다시 해도 한 행이다.
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(1, confirmedRows())
            assertEquals(
                listOf("JO-1", bench.host.instanceId),
                PostgresSupport.queryOne(
                    "SELECT c.job_order_id, k.instance_id FROM mission.execution_close_confirmed k JOIN mission.execution_close c USING (close_id)",
                ) { listOf(it.getString(1), it.getString(2)) },
            )

            // 재기동 뒤 판정하기 전에도 실행 목록에 오르지 않고, 판정에도 사유가 없다.
            bench.restartHost()
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(listOf("DEFERRED", "CLOSED"), kinds("JO-1"))
            assertEquals(1, confirmedRows())
        }
    }

    @Test
    fun `동의하기 전의 증언 종결은 재기동 뒤에도 실행 목록에 남고 그 기체를 계속 빼며 확인 행이 없다`() {
        HostBench(gated = true).use { bench ->
            val reason = "${MissionHost.CLOSED_UNCONFIRMED_REASON}: JO-1"
            attestedDeferredClose(bench)
            bench.restartHost()
            assertEquals("CLOSED", bench.get("/host/executions?recent=20")["unrestored"].single()["result"].asText())
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            bench.restartHost()
            assertEquals("CLOSED", bench.get("/host/executions?recent=20")["unrestored"].single()["result"].asText())
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            assertEquals(0, confirmedRows())
        }
    }

    @Test
    fun `확인 표는 덧붙이기만 하고 종결 행마다 하나다`() {
        HostBench().use { _ ->
            insertJournal("JO-1", HUMANOID)
            insertClose("JO-1", "ATTESTED")
            val closeId = PostgresSupport.queryOne("SELECT close_id FROM mission.execution_close") { it.getLong(1) }
            Fenced.execute("INSERT INTO mission.execution_close_confirmed (close_id, instance_id) VALUES ($closeId, 'mw-now')")
            assertEquals(1, confirmedRows())
            listOf(
                "UPDATE mission.execution_close_confirmed SET instance_id = 'x'",
                "DELETE FROM mission.execution_close_confirmed",
                "TRUNCATE mission.execution_close_confirmed",
            ).forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { Fenced.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
            assertFailsWith<SQLException>("같은 종결 행") {
                Fenced.execute("INSERT INTO mission.execution_close_confirmed (close_id, instance_id) VALUES ($closeId, 'mw-other')")
            }
            assertFailsWith<SQLException>("없는 종결 행") {
                Fenced.execute("INSERT INTO mission.execution_close_confirmed (close_id, instance_id) VALUES (${closeId + 1000}, 'mw-now')")
            }
            assertEquals(1, confirmedRows())
        }
    }

    @Test
    fun `확인 행을 못 적으면 판정은 예외 없이 그 기체를 계속 빼고 다음 판정에서 다시 적는다`() {
        HostBench(gated = true).use { bench ->
            val reason = "${MissionHost.CLOSED_UNCONFIRMED_REASON}: JO-1"
            attestedDeferredClose(bench)
            finishTasks(bench)
            Fenced.execute("ALTER TABLE mission.execution_close_confirmed ADD CONSTRAINT refuse_confirm CHECK (false)")
            // 스냅숏은 동의하지만 확인 행을 못 적는다. 판정은 깨지지 않고 기체는 계속 빠지며 목록에도 남는다.
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            assertEquals(listOf(reason), reasons(bench, HUMANOID))
            assertEquals(0, confirmedRows())
            assertEquals("CLOSED", bench.get("/host/executions?recent=20")["unrestored"].single()["result"].asText())

            Fenced.execute("ALTER TABLE mission.execution_close_confirmed DROP CONSTRAINT refuse_confirm")
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(1, confirmedRows())
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
        }
    }

    @Test
    fun `다른 인스턴스가 먼저 적은 확인 행이 있어도 판정은 풀고 그 행을 그대로 둔다`() {
        HostBench(gated = true).use { bench ->
            attestedDeferredClose(bench)
            finishTasks(bench)
            val closeId = PostgresSupport.queryOne("SELECT close_id FROM mission.execution_close") { it.getLong(1) }
            Fenced.execute("INSERT INTO mission.execution_close_confirmed (close_id, instance_id) VALUES ($closeId, 'mw-other')")
            assertEquals(emptyList(), reasons(bench, HUMANOID))
            assertEquals(listOf("mw-other"), PostgresSupport.queryAll("SELECT instance_id FROM mission.execution_close_confirmed") { it.getString(1) })
            assertTrue(bench.get("/host/executions?recent=20")["unrestored"].isEmpty)
        }
    }

    /**
     * 미룬 행(스냅숏을 못 읽음)을 정지 증언으로 종결한다. 앞 인스턴스의 태스크가 하위에 남아 있고 스냅숏은 종결 뒤에 읽히게 되돌린다.
     * 종결한 인스턴스는 뒤에 그대로 돈다.
     */
    private fun attestedDeferredClose(bench: HostBench) {
        val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
        val before = bench.host.instanceId
        bench.eventually("앞 인스턴스의 태스크") { bench.tasks(HUMANOID).isNotEmpty() }
        bench.blockedSnapshots += HUMANOID
        bench.restartHost()
        val attested = bench.close("JO-1", seen = before to "exec-1", attested = true)
        assertEquals(listOf("CLOSED", "ATTESTED"), listOf("result", "basis").map { attested.body!![it].asText() }, attested.body.toString())
        bench.blockedSnapshots -= HUMANOID
        bench.idlePumps(6)
    }

    /** mimic 시계를 당겨 그 기체의 태스크를 모두 끝낸다. */
    private fun finishTasks(bench: HostBench) {
        repeat(60) {
            if (bench.tasks(HUMANOID).values.any { !it.isTerminal }) {
                bench.mimic.server.advance(Duration.ofSeconds(5))
                bench.awaitPump()
            }
        }
        assertTrue(bench.tasks(HUMANOID).values.all { it.isTerminal }, bench.tasks(HUMANOID).toString())
    }

    private fun confirmedRows(): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_close_confirmed") { it.getInt(1) }

    private fun closeRows(): Int =PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_close") { it.getInt(1) }

    private fun kinds(jobOrderId: String): List<String> =
        PostgresSupport.queryAll("SELECT kind FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id") { it.getString(1) }

    private fun reasons(bench: HostBench, robotId: String): List<String> =
        bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", robotId)).body!!["robots"].single()["reasons"].map { it.asText() }

    /** mimic 이 호스팅하는 태스크 id 와 상태. */
    private fun HostBench.tasks(robotId: String): Map<String, TaskState> =
        mimic.server.exclusive { mimic.instance(robotId)!!.tasks.all.associate { it.taskId to it.machine.state } }

    /**
     * 판단 기록이 JSON 이 아닌 일지 행을 앞 인스턴스(`mw-before`)가 받은 것으로 넣고 호스트를 다시 띄운다. 호스트는 그 행을 GAVE_UP
     * ([MissionHost.RECORD_UNREADABLE])으로 남긴다.
     */
    private fun gaveUp(bench: HostBench, jobOrderId: String, robotId: String) {
        insertJournal(jobOrderId, robotId)
        insertRecord(jobOrderId, "mw-before", "exec-7", "{")
        bench.restartHost()
        val row = bench.get("/host/executions")["restore"]["rows"].single { it["jobOrderId"].asText() == jobOrderId }
        assertEquals("GAVE_UP", row["result"].asText(), row.toString())
    }

    private fun HostBench.close(
        jobOrderId: String,
        seen: Pair<String, String> = "mw-before" to "exec-7",
        attested: Boolean = false,
        requestId: String = UUID.randomUUID().toString(),
        reason: String = "현장 확인",
    ): HostBench.Reply = post(
        "/host/job-orders/$jobOrderId/close",
        JSON.writeValueAsString(
            mapOf(
                "approverId" to "kim", "reason" to reason, "requestId" to requestId,
                "executionInstanceId" to seen.first, "executionId" to seen.second, "attestedStopped" to attested,
            ),
        ),
    )

    private companion object {
        /** 앞 인스턴스([instanceId], 심장박동 행 없음)가 받은 InspectAsset 일지 행을 넣는다. */
        fun insertJournal(jobOrderId: String, robotId: String, instanceId: String = "mw-before", evidence: String = "E0") {
            val order = JSON.writeValueAsString(JSON.readTree(inspect(jobOrderId, "T1" to "bay-7", evidence = evidence)))
            Fenced.execute(
                """
                INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
                VALUES ('$jobOrderId', '$robotId', '$order'::jsonb, 'InspectAsset', NULL, '$instanceId', 'exec-7')
                """.trimIndent(),
            )
        }

        fun insertJournal(jobOrderId: String, robotId: String, evidence: String) = insertJournal(jobOrderId, robotId, "mw-before", evidence)

        /** 작업 지시 칸이 `jobOrderId` 뿐이라 `HostRequests.jobOrder` 가 읽지 못하는 일지 행을 넣는다. */
        fun insertRawJournal(jobOrderId: String, robotId: String, instanceId: String = "mw-before") = Fenced.execute(
            """
            INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
            VALUES ('$jobOrderId', '$robotId', '{"jobOrderId":"$jobOrderId"}'::jsonb, 'InspectAsset', NULL, '$instanceId', 'exec-7')
            """.trimIndent(),
        )

        /** 심장박동이 한 시간 뒤까지 살아 있는 다른 인스턴스 행을 넣는다. */
        fun liveInstance(id: String) =
            Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('$id', clock_timestamp() + interval '1 hour')")

        /** 다른 인스턴스(`mw-writer`)가 적은 GAVE_UP 이벤트를 넣는다. */
        fun insertGaveUpEvent(jobOrderId: String) = Fenced.execute(
            "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES ('$jobOrderId', 'GAVE_UP', 'mw-writer', '일지의 작업 지시를 읽지 못했다: x')",
        )

        /** 판단 기록 한 행을 글자 그대로 넣는다. */
        fun insertRecord(jobOrderId: String, instanceId: String, executionId: String, record: String) {
            Fenced.connection().use { c ->
                c.prepareStatement(
                    "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) VALUES (?, ?, ?, ?, ?)",
                ).use { s ->
                    s.setString(1, jobOrderId)
                    s.setString(2, instanceId)
                    s.setString(3, executionId)
                    s.setString(4, dev.picasso.middleware.JudgmentRecordWire.SCHEMA_VERSION)
                    s.setString(5, record)
                    s.executeUpdate()
                }
            }
        }

        /** 종결 행을 SQL 로 넣는다(CLOSED 이벤트 포함). 요청 id 를 낸다. 시험이 표의 제약을 직접 본다. */
        fun insertClose(
            jobOrderId: String,
            basis: String,
            approverKind: String = "PERSON",
            requestId: String = UUID.randomUUID().toString(),
            reason: String = "r",
        ): String {
            Fenced.connection().use { c ->
                c.autoCommit = false
                try {
                    val eventId = c.prepareStatement(
                        "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES (?, 'CLOSED', 'mw-now', ?) RETURNING event_id",
                    ).use { s ->
                        s.setString(1, jobOrderId)
                        s.setString(2, basis)
                        s.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                    c.prepareStatement(
                        """
                        INSERT INTO mission.execution_close (event_id, job_order_id, instance_id, closed_instance_id, closed_execution_id, robot_id, restore_result,
                            restore_reason, basis, approver_id, approver_kind, reason, request_id, response_id)
                        VALUES (?, ?, 'mw-now', 'mw-before', 'exec-7', 'humanoid-01', 'GAVE_UP', 'r', ?, 'kim', ?, ?, CAST(? AS uuid), ?)
                        """.trimIndent(),
                    ).use { s ->
                        s.setLong(1, eventId)
                        s.setString(2, jobOrderId)
                        s.setString(3, basis)
                        s.setString(4, approverKind)
                        s.setString(5, reason)
                        s.setString(6, requestId)
                        s.setString(7, "close-$eventId")
                        s.executeUpdate()
                    }
                    c.commit()
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
            return requestId
        }
    }
}
