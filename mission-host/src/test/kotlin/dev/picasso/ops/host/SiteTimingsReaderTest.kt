package dev.picasso.ops.host

import dev.picasso.ops.host.timings.SiteTimingsReader
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 현장 시간값 읽기 주기 자체(S3c 스펙 §7.1). 호스트를 띄우지 않고 읽기 주기만 세운다. 뷰는 [SiteTimingsView] 상수의 대역이다. */
class SiteTimingsReaderTest {

    private val jdbc = JdbcClient.create(DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password))

    @BeforeTest
    fun standInView() {
        Fenced.execute("DROP SCHEMA IF EXISTS ops CASCADE")
        Fenced.execute("CREATE SCHEMA ops")
        val values = SiteTimingsView.COLUMNS.zip(HostBench.STANDARD_TIMINGS).joinToString(", ") { (column, value) -> "$value::${column.type}" }
        Fenced.execute(
            "CREATE VIEW ${SiteTimingsView.NAME} (${SiteTimingsView.COLUMNS.joinToString(", ") { it.name }}) AS VALUES ($values)",
        )
    }

    @Test
    fun `읽기 중 Exception 이 아닌 Error 가 나도 실패로 남기고 주기 읽기를 이어 간다`() {
        // 처음 두 번은 시계가 Error 를 던진다. 기동 안의 첫 읽기가 실패한다(실패 시각을 다시 물을 때도 던진다).
        val calls = AtomicInteger()
        val clock = HostClock {
            if (calls.incrementAndGet() <= 2) throw LinkageError("시험이 던진 Error")
            Instant.parse("2026-10-09T00:00:00Z")
        }
        SiteTimingsReader(jdbc, clock).start(Duration.ofMillis(50)).use { reader ->
            val failed = reader.state()
            assertNull(failed.applied)
            assertTrue("LinkageError" in failed.readError!!, failed.readError)
            val deadline = Instant.now().plusSeconds(5)
            while (reader.state().applied == null) {
                check(Instant.now().isBefore(deadline)) { "5초 안에 주기 읽기가 이어지지 않았다: ${reader.state()}" }
                Thread.sleep(20)
            }
            assertEquals(1L, reader.state().applied!!.siteSettingsVersion)
            assertNull(reader.state().readError)
        }
    }

    @Test
    fun `읽기 주기는 1ms 이상이어야 하고 1ms 미만은 읽기 전에 거부한다`() {
        val clock = HostClock { Instant.parse("2026-10-09T00:00:00Z") }
        listOf(Duration.ZERO, Duration.ofNanos(500_000), Duration.ofSeconds(-1)).forEach { period ->
            val reader = SiteTimingsReader(jdbc, clock)
            val e = assertFailsWith<IllegalArgumentException> { reader.start(period) }
            assertTrue("1ms 이상" in e.message!!, e.message)
            // 거부는 첫 읽기 전이다.
            assertNull(reader.state().lastReadAt)
            reader.close()
        }
        SiteTimingsReader(jdbc, clock).start(Duration.ofMillis(1)).use { assertEquals(1L, it.state().applied!!.siteSettingsVersion) }
    }
}
