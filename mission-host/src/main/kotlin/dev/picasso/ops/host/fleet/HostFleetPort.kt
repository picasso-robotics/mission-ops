package dev.picasso.ops.host.fleet

import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.FleetRead
import dev.picasso.middleware.IssuingFleetPort
import dev.picasso.middleware.PrefetchingFleetPort
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportStatus
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.lease.ControllerConfirmation
import java.util.concurrent.atomic.AtomicLong

/**
 * 실행 호스트의 플릿 포트: [delegate] 를 순번을 매기는 [Sequenced] 로, 그것을 [IssuingFleetPort] 로, 다시 [PrefetchingFleetPort] 로 감싼 것(picasso §15.237).
 * 기체 포트([dev.picasso.ops.host.HostRobotPort])와 대칭이다 — 맡기기·취소는 보내는 쪽이 호스트 잠금 밖에서 하고, pump 의 운반 상태·찾기는 미리 읽은
 * 값을 받는다. 미리 읽은 값은 받은 지 `maxAge`(기본 2초, 기체 포트와 같은 기한 예산) 안에서만 쓴다. 두 데코레이터의 시계는 미들웨어와 같은
 * [HostClock] 이다.
 *
 * ## 관측 중 플릿 준비의 재대조(HA 3단계 PR B2)
 *
 * 찾기·상태 요청과 인수 확인([confirm])은 **보내기 직전에** 한 순번([Sequenced.number], 엄격히 증가)을 받는다 — 벽시계가 아니라 «어느 요청이 먼저
 * 나갔나» 로 가른다(찾기는 `null` 을 돌려줄 수 있어 응답 객체의 동일성으로는 짝짓지 않는다). 인수 확인이 바닥값 = 내 세대 ∧ `DURABLE_CONFIRMED` 를
 * 보이면 그 순번이 «확인 경계» 가 된다. 실행 호스트는 그 뒤 pump 앞에서(호스트 잠금 밖) 앞 pump 가 물은 운반 전부를 [readFresh] 로 다시 읽고(경계보다
 * 큰 순번), 잠금 안의 그 pump 에서 미들웨어에 미리 읽은 값 대신 그 답을 준다([serve]) — 미리 읽은 값은 꺼내 버린다(확인 경계 너머로 재사용하지
 * 않는다). pump 가 끝나면 [endPump] 가 그 라운드가 온전했는가(물은 것이 모두 다시 읽은 답이었고 실패가 없었는가)와 가장 작은 순번을 준다. 시간
 * 대기는 없다.
 *
 * 응답마다의 바닥값은 [floorObserved] 로 받아(HTTP 포트가 부르는 스레드에서) 그 요청의 순번과 함께 [onReadFloor] 에 넘긴다 — 래치 뒤 후퇴를 본다.
 *
 * 닫으면 두 데코레이터의 스레드를 내린다(위임 포트는 닫지 않는다).
 *
 * @param url 표시용 주소. 호스트 상태 응답·개요 화면의 «플릿 연결» 에 나간다.
 * @param rawConfirm 현장 플릿 면 인수 확인([HttpFleetPort.confirmController]). 없으면 `null` — 관측 중 플릿 준비가 서지 않는다(운영자 확인).
 */
