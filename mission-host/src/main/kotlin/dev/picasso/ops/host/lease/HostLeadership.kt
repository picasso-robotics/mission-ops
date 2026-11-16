package dev.picasso.ops.host.lease

import dev.picasso.middleware.CommandNotSent
import dev.picasso.ops.host.store.LeaseClaim
import org.slf4j.LoggerFactory
import java.util.EnumMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 리더 상태(HA 설계 기록 §7). 리스 획득은 [ISSUING] 이 아니라 [RECONCILING] 진입이다.
 *
 * - [STANDBY]: 리스가 없다. 심장박동과 주기적 획득 시도만 한다(복원·pump·셀 읽기·기체/플릿 호출·변경 HTTP 없음).
 * - [RECONCILING]: 리스를 쥐었고 카탈로그·일지·잔여 파지를 DB 에서 다시 읽어 실행을 다시 짓는 중이다. 송신하지 않는다.
 * - [HOLDING]: 만료 인수 뒤(또는 확인 안 된 인수를 물려받음). 다시 지은 실행에 다시 붙어 관측만 한다. 신규 발행(기체·플릿·셀 명령, 변경 HTTP)은
 *   운영자 확인 뒤에 연다.
 * - [ISSUING]: 발행한다. 발행을 열었던 보유자의 정상 종료에서 넘겨받았거나 운영자가 옛 프로세스 종료를 확인했다. 들기 전에 리스 기간 행에 «이 세대가
 *   발행을 열었다» 를 적는다.
 * - [STOPPING]: 정상 종료 중. 송신·DB 쓰기·변경 HTTP 를 닫고 송신 중인 명령이 다 끝나기를 기다린다. 리스는 내놓을 때까지 갱신한다.
 * - [LOST]: 리스를 잃었다. 송신·pump 를 멈추고 자기 차단을 (제한 시간 안에) 적은 뒤 프로세스를 끝낸다. 다시 얻지 않는다(감독자의 재기동이 새
 *   인스턴스로 띄운다).
 */
enum class LeaderState { STANDBY, RECONCILING, HOLDING, ISSUING, STOPPING, LOST }

/** 리스를 얻었을 때와 잃었을 때 실행 호스트가 할 일. */
interface LeadershipListener {
    /** 리스를 얻었다([LeaderState.RECONCILING]). 재대조를 마치면 [HostLeadership.reconciled] 를 부른다. */
    fun acquired(acquired: Acquired)

    /** 리스를 잃었다([LeaderState.LOST]). 송신·pump 를 멈추고 자기 차단을 적는다. 그 뒤 프로세스 종료 훅이 돈다. 오래 막히면 안 된다. */
    fun lost(reason: String)
}

/** 리스를 잃은 프로세스를 끝내는 훅. 운영은 프로세스 종료이고 시험은 기록만 하는 훅을 넣는다. */
fun interface HostExit {
    fun exit(reason: String)

    companion object {
        /** 종료 코드. 감독자(자동 재기동)가 새 인스턴스로 다시 띄운다. */
        const val LOST_EXIT_CODE = 70

        /** `System.exit` 의 종료 훅(Spring 닫기)이 이만큼 안에 끝나지 않으면 `Runtime.halt` 로 끝낸다. */
        const val HALT_AFTER_MILLIS = 10_000L

        /**
         * 새 스레드에서 `System.exit` 를 부르고(종료 훅이 Spring 컨텍스트를 닫는다), 그 훅이 [HALT_AFTER_MILLIS] 안에 끝나지 않으면 `Runtime.halt` 로
         * 끝낸다 — LOST 인 채 프로세스가 남으면 감독자가 다시 띄우지 않는다. 부른 스레드를 막지 않는다.
         */
        val PROCESS = HostExit { reason ->
            LoggerFactory.getLogger(HostExit::class.java).error("리스를 잃어 프로세스를 끝낸다: {}", reason)
            Thread({
                Thread.sleep(HALT_AFTER_MILLIS)
                Runtime.getRuntime().halt(LOST_EXIT_CODE)
            }, "mission-host-halt").apply { isDaemon = true }.start()
            Thread({ System.exit(LOST_EXIT_CODE) }, "mission-host-exit").start()
        }
    }
}

