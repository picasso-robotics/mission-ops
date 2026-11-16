package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.ApprovalAttempt
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.RemedySearchRecord
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.rack
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.nio.file.Files
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 두 대장의 사본(picasso §15.327, 한계 §15.166 의 소유자 = 담는 쪽, V17). 인시던트 줄·탐색 줄을 picasso 글자 그대로 사본 표에 적고 커밋 뒤 확인하는 것,
 * 사본을 식별자로 모으는 것(작은 상한), 파일 게시가 DB 커밋 뒤인 것, 일반 승인 경로의 일지·판단 기록이 한 트랜잭션인 것, 같은 조치 승인 횟수가
 * 인스턴스를 넘어 합산되고 재시도·복원이 두 번 세지 않는 것, 재기동 뒤 소모된 승인의 재시도가 새 실행을 세우지 않는 것을 본다.
 */
class LedgerCopyHostTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private val empty = HostFootprint(0, 0, 0, 0, 0, 0, 0)

    /**
     * 결함 1 회귀: 사본 수집이 개수(`incidents.drop(copiedIncidents)`)로 세면, 사본 선언 아래에서 확인한 줄을 상한이 앞에서 내려 목록 길이가 상한에
     * 머물고 그 뒤 새 인시던트를 영영 적지 않는다. 상한 1 에서 세 작업 지시가 차례로 보류에 서고 완료 확인으로 끝나 개정판 보존 폭이 지나 놓여도(그래야
     * 상한이 앞 번들을 내린다) 세 인시던트의 줄이 다 적히고, 완료 확인이 붙인 판단은 같은 id 의 개정 줄로 다시 적히며, 확인 전 내림은 0 이고 확인 대기는
     * 남지 않는다.
     */
    @Test
    fun `사본은 식별자로 모아 작은 상한에서 이빅션한 뒤에도 인시던트 줄이 빠지지 않고 판단은 개정 줄로 적으며 확인 전 내림은 0 이다`() {
        HostBench(retention = MissionHost.RETENTION.copy(incidents = 1)).use { bench ->
            bench.mimic.server.advance(Duration.ofSeconds(1))
            bench.awaitPump()
            bench.activateHold()
            val instance = bench.host.instanceId
            val executions = (1..3).map { i ->
                val submitted = bench.post("/host/job-orders", request(rack("JO-$i", "RACK-204.S01"), "candidates", HUMANOID)).body!!
                assertEquals("ACCEPTED", submitted["result"].asText(), submitted.toString())
                val executionId = submitted["executionId"].asText()
                bench.driveToHold(executionId)
                bench.eventually("첫 줄 $i") { lines(instance).size == i }
                assertEquals("Resolved", bench.resolve(executionId, "rack-arrival", "CONFIRM_DONE", "kim").body!!["result"].asText())
                bench.driveUntil(executionId, settledStates)
                bench.mimic.server.advance(MissionHost.DEFAULT_REVISABLE_RETENTION.plusMinutes(1))
                bench.awaitPump()
                runCatching { bench.eventually("놓기 $i") { bench.host.footprint() == empty } }
                    .onFailure { throw AssertionError("${bench.host.footprint()} ${bench.get("/host/executions")}", it) }
                executionId
            }
            bench.eventually("개정 줄") { lines(instance).all { it.second == 2 } }
            val written = lines(instance)
            assertEquals(executions, written.map { JSON.readTree(it.third)["executionId"].asText() })
            assertEquals(written.map { it.first }, written.map { JSON.readTree(it.third)["incidentId"].asText() })
            written.forEach { (_, _, line) -> assertEquals("CONFIRM_DONE", JSON.readTree(line)["resolution"]["decision"].asText(), line) }
            // 줄 곁의 규약 판은 picasso 의 LINE_SCHEMA_VERSION 이다.
            assertEquals(
                setOf(LedgerExport.LINE_SCHEMA_VERSION),
                PostgresSupport.queryAll("SELECT wire_version FROM mission.incident_copy WHERE instance_id = '$instance'") { it.getString(1) }.toSet(),
            )
            // 상한이 실제로 내렸고(앞 인시던트는 대장에서 빠졌다), 그래도 사본에는 다 있다. 확인 전에 내린 줄은 없다.
            assertTrue(bench.host.retentionEvictions().incidents >= 2, bench.host.retentionEvictions().toString())
            val acks = bench.host.copyAcks()
            assertEquals(listOf(0L, 0L), listOf(acks.incidents.evictedBeforeCopy, acks.remedySearches.evictedBeforeCopy), acks.toString())
            assertEquals(0, acks.incidents.pending, acks.toString())
            val summary = bench.get("/host/executions/summary")["ledgerCopy"]
            assertEquals(true, summary["declared"].asBoolean(), summary.toString())
            assertEquals(listOf(0, 0, 0), listOf("pending", "exposed", "evictedBeforeCopy").map { summary["incidents"][it].asInt() }, summary.toString())
        }
    }

    /**
     * 검토가 번들을 새 객체로 바꾸면 picasso 는 그 id 를 다시 확인 대기로 올린다(§15.327). 줄 글자가 같으면 적을 개정은 없지만 확인은 다시 보내야
     * 한다 — 안 보내면 `pending` 이 영영 남는다. 줄에 검토가 실리면(보통) 개정 줄로 다시 적고 확인한다.
     */
    @Test
    fun `줄 글자가 같은 재검토도 다시 확인해 확인 대기가 남지 않고 글자가 바뀐 검토는 개정 줄로 적는다`() {
        HostBench().use { bench ->
            // 검토를 줄에 싣지 않는 인코더 — «새 객체, 같은 글자» 장면을 만든다(첫 줄도 이 인코더로 적어 글자가 맞는다).
            bench.host.incidentLine = { LedgerExport.incidentLine(it.copy(review = null)) }
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            val instance = bench.host.instanceId
            bench.eventually("첫 줄") { lines(instance).size == 1 }
            bench.eventually("첫 확인") { bench.host.copyAcks().incidents.pending == 0 }
            val incidentId = lines(instance).single().first

            assertTrue(bench.host.reviewIncident(incidentId, dev.picasso.middleware.ReviewVerdict.AGREED, "같은 글자"))
            bench.eventually("같은 글자 재확인") { bench.host.copyAcks().incidents.pending == 0 }
            bench.idlePumps()
            assertEquals(0, bench.host.copyAcks().incidents.pending)
            assertEquals(0, count("incident_copy_revision"), "같은 글자를 개정으로 적었다")

            bench.host.incidentLine = LedgerExport::incidentLine
            assertTrue(bench.host.reviewIncident(incidentId, dev.picasso.middleware.ReviewVerdict.DISPUTED, "다른 글자"))
            bench.eventually("개정 줄") { lines(instance).single().second == 2 }
            assertEquals("DISPUTED", JSON.readTree(lines(instance).single().third)["review"]["verdict"].asText())
            bench.eventually("개정 확인") { bench.host.copyAcks().incidents.pending == 0 }
        }
    }

    /**
     * 결함 2 회귀: 파일 게시는 DB 커밋 뒤다. 사본 쓰기가 실패하는 동안에는 보류의 인시던트가 파일에 나가지 않고(파일이 DB 보다 앞서지 않는다), 쓰기가
     * 풀리면 커밋된 그 줄이 파일에 게시된다 — 파일의 줄과 사본 표의 줄은 같은 글자다.
     */
    @Test
    fun `내보내기 파일은 DB 커밋 뒤에만 게시되고 커밋이 풀리면 사본 표와 같은 줄을 게시한다`() {
        val export = createTempDirectory("ledger-copy")
        HostBench(incidentLoop = mapOf("export" to export.toString())).use { bench ->
            refuseInserts("incident_copy")
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.idlePumps()
            assertEquals(1, bench.get("/host/incidents")["total"].asInt())
            assertEquals(0, count("incident_copy"))
            assertEquals(emptyList(), fileLines(export.resolve(LedgerExport.INCIDENTS)), "커밋 전에 게시했다")

            Fenced.execute("DROP TRIGGER refuse_insert ON mission.incident_copy")
            bench.eventually("게시") { fileLines(export.resolve(LedgerExport.INCIDENTS)).size == 1 }
            assertEquals(lines(bench.host.instanceId).map { it.third }, fileLines(export.resolve(LedgerExport.INCIDENTS)))

            // 판단이 같은 id 의 줄을 바꾸면 개정 줄(2)로 다시 적는다 — 첫 줄은 그대로 남는다(덧붙이기만).
            assertEquals("Resolved", bench.resolve(executionId, "rack-arrival", "REWORK", "kim").body!!["result"].asText())
            bench.eventually("개정 줄") { lines(bench.host.instanceId).single().second == 2 }
            val revised = JSON.readTree(lines(bench.host.instanceId).single().third)
            assertEquals(listOf("REWORK", "kim"), listOf(revised["resolution"]["decision"].asText(), revised["resolution"]["decidedBy"]["id"].asText()), revised.toString())
            assertTrue(
                JSON.readTree(PostgresSupport.queryOne("SELECT line FROM mission.incident_copy") { it.getString(1) })["resolution"].isNull,
                "첫 줄을 고쳤다",
            )
            assertEquals(0, bench.host.copyAcks().incidents.pending)
        }
    }

    /**
     * 결함 3 회귀: 일반 승인 경로(값 없는 승인으로 새 실행이 서는 길)의 실행 일지·판단 기록·승인 사실은 한 트랜잭션이다. 판단 기록 저장이 실패하면
     * 일지도 남지 않고 pump·제출·승인이 막히며(로봇 명령이 나가지 않는다), 저장이 풀리면 다음 pump 앞에서 다시 적어 판단 기록의 승인 칸에 조치 열과
     * 소모가 들고 승인 사실이 한 행 선다.
     */
    @Test
    fun `일반 승인 경로의 일지와 판단 기록은 한 트랜잭션이라 판단 기록 저장이 실패하면 막히고 풀리면 다음 pump 앞에서 다시 적는다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            val patrol = misclearedThenPatrol(bench)
            assertTrue(patrol["result"].asText() != "ACCEPTED", patrol.toString())
            refuseInserts("execution_judgment_record", "NEW.job_order_id = 'JO-P'")
            val tasksBefore = tasks(bench)

            assertFailsWith<JournalWriteFailed> { bench.host.attemptApproval(attempt("JO-P")) }
            assertEquals(0, journaled("JO-P"), "판단 기록 없이 일지만 커밋됐다")
            assertEquals(0, count("remedy_approval"))
            val blocked = bench.get("/host/executions/summary")["journalBlockedExecutionIds"].map { it.asText() }
            assertEquals(1, blocked.size, blocked.toString())
            bench.idlePumps()
            assertEquals(tasksBefore, tasks(bench), "일지 없이 로봇 명령이 나갔다")
            assertEquals("REJECTED", bench.post("/host/job-orders", request(inspect("JO-Z", "T1" to "bay-7"), "candidates", HUMANOID)).body!!["result"].asText())
            val again = bench.host.attemptApproval(attempt("JO-Q"))
            assertTrue(again is dev.picasso.middleware.ApprovalOutcome.Refused && again.reason.contains("실행 일지 저장 실패"), again.toString())

            Fenced.execute("DROP TRIGGER refuse_insert ON mission.execution_judgment_record")
            bench.eventually("다시 적기") { journaled("JO-P") == 1 }
            assertTrue(bench.get("/host/executions/summary")["journalBlockedExecutionIds"].isEmpty)
            val record = JSON.readTree(
                PostgresSupport.queryOne("SELECT record FROM mission.execution_judgment_record WHERE job_order_id = 'JO-P' ORDER BY record_id LIMIT 1") { it.getString(1) },
            )
            val approval = record["approval"]
            assertEquals(listOf("pick_place"), approval["steps"].map { it["skillType"].asText() }, record.toString())
            assertEquals(AGENT, approval["approverId"].asText(), record.toString())
            assertEquals(listOf(HUMANOID to """["pick_place"]"""), approvalFacts())
            assertEquals(blocked.single(), PostgresSupport.queryOne("SELECT execution_id FROM mission.remedy_approval") { it.getString(1) })
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(blocked.single(), settledStates)["physicalState"].asText())
        }
    }

    /**
     * 재기동 뒤 소모된 승인을 다시 보내도 새 실행은 서지 않는다(복원 입구 없음, picasso §15.327): 다시 지은 실행에서는 판단 기록의 승인으로 소모가
     * 다시 서 `CONSUMED`, 정착해 놓은 작업 지시는 실행 일지가 `REMEDY_NOT_APPLIED`, 받은 적 없는 작업 지시는 `NO_PROPOSAL` 이다. 승인 사실은 복원과
     * 재시도에 늘지 않고, 이전 인스턴스의 같은 조치 승인과 합산된다.
     */
    @Test
    fun `재기동 뒤 소모된 승인의 재시도는 새 실행을 세우지 않고 승인 횟수는 인스턴스를 넘어 합산되며 재시도와 복원은 두 번 세지 않는다`() {
        HostBench(incidentLoop = loop(putDown)).use { bench ->
            misclearedThenPatrol(bench)
            val approved = bench.host.attemptApproval(attempt("JO-P"))
            assertTrue(approved is dev.picasso.middleware.ApprovalOutcome.Approved, approved.toString())
            val first = bench.host.instanceId
            assertEquals(listOf(HUMANOID to """["pick_place"]"""), approvalFacts())
            // 관문 거절의 탐색 줄(FOUND)은 다음 pump 뒤 기록이 사본 표에 적는다 — 그 전에 죽으면 잃는다(보장은 프로세스 생존 중까지).
            bench.eventually("탐색 사본") { count("remedy_search_copy") == 1 }

            bench.restartHost()
            val second = bench.host.instanceId
            assertNotEquals(first, second)
            val row = bench.get("/host/executions")["restore"]["rows"].single { it["jobOrderId"].asText() == "JO-P" }
            assertEquals("RESTORED", row["result"].asText(), row.toString())
            val rebuilt = row["executionId"].asText()
            val consumed = bench.host.attemptApproval(attempt("JO-P"))
            assertTrue(
                consumed is dev.picasso.middleware.ApprovalOutcome.Refused && consumed.refusal == dev.picasso.middleware.ApprovalRefusal.CONSUMED,
                "$consumed ${bench.get("/host/executions")}",
            )
            assertEquals(listOf(rebuilt), executionsOf(bench, "JO-P"), "재시도가 새 실행을 세웠다")
            assertEquals("PHYSICALLY_DONE", bench.driveUntil(rebuilt, settledStates)["physicalState"].asText())
            bench.eventually("놓기") { bench.host.footprint() == empty }
            val settled = bench.host.attemptApproval(attempt("JO-P"))
            assertTrue(
                settled is dev.picasso.middleware.ApprovalOutcome.Refused && settled.refusal == dev.picasso.middleware.ApprovalRefusal.REMEDY_NOT_APPLIED,
                settled.toString(),
            )
            assertEquals(1, journaled("JO-P"))
            // 받은 적 없는 작업 지시(이 인스턴스에 제안이 없다)는 NO_PROPOSAL 이다 — 기체가 빈 뒤라 호스트 판정이 먼저 막지 않는다.
            val none = bench.host.attemptApproval(attempt("JO-NEVER"))
            assertTrue(none is dev.picasso.middleware.ApprovalOutcome.Refused && none.refusal == dev.picasso.middleware.ApprovalRefusal.NO_PROPOSAL, none.toString())
            assertTrue(executionsOf(bench, "JO-NEVER").isEmpty())
            assertEquals(0, journaled("JO-NEVER"))
            // 복원과 재시도는 승인 사실을 늘리지 않는다 — 열쇠가 승인 자체다.
            assertEquals(listOf(HUMANOID to """["pick_place"]"""), approvalFacts())
            assertEquals(first, PostgresSupport.queryOne("SELECT instance_id FROM mission.remedy_approval") { it.getString(1) })

            // 이전 인스턴스의 같은 조치 승인(기체 + 조치 유형 열, 승인자 종류는 가르지 않는다)과 합산된다.
            Fenced.execute(
                """
                INSERT INTO mission.remedy_approval (instance_id, execution_id, job_order_id, robot_id, remedy_types, approver_id, approver_kind, approved_at, wall_clock_at)
                VALUES ('mw-older', 'exec-3', 'JO-OLD', '$HUMANOID', '["pick_place"]', 'kim', 'PERSON', now(), now())
                """.trimIndent(),
            )
            val repeated = bench.get("/host/repeated-remedies")["remedies"].single()
            assertEquals(
                listOf(HUMANOID, "pick_place", "2", "2"),
                listOf(repeated["robotId"].asText(), repeated["remedyTypes"].single().asText(), repeated["approvals"].asText(), repeated["instances"].asText()),
                repeated.toString(),
            )
            assertTrue(bench.get("/host/repeated-remedies?atLeast=3")["remedies"].isEmpty)
            assertEquals(400, bench.fetch("/host/repeated-remedies?atLeast=0").status)
            // 이 인스턴스의 탐색 줄은 아직 없고, 앞 인스턴스의 탐색 줄(관문 거절의 FOUND)은 사본 표에서 읽힌다.
            val searches = bench.get("/host/remedy-searches")
            assertTrue(searches["earlier"].any { it["instanceId"].asText() == first && it["outcome"].asText() == "FOUND" }, searches.toString())
            assertTrue(searches["earlier"].filter { it["outcome"].asText() == "FOUND" }.all { it["line"]["steps"].isArray }, searches.toString())
            assertEquals(0L, bench.host.copyAcks().remedySearches.evictedBeforeCopy)
        }
    }

    /**
     * 재기동 앞뒤 인시던트가 같은 `incidentKey` 로 이어지고, 앞 인스턴스의 인시던트 줄은 사본 표에서 읽힌다(그 줄도 같은 열쇠를 든다).
     */
    @Test
    fun `재기동 앞뒤 인시던트는 같은 incidentKey 로 이어지고 앞 인스턴스의 줄은 사본 표에서 읽힌다`() {
        HostBench().use { bench ->
            val first = bench.host.instanceId
            val executionId = bench.submitHeldOrder("JO-1")
            bench.driveToHold(executionId)
            bench.eventually("첫 줄") { lines(first).size == 1 }
            val key = bench.get("/host/incidents")["incidents"].single()["incidentKey"].asText()

            bench.restartHost()
            val second = bench.host.instanceId
            bench.driveToHold("exec-1")
            bench.eventually("다시 연 보류의 줄") { lines(second).size == 1 }
            val before = JSON.readTree(lines(first).single().third)
            val after = JSON.readTree(lines(second).single().third)
            assertEquals(listOf(key, key), listOf(before["incidentKey"].asText(), after["incidentKey"].asText()))
            assertEquals("REOPENED", after["openingKind"].asText(), after.toString())
            // 줄에는 인스턴스가 없다 — 사본 표의 인스턴스 칸이 그것이다. 같은 인시던트 id 가 두 인스턴스에 따로 있다.
            assertFalse(before.has("instanceId"), before.toString())
            assertEquals(1, bench.get("/host/incidents")["earlierTotal"].asInt())
        }
    }

    /**
     * 가림(`WITHHELD`) 탐색의 줄은 DB·조회 응답 어디에도 `steps` 가 없다 — 담는 쪽이 제안 표의 걸음을 조인해 넣지 않는다. 이 호스트는 가림을 켜지 않으므로
     * 탐색 대장 읽기 자리([MissionHost.remedySearches])에 그 기록을 주입한다. 걸음을 조인해 넣은 줄은 DB 가 거부한다(결함 주입).
     */
    @Test
    fun `WITHHELD 탐색 줄은 사본 표와 조회 응답 어디에도 steps 가 없고 걸음을 조인한 줄은 DB 가 거부한다`() {
        HostBench().use { bench ->
            bench.awaitPump()
            val first = bench.host.instanceId
            val at = bench.now()
            val withheld = RemedySearchRecord("search-900", HUMANOID, "JO-W", at, Instant.now(), RemedyOutcome.Withheld)
            bench.host.remedySearches = { it.remedySearches() + withheld }
            bench.eventually("탐색 사본") { count("remedy_search_copy") == 1 }
            val stored = PostgresSupport.queryOne("SELECT outcome, wire_version, line FROM mission.remedy_search_copy") {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }
            assertEquals(Triple("WITHHELD", LedgerExport.LINE_SCHEMA_VERSION, LedgerExport.remedySearchLine(withheld)), stored)
            assertFalse(JSON.readTree(stored.third).has("steps"), stored.third)
            val live = bench.get("/host/remedy-searches")["searches"].single()
            assertEquals(listOf(first, "search-900", "WITHHELD"), listOf(live["instanceId"], live["searchId"], live["outcome"]).map { it.asText() })
            assertFalse(live["line"].has("steps"), live.toString())
            bench.idlePumps()
            assertEquals(1, count("remedy_search_copy"), "같은 탐색을 두 번 적었다")

            bench.restartHost()
            val reply = bench.fetch("/host/remedy-searches")
            val earlier = reply.body!!["earlier"].single()
            assertEquals(listOf(first, "WITHHELD"), listOf(earlier["instanceId"], earlier["outcome"]).map { it.asText() })
            assertFalse(earlier["line"].has("steps"), earlier.toString())
            assertFalse("steps" in reply.body.toString(), reply.body.toString())

            // 결함 주입: 담는 쪽이 제안의 걸음을 조인해 WITHHELD 줄을 채우면 사본 표가 받지 않는다.
            val joined = stored.third.removeSuffix("}") + ""","steps":[{"skillType":"pick_place"}]}"""
            val e = assertFailsWith<SQLException> {
                Fenced.execute(
                    "INSERT INTO mission.remedy_search_copy (instance_id, search_id, job_order_id, robot_id, outcome, wire_version, line) " +
                        "VALUES ('mw-x', 'search-1', 'JO-W', '$HUMANOID', 'WITHHELD', '9', '$joined')",
                )
            }
            assertTrue("remedy_search_copy_withheld_steps" in e.message!!, e.message)
        }
    }

    // ── 장면

    /**
     * 든 채 실패하고 잔여 파지 표시를 사람이 «빈손 확인» 으로 잘못 푼 기체에 빈손을 요구하는 순회를 낸다 — picasso 관문이 스냅숏의 손으로 거절하고 조치
     * 제안(내려놓기)을 세운다(인시던트 고리 시험과 같은 장면). 그 순회의 응답을 돌려준다.
     */
    private fun misclearedThenPatrol(bench: HostBench): JsonNode {
        bench.awaitPump()
        val rack = bench.post("/host/job-orders", request(rack("JO-R", "RACK-204.S01"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", rack["result"].asText(), rack.toString())
        var running: String? = null
        repeat(20) {
            if (running == null) {
                bench.mimic.server.advance(Duration.ofSeconds(1)); bench.awaitPump()
                running = bench.mimic.server.exclusive {
                    bench.mimic.instance(HUMANOID)!!.tasks.all.firstOrNull { it.machine.state == dev.picasso.mimic.engine.TaskState.RUNNING }?.taskId
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
        return bench.post("/host/job-orders", request(inspect("JO-P", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
    }

    private val putDown = """[{"skillType":"pick_place","parameters":{"destination":"RACK-204.S04"}}]"""

    private fun attempt(jobOrderId: String) = ApprovalAttempt(Approver(AGENT, ApproverKind.AGENT), HUMANOID, jobOrderId, listOf("pick_place"))

    private fun loop(actions: String): Map<String, String> {
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

    private fun tasks(bench: HostBench): Int = bench.mimic.server.exclusive { bench.mimic.instance(HUMANOID)!!.tasks.all.size }

    private fun executionsOf(bench: HostBench, jobOrderId: String): List<String> =
        bench.get("/host/executions")["executions"].filter { it["jobOrderId"].asText() == jobOrderId }.map { it["executionId"].asText() }

    private companion object {
        const val AGENT = "recovery-1"

        fun count(table: String): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.$table") { it.getInt(1) }

        fun journaled(jobOrderId: String): Int =
            PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal WHERE job_order_id = '$jobOrderId'") { it.getInt(1) }

        /** 승인 사실(기체, 조치 유형 열 JSON). 적은 순서다. */
        fun approvalFacts(): List<Pair<String, String>> =
            PostgresSupport.queryAll("SELECT robot_id, remedy_types::text FROM mission.remedy_approval ORDER BY approval_id") { it.getString(1) to it.getString(2) }

        /** 그 인스턴스의 인시던트 줄 — 인시던트마다 가장 큰 개정(인시던트 id, 개정, 줄), 첫 줄을 적은 순서. */
        fun lines(instanceId: String): List<Triple<String, Int, String>> =
            PostgresSupport.queryAll(
                """
                SELECT c.incident_id, coalesce(r.revision, 1), coalesce(r.line, c.line)
                FROM mission.incident_copy c
                LEFT JOIN LATERAL (
                    SELECT revision, line FROM mission.incident_copy_revision x
                    WHERE x.instance_id = c.instance_id AND x.incident_id = c.incident_id ORDER BY revision DESC LIMIT 1
                ) r ON true
                WHERE c.instance_id = '$instanceId' AND c.line IS NOT NULL ORDER BY c.copy_id
                """.trimIndent(),
            ) { Triple(it.getString(1), it.getInt(2), it.getString(3)) }

        /** [table] 의 INSERT 를 거부하는 트리거(`refuse_insert`). [condition] 을 주면 그 행만 거부한다. */
        fun refuseInserts(table: String, condition: String = "true") {
            Fenced.execute(
                """
                CREATE OR REPLACE FUNCTION mission.refuse_insert_$table() RETURNS trigger AS ${'$'}${'$'}
                BEGIN IF $condition THEN RAISE EXCEPTION 'insert refused'; END IF; RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            Fenced.execute("CREATE TRIGGER refuse_insert BEFORE INSERT ON mission.$table FOR EACH ROW EXECUTE FUNCTION mission.refuse_insert_$table()")
        }

        fun fileLines(file: java.nio.file.Path): List<String> = if (!Files.exists(file)) emptyList() else file.readLines().filter { it.isNotBlank() }
    }
}
