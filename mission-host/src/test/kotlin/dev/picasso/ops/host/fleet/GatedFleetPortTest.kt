package dev.picasso.ops.host.fleet

import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.FleetCancelIssue
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.lease.GatedFleetPort
import dev.picasso.ops.host.lease.SendAuthority
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * 플릿 송신 직전 확인의 핸들 → 참조 표(HA 3단계 PR B2). 참조 없는 취소(`cancel(handle)`)는 표로 그 실행을 되짚는데, 표에서 축출된 운반이라도 바깥에서 참조와
 * 함께 받은 취소는 그 송신까지 참조가 고정돼 관측 중에 나간다.
 */
class GatedFleetPortTest {

    /** 관측 중 — 전역 발행은 닫혀 있고 운반 참조 `JO-H#HU-1` 의 실행만 준비다. */
    private val authority = object : SendAuthority {
        override fun mayIssue(): Boolean = false
        override fun mayDispatch(reference: String): Boolean = reference == REF
    }

    @Test
    fun `표에서 축출된 운반이라도 참조와 함께 받은 취소는 관측 중에 나간다`() {
        val mimic = AmrFleetMimic().apply { place("OUT", "HU-1"); listOf("A", "B", "C").forEach { place("S-$it", "C-$it") } }
        val gate = GatedFleetPort(mimic, authority, capacity = 2)
        val handle = checkNotNull(gate.dispatch(TransportOrder(REF, "HU-1", "OUT", "IN")))
        // 다른 운반 셋을 배워 H 의 줄을 축출한다.
        listOf("A", "B", "C").forEach { mimic.dispatch(TransportOrder("JO-$it#U", "C-$it", "S-$it", "IN")); gate.find("JO-$it#U") }
        // 참조 없는 취소는 축출된 H 를 되짚지 못해 전역 발행만 본다 — 막힌다.
        assertFailsWith<CommandNotSent> { gate.cancel(handle) }

        HostFleetPort(gate, HostClock { Instant.now() }, "test://fleet").use { host ->
            host.onCancelReference = gate::remember
            host.onCancelSettled = gate::release
            assertEquals(FleetCancelIssue.Pending, host.issueCancel(REF, handle))
            val answered = eventually { host.issueCancel(REF, handle).takeIf { it != FleetCancelIssue.Pending } }
            assertEquals(true, assertIs<FleetCancelIssue.Answered>(answered).accepted)
            assertEquals(TransportState.CANCELLED, mimic.status(handle).state)
            // 결과를 받으면 고정이 풀린다 — 같은 취소를 되풀이해도(결과만 받는 재호출 포함) 남지 않는다.
            host.onCancelSettled = gate::release
            assertEquals(0, gate.pinnedCount)
            repeat(3) {
                host.issueCancel(REF, handle)
                val settled: FleetCancelIssue = eventually { host.issueCancel(REF, handle).takeIf { it != FleetCancelIssue.Pending } }
                assertIs<FleetCancelIssue.Answered>(settled)
            }
            assertEquals(0, gate.pinnedCount, "끝난 취소의 고정 참조가 남았다")
        }
    }

    @Test
    fun `고정한 취소는 표 상한과 무관하게 다른 취소가 많이 고정돼도 축출되지 않아 관측 중에 나간다`() {
        val mimic = AmrFleetMimic().apply { place("OUT", "HU-1"); listOf("A", "B", "C").forEach { place("S-$it", "C-$it") } }
        val gate = GatedFleetPort(mimic, authority, capacity = 2)
        val handle = checkNotNull(gate.dispatch(TransportOrder(REF, "HU-1", "OUT", "IN")))
        gate.remember(handle, REF)
        // 핸들 → 참조 표에서는 H 를 축출하고, 아직 송신하지 않은 H 뒤로 상한을 훨씬 넘는 다른 취소를 고정한다.
        listOf("A", "B", "C").forEach { mimic.dispatch(TransportOrder("JO-$it#U", "C-$it", "S-$it", "IN")); gate.find("JO-$it#U") }
        repeat(50) { gate.remember(dev.picasso.middleware.TransportHandle("other-$it"), "JO-$it#U") }
        assertEquals(51, gate.pinnedCount)
        // 잠금 밖 송신 스레드의 참조 없는 취소 — H 의 고정이 남아 그 실행으로 되짚어 나간다.
        assertEquals(true, gate.cancel(handle))
        assertEquals(TransportState.CANCELLED, mimic.status(handle).state)
        gate.release(handle)
        assertEquals(50, gate.pinnedCount)
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            read()?.let { return it }
            check(Instant.now().isBefore(deadline)) { "5초 안에 답이 오지 않았다" }
            Thread.sleep(20)
        }
    }

    private companion object {
        const val REF = "JO-H#HU-1"
    }
}
