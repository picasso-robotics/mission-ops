package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.JournalEventKind
import dev.picasso.ops.host.store.SettledRow
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 정착 이력의 페이지(V8, HA 설계 기록 §10). 정착 기록 표에서 읽은 페이지가 정착 이벤트 전부를 정착 차례로 늘어놓고 자른 것과 같은지,
 * 커서를 이어 읽으면 겹치거나 빠지지 않는지, 새 정착이 생겨도 남은 페이지가 그대로인지 본다. 미들웨어 없이 기록 표에 바로 적는다.
 */
class SettledHistoryTest {

    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val jdbc = JdbcClient.create(ds)
    private val records = HostRecords(jdbc, TestLease.guard(ds), ObjectMapper())
    private var orders = 0

    @BeforeTest
    fun fresh() {
        Fenced.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
    }

    /** 작업 지시 하나를 일지에 적고, 정착이면 정착 이벤트를 적는다. 사이에 다른 이벤트를 섞어 정착 차례가 연속이 아니게 한다. */
    private fun execution(random: Random, settle: Boolean, instance: String = "mw-1"): String {
        val job = "JO-${++orders}"
        records.journal(job, "humanoid-01", """{"jobOrderId":"$job"}""", "InspectAsset", null, instance, "exec-$orders")
        if (random.nextBoolean()) records.event(job, JournalEventKind.DEFERRED, instance, null, "미룸")
        if (settle) records.settled(job, instance, "exec-$orders", "PHYSICALLY_DONE", """{"executionId":"exec-$orders","n":$orders}""")
        return job
    }

    /** 표의 정착 이벤트 전부. 최근부터. */
    private fun all(): List<SettledRow> = records.settledPage(null, Int.MAX_VALUE)

    @Test
    fun `정착 이력 페이지는 정착 이벤트 전부를 최근부터 늘어놓고 커서 앞에서 limit 개로 자른 것과 같다`() {
        val random = Random(20261015)
        repeat(60) { execution(random, settle = random.nextDouble() < 0.7) }
        val full = all()
        assertEquals(full.map { it.eventId }.sortedDescending(), full.map { it.eventId })
        assertTrue(full.size in 30..55, "${full.size}")
        val cursors = listOf<Long?>(null) + full.map { it.eventId } + listOf(full.first().eventId + 1, 1L)
        var cases = 0
        for (before in cursors) for (limit in listOf(1, 2, 7, full.size, ExecutionPages.MAX)) {
            val older = full.filter { before == null || it.eventId < before }
            val page = older.take(limit)
            val rest = older.size - page.size
            assertEquals(page, records.settledHistory(before, limit).rows, "before=$before limit=$limit")
            assertEquals(page.lastOrNull()?.eventId?.takeIf { rest > 0 }, records.settledHistory(before, limit).nextBefore, "before=$before limit=$limit")
            assertEquals(rest, records.settledHistory(before, limit).olderTotal, "before=$before limit=$limit")
            cases++
        }
        assertTrue(cases > 200)
    }

    @Test
    fun `커서를 이어 읽으면 정착 이력이 겹치거나 빠지지 않고 사이에 새 정착이 생겨도 남은 페이지가 그대로다`() {
        val random = Random(7)
        repeat(25) { execution(random, settle = random.nextDouble() < 0.8) }
        for (limit in listOf(1, 3, 4, 50)) {
            val before = all()
            val first = records.settledHistory(null, limit)
            val seen = first.rows.toMutableList()
            var cursor = first.nextBefore
            var pages = 0
            while (cursor != null) {
                val page = records.settledHistory(cursor, limit)
                // 사이에 새 정착과 정착 없는 실행이 생긴다. 같은 커서의 페이지는 같다.
                repeat(2) { execution(random, settle = random.nextBoolean()) }
                assertEquals(page, records.settledHistory(cursor, limit), "limit=$limit")
                assertTrue(page.rows.size in 1..limit && page.rows.all { it.eventId < cursor!! }, "limit=$limit $page")
                seen += page.rows
                cursor = page.nextBefore
                pages++
            }
            assertEquals(before, seen, "limit=$limit: 겹치거나 빠졌다")
            if (first.olderTotal > 0) assertEquals((first.olderTotal + limit - 1) / limit, pages)
        }
    }

    @Test
    fun `정착 이력은 인스턴스를 가리지 않고 일지 행의 기체와 임무를 싣고 본문 없는 정착 이벤트도 읽는다`() {
        val random = Random(1)
        val earlier = execution(random, settle = true, instance = "mw-0")
        records.journal("JO-old", "quadruped-01", "{}", "PrepareSequencedRack", 3, "mw-0", "exec-9")
        // V8 전에 적힌 정착 이벤트(본문 없음).
        records.event("JO-old", JournalEventKind.SETTLED, "mw-0", "exec-9", "UNVERIFIED")
        val later = execution(random, settle = true, instance = "mw-1")
        val rows = records.settledHistory(null, 10).rows
        assertEquals(listOf(later, "JO-old", earlier), rows.map { it.jobOrderId })
        assertEquals(listOf("mw-1", "mw-0", "mw-0"), rows.map { it.instanceId })
        val old = rows[1]
        assertEquals(listOf("exec-9", "UNVERIFIED", "quadruped-01", "PrepareSequencedRack", 3), listOf(old.executionId, old.physicalState, old.robotId, old.workMasterId, old.missionVersion))
        assertNull(old.snapshot)
        assertEquals(ObjectMapper().readTree("""{"executionId":"exec-2","n":2}"""), ObjectMapper().readTree(rows[0].snapshot))
        assertEquals(SettledRowsEmpty, records.settledHistory(rows.last().eventId, 5).let { it.rows.size to it.nextBefore })
        // 정착 본문은 정착 이벤트에만 적는다.
        val refused = runCatching {
            jdbc.sql("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail, snapshot) VALUES ('JO-old', 'DEFERRED', 'mw-0', 'x', '{}')").update()
        }
        assertTrue(refused.isFailure, "정착 아닌 이벤트에 본문을 적을 수 있다")
    }

    private companion object {
        val SettledRowsEmpty: Pair<Int, Long?> = 0 to null
    }
}
