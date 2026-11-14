package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.Route
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.SOURCE
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 운영자 실행 취소와 잔여 파지 표시(P0-b2).
 *
 * 부분 완료는 둘째 슬롯을 계획 때 실패시켜 만들거나(B형 출발 자리 없음, 빈손), 둘째 슬롯의 pick_place 가 도는 동안 대역 mimic 에 스킬
 * 실패를 강제해 만든다(든 채 실패). 든 채 실패는 mimic 규칙상 실패가 파지를 바꾸지 않아 HOLDING 이 남는다.
 */
class ExecutionControlTest {

    private fun partialOrder(jobOrderId: String) = """
        {"jobOrderId":"$jobOrderId","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E0","parameters":{},
         "materialRequirements":[{"materialDefinitionId":"$MATERIAL","quantity":1},{"materialDefinitionId":"ENGINE-COVER-B","quantity":1}],
         "equipmentRequirements":[{"id":"$S01","equipmentUse":"destination","properties":{"material":"$MATERIAL"}},
          {"id":"$S02","equipmentUse":"destination","properties":{"material":"ENGINE-COVER-B"}},
          {"id":"$SOURCE","equipmentUse":"source","properties":{"material":"$MATERIAL"}}]}
    """.trimIndent()

    /** 근거 등급 E0 의 슬롯 [slots] 랙. 셀 확인 없이 하류 완료로 끝난다. */
    private fun e0Rack(jobOrderId: String, vararg slots: String) = rack(jobOrderId, *slots).replace("\"requiredEvidence\":\"E2\"", "\"requiredEvidence\":\"E0\"")

    private fun HostBench.submit(order: String): String {
        val submitted = post("/host/job-orders", request(order, "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        return submitted["executionId"].asText()
    }

    private fun HostBench.cancel(executionId: String, approverId: String = "kim", reason: String = "부분 완료를 닫는다", instanceId: String = host.instanceId): HostBench.Reply =
        post("/host/executions/$executionId/cancel", JSON.writeValueAsString(linkedMapOf("approverId" to approverId, "reason" to reason, "instanceId" to instanceId)))

    private fun HostBench.clear(robotId: String, markerId: Long, approverId: String = "park", reason: String = "현장에서 기체 빈손 확인"): HostBench.Reply =
        post("/host/robots/$robotId/residual-holds/$markerId/clear", JSON.writeValueAsString(linkedMapOf("approverId" to approverId, "reason" to reason)))

    private fun HostBench.holds(): List<JsonNode> = get("/host/robots/residual-holds")["holds"].toList()

    private fun HostBench.judge(jobOrderId: String = "JO-9"): JsonNode =
        post("/host/eligibility", request(rack(jobOrderId, S03), "robotIds", HUMANOID)).body!!["robots"].single()

    /** [slot] 의 pick_place 태스크가 RUNNING 이 될 때까지 가상 시계를 1초씩 민다. 그 태스크 id 를 돌려준다. */
    private fun HostBench.awaitRunning(slot: String): String {
        repeat(120) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            val running = mimic.server.exclusive {
                mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING && it.taskId.endsWith(slot) }?.taskId
            }
            if (running != null) return running
        }
        error("$slot 의 pick_place 가 RUNNING 이 되지 않았다")
    }

    private fun HostBench.forceFailure(taskId: String) {
        val outcome = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", taskId) }
        assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
    }

    /** 둘째 슬롯이 든 채 실패한 부분 완료 실행. */
    private fun HostBench.heldPartial(jobOrderId: String): String {
        val executionId = submit(e0Rack(jobOrderId, S01, S02))
        forceFailure(awaitRunning(S02))
        val partial = driveUntil(executionId, SETTLED)
        assertEquals("PARTIAL", partial["physicalState"].asText(), partial.toString())
        assertEquals(listOf("DONE", "FAILED"), partial["units"].map { it["state"].asText() }, partial.toString())
        return executionId
    }