/**
 * 송신 직전 로컬 권위 확인(HA 설계 기록 §4.4 의 3). 기체·플릿 명령, 셀 신호 쓰기, 결함 주입 중계가 실제 송신을 시작하기 직전에 [sending] 으로
 * 감싼다. 리더가 [LeaderState.ISSUING] 이고 자기 차단 기한 전이어야 보낸다. **틈을 줄일 뿐 닫지 않는다** — 확인과 송신 사이에 멎은 프로세스,
 * 이미 나간 명령은 막지 못한다(받는 쪽의 세대 대조는 다음 단계).
 */
interface SendAuthority {
    fun mayIssue(): Boolean

    /** 보낼 수 없으면 [CommandNotSent] 를 던진다(보내지 않았음을 아는 실패라 미들웨어가 미확정으로 읽지 않는다). */
    fun checkIssue(what: String) {
        if (!mayIssue()) throw CommandNotSent("$NOT_ISSUING: $what")
    }

    /** 확인한 뒤 [send] 를 부른다. [HostLeadership] 은 송신 중인 수를 세어 정상 종료가 그것이 0 이 되기를 기다린다. */
    fun <T> sending(what: String, send: () -> T): T {
        checkIssue(what)
        return send()
    }

    companion object {
        const val NOT_ISSUING = "리스로 발행하는 리더가 아니어서 보내지 않았다"

        /** 언제나 보낸다. 리스 없이 쓰는 자리(시험, 모의 실행)만 쓴다. */
        val ALWAYS = object : SendAuthority {
            override fun mayIssue(): Boolean = true
        }
    }
}

/**
 * 리더 상태기계(HA 설계 기록 §7). [lease] 를 주기마다 획득(대기)하거나 갱신(보유)하고, 감시가 자기 차단 기한을 본다. 실행 호스트는
 * [begin] 으로 듣는 쪽을 걸고, 획득하면 [LeadershipListener.acquired] 에서 재대조한 뒤 [reconciled] 로 [LeaderState.ISSUING]·
 * [LeaderState.HOLDING] 에 든다.
 *
 * 리스를 잃는 길은 셋이다: 갱신이 0행(보유자·세대 불일치나 만료), 자기 차단 기한이 지남(갱신이 제때 안 됨), DB 쓰기 가드가 리스 불일치를 봄
 * ([lost]). 어느 길이든 한 번만 [LeaderState.LOST] 로 가고, 그 순간부터 송신 확인([mayIssue])·DB 가드([claim])가 막는다. 그 뒤 새
 * 스레드에서 pump 정지·자기 차단 기록([LeadershipListener.lost]) → 종료 훅([exit]) 순으로 돈다.
 *
 * 정상 종료는 [stopping] → (pump 정지) → [awaitQuiet] → 멈춤 기록 → [release] 순서다(실행 호스트의 `close`).
 */
