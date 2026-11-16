package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 화면의 실행 목록과 실행 수(HA 설계 기록 §10)를 실제 미들웨어 실행으로 본다. 남은 실행은 같은 잠금 아래의 전체 목록에서 고른 것과,
 * 정착 이력은 정착할 때 적은 실행 본문과 같은지, 정착 기록이 커밋되기 전의 실행이 빠지지 않는지, 재기동으로 메모리가 비어도 이력이
 * 남는지, REST 인자 검사와 커서 잇기가 맞는지 본다.
 */
class ExecutionsPagedTest {

    private val settledStates = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED")
    private val running = setOf("REQUESTED", "ACCEPTED", "RUNNING", "PARTIAL", "IN_DOUBT", "OPERATOR_HOLD", "CANCELING")

    /** 전체 목록의 칸만으로 정한 활성. 판정을 다시 쓰지 않고 이름으로 적는다. */
    private fun active(full: ExecutionsView, execution: ExecutionView) =
        execution.physicalState in running || execution.units.any { it.state in setOf("OPERATOR_HOLD", "IN_DOUBT") } ||
            execution.executionId in full.journalBlockedExecutionIds

    private fun submit(bench: HostBench, jobOrderId: String): String {
        val reply = bench.post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", reply["result"].asText(), reply.toString())
        return reply["executionId"].asText()
    }

    private fun settledEvents(): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event WHERE kind = 'SETTLED'") { it.getInt(1) }

    private fun ids(node: JsonNode) = node["executions"].map { it["executionId"].asText() }

    private fun settledIds(node: JsonNode) = node["settled"].map { it["execution"]["executionId"].asText() }

    /**
     * 정착 넷(exec-1~4, 정착 기록까지)과 도는 하나(exec-5). exec-5 는 가상 시계를 밀지 않아도 다음 pump 에서 `ACCEPTED` 에서
     * `RUNNING` 으로 넘어가므로, 그 전에 돌려주면 두 번 나눠 읽는 시험이 읽기 사이의 pump 에 따라 갈린다. 그래서 `RUNNING` 에 선
     * 뒤에 돌려준다. 가상 시계가 멈춰 있는 동안에는 그 뒤로 바뀌지 않는다.
     */
    private fun seed(bench: HostBench) {
        for (job in listOf("JO-1", "JO-2", "JO-3", "JO-4")) bench.driveUntil(submit(bench, job), settledStates)
        bench.eventually("정착 기록 넷") { settledEvents() == 4 }
        val live = submit(bench, "JO-5")
        bench.eventually("$live 가 RUNNING") { bench.execution(live)?.get("physicalState")?.asText() == "RUNNING" }
    }

    @Test
    fun `화면 목록은 같은 순간의 전체 목록에서 남은 실행을 고르고 정착 이력은 정착할 때의 실행 본문이며 실행 수는 남은 실행을 센 것이다`() {
        HostBench().use { bench ->
            seed(bench)
            val host = bench.bean(MissionHost::class.java)
            val mapper = bench.bean(ObjectMapper::class.java)
            for (recent in listOf(1, 2, 4, 5, ExecutionPages.MAX)) {
                val (full, page, summary) = host.exclusive { Triple(host.executions(), host.executionsRecent(recent), host.executionSummary()) }
                val settled = full.executions.filter { !active(full, it) }
                assertEquals(full.executions.filter { active(full, it) }, page.executions, "recent=$recent")
                assertEquals(listOf("exec-5"), page.executions.map { it.executionId })
                // 정착 이력은 최근 정착부터이고 그 본문은 지금 전체 목록의 그 실행과 같다(정착 뒤 바뀐 것이 없다).
                val kept = settled.reversed().take(recent)
                assertEquals(kept.map { mapper.valueToTree<JsonNode>(it) }, page.settled.map { it.execution }, "recent=$recent")
                assertTrue(page.settled.all { it.snapshot && it.instanceId == full.instanceId })
                assertEquals(page.settled.map { it.settleSeq }.sortedDescending(), page.settled.map { it.settleSeq })
                assertEquals(page.settled.last().settleSeq.takeIf { settled.size > recent }, page.nextBefore, "recent=$recent")
                assertEquals(maxOf(0, settled.size - recent), page.olderTotal)
                assertEquals(Triple(full.instanceId, full.restore, full.journalBlockedExecutionIds), Triple(page.instanceId, page.restore, page.journalBlockedExecutionIds))

                assertEquals(page.executions.size, summary.live)
                assertEquals(PhysicalStateNames, summary.states.keys.toList())
                assertEquals(PhysicalStateNames.associateWith { name -> page.executions.count { it.physicalState == name } }, summary.states)
                assertEquals(full.journalBlockedExecutionIds, summary.journalBlockedExecutionIds)
                assertEquals(full.instanceId, summary.instanceId)
            }
            val first = host.executionsRecent(1)
            val older = host.executionsBefore(first.nextBefore!!, 2)
            assertEquals(listOf("exec-3", "exec-2"), older.settled.map { it.execution["executionId"].asText() })
            assertEquals(1, older.olderTotal)
            assertEquals(listOf("exec-1"), host.executionsBefore(older.nextBefore!!, 2).settled.map { it.execution["executionId"].asText() })
        }
    }

