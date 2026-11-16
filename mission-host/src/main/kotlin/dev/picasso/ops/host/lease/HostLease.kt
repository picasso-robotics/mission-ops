package dev.picasso.ops.host.lease

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Properties

/** 리스 한 줄(`mission.host_lease` + `host_lease_term`). [live] 는 DB 시각으로 아직 만료하지 않았는가, [issuing] 은 그 세대가 발행을 열었는가다. */
data class LeaseRow(val holder: String?, val generation: Long, val expiresAt: Instant, val live: Boolean, val issuing: Boolean = false)

/**
 * 획득 한 번의 결과. [previousHolder] 는 이 획득 직전의 보유자(처음이면 `null`), [previousStopped] 는 그 보유자가 획득 전에 멈춤
 * (`host_instance.stopped_at`, 송신을 다 멈춘 뒤에만 적는 정상 종료)을 적었는가, [previousIssuing] 은 앞 세대가 발행을 열었는가(ISSUING 에
 * 들었는가 — 만료 인수였다면 운영자 확인 뒤에만 열린다), [othersLive] 는 이 인스턴스 밖에 심장박동이 살아 있는 인스턴스가 있는가다.
 * [recovered] 는 획득 문장의 결과를 못 받아 행 조회로 내 것임을 확인한 획득이다 — 앞 보유자·멈춤·발행 표시·다른 산 인스턴스의 증거를 잃었으므로
 * 세대 1 이라도 정상 종료 인수로 읽지 않는다.
 */
data class Acquired(
    val generation: Long,
    val previousHolder: String?,
    val previousStopped: Boolean,
    val previousIssuing: Boolean = false,
    val othersLive: Boolean = false,
    val recovered: Boolean = false,
) {
    /**
     * 곧바로 발행해도 되는 인수인가. 아니면 만료 인수로 보아 관측만 하고 운영자 확인을 기다린다.
     * - 앞 보유자가 있으면: 그 보유자가 멈춤을 적었고 **그 세대가 발행을 열었을 때만**. 관측(HOLDING) 중에 정상 종료한 보유자에게서 넘겨받으면
     *   앞의 «확인 안 된 인수» 를 그대로 물려받는다(운영자 확인을 재기동으로 건너뛰지 못한다).
     * - 처음 획득(세대 0 → 1)이면: 다른 인스턴스의 심장박동이 살아 있지 않을 때만(리스 이전 빌드의 호스트가 아직 도는 채 이 버전을 띄운 경우를 막는다).
     * - 조회로 복구한 획득([recovered])은 언제나 아니다.
     */
    val graceful: Boolean
        get() = !recovered && if (previousHolder == null) generation == 1L && !othersLive else previousStopped && previousIssuing
}

/** 리스 전용 연결을 여는 쪽. 공용 커넥션 풀과 분리한다(HA 설계 기록 §4.4). 시험이 연결 장애를 넣는 자리다. */
fun interface LeaseConnections {
    fun open(): Connection

    companion object {
        /**
         * JDBC 주소로 직접 연다(풀 없음). 소켓 읽기 제한(주기의 세 배, 초 단위 올림)을 문장 제한(주기의 두 배)보다 길게 걸어, 서버가 문장을
         * 끝내기 전에 소켓이 먼저 끊기지 않게 한다. 끊긴 망에서는 그 안에 연결을 버리고 다음 주기에 새로 연다.
         */
        fun direct(url: String, user: String, password: String, timing: LeaseTiming): LeaseConnections = LeaseConnections {
            val props = Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("ApplicationName", "mission-host-lease")
                setProperty("connectTimeout", socketSeconds(timing).toString())
                setProperty("socketTimeout", socketSeconds(timing).toString())
                setProperty("tcpKeepAlive", "true")
            }
            DriverManager.getConnection(url, props)
        }

        private fun socketSeconds(timing: LeaseTiming): Long = maxOf(1L, (timing.period.toMillis() * 3 + 999) / 1000)
    }
}

