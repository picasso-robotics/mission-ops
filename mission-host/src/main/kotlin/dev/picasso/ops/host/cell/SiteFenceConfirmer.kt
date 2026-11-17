package dev.picasso.ops.host.cell

import dev.picasso.ops.host.lease.ControllerConfirmation
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 현장 HTTP 면(셀·플릿) 인수 확인을 부르는 전용 스레드(HA 3단계 PR B1). 리스를 쥔 동안(재대조·관측·발행) **호스트 잠금 밖에서** 대상마다 획득 직후 한 번
 * 부른다 — 현장이 그 키의 바닥값을 이 세대로 올려, 그 뒤로 옛 세대의 늦은 셀 신호 쓰기·결함 주입·플릿 맡기기·취소를 거절하게 한다(새 세대의 첫 변경을
 * 기다리지 않는다). 실행 준비의 조건이 아니다 — 셀 쓰기·결함 주입은 여전히 전역 발행 중에만 나가고, 플릿 확인의 결과도 플릿 준비(관측 중 자동 재개)에
 * 쓰지 않는다(다음 단계).
 *
 * 간격은 둘이다. 이 세대에서 답이 «정착»(아래)하기 전에는 [retry] 마다, 정착한 뒤에는 [interval] 마다 다시 부른다.
 * - 정착: 바닥값 = 이 세대 ∧ `DURABLE_CONFIRMED`(확정), 또는 현장이 이 면을 대조하지 않는다고 답했다(200 `UNSPECIFIED`·`VOLATILE`, 옛 현장의 4xx) —
 *   짧은 간격으로 되풀이해도 바뀌지 않을 답이다.
 * - 정착 아님(짧은 간격으로 다시): 안 닿음·5xx·`DURABLE_PENDING`·그 밖. 그동안은 바닥값이 오르지 않은 틈이다.
 *
 * 확인 응답의 바닥값이 이 세대보다 높으면 클라이언트의 세대 판정이 리더를 잃음으로 넘긴다(밀려남). 잃으면 [generation] 이 `null` 이라 더 부르지 않는다.
 *
 * @param targets 대상 이름(`셀`·`플릿`) → 확인 호출. 플릿 포트가 없으면 셀만이다.
 * @param generation 리스를 쥐었으면 그 세대, 아니면 `null`(운영 배선은 `HostLeadership.claim()`).
 * @param interval 정착 뒤 다시 부르는 간격(`host.fence-confirm.interval`, 기본 10초).
 * @param retry 정착 전 다시 부르는 간격(`host.fence-confirm.retry`, 기본 1초). [interval] 보다 길 수 없다.
 * @param poll 리스 상태를 보는 주기. 획득 뒤 첫 확인이 이만큼 안에 나간다.
 */
class SiteFenceConfirmer(
    private val targets: Map<String, () -> ControllerConfirmation?>,
    private val generation: () -> Long?,
    private val interval: Duration,
    private val retry: Duration = DEFAULT_RETRY,
    private val poll: Duration = POLL,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {

    init {
        require(!interval.isNegative && !interval.isZero) { "인수 확인 간격(host.fence-confirm.interval)은 0 보다 커야 한다: $interval" }
        require(!retry.isNegative && !retry.isZero && retry <= interval) {
            "인수 확인 재시도 간격(host.fence-confirm.retry)은 0 보다 크고 간격($interval) 이하여야 한다: $retry"
        }
    }

    private class Target(val name: String, val call: () -> ControllerConfirmation?) {
        var generation: Long? = null
        var calledAt = 0L
        var settled = false

        @Volatile
        var last: ControllerConfirmation? = null

        @Volatile
        var calls = 0
        var lastWarned: String? = null
    }

    private val state = targets.map { (name, call) -> Target(name, call) }

    private val thread = Executors.newSingleThreadScheduledExecutor { Thread(it, "mission-host-fence-confirm").apply { isDaemon = true } }

    /** 대상의 마지막 확인 답(시험·진단). 안 닿았으면 `null`. */
    fun last(target: String): ControllerConfirmation? = state.firstOrNull { it.name == target }?.last

    /** 대상에 지금까지 보낸 확인 수(시험). */
    fun calls(target: String): Int = state.firstOrNull { it.name == target }?.calls ?: 0

    fun start(): SiteFenceConfirmer = apply {
        thread.scheduleWithFixedDelay(::tick, 0, poll.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** 한 번 본다. 예외를 삼킨다(실행기가 다음 주기를 멈추지 않게). */
    internal fun tick() {
        val held = try {
            generation()
        } catch (e: Exception) {
            null
        } ?: return
        for (target in state) {
            // 대상마다 호출 직전에 보유를 다시 본다 — 앞 대상의 답이 밀려남(잃음)을 일으켰으면 남은 대상은 부르지 않는다.
            val still = try {
                generation()
            } catch (e: Exception) {
                null
            }
            if (still != held) return
            try {
                visit(target, held)
            } catch (e: Exception) {
                target.settled = false
                warnOnce(target, "${target.name} 인수 확인 실패: $e")
            }
        }
    }

    private fun visit(target: Target, held: Long) {
        val now = nanoTime()
        if (target.generation == held) {
            val wait = if (target.settled) interval else retry
            if (now - target.calledAt < wait.toNanos()) return
        }
        target.generation = held
        target.calledAt = now
        target.settled = false
        target.calls++
        val answer = target.call()
        target.last = answer
        target.settled = settled(held, answer)
        note(target, held, answer)
    }

    private fun settled(held: Long, answer: ControllerConfirmation?): Boolean = when {
        answer == null -> false
        answer.status == 200 -> answer.fenceStatus == CONFIRMED && answer.floor == held || answer.fenceStatus in NOT_FENCING
        else -> answer.status in 400..499
    }

    private fun note(target: Target, held: Long, answer: ControllerConfirmation?) {
        when {
            answer == null -> warnOnce(target, "${target.name} 인수 확인: 현장이 답하지 않는다(세대 $held)")
            answer.status != 200 -> warnOnce(target, "${target.name} 인수 확인: HTTP ${answer.status} (세대 $held) ${answer.body}")
            answer.fenceStatus == CONFIRMED && answer.floor == held -> {
                if (target.lastWarned != null) log.info("{} 인수 확인: 세대 {} 바닥값 {} {}", target.name, held, answer.floor, answer.fenceStatus)
                target.lastWarned = null
            }
            else -> warnOnce(target, "${target.name} 인수 확인: 세대 $held 바닥값 ${answer.floor} ${answer.fenceStatus} — 바닥값이 이 세대로 확정되지 않았다")
        }
    }

    /** 같은 문구는 한 번만 경고한다(간격마다 되풀이되는 옛 현장 404 따위). */
    private fun warnOnce(target: Target, text: String) {
        val key = text.substringBefore(" (")
        if (key == target.lastWarned) return
        target.lastWarned = key
        log.warn(text)
    }

    override fun close() {
        thread.shutdownNow()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SiteFenceConfirmer::class.java)

        /** [poll] 의 기본값. */
        val POLL: Duration = Duration.ofMillis(100)

        /** [retry] 의 기본값. */
        val DEFAULT_RETRY: Duration = Duration.ofSeconds(1)

        const val CELL = "셀"
        const val FLEET = "플릿"

        private const val CONFIRMED = "DURABLE_CONFIRMED"

        /** 현장이 이 면을 대조하지 않는다는 답 — 짧은 간격으로 되풀이해도 바뀌지 않는다. */
        private val NOT_FENCING = setOf("UNSPECIFIED", "VOLATILE")
    }
}
