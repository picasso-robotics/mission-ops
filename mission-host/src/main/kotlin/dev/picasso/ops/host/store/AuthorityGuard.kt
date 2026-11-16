package dev.picasso.ops.host.store

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** 쓰기 가드가 확인할 (보유자, 세대). 리스를 쥔 리더만 낸다. */
data class LeaseClaim(val holder: String, val generation: Long)

/** 리스를 쥐지 않은 인스턴스(대기·잃음)의 쓰기. 아무것도 적지 않았다. */
class NotAuthority(message: String) : RuntimeException(message)

/** 쓰기 직전에 DB 의 리스 행이 이 인스턴스의 (보유자, 세대)가 아니거나 만료했다. 아무것도 적지 않았고 리더는 [AuthorityGuard] 가 잃음으로 넘긴다. */
class AuthorityLost(message: String) : RuntimeException(message)

/**
 * DB 쓰기 펜싱(HA 설계 기록 §6 «DB 상태 전이»). `HostRecords`·`MissionStore` 의 쓰기는 모두 이 가드를 지난다 — 두 저장소는 쓰기 문장을
 * [writes] 로 내고 여러 문장 트랜잭션은 [withAuthorityTransaction] 으로 묶는다.
 *
 * [withAuthorityTransaction] 은 트랜잭션을 열고(있으면 그 안에서) 리스 보유자 행(`host_lease`)을 `FOR SHARE` 로 잠근 뒤 보유자·세대와 기간 행
 * (`host_lease_term`)의 미만료(`clock_timestamp()`)를 확인한다. 맞지 않으면 [AuthorityLost] 를 던지고 [onLost] 로 리더를 잃음으로 넘긴다. 맞으면
 * 트랜잭션 지역 표식(`mission.fenced_generation`)을 세우고 업무 쓰기를 한다. 쓰기 표의 문장 수준 트리거(V19)는 표식이 없으면 거부한다 — 가드를
 * 지나지 않은 쓰기는 DB 가 막는다. 이미 가드 안이면(같은 스레드·같은 트랜잭션) 다시 잠그지 않는다.
 *
 * 직렬화 논증: 보유자·세대를 바꾸는 것은 획득뿐이고, 획득은 보유자 행을 `FOR UPDATE` 로 잠근다. 그래서 쓰기 트랜잭션이 보유자 행을 확인한 뒤 커밋할
 * 때까지 다른 인스턴스는 리스를 가져가지 못하고, 가져간 뒤에 연 쓰기 트랜잭션은 옛 세대 확인에서 실패한다. 만료는 확인하는 순간의 값을 읽기만 한다
 * — 확인 뒤 트랜잭션 도중 만료해도 획득은 이 트랜잭션이 끝나기를 기다리므로 순서는 «옛 세대의 쓰기 → 새 세대의 획득» 하나다(합의 «커밋 직전 재검사
 * 불필요» 와 같음). 갱신·발행 표시·내놓기는 기간 행만 고치고 보유자 행은 `FOR SHARE` 로만 보므로, 쓰기 트랜잭션이 겹쳐 이어져도 갱신이 굶지 않는다.
 *
 * 보유자 행을 쥔 채 멎은 세션은 커넥션의 세션 설정(`idle_in_transaction_session_timeout`·`statement_timeout`, 실행 호스트는 풀 연결을 열 때
 * 세운다)이 끊는다 — 잠그기 전부터 걸려 있어야 잠근 직후 멎은 세션도 끊긴다.
 *
 * @param claims 지금의 (보유자, 세대). 리스를 쥐지 않았으면 `null` 이고 그때 쓰기는 [NotAuthority] 다.
 */
class AuthorityGuard(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val claims: () -> LeaseClaim?,
    private val onLost: (String) -> Unit,
) {
    /** 시험이 보유자 행을 잠근 직후 멎은 프로세스를 흉내 내는 갈고리. */
    @Volatile
    internal var afterLock: (() -> Unit)? = null

    /** 문장마다 가드를 지나는 [JdbcClient]. 끝 연산(`update`·`single`·`list` 따위)이 [withAuthorityTransaction] 안에서 돈다. */
    val writes: JdbcClient = guarded(jdbc, JdbcClient::class.java)

    @Suppress("UNCHECKED_CAST")
    fun <T> withAuthorityTransaction(action: () -> T): T {
        if (TransactionSynchronizationManager.hasResource(KEY)) return action()
        return transactions.execute {
            val claim = claims() ?: throw NotAuthority("리스를 쥔 리더가 아니어서 쓰지 않았다")
            val row = jdbc.sql(LOCK)
                .query { rs, _ -> Triple(rs.getString(1), rs.getLong(2), rs.getBoolean(3)) }
                .single()
            afterLock?.invoke()
            if (row.first != claim.holder || row.second != claim.generation || !row.third) {
                val reason = "쓰기 직전 리스가 이 인스턴스의 것이 아니다: 리스=(${row.first}, ${row.second}, 살아 있음 ${row.third}), " +
                    "이 인스턴스=(${claim.holder}, ${claim.generation})"
                onLost(reason)
                throw AuthorityLost(reason)
            }
            jdbc.sql("SELECT set_config('mission.fenced_generation', :generation, true)")
                .param("generation", claim.generation.toString())
                .query { _, _ -> Unit }
                .single()
            TransactionSynchronizationManager.bindResource(KEY, claim)
            try {
                action()
            } finally {
                TransactionSynchronizationManager.unbindResource(KEY)
            }
        } as T
    }

    /** 끝 연산을 가드 안에서 돌리는 대리. 사슬 연산(`param`·`query`)은 다시 감싸 돌려준다. */
    private fun <T : Any> guarded(target: T, type: Class<T>): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), Handler(target)))

    private inner class Handler(private val target: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return call(method, args)
            val chained = CHAINED.firstOrNull { it == method.returnType }
            if (chained != null) {
                val result = call(method, args) ?: return null
                @Suppress("UNCHECKED_CAST")
                return guarded(result, chained as Class<Any>)
            }
            return withAuthorityTransaction { call(method, args) }
        }

        private fun call(method: Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private companion object {
        /** 보유자 행만 `FOR SHARE`(기간 행은 읽기만 한다). */
        val LOCK = """
            SELECT l.holder, l.generation,
                   (SELECT t.expires_at > clock_timestamp() FROM mission.host_lease_term t WHERE t.role = 'host')
            FROM mission.host_lease l WHERE l.role = 'host' FOR SHARE
        """.trimIndent()

        /** 같은 트랜잭션 안에서 이미 가드를 지났다는 표시. */
        val KEY = Any()

        /** 다시 감쌀 사슬 형식. 나머지 반환은 끝 연산이다. */
        val CHAINED = listOf(
            JdbcClient.StatementSpec::class.java,
            JdbcClient.ResultQuerySpec::class.java,
            JdbcClient.MappedQuerySpec::class.java,
        )
    }
}
