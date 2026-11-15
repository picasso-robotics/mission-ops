package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.mimic.engine.ForceOutcome
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.mission.MockRunner
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 임무 정의의 반복 그룹·once·플릿 경로(picasso §15.325)를 실행 호스트의 저장·검증·모의 실행·활성화·접수로 끝까지 민다.
 *
 * 그룹 정의는 화면 «대상마다 이동 뒤 점검» 템플릿과 같은 글자의 시험 자원 `mission-profile-visit.json` 이다(picasso
 * `MissionFixtures.INSPECT_ASSET` 모양). 선행(`after`)은 picasso 내부 칸이라 실행 본문에 실리지 않으므로 동작으로 본다 — 사족 기체의
 * 첫 이동(`T1.travel`)에 로봇 수준 결함을 강제해 실패시키고 감수하면 T1 점검이 태스크 없이 `PREDECESSOR_NOT_DONE` 으로 걸러진다.
 */
class MissionDefinitionGroupsHostTest {

    private val robots = listOf(HUMANOID, QUADRUPED)
    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private fun HostBench.saveDraft(workMaster: String, definition: String, sampleOrder: JsonNode?): Long {
        val body = linkedMapOf<String, Any>("definition" to definition, "actor" to "lee", "requestId" to UUID.randomUUID().toString())
        sampleOrder?.let { body["sampleOrder"] = it }
        val saved = post("/host/missions/$workMaster/drafts", JSON.writeValueAsString(body))
        assertEquals(200, saved.status, saved.body.toString())
        return saved.body!!["draft"]["draftId"].asLong()
    }

    private fun HostBench.step(draftId: Long, action: String, extra: Map<String, String> = emptyMap()): HostBench.Reply = post(
        "/host/missions/drafts/$draftId/$action",
        JSON.writeValueAsString(mapOf("robotIds" to robots, "requestId" to UUID.randomUUID().toString()) + extra),
    )

    private fun HostBench.activate(draftId: Long): JsonNode =
        step(draftId, "activate", mapOf("actor" to "lee", "reason" to "반복 그룹 정의 도입")).also { assertEquals(200, it.status, it.body.toString()) }.body!!

    private fun order(jobOrderId: String, workMaster: String): ObjectNode = JSON.readTree(
        """{"jobOrderId":"$jobOrderId","workMasterId":"$workMaster","version":1,"requiredEvidence":"E0","parameters":{},"materialRequirements":[],""" +
            """"equipmentRequirements":[{"id":"T1","equipmentUse":"mission_target","properties":{"location":"bay-7"}},""" +
            """{"id":"T2","equipmentUse":"mission_target","properties":{"location":"dock-3"}}]}""",
    ) as ObjectNode

    private fun HostBench.runningTask(): String {
        repeat(20) {
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            mimic.server.exclusive { mimic.instance(QUADRUPED)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId }
                ?.let { return it }
        }
        error("태스크가 RUNNING 이 되지 않았다")
    }

