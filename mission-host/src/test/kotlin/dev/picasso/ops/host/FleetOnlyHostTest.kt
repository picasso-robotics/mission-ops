package dev.picasso.ops.host

import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.ops.host.HostBench.Companion.GHOST
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.STANDARD_CELL
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.mission.MissionVersions
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.registry.PostgresSupport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 플릿 포트가 있는 실행 호스트(2단계). 배선(`host.fleet.url` 빈 값 = 없음·값 있음), 차단이 포트가 있을 때 풀리는 것, 플릿 전용 계획의 기체 규칙
 * D2(a)(요청 후보 정확히 하나·미등록 거절·점유와 기존 제한 유지·스킬 «해당 없음»·선언 못 읽음은 사유 아님·묶인 기체 표기), 끝나지 않은 플릿 단위의
 * 운영자 종결·빈손 확인 막음을 본다. 플릿은 프로세스 안의 플릿 미믹(수동 걸음)이다 — HTTP 면은 `HttpFleetPortTest`·현장 시험·통합 시험이 본다.
 */
class FleetOnlyHostTest {

    private fun deliver(jobOrderId: String, container: String = CONTAINER) =
        """{"jobOrderId":"$jobOrderId","workMasterId":"${DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
            """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
            """{"id":"$OUT","equipmentUse":"source","properties":{"container":"$container"}},""" +
            """{"id":"$IN","equipmentUse":"destination","properties":{}}]}"""

    /** 셀 대역 대역 본문에 인계 자리를 얹는다. [arrived] 면 도착 인계점에 그 용기가 [at] 에 놓였다. */
    private fun cellWith(arrived: String?, at: String?): String {
        val handovers = """"handovers":[{"id":"$OUT","occupied":false,"material":null,"observedAt":null},""" +
            """{"id":"$IN","occupied":${arrived != null},"material":${arrived?.let { "\"$it\"" } ?: "null"},"observedAt":${at?.let { "\"$it\"" } ?: "null"}}]"""
        return STANDARD_CELL.trimEnd().removeSuffix("}") + ",$handovers}"
    }

    private fun HostBench.submit(order: String, vararg robots: String) = post("/host/job-orders", request(order, "candidates", *robots))

    @Test
    fun `host fleet url 이 비면 플릿 연결이 없고 값이 있으면 그 주소로 연결되어 플릿 차단이 풀린다`() {
        HostBench().use { bench ->
            val summary = bench.get("/host/executions/summary")
            assertEquals(false to true, summary["fleet"]["connected"].asBoolean() to summary["fleet"]["url"].isNull, summary.toString())
            val refused = bench.post("/host/eligibility", request(deliver("JO-0"), "robotIds", HUMANOID))
            assertEquals(400 to HostRequests.FLEET_PORT_ABSENT, refused.status to refused.body!!["error"].asText())
        }
        // 주소만 있으면 연결이다(현장이 지금 답하는지와 무관). 판정은 차단 없이 묶인 기체 판정을 낸다.
        HostBench(fleetUrl = "http://127.0.0.1:1/").use { bench ->
            val summary = bench.get("/host/executions/summary")
            assertEquals(true to "http://127.0.0.1:1", summary["fleet"]["connected"].asBoolean() to summary["fleet"]["url"].asText(), summary.toString())
            val judged = bench.post("/host/eligibility", request(deliver("JO-0"), "robotIds", HUMANOID))
            assertEquals(200, judged.status, judged.body.toString())
            assertEquals("NOT_APPLICABLE", judged.body!!["robots"].single()["skillFit"].asText())
        }
        // 주소 모양이 틀리면 기동이 멈춘다(조용히 플릿 없음으로 뜨지 않는다).
        assertFailsWith<Exception> { HostBench(fleetUrl = "127.0.0.1:8083").close() }
    }

