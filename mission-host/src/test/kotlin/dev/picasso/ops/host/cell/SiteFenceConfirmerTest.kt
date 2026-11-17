package dev.picasso.ops.host.cell

import dev.picasso.ops.host.lease.ControllerConfirmation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 현장 HTTP 면 인수 확인 스레드의 간격 규칙([SiteFenceConfirmer]). 시계와 주기를 시험이 쥐고 [SiteFenceConfirmer.tick] 을 직접 부른다. */
class SiteFenceConfirmerTest {

    private var now = 0L

    private fun after(duration: Duration) {
        now += duration.toNanos()
    }

    private fun answer(floor: Long, status: String) = ControllerConfirmation(200, floor, status, "b", "")

    @Test
    fun `확정 전에는 재시도 간격으로 다시 부르고 확정 뒤에는 긴 간격이며 대조 안 함과 4xx 는 정착이고 세대가 바뀌면 곧바로 부른다`() {
        val replies = mutableListOf<ControllerConfirmation?>()
        var calls = 0
        var generation: Long? = null
        val confirmer = SiteFenceConfirmer(
            mapOf(SiteFenceConfirmer.CELL to { calls++; replies.removeAt(0) }),
            { generation },
            interval = Duration.ofSeconds(10),
            retry = Duration.ofSeconds(1),
            nanoTime = { now },
        )
        confirmer.tick()
        assertEquals(0, calls, "리스가 없으면 부르지 않는다")

        generation = 3
        replies += listOf(null, ControllerConfirmation(503, null, null, null, ""), answer(3, "DURABLE_PENDING"), answer(3, "DURABLE_CONFIRMED"))
        confirmer.tick()
        assertEquals(1, calls, "획득 직후 곧바로 부른다")
        after(Duration.ofMillis(500))
        confirmer.tick()
        assertEquals(1, calls)
        // 안 닿음·5xx·미확정은 재시도 간격(1초)마다 다시 — 긴 간격(10초)을 기다리지 않는다.
        repeat(3) {
            after(Duration.ofSeconds(1))
            confirmer.tick()
        }
        assertEquals(4, calls)
        assertEquals("DURABLE_CONFIRMED", confirmer.last(SiteFenceConfirmer.CELL)?.fenceStatus)
        // 확정 뒤에는 긴 간격.
        after(Duration.ofSeconds(9))
        confirmer.tick()
        assertEquals(4, calls)
        replies += answer(3, "UNSPECIFIED")
        after(Duration.ofSeconds(1))
        confirmer.tick()
        assertEquals(5, calls)
        // 현장이 대조하지 않는다고 답했으면 정착 — 재시도 간격으로 되풀이하지 않는다.
        after(Duration.ofSeconds(5))
        confirmer.tick()
        assertEquals(5, calls)
        // 세대가 바뀌면(다음 보유) 곧바로 부르고, 옛 현장의 404 도 정착이다.
        generation = 4
        replies += ControllerConfirmation(404, null, null, null, "")
        confirmer.tick()
        assertEquals(6, calls)
        after(Duration.ofSeconds(5))
        confirmer.tick()
        assertEquals(6, calls)
        // 잃으면(세대 없음) 부르지 않는다.
        generation = null
        after(Duration.ofSeconds(60))
        confirmer.tick()
        assertEquals(6, calls)
        assertEquals(6, confirmer.calls(SiteFenceConfirmer.CELL))
    }

    @Test
    fun `앞 대상의 답이 잃음을 일으키면 같은 주기의 남은 대상은 부르지 않는다`() {
        var generation: Long? = 5
        var fleetCalls = 0
        val confirmer = SiteFenceConfirmer(
            linkedMapOf(
                // 셀 확인의 답 머리말이 더 높은 바닥값을 알려 리더가 잃음으로 갔다(리스 세대가 사라짐).
                SiteFenceConfirmer.CELL to { generation = null; answer(6, "DURABLE_CONFIRMED") },
                SiteFenceConfirmer.FLEET to { fleetCalls++; answer(5, "DURABLE_CONFIRMED") },
            ),
            { generation },
            interval = Duration.ofSeconds(10),
            retry = Duration.ofSeconds(1),
            nanoTime = { now },
        )
        confirmer.tick()
        assertEquals(1 to 0, confirmer.calls(SiteFenceConfirmer.CELL) to fleetCalls)
        after(Duration.ofSeconds(60))
        confirmer.tick()
        assertEquals(1 to 0, confirmer.calls(SiteFenceConfirmer.CELL) to fleetCalls)
    }

    @Test
    fun `대상마다 따로 세고 한 대상의 예외가 다른 대상을 막지 않으며 재시도 간격은 간격보다 길 수 없다`() {
        var fleetCalls = 0
        val confirmer = SiteFenceConfirmer(
            linkedMapOf(
                SiteFenceConfirmer.CELL to { throw IllegalStateException("셀 대역 오류") },
                SiteFenceConfirmer.FLEET to { fleetCalls++; answer(2, "DURABLE_CONFIRMED") },
            ),
            { 2L },
            interval = Duration.ofSeconds(10),
            retry = Duration.ofSeconds(1),
            nanoTime = { now },
        )
        confirmer.tick()
        assertEquals(1 to 1, confirmer.calls(SiteFenceConfirmer.CELL) to fleetCalls)
        after(Duration.ofSeconds(1))
        confirmer.tick()
        assertEquals(2 to 1, confirmer.calls(SiteFenceConfirmer.CELL) to fleetCalls, "실패한 셀은 재시도, 확정한 플릿은 긴 간격")
        assertFailsWith<IllegalArgumentException> {
            SiteFenceConfirmer(emptyMap(), { 1L }, interval = Duration.ofSeconds(1), retry = Duration.ofSeconds(2))
        }
    }
}
