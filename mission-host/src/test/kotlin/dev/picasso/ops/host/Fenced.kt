package dev.picasso.ops.host

import dev.picasso.ops.host.store.AuthorityGuard
import dev.picasso.ops.host.store.LeaseClaim
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/**
 * 시험이 `mission` 표에 SQL 로 직접 쓰는 길(V19 의 쓰기 펜싱). 연결에 세션 표식(`mission.fenced_generation`)을 세워 두므로 그 연결의 쓰기는
 * 펜싱 트리거를 지난다. 운영 경로는 이 표식을 트랜잭션 지역으로만 세운다([AuthorityGuard]) — 세션 표식은 시험 연결에만 있다. 표식 없는 쓰기가
 * 거부되는 것을 보는 시험은 `PostgresSupport` 를 그대로 쓴다.
 */
object Fenced {
    fun connection(): Connection = PostgresSupport.connection().also { c ->
        c.createStatement().use { it.execute("SET mission.fenced_generation = 'test'") }
    }

    fun execute(sql: String) = connection().use { c -> c.createStatement().use { it.execute(sql) } }
}

/**
 * 호스트 없이 기록 저장소를 만드는 시험의 리스 가드. 처음 쓸 때 리스 행을 시험 보유자로 한 시간 쥐고([acquire]) 실제 가드 경로(`FOR SHARE`·확인·
 * 트랜잭션 지역 표식)를 지난다. 같은 시험 안의 저장소들은 가드 하나를 나눠 쓴다(새로 쥐면 앞 세대가 낡는다).
 */
object TestLease {
    fun guard(ds: DataSource): AuthorityGuard {
        val claim = lazy { acquire() }
        return AuthorityGuard(
            JdbcClient.create(ds), TransactionTemplate(DataSourceTransactionManager(ds)), { claim.value }, { reason -> error(reason) },
        )
    }

    fun acquire(holder: String = "mw-test-${UUID.randomUUID()}"): LeaseClaim = PostgresSupport.queryOne(
        "WITH t AS (UPDATE mission.host_lease_term SET expires_at = clock_timestamp() + interval '1 hour', issuing = true WHERE role = 'host' RETURNING 1) " +
            "UPDATE mission.host_lease SET holder = '$holder', generation = generation + 1 WHERE role = 'host' AND EXISTS (SELECT 1 FROM t) RETURNING generation",
    ) { LeaseClaim(holder, it.getLong(1)) }
}
