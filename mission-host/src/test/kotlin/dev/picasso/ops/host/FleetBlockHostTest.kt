package dev.picasso.ops.host

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.mission.DefinedCapability
import dev.picasso.middleware.mission.MissionDefinitionParser
import dev.picasso.middleware.mission.MissionParse
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.mission.StoredMissionCatalog
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.ops.host.web.HostRequests
import dev.picasso.registry.PostgresSupport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 플릿 포트가 없는 실행 호스트의 플릿 차단이 실행을 세우는 모든 입구에 걸리는가. 판정·새 접수는 `MissionHostTest`, 활성화는
 * `MissionDefinitionGroupsHostTest`, 값 없는 승인은 `IncidentLoopHostTest` 가 보고, 여기서는 재기동이 되살린 플릿 활성 버전(기본·숫자 고정),
 * 기동 복원, 개정을 본다. 플릿 정의는 활성화로 설 수 없으므로 DB 에 버전 행을 직접 넣거나(옛 호스트가 남긴 것) 카탈로그에 직접 싣는다.
 */
class FleetBlockHostTest {

    private fun HostBench.refused(path: String, order: String, field: String, robot: String = HUMANOID) =
        post(path, request(order, field, robot)).also {
            assertEquals(400, it.status, "$path ${it.body}")
            assertEquals(HostRequests.FLEET_PORT_ABSENT, it.body!!["error"].asText(), it.body.toString())
            assertTrue(it.body["detail"].asText().startsWith(HostRequests.FLEET_PORT_ABSENT_DETAIL), it.body.toString())
        }