    @Test
    fun `플릿 전용 계획의 판정은 스킬 해당 없음이고 묶인 기체이며 미등록 기체는 등록 확인 안 됨으로 뺀다`() {
        HostBench(fleet = AmrFleetMimic()).use { bench ->
            val judged = bench.post("/host/eligibility", request(deliver("JO-1"), "robotIds", HUMANOID, QUADRUPED, GHOST))
            assertEquals(200, judged.status, judged.body.toString())
            val rows = judged.body!!["robots"].associateBy { it["robotId"].asText() }
            listOf(HUMANOID, QUADRUPED).forEach { id ->
                val row = rows.getValue(id)
                assertEquals(listOf("NOT_APPLICABLE", "true", "true", "0"), listOf(row["skillFit"].asText(), row["passed"].asText(), row["boundRobot"].asText(), row["reasons"].size().toString()), row.toString())
                assertEquals(0, row["requiredSkills"].size(), row.toString())
            }
            val ghost = rows.getValue(GHOST)
            assertEquals(listOf(MissionHost.UNREGISTERED_REASON), ghost["reasons"].map { it.asText() }, ghost.toString())
            assertEquals(false, ghost["passed"].asBoolean())
            // 로봇 단위가 있는 계획은 그대로 스킬로 판정한다(묶인 기체가 아니다).
            val robotPlan = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S01"), "robotIds", QUADRUPED)).body!!["robots"].single()
            assertEquals("MISSING" to false, robotPlan["skillFit"].asText() to robotPlan["boundRobot"].asBoolean())
        }
    }

