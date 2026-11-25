package dev.picasso.ops.host

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.RestartReleaseOutcome
import dev.picasso.ops.host.store.RestartSuspicionWrite
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 수신자 재시작 의심 보류의 저장소(V22)를 호스트 없이 본다 — 해제 범위(운영자 해제만 덮음, 시스템 해제는 덮지 않음), 시스템 해제된 의심의 재대조 목록,
 * 열린 의심의 중복 없는 쓰기.
 */
class RestartHoldRecordsTest {

    private val ds = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

    @BeforeTest
    fun fresh() {
        Fenced.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        HostSchema.flyway(ds).migrate()
    }

    @Test
    fun `해제 범위는 운영자 해제만 덮고 시스템 해제는 덮지 않으며 시스템 해제된 의심은 그 뒤 송신 의도·운영자 해제·새 의심이 없을 때만 다시 대조한다`() {
        val claim = TestLease.acquire()
        val guard = dev.picasso.ops.host.store.AuthorityGuard(
            JdbcClient.create(ds), TransactionTemplate(DataSourceTransactionManager(ds)), { claim }, { reason -> error(reason) },
        )
        val records = HostRecords(JdbcClient.create(ds), guard, ObjectMapper())
        fun write(task: String) = RestartSuspicionWrite("quadruped-01", "JO-1", null, task.substringAfter('#'), 0, task, 1, "ABSENT", 0, null, null, null)
        val a = records.recordRestartSuspicion(claim.generation, claim.holder, write("JO-1#A"))
        val b = records.recordRestartSuspicion(claim.generation, claim.holder, write("JO-1#B"))
        // 같은 근거의 열린 의심은 한 줄이다.
        assertEquals(a, records.recordRestartSuspicion(claim.generation, claim.holder, write("JO-1#A")))

        assertIs<RestartReleaseOutcome.Released>(
            records.releaseRestartSuspicion(a, "quadruped-01", "JO-1#A", claim.generation, claim.holder, "system:attached", "붙음"),
        )
        assertIs<RestartReleaseOutcome.Released>(
            records.releaseRestartSuspicion(b, "quadruped-01", "JO-1#B", claim.generation, claim.holder, "kim", "현장 확인"),
        )
        // 시스템 해제만 있는 태스크는 해제 범위에 없고, 운영자 해제는 그때의 의도 순번(0)이 나온다.
        assertEquals(mapOf("JO-1#B" to 0L), records.restartReleaseCoverage("JO-1"))
        // 시스템 해제된 A 는 다시 대조할 목록에 오른다(운영자 해제된 B 는 아님).
        assertEquals(listOf("JO-1#A"), records.systemReleasedSuspicions().map { it.taskId })
        // 해제된 줄만 있으면 같은 근거라도 새 줄이다(시스템 해제 뒤 다시 사라짐).
        val again = records.recordRestartSuspicion(claim.generation, claim.holder, write("JO-1#A"))
        assertNotEquals(a, again)
        // 더 뒤 의심이 생기면 앞의 시스템 해제는 다시 대조할 목록에서 빠진다.
        assertTrue(records.systemReleasedSuspicions().isEmpty())
        // 시스템 해제 뒤 그 태스크의 송신 의도가 생기면(그 출발을 냄) 빠진다.
        assertIs<RestartReleaseOutcome.Released>(
            records.releaseRestartSuspicion(again, "quadruped-01", "JO-1#A", claim.generation, claim.holder, "system:attached", "붙음"),
        )
        assertEquals(listOf("JO-1#A"), records.systemReleasedSuspicions().map { it.taskId })
        records.recordIssueIntent(claim.generation, claim.holder, "quadruped-01", "JO-1", "exec-1", "A", 0, "JO-1#A", 1, null)
        assertTrue(records.systemReleasedSuspicions().isEmpty())
    }
}