/**
 * 실행 호스트 리스의 획득·갱신·발행 표시·내놓기(HA 설계 기록 §4.4). 이 인스턴스([instanceId])의 것이고 리스 전용 연결 하나를 쓴다. 호출은
 * 리더 상태기계([HostLeadership])에서만 오고 메서드마다 동기화한다.
 *
 * - **획득**: 먼저 잠금 없이 기간 행의 만료를 읽고, 만료했을 때만 보유자 행 → 기간 행 순서로 `FOR UPDATE`(쓰기 트랜잭션의 `FOR SHARE` 가 끝나기를
 *   기다림) 한 뒤 잠근 상태에서 만료를 다시 보고 보유자·세대(+1)를 바꾸고 새 기간을 둔다 — 리스가 살아 있는 동안 대기는 보유자 행을 잠그지 않아,
 *   대기가 멎어도 능동의 쓰기·갱신을 막지 않는다. 같은 보유자의 재획득 조건(`OR holder = :me`)은 없다. 확정되지 않은 실패(망 오류)면 다시 고치지
 *   않고 새 연결로 행을 읽어 내가 보유자이고 살아 있으면 그 세대로 획득한 것으로 본다([Acquired.recovered], 늘 만료 인수). 그 읽기마저 실패하면 다음
 *   획득 시도가 먼저 읽는다. 복구한 획득의 자기 차단 기한은 **처음 획득 문장을 보내기 직전** 시각에서 잰다(조회는 DB 만료를 늘리지 않으므로) —
 *   그 기한이 이미 지났으면 복구하지 않고 리스가 만료하게 둔다.
 * - **갱신**: 한 문장(자동 커밋)으로, 보유자 행을 하위 질의에서 `FOR SHARE` 로 잠가 보유자·세대를 확인하고(먼저) 기간 행의 만료를 민다(아직
 *   만료하지 않았을 때만, 그 다음). 0행이면 잃은 것이다 — 만료 뒤 늦게 닿은 갱신이 권위를 되살리지 않는다. 클라이언트가 잠금을 쥔 채 멎을 문장 사이
 *   틈이 없다. 쓰기 펜싱도 보유자 행만 `FOR SHARE` 로 잠그므로 갱신과 쓰기 트랜잭션은 서로 막지 않는다. 발행 표시·내놓기도 같은 모양의 한 문장이다.
 * - **자기 차단 기한**: 획득·갱신 요청을 보내기 직전의 단조 시각(`System.nanoTime`) + 만료 − 여유. DB 의 만료는 그 요청을 받은 뒤의 DB 시각 +
 *   만료이므로 로컬 기한이 늘 먼저 온다. 전제: 단조 시계는 프로세스가 멎은 동안(GC 정지·SIGSTOP)에도 흐른다. OS 절전·VM 일시정지에서 단조
 *   시계가 멈추는 플랫폼에서는 이 전제가 깨져, 다음 갱신이 0행으로 돌아올 때(최대 한 주기)까지 송신 직전 확인이 통과할 수 있다.
 *
 * 연결에는 잠금 대기 제한(`lock_timeout` = 주기), 문장 제한(주기의 두 배), 유휴 트랜잭션 제한(주기)을 건다 — 획득 트랜잭션 도중 멎은 세션은
 * DB 가 끊어 능동의 쓰기를 오래 막지 않는다.
 */
