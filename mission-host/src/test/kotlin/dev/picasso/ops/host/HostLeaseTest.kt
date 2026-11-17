package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportStatus
import dev.picasso.ops.host.lease.Acquired
import dev.picasso.ops.host.lease.GatedFleetPort
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.SendAuthority
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.host.lease.HostLease
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeadershipListener
import dev.picasso.ops.host.lease.LeaseConnections
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.store.AuthorityGuard
import dev.picasso.ops.host.store.AuthorityLost
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.LeaseClaim
import dev.picasso.ops.host.store.NotAuthority
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 리스 행·DB 쓰기 펜싱·리더 상태기계(V19, HA 설계 기록 §4·§6·§7)를 호스트 없이 실제 시험 Postgres 에서 본다. 호스트를 띄워 보는 것은
 * [HostLeaderTest]·[HostTakeoverTest] 다.
 */
class HostLeaseTest {

    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

    @BeforeTest
    fun fresh() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
    }

    @Test
    fun `여러 인스턴스가 동시에 획득하면 하나만 얻고 세대는 한 번만 오르며 산 리스는 다시 얻지 못한다`() {
        val leases = (1..8).map { HostLease("mw-$it", FAST, PLAIN) }
        try {
            val barrier = CyclicBarrier(leases.size)
            val pool = Executors.newFixedThreadPool(leases.size)
            val results = leases.map { lease -> pool.submit<Acquired?> { barrier.await(); lease.acquire() } }.map { it.get(10, TimeUnit.SECONDS) }
            pool.shutdown()
            val winners = results.withIndex().filter { it.value != null }
            assertEquals(1, winners.size, results.toString())
            assertEquals(Acquired(1, null, false), winners.single().value)
            assertEquals("mw-${winners.single().index + 1}" to 1L, row())
            // 산 리스는 다른 인스턴스가 다시 얻지 못한다(세대도 그대로다).
            assertTrue(leases.filterIndexed { i, _ -> i != winners.single().index }.all { it.acquire() == null })
            assertEquals(1L, row().second)
        } finally {
            leases.forEach { it.close() }
        }
    }

    @Test
    fun `만료한 리스의 늦은 갱신은 거부되고 권위를 되살리지 않으며 다음 획득은 세대를 올리고 앞 보유자의 멈춤과 발행 표시를 본다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            assertNotNull(a.acquire())
            assertTrue(a.renew())
            assertTrue(a.markIssuing())
            Thread.sleep(FAST.expiry.toMillis() + 200)
            assertFalse(a.renew(), "만료한 리스의 갱신이 받아들여졌다")
            assertNull(a.generation)
            assertFalse(a.withinDeadline())
            assertEquals(false, PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_lease_term") { it.getBoolean(1) })
            assertFalse(a.markIssuing(), "잃은 세대가 발행 표시를 적었다")
            // 앞 보유자가 발행을 열었고 멈춤을 적었으면 다음 획득은 정상 종료 인수로 읽는다.
            Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at, stopped_at) VALUES ('mw-a', clock_timestamp(), clock_timestamp())")
            HostLease("mw-b", FAST, PLAIN).use { b ->
                val taken = assertNotNull(b.acquire())
                assertEquals(Acquired(2, "mw-a", previousStopped = true, previousIssuing = true), taken)
                assertTrue(taken.graceful)
                // 새 세대는 발행 표시 없이 시작한다.
                assertEquals(false, PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) })
            }
        }
    }

    @Test
    fun `획득 응답을 못 받으면 다시 고치지 않고 행을 읽어 내가 쥔 세대를 확인하되 세대 1 이라도 만료 인수로 읽는다`() {
        var opened = 0
        val lossy = LeaseConnections {
            val real = PostgresSupport.connection()
            if (opened++ > 0) real else answerLost(real)
        }
        HostLease("mw-a", FAST, lossy).use { a ->
            val recovered = a.acquire()
            assertEquals(Acquired(1, null, false, recovered = true), recovered)
            assertFalse(recovered!!.graceful, "조회로 복구한 첫 획득이 정상 종료 인수로 읽혔다")
            assertEquals(1L, a.generation)
            assertTrue(a.withinDeadline())
            assertEquals("mw-a" to 1L, row())
            assertTrue(a.renew())
            assertEquals(1L, row().second)
        }
    }

    @Test
    fun `커밋 응답과 첫 조회를 모두 잃은 획득은 다음 주기 조회로 복구해도 자기 차단 기한을 처음 획득 문장의 송신 시각에서 잰다`() {
        var opened = 0
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        val lossy = LeaseConnections {
            val real = PostgresSupport.connection()
            when (opened++) {
                0 -> answerLost(real)
                1 -> {
                    real.close()
                    throw SQLException("조회도 끊겼다", "08001")
                }
                else -> real
            }
        }
        val deadline = FAST.expiry.minus(FAST.margin).toNanos()
        HostLease("mw-a", FAST, lossy, nanoTime = clock::get).use { a ->
            assertNull(a.acquire(), "확인하지 못한 획득을 쥔 것으로 봤다")
            assertEquals("mw-a" to 1L, row())
            // 한 주기 뒤 조회가 된다. 기한은 처음 시각(0)에서 재고, 조회 시각에서 다시 재지 않는다.
            clock.set(FAST.period.toNanos())
            assertEquals(Acquired(1, null, false, recovered = true), a.acquire())
            clock.set(deadline - 1)
            assertTrue(a.withinDeadline())
            clock.set(deadline)
            assertFalse(a.withinDeadline(), "조회로 자기 차단 기한이 늘었다")
        }
        // 기한이 이미 지난 뒤에야 조회가 되면 복구하지 않는다.
        PostgresSupport.execute("DROP SCHEMA mission CASCADE")
        HostSchema.flyway(ds).migrate()
        opened = 0
        HostLease("mw-b", FAST, lossy, nanoTime = clock::get).use { b ->
            clock.set(0)
            assertNull(b.acquire())
            clock.set(deadline + 1)
            assertNull(b.acquire(), "기한이 지난 획득을 복구했다")
            assertNull(b.generation)
        }
    }

    @Test
    fun `다른 세션이 보유자 행을 배타로 쥔 동안 갱신은 그 잠금을 기다리다 잠금 대기 제한으로 실패하고 풀리면 성공한다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            assertNotNull(a.acquire())
            PostgresSupport.connection().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT holder FROM mission.host_lease WHERE role = 'host' FOR UPDATE") }
                val started = System.nanoTime()
                val e = assertFailsWith<SQLException> { a.renew() }
                assertEquals("55P03", e.sqlState, e.message)
                assertTrue(Duration.ofNanos(System.nanoTime() - started) >= FAST.period.minusMillis(50), "갱신이 보유자 행 잠금을 기다리지 않았다")
                holder.rollback()
            }
            assertTrue(a.renew())
        }
    }

    @Test
    fun `만료 읽기 뒤 다른 인스턴스가 먼저 얻은 경합에서 뒤늦은 대기가 잠근 채 멎어도 새 보유자의 갱신과 발행 표시는 이어지고 기한을 잃지 않는다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            assertNotNull(a.acquire())
            Thread.sleep(FAST.expiry.toMillis() + 200)
            HostLease("mw-b", FAST, PLAIN).use { late ->
                HostLease("mw-c", FAST, PLAIN).use { c ->
                    val stall = FAST.period.toMillis() * 6
                    val locked = CountDownLatch(1)
                    // 대기 b 가 만료를 읽은 직후, 잠그기 전에 c 가 먼저 얻는다. b 는 그 뒤 두 행을 잠근 채 멎는다.
                    late.afterPeek = { assertNotNull(c.acquire()) }
                    late.afterLock = {
                        locked.countDown()
                        Thread.sleep(stall)
                    }
                    val stalled = CompletableFuture.supplyAsync { runCatching { late.acquire() } }
                    assertTrue(locked.await(5, TimeUnit.SECONDS))
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(stall - 200)
                    var renewed = 0
                    while (System.nanoTime() < until) {
                        if (runCatching { c.renew() }.getOrDefault(false)) renewed++
                        assertTrue(c.withinDeadline(), "새 보유자가 자기 차단 기한을 잃었다")
                        Thread.sleep(100)
                    }
                    assertTrue(renewed > 0, "멎은 대기가 새 보유자의 갱신을 내내 막았다")
                    assertTrue(c.markIssuing())
                    assertEquals("mw-c" to 2L, row())
                    assertNull(stalled.get(10, TimeUnit.SECONDS).getOrNull(), "뒤늦은 대기가 리스를 얻었다")
                }
            }
        }
    }

    @Test
    fun `리스가 살아 있는 동안 대기의 획득 시도는 보유자 행을 잠그지 않는다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            HostLease("mw-b", FAST, PLAIN).use { b ->
                assertNotNull(a.acquire())
                var locked = false
                b.afterLock = { locked = true }
                repeat(5) { assertNull(b.acquire()) }
                assertFalse(locked, "살아 있는 리스의 보유자 행을 대기가 잠갔다")
                assertTrue(a.renew())
            }
        }
    }

    @Test
    fun `만료한 리스를 잠근 직후 멎은 대기의 세션은 리스 연결의 유휴 제한이 끊어 다른 인스턴스의 획득을 막지 않는다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            assertNotNull(a.acquire())
            Thread.sleep(FAST.expiry.toMillis() + 200)
            HostLease("mw-b", FAST, PLAIN).use { b ->
                HostLease("mw-c", FAST, PLAIN).use { c ->
                    val locked = CountDownLatch(1)
                    val stall = FAST.period.toMillis() * 6
                    b.afterLock = {
                        locked.countDown()
                        Thread.sleep(stall)
                    }
                    val stalled = CompletableFuture.supplyAsync { runCatching { b.acquire() } }
                    assertTrue(locked.await(5, TimeUnit.SECONDS))
                    val started = System.nanoTime()
                    var taken: Acquired? = null
                    while (taken == null) {
                        check(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(stall)) { "멎은 대기가 깨어나기 전에 획득하지 못했다" }
                        taken = c.acquire()
                        if (taken == null) Thread.sleep(50)
                    }
                    assertEquals(2L, taken.generation)
                    assertEquals("mw-c" to 2L, row())
                    assertNull(stalled.get(10, TimeUnit.SECONDS).getOrNull(), "멎은 대기가 리스를 얻었다")
                }
            }
        }
    }

    @Test
    fun `갱신 연결이 끊기면 DB 가 만료하기 전에 로컬 자기 차단으로 리스를 잃고 송신을 막는다`() {
        val broken = AtomicBoolean(false)
        val flaky = LeaseConnections { breakable(PostgresSupport.connection(), broken) }
        val liveAtExit = CompletableFuture<Boolean>()
        val leadership = HostLeadership(HostLease("mw-a", SLOW, flaky), SLOW, HostExit {
            liveAtExit.complete(PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_lease_term") { it.getBoolean(1) })
        })
        val lost = CopyOnWriteArrayList<String>()
        leadership.use {
            leadership.begin(listener(leadership, lost)) { it.run() }
            assertEquals(LeaderState.ISSUING, leadership.state)
            assertTrue(leadership.mayIssue())
            Thread.sleep(SLOW.period.toMillis() * 2)
            broken.set(true)
            assertTrue(liveAtExit.get(SLOW.expiry.toMillis() + 2000, TimeUnit.MILLISECONDS), "DB 가 먼저 만료했다")
            assertEquals(LeaderState.LOST, leadership.state)
            assertFalse(leadership.mayIssue())
            assertTrue(leadership.lostReason!!.startsWith("자기 차단 기한"), leadership.lostReason)
            assertEquals(listOf(leadership.lostReason), lost)
            assertNull(leadership.claim())
        }
    }

    @Test
    fun `가드는 리스를 쥔 세대의 쓰기만 받고 다른 인스턴스가 가져간 뒤의 쓰기는 적지 않고 잃음으로 넘긴다`() {
        val a = TestLease.acquire("mw-a")
        val lost = CopyOnWriteArrayList<String>()
        var claim: LeaseClaim? = a
        val records = HostRecords(JdbcClient.create(ds), guard(ds, { claim }, lost), ObjectMapper())
        records.journal("JO-1", "humanoid-01", """{"jobOrderId":"JO-1"}""", "InspectAsset", null, "mw-a", "exec-1")
        assertEquals(1, journals())

        TestLease.acquire("mw-b")
        val refused = assertFailsWith<AuthorityLost> {
            records.journal("JO-2", "humanoid-01", """{"jobOrderId":"JO-2"}""", "InspectAsset", null, "mw-a", "exec-2")
        }
        assertEquals(listOf(refused.message), lost)
        assertEquals(1, journals())
        // 여러 문장 트랜잭션도 같다. 앞 문장도 남지 않는다.
        assertFailsWith<AuthorityLost> { records.inTransaction { records.event("JO-1", dev.picasso.ops.host.store.JournalEventKind.DEFERRED, "mw-a", null, "x") } }
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event") { it.getInt(1) })

        claim = null
        assertFailsWith<NotAuthority> { records.event("JO-1", dev.picasso.ops.host.store.JournalEventKind.DEFERRED, "mw-a", null, "x") }
    }

    @Test
    fun `표식 없는 쓰기는 펜싱 트리거가 거부한다`() {
        TestLease.acquire("mw-a")
        listOf(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) VALUES ('JO-1', 'r', '{}', 'W', 'mw-a', 'e')",
            "UPDATE mission.result_producer SET last_sequence = last_sequence",
            "DELETE FROM mission.draft",
        ).forEach { sql ->
            val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
            assertEquals("MHF01", e.sqlState, "$sql: ${e.message}")
            assertTrue("리스 세대 표식이 없다" in e.message!!, e.message)
        }
        // 가드 밖이어야 하는 표(심장박동·리스)는 표식 없이 쓴다.
        PostgresSupport.execute("INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('mw-x', clock_timestamp() + interval '1 minute')")
        PostgresSupport.execute("UPDATE mission.host_lease_term SET expires_at = expires_at")
        PostgresSupport.execute("UPDATE mission.host_lease SET holder = holder")
    }

    @Test
    fun `V19 에서 V20 으로 올리면 지금 세대 한 줄과 발행 표시를 보유자 이력에 옮기고 그 앞 세대는 비어 있으며 다음 획득이 이어 적는다`() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        org.flywaydb.core.Flyway.configure().configuration(HostSchema.flyway(ds).configuration).target("19").load().migrate()
        // V19 아래에서 세대 3 을 쥔 보유자가 발행을 열었고 리스는 이미 만료했다.
        PostgresSupport.execute("UPDATE mission.host_lease SET holder = 'mw-old', generation = 3 WHERE role = 'host'")
        PostgresSupport.execute("UPDATE mission.host_lease_term SET issuing = true, expires_at = clock_timestamp() - interval '1 second' WHERE role = 'host'")
        assertEquals(1, HostSchema.flyway(ds).migrate().migrationsExecuted)
        fun grants() = PostgresSupport.queryAll("SELECT generation, holder, issuing_at IS NOT NULL FROM mission.host_lease_grant ORDER BY generation") {
            "${it.getLong(1)}|${it.getString(2)}|${it.getBoolean(3)}"
        }
        assertEquals(listOf("3|mw-old|true"), grants())
        // 옛 확인 행에는 빈 지목 집합·이력 완전이 채워진다(칸 기본값).
        val defaults = PostgresSupport.queryAll(
            "SELECT column_name, column_default FROM information_schema.columns WHERE table_schema = 'mission' AND table_name = 'host_takeover_confirmation' " +
                "AND column_name IN ('unresolved_holders', 'history_complete')",
        ) { it.getString(1) to it.getString(2) }.toMap()
        assertTrue(defaults.getValue("unresolved_holders").startsWith("'{}'"), defaults.toString())
        assertEquals("true", defaults.getValue("history_complete"))
        HostLease("mw-new", FAST, PLAIN).use { lease ->
            assertEquals(4L, assertNotNull(lease.acquire()).generation)
            assertTrue(lease.markIssuing())
        }
        assertEquals(listOf("3|mw-old|true", "4|mw-new|true"), grants())
    }

    @Test
    fun `펜싱 트리거는 mission 스키마의 쓰기 표 전부에 문장 수준으로 걸리고 예외 표에는 없다`() {
        val tables = PostgresSupport.queryAll("SELECT tablename FROM pg_tables WHERE schemaname = 'mission' ORDER BY tablename") { it.getString(1) }
        val fenced = PostgresSupport.queryAll(
            """
            SELECT c.relname, t.tgtype FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_proc p ON p.oid = t.tgfoid
            WHERE n.nspname = 'mission' AND p.proname = 'mission_require_fenced_generation' ORDER BY c.relname
            """.trimIndent(),
        ) { it.getString(1) to it.getInt(2) }
        val exempt = setOf("host_lease", "host_lease_term", "host_lease_grant", "host_instance", "flyway_schema_history")
        assertTrue(tables.size > 25, tables.toString())
        assertEquals(tables.filter { it !in exempt }, fenced.map { it.first }, "펜싱 트리거가 빠진 쓰기 표가 있다(새 표는 그 마이그레이션이 트리거를 건다)")
        // BEFORE(2) + INSERT(4) + DELETE(8) + UPDATE(16), 행 수준(1) 아님.
        assertEquals(setOf(30), fenced.map { it.second }.toSet())
    }

    @Test
    fun `커넥션을 다시 내줘도 트랜잭션 지역 표식이 남지 않는다`() {
        val single = SingleConnectionDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password, true)
        try {
            val a = TestLease.acquire("mw-a")
            val records = HostRecords(JdbcClient.create(single), guard(single, { a }, CopyOnWriteArrayList()), ObjectMapper())
            records.journal("JO-1", "humanoid-01", """{"jobOrderId":"JO-1"}""", "InspectAsset", null, "mw-a", "exec-1")
            val jdbc = JdbcClient.create(single)
            assertEquals("", jdbc.sql("SELECT coalesce(current_setting('mission.fenced_generation', true), '')").query(String::class.java).single())
            val e = assertFailsWith<org.springframework.dao.DataAccessException> {
                jdbc.sql("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id) VALUES ('JO-1', 'DEFERRED', 'mw-a')").update()
            }
            assertEquals("MHF01", (e.mostSpecificCause as SQLException).sqlState)
        } finally {
            single.destroy()
        }
    }

    @Test
    fun `보유자 행을 잠근 직후 멎은 쓰기 트랜잭션은 만료 뒤에도 획득을 막다가 세션 설정의 유휴 제한이 끊으면 넘겨주고 그 쓰기는 남지 않는다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            HostLease("mw-b", FAST, PLAIN).use { b ->
                assertNotNull(a.acquire())
                val idle = Duration.ofSeconds(3)
                // 실행 호스트처럼 연결을 열 때 세션 설정으로 건다(잠그기 전부터 걸림). 이 시험은 만료(1.5초)보다 길게 두어 «만료 뒤에도 막음» 을 본다.
                val sessioned = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password).apply {
                    connectionProperties = java.util.Properties().apply { setProperty("options", "-c idle_in_transaction_session_timeout=${idle.toMillis()}") }
                }
                val guard = AuthorityGuard(
                    JdbcClient.create(sessioned), TransactionTemplate(DataSourceTransactionManager(sessioned)), { LeaseClaim("mw-a", a.generation!!) }, {},
                )
                val records = HostRecords(JdbcClient.create(sessioned), guard, ObjectMapper())
                val written = CountDownLatch(1)
                // 프로세스가 보유자 행을 FOR SHARE 로 잠근 직후(표식·업무 쓰기 전) 멎는다. 그동안 갱신도 없다.
                guard.afterLock = {
                    written.countDown()
                    Thread.sleep(idle.toMillis() + 2000)
                }
                val stalled = CompletableFuture.supplyAsync {
                    runCatching {
                        records.journal("JO-1", "humanoid-01", """{"jobOrderId":"JO-1"}""", "InspectAsset", null, "mw-a", "exec-1")
                    }
                }
                assertTrue(written.await(5, TimeUnit.SECONDS))
                val started = System.nanoTime()
                var acquired: Acquired? = null
                while (acquired == null) {
                    check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15)) { "획득하지 못했다" }
                    acquired = b.acquire()
                    if (acquired == null) Thread.sleep(50)
                }
                val waited = Duration.ofNanos(System.nanoTime() - started)
                // 리스는 FAST.expiry(1.5초)에 만료했지만 세션이 끊길 때(유휴 제한 3초)까지 획득이 막혔다.
                assertTrue(waited >= idle.minusMillis(500), "유휴 제한 전에 획득했다: $waited")
                assertEquals(2L, acquired.generation)
                assertTrue(stalled.get(10, TimeUnit.SECONDS).isFailure, "멎은 트랜잭션이 커밋됐다")
                assertEquals(0, journals())
            }
        }
    }

    @Test
    fun `발행을 열지 않은 세대가 정상 종료해도 다음 획득은 정상 종료 인수가 아니고 처음 획득도 다른 인스턴스가 살아 있으면 아니다`() {
        // 리스 이전 빌드의 호스트가 도는 채(심장박동만 적음) 처음 획득하면 곧바로 발행하지 않는다.
        Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at) VALUES ('mw-old', clock_timestamp() + interval '1 hour')")
        HostLease("mw-a", FAST, PLAIN).use { a ->
            val first = assertNotNull(a.acquire())
            assertEquals(Acquired(1, null, false, false, othersLive = true), first)
            assertFalse(first.graceful)
            // a 는 관측만 하다(발행 표시 없음) 멈춤을 적고 내놓는다.
            Fenced.execute("INSERT INTO mission.host_instance (instance_id, expires_at, stopped_at) VALUES ('mw-a', clock_timestamp(), clock_timestamp())")
            a.release()
        }
        HostLease("mw-b", FAST, PLAIN).use { b ->
            val taken = assertNotNull(b.acquire())
            assertEquals(Acquired(2, "mw-a", previousStopped = true, previousIssuing = false, othersLive = true), taken)
            assertFalse(taken.graceful, "확인 안 된 인수가 정상 종료로 세탁됐다")
        }
    }

    @Test
    fun `정상 종료는 발행 상태와 DB 쓰기를 닫고 송신 직전 확인을 지난 명령이 끝나기를 기다린다`() {
        val leadership = HostLeadership(HostLease("mw-a", FAST, PLAIN), FAST, HostExit {})
        leadership.use {
            leadership.begin(listener(leadership, CopyOnWriteArrayList())) { it.run() }
            assertEquals(LeaderState.ISSUING, leadership.state)
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val sender = Thread { leadership.sending("시험 송신") { entered.countDown(); finish.await() } }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(leadership.stopping())
            assertEquals(LeaderState.STOPPING, leadership.state)
            assertFalse(leadership.mayIssue())
            assertNull(leadership.claim())
            assertFailsWith<CommandNotSent> { leadership.sending("늦은 송신") { } }
            assertFalse(leadership.awaitQuiet(200), "송신 중인 명령을 기다리지 않았다")
            finish.countDown()
            assertTrue(leadership.awaitQuiet(2000))
            sender.join(2000)
        }
    }

    @Test
    fun `쓰기 트랜잭션이 겹쳐 이어져도 갱신은 보유자 행의 공유 잠금 뒤에서 기다리지 않고 성공한다`() {
        HostLease("mw-a", FAST, PLAIN).use { a ->
            assertNotNull(a.acquire())
            val guard = guard(ds, { LeaseClaim("mw-a", a.generation!!) }, CopyOnWriteArrayList())
            val running = AtomicBoolean(true)
            val writers = Executors.newFixedThreadPool(4)
            repeat(4) { i ->
                writers.submit {
                    Thread.sleep(i * 15L)
                    // 쓰기 트랜잭션마다 보유자 행을 FOR SHARE 로 쥐고 60 ms 머문다. 넷이 엇갈려 늘 하나 이상이 쥐고 있다.
                    while (running.get()) guard.withAuthorityTransaction { Thread.sleep(60) }
                }
            }
            try {
                Thread.sleep(200)
                repeat(10) {
                    val started = System.nanoTime()
                    assertTrue(a.renew(), "갱신이 거부됐다")
                    val took = Duration.ofNanos(System.nanoTime() - started)
                    assertTrue(took < FAST.period, "갱신이 쓰기 트랜잭션 뒤에서 기다렸다: $took")
                    Thread.sleep(50)
                }
            } finally {
                running.set(false)
                writers.shutdown()
                writers.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `송신 직전 확인은 발행하는 리더가 아니면 기체 출발과 취소, 플릿 맡기기와 취소를 보내지 않고 읽기는 그대로 둔다`() {
        val issuing = AtomicBoolean(false)
        val authority = object : SendAuthority {
            override fun mayIssue(): Boolean = issuing.get()
        }
        val sent = CopyOnWriteArrayList<String>()
        val robot = GatedRobotPort(object : RobotPort {
            override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
                sent += "start $taskId"
                return StartTaskResponse.getDefaultInstance()
            }
            override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = emptyList<WatchTaskResponse>().also { sent += "watch" }
            override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance().also { sent += "cancel" }
            override fun snapshot(robotId: String): RobotSnapshot? = null.also { sent += "snapshot" }
            override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
        }, authority)
        val fleet = GatedFleetPort(object : AmrFleetPort {
            override fun dispatch(order: TransportOrder): TransportHandle? = null.also { sent += "dispatch" }
            override fun find(reference: String): TransportHandle? = null.also { sent += "find" }
            override fun status(handle: TransportHandle): TransportStatus = AmrFleetPort.None.status(handle).also { sent += "status" }
            override fun cancel(handle: TransportHandle): Boolean = false.also { sent += "fleet-cancel" }
        }, authority)
        val handle = TaskHandle.newBuilder().setTaskId("JO-1#T1").setRobotId("r").build()
        val order = TransportOrder("JO-1#F1", "c", "a", "b")
        val refusals = listOf<() -> Any?>(
            { robot.start("r", "JO-1#T1", 1, "inspect", emptyMap()) }, { robot.cancel("r", handle) },
            { fleet.dispatch(order) }, { fleet.cancel(TransportHandle("h")) },
        ).map { call -> assertFailsWith<CommandNotSent> { call() }.message!! }
        assertTrue(refusals.all { it.startsWith(SendAuthority.NOT_ISSUING) }, refusals.toString())
        robot.watch("r", handle)
        robot.snapshot("r")
        fleet.find("JO-1#F1")
        fleet.status(TransportHandle("h"))
        assertEquals(listOf("watch", "snapshot", "find", "status"), sent)

        issuing.set(true)
        sent.clear()
        robot.start("r", "JO-1#T1", 1, "inspect", emptyMap())
        robot.cancel("r", handle)
        fleet.dispatch(order)
        fleet.cancel(TransportHandle("h"))
        assertEquals(listOf("start JO-1#T1", "cancel", "dispatch", "fleet-cancel"), sent)
    }

    private fun row(): Pair<String?, Long> = PostgresSupport.queryOne("SELECT holder, generation FROM mission.host_lease") { it.getString(1) to it.getLong(2) }

    private fun journals(): Int = PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal") { it.getInt(1) }

    private fun guard(source: DataSource, claim: () -> LeaseClaim?, lost: MutableList<String>) = AuthorityGuard(
        JdbcClient.create(source), TransactionTemplate(DataSourceTransactionManager(source)), claim, { lost += it },
    )

    private fun listener(leadership: HostLeadership, lost: MutableList<String>) = object : LeadershipListener {
        override fun acquired(acquired: Acquired) = leadership.reconciled()
        override fun lost(reason: String) {
            lost += reason
        }
    }

    private companion object {
        /** 주기 0.3초·만료 1.5초·여유 0.3초·유휴 트랜잭션 제한 1초. */
        val FAST = LeaseTiming(Duration.ofMillis(300), Duration.ofMillis(1500), Duration.ofMillis(300), Duration.ofSeconds(1))

        /** 주기 0.3초·만료 3초·여유 1초. 자기 차단(마지막 갱신 송신 + 2초)과 DB 만료(그 갱신 도착 + 3초) 사이가 1초 남짓이다. */
        val SLOW = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))

        val PLAIN = LeaseConnections { PostgresSupport.connection() }

        /** 첫 커밋은 서버에서 되지만 답이 오지 않은 것처럼 실패하는 연결(망 유실). 획득 트랜잭션의 커밋이 그것이다. */
        fun answerLost(real: Connection): Connection {
            var committed = false
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = invoke(real, method, args)
                if (method.name == "commit" && !committed) {
                    committed = true
                    throw SQLException("응답을 받지 못했다", "08006")
                }
                result
            } as Connection
        }

        /** [broken] 이 서면 문장 준비부터 실패하는 연결(망 단절). 새 연결도 그때부터 열리지 않는다. */
        fun breakable(real: Connection, broken: AtomicBoolean): Connection {
            if (broken.get()) {
                real.close()
                throw SQLException("연결이 끊겼다", "08001")
            }
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (broken.get() && method.name.startsWith("prepare")) throw SQLException("연결이 끊겼다", "08006")
                invoke(real, method, args)
            } as Connection
        }

        private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