    @Test
    fun `재기동이 DB 의 플릿 활성 버전을 되살려도 판정과 접수는 기본과 숫자 고정 모두 400 FLEET_PORT_ABSENT 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val store = bench.bean(MissionStore::class.java)
            val draft = store.saveDraft(PrepareSequencedRack.WORK_MASTER, deliver(PrepareSequencedRack.WORK_MASTER), "lee", UUID.randomUUID())
            store.insertVersion(PrepareSequencedRack.WORK_MASTER, draft.draftId, draft.definition, "old-host", "옛 호스트가 활성화", UUID.randomUUID())
            bench.restartHost()
            bench.awaitPump()

            val order = rack("JO-1", "RACK-204.S01")
            val pinned = (JSON.readTree(order) as ObjectNode).put("missionVersion", "1").toString()
            listOf("/host/eligibility" to "robotIds", "/host/job-orders" to "candidates").forEach { (path, field) ->
                listOf(order, pinned).forEach { bench.refused(path, it, field) }
            }
            assertEquals(0, bench.get("/host/executions")["executions"].size())
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })
        }
    }

    @Test
    fun `기동 복원은 다시 지을 계획에 플릿 단위가 있으면 다시 짓지 않고 FLEET_PORT_ABSENT 사유로 미루고 종결도 받지 않는다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            insertDeliver("WT-781")
            bench.restartHost()
            val view = bench.get("/host/executions")
            val row = view["restore"]["rows"].single()
            assertEquals(listOf("WT-781", "DEFERRED"), listOf(row["jobOrderId"].asText(), row["result"].asText()), row.toString())
            assertTrue(row["reason"].asText().startsWith("${HostRequests.FLEET_PORT_ABSENT}: ${HostRequests.FLEET_PORT_ABSENT_DETAIL}"), row.toString())
            assertTrue("HU-1042" in row["reason"].asText(), row.toString())
            assertTrue(row["executionId"].isNull && view["executions"].isEmpty, view.toString())
            // 미룸 이벤트가 일지에 남고(포기 아님 — 다시 시도된다) 기체에는 아무것도 나가지 않았다.
            assertEquals(
                listOf("DEFERRED"),
                PostgresSupport.queryAll("SELECT kind FROM mission.execution_journal_event WHERE job_order_id = 'WT-781' ORDER BY event_id") { it.getString(1) },
            )
            assertEquals(0, bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size })
            // 미룬 동안 그 기체는 판정에서 빠지고, 그 행은 운영자 종결로 닫지 않는다(플릿 포트를 붙인 기동이 다시 짓는다).
            val judged = bench.post("/host/eligibility", request(rack("JO-2", "RACK-204.S01"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertTrue(judged["reasons"].any { it.asText().contains("WT-781") }, judged.toString())
            val unrestored = bench.get("/host/executions?recent=5")["unrestored"].single()
            assertEquals(false, unrestored["closable"].asBoolean(), unrestored.toString())
            val closed = bench.post("/host/job-orders/WT-781/close", close("mw-before", "exec-9")).body!!
            assertEquals("NOT_CLOSABLE", closed["result"].asText(), closed.toString())
        }
    }

    @Test
    fun `플릿 포트 없이 재기동해 미룬 플릿 행은 플릿 포트를 붙여 재기동하면 다시 지어지고 그 운반을 맡긴다`() {
        val fleet = AmrFleetMimic().apply { place("OUT-07", "HU-1042") }
        HostBench().use { bench ->
            bench.awaitPump()
            insertDeliver("WT-782")
            bench.restartHost()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText())
            bench.idlePumps()
            assertEquals("DEFERRED", bench.get("/host/executions")["restore"]["rows"].single()["result"].asText(), "포트가 없으면 다시 시도해도 미룬 채다")
            bench.fleet = fleet
            bench.restartHost()
            val row = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("RESTORED", row["result"].asText(), row.toString())
            bench.eventually("플릿에 맡김") { fleet.dispatches == 1 }
            assertEquals(
                listOf("DEFERRED", "RESTORED"),
                PostgresSupport.queryAll("SELECT kind FROM mission.execution_journal_event WHERE job_order_id = 'WT-782' ORDER BY event_id") { it.getString(1) },
            )
        }
    }

    @Test
    fun `실행이 있는 작업 지시라도 개정판의 계획에 플릿 단위가 생기면 400 FLEET_PORT_ABSENT 이고 같은 판 재전송은 IDEMPOTENT 다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            // 대상마다 이동 뒤 점검 + 인계 대상(handover)마다 플릿 운반. 인계 대상이 없는 판은 로봇 단위뿐이다.
            val profile = JSON.readTree(javaClass.getResourceAsStream("/mission-profile-visit.json"))
            val wm = profile["workMasterId"].asText()
            val definition = profile["definition"].toString().removeSuffix("]}") +
                """,{"kind":"unit","id":"handover","forEach":"handover","route":"FLEET","skill":"transport","unitId":{"from":"ITEM_ID"},""" +
                """"parameters":{},"expectedIdentity":{"from":"ITEM_PROPERTY","property":"container"},""" +
                """"source":{"from":"ITEM_PROPERTY","property":"from"},"destination":{"from":"ITEM_ID"}}]}"""
            val parsed = when (val p = MissionDefinitionParser.parse(definition)) {
                is MissionParse.Parsed -> p.definition
                is MissionParse.Unreadable -> error("정의를 못 읽었다: ${p.problems}")
            }
            bench.bean(StoredMissionCatalog::class.java).install(wm, DefinedCapability(parsed), 7)

            fun order(version: Int, handover: Boolean) =
                """{"jobOrderId":"JO-1","workMasterId":"$wm","version":$version,"requiredEvidence":"E0","parameters":{},"materialRequirements":[],""" +
                    """"equipmentRequirements":[{"id":"T1","equipmentUse":"mission_target","properties":{"location":"bay-7"}}""" +
                    (if (handover) """,{"id":"H1","equipmentUse":"handover","properties":{"container":"HU-1","from":"OUT-07"}}""" else "") + "]}"

            val accepted = bench.post("/host/job-orders", request(order(1, handover = false), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            val again = bench.post("/host/job-orders", request(order(1, handover = false), "candidates", QUADRUPED)).body!!
            assertEquals("IDEMPOTENT", again["result"].asText(), again.toString())

            val revised = bench.refused("/host/job-orders", order(2, handover = true), "candidates", QUADRUPED)
            assertTrue("H1" in revised.body!!["detail"].asText(), revised.body.toString())
            // 실행은 여전히 첫 판이고 플릿 단위가 없다.
            val execution = bench.execution(accepted["executionId"].asText())!!
            assertTrue(execution["units"].none { it["route"].asText() == "FLEET" }, execution.toString())
        }
    }

    private fun insertDeliver(jobOrderId: String) {
        val order = """{"jobOrderId":"$jobOrderId","workMasterId":"${DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
            """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
            """{"id":"OUT-07","equipmentUse":"source","properties":{"container":"HU-1042"}},""" +
            """{"id":"SEQ-IN-02","equipmentUse":"destination","properties":{}}]}"""
        Fenced.execute(
            """
            INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, mission_version, instance_id, execution_id)
            VALUES ('$jobOrderId', '$HUMANOID', '$order'::jsonb, '${DeliverContainer.WORK_MASTER}', NULL, 'mw-before', 'exec-9')
            """.trimIndent(),
        )
    }

    private fun close(instanceId: String, executionId: String) = JSON.writeValueAsString(
        mapOf(
            "approverId" to "park", "reason" to "현장에서 멈춤 확인", "requestId" to UUID.randomUUID().toString(),
            "executionInstanceId" to instanceId, "executionId" to executionId, "attestedStopped" to true,
        ),
    )

    companion object {
        /** picasso `MissionFixtures.DELIVER_CONTAINER` 모양 — 첫 출발 설비의 container 속성이 용기이고 첫 목적지로 운반한다. */
        fun deliver(workMaster: String): String {
            val first = """{"from":"FIRST_ID","equipmentUse":"source"}"""
            val destination = """{"from":"FIRST_ID","equipmentUse":"destination"}"""
            val container = """{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"container"}"""
            return """{"schemaVersion":1,"workMasterId":"$workMaster","maxEvidence":"E2","preferredOptionals":{},"steps":[""" +
                """{"kind":"unit","id":"deliver","once":true,"route":"FLEET","skill":"transport","requires":[$first,$destination,$container],""" +
                """"whenMissing":{"failureClass":"INCOMPLETE_ORDER","parameters":[]},""" +
                """"unitId":{"from":"FIRST_PROPERTY","equipmentUse":"source","property":"container","else":{"from":"ORDER_ID"}},""" +
                """"parameters":{"container":$container,"source":$first,"destination":$destination},""" +
                """"expectedIdentity":$container,"source":$first,"destination":$destination}]}"""
        }
    }
}
