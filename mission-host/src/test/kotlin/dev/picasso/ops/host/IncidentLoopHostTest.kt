package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.host.FileEntitlements
import dev.picasso.middleware.ApprovalOutcome
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.Middleware
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.ROOT
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.incident.ApprovalWindow
import dev.picasso.registry.PostgresSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 인시던트 고리(picasso H1 `host-kit`, 한계 §15.176). 설정을 비우면 붙지 않는 것, 승인 호스트가 선언 파일과 이 인스턴스로 답하는 것,
 * 승인으로 선 실행이 실행 일지에 적혀 pump 를 막지 않는 것, 내보내기가 놓은 실행의 작업 응답을 남기는 것을 본다.
 */
class IncidentLoopHostTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private val empty = HostFootprint(0, 0, 0, 0, 0, 0, 0)

    @Test
    fun `견본 선언은 recovery 와 운영자를 이 현장 기체로 선언하고 조치는 비워 둔다`() {
        // 견본은 복사해 고쳐 쓰는 출발점이다(`mission-host/incident-loop/README.md`). 읽히지 않으면 기동이 멈추므로 여기서 읽어 본다.
        val declared = FileEntitlements.read(EXAMPLE)
        assertEquals(setOf(AGENT, "operator-1"), declared.approvers())
        listOf(AGENT, "operator-1").forEach { id ->
            val one = checkNotNull(declared.declaredFor(id))
            assertEquals(setOf(HUMANOID, QUADRUPED), one.robotIds, id)
            // 이 호스트의 기본 임무로는 조치 제안이 서지 않는다 — 덮을 조치를 지어내지 않는다.
            assertTrue(one.actions.isEmpty(), "$id 의 조치가 비어 있지 않다: ${one.actions}")
            assertNull(one.revocation, id)
        }
        HostBench(incidentLoop = mapOf("entitlements" to EXAMPLE.toString(), "approval-port" to "0")).use { bench ->
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            assertEquals("REFUSED", approve(port, AGENT, "JO-1").second["outcome"].asText())
        }
    }

    @Test
    fun `설정을 비우면 승인 호스트를 열지 않는다`() {
        HostBench().use { bench ->
            assertNull(bench.bean(ApprovalWindow::class.java).port)
        }
    }

    @Test
    fun `승인 호스트는 미들웨어로 판정하고 답에 이 인스턴스 id 를 싣는다`() {
        HostBench(incidentLoop = loop()).use { bench ->
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)

            // 제안을 선언보다 먼저 본다(picasso RemedyDesk.judge). 제안이 없으면 승인자와 무관하게 NO_PROPOSAL 이다.
            val noProposal = approve(port, AGENT, "JO-1")
            assertEquals(listOf(200, "REFUSED", "NO_PROPOSAL"), listOf(noProposal.first, noProposal.second["outcome"].asText(), noProposal.second["refusal"].asText()), noProposal.toString())
            assertEquals(bench.host.instanceId, noProposal.second["instanceId"].asText())

            val get = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/approvals")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode(), get.body())
        }
    }

    @Test
    fun `승인으로 선 실행은 실행 일지에 적혀 pump 와 제출을 막지 않고 정착까지 간다`() {
        HostBench(incidentLoop = loop()).use { bench ->
            bench.awaitPump()
            // 이 호스트에서는 판정이 바쁜 기체를 미리 빼므로 제안이 서는 장면을 만들기 어렵다. 미들웨어가 승인으로 실행을 세운 것처럼 주입한다.
            bench.host.approve = { middleware, attempt ->
                val accepted = middleware.submit(patrol(attempt.jobOrderId), attempt.robotId) as Middleware.Submission.Accepted
                ApprovalOutcome.Approved(accepted.execution.executionId, emptyList())
            }
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val approved = approve(port, AGENT, "JO-A")
            assertEquals("APPROVED", approved.second["outcome"].asText(), approved.toString())
            val executionId = approved.second["executionId"].asText()

            assertEquals(
                listOf("JO-A" to executionId),
                PostgresSupport.queryAll("SELECT job_order_id, execution_id FROM mission.execution_journal WHERE job_order_id = 'JO-A'") {
                    it.getString(1) to it.getString(2)
                },
            )
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settledStates)["physicalState"].asText())
            bench.eventually("정착 기록") {
                PostgresSupport.queryAll("SELECT count(*) FROM mission.execution_journal_event WHERE kind = 'SETTLED' AND job_order_id = 'JO-A'") { it.getInt(1) }.single() == 1
            }
            // 일지에 없는 실행이 남지 않았으므로 다음 제출이 막히지 않는다.
            bench.eventually("놓기") { bench.host.footprint() == empty }
            val next = bench.post("/host/job-orders", request(inspect("JO-B", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", next["result"].asText(), next.toString())
        }
    }

    @Test
    fun `정착을 적고 놓은 작업 지시의 승인 시도는 미들웨어에 넘기지 않고 REMEDY_NOT_APPLIED 로 거부한다`() {
        HostBench(incidentLoop = loop()).use { bench ->
            settle(bench, "JO-1")
            bench.host.approve = { _, attempt -> fail("미들웨어에 넘기지 않아야 한다: $attempt") }
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val refused = approve(port, AGENT, "JO-1")
            assertEquals(listOf("REFUSED", "REMEDY_NOT_APPLIED"), listOf(refused.second["outcome"].asText(), refused.second["refusal"].asText()), refused.toString())
            assertTrue(refused.second["reason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER), refused.toString())
        }
    }

    @Test
    fun `내보내기는 놓은 실행의 작업 응답도 남기고 대장과 안내를 쓴다`() {
        val export = createTempDirectory("incident-loop")
        HostBench(incidentLoop = mapOf("export" to export.toString())).use { bench ->
            val first = settle(bench, "JO-1")
            val second = settle(bench, "JO-2")
            bench.eventually("작업 응답 파일") {
                val ids = responseIds(export.resolve("job-responses.jsonl"))
                first in ids && second in ids
            }
            assertTrue(Files.exists(export.resolve("manifest.json")))
            assertTrue(Files.exists(export.resolve("incidents.jsonl")))
            assertTrue(Files.exists(export.resolve("remedy-searches.jsonl")))
        }
    }

    /**
     * 든 채 실패하고 잔여 파지 표시를 사람이 «빈손 확인» 으로 잘못 푼 기체(2026-11-13 조사 장면). 손은 여전히 든 채다. B2 부터 첫 확인은 발신자의 HOLDING
     * 으로 409 이고, 사람이 그 관측을 지목해 «현장에서 빈손을 확인했다» 고 다시 확인해야 풀린다(어긋남 인지). 그 기체에 빈손을 요구하는 순회를 내면 picasso
     * 관문이 기체 스냅숏의 손으로 거절하고 조치 제안을 세운다(picasso §15.316, 계약 0.11.0).
     */
    private fun misclearedThenPatrol(bench: HostBench): JsonNode = heldThenPatrol(bench, clear = true)

    /** 든 채 실패해 잔여 파지 표시가 선 기체에 순회를 낸다. [clear] 면 그 전에 사람이 어긋남을 지목하고 «빈손 확인» 으로 (잘못) 푼다. */
    private fun heldThenPatrol(bench: HostBench, clear: Boolean): JsonNode {
        bench.awaitPump()
        val rack = bench.post("/host/job-orders", request(HostBench.rack("JO-R", "RACK-204.S01"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", rack["result"].asText(), rack.toString())
        var running: String? = null
        repeat(20) {
            if (running == null) {
                bench.mimic.server.advance(java.time.Duration.ofSeconds(1)); bench.awaitPump()
                running = bench.mimic.server.exclusive {
                    bench.mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == dev.picasso.mimic.engine.TaskState.RUNNING }?.taskId
                }
            }
        }
        bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", checkNotNull(running)) }
        assertEquals("FAILED", bench.driveUntil(rack["executionId"].asText(), setOf("FAILED", "UNVERIFIED", "ABORTED", "PARTIAL", "PHYSICALLY_DONE"))["physicalState"].asText())
        bench.eventually("잔여 파지 표시") { bench.get("/host/robots/residual-holds")["holds"].size() == 1 }
        val markerId = bench.get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
        if (clear) {
            val cleared = bench.clearHeldCiting(HUMANOID, markerId)
            assertEquals(200, cleared.status, cleared.body.toString())
            assertEquals(true, cleared.body!!["clearing"]["acknowledgedMismatch"].asBoolean(), cleared.body.toString())
        }
        bench.awaitPump()
        return bench.post("/host/job-orders", request(inspect("JO-P", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
    }

    /** 내려놓기 조치를 덮는 선언 — 목적지는 선언이, 대상은 지금 손이 채운다. */
    private val putDown = """[{"skillType":"pick_place","parameters":{"destination":"RACK-204.S04"}}]"""

    @Test
    fun `잘못 푼 표시 뒤의 순회는 관문이 스냅숏 손으로 막아 제안이 서고, 값 없이 승인하면 실행이 일지에 적힌다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            val patrol = misclearedThenPatrol(bench)
            assertTrue(patrol["result"].asText() != "ACCEPTED", "든 채인 기체에 빈손을 요구하는 순회를 받았다: $patrol")
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val (_, approved) = approve(port, AGENT, "JO-P", saw = listOf("pick_place"))
            assertEquals("APPROVED", approved["outcome"].asText(), approved.toString())
            val executionId = approved["executionId"].asText()
            assertEquals(
                listOf("JO-P" to executionId),
                PostgresSupport.queryAll("SELECT job_order_id, execution_id FROM mission.execution_journal WHERE job_order_id = 'JO-P'") {
                    it.getString(1) to it.getString(2)
                },
            )
        }
    }

    @Test
    fun `승인도 실행 호스트 판정이 막은 기체에는 실행을 세우지 않는다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            misclearedThenPatrol(bench)
            // 같은 기체에 다른 일이 선다 — 판정은 이 기체를 «진행 중인 실행» 으로 막는다. 미들웨어 관문은 그 제한을 모른다.
            val other = bench.post("/host/job-orders", request(HostBench.rack("JO-X", "RACK-204.S02"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", other["result"].asText(), other.toString())
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val (_, refused) = approve(port, AGENT, "JO-P", saw = listOf("pick_place"))
            assertEquals(listOf("REFUSED", "REFUSED_BY_GATE"), listOf(refused["outcome"].asText(), refused["refusal"].asText()), refused.toString())
            assertTrue("실행 호스트 판정" in refused["reason"].asText() && "진행 중인 실행" in refused["reason"].asText(), refused.toString())
            assertTrue(
                PostgresSupport.queryAll("SELECT count(*) FROM mission.execution_journal WHERE job_order_id = 'JO-P'") { it.getInt(1) }.single() == 0,
                "판정이 막은 기체에 승인이 실행을 세웠다",
            )
        }
    }

    @Test
    fun `표시를 풀지 않은 기체는 관문 확인으로 조치 제안을 얻고, 값 없는 승인이 표시 예외로 회복 실행을 세우되 표시는 남는다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            bench.awaitPump()
            // 판정은 표시 사유만 남은 기체를 «관문 확인용» 으로 통과시킨다 — 운영 서비스가 후보로 넘길 수 있게.
            val patrol = heldThenPatrol(bench, clear = false)
            val markerId = bench.get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
            assertEquals("UNASSIGNED", patrol["result"].asText(), patrol.toString())
            val refusal = patrol["refusals"].single()
            assertEquals(HUMANOID, refusal["robotId"].asText())
            assertEquals("FOUND", refusal["remedy"]["kind"].asText(), refusal.toString())
            assertEquals(listOf(markerId), refusal["residualHoldMarkers"].map { it.asLong() })
            assertEquals(0, patrol["excluded"].size(), patrol.toString())
            assertEquals(0, journaled("JO-P"), "관문 확인용 기체에 실행이 섰다")

            val eligibility = bench.post("/host/eligibility", request(inspect("JO-E", "T1" to "bay-7"), "robotIds", HUMANOID)).body!!["robots"].single()
            assertEquals(listOf(true, true), listOf(eligibility["passed"].asBoolean(), eligibility["gateTrial"].asBoolean()), eligibility.toString())

            // 값 없는 승인 — 이번 인스턴스가 관문 확인으로 세운 제안이라 표시 사유를 빼고 받는다. 일지·판단 기록·연결이 한 트랜잭션이다.
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val (_, approved) = approve(port, AGENT, "JO-P", saw = listOf("pick_place"))
            assertEquals("APPROVED", approved["outcome"].asText(), approved.toString())
            val executionId = approved["executionId"].asText()
            assertEquals(1, journaled("JO-P"))
            assertEquals(
                listOf(markerId to executionId),
                PostgresSupport.queryAll("SELECT marker_id, execution_id FROM mission.robot_residual_hold_recovery WHERE job_order_id = 'JO-P'") {
                    it.getLong(1) to it.getString(2)
                },
            )
            assertEquals(1, bench.get("/host/robots/residual-holds")["holds"].size(), "승인이 표시를 풀었다")

            // 회복 실행이 내려놓아 손이 비어도 표시는 그대로다. 다음 순회는 관문이 통과시키지만 채택하지 않고 표시 사유로 뺀다.
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settledStates)["physicalState"].asText(), "조치 뒤 원래 순회가 끝나지 않았다")
            val next = bench.post("/host/job-orders", request(inspect("JO-Q", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", next["result"].asText(), next.toString())
            assertEquals(0, next["refusals"].size(), next.toString())
            val held = next["excluded"].single()
            assertEquals(false, held["passed"].asBoolean())
            assertTrue(held["reasons"].any { it.asText().startsWith(MissionHost.RESIDUAL_HOLD_REASON) }, held.toString())
            assertEquals(0, journaled("JO-Q"))
        }
    }

    /**
     * B2: 든 채인 기체의 빈손 확인은 충돌로 남는다. 그 뒤 관문 확인으로 선 조치 제안을 값 없이 승인해 회복 실행이 내려놓으면 손이 빈다. 그 충돌을 지목한
     * 확인은 지금 관측(EMPTY)으로 해소하고 어긋남 인지는 없으며 충돌 연결과 V14 회복 연결은 그대로 남는다.
     */
    @Test
    fun `충돌을 받은 뒤 회복 실행이 손을 비우면 그 충돌을 지목한 확인은 EMPTY 로 어긋남 인지 없이 해소한다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            heldThenPatrol(bench, clear = false)
            val markerId = bench.get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
            val refused = bench.clearHold(HUMANOID, markerId)
            assertEquals(409, refused.status, refused.body.toString())
            assertEquals("HOLDING", refused.body!!["observation"]["observedKind"].asText())
            val conflict = refused.body["conflictRequestId"].asText()

            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val (_, approved) = approve(port, AGENT, "JO-P", saw = listOf("pick_place"))
            assertEquals("APPROVED", approved["outcome"].asText(), approved.toString())
            val executionId = approved["executionId"].asText()
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionId, settledStates)["physicalState"].asText())
            val recovery = PostgresSupport.queryAll("SELECT marker_id, execution_id FROM mission.robot_residual_hold_recovery") { it.getLong(1) to it.getString(2) }
            assertEquals(listOf(markerId to executionId), recovery)

            val cleared = bench.clearHold(HUMANOID, markerId, cited = HostBench.cite(refused.body))
            assertEquals(200, cleared.status, cleared.body.toString())
            val clearing = cleared.body!!["clearing"]
            assertEquals(listOf("EMPTY", conflict), listOf(clearing["observation"]["observedKind"], clearing["conflictRequestId"]).map { it.asText() })
            assertEquals(false, clearing["acknowledgedMismatch"].asBoolean())
            assertEquals(0, bench.get("/host/robots/residual-holds")["holds"].size())
            assertEquals(recovery, PostgresSupport.queryAll("SELECT marker_id, execution_id FROM mission.robot_residual_hold_recovery") { it.getLong(1) to it.getString(2) })
        }
    }

    @Test
    fun `관문 확인으로 세우지 않은 제안은 표시 기체에서 값 없이 승인되지 않는다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            heldThenPatrol(bench, clear = false)
            // JO-P 의 제안은 관문 확인으로 섰다. 다른 작업 지시는 이 인스턴스가 관문 확인으로 세운 제안이 없다 — 표시 사유가 남아 미들웨어에 넘기기 전에 막는다.
            bench.host.approve = { _, attempt -> fail("미들웨어에 넘기지 않아야 한다: $attempt") }
            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            val (_, refused) = approve(port, AGENT, "JO-NONE", saw = listOf("pick_place"))
            assertEquals(listOf("REFUSED", "REFUSED_BY_GATE"), listOf(refused["outcome"].asText(), refused["refusal"].asText()), refused.toString())
            assertTrue(MissionHost.RESIDUAL_HOLD_REASON in refused["reason"].asText(), refused.toString())
            assertEquals(0, journaled("JO-NONE"))
        }
    }

    private fun journaled(jobOrderId: String): Int =
        PostgresSupport.queryAll("SELECT count(*) FROM mission.execution_journal WHERE job_order_id = '$jobOrderId'") { it.getInt(1) }.single()

    /** 작업 지시 하나를 정착시키고 picasso 메모리에서 놓일 때까지 기다린다. 그 작업 응답 id 를 돌려준다. */
    private fun settle(bench: HostBench, jobOrderId: String): String {
        val reply = bench.post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", reply["result"].asText(), reply.toString())
        assertEquals("PHYSICALLY_DONE", bench.driveUntil(reply["executionId"].asText(), settledStates)["physicalState"].asText())
        bench.eventually("놓기") { bench.host.footprint() == empty }
        return bench.get("/host/job-responses?jobOrderId=$jobOrderId")["responses"].last()["jobResponseId"].asText()
    }

    private fun responseIds(file: Path): Set<String> =
        if (!Files.exists(file)) emptySet() else file.readLines().filter { it.isNotBlank() }.map { JSON.readTree(it)["jobResponseId"].asText() }.toSet()

    /** 선언 파일을 쓰고 승인 호스트를 빈 포트로 여는 설정. */
    private fun loop(actions: String = """[{"skillType":"inspect","parameters":{}}]"""): Map<String, String> {
        val file = Files.createTempFile("entitlements", ".json")
        Files.writeString(
            file,
            """
            {"entitlements":[{"approverId":"$AGENT","robotIds":["$HUMANOID"],"expiresAt":"2099-01-01T00:00:00Z",
              "actions":$actions}]}
            """.trimIndent(),
        )
        return mapOf("entitlements" to file.toString(), "approval-port" to "0")
    }

    private fun approve(port: Int, approverId: String, jobOrderId: String, saw: List<String> = listOf("inspect")): Pair<Int, JsonNode> {
        val body = JSON.writeValueAsString(
            linkedMapOf(
                "approverId" to approverId, "approverKind" to "AGENT", "robotId" to HUMANOID, "jobOrderId" to jobOrderId,
                "sawSkillTypes" to saw,
            ),
        )
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/approvals")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to JSON.readTree(response.body())
    }

    private fun patrol(jobOrderId: String) = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = "InspectAsset",
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(EquipmentRequirement("T1", "inspection_target", mapOf("location" to "bay-7"))),
    )

    private companion object {
        const val AGENT = "recovery-1"
        val EXAMPLE: Path = ROOT.resolve("mission-host/incident-loop/entitlements.example.json")
        val HTTP: HttpClient = HttpClient.newHttpClient()
    }
}
