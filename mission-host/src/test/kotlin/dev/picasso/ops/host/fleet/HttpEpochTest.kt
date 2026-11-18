package dev.picasso.ops.host.fleet

import com.sun.net.httpserver.HttpServer
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.FleetCancelIssue
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.lease.HttpEpoch
import dev.picasso.ops.host.lease.SendAuthority
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 현장 HTTP 면(플릿·셀)에 싣는 제어자 세대와 밀려남 판정(HA 3단계 PR B1). 송신 쪽([HttpFleetPort]·[CellBandClient], [HttpEpoch])만 본다 — 현장 쪽 대조는
 * 현장 시험(`SiteHttpFenceTest`)이 본다. 대역은 경로마다 (상태 코드, 본문, 바닥값 헤더)를 내고 받은 요청의 세대 헤더를 든다.
 */
class HttpEpochTest {

    private class Stub : AutoCloseable {
        /** 경로(+질의) → (상태 코드, 본문, 응답 바닥값 헤더 — 널이면 싣지 않음). */
        val replies = mutableMapOf<String, Triple<Int, String, Long?>>()

        /** 경로 → 답하기 전 지연(밀리초). */
        val delays = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** 받은 요청(방법, 경로, 세대 헤더). */
        val seen = CopyOnWriteArrayList<Triple<String, String, String?>>()

        private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val key = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
                exchange.requestBody.readAllBytes()
                seen += Triple(exchange.requestMethod, key, exchange.requestHeaders.getFirst(HttpEpoch.EPOCH_HEADER))
                val (status, body, floor) = replies[key] ?: Triple(404, "", null)
                delays[key]?.let { Thread.sleep(it) }
                floor?.let { exchange.responseHeaders.add(HttpEpoch.FLOOR_HEADER, it.toString()) }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                exchange.close()
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    @Test
    fun `인수 확인은 현장이 앞 세대 변경을 기다린 뒤 내는 DURABLE_PENDING 을 시간 초과로 놓치지 않는다`() {
        Stub().use { stub ->
            val pending = """{"controllerEpochFloor":"7","fenceStatus":"DURABLE_PENDING","receiverBootId":"b"}"""
            // 현장은 앞 세대로 통과한 변경을 1초(SiteHttpFence.CONFIRM_WAIT)까지 기다린 뒤 답한다 — 플릿 포트의 보통 요청 제한(1초)보다 길다.
            listOf(HttpFleetPort.CONFIRM_PATH, CellBandClient.CONFIRM_PATH).forEach { path ->
                stub.replies[path] = Triple(200, pending, 7)
                stub.delays[path] = 1_300
            }
            val fleet = assertNotNull(HttpFleetPort(stub.url, epoch = epoch("플릿") { 7 }).confirmController(), "플릿 확인이 시간 초과로 끝났다")
            val cell = assertNotNull(CellBandClient(stub.url, epoch = epoch("셀") { 7 }).confirmController(), "셀 확인이 시간 초과로 끝났다")
            assertEquals(listOf("DURABLE_PENDING", "DURABLE_PENDING"), listOf(fleet.fenceStatus, cell.fenceStatus))
            assertTrue(HttpEpoch.CONFIRM_TIMEOUT >= java.time.Duration.ofSeconds(3), "현장 대기(1초) + 2초 이상")
        }
    }

    /** 밀려남 기록(수신자, 내 세대, 바닥값). */
    private val superseded = CopyOnWriteArrayList<Triple<String, Long, Long>>()

    private fun epoch(receiver: String, supply: () -> Long) = HttpEpoch(receiver, supply) { r, mine, floor -> superseded += Triple(r, mine, floor) }

    private val order = TransportOrder("JO-1#HU-1", "HU-1", "OUT-07", "LINE-IN-03")
    private val handle = TransportHandle("b:tr-1")

    private fun stale(requested: Long, floor: Long, reason: String = "BELOW_FLOOR") =
        """{"error":"STALE_CONTROLLER","reason":"$reason","requestedControllerEpoch":"$requested","controllerEpochFloor":"$floor","detail":"x"}"""

