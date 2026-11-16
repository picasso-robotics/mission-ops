package dev.picasso.ops.host.lease

import java.time.Duration

/**
 * 리스와 심장박동의 시간 폭(HA 설계 기록 §4.5). 설정 키는 `host.lease.period`·`host.lease.expiry`·`host.lease.margin`·
 * `host.lease.idle-in-transaction-timeout`·`host.lease.statement-timeout` 이다. 심장박동(`mission.host_instance`, 진단용)도 같은 주기·만료로 돈다.
 *
 * - [period]: 갱신(보유자)·획득 시도(대기)·심장박동 주기.
 * - [expiry]: 갱신 한 번이 리스를 늘리는 폭(DB 시각). 주기의 세 배 이상이어야 한다(§4.5 의 하한식에서 주기 몫).
 * - [margin]: 로컬 자기 차단의 여유. 보유자는 갱신 요청을 보내기 직전의 단조 시각 + [expiry] − [margin] 을 지나면 DB 가 아직 만료하지
 *   않았어도 송신을 멈추고 리스를 잃은 것으로 다룬다. 갱신이 한 번 늦어도 자기 차단에 걸리지 않게 [expiry] − [margin] 은 주기의 두 배보다
 *   길어야 한다.
 * - [idleInTransaction]: 공용 풀 연결의 세션 `idle_in_transaction_session_timeout`(연결을 열 때 세움 — 잠그기 전부터 걸림). 리스 보유자
 *   행을 `FOR SHARE` 로 쥔 채 멎은 프로세스의 세션을 DB 가 끊어 대기 인스턴스의 획득이 끝없이 막히지 않게 한다. [expiry] 보다 짧아야 한다.
 * - [statementTimeout]: 공용 풀 연결의 세션 `statement_timeout`. 클라이언트가 멎은 채 서버 쪽에서 돌던(유휴가 아닌) 문장도 끊는다. pump 의
 *   가장 긴 트랜잭션 문장보다 넉넉히, [expiry] 보다 짧게. 기본은 만료 − 여유.
 *
 * **30초 만료는 30초 인수가 아니다.** 비정상 종료 뒤 인수 공백은 만료 폭 + 획득 주기 + 재대조 시간이고, 만료 인수는 운영자 확인 전까지 신규
 * 발행을 열지 않는다.
 */
data class LeaseTiming(
    val period: Duration,
    val expiry: Duration,
    val margin: Duration,
    val idleInTransaction: Duration = DEFAULT_IDLE_IN_TRANSACTION,
    val statementTimeout: Duration = expiry.minus(margin),
) {
    init {
        require(!period.isNegative && !period.isZero) { "리스 주기는 양수여야 한다: $period" }
        require(expiry >= period.multipliedBy(3)) { "리스 만료($expiry)는 주기($period)의 세 배 이상이어야 한다" }
        require(!margin.isNegative && !margin.isZero) { "리스 자기 차단 여유는 양수여야 한다: $margin" }
        require(expiry.minus(margin) > period.multipliedBy(2)) { "리스 만료($expiry) − 여유($margin)는 주기($period)의 두 배보다 길어야 한다" }
        require(!idleInTransaction.isNegative && !idleInTransaction.isZero && idleInTransaction < expiry) {
            "쓰기 트랜잭션 유휴 제한($idleInTransaction)은 양수이고 리스 만료($expiry)보다 짧아야 한다"
        }
        require(!statementTimeout.isNegative && !statementTimeout.isZero && statementTimeout < expiry) {
            "풀 연결 문장 제한($statementTimeout)은 양수이고 리스 만료($expiry)보다 짧아야 한다"
        }
    }

    companion object {
        val DEFAULT_IDLE_IN_TRANSACTION: Duration = Duration.ofSeconds(10)
        val DEFAULT = LeaseTiming(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5))
    }
}