class HostFleetPort private constructor(
    val url: String,
    private val sequenced: Sequenced,
    private val clock: HostClock,
    private val issuing: IssuingFleetPort,
    private val prefetching: PrefetchingFleetPort,
    private val rawConfirm: (() -> ControllerConfirmation?)?,
) : AmrFleetPort by prefetching, AutoCloseable {

    constructor(delegate: AmrFleetPort, clock: HostClock, url: String, confirm: (() -> ControllerConfirmation?)? = null) :
        this(url, Sequenced(delegate), clock, confirm)

    /**
     * 참조와 함께 받은 취소를 잠금 밖 송신에 넘기기 전에 부를 것(핸들, 참조). 운영 배선은 안쪽 송신 직전 확인([dev.picasso.ops.host.lease.GatedFleetPort.remember])
     * 에 그 참조를 고정한다 — 그 확인은 참조 없이 불리므로, 핸들 → 참조 표에서 축출된 운반의 취소도 그 실행으로 되짚어 묻게 한다.
     */
    @Volatile
    var onCancelReference: ((TransportHandle, String) -> Unit)? = null

    /**
     * 그 취소의 결과가 이 포트에서 확정됐을 때(답·실패) 부를 것 — 운영 배선은 고정을 푼다([dev.picasso.ops.host.lease.GatedFleetPort.release]). 결과를 이 포트에서
     * 받지 못한 취소(결과 조회가 바깥 관문에서 거절됨, 보낸 뒤 실행이 놓이거나 잊힘)는 부르지 않아 고정이 한 줄 남을 수 있다(권위 영향 없음, 정리는 후속).
     */
    @Volatile
    var onCancelSettled: ((TransportHandle) -> Unit)? = null

    override fun issueCancel(reference: String, handle: TransportHandle): dev.picasso.middleware.FleetCancelIssue? {
        onCancelReference?.invoke(handle, reference)
        val issue = try {
            prefetching.issueCancel(reference, handle)
        } catch (e: Exception) {
            // 넘기지 못했다(송신 실패) — 고정을 푼다.
            onCancelSettled?.invoke(handle)
            throw e
        }
        // 보내는 중이 아니면(결과를 받았거나 실패) 고정을 푼다 — 결과만 받는 재호출이 다시 고정한 것도 여기서 풀린다.
        if (issue != dev.picasso.middleware.FleetCancelIssue.Pending) onCancelSettled?.invoke(handle)
        return issue
    }

    private constructor(url: String, sequenced: Sequenced, clock: HostClock, confirm: (() -> ControllerConfirmation?)?) :
        this(url, sequenced, clock, IssuingFleetPort(sequenced, now = clock::now), confirm)

    private constructor(url: String, sequenced: Sequenced, clock: HostClock, issuing: IssuingFleetPort, confirm: (() -> ControllerConfirmation?)?) :
        this(url, sequenced, clock, issuing, PrefetchingFleetPort(issuing, now = clock::now), confirm)

    /** 찾기·상태 요청에 보내기 직전 순번을 매기는 위임 포트. 인수 확인도 같은 순번을 쓴다. 지금 도는 요청의 순번은 그 스레드에서 읽는다([current]). */
    internal class Sequenced(private val inner: AmrFleetPort) : AmrFleetPort by inner {
        private val next = AtomicLong()
        private val running = ThreadLocal<Long>()

        fun number(): Long = next.incrementAndGet()

        /** 이 스레드에서 지금 도는 읽기의 순번. 읽기 밖이면 `null`. */
        fun current(): Long? = running.get()

        fun <T> numbered(n: Long, call: () -> T): T {
            running.set(n)
            try {
                return call()
            } finally {
                running.remove()
            }
        }

        override fun status(handle: TransportHandle): TransportStatus = numbered(number()) { inner.status(handle) }

        override fun find(reference: String): TransportHandle? = numbered(number()) { inner.find(reference) }

        fun statusAs(n: Long, handle: TransportHandle): TransportStatus = numbered(n) { inner.status(handle) }

        fun findAs(n: Long, reference: String): TransportHandle? = numbered(n) { inner.find(reference) }
    }

    /** 미들웨어가 묻는 플릿 읽기 하나. */
    sealed interface Key {
        data class Status(val handle: TransportHandle) : Key
        data class Find(val reference: String) : Key
    }

    /** 다시 읽은 답 하나와 그 요청 순번. */
    class Fresh(val seq: Long, val read: FleetRead<Any?>)

    /** 확인 뒤 다시 읽은 한 라운드. */
    class Round(val opened: Long, val reads: Map<Key, Fresh>) {
        internal var missed = false
        internal val served = mutableListOf<Fresh>()
    }

    /** 끝난 라운드의 판정. [complete] 면 미들웨어가 물은 플릿 읽기가 모두 이 라운드에서 다시 읽은 성공한 답이었다. [minSeq] 는 쓴 답의 가장 작은 순번(물은 것이 없으면 `null`). */
    data class RoundResult(val complete: Boolean, val opened: Long, val minSeq: Long?, val served: Int)

    /** 인수 확인 답을 받는 쪽(순번, 답). 실행 호스트가 준비 판정에 건다. 확인 스레드에서 불린다. */
    @Volatile
    var onConfirm: ((Long, ControllerConfirmation?) -> Unit)? = null

    /** 플릿 읽기 응답의 바닥값을 받는 쪽(그 요청의 순번, 바닥값). 실행 호스트가 래치 뒤 후퇴 판정에 건다. */
    @Volatile
    var onReadFloor: ((Long, Long) -> Unit)? = null

    /** 인수 확인. 순번을 받은 뒤 보내고 답을 [onConfirm] 에 넘긴다. 현장 플릿 면이 아니면 `null`. */
    val confirm: (() -> ControllerConfirmation?)? = rawConfirm?.let { raw ->
        {
            val n = sequenced.number()
            val answer = raw()
            onConfirm?.invoke(n, answer)
            answer
        }
    }

    /** HTTP 포트가 응답을 받을 때마다 그 스레드에서 부른다. 순번이 매겨진 읽기의 응답이면 [onReadFloor] 에 넘긴다. */
    fun floorObserved(floor: Long) {
        val seq = sequenced.current() ?: return
        onReadFloor?.invoke(seq, floor)
    }

    /** 이번 pump 에서 미들웨어가 물은 읽기(호스트 잠금 아래). */
    private val asked = LinkedHashSet<Key>()

    /** 앞 pump 에서 물은 읽기. 다음 라운드가 다시 읽을 대상이다. */
    @Volatile
    private var lastAsked: Set<Key> = emptySet()

    /**
     * 이 호스트가 아는 운반의 참조(태스크 id `작업지시#단위`) — 구동이 도는 실행의 끝나지 않은 플릿 단위. 실행 호스트가 pump 마다(호스트 잠금 아래) 바꾼다.
     * 다시 지은 실행의 플릿 단위는 미들웨어가 다시 맡기기 전까지 플릿을 읽지 않으므로, 라운드는 이 참조마다 찾기를 더해 맡겨진 운반을 확인 뒤에 다시 본다.
     */
    @Volatile
    var references: Set<String> = emptySet()

    /** 이번 pump 에 줄 라운드. */
    private var round: Round? = null

    /** 재대조 라운드 사이의 최소 간격(단조 시계). 후보가 오래 남아도(읽기 하나가 계속 실패) 매 pump 아는 운반 전부를 다시 읽지 않게 한다. */
    var roundGap: java.time.Duration = ROUND_GAP

    /** 라운드 간격을 재는 단조 시계(나노초). 시험이 바꾼다. */
    @Volatile
    var nanoTime: () -> Long = System::nanoTime

    @Volatile
    private var lastRoundNanos: Long? = null

    /** 지금 재대조 라운드를 돌 차례인가 — 첫 라운드는 곧바로, 그 뒤는 [roundGap] 마다. 차례가 아니면 그 pump 는 라운드 없이 돈다(그 pump 로는 열지 않는다). */
    fun roundDue(): Boolean {
        val last = lastRoundNanos ?: return true
        return nanoTime() - last >= roundGap.toNanos()
    }

    /** 찾기 답으로 배운 운반 핸들(최근 것만). 다시 지은 실행의 운반은 상태를 묻기 전에 찾기로 먼저 보인다. */
    private val foundHandles: MutableSet<String> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > FOUND_HANDLES
        }),
    )

    /**
     * 이 호스트가 아는 운반 핸들의 기동 접두(`<기동 id>:tr-N`) — 앞 pump 가 물은 상태 읽기의 핸들과 찾기 답으로 배운 핸들. 복원한 핸들이 다른 기동의 것이면
     * 현장이 그 운반을 맡긴 뒤 다시 떴다.
     */
    fun handleBoots(): Set<String> {
        val handles = lastAsked.mapNotNull { (it as? Key.Status)?.handle?.id } + synchronized(foundHandles) { foundHandles.toList() }
        return handles.mapNotNullTo(HashSet()) { it.substringBefore(':', "").takeIf(String::isNotEmpty) }
    }

    private fun learn(read: FleetRead<*>) {
        ((read as? FleetRead.Read<*>)?.value as? TransportHandle)?.let { foundHandles += it.id }
    }

    /**
     * 앞 pump 가 물은 읽기 전부를 지금 다시 읽는다(호스트 잠금 밖). 요청마다 보내기 직전 순번을 받는다 — 확인 경계보다 크다(경계를 정한 확인보다 뒤에
     * 보냈다). 실패는 그 답으로 둔다.
     */
    fun readFresh(): Round {
        lastRoundNanos = nanoTime()
        // 라운드를 연 순번 — 다시 읽을 것이 없어도 이 라운드가 확인 경계보다 뒤에 열렸음을 보인다.
        val opened = sequenced.number()
        val reads = LinkedHashMap<Key, Fresh>()
        for (key in lastAsked + references.map { Key.Find(it) }) {
            val n = sequenced.number()
            val sentAt = clock.now()
            val read: FleetRead<Any?> = try {
                val value: Any? = when (key) {
                    is Key.Status -> sequenced.statusAs(n, key.handle)
                    is Key.Find -> sequenced.findAs(n, key.reference)
                }
                FleetRead.Read(value, sentAt, clock.now())
            } catch (e: RuntimeException) {
                FleetRead.Failed(e)
            }
            learn(read)
            reads[key] = Fresh(n, read)
        }
        return Round(opened, reads)
    }

    /** 이번 pump 를 시작한다(호스트 잠금 아래, `Middleware.pump` 앞). [fresh] 가 있으면 미들웨어에 미리 읽은 값 대신 그 답을 준다. */
    fun beginPump(fresh: Round?) {
        asked.clear()
        round = fresh
    }

    /** 이번 pump 를 끝낸다(호스트 잠금 아래, `Middleware.pump` 뒤). 라운드를 주었으면 그 판정, 아니면 `null`. */
    fun endPump(): RoundResult? {
        lastAsked = LinkedHashSet(asked)
        val r = round ?: return null
        round = null
        // 온전함: 미들웨어가 물은 것이 모두 이 라운드의 답이었고, 이 라운드가 다시 읽은 것(물은 것 + 아는 참조의 찾기)이 모두 성공했다.
        val ok = !r.missed && r.reads.values.all { it.read is FleetRead.Read }
        return RoundResult(ok, r.opened, r.reads.values.minOfOrNull { it.seq }, r.served.size)
    }

    override fun prefetchedStatus(handle: TransportHandle): FleetRead<TransportStatus> {
        val key = Key.Status(handle)
        asked += key
        // 미리 읽은 값은 꺼내 버린다 — 남기면 다음 라운드에 이번 답보다 오래된 값이 앉는다(그리고 다음 읽기를 건다).
        val cached = prefetching.prefetchedStatus(handle)
        @Suppress("UNCHECKED_CAST")
        return fresh(key)?.read as FleetRead<TransportStatus>? ?: cached
    }

    override fun prefetchedFind(reference: String): FleetRead<TransportHandle?> {
        val key = Key.Find(reference)
        asked += key
        val cached = prefetching.prefetchedFind(reference)
        learn(cached)
        @Suppress("UNCHECKED_CAST")
        return fresh(key)?.read as FleetRead<TransportHandle?>? ?: cached
    }

    private fun fresh(key: Key): Fresh? {
        val r = round ?: return null
        val answer = r.reads[key]
        if (answer == null) {
            r.missed = true
            return null
        }
        r.served += answer
        return answer
    }

    override fun close() {
        prefetching.close()
        issuing.close()
    }

    companion object {
        /** [roundGap] 의 기본값(확인 스레드의 재시도 간격 기본값과 같다). */
        val ROUND_GAP: java.time.Duration = java.time.Duration.ofSeconds(1)

        /** 찾기 답으로 배운 핸들을 두는 상한. */
        const val FOUND_HANDLES = 1_000
    }
}
