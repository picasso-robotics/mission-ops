package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.middleware.JudgmentRecordWire
import dev.picasso.middleware.LedgerExport
import dev.picasso.mimic.engine.TaskState
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.registry.PostgresSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 리비전 제거(picasso §15.329, 내보내기 버전 10, V18). 작업 지시에서 손대지 않은 항목을 빼는 리비전은 받아 그 단위를 지우고(발행 안 됨) 제거 사건을
 * 개정판 행·판단 기록과 같은 트랜잭션에 적으며 재기동 뒤 리빌드가 그대로 선다. 손댄 항목을 빼는 리비전은 picasso 사유 그대로 거부하고 아무것도
 * 저장하지 않는다. 지운 뒤 남은 단위가 모두 끝났으면 그 자리에서 정산하고 작업 응답은 한 번이다.
 */
class RevisionRemovalHostTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    private fun HostBench.submit(vararg targets: Pair<String, String>): String {
        val accepted = post("/host/job-orders", request(inspect("JO-1", *targets), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
        return accepted["executionId"].asText()
    }

    /** 작업 지시 JO-1 의 버전 [version] 을 [targets] 로 낸다. */
    private fun HostBench.revise(version: Int, vararg targets: Pair<String, String>): HostBench.Reply =
        post("/host/job-orders", request(inspect("JO-1", *targets).replace("\"version\":1", "\"version\":$version"), "candidates", HUMANOID))

    private fun units(execution: JsonNode): Map<String, String> = execution["units"].associate { it["unitId"].asText() to it["state"].asText() }

    private fun HostBench.driveUnits(executionId: String, done: (JsonNode) -> Boolean): JsonNode {
        repeat(120) {
            val execution = checkNotNull(execution(executionId))
            if (done(execution)) return execution
            mimic.server.advance(Duration.ofSeconds(1))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
        }
        error("실행이 바라는 상태에 이르지 않았다: ${execution(executionId)}")
    }

    private fun HostBench.tasks(): Map<String, TaskState> =
        mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.associate { it.taskId to it.machine.state } }

    private fun HostBench.records() = bean(HostRecords::class.java)

    private fun mapping(): Path = Files.createTempFile("work-permits", ".json").also {
        Files.writeString(it, """{"signals":[{"signal":"guard_closed","permittedWhen":"true","robots":["$HUMANOID"]}]}""")
        it.toFile().deleteOnExit()
    }

    /** 안내(`manifest.json`)가 센 제거 건수. 아직 안내가 없으면 널이다 — 안내는 이름 바꾸기로 놓이므로 반쯤 쓰인 것을 읽지 않는다. */
    private fun manifestRemovals(export: Path): Int? = export.resolve(LedgerExport.MANIFEST)
        .takeIf { Files.exists(it) }?.let { JSON.readTree(it.readText())["counts"]?.get("revisionRemovals")?.asInt() }

    private fun guard(value: String): String {
        val closed = "\"name\":\"guard_closed\",\"location\":null,\"kind\":\"BOOLEAN\",\"safety\":true,\"value\":\"true\""
        return HostBench.STANDARD_CELL.replace(closed, closed.replace("\"value\":\"true\"", "\"value\":\"$value\""))
    }

