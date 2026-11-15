package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.ForgottenOrdersState
import dev.picasso.middleware.ForgottenOverflow
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.ProposalTarget
import dev.picasso.middleware.Retention
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.incident.ApprovalWindow
import dev.picasso.registry.PostgresSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 놓은 작업 지시 표시의 넘침 선언(picasso §15.324, 인계 노트 `picasso/handoff/mission-ops/tombstone-overflow.md`). 호스트는 `EVICT` 를
 * 선언하고([MissionHost.RETENTION]) 그 약속 — 내린 표시의 작업 지시 id 를 picasso 를 부르기 전에 실행 일지가 막는다 — 을 실제 호스트에서 본다.
 * 상한만 1 로 줄인 호스트에서 작업 지시 둘을 끝내 놓아 앞의 표시가 실제로 내려간 뒤, 그 작업 지시의 재제출과 그 작업 지시에 남은 제안의 값 없는
 * 승인이 새 실행도 기체 명령도 내지 않는 것을 본다.
 */
class TombstoneOverflowHostTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    @Test
    fun `호스트는 놓은 작업 지시 표시의 넘침을 EVICT 로 선언하고 두 대장의 사본을 선언하며 다른 상한은 picasso 기본 그대로 넘긴다`() {
        // 사본 선언(picasso §15.327)은 확인 대기 한도도 picasso 기본(대장 상한과 같음)이다.
        assertEquals(Retention(forgottenOverflow = ForgottenOverflow.EVICT, copyAck = dev.picasso.middleware.CopyAck()), MissionHost.RETENTION)
        HostBench().use { bench ->
            assertEquals(ForgottenOrdersState(0, Retention().forgottenOrders, ForgottenOverflow.EVICT, 0, 0), bench.host.forgottenOrders())
        }
    }

    /**
     * A 는 든 채인 휴머노이드의 관문 거절로 그 기체에 조치 제안을 세운 뒤 다시 내 사족 기체에서 끝난다(다른 기체에서 끝난 주문의 미소모 제안). B 가 끝나 놓이면
     * 상한 1 이 A 의 표시를 내린다. 그 뒤 picasso 는 A 를 더는 모르므로 막는 것은 호스트의 실행 일지뿐이다.
     */
    @Test
    fun `표시 상한이 내린 작업 지시는 재제출도 남은 제안의 값 없는 승인도 실행 일지가 picasso 앞에서 거부해 새 실행과 기체 명령이 없다`() {
        HostBench(incidentLoop = loop(), retention = MissionHost.RETENTION.copy(forgottenOrders = 1)).use { bench ->
            heldHumanoid(bench)
            // 후보가 모두 떨어질 때만 제안이 서므로(picasso §15.164) 휴머노이드 하나로 먼저 내고, 같은 작업 지시를 사족 기체로 다시 낸다.
            val proposed = bench.post("/host/job-orders", request(inspect(A, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", proposed["result"].asText(), proposed.toString())
            val refusal = proposed["refusals"].single()
            assertEquals(listOf(HUMANOID, "FOUND"), listOf(refusal["robotId"].asText(), refusal["remedy"]["kind"].asText()), proposed.toString())
            val first = bench.post("/host/job-orders", request(inspect(A, "T1" to "bay-7"), "candidates", QUADRUPED)).body!!
            assertEquals(listOf("ACCEPTED", QUADRUPED), listOf(first["result"].asText(), first["robotId"].asText()), first.toString())
            val executionA = first["executionId"].asText()
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(executionA, settledStates)["physicalState"].asText())
            bench.eventually("A 놓기") { bench.host.forgottenOrders().held == 1 }
            assertEquals(0, bench.host.retentionEvictions().forgottenOrders)

            val second = bench.post("/host/job-orders", request(inspect(B, "T1" to "bay-7"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", second["result"].asText(), second.toString())
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(second["executionId"].asText(), settledStates)["physicalState"].asText())
            // B 를 놓으면 상한 1 이 A 의 표시를 내린다. 찬 표 때문에 거부한 놓기는 없다.
            bench.eventually("A 표시 내림") { bench.host.retentionEvictions().forgottenOrders == 1L }
            assertEquals(ForgottenOrdersState(1, 1, ForgottenOverflow.EVICT, 0, 0), bench.host.forgottenOrders())

            val executions = bench.host.footprint().executions
            val tasks = tasks(bench)

            val resubmitted = bench.post("/host/job-orders", request(inspect(A, "T1" to "bay-7"), "candidates", HUMANOID, QUADRUPED))
            assertEquals(200, resubmitted.status, resubmitted.body.toString())
            val again = resubmitted.body!!
            assertEquals("REJECTED", again["result"].asText(), again.toString())
            assertTrue(again["forgottenJobOrder"].asBoolean(), again.toString())
            // picasso 의 표시 거부가 아니라 호스트 일지의 거부다(사유 끝의 출처).
            val reason = again["rejectionReason"].asText()
            assertTrue(reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER) && JOURNAL in reason, again.toString())
            assertEquals(0, again["refusals"].size(), again.toString())

            val port = checkNotNull(bench.bean(ApprovalWindow::class.java).port)
            // 승인할 제안이 아직 서 있다 — 호스트 일지가 없으면 picasso 가 이 승인으로 조치 열을 단 새 실행을 세울 장면이다.
            assertEquals(ProposalTarget.Order, bench.host.proposalTarget(HUMANOID, A))
            val refused = approve(port, A)
            assertEquals(listOf("REFUSED", "REMEDY_NOT_APPLIED"), listOf(refused["outcome"].asText(), refused["refusal"].asText()), refused.toString())
            assertTrue(refused["reason"].asText().startsWith(Middleware.FORGOTTEN_JOB_ORDER) && JOURNAL in refused["reason"].asText(), refused.toString())

            bench.mimic.server.advance(Duration.ofSeconds(5))
            bench.awaitPump()
            bench.idlePumps()
            assertEquals(executions, bench.host.footprint().executions, "picasso 실행 표에 실행이 늘었다")
            assertEquals(listOf(executionA), bench.get("/host/executions?jobOrderId=$A")["executions"].map { it["executionId"].asText() })
            assertEquals(1, journaled(A))
            assertEquals(tasks, tasks(bench), "기체에 새 명령이 나갔다")
            assertEquals(ForgottenOrdersState(1, 1, ForgottenOverflow.EVICT, 0, 0), bench.host.forgottenOrders())
        }
    }

    /** 기체마다 mimic 이 받은 태스크 수(기체에 나간 명령). */
    private fun tasks(bench: HostBench): Map<String, Int> =
        listOf(HUMANOID, QUADRUPED).associateWith { robot -> bench.mimic.server.exclusive { bench.mimic.instance(robot)!!.tasks.all.size } }

    /**
     * 휴머노이드를 든 채 실패시키고 잔여 파지 표시를 사람이 현장 확인을 지목해 «빈손» 으로 (잘못) 푼다 — 손은 여전히 든 채라 빈손을 요구하는 순회는
     * picasso 관문이 거절하고 조치 제안을 세운다(`IncidentLoopHostTest` 의 장면). 그 랙 실행은 `FAILED` 라 두는 폭(5분) 동안 놓이지 않는다.
     */
    private fun heldHumanoid(bench: HostBench) {
        bench.awaitPump()
        val rack = bench.post("/host/job-orders", request(HostBench.rack("JO-R", "RACK-204.S01"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", rack["result"].asText(), rack.toString())
        var running: String? = null
        repeat(20) {
            if (running == null) {
                bench.mimic.server.advance(Duration.ofSeconds(1)); bench.awaitPump()
                running = bench.mimic.server.exclusive {
                    bench.mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == TaskState.RUNNING }?.taskId
                }
            }
        }
        bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.forceFault("SKILL_EXECUTION_FAILED", checkNotNull(running)) }
        assertEquals("FAILED", bench.driveUntil(rack["executionId"].asText(), settledStates)["physicalState"].asText())
        bench.eventually("잔여 파지 표시") { bench.get("/host/robots/residual-holds")["holds"].size() == 1 }
        val markerId = bench.get("/host/robots/residual-holds")["holds"][0]["markerId"].asLong()
        val cleared = bench.clearHeldCiting(HUMANOID, markerId)
        assertEquals(200, cleared.status, cleared.body.toString())
        bench.awaitPump()
    }

    /** 휴머노이드의 내려놓기 조치를 덮는 자격 선언을 쓰고 승인 호스트를 빈 포트로 여는 설정. */
    private fun loop(): Map<String, String> {
        val file = Files.createTempFile("entitlements", ".json")
        Files.writeString(
            file,
            """
            {"entitlements":[{"approverId":"$AGENT","robotIds":["$HUMANOID"],"expiresAt":"2099-01-01T00:00:00Z",
              "actions":[{"skillType":"pick_place","parameters":{"destination":"RACK-204.S04"}}]}]}
            """.trimIndent(),
        )
        return mapOf("entitlements" to file.toString(), "approval-port" to "0")
    }

    /** 휴머노이드에 선 [jobOrderId] 의 제안을 값 없이 승인한다(`POST /approvals` → [MissionHost.attemptApproval]). */
    private fun approve(port: Int, jobOrderId: String): JsonNode {
        val body = JSON.writeValueAsString(
            linkedMapOf(
                "approverId" to AGENT, "approverKind" to "AGENT", "robotId" to HUMANOID, "jobOrderId" to jobOrderId,
                "sawSkillTypes" to listOf("pick_place"),
            ),
        )
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/approvals")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        return JSON.readTree(response.body())
    }

    private fun journaled(jobOrderId: String): Int =
        PostgresSupport.queryAll("SELECT count(*) FROM mission.execution_journal WHERE job_order_id = '$jobOrderId'") { it.getInt(1) }.single()

    private companion object {
        const val A = "JO-A"
        const val B = "JO-B"
        const val AGENT = "recovery-1"

        /** [MissionHost] 일지 거부 사유의 출처 꼬리. picasso 의 표시 거부 사유에는 없다. */
        const val JOURNAL = "(실행 일지)"
        val HTTP: HttpClient = HttpClient.newHttpClient()
    }
}