    @Test
    fun `빈손 부분 완료를 운영자가 취소하면 ABORTED 로 정착해 작업 응답이 나가고 표시 없이 기체가 다시 배정된다`() {
        HostBench().use { bench ->
            val executionId = bench.submit(partialOrder("JO-1"))
            assertEquals("PARTIAL", bench.driveUntil(executionId, SETTLED)["physicalState"].asText())
            assertEquals(listOf("진행 중인 실행이 있다: $executionId"), bench.judge()["reasons"].map { it.asText() })

            val canceled = bench.cancel(executionId)
            assertEquals(200, canceled.status, canceled.body.toString())
            assertEquals(
                JSON.readTree("""{"result":"CANCELED","executionId":"$executionId","physicalState":"PARTIAL","detail":null,"requestId":null}"""),
                canceled.body,
            )
            val cancel = bench.execution(executionId)!!["cancel"]
            assertEquals(listOf("approverId", "approverKind", "reason", "physicalState", "recordedAt"), cancel.fieldNames().asSequence().toList())
            assertEquals(listOf("kim", "PERSON", "부분 완료를 닫는다", "PARTIAL"), listOf("approverId", "approverKind", "reason", "physicalState").map { cancel[it].asText() })
            // 이미 건 취소는 다시 받지 않는다.
            assertEquals("NOT_CANCELABLE", bench.cancel(executionId).body!!["result"].asText())

            val aborted = bench.driveUntil(executionId, setOf("ABORTED"))
            assertEquals("ABORTED", aborted["jobResponse"]["physicalState"].asText(), aborted.toString())
            bench.eventually("정착 이벤트") { events("JO-1").isNotEmpty() }
            assertEquals(listOf(listOf("SETTLED", executionId, "ABORTED")), events("JO-1"))
            assertEquals("ABORTED", bench.get("/host/job-responses?jobOrderId=JO-1")["responses"][0]["physicalState"].asText())
            bench.eventually("결과 아웃박스") {
                bench.get("/host/result-events?after=0")["events"].any { it["snapshot"]["physicalState"].asText() == "ABORTED" }
            }

            assertEquals(emptyList(), bench.holds())
            assertEquals(0, count("robot_residual_hold"))
            val judged = bench.judge()
            assertEquals(true, judged["passed"].asBoolean(), judged.toString())
            assertTrue(judged["runningExecutionId"].isNull)

            val settled = bench.cancel(executionId).body!!
            assertEquals(listOf("NOT_CANCELABLE", "ABORTED"), listOf(settled["result"].asText(), settled["physicalState"].asText()))
            assertTrue(settled["detail"].asText().startsWith("종결한 실행은 취소하지 않는다"), settled.toString())
            assertEquals(1, count("execution_cancel"))
        }
    }