class HostLease(
    val instanceId: String,
    private val timing: LeaseTiming,
    private val connections: LeaseConnections,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {

    private var connection: Connection? = null

    /** 앞 획득이 확정되지 않았고 조회도 못 했다면 그 획득 문장을 보내기 직전의 단조 시각. 다음 [acquire] 가 고치기 전에 먼저 읽는다. */
    private var unverifiedSentAt: Long? = null

    /** 시험이 만료 읽기와 잠금 사이에 다른 인스턴스의 획득을 끼워 넣는 갈고리. */
    @Volatile
    internal var afterPeek: (() -> Unit)? = null

    /** 시험이 획득이 두 행을 잠근 직후 멎은 대기를 흉내 내는 갈고리. */
    @Volatile
    internal var afterLock: (() -> Unit)? = null

    /** 시험이 발행 표시 직전의 장애를 넣는 갈고리. */
    @Volatile
    internal var beforeMark: (() -> Unit)? = null

    /** 지금 쥔 세대. 쥔 적이 없거나 잃었으면 `null` 이다. */
    @Volatile
    var generation: Long? = null
        private set

    /** 자기 차단 기한(단조 시각, 나노초). [generation] 이 있을 때만 뜻이 있다. */
    @Volatile
    private var deadline: Long = 0

    /** 지금 쥔 세대가 있고 자기 차단 기한 전인가. 송신 직전 확인과 감시가 본다. */
    fun withinDeadline(): Boolean = generation != null && nanoTime() - deadline < 0

    /** 만료한 리스를 가져온다. 못 가져오면(살아 있는 보유자, 잠금 대기 초과) `null`. */
    @Synchronized
    fun acquire(): Acquired? {
        check(generation == null) { "이미 리스를 쥐었다: 세대 $generation" }
        unverifiedSentAt?.let { original ->
            val row = read()
            unverifiedSentAt = null
            if (row.holder == instanceId && row.live) return recover(row, original)
        }
        // 살아 있는 리스는 잠그지 않는다(대기가 멎어도 능동을 막지 않게).
        if (!expiredNow()) return null
        afterPeek?.invoke()
        val sentAt = nanoTime()
        val acquired = try {
            transaction { c -> take(c) }
        } catch (e: SQLException) {
            drop()
            if (e.sqlState == LOCK_NOT_AVAILABLE) return null
            // 확정되지 않았다. 다시 고치지 않는다(세대가 또 오른다). 새 연결로 읽어 내가 쥐었는지 본다.
            log.warn("리스 획득의 결과를 모르니 행을 읽어 확인한다: {}", e.toString())
            val row = try {
                read()
            } catch (again: SQLException) {
                unverifiedSentAt = sentAt
                log.warn("리스 행도 읽지 못했다(다음 시도가 먼저 읽는다): {}", again.toString())
                return null
            }
            return if (row.holder == instanceId && row.live) recover(row, sentAt) else null
        }
        return acquired?.let { adopt(it, sentAt) }
    }

    /**
     * 조회로 내 것임을 확인한 획득. 기한은 처음 획득 문장의 송신 직전 시각([sentAt])에서 잰다. 그 기한이 이미 지났으면 복구하지 않는다(리스는
     * DB 에서 만료하고 다음 획득이 세대를 올린다).
     */
    private fun recover(row: LeaseRow, sentAt: Long): Acquired? {
        val until = sentAt + timing.expiry.minus(timing.margin).toNanos()
        if (nanoTime() - until >= 0) {
            log.warn("조회로 확인한 리스(세대 {})의 자기 차단 기한이 이미 지나 복구하지 않는다", row.generation)
            return null
        }
        return adopt(Acquired(row.generation, null, false, recovered = true), sentAt)
    }

    private fun adopt(acquired: Acquired, sentAt: Long): Acquired {
        deadline = sentAt + timing.expiry.minus(timing.margin).toNanos()
        generation = acquired.generation
        return acquired
    }

    private fun expiredNow(): Boolean = try {
        autocommit { c -> c.prepareStatement(PEEK).use { s -> s.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } } }
    } catch (e: SQLException) {
        drop()
        throw e
    }

    private fun take(c: Connection): Acquired? {
        val (previous, generationBefore) = c.prepareStatement(LOCK_HOLDER).use { s ->
            s.executeQuery().use { rs -> check(rs.next()) { "리스 행이 없다" }; rs.getString(1) to rs.getLong(2) }
        }
        val (expired, previousIssuing) = c.prepareStatement(LOCK_TERM).use { s ->
            s.executeQuery().use { rs -> check(rs.next()) { "리스 기간 행이 없다" }; rs.getBoolean(1) to rs.getBoolean(2) }
        }
        afterLock?.invoke()
        if (!expired) return null
        val (previousStopped, othersLive) = c.prepareStatement(EVIDENCE).use { s ->
            s.setString(1, previous)
            s.setString(2, instanceId)
            s.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) to rs.getBoolean(2) }
        }
        val next = c.prepareStatement(TAKE_HOLDER).use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        check(next == generationBefore + 1)
        c.prepareStatement(TAKE_TERM).use { s ->
            s.setDouble(1, timing.expiry.toMillis() / 1000.0)
            s.executeUpdate()
        }
        return Acquired(next, previous, previousStopped, previousIssuing, othersLive)
    }

    /**
     * 만료를 민다. 참이면 늘렸고 자기 차단 기한도 밀렸다. 거짓이면 잃었다(보유자·세대가 다르거나 이미 만료) — [generation] 을 지운다.
     * 연결 장애는 예외로 나가고 기한은 그대로다(부르는 쪽이 기한을 본다).
     */
    @Synchronized
    fun renew(): Boolean {
        val held = generation ?: return false
        val sentAt = nanoTime()
        val updated = single(RENEW, held) { s -> s.setDouble(1, timing.expiry.toMillis() / 1000.0) }
        if (updated == 1) {
            deadline = sentAt + timing.expiry.minus(timing.margin).toNanos()
            return true
        }
        generation = null
        return false
    }

    /**
     * 이 세대가 발행을 연다는 표시(기간 행 `issuing`). [HostLeadership] 이 [LeaderState.ISSUING] 에 들기 **전에** 적는다 — 다음 보유자는 이것이
     * 있을 때만 앞 보유자의 정상 종료를 곧바로 발행할 근거로 쓴다. 거짓이면 이 세대가 아니다(잃었다).
     */
    @Synchronized
    fun markIssuing(): Boolean {
        val held = generation ?: return false
        beforeMark?.invoke()
        return single(MARK_ISSUING, held) { } == 1
    }

    /** 쥔 리스를 곧바로 만료시킨다(정상 종료). 실패는 삼킨다 — 그러면 대기 인스턴스는 만료까지 기다린다. */
    @Synchronized
    fun release() {
        val held = generation ?: return
        generation = null
        try {
            single(RELEASE, held) { }
        } catch (e: SQLException) {
            log.warn("리스를 내놓지 못했다(만료까지 남는다): {}", e.toString())
        }
    }

    /** 잃은 것으로 둔다. 연결은 그대로다. */
    fun forget() {
        generation = null
    }

    /** 리스 행을 읽는다(전용 연결). */
    @Synchronized
    fun read(): LeaseRow = try {
        autocommit { c ->
            c.prepareStatement(READ).use { s ->
                s.executeQuery().use { rs ->
                    check(rs.next()) { "리스 행이 없다" }
                    LeaseRow(rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime::class.java).toInstant(), rs.getBoolean(4), rs.getBoolean(5))
                }
            }
        }
    } catch (e: SQLException) {
        drop()
        throw e
    }

    @Synchronized
    override fun close() = drop()

    /**
     * 보유자·세대 조건을 단 한 문장(자동 커밋)을 돌려 갱신 수를 돌려준다. 문장 끝의 두 자리표시자가 보유자([instanceId])·세대([held])이고 앞의 것은
     * [bind] 가 채운다. 연결 장애는 연결을 버리고 예외로 나간다.
     */
    private fun single(sql: String, held: Long, bind: (java.sql.PreparedStatement) -> Unit): Int = try {
        autocommit { c ->
            c.prepareStatement(sql).use { s ->
                bind(s)
                val count = sql.count { it == '?' }
                s.setString(count - 1, instanceId)
                s.setLong(count, held)
                s.executeUpdate()
            }
        }
    } catch (e: SQLException) {
        drop()
        throw e
    }

    private fun <T> autocommit(action: (Connection) -> T): T = action(connection())

    private fun <T> transaction(action: (Connection) -> T): T {
        val c = connection()
        c.autoCommit = false
        try {
            val result = action(c)
            c.commit()
            c.autoCommit = true
            return result
        } catch (e: SQLException) {
            drop()
            throw e
        } catch (e: RuntimeException) {
            runCatching { c.rollback(); c.autoCommit = true }
            throw e
        }
    }

    private fun connection(): Connection = connection ?: connections.open().also { c ->
        c.autoCommit = true
        c.createStatement().use { it.execute("SET lock_timeout = '${timing.period.toMillis()}ms'") }
        c.createStatement().use { it.execute("SET statement_timeout = '${timing.period.toMillis() * 2}ms'") }
        c.createStatement().use { it.execute("SET idle_in_transaction_session_timeout = '${timing.period.toMillis()}ms'") }
        connection = c
    }

    private fun drop() {
        val c = connection ?: return
        connection = null
        try {
            c.close()
        } catch (_: SQLException) {
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HostLease::class.java)

        /** Postgres `lock_not_available`(잠금 대기 제한 초과). 확정된 실패라 조회하지 않는다. */
        private const val LOCK_NOT_AVAILABLE = "55P03"

        /** 잠금 없는 만료 읽기. 만료했을 때만 획득 트랜잭션에 들어간다. */
        private const val PEEK = "SELECT expires_at < clock_timestamp() FROM mission.host_lease_term WHERE role = 'host'"

        private const val LOCK_HOLDER = "SELECT holder, generation FROM mission.host_lease WHERE role = 'host' FOR UPDATE"
        private const val LOCK_TERM = "SELECT expires_at < clock_timestamp(), issuing FROM mission.host_lease_term WHERE role = 'host' FOR UPDATE"

        /** 앞 보유자의 멈춤, 이 인스턴스 밖의 살아 있는 심장박동. 잠금을 다 쥔 뒤의 문장이라 그때까지 커밋된 것을 본다. */
        private val EVIDENCE = """
            SELECT coalesce((SELECT i.stopped_at IS NOT NULL FROM mission.host_instance i WHERE i.instance_id = ?), false),
                   EXISTS (SELECT 1 FROM mission.host_instance i
                           WHERE i.instance_id <> ? AND i.stopped_at IS NULL AND i.fenced_at IS NULL AND i.expires_at > clock_timestamp())
        """.trimIndent()

        private const val TAKE_HOLDER = "UPDATE mission.host_lease SET holder = ?, generation = generation + 1 WHERE role = 'host' RETURNING generation"
        private const val TAKE_TERM =
            "UPDATE mission.host_lease_term SET expires_at = clock_timestamp() + make_interval(secs => ?), issuing = false WHERE role = 'host'"

        /**
         * 보유자·세대 조건. `UPDATE` 는 대상 행(기간 행)을 잠그기 전에 조건을 평가하므로, 하위 질의의 `FOR SHARE` 가 보유자 행을 먼저 잠그고 그 다음에
         * 기간 행을 고친다 — 획득(보유자 행 → 기간 행 `FOR UPDATE`)과 같은 순서라 교착이 없다. 획득이 보유자 행을 쥐고 있으면 기다렸다가 바뀐 보유자·
         * 세대를 보고 0행이 된다.
         */
        private const val MINE =
            "AND EXISTS (SELECT 1 FROM mission.host_lease l WHERE l.role = 'host' AND l.holder = ? AND l.generation = ? FOR SHARE)"

        private const val RENEW =
            "UPDATE mission.host_lease_term SET expires_at = clock_timestamp() + make_interval(secs => ?) WHERE role = 'host' AND expires_at > clock_timestamp() $MINE"
        private const val MARK_ISSUING = "UPDATE mission.host_lease_term SET issuing = true WHERE role = 'host' AND expires_at > clock_timestamp() $MINE"
        private const val RELEASE =
            "UPDATE mission.host_lease_term SET expires_at = clock_timestamp() WHERE role = 'host' AND expires_at > clock_timestamp() $MINE"

        private const val READ = """
            SELECT l.holder, l.generation, t.expires_at, t.expires_at > clock_timestamp(), t.issuing
            FROM mission.host_lease l JOIN mission.host_lease_term t USING (role) WHERE l.role = 'host'
        """
    }
}
