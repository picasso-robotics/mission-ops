package dev.picasso.ops.host

import dev.picasso.contracts.v1.Capability
import dev.picasso.middleware.IssuingRobotPort
import dev.picasso.middleware.PrefetchedRead
import dev.picasso.middleware.PrefetchingRobotPort
import dev.picasso.middleware.Replay
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 실행 호스트의 기체 포트: [delegate] 를 [IssuingRobotPort] 로, 그것을 다시 [PrefetchingRobotPort] 로 감싼 것. pump 의 동기화는 미리
 * 읽은 값을 받고(`prefetched`), 출발·취소는 보내는 쪽이 잠금 밖에서 한다. 나머지는 위임한다.
 *
 * **쓸 미리 읽기가 없으면 지금 읽는다.** 미리 읽는 포트는 읽은 적이 없거나 마지막 읽기가 `maxAge`(기본 2초)를 넘겨 낡았으면
 * [PrefetchedRead.Unread] 를 준다. 미들웨어는 그것을 못 본 기체로 다루고, 출발 관문은 못 본 기체의 결함으로는 막지 않고 메모만 남긴
 * 채 지나간다(`proceeding without the gate`). 그러면 재기동 직후 첫 라운드나 읽기가 늦은 라운드에 서 있는 결함이 다음 단위를 막지
 * 못한다. 그래서 [prefetched] 는 [PrefetchedRead.Unread] 를 받으면 위임 포트에 지금 묻고(감싸기 전의 동기화와 같다) 그 라운드의
 * 재생도 위임 포트에 묻는다. 데워진 뒤에는 미리 읽은 값을 쓰고 잠금 아래에서 기다리지 않는다. 답하지 않는 기체는 낡은 채로 남아
 * 라운드마다 지금 묻게 되며, 그것은 감싸기 전과 같다 — 단 [unreadFallback] 을 건 실행 호스트가 관측(HOLDING) 중이면 지금 읽기 대상이 아닌 기체는
 * 이번 관측의 첫 관측과, 그 뒤 기체마다 정해진 간격([dev.picasso.ops.host.lease.HostLeadership.allowUnreadFallback])마다 한 번만 지금 묻는다.
 *
 * [snapshot] 은 늘 위임 포트에 지금 묻는다(감싸기 전과 같다). 부르는 자리는 지금의 기체 상태로 정하는 곳이다: 기동 복원
 * (`Middleware.resume`), 복원 못 한 실행의 종결 확인(기체가 움직이는가), 판정에서 뺀 기체의 스냅숏 확인. 미리 읽은 값은 2초까지 낡을 수
 * 있어 이 자리에 쓰지 않는다.
 *
 * [capabilities] 는 미리 읽은 선언이 받은 지 `maxAge` 안이면 그것을 주고(picasso 가 미리 읽기에서 정한 것), 없으면 지금 묻는다 —
 * 데워지기 전 첫 제출의 선언 확인이 선언을 모르는 것으로 바뀌지 않게 한다. 그래서 [capabilitiesPending] 은 늘 거짓이다(지금 묻는 포트와
 * 같다): 미리 읽은 선언이 없으면 [capabilities] 가 지금 물으므로 출발을 미룰 까닭이 없다. 미리 읽는 포트의 기다림은 시계로 재는데,
 * 가상 시계가 라운드 사이에 `maxAge` 넘게 건너뛰면 선언이 매 라운드 낡아 기다림이 다시 시작되고 출발이 끝없이 밀린다.
 *
 * **곧 출발할 기체는 지금 읽는다.** 출발 관문은 그 라운드 뷰의 결함으로 막는다. 미리 읽은 값은 한 라운드 앞에(읽기가 늦으면
 * `maxAge` 까지) 보낸 읽기의 것이라, 그 사이 선 결함을 못 보고 내보낸다. 그래서 실행 호스트가 pump 마다 출발을 앞둔 기체를
 * [readNowFor] 로 알려 주고, 그 기체는 데워져 있어도 지금 묻는다(감싸기 전과 같다). 미리 읽은 값은 그래도 꺼내 버린다. 남겨 두면
 * 다음 라운드에 이번 지금 읽기보다 오래된 값이 뷰에 앉는다.
 *
 * **지금 읽은 스냅숏만 준비 판정에 넘긴다**(HA 3단계). 실행 호스트가 [onFreshRead] 를 걸면 [prefetched] 가 위임 포트에 지금 물은 스냅숏(못
 * 읽었으면 `null`)을 그것에 넘긴다 — 미리 읽은 값은 넘기지 않는다. 미리 읽은 값은 수신자가 이 세대를 확인하기 전에 보낸 읽기의 답일 수 있어
 * 그것으로 기체를 열면 재대조가 확인 전의 상태로 돈다. 미리 읽은 값은 [onOtherRead] 로만 넘기고, 관측 중인 실행 호스트는 그 값이 준비를 보인
 * 미준비 기체를 [readNowFor] 에 넣어 그 라운드에 지금 읽는다.
 *
 * 미들웨어는 호스트 잠금 아래 한 스레드에서 이 포트를 부르므로 [direct] 와 [readNowFor] 의 집합은 잠금이 지킨다. 닫으면 두 데코레이터의 스레드를 내린다(위임
 * 포트는 닫지 않는다).
 */