    @Test
    fun `든 채 끝난 부분 완료를 취소하면 정착 때 잔여 파지 표시가 서고 사람이 해소해야 기체가 다시 배정된다`() {
        HostBench().use { bench ->
            val executionId = bench.heldPartial("JO-1")
            assertEquals(listOf("진행 중인 실행이 있다: $executionId"), bench.judge()["reasons"].map { it.asText() })
            // 부분 완료는 아직 정착으로 적지 않아 표시도 없다.
            assertEquals(emptyList(), bench.holds())

            assertEquals("CANCELED", bench.cancel(executionId).body!!["result"].asText())
            bench.driveUntil(executionId, setOf("ABORTED"))
            bench.eventually("잔여 파지 표시") { bench.holds().isNotEmpty() }
            val hold = bench.holds().single()
            assertEquals(
                listOf("markerId", "robotId", "jobOrderId", "executionId", "instanceId", "unitId", "objectRef", "recordedAt"),
                hold.fieldNames().asSequence().toList(),
            )
            assertEquals(
                listOf(HUMANOID, "JO-1", executionId, bench.host.instanceId, S02),
                listOf("robotId", "jobOrderId", "executionId", "instanceId", "unitId").map { hold[it].asText() },
            )

            // 표시만 남은 기체는 «관문 확인용» 으로만 통과한다 — 일반 배정은 서지 않는다(picasso §15.317).
            val excluded = bench.judge()
            assertEquals(true to true, excluded["passed"].asBoolean() to excluded["gateTrial"].asBoolean(), excluded.toString())
            assertTrue(excluded["runningExecutionId"].isNull, excluded.toString())
            assertEquals(listOf("${MissionHost.RESIDUAL_HOLD_REASON}: JO-1 $S02"), excluded["reasons"].map { it.asText() })
            val submitted = bench.post("/host/job-orders", request(rack("JO-9", S03), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", submitted["result"].asText(), submitted.toString())
            assertEquals(listOf("${MissionHost.RESIDUAL_HOLD_REASON}: JO-1 $S02"), submitted["excluded"].single()["reasons"].map { it.asText() })

            val markerId = hold["markerId"].asLong()
            // 에이전트는 해소하지 못한다. REST 는 늘 사람 승인자를 만들므로 호스트를 직접 부른다.
            val refused = bench.host.clearResidualHold(HUMANOID, markerId, Approver("planner-bot", ApproverKind.AGENT), "빈손", null)
            assertIs<ClearOutcome.Refused>(refused)
            assertTrue(refused.reason.startsWith(MissionHost.AGENT_CLEAR_REFUSED), refused.reason)
            val wrongRobot = bench.clear(HostBench.QUADRUPED, markerId)
            assertEquals(404, wrongRobot.status)
            assertEquals("RESIDUAL_HOLD_NOT_FOUND", wrongRobot.body!!["error"].asText())
            assertEquals(1, bench.holds().size)

            // 든 채 실패라 mimic 손은 아직 HOLDING 이다. 첫 확인은 어긋남이고(B2) 그 관측을 지목해 다시 확인해야 푼다.
            val cleared = bench.clearHeldCiting(HUMANOID, markerId, reason = "현장에서 기체 빈손 확인")
            assertEquals(200, cleared.status, cleared.body.toString())
            assertEquals(listOf("hold", "clearing", "requestId"), cleared.body!!.fieldNames().asSequence().toList())
            assertEquals(hold, cleared.body["hold"])
            assertEquals(listOf("park", "PERSON", "현장에서 기체 빈손 확인"), listOf("approverId", "approverKind", "reason").map { cleared.body["clearing"][it].asText() })
            assertEquals(emptyList(), bench.holds())
            assertEquals(true to false, bench.judge().let { it["passed"].asBoolean() to it["gateTrial"].asBoolean() })

            val again = bench.clear(HUMANOID, markerId)
            assertEquals(409, again.status)
            assertEquals("RESIDUAL_HOLD_ALREADY_CLEARED", again.body!!["error"].asText())
            assertEquals(1, count("robot_residual_hold_clearing"))
            assertEquals(1, count("robot_residual_hold_conflict"))
        }
    }

    /** 슬롯 하나 랙을 든 채 실패로 정착시킨다. 그 표시 id 를 돌려준다. mimic 손은 HOLDING 으로 남는다(실패는 파지를 바꾸지 않는다). */
    private fun HostBench.heldFailure(jobOrderId: String): Long {
        val executionId = submit(e0Rack(jobOrderId, S01))
        forceFailure(awaitRunning(S01))
        assertEquals("FAILED", driveUntil(executionId, SETTLED)["physicalState"].asText())
        eventually("잔여 파지 표시") { holds().isNotEmpty() }
        return holds().single()["markerId"].asLong()
    }

    /**
     * B2: 발신자가 지금 든 채라고 말하는 기체의 빈손 확인은 해소하지 않고 그 관측을 충돌 행으로 남긴다. 같은 요청 id 의 재전송은 같은 충돌이고, 저장된
     * 충돌과 다른 지목(물품 이름, 승인자, 없는 충돌)은 400 이며 아무것도 적지 않는다. 재기동 뒤에도 그 충돌을 지목한 새 요청은 어긋남을 알고 해소한다.
     */
    @Test
    fun `든 채인 기체의 빈손 확인은 충돌로 남고 그 충돌을 지목한 재확인만 어긋남을 알고 해소한다`() {
        HostBench().use { bench ->
            val markerId = bench.heldFailure("JO-1")
            val first = UUID.randomUUID().toString()

            // (a) 지목 없는 확인 → 409, 충돌 1행, 해소 0행, 표시 열림
            val refused = bench.clearHold(HUMANOID, markerId, requestId = first)
            assertEquals(409, refused.status, refused.body.toString())
            val body = refused.body!!
            assertEquals(
                listOf("error", "detail", "robotId", "markerId", "conflictRequestId", "observation"),
                body.fieldNames().asSequence().toList(),
            )
            assertEquals(listOf("RESIDUAL_HOLD_OBSERVATION_MISMATCH", HUMANOID, first), listOf(body["error"], body["robotId"], body["conflictRequestId"]).map { it.asText() })
            assertEquals(markerId, body["markerId"].asLong())
            val seen = body["observation"]
            assertEquals(
                listOf(
                    "observedKind", "observedObjectRef", "observedDetail", "snapshotSession", "snapshotSequence", "snapshotOccurredAt",
                    "hostReadStartedAt", "hostReadReceivedAt",
                ),
                seen.fieldNames().asSequence().toList(),
            )
            assertEquals("HOLDING", seen["observedKind"].asText())
            assertTrue(!seen["hostReadReceivedAt"].isNull && seen["snapshotSequence"].isTextual, seen.toString())
            assertTrue("현장에서 빈손을 확인했으면" in body["detail"].asText(), body.toString())
            assertEquals(1 to 0, count("robot_residual_hold_conflict") to count("robot_residual_hold_clearing"))
            assertEquals(listOf(markerId), bench.holds().map { it["markerId"].asLong() })
            assertTrue(bench.get("/host/robots/$HUMANOID/residual-holds/$markerId")["clearing"].isNull)

            // (b) 같은 요청 id 재전송 → 같은 409, 충돌 행 그대로
            val again = bench.clearHold(HUMANOID, markerId, requestId = first)
            assertEquals(409 to body, again.status to again.body)
            assertEquals(1, count("robot_residual_hold_conflict"))

            // (d) 대조군: 저장값과 다른 지목, 다른 승인자, 없는 충돌은 400 이고 아무것도 적지 않는다
            val cited = HostBench.cite(body)
            listOf(
                bench.clearHold(HUMANOID, markerId, cited = cited + ("objectRef" to "OTHER-9")),
                bench.clearHold(HUMANOID, markerId, approverId = "lee", cited = cited),
                bench.clearHold(HUMANOID, markerId, cited = cited + ("conflictRequestId" to UUID.randomUUID().toString())),
            ).forEach { reply ->
                assertEquals(400, reply.status, reply.body.toString())
                assertEquals("RESIDUAL_HOLD_CITATION_INVALID", reply.body!!["error"].asText())
            }
            assertEquals(1 to 0, count("robot_residual_hold_conflict") to count("robot_residual_hold_clearing"))
            assertEquals(1, bench.holds().size)

            // (c) 재기동 뒤 그 충돌을 지목한 새 요청 → 200, 해소 행 HOLDING·어긋남 인지·충돌 연결
            bench.restartHost()
            val second = UUID.randomUUID().toString()
            val cleared = bench.clearHold(HUMANOID, markerId, requestId = second, cited = cited)
            assertEquals(200, cleared.status, cleared.body.toString())
            val clearing = cleared.body!!["clearing"]
            assertEquals(
                listOf(
                    "approverId", "approverKind", "reason", "clearedAt", "requestId", "observation", "conflictRequestId", "acknowledgedMismatch",
                ),
                clearing.fieldNames().asSequence().toList(),
            )
            assertEquals(listOf(second, first, "HOLDING"), listOf(clearing["requestId"], clearing["conflictRequestId"], clearing["observation"]["observedKind"]).map { it.asText() })
            assertEquals(true, clearing["acknowledgedMismatch"].asBoolean())
            assertEquals(second, cleared.body["requestId"].asText())
            assertEquals(
                listOf(listOf("HOLDING", "true", first, second)),
                PostgresSupport.queryAll(
                    "SELECT observed_kind, acknowledged_mismatch, conflict_request_id, request_id FROM mission.robot_residual_hold_clearing",
                ) { listOf(it.getString(1), it.getBoolean(2).toString(), it.getString(3), it.getString(4)) },
            )
            assertEquals(emptyList(), bench.holds())
            assertEquals(clearing, bench.get("/host/robots/$HUMANOID/residual-holds/$markerId")["clearing"])
            assertEquals(404, bench.fetch("/host/robots/$QUADRUPED/residual-holds/$markerId").status)
            assertEquals(1, count("robot_residual_hold_conflict"))
        }
    }

    /**
     * B2: 관측 JSON 의 순번은 10진 문자열이라 2^53 을 넘어도 왕복에서 바뀌지 않는다. 시험 스레드의 스냅숏 읽기만 큰 순번으로 바꾸고 호스트를 직접 부른다.
     * 409 본문의 순번 문자열로 만든 지목을 요청 본문 읽기로 되읽어 다시 확인하면 어긋남을 알고 해소한다.
     */
    @Test
    fun `2의 53승을 넘는 스냅숏 순번도 문자열로 왕복해 지목 재확인이 통과한다`() {
        HostBench(gated = true).use { bench ->
            val markerId = bench.heldFailure("JO-1")
            val big = 9_007_199_254_740_993L
            bench.snapshotOverride = Thread.currentThread() to { read -> read?.copy(sequence = big) }
            val first = UUID.randomUUID().toString()
            val refused = bench.host.clearResidualHold(HUMANOID, markerId, Approver("park", ApproverKind.PERSON), "빈손 확인", first)
            assertIs<ClearOutcome.ObservationMismatch>(refused)
            assertEquals(big, refused.observation.snapshotSequence)
            // 같은 요청 id 재전송(REST)은 저장된 충돌을 그대로 돌려준다. 순번은 문자열이다.
            val body = bench.clearHold(HUMANOID, markerId, requestId = first).body!!
            assertEquals("9007199254740993", body["observation"]["snapshotSequence"].textValue(), body.toString())
            val request = HostRequests.clearing(
                JSON.valueToTree(linkedMapOf("approverId" to "park", "reason" to "빈손 확인", "requestId" to UUID.randomUUID().toString(), "cited" to HostBench.cite(body))),
            )
            assertEquals(big, request.cited!!.sequence)
            val cleared = bench.host.clearResidualHold(HUMANOID, markerId, Approver("park", ApproverKind.PERSON), "빈손 확인", request.requestId, request.cited)
            assertIs<ClearOutcome.Cleared>(cleared)
            assertEquals(true to big, cleared.view.clearing.acknowledgedMismatch to cleared.view.clearing.observation.snapshotSequence)
            assertEquals("9007199254740993", bench.get("/host/robots/$HUMANOID/residual-holds/$markerId")["clearing"]["observation"]["snapshotSequence"].textValue())
            // 하위 호환: JSON 정수 순번도 받는다.
            val legacy = HostRequests.clearing(JSON.readTree("""{"approverId":"p","reason":"r","cited":{"conflictRequestId":"$CONFLICT","kind":"HOLDING","sequence":12}}"""))
            assertEquals(12L, legacy.cited!!.sequence)
        }
    }

    /**
     * B2: 표시 조회(GET)는 호스트 잠금을 잡는다. 해소는 잠금 안에서 스냅숏을 읽느라 늦을 수 있어, 잠금 없이 읽으면 운영 서비스의 응답 없음 뒤 재조회가 처리 중인
     * 해소를 «해소 행 없음» 으로 확정한다. 잠금을 쥔 동안 낸 조회는 기다렸다가 그 사이 적은 해소를 본다.
     */
    @Test
    fun `표시 조회는 호스트 잠금을 기다려 처리 중인 해소를 없음으로 내지 않는다`() {
        HostBench().use { bench ->
            val markerId = bench.heldFailure("JO-1")
            val refused = bench.clearHold(HUMANOID, markerId).body!!
            val request = UUID.randomUUID()
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val read = bench.host.exclusive {
                    val pending = pool.submit<JsonNode> { bench.get("/host/robots/$HUMANOID/residual-holds/$markerId") }
                    Thread.sleep(500)
                    assertTrue(!pending.isDone, "잠금을 쥔 동안 조회가 끝났다")
                    // 잠금은 재진입된다. 해소를 잠금 안에서 마친 뒤 놓는다.
                    val cited = HostRequests.clearing(JSON.valueToTree(linkedMapOf("approverId" to "park", "reason" to "r", "cited" to HostBench.cite(refused)))).cited
                    assertIs<ClearOutcome.Cleared>(bench.host.clearResidualHold(HUMANOID, markerId, Approver("park", ApproverKind.PERSON), "r", request.toString(), cited))
                    pending
                }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(request.toString(), read["clearing"]["requestId"].asText(), read.toString())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `든 채 실패로 정착한 실행의 표시는 재기동해도 기체를 빼고 해소도 재기동 뒤까지 남으며 표시는 작업 지시마다 하나다`() {
        HostBench().use { bench ->
            val executionId = bench.submit(e0Rack("JO-1", S01))
            bench.forceFailure(bench.awaitRunning(S01))
            assertEquals("FAILED", bench.driveUntil(executionId, SETTLED)["physicalState"].asText())
            bench.eventually("잔여 파지 표시") { bench.holds().isNotEmpty() }
            val markerId = bench.holds().single()["markerId"].asLong()
            // 정착한 실행은 취소를 받지 않고 아무것도 적지 않는다.
            val settled = bench.cancel(executionId)
            assertEquals(200, settled.status, settled.body.toString())
            assertEquals(listOf("NOT_CANCELABLE", "FAILED"), listOf(settled.body!!["result"].asText(), settled.body["physicalState"].asText()))
            assertEquals(0, count("execution_cancel"))
            // 같은 작업 지시의 리비전도 새 배정이라 빠진다. 든 채인 기체에 같은 pick_place 를 다시 보내지 않는다.
            val revised = bench.post("/host/job-orders", request(e0Rack("JO-1", S01).replace("\"version\":1", "\"version\":2"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", revised["result"].asText(), revised.toString())
            assertEquals(listOf("${MissionHost.RESIDUAL_HOLD_REASON}: JO-1 $S01"), revised["excluded"].single()["reasons"].map { it.asText() })

            bench.restartHost()
            assertEquals(listOf(markerId), bench.holds().map { it["markerId"].asLong() })
            assertEquals(listOf("${MissionHost.RESIDUAL_HOLD_REASON}: JO-1 $S01"), bench.judge()["reasons"].map { it.asText() })
            // 같은 작업 지시의 표시를 다시 적으면 아무것도 적지 않는다.
            assertNull(bench.recordsMark("JO-1", executionId))
            assertEquals(1, count("robot_residual_hold"))

            assertEquals(200, bench.clearHeldCiting(HUMANOID, markerId).status)
            bench.restartHost()
            assertEquals(emptyList(), bench.holds())
            assertEquals(true, bench.judge()["passed"].asBoolean(), bench.judge().toString())
        }
    }

    /**
     * picasso 는 이미 실행이 있는 작업 지시의 후보 목록을 읽지 않고 그 실행의 기체에서 개정한다. 그래서 개정 여부는 후보가 아니라 그
     * 기체 하나의 호스트 판정으로 가른다. 여기서는 그 기체의 앞 작업 지시에 적은 잔여 파지 표시가 그 기체를 뺀다(표시는 기동 때 읽으므로
     * 적고 재기동한다). 통과하는 다른 후보가 있어도 개정판은 그 기체로 가지 않는다. 개정이 아닌 같은 판 재제출은 그 판정에 걸리지 않는다.
     */
    @Test
    fun `기존 실행의 기체가 호스트 판정으로 빠지면 통과하는 다른 후보가 있어도 개정하지 않고 UNASSIGNED 에 그 기체의 판정을 실으며 같은 판 재제출은 판정 없이 IDEMPOTENT 다`() {
        HostBench().use { bench ->
            val earlier = bench.submit(e0Rack("JO-0", S01))
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(earlier, SETTLED)["physicalState"].asText())
            bench.submit(inspect("JO-1", "T1" to "bay-7"))
            assertEquals(S01, bench.records().markResidualHold(HUMANOID, "JO-0", earlier, bench.host.instanceId, S01, null)!!.unitId)
            bench.restartHost()
            val executionId = bench.get("/host/executions")["restore"]["rows"].single()
                .let { row -> assertEquals("RESTORED", row["result"].asText(), row.toString()); row["executionId"].asText() }
            val hold = "${MissionHost.RESIDUAL_HOLD_REASON}: JO-0 $S01"

            val revised = bench.post(
                "/host/job-orders",
                request(inspect("JO-1", "T1" to "bay-7").replace("\"version\":1", "\"version\":2"), "candidates", QUADRUPED),
            ).body!!
            assertEquals("UNASSIGNED", revised["result"].asText(), revised.toString())
            assertTrue(revised["executionId"].isNull && revised["robotId"].isNull, revised.toString())
            assertEquals(0, revised["refusals"].size(), revised.toString())
            val current = revised["excluded"].single()
            assertEquals(HUMANOID, current["robotId"].asText(), revised.toString())
            // 그 실행 자신은 도는 실행 사유가 되지 않는다. 사유는 표시 하나다.
            assertTrue(current["runningExecutionId"].isNull, revised.toString())
            assertEquals(listOf(hold), current["reasons"].map { it.asText() })

            assertEquals(HUMANOID, bench.execution(executionId)!!["robotId"].asText())
            assertEquals(0, count("execution_journal_revision"))

            // 같은 판은 개정이 아니라 로봇 명령이 없다. 그 기체가 빠져 있어도 판정 없이 picasso 의 IDEMPOTENT 를 그대로 낸다.
            val same = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", QUADRUPED)).body!!
            assertEquals(listOf("IDEMPOTENT", executionId, HUMANOID), listOf("result", "executionId", "robotId").map { same[it].asText() }, same.toString())
            assertEquals(0, same["excluded"].size(), same.toString())
        }
    }

    /**
     * 기존 실행 자신이 쥔 기체는 그 실행의 개정을 막지 않는다. 운영 서비스는 판정에서 빠진 기체를 후보로 넘기지 않으므로 후보가 그 기체를
     * 빼거나 다 빠져도 개정은 그 실행의 기체에서 이어진다.
     */
    @Test
    fun `부분 완료 실행의 개정판은 제 실행이 쥔 기체로 빠지지 않아 후보가 다 빠져도 그 기체에서 개정한다`() {
        HostBench().use { bench ->
            val executionId = bench.submit(partialOrder("JO-1"))
            assertEquals("PARTIAL", bench.driveUntil(executionId, SETTLED)["physicalState"].asText())

            val revised = bench.post(
                "/host/job-orders",
                request(partialOrder("JO-1").replace("\"version\":1", "\"version\":2"), "candidates", QUADRUPED),
            ).body!!
            assertEquals(listOf("ACCEPTED", executionId, HUMANOID), listOf("result", "executionId", "robotId").map { revised[it].asText() }, revised.toString())
            assertEquals(0, revised["excluded"].size(), revised.toString())
            assertEquals(listOf(2), PostgresSupport.queryAll(
                "SELECT version FROM mission.execution_journal_revision WHERE job_order_id = 'JO-1' ORDER BY revision_id",
            ) { it.getInt(1) })
        }
    }

    @Test
    fun `취소는 정착한 실행과 없는 실행을 받지 않고 다른 인스턴스는 409 이며 에이전트 승인자는 거부한다`() {
        HostBench().use { bench ->
            val executionId = bench.submit(partialOrder("JO-1"))
            assertEquals("NOT_FOUND", bench.cancel("exec-999").body!!["result"].asText())
            val mismatch = bench.cancel(executionId, instanceId = "mw-earlier")
            assertEquals(409, mismatch.status)
            assertEquals("INSTANCE_MISMATCH", mismatch.body!!["error"].asText())

            val refused = bench.host.cancel(executionId, Approver("planner-bot", ApproverKind.AGENT), "닫기", null)
            assertEquals(CancelResult.REFUSED, refused.result)
            assertTrue(refused.detail!!.startsWith(MissionHost.AGENT_CANCEL_REFUSED), refused.toString())
            assertEquals(0, count("execution_cancel"))
            assertTrue(bench.execution(executionId)!!["cancel"].isNull)
        }
    }

    @Test
    fun `취소와 해소 요청 본문이 틀리면 400 BAD_REQUEST 이고 JSON 이 아니면 415 다`() {
        HostBench().use { bench ->
            val instance = bench.host.instanceId
            listOf(
                """{"reason":"r","instanceId":"$instance"}""",
                """{"approverId":"kim","reason":" ","instanceId":"$instance"}""",
                """{"approverId":"kim","reason":"r"}""",
                """{"approverId":"kim","reason":"r","instanceId":"$instance","requestId":"not-a-uuid"}""",
                """["kim"]""",
            ).forEach { body ->
                val reply = bench.post("/host/executions/exec-1/cancel", body)
                assertEquals(400, reply.status, body)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), body)
            }
            listOf(
                "1" to """{"reason":"r"}""",
                "1" to """{"approverId":"kim"}""",
                "x" to """{"approverId":"kim","reason":"r"}""",
                "0" to """{"approverId":"kim","reason":"r"}""",
                "1" to """{"approverId":"kim","reason":"r","cited":"x"}""",
                "1" to """{"approverId":"kim","reason":"r","cited":{"conflictRequestId":"not-a-uuid","kind":"HOLDING"}}""",
                "1" to """{"approverId":"kim","reason":"r","cited":{"conflictRequestId":"$CONFLICT"}}""",
                "1" to """{"approverId":"kim","reason":"r","cited":{"conflictRequestId":"$CONFLICT","kind":"HOLDING","sequence":1.5}}""",
                "1" to """{"approverId":"kim","reason":"r","cited":{"conflictRequestId":"$CONFLICT","kind":"HOLDING","sequence":"12x"}}""",
                "1" to """{"approverId":"kim","reason":"r","cited":{"conflictRequestId":"$CONFLICT","kind":"HOLDING","objectRef":3}}""",
            ).forEach { (marker, body) ->
                val reply = bench.post("/host/robots/$HUMANOID/residual-holds/$marker/clear", body)
                assertEquals(400, reply.status, "$marker $body")
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText())
            }
            assertEquals(404, bench.clear(HUMANOID, 7).status)
            assertEquals(415, bench.post("/host/executions/exec-1/cancel", """{"approverId":"kim","reason":"r","instanceId":"$instance"}""", contentType = "text/plain").status)
            assertEquals(415, bench.post("/host/robots/$HUMANOID/residual-holds/1/clear", """{"approverId":"kim","reason":"r"}""", contentType = "text/plain").status)
        }
    }

    @Test
    fun `잔여 파지 단위는 로봇 단위의 마지막 파지 관측이고 플릿 단위의 HOLDING 은 보지 않는다`() {
        fun unit(id: String, route: Route, hold: HoldKind?) = ExecutionUnit(
            id, route, "s", emptyMap(), null, null, null,
            hold = hold?.let { HoldState.newBuilder().setKind(it).setObjectRef("C-1").build() } ?: HoldState.getDefaultInstance(),
        )
        val robotHeld = unit("r1", Route.ROBOT, HoldKind.HOLD_KIND_HOLDING)
        val robotEmpty = unit("r2", Route.ROBOT, HoldKind.HOLD_KIND_EMPTY)
        val fleetHeld = unit("f1", Route.FLEET, HoldKind.HOLD_KIND_HOLDING)
        val unobserved = unit("r3", Route.ROBOT, null)

        assertNull(MissionHost.residualHoldUnit(listOf(robotEmpty, fleetHeld)))
        assertNull(MissionHost.residualHoldUnit(listOf(fleetHeld)))
        assertSame(robotHeld, MissionHost.residualHoldUnit(listOf(robotHeld, fleetHeld)))
        assertSame(robotHeld, MissionHost.residualHoldUnit(listOf(robotHeld, unobserved)))
        assertNull(MissionHost.residualHoldUnit(listOf(robotHeld, robotEmpty)))
        assertNull(MissionHost.residualHoldUnit(emptyList()))
    }

    private fun HostBench.records() = bean(dev.picasso.ops.host.store.HostRecords::class.java)

    private fun HostBench.recordsMark(jobOrderId: String, executionId: String) =
        records().markResidualHold(HUMANOID, jobOrderId, executionId, host.instanceId, S01, "C-1")

    private companion object {
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        const val S03 = "RACK-204.S03"
        const val CONFLICT = "11111111-1111-1111-1111-111111111111"
        val SETTLED = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

        /** 그 작업 지시의 일지 이벤트(종류, 실행 id, 사유). */
        fun events(jobOrderId: String): List<List<String?>> = PostgresSupport.queryAll(
            "SELECT kind, execution_id, detail FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { listOf(it.getString(1), it.getString(2), it.getString(3)) }

        fun count(table: String): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.$table") { it.getInt(1) }
    }
}
