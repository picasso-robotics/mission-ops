package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.OperatorDecision
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실행 호스트의 인시던트 목록·상세, 운영자 판단, 장애 주입 전달(S4a 스펙 §6, §10 의 호스트 행).
 *
 * 운영자 보류는 `ARRIVAL_WAIT_HOLD` 템플릿을 S3b 흐름으로 활성화하고 신호를 켜지 않은 채 가상 시계를 밀어 만든다. 스킬 실패는
 * 대역 mimic 엔진에 직접 강제한다(현장의 `POST /faults` 와 같은 엔진 호출, 밀기 없이 다음 시계 진행에 맡김).
 */
class IncidentResolveTest {

    private val robots = listOf(HUMANOID, QUADRUPED)
    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    /** 운영자 보류 대기 템플릿을 초안 저장, 모의 실행, 활성화한다. 새 세트라 버전 1 이다. */
    private fun HostBench.activateHold() {
        val definition = get("/host/missions/templates/$PSR")["templates"].single { it["id"].asText() == "ARRIVAL_WAIT_HOLD" }["definition"].asText()
        val saved = post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to "${UUID.randomUUID()}")))
        val draftId = saved.body!!["draft"]["draftId"].asLong()
        val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to "${UUID.randomUUID()}")))
        assertEquals("PASSED", mock.body!!["result"].asText(), mock.body.toString())
        val activation = post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "보류 시연", "robotIds" to robots, "requestId" to "${UUID.randomUUID()}")),
        )
        assertEquals("ACTIVATED", activation.body!!["result"].asText(), activation.body.toString())
        assertEquals(1, activation.body["version"].asInt())
    }

    /** 셀 대역을 한 번 읽힌 뒤 보류 버전을 활성화하고 작업 지시 하나를 낸다. 실행 id 를 돌려준다. */
    private fun HostBench.submitHeldOrder(): String {
        mimic.server.advance(Duration.ofSeconds(1))
        awaitPump()
        activateHold()
        val submitted = post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        return submitted["executionId"].asText()
    }

    /** 실행이 운영자 보류가 될 때까지 민다. 기한 20초이므로 가상 시간 30초(5초씩 여섯 번) 안에 서야 한다. */
    private fun HostBench.driveToHold(executionId: String): JsonNode {
        val held = driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 6)
        assertEquals("OPERATOR_HOLD", held["physicalState"].asText(), held.toString())
        assertEquals("OPERATOR_HOLD", held["units"][0]["state"].asText(), held.toString())
        return held
    }

    private fun HostBench.resolve(executionId: String, unitId: String, decision: String, approverId: String, requestId: String? = null): HostBench.Reply =
        post(
            "/host/executions/$executionId/units/$unitId/resolve",
            JSON.writeValueAsString(
                linkedMapOf("decision" to decision, "approverId" to approverId, "requestId" to requestId, "instanceId" to host.instanceId),
            ),
        )

    private fun HostBench.incidents(): List<JsonNode> = get("/host/incidents")["incidents"].toList()

    @Test
    fun `보류 버전의 대기가 기한 20초를 넘기면 운영자 보류이고 인시던트는 미해결이며 보류 중이고 상세가 근거와 의도를 싣는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)

            val item = bench.incidents().single()
            assertEquals(LIST_FIELDS, item.fieldNames().asSequence().toList())
            assertEquals(
                listOf(executionId, WAIT, "SIGNAL_DEADLINE", "SIGNAL", 1, 1L),
                listOf(item["executionId"].asText(), item["unitId"].asText(), item["failureClass"].asText(), item["route"].asText(), item["missionVersion"].asInt(), item["siteSettingsVersion"].asLong()),
            )
            assertEquals(true, item["unresolved"].asBoolean())
            assertTrue(item["resolution"].isNull, item.toString())
            assertTrue(item["fault"].isNull, item.toString())
            assertEquals(true, item["held"].asBoolean())
            assertEquals(false, item["confirmedWithoutEvidence"].asBoolean())

            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals(DETAIL_FIELDS, detail.fieldNames().asSequence().toList())
            assertEquals(item["incidentId"], detail["incidentId"])
            assertEquals("OPERATOR_HOLD", detail["unitState"].asText())
            assertEquals(true, detail["held"].asBoolean())
            assertEquals(true, detail["unresolved"].asBoolean())
            assertTrue(detail["resolution"].isNull && detail["fault"].isNull, detail.toString())
            assertTrue(detail["blockedBy"].isArray && detail["blockedBy"].isEmpty, detail.toString())
            assertEquals(listOf("E2", "NOT_REQUESTED"), listOf(detail["requiredEvidence"].asText(), detail["verification"].asText()))
            assertTrue(detail["reachedEvidence"].isTextual)
            assertEquals(JSON.readTree("""{"at":1,"plan":["$WAIT","$S01"],"completed":[]}"""), detail["step"])
            // 기한 시점의 신호 관측이 근거 윈도우에 든다(미들웨어가 기한에 한 번 더 남긴다).
            val deadline = detail["evidenceWindow"].filter { it["kind"].asText() == "CELL_SIGNAL" && "at deadline" in it["detail"].asText() }
            assertEquals(1, deadline.size, detail["evidenceWindow"].toString())
            assertEquals(listOf("sequence", "occurredAt", "kind", "detail", "local"), deadline.single().fieldNames().asSequence().toList())

            val intent = detail["intent"]
            assertEquals(INTENT_FIELDS, intent.fieldNames().asSequence().toList())
            assertEquals(
                listOf(PSR, 1, 1, 1L, 30L, 15L, 60L, 300L),
                listOf(
                    intent["workMasterId"].asText(), intent["orderVersion"].asInt(), intent["missionVersion"].asInt(), intent["siteSettingsVersion"].asLong(),
                    intent["evidenceBeforeSeconds"].asLong(), intent["evidenceAfterSeconds"].asLong(),
                    intent["inDoubtGraceSeconds"].asLong(), intent["stallWindowSeconds"].asLong(),
                ),
            )
            assertEquals(JSON.readTree("""[{"materialDefinitionId":"${HostBench.MATERIAL}","quantity":1}]"""), intent["materials"])
            assertEquals(listOf(S01, HostBench.SOURCE), intent["equipment"].map { it["id"].asText() })

            val missing = bench.fetch("/host/incidents/incident-999")
            assertEquals(404, missing.status)
            assertEquals("INCIDENT_NOT_FOUND", missing.body!!["error"].asText())
        }
    }

    @Test
    fun `판단 결과는 picasso 이름 그대로 Resolved NotHeld Refused 이고 Refused 와 NotHeld 는 아무것도 바꾸지 않는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)
            val incidentId = bench.incidents().single()["incidentId"].asText()

            // REST 는 늘 PERSON 승인자를 만든다. 에이전트 승인자는 호스트를 직접 불러 만든다.
            val refused = bench.host.resolve(executionId, WAIT, OperatorDecision.REWORK, Approver("planner-bot", ApproverKind.AGENT), null)
            assertEquals("Refused", refused.result)
            assertTrue(!refused.detail.isNullOrBlank() && refused.incidentId == null, refused.toString())

            val notHeld = bench.resolve(executionId, S01, "CONFIRM_DONE", "kim")
            assertEquals(200, notHeld.status)
            assertEquals(JSON.readTree("""{"result":"NotHeld","detail":null,"incidentId":null,"requestId":null}"""), notHeld.body)
            assertEquals("NotHeld", bench.resolve("exec-999", WAIT, "REWORK", "kim").body!!["result"].asText())
            val untouched = bench.incidents().single()
            assertTrue(untouched["resolution"].isNull && untouched["held"].asBoolean(), untouched.toString())
            assertEquals("OPERATOR_HOLD", bench.execution(executionId)!!["units"][0]["state"].asText())

            val requestId = UUID.randomUUID().toString()
            val resolved = bench.resolve(executionId, WAIT, "REWORK", "kim", requestId)
            assertEquals(200, resolved.status)
            assertEquals(
                JSON.readTree("""{"result":"Resolved","detail":null,"incidentId":"$incidentId","requestId":"$requestId"}"""),
                resolved.body,
            )
        }
    }

    @Test
    fun `재작업 뒤 두 번째 보류는 새 인시던트만 보류 중이고 unresolved 는 봉인 값 그대로이며 근거 없는 완료 확인이 드러난다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)
            val first = bench.incidents().single()["incidentId"].asText()

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
            val reworked = bench.incidents().single()
            assertEquals(true, reworked["unresolved"].asBoolean())
            assertEquals(false, reworked["held"].asBoolean())
            assertEquals(listOf("decision", "at", "wallClockAt", "decidedBy"), reworked["resolution"].fieldNames().asSequence().toList())
            assertEquals("REWORK", reworked["resolution"]["decision"].asText())
            assertEquals(JSON.readTree("""{"id":"kim","kind":"PERSON"}"""), reworked["resolution"]["decidedBy"])
            assertEquals(false, reworked["confirmedWithoutEvidence"].asBoolean())
            assertEquals("RUNNING", bench.execution(executionId)!!["physicalState"].asText())

            // 대기가 새로 시작되고 신호가 없으니 다시 보류가 선다.
            bench.driveToHold(executionId)
            val (second, again) = bench.incidents()
            assertEquals(first, again["incidentId"].asText())
            assertEquals(listOf(true, false), listOf(second["held"].asBoolean(), again["held"].asBoolean()))
            assertTrue(second["resolution"].isNull, second.toString())
            assertEquals("REWORK", again["resolution"]["decision"].asText())

            val confirmed = bench.resolve(executionId, WAIT, "CONFIRM_DONE", "park").body!!
            assertEquals(second["incidentId"], confirmed["incidentId"])
            val (confirmedItem, reworkedItem) = bench.incidents()
            assertEquals("CONFIRM_DONE", confirmedItem["resolution"]["decision"].asText())
            assertEquals("park", confirmedItem["resolution"]["decidedBy"]["id"].asText())
            assertEquals(true, confirmedItem["confirmedWithoutEvidence"].asBoolean())
            assertEquals(true, confirmedItem["unresolved"].asBoolean())
            assertEquals(listOf(false, false), listOf(confirmedItem["held"].asBoolean(), reworkedItem["held"].asBoolean()))
            assertEquals("REWORK", reworkedItem["resolution"]["decision"].asText())
            assertEquals("kim", reworkedItem["resolution"]["decidedBy"]["id"].asText())

            val detail = bench.get("/host/incidents/${confirmedItem["incidentId"].asText()}")
            assertEquals("DONE", detail["unitState"].asText())
            assertEquals(true, detail["confirmedWithoutEvidence"].asBoolean())
            assertEquals("NotHeld", bench.resolve(executionId, WAIT, "CONFIRM_DONE", "park").body!!["result"].asText())
        }
    }

    @Test
    fun `진행 중 스킬 실패는 근거가 없으면 GRASP_FAILED 인시던트로 남고 결함 요약과 원문을 싣되 보류가 아니다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(rack("JO-1", S01), "candidates", HUMANOID)).body!!
            val executionId = submitted["executionId"].asText()
            var taskId: String? = null
            repeat(20) {
                if (taskId == null) {
                    bench.mimic.server.advance(Duration.ofSeconds(1))
                    bench.awaitPump()
                    taskId = bench.mimic.server.exclusive {
                        bench.mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId
                    }
                }
            }
            val running = checkNotNull(taskId) { "pick_place 가 RUNNING 이 되지 않았다" }
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())

            val done = bench.driveUntil(executionId, settled)
            assertEquals("FAILED", done["units"][0]["state"].asText(), done.toString())
            val item = bench.incidents().single()
            assertEquals("GRASP_FAILED", item["failureClass"].asText())
            assertEquals(JSON.readTree("""{"failureClass":"GRASP_FAILED","errorType":"SKILL_EXECUTION_FAILED","errorHint":""}""").fieldNames().asSequence().toList(), item["fault"].fieldNames().asSequence().toList())
            assertEquals(listOf("GRASP_FAILED", "SKILL_EXECUTION_FAILED"), listOf(item["fault"]["failureClass"].asText(), item["fault"]["errorType"].asText()))
            assertEquals(listOf(false, false), listOf(item["unresolved"].asBoolean(), item["held"].asBoolean()))
            assertTrue(item["missionVersion"].isNull, item.toString())

            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals("FAILED", detail["unitState"].asText())
            assertEquals("NOT_REQUESTED", detail["verification"].asText())
            assertEquals(item["fault"]["errorType"], detail["fault"]["errorType"])
            assertTrue(detail["fault"]["references"].any { it["value"].asText() == running }, detail["fault"].toString())
            assertFalse(detail["fault"]["canContinueCurrentTask"].asBoolean())
            assertEquals("pick_place", detail["intent"]["skillType"].asText())
            assertEquals(S01, detail["intent"]["destination"].asText())
        }
    }

    @Test
    fun `로봇 수준 결함이 다음 단위를 막으면 인시던트 상세의 blockedBy 에 그 결함 원문이 실린다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val submitted = bench.post("/host/job-orders", request(HostBench.inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val executionId = submitted["executionId"].asText()
            var taskId: String? = null
            repeat(20) {
                if (taskId == null) {
                    bench.mimic.server.advance(Duration.ofSeconds(1))
                    bench.awaitPump()
                    taskId = bench.mimic.server.exclusive {
                        bench.mimic.instance(QUADRUPED)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId
                    }
                }
            }
            val running = checkNotNull(taskId) { "첫 태스크가 RUNNING 이 되지 않았다" }
            // 지울 때까지 유지되고 새 태스크를 받지 못하게 하는 로봇 수준 결함이다. 현장의 장애 주입 종류에는 없다(T2).
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())

            val blocked = bench.driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 4)
            assertEquals("OPERATOR_HOLD", blocked["physicalState"].asText(), blocked.toString())
            val item = bench.incidents().first()
            val detail = bench.get("/host/incidents/${item["incidentId"].asText()}")
            assertEquals(listOf("LOCALIZATION_LOST"), detail["blockedBy"].map { it["errorType"].asText() }, detail.toString())
            assertEquals(false, detail["blockedBy"][0]["canAcceptNewTask"].asBoolean())
            assertEquals("KIND_UNTIL_CLEARED", detail["blockedBy"][0]["activeUntilKind"].asText())
            // 막힘은 실행 수준이라 단위 보류가 아니다. 판단 대상이 아니다.
            assertEquals(false, item["held"].asBoolean())
            assertEquals("NotHeld", bench.resolve(executionId, item["unitId"].asText(), "REWORK", "kim").body!!["result"].asText())
        }
    }

    @Test
    fun `판단 요청 본문이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val instance = bench.host.instanceId
            listOf(
                """{"decision":"RELEASE","approverId":"kim","instanceId":"$instance"}""",
                """{"decision":"REWORK","instanceId":"$instance"}""",
                """{"decision":"REWORK","approverId":" ","instanceId":"$instance"}""",
                """{"decision":"REWORK","approverId":"kim","requestId":"not-a-uuid","instanceId":"$instance"}""",
                """{"decision":"REWORK","approverId":"kim","requestId":7,"instanceId":"$instance"}""",
                """["REWORK"]""",
            ).forEach { body ->
                val reply = bench.post("/host/executions/exec-1/units/$WAIT/resolve", body)
                assertEquals(400, reply.status, body)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
            }
            assertEquals(
                415,
                bench.post("/host/executions/exec-1/units/$WAIT/resolve", """{"decision":"REWORK","approverId":"kim","instanceId":"$instance"}""", contentType = "text/plain").status,
            )
        }
    }

    @Test
    fun `판단 요청에 instanceId 가 없으면 400 이고 지금 인스턴스가 아니면 409 INSTANCE_MISMATCH 이며 판단하지 않는다`() {
        HostBench().use { bench ->
            val executionId = bench.submitHeldOrder()
            bench.driveToHold(executionId)
            val path = "/host/executions/$executionId/units/$WAIT/resolve"

            listOf(
                """{"decision":"REWORK","approverId":"kim"}""",
                """{"decision":"REWORK","approverId":"kim","instanceId":null}""",
                """{"decision":"REWORK","approverId":"kim","instanceId":" "}""",
                """{"decision":"REWORK","approverId":"kim","instanceId":7}""",
            ).forEach { body ->
                val reply = bench.post(path, body)
                assertEquals(400, reply.status, body)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
            }

            val mismatch = bench.post(path, """{"decision":"REWORK","approverId":"kim","instanceId":"mw-earlier"}""")
            assertEquals(409, mismatch.status, mismatch.body.toString())
            assertEquals(listOf("error", "detail"), mismatch.body!!.fieldNames().asSequence().toList())
            assertEquals("INSTANCE_MISMATCH", mismatch.body["error"].asText())
            val untouched = bench.incidents().single()
            assertTrue(untouched["resolution"].isNull && untouched["held"].asBoolean(), untouched.toString())
            assertEquals("OPERATOR_HOLD", bench.execution(executionId)!!["units"][0]["state"].asText())

            assertEquals("Resolved", bench.resolve(executionId, WAIT, "REWORK", "kim").body!!["result"].asText())
        }
    }

    @Test
    fun `장애 주입은 현장에 그대로 넘기고 현장의 응답을 그대로 돌려주며 현장이 안 닿으면 503 CELL_SILENT 다`() {
        HostBench().use { bench ->
            val body = """{"robotId":"humanoid-01","kind":"CONNECTION","state":"OFFLINE"}"""
            val ok = bench.post("/host/faults", body)
            assertEquals(200, ok.status)
            assertEquals(JSON.readTree(bench.faultReply.second), ok.body)
            assertEquals("application/json" to body, bench.faultWrites.single())

            listOf(
                409 to """{"error":"NO_RUNNING_TASK","detail":"x"}""",
                404 to """{"error":"UNKNOWN_ROBOT","detail":"x"}""",
                400 to """{"error":"UNSUPPORTED_FAULT","detail":"x"}""",
            ).forEach { (status, reply) ->
                bench.faultReply = status to reply
                val relayed = bench.post("/host/faults", """{"robotId":"humanoid-01","kind":"SKILL_EXECUTION_FAILED"}""")
                assertEquals(status, relayed.status)
                assertEquals(JSON.readTree(reply), relayed.body)
            }
            assertEquals(415, bench.post("/host/faults", body, contentType = "text/plain").status)

            bench.stopCell()
            val silent = bench.post("/host/faults", body)
            assertEquals(503, silent.status)
            assertEquals("CELL_SILENT", silent.body!!["error"].asText())
        }
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
        const val WAIT = "rack-arrival"
        const val S01 = "RACK-204.S01"

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

        val INTENT_FIELDS = listOf(
            "workMasterId", "orderVersion", "orderParameters", "materials", "equipment", "capabilityMaxEvidence", "evidenceBeforeSeconds",
            "evidenceAfterSeconds", "skillType", "unitParameters", "source", "destination", "expectedIdentity", "missionVersion",
            "siteSettingsVersion", "inDoubtGraceSeconds", "stallWindowSeconds",
        )
    }
}