    @Test
    fun `반복 그룹 정의는 검증 모의 실행 활성화를 지나고 접수한 작업 지시는 대상마다 이동 뒤 점검이며 이동이 실패한 대상의 점검은 선행으로 걸러진다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val profile = JSON.readTree(javaClass.getResourceAsStream("/mission-profile-visit.json"))
            val wm = profile["workMasterId"].asText()
            val draftId = bench.saveDraft(wm, profile["definition"].toString(), order("SAMPLE", wm))
            val validated = bench.step(draftId, "validate").body!!
            assertEquals("PASSED", validated["result"].asText(), validated.toString())
            val mock = bench.step(draftId, "mock-run").body!!
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            assertEquals(
                listOf("T1.travel", "T1", "T2.travel", "T2"),
                mock["mockRun"]["result"]["units"].map { it["unitId"].asText() },
                mock.toString(),
            )
            val activation = bench.activate(draftId)
            assertEquals(listOf("ACTIVATED", "1"), listOf(activation["result"].asText(), activation["version"].asText()), activation.toString())

            val submitted = bench.post("/host/job-orders", request(order("JO-1", wm).put("missionVersion", "1").toString(), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val executionId = submitted["executionId"].asText()
            val planned = bench.execution(executionId)!!
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), planned["units"].map { it["unitId"].asText() }, planned.toString())
            assertEquals(listOf("navigate_to", "inspect", "navigate_to", "inspect"), planned["units"].map { it["skillType"].asText() })

            // 첫 이동에 로봇 수준 결함을 강제한다. 다음 단위(T1 점검) 시작 전에 실행이 운영자 보류에 선다.
            val running = bench.runningTask()
            // 결함을 거는 태스크는 첫 단위 T1.travel 의 이동이다 — 기체의 태스크는 아직 그것 하나이고 실행 본문의 T1.travel 이 RUNNING 이다.
            assertEquals(
                listOf(running to "navigate_to"),
                bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.all.map { it.taskId to it.skillType } },
            )
            val atFault = bench.execution(executionId)!!["units"].associate { it["unitId"].asText() to it["state"].asText() }
            assertEquals("RUNNING", atFault["T1.travel"], atFault.toString())
            assertEquals("PENDING", atFault["T1"], atFault.toString())
            val outcome = bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.forceFault("LOCALIZATION_LOST", running) }
            assertTrue(outcome is ForceOutcome.Raised, outcome.toString())
            val blocked = bench.driveUntil(executionId, settled + "OPERATOR_HOLD", rounds = 4)
            assertEquals("OPERATOR_HOLD", blocked["physicalState"].asText(), blocked.toString())
            val released = bench.post(
                "/host/executions/$executionId/release",
                JSON.writeValueAsString(
                    linkedMapOf(
                        "approverId" to "kim", "reason" to "현장에서 기체 상태를 확인했다", "instanceId" to bench.host.instanceId,
                        "sawFaults" to blocked["blockedFaults"].map { mapOf("errorType" to it["errorType"].asText(), "references" to it["references"]) },
                        "requestId" to UUID.randomUUID().toString(),
                    ),
                ),
            )
            assertEquals("RELEASED", released.body!!["result"].asText(), released.body.toString())

            val done = bench.driveUntil(executionId, settled)
            assertEquals("PARTIAL", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2"), done["units"].map { it["unitId"].asText() })
            assertEquals(listOf("FAILED", "FAILED", "DONE", "DONE"), done["units"].map { it["state"].asText() }, done.toString())
            assertEquals(Middleware.PREDECESSOR_NOT_DONE, done["jobResponse"]["incompleteUnits"].path("T1").asText(), done.toString())
            // T1 점검은 태스크 없이 걸러졌다 — 기체에 나간 태스크는 T1 이동, T2 이동, T2 점검 셋뿐이고 점검은 하나다.
            assertEquals(
                listOf("navigate_to", "navigate_to", "inspect"),
                bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.all.map { it.skillType } },
            )
        }
    }

    @Test
    fun `플릿 경로 노드를 든 정의는 검증을 지나도 모의 실행이 플릿 경로 실패라 활성화가 MOCK_RUN_REQUIRED 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val first = """{"from":"FIRST_ID","equipmentUse":"source"}"""
            val destination = """{"from":"FIRST_ID","equipmentUse":"destination"}"""
            val container = """{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"material"}"""
            val fleet = """{"schemaVersion":1,"workMasterId":"${PrepareSequencedRack.WORK_MASTER}","maxEvidence":"E2","preferredOptionals":{},"steps":[""" +
                """{"kind":"unit","id":"deliver","once":true,"route":"FLEET","skill":"transport","requires":[$first,$destination,$container],""" +
                """"whenMissing":{"failureClass":"INCOMPLETE_ORDER","parameters":[]},""" +
                """"unitId":{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"material","else":{"from":"ORDER_ID"}},""" +
                """"parameters":{"container":$container,"source":$first,"destination":$destination},""" +
                """"expectedIdentity":$container,"source":$first,"destination":$destination}]}"""
            val draftId = bench.saveDraft(PrepareSequencedRack.WORK_MASTER, fleet, null)
            val validated = bench.step(draftId, "validate").body!!
            assertEquals("PASSED", validated["result"].asText(), validated.toString())

            val mock = bench.step(draftId, "mock-run").body!!
            assertEquals("FAILED", mock["result"].asText(), mock.toString())
            val result = mock["mockRun"]["result"]
            assertEquals(listOf("FLEET_ROUTE", "${MockRunner.FLEET_ROUTE_DETAIL}: deliver"), listOf(result["failure"].asText(), result["detail"].asText()))
            assertTrue(result["units"].isEmpty && result["physicalState"].isNull, result.toString())

            val activation = bench.activate(draftId)
            assertEquals("MOCK_RUN_REQUIRED", activation["result"].asText(), activation.toString())
            assertTrue(activation["version"].isNull, activation.toString())
        }
    }
}
