package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.web.HostController
import dev.picasso.registry.PostgresSupport
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /host/executions` 를 작업 지시 id·실행 id 로 좁힌 읽기(HA 설계 기록 §10). 좁힌 본문이 같은 잠금 아래의 전체 목록에서 그 실행만
 * 남긴 것과 같은지, 인자 없는 목록이 정착한 실행까지 전과 같은 본문인지 본다.
 */
class ExecutionsNarrowedTest {

    private val settled = setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL")

    /** [full] 에서 작업 지시 [jobs] 나 실행 [ids] 의 실행만 남긴 것. 일지 막힘도 남은 실행의 것만이다. */
    private fun kept(full: ExecutionsView, jobs: Set<String>, ids: Set<String>): ExecutionsView {
        val kept = full.executions.filter { it.jobOrderId in jobs || it.executionId in ids }
        return full.copy(executions = kept, journalBlockedExecutionIds = full.journalBlockedExecutionIds.filter { id -> kept.any { it.executionId == id } })
    }

    private fun submit(bench: HostBench, jobOrderId: String): String {
        val reply = bench.post("/host/job-orders", request(inspect(jobOrderId, "T1" to "bay-7"), "candidates", HUMANOID)).body!!
        assertEquals("ACCEPTED", reply["result"].asText(), reply.toString())
        return reply["executionId"].asText()
    }

    private fun query(name: String, value: String) = "$name=" + URLEncoder.encode(value, Charsets.UTF_8)

    @Test
    fun `작업 지시나 실행 id 로 좁힌 실행 목록은 같은 순간의 전체 목록에서 그 실행만 남긴 것과 같다`() {
        HostBench().use { bench ->
            // 응답이 있는 정착한 실행 둘과 도는 실행 하나.
            for (job in listOf("JO-1", "JO-2")) bench.driveUntil(submit(bench, job), settled)
            submit(bench, "JO-3")
            val host = bench.bean(MissionHost::class.java)
            val cases = listOf(
                setOf("JO-1") to emptySet(), setOf("JO-3", "JO-없음") to emptySet(), emptySet<String>() to setOf("exec-2"),
                setOf("JO-1") to setOf("exec-3"), setOf("JO-없음") to setOf("exec-없음"), setOf("JO-1", "JO-2", "JO-3") to emptySet(),
            )
            for ((jobs, ids) in cases) {
                val (full, chosen) = host.exclusive { host.executions() to host.executions(jobs, ids) }
                assertEquals(kept(full, jobs, ids), chosen, "$jobs $ids")
            }
            val full = host.executions()
            assertEquals(listOf("JO-1", "JO-2", "JO-3"), full.executions.map { it.jobOrderId })
            assertTrue(full.executions.take(2).all { it.jobResponse != null }, full.toString())
            assertEquals(full.executions, host.executions(setOf("JO-1", "JO-2", "JO-3"), emptySet()).executions)
        }
    }

    @Test
    fun `일지에 막힌 실행은 좁힌 목록에 그 실행이 들 때만 막힘으로 나온다`() {
        HostBench().use { bench ->
            Fenced.execute(
                """
                CREATE FUNCTION mission.refuse_journal() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'journal refused'; END; ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            Fenced.execute("CREATE TRIGGER refuse_journal BEFORE INSERT ON mission.execution_journal FOR EACH ROW EXECUTE FUNCTION mission.refuse_journal()")
            assertEquals(500, bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).status)
            for (path in listOf("/host/executions", "/host/executions?jobOrderId=JO-1", "/host/executions?executionId=exec-1")) {
                val view = bench.get(path)
                assertEquals(listOf("exec-1"), view["journalBlockedExecutionIds"].map { it.asText() }, path)
                assertEquals(listOf("exec-1"), view["executions"].map { it["executionId"].asText() }, path)
            }
            val other = bench.get("/host/executions?jobOrderId=JO-2")
            assertTrue(other["executions"].isEmpty && other["journalBlockedExecutionIds"].isEmpty, other.toString())
        }
    }

    @Test
    fun `인자 없는 실행 목록은 정착한 실행까지 전과 같은 본문이고 좁히는 값은 쉼표로 가르지 않으며 빈 값은 400 이다`() {
        HostBench().use { bench ->
            bench.driveUntil(submit(bench, "JO-1"), settled)
            bench.driveUntil(submit(bench, "JO,2"), settled)
            val host = bench.bean(MissionHost::class.java)
            val mapper = bench.bean(ObjectMapper::class.java)
            fun withoutPump(node: com.fasterxml.jackson.databind.JsonNode) = (node.deepCopy<ObjectNode>()).apply { remove("pumpedAt") }
            val all = bench.get("/host/executions")
            assertEquals(withoutPump(mapper.valueToTree(host.executions())), withoutPump(all))
            assertEquals(setOf("instanceId", "pumpedAt", "executions", "restore", "journalBlockedExecutionIds"), all.fieldNames().asSequence().toSet())
            assertEquals(listOf("JO-1", "JO,2"), all["executions"].map { it["jobOrderId"].asText() })
            assertEquals(withoutPump(all), withoutPump(bench.get("/host/executions?other=JO-1")), "모르는 인자는 전과 같이 버린다")
            assertEquals(listOf("JO,2"), bench.get("/host/executions?" + query(HostController.JOB_ORDER_ID, "JO,2"))["executions"].map { it["jobOrderId"].asText() })
            val both = bench.get("/host/executions?" + query(HostController.JOB_ORDER_ID, "JO-1") + "&" + query(HostController.EXECUTION_ID, "exec-2"))
            assertEquals(withoutPump(all), withoutPump(both))
            for (path in listOf("/host/executions?jobOrderId=", "/host/executions?jobOrderId=JO-1&executionId=", "/host/executions?executionId=%20")) {
                val reply = bench.fetch(path)
                assertEquals(400, reply.status, path)
                assertEquals("BAD_REQUEST", reply.body!!["error"].asText(), path)
            }
        }
    }
}
