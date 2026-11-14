package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.OperatorDecision
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.MATERIAL
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 시도 뒤 실패한 자재 다루기(`pick_place` 계열)의 재작업(picasso §15.323, `ATTEMPTED_HANDLING`)을 실제 호스트·mimic 위에서 본다.
 *
 * 장면: 목적지 관측을 요구한(REQUIRED) PrepareSequencedRack 버전으로 사람형 기체에 랙 자리 하나를 내고, 도는 `pick_place` 태스크에 스킬 수준 실패
 * (`PAYLOAD_LOST`, 놓쳐서 손은 빈손이고 새 태스크는 받는다)를 강제한다. 쥔 채 실패하는 `SKILL_EXECUTION_FAILED` 는 손이 든 채라 관문이 늘 거부한다. 단위는 태스크가 나간 채 실패하고 실행은 `FAILED` 로 종결한다.
 *
 * - 물리 관문: 받을 때 손(기체 스냅숏)·목적지(셀 자리)·출발(셀 제시대)을 읽는다. 목적지가 찼거나 손을 말하지 않으면 `Refused`(사유 문장 그대로), 맞으면 받는다.
 * - 첫 발행 직전 재검사: 받은 뒤 첫 발행 전에 기체가 손을 말하지 않게 바꾸면 그 시도는 태스크 없이 `FAILED`(`REWORK_RECHECK`)로 닫히고, 후보가
 *   `recheckFailed` 로 다시 서며, 다시 재작업하면 태스크 없이 소모된 시도 다음 번호(`@r2`)로 나간다. 실패 분류 글자가 호스트의
 *   [MissionHost.REWORK_RECHECK] 와 같은지도 여기서 대는다(picasso 가 이름을 바꾸면 이 시험이 깨진다).
 *
 * 손을 지우는 것은 [HostBench.hiddenHands](옛 발신자 포트 흉내)다. 첫 발행 직전의 읽기는 호스트 잠금 아래 pump 의 지금 읽기라, 잠금을 쥔 채 판단하고
 * 손을 지우면 다음 pump 가 반드시 지운 값을 본다.
 */
class ReworkHandlingHostTest {

    private val kim = Approver("kim", ApproverKind.PERSON)
    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private fun HostBench.template(id: String): String =
        get("/host/missions/templates/$PSR")["templates"].single { it["id"].asText() == id }["definition"].asText()

    /** DATA_V1 의 목적지 관측 선언을 REQUIRED 로 바꿔 활성화한다. */
    private fun HostBench.activateRequired() {
        val definition = template("DATA_V1").replace(DECLARED, "\"destinationObservation\": \"REQUIRED\"")
        check(definition != template("DATA_V1")) { "선언을 바꾸지 못했다" }
        val saved = post("/host/missions/$PSR/drafts", JSON.writeValueAsString(mapOf("definition" to definition, "actor" to "lee", "requestId" to UUID.randomUUID().toString())))
        val draftId = saved.body!!["draft"]["draftId"].asLong()
        val mock = post("/host/missions/drafts/$draftId/mock-run", JSON.writeValueAsString(mapOf("robotIds" to listOf(HUMANOID), "requestId" to UUID.randomUUID().toString()))).body!!
        assertEquals("PASSED", mock["result"].asText(), mock.toString())
        val activation = post(
            "/host/missions/drafts/$draftId/activate",
            JSON.writeValueAsString(mapOf("actor" to "lee", "reason" to "목적지 관측 요구", "robotIds" to listOf(HUMANOID), "requestId" to UUID.randomUUID().toString())),
        ).body!!
        assertEquals("ACTIVATED", activation["result"].asText(), activation.toString())
    }

    private fun HostBench.slot(occupied: Boolean) {
        val free = """{"id":"$SLOT","occupied":false,"material":null,"observedAt":null}"""
        cellBody = if (occupied) HostBench.STANDARD_CELL.replace(free, """{"id":"$SLOT","occupied":true,"material":"$MATERIAL","observedAt":null}""") else HostBench.STANDARD_CELL
        eventually("목적지 답을 읽는다") {
            get("/host/cell")["cell"]?.get("slots")?.any { it["id"].asText() == SLOT && it["occupied"].asBoolean() == occupied } == true
        }
    }

