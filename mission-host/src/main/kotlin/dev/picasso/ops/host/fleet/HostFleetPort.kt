package dev.picasso.ops.host.fleet

import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.IssuingFleetPort
import dev.picasso.middleware.PrefetchingFleetPort
import dev.picasso.ops.host.HostClock

/**
 * 실행 호스트의 플릿 포트: [delegate] 를 [IssuingFleetPort] 로, 그것을 다시 [PrefetchingFleetPort] 로 감싼 것(picasso §15.237). 기체 포트
 * ([dev.picasso.ops.host.HostRobotPort])와 대칭이다 — 맡기기·취소는 보내는 쪽이 호스트 잠금 밖에서 하고, pump 의 운반 상태·찾기는 미리 읽은
 * 값을 받는다. 미리 읽은 값은 받은 지 `maxAge`(기본 2초, 기체 포트와 같은 기한 예산) 안에서만 쓴다. 두 데코레이터의 시계는 미들웨어와 같은
 * [HostClock] 이다.
 *
 * 닫으면 두 데코레이터의 스레드를 내린다(위임 포트는 닫지 않는다).
 *
 * @param url 표시용 주소. 호스트 상태 응답·개요 화면의 «플릿 연결» 에 나간다.
 * @param confirm 현장 플릿 면 인수 확인([HttpFleetPort.confirmController]). 리스를 쥔 동안 확인 스레드가 부른다. 없으면 `null`.
 */
class HostFleetPort private constructor(
    val url: String,
    private val issuing: IssuingFleetPort,
    private val prefetching: PrefetchingFleetPort,
    val confirm: (() -> dev.picasso.ops.host.lease.ControllerConfirmation?)?,
) : AmrFleetPort by prefetching, AutoCloseable {

    constructor(delegate: AmrFleetPort, clock: HostClock, url: String, confirm: (() -> dev.picasso.ops.host.lease.ControllerConfirmation?)? = null) :
        this(url, IssuingFleetPort(delegate, now = clock::now), clock, confirm)

    private constructor(url: String, issuing: IssuingFleetPort, clock: HostClock, confirm: (() -> dev.picasso.ops.host.lease.ControllerConfirmation?)?) :
        this(url, issuing, PrefetchingFleetPort(issuing, now = clock::now), confirm)

    override fun close() {
        prefetching.close()
        issuing.close()
    }
}