    @Test
    fun `선언을 못 읽은 기체도 플릿 전용 계획에서는 거절 사유가 아니지만 로봇 계획에서는 모름이다`() {
        HostBench(gated = true, fleet = AmrFleetMimic()).use { bench ->
            bench.blindCapabilities += HUMANOID
            val fleetRow = bench.post("/host/eligibility", request(deliver("JO-1"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(listOf("NOT_APPLICABLE", "true"), listOf(fleetRow["skillFit"].asText(), fleetRow["passed"].asText()), fleetRow.toString())
            assertTrue(fleetRow["availableSkills"].isNull, fleetRow.toString())
            val robotRow = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S01"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals("UNKNOWN" to false, robotRow["skillFit"].asText() to robotRow["passed"].asBoolean())
        }
    }

    @Test
    fun `플릿 전용 계획은 요청 후보가 정확히 하나일 때만 받고 여럿이거나 비면 FLEET_ONLY_CANDIDATES 로 거절하며 아무 기체도 고르지 않는다`() {
        val fleet = AmrFleetMimic().apply { place(OUT, CONTAINER) }
        HostBench(fleet = fleet).use { bench ->
            bench.awaitPump()
            listOf(arrayOf(HUMANOID, QUADRUPED), arrayOf(HUMANOID, QUADRUPED, HUMANOID), emptyArray()).forEach { robots ->
                val refused = bench.submit(deliver("JO-1"), *robots)
                assertEquals(400 to HostRequests.FLEET_ONLY_CANDIDATES, refused.status to refused.body!!["error"].asText(), refused.body.toString())
                assertTrue(refused.body["detail"].asText().startsWith(HostRequests.FLEET_ONLY_CANDIDATES_DETAIL), refused.body.toString())
            }
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            // 같은 기체를 두 번 적은 것은 하나다 — 그리고 미등록 기체 하나는 등록 확인 안 됨으로 빠져 미배정이다.
            val ghost = bench.submit(deliver("JO-1"), GHOST).body!!
            assertEquals("UNASSIGNED", ghost["result"].asText(), ghost.toString())
            assertEquals(listOf(MissionHost.UNREGISTERED_REASON), ghost["excluded"].single()["reasons"].map { it.asText() })
            bench.idlePumps()
            assertEquals(0, fleet.dispatches, "거절·미배정은 플릿에 아무것도 맡기지 않는다")
        }
    }

    @Test
    fun `후보 하나의 플릿 전용 계획은 받아 플릿에 맡기고 묶인 기체로 표기하며 인계가 셀 신호로 오르면 E2 로 끝난다`() {
        val fleet = AmrFleetMimic().apply { place(OUT, CONTAINER) }
        HostBench(fleet = fleet).use { bench ->
            bench.awaitPump()
            val accepted = bench.submit(deliver("JO-1"), HUMANOID).body!!
            assertEquals(listOf("ACCEPTED", HUMANOID, "true"), listOf(accepted["result"].asText(), accepted["robotId"].asText(), accepted["boundRobot"].asText()), accepted.toString())
            val executionId = accepted["executionId"].asText()
            bench.eventually("플릿에 맡김") { fleet.dispatches == 1 }
            assertEquals(true, bench.execution(executionId)!!["boundRobot"].asBoolean())

            // 묶인 기체는 점유된다 — 다른 계획이 그 기체를 받지 못한다.
            val busy = bench.post("/host/eligibility", request(deliver("JO-2", "HU-2"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(false, busy["passed"].asBoolean())
            assertTrue(busy["reasons"].any { it.asText() == "진행 중인 실행이 있다: $executionId" }, busy.toString())

            repeat(3) { fleet.tick() }
            bench.awaitPump()
            bench.idlePumps()
            assertEquals("VERIFYING", bench.execution(executionId)!!["units"].single()["state"].asText(), "인계가 셀 신호에 오르기 전에는 확인 중이다")
            bench.cellBody = cellWith(CONTAINER, bench.now().toString())
            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "UNVERIFIED", "FAILED"), rounds = 2)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(listOf("FLEET", "DONE", "E2"), done["units"].single().let { listOf(it["route"].asText(), it["state"].asText(), it["reached"].asText()) })
            // 기체에는 아무 태스크도 나가지 않았다.
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })
            assertEquals(1, fleet.dispatches)
        }
    }

    @Test
    fun `플릿 포트가 있으면 플릿 정의의 활성화가 FLEET_PORT_ABSENT 로 막히지 않는다`() {
        HostBench(fleet = AmrFleetMimic()).use { bench ->
            bench.awaitPump()
            val saved = bench.post(
                "/host/missions/${PrepareSequencedRack.WORK_MASTER}/drafts",
                JSON.writeValueAsString(mapOf("definition" to FleetBlockHostTest.deliver(PrepareSequencedRack.WORK_MASTER), "actor" to "lee", "requestId" to UUID.randomUUID().toString())),
            )
            val draftId = saved.body!!["draft"]["draftId"].asLong()
            fun step(action: String, extra: Map<String, String> = emptyMap()) = bench.post(
                "/host/missions/drafts/$draftId/$action",
                JSON.writeValueAsString(mapOf("robotIds" to listOf(HUMANOID, QUADRUPED), "requestId" to UUID.randomUUID().toString()) + extra),
            ).body!!
            assertEquals("PASSED", step("validate")["result"].asText())
            val early = step("activate", mapOf("actor" to "lee", "reason" to "플릿 운반 도입"))
            assertTrue(early["result"].asText() != MissionVersions.FLEET_PORT_ABSENT, early.toString())
            assertEquals("PASSED", step("mock-run")["result"].asText())
            val activated = step("activate", mapOf("actor" to "lee", "reason" to "플릿 운반 도입"))
            assertEquals("ACTIVATED", activated["result"].asText(), activated.toString())
        }
    }

    @Test
    fun `끝나지 않은 플릿 단위가 있는 작업 지시의 잔여 파지 표시는 빈손 확인을 FLEET_UNIT_OPEN 으로 막는다`() {
        val fleet = AmrFleetMimic().apply { place(OUT, CONTAINER) }
        HostBench(fleet = fleet).use { bench ->
            bench.awaitPump()
            val accepted = bench.submit(deliver("JO-F"), QUADRUPED).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.eventually("플릿에 맡김") { fleet.dispatches == 1 }
            // 같은 작업 지시에 걸린 표시(다른 기체). 실제로는 개정판·재작업이 정착한 실행을 다시 돌릴 때 생기는 자리다.
            Fenced.execute(
                "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) VALUES ('$HUMANOID', 'JO-F', 'exec-0', 'mw-old', 'pick')",
            )
            val markerId = PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold WHERE job_order_id = 'JO-F'") { it.getLong(1) }.single()
            val refused = bench.clearHold(HUMANOID, markerId)
            assertEquals(409 to MissionHost.FLEET_UNIT_OPEN, refused.status to refused.body!!["error"].asText(), refused.body.toString())
            assertTrue(CONTAINER in refused.body["detail"].asText(), refused.body.toString())
            assertEquals(0, PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold_clearing") { it.getLong(1) }.size)
        }
    }

    /** 작업 지시를 못 읽는 일지 행을 넣는다(기동 복원이 GAVE_UP 으로 포기한다). */
    private fun insertUnreadable(jobOrderId: String, workMaster: String, version: Int? = null) = Fenced.execute(
        """
        INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
        VALUES ('$jobOrderId', '$HUMANOID', '{"jobOrderId":"$jobOrderId"}'::jsonb, '$workMaster', ${version ?: "NULL"}, 'mw-before', 'exec-9')
        """.trimIndent(),
    )

    private fun closeBody() = JSON.writeValueAsString(
        mapOf(
            "approverId" to "park", "reason" to "현장에서 멈춤 확인", "requestId" to UUID.randomUUID().toString(),
            "executionInstanceId" to "mw-before", "executionId" to "exec-9", "attestedStopped" to true,
        ),
    )

    @Test
    fun `작업 지시를 못 읽는 포기 행은 대상 임무가 플릿이거나 대상 버전을 모르면 운영자 종결과 빈손 확인을 FLEET_UNIT_OPEN 으로 막는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            // 대상이 코드 DeliverContainer 인 행, 대상 버전(1)이 플릿 정의인 행, 대상 버전(99)이 유실된 행.
            insertUnreadable("WT-9", DeliverContainer.WORK_MASTER)
            val store = bench.bean(dev.picasso.ops.host.store.MissionStore::class.java)
            val draft = store.saveDraft(PrepareSequencedRack.WORK_MASTER, FleetBlockHostTest.deliver(PrepareSequencedRack.WORK_MASTER), "lee", UUID.randomUUID())
            store.insertVersion(PrepareSequencedRack.WORK_MASTER, draft.draftId, draft.definition, "old-host", "옛 호스트가 활성화", UUID.randomUUID())
            insertUnreadable("WT-10", PrepareSequencedRack.WORK_MASTER, version = 1)
            insertUnreadable("WT-12", PrepareSequencedRack.WORK_MASTER, version = 99)
            bench.restartHost()
            val rows = bench.get("/host/executions")["restore"]["rows"]
            assertEquals(listOf("GAVE_UP", "GAVE_UP", "GAVE_UP"), rows.map { it["result"].asText() }, rows.toString())
            listOf("WT-9", "WT-10", "WT-12").forEach { id ->
                val closed = bench.post("/host/job-orders/$id/close", closeBody()).body!!
                assertEquals("NOT_CLOSABLE", closed["result"].asText(), closed.toString())
                assertTrue(closed["detail"].asText().startsWith(MissionHost.FLEET_UNIT_OPEN), closed.toString())
                assertEquals(listOf("GAVE_UP"), PostgresSupport.queryAll("SELECT kind FROM mission.execution_journal_event WHERE job_order_id = '$id' ORDER BY event_id") { it.getString(1) })
            }
            // 빈손 확인도 같은 일지 판정이다(대칭).
            Fenced.execute(
                "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) VALUES ('$QUADRUPED', 'WT-9', 'exec-9', 'mw-before', 'pick')",
            )
            val markerId = PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold WHERE job_order_id = 'WT-9'") { it.getLong(1) }.single()
            val refused = bench.clearHold(QUADRUPED, markerId)
            assertEquals(409 to MissionHost.FLEET_UNIT_OPEN, refused.status to refused.body!!["error"].asText(), refused.body.toString())
            assertEquals(0, PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold_clearing") { it.getLong(1) }.size)
        }
    }

    @Test
    fun `대상 임무가 로봇 전용이면 작업 지시를 못 읽어도 다른 버전의 플릿 노드 때문에 막지 않고 빈손 확인도 받는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            // 같은 WorkMaster 의 저장된 버전 1 은 플릿 정의지만, 이 행의 대상은 코드 정의(로봇 전용)다.
            val store = bench.bean(dev.picasso.ops.host.store.MissionStore::class.java)
            val draft = store.saveDraft(PrepareSequencedRack.WORK_MASTER, FleetBlockHostTest.deliver(PrepareSequencedRack.WORK_MASTER), "lee", UUID.randomUUID())
            store.insertVersion(PrepareSequencedRack.WORK_MASTER, draft.draftId, draft.definition, "old-host", "옛 호스트가 활성화", UUID.randomUUID())
            insertUnreadable("WT-11", PrepareSequencedRack.WORK_MASTER)
            bench.restartHost()
            assertEquals("GAVE_UP", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            Fenced.execute(
                "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) VALUES ('$QUADRUPED', 'WT-11', 'exec-9', 'mw-before', 'pick')",
            )
            val markerId = PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold WHERE job_order_id = 'WT-11'") { it.getLong(1) }.single()
            val cleared = bench.clearHold(QUADRUPED, markerId)
            assertEquals(200, cleared.status, cleared.body.toString())
            val closed = bench.post("/host/job-orders/WT-11/close", closeBody()).body!!
            assertTrue(!closed["detail"].isTextual || !closed["detail"].asText().startsWith(MissionHost.FLEET_UNIT_OPEN), closed.toString())
        }
    }

    private companion object {
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"
    }
}