    @Test
    fun `손대지 않은 항목을 뺀 리비전은 받아 그 단위를 지우고 내지 않으며 제거 사건을 개정판 행과 함께 적고 재기동 뒤 리빌드가 선다`() {
        val export = createTempDirectory("revision-removal")
        HostBench(incidentLoop = mapOf("export" to export.toString())).use { bench ->
            val before = bench.host.instanceId
            val executionId = bench.submit("T1" to "bay-7", "T2" to "dock-3")
            val revised = bench.revise(2, "T1" to "bay-7")
            assertEquals(200, revised.status, revised.body.toString())
            val body = revised.body!!
            assertEquals(listOf("ACCEPTED", executionId), listOf(body["result"].asText(), body["executionId"].asText()), body.toString())
            // 제출 결과가 제거를 싣는다 — 리비전 2 가 T2 의 두 단위를 범위에서 뺐다(실패·취소가 아니다).
            val removal = body["revisionRemoval"]
            assertEquals(listOf(before, executionId, "1", "2"), listOf("instanceId", "executionId", "fromVersion", "toVersion").map { removal[it].asText() })
            assertEquals(listOf("T2.travel" to "PENDING", "T2" to "PENDING"), removal["removed"].map { it["unitId"].asText() to it["state"].asText() })

            val execution = bench.execution(executionId)!!
            assertEquals(listOf("T1.travel", "T1"), execution["units"].map { it["unitId"].asText() }, execution.toString())
            assertEquals(removal, execution["revisionRemovals"].single())

            // 같은 트랜잭션: 개정판 행과 제거 사건이 함께 있다. 줄은 picasso 글자 그대로이고 곁의 판은 LINE_SCHEMA_VERSION 이다.
            assertEquals(listOf(2), versions())
            val row = bench.records().revisionRemovals(listOf("JO-1")).single()
            assertEquals(listOf(before, executionId, "JO-1", HUMANOID), listOf(row.instanceId, row.executionId, row.jobOrderId, row.robotId))
            assertEquals(LedgerExport.LINE_SCHEMA_VERSION, row.wireVersion)
            val line = JSON.readTree(row.line)
            assertEquals(listOf(executionId, "JO-1", "1", "2"), listOf("executionId", "jobOrderId", "fromVersion", "toVersion").map { line[it].asText() })
            assertEquals(removal["removed"], line["removed"])
            // 제거 사건은 picasso 판단 기록에 실리지 않는다 — 기록은 지운 뒤의 단위만 든다.
            val record = JSON.readTree(bench.records().latestJudgmentRecord("JO-1")!!.record)
            assertTrue("T2" !in record.toString(), record.toString())

            // 내보내기(버전 10): 커밋한 제거 사건이 셋째 레지스터에 picasso 글자 그대로 실리고 안내가 그 수를 센다.
            // 안내는 한 벌의 맨 마지막에 바뀐다 — 레지스터만 보고 읽으면 앞 벌의 안내(0 건)를 읽는다. 안내의 건수를 기다린다.
            bench.eventually("안내의 제거 건수") { manifestRemovals(export) == 1 }
            assertEquals(listOf(row.line), export.resolve(LedgerExport.REVISION_REMOVALS).readLines())
            val manifest = JSON.readTree(export.resolve(LedgerExport.MANIFEST).readText())
            assertEquals("10", manifest["schemaVersion"].asText(), manifest.toString())
            assertEquals(1, manifest["counts"]["revisionRemovals"].asInt(), manifest.toString())
            val copy = bench.get("/host/executions/summary")["ledgerCopy"]["revisionRemovals"]
            assertEquals(listOf(1, 0, 0), listOf("copied", "pending", "evictedBeforeCopy").map { copy[it].asInt() }, copy.toString())

            // 재기동: 최신 작업 지시(버전 2)와 판단 기록이 맞아 다시 짓는다(`RESUME_RECORD_MISMATCH` 아님). 앞 인스턴스의 제거가 실행 본문에 남는다.
            bench.restartHost()
            val restore = bench.get("/host/executions")["restore"]["rows"].single()
            assertEquals("RESTORED", restore["result"].asText(), restore.toString())
            val rebuilt = bench.execution(restore["executionId"].asText())!!
            assertEquals(listOf("T1.travel", "T1"), rebuilt["units"].map { it["unitId"].asText() }, rebuilt.toString())
            assertEquals(before, rebuilt["revisionRemovals"].single()["instanceId"].asText())

            val done = bench.driveUntil(rebuilt["executionId"].asText(), settledStates)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            // 뺀 단위는 내지 않았고 작업 응답의 완료·미완료 목록에도 없다.
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1"), bench.tasks().keys)
            assertEquals(listOf("T1", "T1.travel"), done["jobResponse"]["completedUnits"].map { it.asText() }.sorted(), done.toString())
            assertTrue(done["jobResponse"]["incompleteUnits"].isEmpty, done.toString())
            assertEquals(1, removalRows())
        }
    }