    @Test
    fun `정착 기록이 커밋되기 전의 정착 실행은 남은 실행으로 보이고 기록되면 정착 이력으로 옮긴다`() {
        HostBench().use { bench ->
            Fenced.execute(
                """
                CREATE FUNCTION mission.refuse_settled() RETURNS trigger AS ${'$'}${'$'} BEGIN
                  IF NEW.kind = 'SETTLED' THEN RAISE EXCEPTION 'settled refused'; END IF; RETURN NEW; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            Fenced.execute("CREATE TRIGGER refuse_settled BEFORE INSERT ON mission.execution_journal_event FOR EACH ROW EXECUTE FUNCTION mission.refuse_settled()")
            bench.driveUntil(submit(bench, "JO-1"), settledStates)
            bench.idlePumps()
            val unrecorded = bench.get("/host/executions?recent=5")
            assertEquals(listOf("exec-1"), ids(unrecorded), "정착했으나 기록되지 않은 실행이 빠졌다")
            assertEquals("PHYSICALLY_DONE", unrecorded["executions"][0]["physicalState"].asText())
            assertTrue(unrecorded["settled"].isEmpty)
            assertEquals(1, bench.get("/host/executions/summary")["live"].asInt())

            Fenced.execute("DROP TRIGGER refuse_settled ON mission.execution_journal_event")
            bench.eventually("정착 기록") { settledEvents() == 1 }
            val recorded = bench.get("/host/executions?recent=5")
            assertEquals(emptyList(), ids(recorded))
            assertEquals(listOf("exec-1"), settledIds(recorded))
            assertEquals(0, bench.get("/host/executions/summary")["live"].asInt())
        }
    }

    @Test
    fun `커서를 이어 읽으면 정착 이력이 다 한 번씩 나오고 사이에 정착이 생겨도 남은 페이지가 그대로이며 재기동 뒤에도 남는다`() {
        HostBench().use { bench ->
            seed(bench)
            val mapper = bench.bean(ObjectMapper::class.java)
            fun withoutPump(node: JsonNode) = node.deepCopy<ObjectNode>().apply { remove("pumpedAt") }
            val first = bench.get("/host/executions?recent=1")
            assertEquals(withoutPump(mapper.readTree(mapper.writeValueAsString(bench.bean(MissionHost::class.java).executionsRecent(1)))), withoutPump(first))
            assertEquals(
                listOf("instanceId", "pumpedAt", "executions", "restore", "journalBlockedExecutionIds", "reworkableTargets", "settled", "nextBefore", "olderTotal", "unrestored"),
                first.fieldNames().asSequence().toList(),
            )
            assertEquals(listOf("settleSeq", "instanceId", "settledAt", "snapshot", "execution", "superseded"), first["settled"][0].fieldNames().asSequence().toList())
            assertEquals(listOf("exec-5"), ids(first))
            assertEquals(listOf("exec-4"), settledIds(first))
            val page = { cursor: Long -> bench.get("/host/executions?before=$cursor&limit=2") }
            val second = page(first["nextBefore"].asLong())
            assertEquals(listOf("settled", "nextBefore", "olderTotal"), second.fieldNames().asSequence().toList())
            assertEquals(listOf("exec-3", "exec-2"), settledIds(second))
            // 사이에 도는 실행이 정착하고 새 실행이 붙는다. 남은 페이지는 커서 앞의 정착 차례만 읽으므로 그대로다.
            bench.driveUntil("exec-5", settledStates)
            bench.eventually("정착 기록 다섯") { settledEvents() == 5 }
            submit(bench, "JO-6")
            val third = page(second["nextBefore"].asLong())
            assertEquals(listOf("exec-1"), settledIds(third))
            assertTrue(third["nextBefore"].isNull && third["olderTotal"].asInt() == 0, third.toString())
            assertEquals(second, page(first["nextBefore"].asLong()), "같은 커서의 페이지는 새 정착 뒤에도 같다")
            val after = bench.get("/host/executions?recent=1")
            assertEquals(listOf("exec-6"), ids(after))
            assertEquals(listOf("exec-5"), settledIds(after))
            assertEquals(4, after["olderTotal"].asInt())
            assertEquals(listOf("exec-4", "exec-3", "exec-2", "exec-1"), settledIds(bench.get("/host/executions?before=${after["nextBefore"].asLong()}")), "기본 페이지 크기는 20 이다")

            // 재기동: 새 인스턴스의 메모리에는 정착한 실행이 없다. 정착 이력은 기록에서 읽으므로 그대로이고 앞 인스턴스를 싣는다.
            val previous = after["instanceId"].asText()
            val history = settledIds(bench.get("/host/executions?recent=10"))
            bench.restartHost()
            val restarted = bench.get("/host/executions?recent=10")
            assertTrue(restarted["instanceId"].asText() != previous)
            assertTrue(restarted["executions"].none { it["executionId"].asText() in setOf("exec-1", "exec-2", "exec-3", "exec-4", "exec-5") && it["jobOrderId"].asText() != "JO-6" })
            assertEquals(history, settledIds(restarted))
            assertTrue(restarted["settled"].all { it["instanceId"].asText() == previous }, restarted.toString())
        }
    }

    @Test
    fun `일지에 막힌 실행은 활성이라 화면 목록에 늘 들고 실행 수의 남은 실행과 막힘에도 든다`() {
        HostBench().use { bench ->
            Fenced.execute(
                """
                CREATE FUNCTION mission.refuse_journal() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'journal refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            Fenced.execute("CREATE TRIGGER refuse_journal BEFORE INSERT ON mission.execution_journal FOR EACH ROW EXECUTE FUNCTION mission.refuse_journal()")
            assertEquals(500, bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).status)
            val page = bench.get("/host/executions?recent=1")
            assertEquals(listOf("exec-1"), ids(page))
            assertEquals(listOf("exec-1"), page["journalBlockedExecutionIds"].map { it.asText() })
            assertNull(page["nextBefore"].numberValue())
            val summary = bench.get("/host/executions/summary")
            assertEquals(listOf("exec-1"), summary["journalBlockedExecutionIds"].map { it.asText() })
            assertEquals(1, summary["live"].asInt())
            assertEquals(1, summary["states"].fields().asSequence().sumOf { it.value.asInt() })
        }
    }