    /** 랙 자리 하나를 내고 도는 `pick_place` 에 스킬 실패를 강제해 `FAILED` 로 종결·기록된 실행의 id. */
    private fun HostBench.failedHandling(): String {
        awaitPump()
        activateRequired()
        val submitted = post("/host/job-orders", request(rack("JO-1", SLOT), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
        val executionId = submitted["executionId"].asText()
        var running: String? = null
        repeat(40) {
            if (running != null) return@repeat
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            running = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
        }
        val task = checkNotNull(running) { "pick_place 가 RUNNING 이 되지 않았다" }
        val raised = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.forceFault("PAYLOAD_LOST", task) }
        assertTrue(raised is ForceOutcome.Raised, raised.toString())
        val done = driveUntil(executionId, settled)
        assertEquals("FAILED", done["physicalState"].asText(), done.toString())
        eventually("종결 기록") { host.footprint().settleMarks == 1 }
        return executionId
    }

    private fun HostBench.candidate(): JsonNode = get("/host/executions?recent=5")["reworkableTargets"].single()

    private fun HostBench.rework(executionId: String): JsonNode = post(
        "/host/executions/$executionId/units/$SLOT/resolve",
        JSON.writeValueAsString(linkedMapOf("decision" to "REWORK", "approverId" to "kim", "instanceId" to host.instanceId, "requestId" to null)),
    ).body!!

    @Test
    fun `시도 뒤 실패한 자재 다루기는 ATTEMPTED_HANDLING 후보이고 목적지가 찼거나 손을 말하지 않으면 거부하며 맞으면 받는다`() {
        HostBench(gated = true).use { bench ->
            val executionId = bench.failedHandling()
            val target = bench.candidate()
            assertEquals(
                listOf(SLOT, "ATTEMPTED_HANDLING", "[\"$SLOT\"]", "false"),
                listOf(target["unitId"].asText(), target["kind"].asText(), target["bundle"].toString(), target["recheckFailed"].asText()),
                target.toString(),
            )

            bench.slot(occupied = true)
            val occupied = bench.rework(executionId)
            assertEquals("Refused", occupied["result"].asText(), occupied.toString())
            assertTrue(occupied["detail"].asText().startsWith("목적지 $SLOT 가 비어 있지 않다"), occupied.toString())
            bench.slot(occupied = false)

            bench.hiddenHands += HUMANOID
            val silent = bench.rework(executionId)
            assertEquals("Refused", silent["result"].asText(), silent.toString())
            assertTrue("손을 말하지 않는다" in silent["detail"].asText(), silent.toString())
            bench.hiddenHands.clear()
            // 거부는 아무것도 바꾸지 않는다 — 후보가 그대로 남는다.
            assertEquals(target, bench.candidate())

            val accepted = bench.rework(executionId)
            assertEquals("Resolved", accepted["result"].asText(), accepted.toString())
            bench.eventually("새 시도 발행") { bench.starts.any { it == HUMANOID to "JO-1#$SLOT@r1" } }
        }
    }

    @Test
    fun `받은 자재 다루기 재작업이 첫 발행 직전 재검사를 못 지나면 REWORK_RECHECK 로 닫히고 후보가 다시 서며 다시 재작업하면 다음 시도로 나간다`() {
        HostBench(gated = true).use { bench ->
            val executionId = bench.failedHandling()
            bench.host.exclusive {
                assertEquals(MissionHost.RESOLVED, bench.host.resolve(executionId, SLOT, OperatorDecision.REWORK, kim, null).result)
                // 잠금을 쥔 채 손을 지운다. 다음 pump 의 첫 발행 직전 읽기가 이 값을 본다.
                bench.hiddenHands += HUMANOID
            }
            val closed = bench.driveUntil(executionId, settled)
            assertEquals("FAILED", closed["physicalState"].asText(), closed.toString())
            // 실패 분류 글자는 picasso 의 실제 값이고 호스트 상수와 같다.
            assertEquals(MissionHost.REWORK_RECHECK, closed["jobResponse"]["incompleteUnits"][SLOT].asText(), closed.toString())
            assertTrue(bench.starts.none { it.second.startsWith("JO-1#$SLOT@r") }, bench.starts.toString())
            val incident = bench.get("/host/incidents")["incidents"].first()
            assertEquals(listOf(SLOT, MissionHost.REWORK_RECHECK, "false"), listOf(incident["unitId"].asText(), incident["failureClass"].asText(), incident["held"].asText()), incident.toString())

            bench.eventually("종결 기록") { bench.get("/host/executions?recent=5")["reworkableTargets"].size() == 1 }
            val again = bench.candidate()
            assertEquals(listOf("ATTEMPTED_HANDLING", "true"), listOf(again["kind"].asText(), again["recheckFailed"].asText()), again.toString())
            // 운영자 보류가 아니므로 완료 확인은 받지 않는다.
            val confirm = bench.post(
                "/host/executions/$executionId/units/$SLOT/resolve",
                JSON.writeValueAsString(linkedMapOf("decision" to "CONFIRM_DONE", "approverId" to "kim", "instanceId" to bench.host.instanceId, "requestId" to null)),
            ).body!!
            assertEquals("Refused", confirm["result"].asText(), confirm.toString())

            bench.hiddenHands.clear()
            assertEquals("Resolved", bench.rework(executionId)["result"].asText())
            // 재검사가 닫은 시도(@r1)는 태스크 없이 소모됐다. 다음 시도는 @r2 다.
            bench.eventually("다음 시도 발행") { bench.starts.any { it == HUMANOID to "JO-1#$SLOT@r2" } }
            assertTrue(bench.starts.none { it.second == "JO-1#$SLOT@r1" }, bench.starts.toString())
        }
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
        const val SLOT = "RACK-204.S01"
        const val DECLARED = "\"destinationObservation\": \"OPTIONAL\""
    }
}