    @Test
    fun `요청마다 리스 세대를 싣고 처음 본 0 아닌 세대를 붙잡아 잃은 뒤에는 변경을 보내지 않고 읽기는 붙잡은 세대로 보낸다`() {
        Stub().use { stub ->
            stub.replies["/fleet/transports?reference=JO-1%23HU-1"] = Triple(200, """{"handle":null}""", null)
            stub.replies["/fleet/transports"] = Triple(200, """{"handle":"b:tr-1"}""", null)
            stub.replies["/fleet/transports/b%3Atr-1"] = Triple(200, """{"state":"ACCEPTED","holding":false}""", null)
            stub.replies["/cell"] = Triple(200, """{"presentations":[],"slots":[]}""", null)
            val supply = AtomicLong(0)
            val port = HttpFleetPort(stub.url, epoch = epoch("플릿", supply::get))
            val cell = CellBandClient(stub.url, epoch = epoch("셀", supply::get))

            port.find("JO-1#HU-1")
            supply.set(7)
            port.dispatch(order)
            port.status(handle)
            assertNotNull(cell.fetch())
            assertEquals(listOf("0", "7", "7", "7"), stub.seen.map { it.third })

            // 잃었다(공급 0). 읽기는 붙잡은 7 을 싣고 가고, 변경은 선로에 나가지 않는다.
            supply.set(0)
            stub.seen.clear()
            port.status(handle)
            assertNotNull(cell.fetch())
            val dispatch = assertFailsWith<CommandNotSent> { port.dispatch(order) }
            assertTrue(dispatch.message!!.startsWith(SendAuthority.NOT_ISSUING), dispatch.message)
            assertFailsWith<CommandNotSent> { port.cancel(handle) }
            assertFailsWith<CommandNotSent> { cell.writeSignal("lot_code", """{"value":"X"}""".toByteArray()) }
            assertFailsWith<CommandNotSent> { cell.injectFault("{}".toByteArray()) }
            assertFailsWith<CommandNotSent> { cell.confirmController() }
            assertEquals(listOf("GET" to "7", "GET" to "7"), stub.seen.map { it.first to it.third })
            assertTrue(superseded.isEmpty())
        }
    }

    @Test
    fun `응답 바닥값이 실은 세대보다 높으면 읽기만으로도 밀려남이고 200 맡기기의 답은 그대로이며 세대 0 과 같거나 낮은 바닥값은 밀려남이 아니다`() {
        Stub().use { stub ->
            val port = HttpFleetPort(stub.url, epoch = epoch("플릿") { 7 })
            stub.replies["/fleet/transports/b%3Atr-1"] = Triple(200, """{"state":"IN_TRANSIT","observedContainer":"HU-1","holding":true}""", 8)
            assertEquals(TransportState.IN_TRANSIT, port.status(handle).state, "받은 읽기는 그대로 쓴다")
            assertEquals(listOf(Triple("플릿", 7L, 8L)), superseded.toList())

            stub.replies["/fleet/transports"] = Triple(200, """{"handle":"b:tr-1"}""", 9)
            assertEquals(handle, port.dispatch(order), "이미 받은 성공은 뒤집지 않는다")
            assertEquals(Triple("플릿", 7L, 9L), superseded.last())

            superseded.clear()
            stub.replies["/fleet/transports/b%3Atr-1"] = Triple(200, """{"state":"IN_TRANSIT","observedContainer":"HU-1","holding":true}""", 7)
            port.status(handle)
            stub.replies["/fleet/transports/b%3Atr-1"] = Triple(200, """{"state":"IN_TRANSIT","observedContainer":"HU-1","holding":true}""", null)
            port.status(handle)
            assertTrue(superseded.isEmpty(), "같은 바닥값·헤더 없음은 밀려남이 아니다")

            // 세대를 싣지 않은(0) 발신자의 거절은 그 명령만의 일이다 — 바닥값이 높아도 밀려남이 아니다.
            val unpinned = HttpFleetPort(stub.url, epoch = epoch("플릿") { 0 })
            stub.replies["/fleet/transports"] = Triple(409, stale(0, 5, "EPOCH_MISSING"), 5)
            assertFailsWith<CommandNotSent> { unpinned.dispatch(order) }
            assertTrue(superseded.isEmpty())
        }
    }