    @Test
    fun `발행한 단위가 든 항목을 뺀 리비전은 picasso 사유 그대로 거부하고 개정판 행 판단 기록 제거 사건을 남기지 않으며 실행은 그대로 돈다`() {
        HostBench().use { bench ->
            val executionId = bench.submit("T1" to "bay-7", "T2" to "dock-3")
            bench.eventually("첫 단위 발행") { units(bench.execution(executionId)!!)["T1.travel"] != "PENDING" }
            val unitsBefore = bench.execution(executionId)!!["units"].map { it["unitId"].asText() }

            val refused = bench.revise(2, "T2" to "dock-3")
            assertEquals(200, refused.status, refused.body.toString())
            val body = refused.body!!
            assertEquals("REJECTED", body["result"].asText(), body.toString())
            val reason = body["rejectionReason"].asText()
            assertTrue(reason.startsWith("리비전이 손댄 단위를 뺀다: T1.travel("), reason)
            assertTrue(reason.endsWith("발행했거나 끝난 단위가 든 항목은 뺄 수 없다. 새 작업 지시로 낸다"), reason)
            assertTrue(body["revisionRemoval"].isNull, body.toString())

            // 아무것도 저장하지 않았다 — 적용된 작업 지시(개정판 행)도, 판단 기록도, 제거 사건도 없다. 일지도 막히지 않았다.
            bench.idlePumps()
            assertEquals(emptyList(), versions())
            assertEquals(0, removalRows())
            // 판단 기록은 모두 버전 1 의 것이다 — 거부된 리비전이 기록에 들지 않았다.
            assertEquals(setOf(1), bench.records().judgmentRecords("JO-1").map { JudgmentRecordWire.decode(it.record).orderVersion }.toSet())
            val after = bench.get("/host/executions")
            assertTrue(after["journalBlockedExecutionIds"].isEmpty, after.toString())
            val execution = after["executions"].single()
            assertEquals(unitsBefore, execution["units"].map { it["unitId"].asText() })
            assertTrue(execution["revisionRemovals"].isEmpty, execution.toString())

            // 같은 버전 2 를 다시 내도 같은 거부다(버전이 오르지 않았다). 실행은 버전 1 그대로 끝까지 간다.
            assertEquals(reason.substringBefore("("), bench.revise(2, "T2" to "dock-3").body!!["rejectionReason"].asText().substringBefore("("))
            val done = bench.driveUntil(executionId, settledStates)
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            assertEquals(1, done["jobResponse"]["version"].asInt(), done.toString())
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1", "JO-1#T2.travel", "JO-1#T2"), bench.tasks().keys)
        }
    }

    @Test
    fun `지운 뒤 남은 단위가 모두 끝났으면 그 자리에서 정산하고 그 버전의 작업 응답은 한 번이다`() {
        HostBench(permitMapping = mapping()).use { bench ->
            val executionId = bench.submit("T1" to "bay-7", "T2" to "dock-3")
            bench.driveUnits(executionId) { units(it)["T1"] == "RUNNING" }
            // 다음 단위(T2.travel)의 발행을 작업 허가로 막아 손대지 않은 채 둔다.
            bench.cellBody = guard("false")
            bench.awaitPump()
            bench.awaitPump()
            val blocked = bench.driveUnits(executionId) { units(it)["T1"] == "DONE" && !it["permitBlock"].isNull }
            assertEquals("T2.travel", blocked["permitBlock"]["unitId"].asText(), blocked.toString())
            assertEquals("PENDING", units(blocked)["T2.travel"])

            val revised = bench.revise(2, "T1" to "bay-7").body!!
            assertEquals("ACCEPTED", revised["result"].asText(), revised.toString())
            assertEquals(listOf("T2.travel", "T2"), revised["revisionRemoval"]["removed"].map { it["unitId"].asText() })
            val settled = bench.execution(executionId)!!
            assertEquals("PHYSICALLY_DONE", settled["physicalState"].asText(), settled.toString())
            assertTrue(settled["permitBlock"].isNull, settled.toString())

            bench.eventually("정착 기록") { settledEvents() == 1 }
            bench.idlePumps()
            val v2 = responses().filter { it.first == 2 }
            assertEquals(listOf(2 to "PHYSICALLY_DONE"), v2, responses().toString())
            assertEquals(1, settledEvents())
            assertEquals(1, removalRows())
            assertEquals(setOf("JO-1#T1.travel", "JO-1#T1"), bench.tasks().keys)
        }
    }

    /**
     * 결함 주입: 제거 사건 쓰기가 실패하면 그 트랜잭션의 개정판 행·판단 기록도 함께 남지 않고(같은 트랜잭션), 실행은 일지 막힘에 들어 pump 가 돌지 않으며,
     * 쓰기가 다시 되면 다음 pump 가 둘을 함께 적는다.
     */
    @Test
    fun `제거 사건을 적지 못하면 개정판 행도 남지 않고 일지가 막히며 다음 pump 가 함께 다시 적는다`() {
        HostBench().use { bench ->
            val executionId = bench.submit("T1" to "bay-7", "T2" to "dock-3")
            Fenced.execute(
                "CREATE FUNCTION mission.refuse_removal() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'removal refused'; END; ${'$'}${'$'} LANGUAGE plpgsql",
            )
            Fenced.execute("CREATE TRIGGER refuse_removal BEFORE INSERT ON mission.revision_removal FOR EACH ROW EXECUTE FUNCTION mission.refuse_removal()")
            try {
                assertEquals(500, bench.revise(2, "T1" to "bay-7").status)
                bench.idlePumps()
                assertEquals(emptyList(), versions())
                assertEquals(0, removalRows())
                assertEquals(listOf(executionId), bench.get("/host/executions")["journalBlockedExecutionIds"].map { it.asText() })
            } finally {
                Fenced.execute("DROP TRIGGER refuse_removal ON mission.revision_removal")
                Fenced.execute("DROP FUNCTION mission.refuse_removal()")
            }
            bench.eventually("다시 적음") { versions() == listOf(2) && removalRows() == 1 }
            bench.eventually("막힘 풀림") { bench.get("/host/executions")["journalBlockedExecutionIds"].isEmpty }
            assertEquals(listOf("T1.travel", "T1"), bench.execution(executionId)!!["units"].map { it["unitId"].asText() })
        }
    }

