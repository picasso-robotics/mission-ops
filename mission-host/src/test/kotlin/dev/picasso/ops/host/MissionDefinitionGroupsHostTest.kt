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
import dev.picasso.ops.host.mission.MissionVersions
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
            assertEquals(listOf("ROBOT", "ROBOT", "ROBOT", "ROBOT"), planned["units"].map { it["route"].asText() }, planned.toString())

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
    fun `플릿 경로 노드를 든 정의는 모의 실행이 인프로세스 플릿 미믹으로 E2 통과해도 플릿 포트가 없는 호스트라 활성화가 FLEET_PORT_ABSENT 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            // picasso `MissionFixtures.DELIVER_CONTAINER` 모양 — 첫 출발 설비의 container 속성이 용기다.
            val first = """{"from":"FIRST_ID","equipmentUse":"source"}"""
            val destination = """{"from":"FIRST_ID","equipmentUse":"destination"}"""
            val container = """{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"container"}"""
            val fleet = """{"schemaVersion":1,"workMasterId":"${PrepareSequencedRack.WORK_MASTER}","maxEvidence":"E2","preferredOptionals":{},"steps":[""" +
                """{"kind":"unit","id":"deliver","once":true,"route":"FLEET","skill":"transport","requires":[$first,$destination,$container],""" +
                """"whenMissing":{"failureClass":"INCOMPLETE_ORDER","parameters":[]},""" +
                """"unitId":{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"container","else":{"from":"ORDER_ID"}},""" +
                """"parameters":{"container":$container,"source":$first,"destination":$destination},""" +
                """"expectedIdentity":$container,"source":$first,"destination":$destination}]}"""
            val draftId = bench.saveDraft(PrepareSequencedRack.WORK_MASTER, fleet, null)
            val validated = bench.step(draftId, "validate").body!!
            assertEquals("PASSED", validated["result"].asText(), validated.toString())
            // 모의 실행 전에도 플릿 포트 없음이 앞선다(판정 순서: 다시 검증 → 플릿 포트 → 모의 실행).
            val early = bench.activate(draftId)
            assertEquals(MissionVersions.FLEET_PORT_ABSENT, early["result"].asText(), early.toString())
            assertTrue(early["lastMockRun"].isNull, early.toString())

            // 표본은 셀 대역 스냅숏으로 만들고 플릿 정의라 출발 자리에 용기 속성을 싣는다.
            val mock = bench.step(draftId, "mock-run").body!!
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val unit = mock["mockRun"]["result"]["units"].single()
            assertEquals(
                listOf("${MockRunner.SAMPLE_CONTAINER_PREFIX}$draftId", "FLEET", "DONE", "E2"),
                listOf(unit["unitId"].asText(), unit["route"].asText(), unit["state"].asText(), unit["reached"].asText()),
                mock.toString(),
            )

            val activation = bench.activate(draftId)
            assertEquals(MissionVersions.FLEET_PORT_ABSENT, activation["result"].asText(), activation.toString())
            assertTrue(activation["version"].isNull && activation["activated"].isNull, activation.toString())
            assertEquals(true, activation["lastMockRun"]["passed"].asBoolean(), activation.toString())
            // 버전 행이 서지 않았고 카탈로그는 코드 정의 그대로다.
            val overview = bench.get("/host/missions/${PrepareSequencedRack.WORK_MASTER}")
            assertTrue(overview["versions"].isEmpty, overview.toString())
            assertEquals(MissionVersions.SOURCE_CODE, overview["active"]["source"].asText(), overview.toString())
        }
    }

    /** 그룹 안 분기의 작업 지시 — 대상마다 `item`(점검 종류)을 단다. 같은 id 를 두 번 넣을 수 있다(분기 자리 겹침). */
    private fun byItem(jobOrderId: String, workMaster: String, version: Int, vararg targets: Pair<String, String>): ObjectNode = JSON.readTree(
        """{"jobOrderId":"$jobOrderId","workMasterId":"$workMaster","version":$version,"requiredEvidence":"E0","parameters":{},"materialRequirements":[],""" +
            """"equipmentRequirements":[""" + targets.joinToString(",") { (id, item) ->
                """{"id":"$id","equipmentUse":"mission_target","properties":{"location":"bay-$id","item":"$item"}}"""
            } + "]}",
    ) as ObjectNode

    /**
     * 접수 시점 분기(picasso §15.328)를 실행 호스트의 저장·검증·모의 실행·활성화·접수·리비전으로 민다. 정의는 화면 «대상마다 이동 뒤 종류별
     * 점검» 템플릿과 같은 글자의 시험 자원 `mission-profile-choice.json` 이다(picasso `MissionFixtures.INSPECT_BY_ITEM` 모양).
     */
    @Test
    fun `분기 정의는 모의 실행이 자리마다 돈 가지와 안 돈 가지를 싣고 활성화 뒤 접수한 작업 지시는 고른 가지만 기체에 내며 가지를 바꾸는 리비전과 분기 자리가 겹친 작업 지시는 picasso 사유 그대로 거부한다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val profile = JSON.readTree(javaClass.getResourceAsStream("/mission-profile-choice.json"))
            val wm = profile["workMasterId"].asText()
            val draftId = bench.saveDraft(wm, profile["definition"].toString(), byItem("SAMPLE", wm, 1, "T1" to "thermal", "T2" to "visual"))
            val validated = bench.step(draftId, "validate").body!!
            assertEquals("PASSED", validated["result"].asText(), validated.toString())
            val mock = bench.step(draftId, "mock-run").body!!
            assertEquals("PASSED", mock["result"].asText(), mock.toString())
            val result = mock["mockRun"]["result"]
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2.look"), result["units"].map { it["unitId"].asText() }, mock.toString())
            // 모의 실행 행에 자리마다 고른 가지와 돌지 않은 가지가 남는다 — 화면이 안 돈 가지를 검증 성공으로 보이지 않게 이 칸을 읽는다.
            assertEquals(
                listOf(Triple("how[T1]", "arms[0]", listOf("arms[1]", "otherwise")), Triple("how[T2]", "arms[1]", listOf("arms[0]", "otherwise"))),
                result["branches"].map { b -> Triple(b["site"].asText(), b["chosen"].asText(), b["notRun"].map { it.asText() }) },
                mock.toString(),
            )
            val activation = bench.activate(draftId)
            assertEquals(listOf("ACTIVATED", "1"), listOf(activation["result"].asText(), activation["version"].asText()), activation.toString())

            // 이 정의는 대상마다 이동 단위(`<대상>.travel`)를 내므로 같은 설비 id 가 둘이면 분기 자리 겹침보다 단위 id 겹침이 먼저 걸린다
            // (picasso `submit` 의 검사 순서). 분기 자리 겹침만 걸리는 모양은 아래 [choiceOnly] 시험이 본다.
            val clash = bench.post(
                "/host/job-orders",
                request(byItem("JO-2", wm, 1, "T1" to "thermal", "T1" to "visual").put("missionVersion", "1").toString(), "candidates", QUADRUPED),
            ).body!!
            assertEquals("REJECTED", clash["result"].asText(), clash.toString())
            assertTrue(clash["rejectionReason"].asText().startsWith(Middleware.DUPLICATE_UNIT_ID), clash.toString())

            val submitted = bench.post(
                "/host/job-orders",
                request(byItem("JO-1", wm, 1, "T1" to "thermal", "T2" to "visual").put("missionVersion", "1").toString(), "candidates", QUADRUPED),
            ).body!!
            assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
            val executionId = submitted["executionId"].asText()
            val planned = bench.execution(executionId)!!
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2.look"), planned["units"].map { it["unitId"].asText() }, planned.toString())

            // 남은 자리의 가지를 바꾸는 리비전(T2 를 visual → thermal)은 picasso 의 거부 사유가 호스트 응답으로 그대로 나간다.
            val revised = bench.post(
                "/host/job-orders",
                request(byItem("JO-1", wm, 2, "T1" to "thermal", "T2" to "thermal").put("missionVersion", "1").toString(), "candidates", QUADRUPED),
            )
            assertEquals(200, revised.status, revised.body.toString())
            assertEquals("REJECTED", revised.body!!["result"].asText(), revised.body.toString())
            val reason = revised.body["rejectionReason"].asText()
            assertTrue(reason.startsWith("리비전이 분기의 가지를 바꾼다: how[T2]") && reason.endsWith("새 작업 지시로 낸다"), reason)
            // 대상을 빼는 리비전(T2 를 뺌)은 분기 자리를 없애 받지 않는다.
            val dropped = bench.post(
                "/host/job-orders",
                request(byItem("JO-1", wm, 3, "T1" to "thermal").put("missionVersion", "1").toString(), "candidates", QUADRUPED),
            ).body!!
            assertEquals("REJECTED", dropped["result"].asText(), dropped.toString())
            assertTrue(dropped["rejectionReason"].asText().startsWith("리비전이 분기 자리를 없앤다: how[T2]"), dropped.toString())

            val done = bench.driveUntil(executionId, settled)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("T1.travel", "T1", "T2.travel", "T2.look"), done["units"].map { it["unitId"].asText() }, done.toString())
            assertTrue(done["units"].all { it["state"].asText() == "DONE" }, done.toString())
            // 기체에 나간 태스크는 고른 가지의 것뿐이다 — 대상마다 이동 하나와 점검 하나. 고르지 않은 가지의 점검은 나가지 않았다.
            assertEquals(
                listOf("navigate_to", "inspect", "navigate_to", "inspect"),
                bench.mimic.server.exclusive { bench.mimic.instance(QUADRUPED)!!.tasks.all.map { it.skillType } },
            )
        }
    }

    /**
     * 분기 자리 겹침(picasso §15.328) — 그룹 안에 분기만 둔 정의(이동 없음, `mission-profile-choice.json` 에서 이동 노드와 선행을 뺀 것)는
     * 같은 설비 id 둘이 고르지 않는 가지(`otherwise`)로 가면 단위 id 가 겹치지 않아 분기 자리 겹침만 걸린다. 작업 수락과 리비전 둘 다
     * picasso 사유 그대로 REJECTED 이고, 대상을 더하는 리비전은 받는다.
     */
    @Test
    fun `그룹 안 분기만 둔 정의는 같은 설비 id 가 둘인 작업 지시를 작업 수락과 리비전 모두 분기 자리 겹침으로 거부하고 대상을 더하는 리비전은 받는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val profile = JSON.readTree(javaClass.getResourceAsStream("/mission-profile-choice.json"))
            val wm = "AssetInspectByItemOnly"
            val each = profile["definition"]["steps"][0] as ObjectNode
            val steps = each["steps"] as com.fasterxml.jackson.databind.node.ArrayNode
            steps.remove(0)
            steps[0]["arms"].forEach { arm -> arm["steps"].forEach { (it as ObjectNode).remove("after") } }
            val definition = (profile["definition"] as ObjectNode).put("workMasterId", wm).toString()
            check("travel" !in definition) { "이동 노드를 빼지 못했다" }
            val draftId = bench.saveDraft(wm, definition, byItem("SAMPLE", wm, 1, "T1" to "thermal"))
            assertEquals("PASSED", bench.step(draftId, "validate").body!!["result"].asText())
            assertEquals("PASSED", bench.step(draftId, "mock-run").body!!["result"].asText())
            bench.activate(draftId)

            fun submit(version: Int, vararg targets: Pair<String, String>) = bench.post(
                "/host/job-orders",
                request(byItem("JO-1", wm, version, *targets).put("missionVersion", "1").toString(), "candidates", QUADRUPED),
            ).body!!

            val clash = submit(1, "T1" to "acoustic", "T1" to "acoustic", "T2" to "thermal")
            assertEquals("REJECTED", clash["result"].asText(), clash.toString())
            assertTrue(clash["rejectionReason"].asText().startsWith("분기 자리가 겹쳐 받지 않는다: how[T1]"), clash.toString())
            assertTrue(clash["executionId"].isNull, clash.toString())

            val accepted = submit(1, "T2" to "thermal")
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val executionId = accepted["executionId"].asText()
            val revisedClash = submit(2, "T1" to "acoustic", "T1" to "acoustic", "T2" to "thermal")
            assertEquals("REJECTED", revisedClash["result"].asText(), revisedClash.toString())
            assertTrue(revisedClash["rejectionReason"].asText().startsWith("분기 자리가 겹쳐 받지 않는다: how[T1]"), revisedClash.toString())
            assertEquals(listOf("T2"), bench.execution(executionId)!!["units"].map { it["unitId"].asText() })

            // 새 자리를 더하는 리비전(T3 를 더함)은 받는다 — 같은 실행에 T3 의 고른 가지 단위가 더해진다.
            val added = submit(3, "T2" to "thermal", "T3" to "visual")
            assertEquals(listOf("ACCEPTED", executionId), listOf(added["result"].asText(), added["executionId"].asText()), added.toString())
            assertEquals(listOf("T2", "T3.look"), bench.execution(executionId)!!["units"].map { it["unitId"].asText() })
        }
    }
}
