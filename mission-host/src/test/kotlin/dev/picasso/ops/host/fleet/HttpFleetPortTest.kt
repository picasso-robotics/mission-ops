package dev.picasso.ops.host.fleet

import com.sun.net.httpserver.HttpServer
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.DispatchIssue
import dev.picasso.middleware.FleetRead
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.ops.host.HostClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실행 호스트의 플릿 HTTP 포트([HttpFleetPort])와 데코레이터 배선([HostFleetPort]). 현장 플릿 면(`/fleet`)의 모양만 내는 루프백 대역을 띄운다 —
 * 현장 쪽 모양 자체는 현장 시험(`SiteFleetTest`)이 본다.
 */
class HttpFleetPortTest {

    /** 경로마다 (상태 코드, 본문, 지연)을 내는 대역. 받은 요청(방법, 경로, 본문)을 든다. */
    private class Stub : AutoCloseable {
        val replies = mutableMapOf<String, Triple<Int, String, Duration>>()
        val seen = CopyOnWriteArrayList<Triple<String, String, String>>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/fleet") { exchange ->
                val key = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
                seen += Triple(exchange.requestMethod, key, exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                val (status, body, delay) = replies[key] ?: Triple(404, "", Duration.ZERO)
                if (!delay.isZero) Thread.sleep(delay.toMillis())
                val bytes = body.toByteArray()
                runCatching {
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                }
                exchange.close()
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    private val order = TransportOrder("JO-1#HU-1", "HU-1", "OUT-07", "LINE-IN-03")

    /** 아무도 듣지 않는 루프백 포트. */
    private fun deadPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `맡기기·찾기·상태·취소가 현장 플릿 면의 본문을 그대로 옮긴다`() {
        Stub().use { stub ->
            stub.replies["/fleet/transports"] = Triple(200, """{"handle":"tr-1"}""", Duration.ZERO)
            stub.replies["/fleet/transports?reference=JO-1%23HU-1"] = Triple(200, """{"handle":"tr-1"}""", Duration.ZERO)
            stub.replies["/fleet/transports?reference=JO-9%23X"] = Triple(200, """{"handle":null}""", Duration.ZERO)
            stub.replies["/fleet/transports/tr-1"] = Triple(200, """{"state":"PICKED_UP","observedContainer":"HU-1","holding":true,"detail":null}""", Duration.ZERO)
            stub.replies["/fleet/transports/tr-1/cancel"] = Triple(200, """{"accepted":true}""", Duration.ZERO)
            val port = HttpFleetPort(stub.url + "/")

            assertEquals(TransportHandle("tr-1"), port.dispatch(order))
            val (method, path, body) = stub.seen.single()
            assertEquals("POST" to "/fleet/transports", method to path)
            assertEquals("""{"reference":"JO-1#HU-1","containerId":"HU-1","source":"OUT-07","destination":"LINE-IN-03"}""", body)
            assertEquals(TransportHandle("tr-1"), port.find("JO-1#HU-1"))
            assertNull(port.find("JO-9#X"))
            val status = port.status(TransportHandle("tr-1"))
            assertEquals(listOf(TransportState.PICKED_UP, "HU-1", true), listOf(status.state, status.observedContainer, status.holding))
            assertEquals(true, port.cancel(TransportHandle("tr-1")))
            assertEquals(stub.url, port.url, "표시 주소는 끝 / 를 뗀다")
        }
    }

    @Test
    fun `모르는 핸들은 현장이 준 FAILED 모르는 운반을 그대로 옮기고 지어내지 않는다`() {
        Stub().use { stub ->
            stub.replies["/fleet/transports/tr-404"] = Triple(200, """{"state":"FAILED","observedContainer":null,"holding":false,"detail":"모르는 운반: tr-404"}""", Duration.ZERO)
            stub.replies["/fleet/transports/tr-404/cancel"] = Triple(200, """{"accepted":false}""", Duration.ZERO)
            val port = HttpFleetPort(stub.url)
            val status = port.status(TransportHandle("tr-404"))
            assertEquals(TransportState.FAILED to false, status.state to status.holding)
            assertEquals("모르는 운반: tr-404", status.detail)
            assertEquals(false, port.cancel(TransportHandle("tr-404")))
        }
    }

    @Test
    fun `연결이 서지 않거나 현장이 4xx 로 받지 않은 맡기기는 보내지 않음이고 5xx 와 틀린 본문은 미확정이다`() {
        val dead = HttpFleetPort("http://127.0.0.1:${deadPort()}")
        assertFailsWith<CommandNotSent> { dead.dispatch(order) }
        // 읽기와 취소는 가르지 않는다.
        assertFailsWith<FleetUnreachable> { dead.status(TransportHandle("tr-1")) }
        assertFailsWith<FleetUnreachable> { dead.find("JO-1#HU-1") }
        assertFailsWith<FleetUnreachable> { dead.cancel(TransportHandle("tr-1")) }
        Stub().use { stub ->
            val port = HttpFleetPort(stub.url)
            stub.replies["/fleet/transports"] = Triple(400, """{"error":"BAD_REQUEST","detail":"x"}""", Duration.ZERO)
            assertFailsWith<CommandNotSent> { port.dispatch(order) }
            stub.replies["/fleet/transports"] = Triple(500, "", Duration.ZERO)
            // 5xx 는 보냈는지 모름이다(IssuingFleetPort 가 미확정으로 읽는다).
            assertEquals(FleetUnreachable::class, assertFailsWith<RuntimeException> { port.dispatch(order) }::class)
            stub.replies["/fleet/transports"] = Triple(200, """{"no":"handle"}""", Duration.ZERO)
            assertFailsWith<FleetUnreachable> { port.dispatch(order) }
            stub.replies["/fleet/transports/tr-1"] = Triple(200, """{"state":"TELEPORTED","holding":false}""", Duration.ZERO)
            assertFailsWith<FleetUnreachable> { port.status(TransportHandle("tr-1")) }
        }
        assertFailsWith<IllegalArgumentException> { HttpFleetPort("127.0.0.1:8083") }
    }

    @Test
    fun `주소에 fleet 경로를 붙이거나 탐침이 4xx 인 곳이면 기동을 멈추고 안 닿거나 5xx 인 곳은 경고만 하고 뜬다`() {
        val clock = HostClock { Instant.now() }
        listOf("http://127.0.0.1:8784/fleet", "http://127.0.0.1:8784/fleet/").forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { HttpFleetPort(url) }
        }
        Stub().use { stub ->
            // 플릿 면이 없는 서버(다른 서비스, 옛 현장) — 모든 경로가 404 다.
            assertEquals(404, HttpFleetPort(stub.url).probe())
            assertFailsWith<IllegalArgumentException> { dev.picasso.ops.host.MissionHostApplication.fleetPortOf(stub.url, clock) }
            stub.replies["/fleet/world"] = Triple(405, "", Duration.ZERO)
            assertFailsWith<IllegalArgumentException> { dev.picasso.ops.host.MissionHostApplication.fleetPortOf(stub.url, clock) }
            // 5xx 는 일시 장애로 보아 뜬다(안 닿음과 같음).
            stub.replies["/fleet/world"] = Triple(503, "", Duration.ZERO)
            assertIs<HostFleetPort>(dev.picasso.ops.host.MissionHostApplication.fleetPortOf(stub.url, clock)).close()
            stub.replies["/fleet/world"] = Triple(200, """{"places":[],"blocked":[],"dispatches":0}""", Duration.ZERO)
            assertIs<HostFleetPort>(dev.picasso.ops.host.MissionHostApplication.fleetPortOf(stub.url, clock)).close()
        }
        val dead = "http://127.0.0.1:${deadPort()}"
        assertNull(HttpFleetPort(dead).probe())
        assertIs<HostFleetPort>(dev.picasso.ops.host.MissionHostApplication.fleetPortOf(dead, clock)).close()
    }