    @Test
    fun `페이지 인자는 범위와 짝을 검사하고 인자 없는 목록과 모르는 인자는 그대로다`() {
        HostBench().use { bench ->
            bench.driveUntil(submit(bench, "JO-1"), settledStates)
            val bad = listOf(
                "recent=0", "recent=201", "recent=x", "recent=", "recent=1&recent=2", "recent=1&before=3", "recent=1&limit=2",
                "recent=1&jobOrderId=JO-1", "limit=2", "before=0", "before=-1", "before=x", "before=", "before=3&before=4",
                "before=3&limit=0", "before=3&limit=201", "before=3&executionId=exec-1",
            )
            for (query in bad) {
                val reply = bench.fetch("/host/executions?$query")
                assertEquals(400, reply.status, query)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), query)
            }
            assertEquals(200, bench.fetch("/host/executions?recent=200").status)
            assertEquals(200, bench.fetch("/host/executions?before=${Long.MAX_VALUE}&limit=200").status)
            val all = bench.get("/host/executions")
            assertEquals(setOf("instanceId", "pumpedAt", "executions", "restore", "journalBlockedExecutionIds"), all.fieldNames().asSequence().toSet())
            assertEquals(listOf("exec-1"), ids(all))
            assertEquals(ids(all), ids(bench.get("/host/executions?instanceId=x&other=1")), "모르는 인자는 전처럼 버린다")
        }
    }

    private companion object {
        val PhysicalStateNames = listOf(
            "REQUESTED", "ACCEPTED", "RUNNING", "PARTIAL", "IN_DOUBT", "OPERATOR_HOLD",
            "PHYSICALLY_DONE", "UNVERIFIED", "FAILED", "CANCELING", "ABORTED",
        )
    }
}