class HostRobotPort private constructor(
    private val delegate: Sequenced,
    private val clock: HostClock,
    private val issuing: IssuingRobotPort,
    private val prefetching: PrefetchingRobotPort,
) : RobotPort by prefetching, AutoCloseable {

    constructor(delegate: RobotPort, clock: HostClock) : this(delegate, clock, null)

    /** [reads] 는 미리 읽기를 돌릴 실행기다. 널이면 picasso 기본(기체마다 가상 스레드)이다. 시험이 읽기가 끝나는 때를 정할 때 넘긴다. */
    internal constructor(delegate: RobotPort, clock: HostClock, reads: Executor?) : this(Sequenced(delegate), clock, reads)

    private constructor(sequenced: Sequenced, clock: HostClock, reads: Executor?) :
        this(sequenced, clock, IssuingRobotPort(sequenced, now = clock::now), reads)

    private constructor(delegate: Sequenced, clock: HostClock, issuing: IssuingRobotPort, reads: Executor?) :
        this(delegate, clock, issuing, PrefetchingRobotPort(issuing, now = clock::now, executor = reads))

    /**
     * 스냅숏 요청에 **보내기 직전** 순번을 매기는 위임 포트. 미리 읽기(picasso `PrefetchingRobotPort` → `IssuingRobotPort` → [snapshot])와 지금 읽기
     * ([read])가 같은 순번을 나눠 쓴다 — 엄격히 증가하고 같은 번호가 없어 벽시계(역행·같은 시각)와 무관하게 «어느 요청이 먼저 나갔나» 를 가른다.
     * picasso 는 고치지 않는다.
     *
     * 미리 읽기의 응답은 기체마다 «끝난 미리 읽기의 (순번, 응답 객체)» 를 최근 [RECENT] 개만 둔다(기체마다 작은 고정 슬롯, 전역 표 없음). 하나가 아니라
     * 몇 개인 까닭: picasso `PrefetchingRobotPort` 는 소비한 값을 돌려주기 **전에** 다음 미리 읽기를 띄우므로, 그 다음 읽기가 소비 쪽의 조회보다 먼저
     * 끝나 슬롯 하나를 덮으면 방금 소비한 응답의 순번을 잃는다(한 기체에 진행 중인 미리 읽기는 하나뿐이라 둘이면 넉넉하다). 소비하는 쪽은 받은 응답
     * 객체와 같은 객체(`===`)인 항목의 순번을 얻고([prefetchedSeq], 여럿이면 가장 큰 것), 없으면 순번을 모른다(받는 쪽은 옛 응답으로 다룬다). 지금 읽기와 [HostRobotPort.snapshot] 은 반환값과 순번을 곧바로 짝지어 넘기므로 슬롯을 쓰지도 지우지도 않는다. 두 요청이 같은 응답 객체를
     * 돌려주면 옛 소비가 더 큰 순번을 받을 수 있으나 내용이 같으므로 판정은 바뀌지 않는다.
     */
    internal class Sequenced(private val inner: RobotPort) : RobotPort by inner {
        private val next = AtomicLong()

        private class Slot(val seq: Long, val snapshot: RobotSnapshot)

        private val slots = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Slot>>()

        /** 다음 요청 순번. 요청을 보내기 직전에 뽑는다. */
        fun number(): Long = next.incrementAndGet()

        /** 지금까지 뽑은 가장 큰 요청 순번. */
        fun current(): Long = next.get()

        /** 순번 [n] 으로 지금 읽는다(슬롯을 건드리지 않는다). */
        fun read(robotId: String, n: Long): RobotSnapshot? = inner.snapshot(robotId)

        /** 미리 읽기 경로. 순번을 매겨 읽고, 응답이 있으면 그 기체의 최근 슬롯에 (순번, 응답 객체)를 더한다(오래된 것부터 버림). */
        override fun snapshot(robotId: String): RobotSnapshot? {
            val n = number()
            val snapshot = inner.snapshot(robotId) ?: return null
            val recent = slots.computeIfAbsent(robotId) { java.util.ArrayDeque() }
            synchronized(recent) {
                recent.addLast(Slot(n, snapshot))
                while (recent.size > RECENT) recent.removeFirst()
            }
            return snapshot
        }

        /** 소비한 미리 읽기 응답 [snapshot] 의 순번(같은 객체인 최근 항목 가운데 가장 큰 것). 없으면 `null`(모름). */
        fun prefetchedSeq(robotId: String, snapshot: RobotSnapshot): Long? {
            val recent = slots[robotId] ?: return null
            return synchronized(recent) { recent.filter { it.snapshot === snapshot }.maxOfOrNull { it.seq } }
        }

        companion object {
            /** 기체마다 두는 최근 미리 읽기 응답 수. */
            const val RECENT = 4
        }
    }

    /** 지금까지 뽑은 가장 큰 요청 순번 — 이보다 큰 순번의 응답은 이 시점 뒤에 보낸 요청의 답이다(수신자 재시작 의심 후보의 기준). */
    fun currentSeq(): Long = delegate.current()

    /** 이번 라운드에 지금 읽은 기체. 그 기체의 재생도 위임 포트에 묻는다. */
    private val direct = HashSet<String>()

    /** 출발을 앞둔 기체. 실행 호스트가 pump 마다 [readNowFor] 로 바꾼다. */
    private var readNow: Set<String> = emptySet()

    /** 이번 pump 에서 데워져 있어도 지금 읽을 기체. 호스트 잠금 아래에서 `Middleware.pump` 앞에 부른다. */
    fun readNowFor(robotIds: Set<String>) {
        readNow = robotIds
    }

    /** 지금 읽은 스냅숏(기체, 스냅숏 또는 `null`, 요청 순번, 받은 시각)을 받는 쪽. 실행 호스트가 준비 판정에 건다. 호스트 잠금 아래에서 불린다. */
    @Volatile
    var onFreshRead: ((String, RobotSnapshot?, Long, java.time.Instant) -> Unit)? = null

    /**
     * 미리 읽은 값이 없을 때(`Unread`) 지금 읽기 대상이 아닌 기체를 동기로 읽어도 되는가([dev.picasso.ops.host.lease.HostLeadership.allowUnreadFallback]).
     * 거짓이면 동기로 읽지 않고 `Unread` 를 그대로 준다. 걸지 않으면 늘 읽는다(오늘 거동).
     */
    @Volatile
    var unreadFallback: ((String) -> Boolean)? = null

    /**
     * 그 밖의 스냅숏 응답(미리 읽은 값, [snapshot])을 받는 쪽. 실행 호스트는 이것으로 기체를 열지 않고 지금 읽기 대상을 거르고 래치 뒤 후퇴를 본다.
     */
    @Volatile
    var onOtherRead: ((String, RobotSnapshot?, Long?) -> Unit)? = null

    override fun prefetched(robotId: String): PrefetchedRead {
        direct.remove(robotId)
        val read = prefetching.prefetched(robotId)
        if (read is PrefetchedRead.Read) onOtherRead?.invoke(robotId, read.snapshot, read.snapshot?.let { delegate.prefetchedSeq(robotId, it) })
        if (read != PrefetchedRead.Unread && robotId !in readNow) return read
        if (read == PrefetchedRead.Unread && robotId !in readNow && unreadFallback?.invoke(robotId) == false) return read
        direct.add(robotId)
        val sentAt = clock.now()
        val seq = delegate.number()
        return try {
            val snapshot = delegate.read(robotId, seq)
            val at = clock.now()
            onFreshRead?.invoke(robotId, snapshot, seq, at)
            PrefetchedRead.Read(snapshot, sentAt, at)
        } catch (e: RuntimeException) {
            onFreshRead?.invoke(robotId, null, seq, clock.now())
            PrefetchedRead.Failed(e)
        }
    }

    override fun replay(robotId: String, from: Long): Replay? =
        if (direct.remove(robotId)) delegate.replay(robotId, from) else prefetching.replay(robotId, from)

    override fun snapshot(robotId: String): RobotSnapshot? {
        val seq = delegate.number()
        val snapshot = delegate.read(robotId, seq)
        onOtherRead?.invoke(robotId, snapshot, seq)
        return snapshot
    }

    override fun capabilities(robotId: String): Capability? = prefetching.capabilities(robotId) ?: delegate.capabilities(robotId)

    override fun capabilitiesPending(robotId: String): Boolean = false

    override fun close() {
        prefetching.close()
        issuing.close()
    }
}