class HostLeadership(
    val lease: HostLease,
    val timing: LeaseTiming,
    private val exit: HostExit = HostExit.PROCESS,
) : SendAuthority, AutoCloseable {

    val instanceId: String get() = lease.instanceId

    private val stateRef = AtomicReference(LeaderState.STANDBY)

    val state: LeaderState get() = stateRef.get()

    /** 이번 보유의 획득 결과. 쥔 적이 없으면 `null`. */
    @Volatile
    var acquired: Acquired? = null
        private set

    /** [LeaderState.LOST] 의 까닭. */
    @Volatile
    var lostReason: String? = null
        private set

    private var listener: LeadershipListener? = null

    /** 송신 직전 확인을 지나 아직 송신 중인 명령 수([sending]). */
    private val inFlight = AtomicInteger()

    /** 시험이 프로세스 멈춤(긴 GC 정지 따위)을 흉내 내는 문. 리스·감시 스레드와 실행 호스트의 pump·심장박동이 주기마다 지난다. */
    internal val stall = Stall()

    private val leaseThread = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-lease").apply { isDaemon = true } }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-lease-watch").apply { isDaemon = true } }

    override fun mayIssue(): Boolean = state == LeaderState.ISSUING && lease.withinDeadline()

    /**
     * 송신 중인 수를 먼저 올리고 확인한다 — [stopping] 이 상태를 바꾼 뒤 [awaitQuiet] 가 0 을 보면, 그 뒤에 확인을 지나는 송신은 없다(올린 뒤
     * 확인하므로 상태 변경 전에 지난 송신은 수에 이미 들어 있다).
     */
    override fun <T> sending(what: String, send: () -> T): T {
        inFlight.incrementAndGet()
        try {
            checkIssue(what)
            return send()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** DB 쓰기 가드가 쓸 (보유자, 세대). 리스를 쥐고 발행 쪽 상태(재대조·관측·발행)일 때만 있다 — 정상 종료 중·잃음에는 없다. */
    fun claim(): LeaseClaim? {
        val s = state
        if (s != LeaderState.RECONCILING && s != LeaderState.HOLDING && s != LeaderState.ISSUING) return null
        return lease.generation?.let { LeaseClaim(instanceId, it) }
    }

    /**
     * 듣는 쪽을 걸고 리스를 한 번 곧바로 얻어 본다. 얻으면 이 스레드에서 [LeadershipListener.acquired] 를 불러 재대조를 끝낸 뒤 돌아온다(기동
     * 복원이 웹 서버보다 먼저). 못 얻으면 [LeaderState.STANDBY] 로 두고 주기마다 다시 얻어 본다 — 그때의 재대조는 [takeover] 실행기에서 돈다.
     */
    fun begin(listener: LeadershipListener, takeover: (Runnable) -> Unit) {
        this.listener = listener
        val first = try {
            lease.acquire()
        } catch (e: Exception) {
            log.warn("리스 획득 실패(대기로 둔다): {}", e.toString())
            null
        }
        first?.let(::enter)
        val period = timing.period.toMillis()
        leaseThread.scheduleWithFixedDelay({ tick(takeover) }, period, period, TimeUnit.MILLISECONDS)
        val watch = maxOf(20L, period / 5)
        watchdog.scheduleWithFixedDelay(::watch, watch, watch, TimeUnit.MILLISECONDS)
        first?.let(::reconcile)
    }

    /**
     * 재대조를 마쳤다. 곧바로 발행해도 되는 인수([Acquired.graceful])면 «이 세대가 발행을 연다» 를 리스에 적은 뒤 발행하고, 아니면(또는 그 표시를 못
     * 적으면) 관측만 한다.
     */
    fun reconciled() {
        val next = if (acquired?.graceful == true && marked()) LeaderState.ISSUING else LeaderState.HOLDING
        if (stateRef.compareAndSet(LeaderState.RECONCILING, next)) log.info("리더 상태 {} (세대 {})", next, lease.generation)
    }

    /**
     * 운영자가 옛 프로세스 종료를 확인했다. [LeaderState.HOLDING] 에서만 «이 세대가 발행을 연다» 를 리스에 적고 [LeaderState.ISSUING] 으로 간다.
     * 적지 못하면(이 세대가 아님) 잃음으로 가고 거짓이다. 연결 장애는 예외로 나간다(상태는 그대로).
     */
    @Synchronized
    fun confirmed(): Boolean {
        if (state != LeaderState.HOLDING) return false
        if (!lease.markIssuing()) {
            lost("발행 표시를 적지 못했다(이 세대의 리스가 아니다)")
            return false
        }
        return stateRef.compareAndSet(LeaderState.HOLDING, LeaderState.ISSUING)
    }

    private fun marked(): Boolean = try {
        lease.markIssuing()
    } catch (e: Exception) {
        log.warn("발행 표시를 적지 못해 관측으로 둔다: {}", e.toString())
        false
    }

    /** 리스를 얻은 스레드에서 곧바로 [LeaderState.RECONCILING] 에 든다(다음 주기부터 갱신한다). */
    private fun enter(acquired: Acquired) {
        this.acquired = acquired
        check(stateRef.compareAndSet(LeaderState.STANDBY, LeaderState.RECONCILING)) { "대기가 아닌데 리스를 얻었다: $state" }
        log.info(
            "리스를 얻었다: 세대 {}, 앞 보유자 {} (멈춤 {}, 앞 세대 발행 {}, 다른 산 인스턴스 {})",
            acquired.generation, acquired.previousHolder, acquired.previousStopped, acquired.previousIssuing, acquired.othersLive,
        )
    }

    /** 재대조. 실패하면 리스를 잃은 것으로 다룬다(다시 얻지 않는다). */
    private fun reconcile(acquired: Acquired) {
        try {
            checkNotNull(listener).acquired(acquired)
        } catch (e: Exception) {
            lost("재대조 실패: $e")
            throw e
        }
    }

    private fun tick(takeover: (Runnable) -> Unit) {
        stall.pass(Stall.Part.LEASE)
        try {
            when (state) {
                LeaderState.STANDBY -> lease.acquire()?.let { got ->
                    enter(got)
                    takeover(Runnable { runCatching { reconcile(got) } })
                }
                LeaderState.RECONCILING, LeaderState.HOLDING, LeaderState.ISSUING, LeaderState.STOPPING ->
                    if (!lease.renew()) lost("리스 갱신이 거부됐다(보유자·세대가 다르거나 이미 만료)")
                LeaderState.LOST -> Unit
            }
        } catch (e: Exception) {
            log.warn("리스 {} 실패: {}", if (state == LeaderState.STANDBY) "획득" else "갱신", e.toString())
        }
    }

    /** 리스를 쥔 동안 자기 차단 기한이 지났으면 잃은 것으로 다룬다. */
    private fun watch() {
        stall.pass(Stall.Part.WATCH)
        val s = state
        if (s != LeaderState.STANDBY && s != LeaderState.LOST && !lease.withinDeadline()) {
            lost("자기 차단 기한이 지났다(제때 갱신하지 못함)")
        }
    }

    /**
     * 리스를 잃었다. 한 번만 돈다. 상태를 곧바로 [LeaderState.LOST] 로 바꿔 송신 확인과 DB 가드를 막고, 새 스레드에서 pump 정지·자기 차단 기록
     * ([LeadershipListener.lost]) 뒤 종료 훅을 부른다. 다시 얻지 않는다.
     */
    fun lost(reason: String) {
        while (true) {
            val s = state
            if (s == LeaderState.LOST || s == LeaderState.STANDBY) return
            if (stateRef.compareAndSet(s, LeaderState.LOST)) break
        }
        lostReason = reason
        lease.forget()
        log.error("리스를 잃었다: {}", reason)
        Thread({
            try {
                listener?.lost(reason)
            } catch (e: Exception) {
                log.warn("자기 차단 처리 실패: {}", e.toString())
            } finally {
                leaseThread.shutdownNow()
                watchdog.shutdownNow()
                exit.exit(reason)
            }
        }, "mission-host-lost").start()
    }

    /** 정상 종료를 시작한다. 리스를 쥔 발행 쪽 상태면 [LeaderState.STOPPING] 으로 가서 송신·DB 쓰기·변경 HTTP 를 닫는다. 그 상태였는가. */
    fun stopping(): Boolean {
        while (true) {
            val s = state
            if (s != LeaderState.RECONCILING && s != LeaderState.HOLDING && s != LeaderState.ISSUING) return false
            if (stateRef.compareAndSet(s, LeaderState.STOPPING)) return true
        }
    }

    /** 송신 직전 확인을 지난 명령이 다 끝날 때까지 [timeoutMillis] 만큼 기다린다. 0 이 되었는가. */
    fun awaitQuiet(timeoutMillis: Long): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (inFlight.get() > 0) {
            if (System.nanoTime() - until > 0) return false
            Thread.sleep(5)
        }
        return true
    }

    /** 리스를 내놓는다(정상 종료). 잃었으면 하지 않는다. */
    fun release() {
        leaseThread.shutdownNow()
        watchdog.shutdownNow()
        leaseThread.awaitTermination(2, TimeUnit.SECONDS)
        if (state != LeaderState.LOST) lease.release()
    }

    override fun close() {
        leaseThread.shutdownNow()
        watchdog.shutdownNow()
        lease.close()
    }

    /** 프로세스 멈춤 흉내. 닫은 부분을 지나는 스레드는 열릴 때까지 기다린다. */
    class Stall {
        enum class Part { LEASE, WATCH, PUMP, BEAT }

        private val gates = EnumMap<Part, CountDownLatch>(Part::class.java)

        @Synchronized
        fun close(vararg parts: Part = Part.entries.toTypedArray()) {
            parts.forEach { if (gates[it] == null) gates[it] = CountDownLatch(1) }
        }

        @Synchronized
        fun open(vararg parts: Part = Part.entries.toTypedArray()) {
            parts.forEach { gates.remove(it)?.countDown() }
        }

        fun pass(part: Part) {
            synchronized(this) { gates[part] }?.await()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HostLeadership::class.java)
    }
}