    @Test
    fun `세대 거부는 맡기기·취소면 보내지 않음이고 취소는 미들웨어에서 실패한 취소이며 읽기는 세대 거부 전용 예외이고 다른 409 는 세대 거부가 아니다`() {
        Stub().use { stub ->
            val port = HttpFleetPort(stub.url, epoch = epoch("플릿") { 7 })
            stub.replies["/fleet/transports"] = Triple(409, stale(7, 8), 8)
            assertFailsWith<CommandNotSent> { port.dispatch(order) }
            stub.replies["/fleet/transports/b%3Atr-1/cancel"] = Triple(409, stale(7, 8), 8)
            // 취소의 세대 거부는 «현장이 받지 않음» 이다 — 안 닿음(FleetUnreachable)도, 받지 않은 취소(false)도 아니다.
            assertEquals(CommandNotSent::class, assertFailsWith<RuntimeException> { port.cancel(handle) }::class)
            assertEquals(2, superseded.size, "409 응답도 바닥값 헤더로 밀려남을 본다")

            stub.replies["/fleet/transports/b%3Atr-1"] = Triple(409, stale(7, 8), 8)
            stub.replies["/fleet/transports?reference=JO-1%23HU-1"] = Triple(409, stale(7, 8), 8)
            assertFailsWith<FleetEpochRefused> { port.status(handle) }
            assertFailsWith<FleetEpochRefused> { port.find("JO-1#HU-1") }

            // 같은 409 라도 세대 거부가 아니면 예전 그대로다(취소·읽기는 안 닿음).
            stub.replies["/fleet/transports/b%3Atr-1/cancel"] = Triple(409, """{"error":"OTHER","detail":"x"}""", null)
            assertEquals(FleetUnreachable::class, assertFailsWith<RuntimeException> { port.cancel(handle) }::class)

            // 데코레이터 배선(잠금 밖 송신)에서 취소의 세대 거부는 실패한 취소(보내지 않음)다 — 미들웨어는 CANCEL_UNSENT 를 남기고 다시 보낸다.
            stub.replies["/fleet/transports/b%3Atr-1/cancel"] = Triple(409, stale(7, 8), 8)
            HostFleetPort(port, HostClock { Instant.now() }, stub.url).use { host ->
                assertEquals(FleetCancelIssue.Pending, host.issueCancel(order.reference, handle))
                val result = eventually { host.issueCancel(order.reference, handle).takeIf { it != FleetCancelIssue.Pending } }
                assertIs<CommandNotSent>(assertIs<FleetCancelIssue.Failed>(result).failure)
            }
        }
    }

    @Test
    fun `셀 중계는 밀려남을 본 뒤 현장의 409 본문을 그대로 넘기고 엔진 거부 409 는 밀려남이 아니다`() {
        Stub().use { stub ->
            val cell = CellBandClient(stub.url, epoch = epoch("셀") { 7 })
            stub.replies["/cell/signals/lot_code"] = Triple(409, stale(7, 8), 8)
            val relayed = assertNotNull(cell.writeSignal("lot_code", """{"value":"X"}""".toByteArray()), "세대 거부를 «현장이 안 닿음» 으로 접지 않는다")
            assertEquals(409 to stale(7, 8), relayed.status to relayed.body.toString(Charsets.UTF_8))
            assertEquals(listOf(Triple("셀", 7L, 8L)), superseded.toList())

            superseded.clear()
            stub.replies["/faults"] = Triple(409, """{"error":"FAULT_REFUSED","detail":"exclusive_control_required=false"}""", 7)
            val refused = assertNotNull(cell.injectFault("""{"robotId":"quadruped-01","kind":"CONTROL_AUTHORITY_LOST"}""".toByteArray()))
            assertEquals(409, refused.status)
            assertTrue(superseded.isEmpty(), "409 상태만으로는 밀려남이 아니다")

            // 읽기 응답 머리말만으로 밀려남.
            stub.replies["/cell"] = Triple(200, """{"presentations":[],"slots":[]}""", 9)
            assertNotNull(cell.fetch())
            assertEquals(listOf(Triple("셀", 7L, 9L)), superseded.toList())

            // 인수 확인은 세대를 싣고 답을 읽는다.
            stub.replies[CellBandClient.CONFIRM_PATH] = Triple(200, """{"controllerEpochFloor":"7","fenceStatus":"DURABLE_CONFIRMED","receiverBootId":"b"}""", 7)
            val confirmed = assertNotNull(cell.confirmController())
            assertEquals(listOf<Any?>(200, 7L, "DURABLE_CONFIRMED", "b"), listOf(confirmed.status, confirmed.floor, confirmed.fenceStatus, confirmed.receiverBootId))
            assertEquals("POST" to "7", stub.seen.last().let { it.first to it.third })
        }
    }

