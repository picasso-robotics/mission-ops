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
    private fun loop(): Map<String, String> {
        val file = Files.createTempFile("entitlements", ".json")
        Files.writeString(
            file,
            """
            {"entitlements":[{"approverId":"$AGENT","robotIds":["$HUMANOID"],"expiresAt":"2099-01-01T00:00:00Z",
              "actions":[{"skillType":"inspect","parameters":{}}]}]}
            """.trimIndent(),
        )
        return mapOf("entitlements" to file.toString(), "approval-port" to "0")
    }

    private fun approve(port: Int, approverId: String, jobOrderId: String): Pair<Int, JsonNode> {
        val body = JSON.writeValueAsString(
            linkedMapOf(
                "approverId" to approverId, "approverKind" to "AGENT", "robotId" to HUMANOID, "jobOrderId" to jobOrderId,
                "sawSkillTypes" to listOf("inspect"),
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