    /**
     * 결함 회귀: 게시할 제거 사건을 picasso 레지스터에서 모으면, pump 뒤 기록이 실패해 게시가 늦는 동안 레지스터가 보존 상한(1)으로 앞 사건을 내릴 때
     * 이미 커밋한 그 사건이 파일에서 영영 빠진다(`evictedBeforeCopy` 도 0). 게시는 커밋된 사본 행에서 모아 두 사건이 다 실린다.
     */
    @Test
    fun `게시가 늦는 동안 레지스터가 상한으로 내린 제거 사건도 커밋된 사본 행에서 모아 내보내기 파일에 싣는다`() {
        val export = createTempDirectory("revision-removal-late")
        HostBench(incidentLoop = mapOf("export" to export.toString()), retention = MissionHost.RETENTION.copy(revisionRemovals = 1)).use { bench ->
            listOf("JO-1" to HUMANOID, "JO-2" to HostBench.QUADRUPED).forEach { (jobOrderId, robot) ->
                val accepted = bench.post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7", "T2" to "dock-3"), "candidates", robot)).body!!
                assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            }
            bench.idlePumps()
            // pump 뒤 기록(송신 기록)을 실패시켜 게시를 늦춘다. 리비전의 개정판 행·제거 사건 트랜잭션은 그대로 커밋된다.
            Fenced.execute(
                "CREATE FUNCTION mission.refuse_response() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'response refused'; END; ${'$'}${'$'} LANGUAGE plpgsql",
            )
            Fenced.execute("CREATE TRIGGER refuse_response BEFORE INSERT ON mission.job_response_log FOR EACH ROW EXECUTE FUNCTION mission.refuse_response()")
            try {
                listOf("JO-1", "JO-2").forEach { jobOrderId ->
                    val revised = bench.post(
                        "/host/job-orders",
                        request(inspect(jobOrderId, "T1" to "bay-7").replace("\"version\":1", "\"version\":2"), "candidates", if (jobOrderId == "JO-1") HUMANOID else HostBench.QUADRUPED),
                    ).body!!
                    assertEquals("ACCEPTED", revised["result"].asText(), revised.toString())
                }
                assertEquals(2, removalRows())
                bench.idlePumps()
                // 레지스터는 상한 1 이라 앞 사건을 내렸다.
                assertEquals(1L, bench.host.retentionEvictions().revisionRemovals)
            } finally {
                Fenced.execute("DROP TRIGGER refuse_response ON mission.job_response_log")
                Fenced.execute("DROP FUNCTION mission.refuse_response()")
            }
            val file = export.resolve(LedgerExport.REVISION_REMOVALS)
            bench.eventually("두 사건 게시") { manifestRemovals(export) == 2 }
            val rows = bench.records().revisionRemovalsOf(bench.host.instanceId)
            assertEquals(rows.map { it.line }, file.readLines())
            val copy = bench.get("/host/executions/summary")["ledgerCopy"]["revisionRemovals"]
            assertEquals(listOf(2, 0, 0), listOf("copied", "pending", "evictedBeforeCopy").map { copy[it].asInt() }, copy.toString())
        }
    }

    private companion object {
        fun versions(): List<Int> =
            PostgresSupport.queryAll("SELECT version FROM mission.execution_journal_revision WHERE job_order_id = 'JO-1' ORDER BY revision_id") { it.getInt(1) }

        fun removalRows(): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.revision_removal") { it.getInt(1) }

        fun settledEvents(): Int =
            PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event WHERE job_order_id = 'JO-1' AND kind = 'SETTLED'") { it.getInt(1) }

        fun responses(): List<Pair<Int, String>> = PostgresSupport.queryAll(
            "SELECT version, physical_state FROM mission.job_response_log WHERE job_order_id = 'JO-1' ORDER BY log_id",
        ) { it.getInt(1) to it.getString(2) }
    }
}