    @Test
    fun `요청 제한을 넘긴 맡기기는 보냈으나 답을 못 받은 미확정이고 읽기는 기한 안에 실패로 끝난다`() {
        Stub().use { stub ->
            stub.replies["/fleet/transports"] = Triple(200, """{"handle":"tr-1"}""", Duration.ofMillis(1500))
            stub.replies["/fleet/transports/tr-1"] = Triple(200, """{"state":"ACCEPTED","holding":false}""", Duration.ofMillis(1500))
            val port = HttpFleetPort(stub.url, timeout = Duration.ofMillis(300))
            val started = Instant.now()
            // 보낸 뒤의 시간 초과를 «보내지 않음»(CommandNotSent)으로 접지 않는다.
            assertEquals(FleetUnreachable::class, assertFailsWith<RuntimeException> { port.dispatch(order) }::class)
            assertFailsWith<FleetUnreachable> { port.status(TransportHandle("tr-1")) }
            assertTrue(Duration.between(started, Instant.now()) < Duration.ofMillis(2500), "요청 제한에서 끊는다")
        }
    }

    @Test
    fun `HostFleetPort 는 맡기기를 잠금 밖에서 보내고 상태를 미리 읽으며 보내지 못한 맡기기를 NotSent 로 가른다`() {
        Stub().use { stub ->
            stub.replies["/fleet/transports"] = Triple(200, """{"handle":"tr-1"}""", Duration.ZERO)
            stub.replies["/fleet/transports/tr-1"] = Triple(200, """{"state":"IN_TRANSIT","observedContainer":"HU-1","holding":true}""", Duration.ZERO)
            val clock = HostClock { Instant.now() }
            HostFleetPort(HttpFleetPort(stub.url), clock, stub.url).use { port ->
                assertEquals(DispatchIssue.Pending, port.issueDispatch(order))
                val answered = eventually { port.issueDispatch(order).takeIf { it !is DispatchIssue.Pending } }
                assertEquals(TransportHandle("tr-1"), assertIs<DispatchIssue.Answered>(answered).handle)
                assertEquals(FleetRead.Pending, port.prefetchedStatus(TransportHandle("tr-1")))
                val read = eventually { port.prefetchedStatus(TransportHandle("tr-1")).takeIf { it is FleetRead.Read } }
                assertEquals(TransportState.IN_TRANSIT, assertIs<FleetRead.Read<dev.picasso.middleware.TransportStatus>>(read).value.state)
                assertEquals(stub.url, port.url)
            }
        }
        HostFleetPort(HttpFleetPort("http://127.0.0.1:${deadPort()}"), HostClock { Instant.now() }, "x").use { port ->
            port.issueDispatch(order)
            assertIs<DispatchIssue.NotSent>(eventually { port.issueDispatch(order).takeIf { it !is DispatchIssue.Pending } })
        }
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            read()?.let { return it }
            check(Instant.now().isBefore(deadline)) { "5초 안에 답이 오지 않았다" }
            Thread.sleep(20)
        }
    }
}
