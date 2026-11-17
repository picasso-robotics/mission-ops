package dev.picasso.ops.host

import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.cell.SiteFenceConfirmer
import dev.picasso.ops.host.lease.LeaderState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 실행 호스트의 셀 면 세대 배선(HA 3단계 PR B1) — 인수 확인 스레드, 셀 읽기 응답만으로의 밀려남, 신호 쓰기·결함 주입 중계의 409. 셀 대역은 [HostBench] 의
 * 대역이다(현장 쪽 대조는 현장 시험과 통합 시험이 본다).
 */
class HttpEpochHostTest {

    private fun eventually(what: String, within: Duration = Duration.ofSeconds(10), check: () -> Boolean) {
        val deadline = Instant.now().plus(within)
        while (!check()) {
            check(Instant.now().isBefore(deadline)) { "$within 안에 $what 이(가) 되지 않았다" }
            Thread.sleep(20)
        }
    }

    private fun HostBench.confirms(): List<String?> = cellEpochs.filter { it.first == CellBandClient.CONFIRM_PATH }.map { it.second }

    @Test
    fun `셀 인수 확인은 리스 획득 직후 호스트 잠금 밖에서 리스 세대를 싣고 나가고 간격마다 다시 나가며 그동안 pump 와 잠금을 쓰는 조회가 돈다`() {
        HostBench(extraArgs = listOf("--host.fence-confirm.interval=PT0.5S", "--host.fence-confirm.retry=PT0.5S"), concurrentCell = true).use { bench ->
            bench.awaitLeader(LeaderState.ISSUING)
            val generation = bench.leadership.controllerEpoch()
            assertTrue(generation > 0)
            eventually("획득 뒤 첫 셀 인수 확인") { bench.confirms().isNotEmpty() }
            assertEquals(generation.toString(), bench.confirms().first())
            // 대역이 요청을 적은 뒤 확인기가 응답을 처리해 남기기까지 틈이 있다 — 그 결과를 기다린다.
            eventually("첫 셀 인수 확인 결과 200") { bench.bean(SiteFenceConfirmer::class.java).last(SiteFenceConfirmer.CELL)?.status == 200 }

            // 다음 확인을 대역 안에서 붙잡는다 — 그동안 pump(셀 읽기)와 호스트 잠금을 쓰는 조회가 막히지 않는다.
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            bench.onCellConfirm = {
                bench.onCellConfirm = null
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "간격 뒤 다시 확인하지 않았다")
                val reads = bench.cellEpochs.count { it.first == "/cell" }
                assertEquals(200, bench.fetch("/host/executions/summary").status)
                eventually("확인이 막힌 동안의 pump 셀 읽기") { bench.cellEpochs.count { it.first == "/cell" } > reads + 2 }
            } finally {
                release.countDown()
            }
            eventually("간격마다 다시 확인") { bench.confirms().size >= 3 }
            assertTrue(bench.confirms().all { it == generation.toString() }, bench.confirms().toString())
            assertTrue(bench.cellEpochs.filter { it.first == "/cell" }.all { it.second == generation.toString() }, "셀 읽기도 리스 세대를 싣는다")
            assertEquals(LeaderState.ISSUING, bench.leadership.state)
        }
    }

    @Test
    fun `밀려난 능동은 셀 읽기 응답의 바닥값 헤더만으로 잃고 같은 바닥값은 잃음이 아니다`() {
        HostBench().use { bench ->
            bench.awaitLeader(LeaderState.ISSUING)
            val generation = bench.leadership.controllerEpoch()
            bench.cellFloor = generation
            val reads = bench.cellEpochs.count { it.first == "/cell" }
            eventually("같은 바닥값의 셀 읽기") { bench.cellEpochs.count { it.first == "/cell" } > reads + 2 }
            assertEquals(LeaderState.ISSUING, bench.leadership.state)

            bench.cellFloor = generation + 1
            bench.awaitLeader(LeaderState.LOST, Duration.ofSeconds(10))
            assertTrue(bench.leadership.lostReason!!.contains("robot=셀"), bench.leadership.lostReason)
            eventually("종료 훅") { bench.exits.isNotEmpty() }
        }
    }

    @Test
    fun `신호 쓰기 중계는 현장의 세대 거부 409 를 본문 그대로 돌려주며 잃고 엔진 거부 409 는 그대로 돌려주고 잃지 않는다`() {
        // 셀 인수 확인이 시험 중에 다시 나가지 않게 간격을 길게 둔다(그 응답의 바닥값으로 먼저 잃지 않게).
        HostBench(extraArgs = listOf("--host.fence-confirm.interval=PT1H", "--host.fence-confirm.retry=PT1H")).use { bench ->
            bench.awaitLeader(LeaderState.ISSUING)
            val generation = bench.leadership.controllerEpoch()

            val engine = """{"error":"FAULT_REFUSED","detail":"exclusive_control_required=false"}"""
            bench.faultReply = 409 to engine
            val refused = bench.post("/host/faults", """{"robotId":"quadruped-01","kind":"CONTROL_AUTHORITY_LOST"}""")
            assertEquals(409, refused.status)
            assertEquals(HostBench.JSON.readTree(engine), refused.body)
            assertEquals(LeaderState.ISSUING, bench.leadership.state)

            val stale = """{"error":"STALE_CONTROLLER","reason":"BELOW_FLOOR","requestedControllerEpoch":"$generation",""" +
                """"controllerEpochFloor":"${generation + 1}","detail":"다른 제어자가 넘겨받았다"}"""
            bench.signalReply = 409 to stale
            bench.signalFloor = generation + 1
            val relayed = bench.post("/host/cell/signals/lot_code", """{"value":"LOT-X"}""")
            assertEquals(409, relayed.status, "«현장이 안 닿음»(503 CELL_SILENT)으로 접지 않는다")
            assertEquals(HostBench.JSON.readTree(stale), relayed.body)
            assertEquals(LeaderState.LOST, bench.leadership.state)
            assertTrue(bench.leadership.lostReason!!.contains("robot=셀"), bench.leadership.lostReason)
            assertEquals(generation.toString(), bench.cellEpochs.last { it.first == "/cell/signals/lot_code" }.second)
        }
    }
}
