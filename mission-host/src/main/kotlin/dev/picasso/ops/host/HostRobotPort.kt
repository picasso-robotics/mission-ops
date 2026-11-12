package dev.picasso.ops.host

import dev.picasso.contracts.v1.Capability
import dev.picasso.middleware.IssuingRobotPort
import dev.picasso.middleware.PrefetchedRead
import dev.picasso.middleware.PrefetchingRobotPort
import dev.picasso.middleware.Replay
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import java.util.concurrent.Executor

/**
 * 실행 호스트의 기체 포트: [delegate] 를 [IssuingRobotPort] 로, 그것을 다시 [PrefetchingRobotPort] 로 감싼 것. pump 의 동기화는 미리
 * 읽은 값을 받고(`prefetched`), 출발·취소는 보내는 쪽이 잠금 밖에서 한다. 나머지는 위임한다.
 *
 * **쓸 미리 읽기가 없으면 지금 읽는다.** 미리 읽는 포트는 읽은 적이 없거나 마지막 읽기가 `maxAge`(기본 2초)를 넘겨 낡았으면
 * [PrefetchedRead.Unread] 를 준다. 미들웨어는 그것을 못 본 기체로 다루고, 출발 관문은 못 본 기체의 결함으로는 막지 않고 메모만 남긴
 * 채 지나간다(`proceeding without the gate`). 그러면 재기동 직후 첫 라운드나 읽기가 늦은 라운드에 서 있는 결함이 다음 단위를 막지
 * 못한다. 그래서 [prefetched] 는 [PrefetchedRead.Unread] 를 받으면 위임 포트에 지금 묻고(감싸기 전의 동기화와 같다) 그 라운드의
 * 재생도 위임 포트에 묻는다. 데워진 뒤에는 미리 읽은 값을 쓰고 잠금 아래에서 기다리지 않는다. 답하지 않는 기체는 낡은 채로 남아
 * 라운드마다 지금 묻게 되며, 그것은 감싸기 전과 같다.
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
 * 미들웨어는 호스트 잠금 아래 한 스레드에서 이 포트를 부르므로 [direct] 와 [readNowFor] 의 집합은 잠금이 지킨다. 닫으면 두 데코레이터의 스레드를 내린다(위임
 * 포트는 닫지 않는다).
 */
class HostRobotPort private constructor(
    private val delegate: RobotPort,
    private val clock: HostClock,
    private val issuing: IssuingRobotPort,
    private val prefetching: PrefetchingRobotPort,
) : RobotPort by prefetching, AutoCloseable {

    constructor(delegate: RobotPort, clock: HostClock) : this(delegate, clock, null)

    /** [reads] 는 미리 읽기를 돌릴 실행기다. 널이면 picasso 기본(기체마다 가상 스레드)이다. 시험이 읽기가 끝나는 때를 정할 때 넘긴다. */
    internal constructor(delegate: RobotPort, clock: HostClock, reads: Executor?) :
        this(delegate, clock, IssuingRobotPort(delegate, now = clock::now), reads)

    private constructor(delegate: RobotPort, clock: HostClock, issuing: IssuingRobotPort, reads: Executor?) :
        this(delegate, clock, issuing, PrefetchingRobotPort(issuing, now = clock::now, executor = reads))

    /** 이번 라운드에 지금 읽은 기체. 그 기체의 재생도 위임 포트에 묻는다. */
    private val direct = HashSet<String>()

    /** 출발을 앞둔 기체. 실행 호스트가 pump 마다 [readNowFor] 로 바꾼다. */
    private var readNow: Set<String> = emptySet()

    /** 이번 pump 에서 데워져 있어도 지금 읽을 기체. 호스트 잠금 아래에서 `Middleware.pump` 앞에 부른다. */
    fun readNowFor(robotIds: Set<String>) {
        readNow = robotIds
    }

    override fun prefetched(robotId: String): PrefetchedRead {
        direct.remove(robotId)
        val read = prefetching.prefetched(robotId)
        if (read != PrefetchedRead.Unread && robotId !in readNow) return read
        direct.add(robotId)
        val sentAt = clock.now()
        return try {
            PrefetchedRead.Read(delegate.snapshot(robotId), sentAt, clock.now())
        } catch (e: RuntimeException) {
            PrefetchedRead.Failed(e)
        }
    }

    override fun replay(robotId: String, from: Long): Replay? =
        if (direct.remove(robotId)) delegate.replay(robotId, from) else prefetching.replay(robotId, from)

    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)

    override fun capabilities(robotId: String): Capability? = prefetching.capabilities(robotId) ?: delegate.capabilities(robotId)

    override fun capabilitiesPending(robotId: String): Boolean = false

    override fun close() {
        prefetching.close()
        issuing.close()
    }
}