    /**
     * 응답 머리말(높은 바닥값, `Content-Length` 100)과 본문 일부만 보내고 연결을 끊는 실제 소켓 서버. 받은 요청 수를 센다.
     */
    private class Truncating(val floor: Long) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val served = java.util.concurrent.atomic.AtomicInteger()
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        served.incrementAndGet()
                        val out = c.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n" +
                                "${HttpEpoch.FLOOR_HEADER}: $floor\r\n\r\n{\"handle\":").toByteArray(),
                        )
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        override fun close() = socket.close()
    }

    @Test
    fun `높은 바닥값 머리말 뒤 본문 수신이 끊겨도 플릿 호출마다 셀 중계와 확인마다 밀려남을 본다`() {
        Truncating(9).use { server ->
            val port = HttpFleetPort(server.url, epoch = epoch("플릿") { 7 })
            val cell = CellBandClient(server.url, epoch = epoch("셀") { 7 })
            val calls: List<Pair<String, () -> Unit>> = listOf(
                "맡기기" to { runCatching { port.dispatch(order) } },
                "취소" to { runCatching { port.cancel(handle) } },
                "상태" to { runCatching { port.status(handle) } },
                "찾기" to { runCatching { port.find("JO-1#HU-1") } },
                "플릿 확인" to { port.confirmController() },
                "셀 읽기" to { cell.fetch() },
                "신호 쓰기" to { cell.writeSignal("lot_code", """{"value":"X"}""".toByteArray()) },
                "결함 주입" to { cell.injectFault("{}".toByteArray()) },
                "셀 확인" to { cell.confirmController() },
            )
            calls.forEachIndexed { i, (what, call) ->
                call()
                assertEquals(i + 1, server.served.get(), "$what 요청이 서버에 닿았다")
                assertEquals(i + 1, superseded.size, "$what: 본문이 끊겨도 머리말의 바닥값으로 밀려남을 본다")
            }
            // 예외 형은 예전 그대로다(본문 수신 실패) — 맡기기는 미확정, 읽기·취소는 안 닿음, 셀 중계·확인은 null.
            assertEquals(FleetUnreachable::class, assertFailsWith<RuntimeException> { port.dispatch(order) }::class)
            assertEquals(null, cell.writeSignal("lot_code", "{}".toByteArray()))
            assertEquals(null, port.confirmController())
            assertTrue(superseded.all { it.second == 7L && it.third == 9L })
        }
    }

    @Test
    fun `현장 바닥값 저장 실패 503 의 맡기기·취소는 보내지 않음이고 셀 중계는 본문 그대로다`() {
        Stub().use { stub ->
            val failed = """{"error":"FENCE_STORE_FAILED","detail":"fleet 바닥값을 저장하지 못해 받지 않았다"}"""
            val port = HttpFleetPort(stub.url, epoch = epoch("플릿") { 7 })
            stub.replies["/fleet/transports"] = Triple(503, failed, 0)
            stub.replies["/fleet/transports/b%3Atr-1/cancel"] = Triple(503, failed, 0)
            assertEquals(CommandNotSent::class, assertFailsWith<RuntimeException> { port.dispatch(order) }::class)
            assertEquals(CommandNotSent::class, assertFailsWith<RuntimeException> { port.cancel(handle) }::class)
            // 다른 503 은 예전 그대로 미확정이다.
            stub.replies["/fleet/transports"] = Triple(503, """{"error":"OTHER"}""", 0)
            assertEquals(FleetUnreachable::class, assertFailsWith<RuntimeException> { port.dispatch(order) }::class)
            val cell = CellBandClient(stub.url, epoch = epoch("셀") { 7 })
            stub.replies["/cell/signals/lot_code"] = Triple(503, failed, 0)
            val relayed = assertNotNull(cell.writeSignal("lot_code", """{"value":"X"}""".toByteArray()))
            assertEquals(503 to failed, relayed.status to relayed.body.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `준비 해제 판정에는 바닥값 헤더가 실린 성공 응답과 세대 거부만 넘기고 헤더 없는 오류·그 밖의 오류는 넘기지 않는다`() {
        Stub().use { stub ->
            val port = HttpFleetPort(stub.url, epoch = epoch("플릿") { 7 })
            val floors = CopyOnWriteArrayList<Long>()
            port.onResponseFloor = { floors += it }
            val path = "/fleet/transports/b%3Atr-1"
            stub.replies[path] = Triple(503, "", null)
            runCatching { port.status(handle) }
            stub.replies[path] = Triple(503, """{"error":"OTHER"}""", 0)
            runCatching { port.status(handle) }
            stub.replies[path] = Triple(404, "", 7)
            runCatching { port.status(handle) }
            stub.replies[path] = Triple(200, """{"state":"ACCEPTED","holding":false}""", null)
            port.status(handle)
            assertTrue(floors.isEmpty(), "읽기 실패·헤더 없는 응답을 바닥값으로 넘겼다: $floors")
            stub.replies[path] = Triple(200, """{"state":"ACCEPTED","holding":false}""", 7)
            port.status(handle)
            stub.replies[path] = Triple(409, stale(7, 7), 7)
            runCatching { port.status(handle) }
            assertEquals(listOf(7L, 7L), floors.toList())
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
