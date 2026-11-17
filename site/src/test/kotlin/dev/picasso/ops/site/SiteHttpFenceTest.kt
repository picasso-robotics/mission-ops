package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.adapter.host.FileFenceStore
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceDurability
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FenceStore
import dev.picasso.mimic.cli.MimicCli
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 현장 HTTP 면(플릿 `/fleet`·셀 대역 `/cell`·결함 주입 `/faults`)의 제어자 세대 대조([SiteHttpFence], HA 3단계 PR B1). registry 없이 mimic 하나와 셀 대역만
 * 띄운다. 거절의 «효과 없음» 은 가상 시계를 멈춘 채 본다 — 플릿 세계는 시계로 걸으므로 시계를 밀면 거절과 무관하게 바뀐다.
 */
class SiteHttpFenceTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private fun mimic(): MimicCli.Started = checkNotNull(
        MimicCli().start(
            robots = mapOf(
                HUMANOID to root.resolve("picasso/profile/profiles/humanoid-a.json"),
                QUADRUPED to root.resolve("picasso/profile/profiles/quadruped-b.json"),
            ),
            schema = root.resolve(SiteConfig.PROFILE_SCHEMA),
            port = 0,
            virtual = true,
            seed = 0L,
            err = System.err,
        ),
    ) { "mimic 기동 거부" }

    /** 바닥값 저장소. 닫으면 디렉터리를 지운다. */
    private class Floors : AutoCloseable {
        val dir: Path = Files.createTempDirectory("site-http-fence")
        val store = FileFenceStore(dir)

        override fun close() {
            store.close()
            dir.toFile().deleteRecursively()
        }
    }

    private inner class Bench(val httpFence: SiteHttpFence = SiteHttpFence()) : AutoCloseable {
        val mimic = mimic()
        val cell = SiteCell(mimic, httpFence = httpFence)

        init {
            mimic.server.exclusive {
                mimic.server.advance(Duration.between(Instant.EPOCH, START))
                cell.scan()
            }
        }

        val base: String get() = "http://127.0.0.1:${cell.port}"

        fun connection(robotId: String): ConnectionState = mimic.server.exclusive { mimic.instance(robotId)!!.events.connectionState }

        override fun close() {
            cell.close()
            mimic.server.shutdown()
        }
    }

    private fun fenced(store: FenceStore, wait: Duration = SiteHttpFence.CONFIRM_WAIT) = SiteHttpFence(ControllerFence(FenceMode.FENCED, store), wait)

    private fun request(base: String, method: String, path: String, epoch: Any?, body: String? = null, extra: List<String> = emptyList()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        epoch?.let { builder.header(SiteHttpFence.EPOCH_HEADER, it.toString()) }
        extra.forEach { builder.header(SiteHttpFence.EPOCH_HEADER, it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(base: String, path: String, epoch: Any? = null) = request(base, "GET", path, epoch)

    private fun post(base: String, path: String, body: String, epoch: Any? = null) = request(base, "POST", path, epoch, body)

    private fun dispatch(base: String, reference: String, epoch: Any?) =
        post(base, "/fleet/transports", """{"reference":"$reference","containerId":"$CONTAINER","source":"$OUT","destination":"$IN"}""", epoch)

    private fun HttpResponse<String>.floor(): String? = headers().firstValue(SiteHttpFence.FLOOR_HEADER).orElse(null)

    private fun HttpResponse<String>.json(): JsonNode = json.readTree(body())

    private fun HttpResponse<String>.error(): String? = runCatching { json()["error"].asText() }.getOrNull()

    private fun assertStale(response: HttpResponse<String>, reason: String, requested: String, floor: String) {
        assertEquals(409, response.statusCode(), response.body())
        val body = response.json()
        assertEquals(
            listOf("error", "reason", "requestedControllerEpoch", "controllerEpochFloor", "detail"),
            body.fieldNames().asSequence().toList(),
        )
        assertEquals(listOf(SiteHttpFence.STALE_CONTROLLER, reason, requested, floor), listOf("error", "reason", "requestedControllerEpoch", "controllerEpochFloor").map { body[it].asText() })
        assertTrue(body["requestedControllerEpoch"].isTextual && body["controllerEpochFloor"].isTextual, "세대·바닥값은 10진 문자열이다")
        assertTrue(body["detail"].asText().isNotBlank())
        assertEquals(floor, response.floor())
    }

    @Test
    fun `세대 헤더가 없으면 0 이고 형식이 틀리거나 두 번 실렸거나 범위 밖이면 어느 면이든 400 이며 모든 응답에 바닥값 헤더가 실린다`() {
        Bench().use { bench ->
            val base = bench.base
            listOf(get(base, "/cell"), get(base, "/fleet/world"), get(base, "/cell", "0"), get(base, "/cell/nowhere"), get(base, "/fleet/nowhere"), get(base, "/faults/nowhere")).forEach {
                assertEquals("0", it.floor(), "${it.uri()} → ${it.statusCode()}")
            }
            assertEquals(200, get(base, "/cell", "0").statusCode(), "명시한 0 은 없음과 같다")
            val bad = listOf(
                get(base, "/cell", "abc"),
                get(base, "/cell", "-1"),
                get(base, "/cell", "1.5"),
                get(base, "/cell", "99999999999999999999"),
                request(base, "GET", "/fleet/world", null, extra = listOf("1", "2")),
                dispatch(base, "JO-1#HU", "x1"),
                post(base, "/cell/signals/lot_code", """{"value":"A"}""", "x"),
                post(base, "/faults", """{"robotId":"$HUMANOID","kind":"CONNECTION","state":"OFFLINE"}""", "1,2"),
                post(base, "/cell/controller/confirm", "{}", "9223372036854775808"),
            )
            bad.forEach { response ->
                assertEquals(400, response.statusCode(), "${response.uri()} ${response.body()}")
                assertEquals("BAD_REQUEST", response.error(), response.body())
                assertEquals("0", response.floor())
            }
            // 틀린 헤더의 변경은 아무것도 바꾸지 않았다.
            assertEquals(0, bench.cell.fleet.world.dispatches)
            assertEquals("LOT-0001", bench.cell.snapshot.signals.single { it.name == "lot_code" }.value)
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))
            // 대조 안 함이면 어떤 세대(0 포함)의 변경도 받는다.
            assertEquals(200, dispatch(base, "JO-1#HU", null).statusCode())
            assertEquals(200, post(base, "/cell/signals/lot_code", """{"value":"A"}""").statusCode())
        }
    }

    @Test
    fun `대조하는 면은 새 세대 확인 뒤 옛 세대의 맡기기·취소·셀 쓰기·결함 주입을 409 로 거절하고 세계와 셀 투영과 기체를 바꾸지 않는다`() {
        Floors().use { floors ->
            Bench(fenced(floors.store)).use { bench ->
                val base = bench.base
                // 옛 능동(세대 1)이 정상으로 맡기고 쓴다 — 바닥값이 1 로 오른다.
                val first = dispatch(base, "JO-1#$CONTAINER", 1)
                assertEquals(200 to "1", first.statusCode() to first.floor(), first.body())
                val handle = first.json()["handle"].asText()
                assertEquals(200, post(base, "/cell/signals/lot_code", """{"value":"LOT-A"}""", 1).statusCode())

                // 새 보유자(세대 2)의 인수 확인.
                listOf("/fleet/controller/confirm", "/cell/controller/confirm").forEach { path ->
                    val confirmed = post(base, path, "{}", 2)
                    assertEquals(200, confirmed.statusCode(), confirmed.body())
                    assertEquals(listOf("2", "DURABLE_CONFIRMED", bench.cell.fleet.boot), listOf("controllerEpochFloor", "fenceStatus", "receiverBootId").map { confirmed.json()[it].asText() })
                    assertEquals("2", confirmed.floor())
                }

                val dispatches = bench.cell.fleet.world.dispatches
                val status = get(base, "/fleet/transports/$handle").body()
                val snapshot = bench.cell.snapshot

                // 옛 능동의 로컬 관문을 지나 늦게 닿은 변경 넷.
                assertStale(dispatch(base, "JO-2#$CONTAINER", 1), "BELOW_FLOOR", "1", "2")
                assertStale(post(base, "/fleet/transports/$handle/cancel", "{}", 1), "BELOW_FLOOR", "1", "2")
                assertStale(post(base, "/cell/signals/lot_code", """{"value":"LOT-B"}""", 1), "BELOW_FLOOR", "1", "2")
                assertStale(post(base, "/faults", """{"robotId":"$HUMANOID","kind":"CONNECTION","state":"OFFLINE"}""", 1), "BELOW_FLOOR", "1", "2")
                // 세대를 싣지 않은 변경도 거절한다(설정 오류 — 밀려남이 아니다).
                assertStale(dispatch(base, "JO-3#$CONTAINER", null), "EPOCH_MISSING", "0", "2")
                assertStale(post(base, "/cell/signals/lot_code", """{"value":"LOT-C"}""", 0), "EPOCH_MISSING", "0", "2")

                // 효과 없음 — 시계를 밀지 않았으므로 바뀐 것이 있으면 거절된 요청 탓이다.
                assertEquals(dispatches, bench.cell.fleet.world.dispatches)
                assertEquals(status, get(base, "/fleet/transports/$handle").body(), "취소가 닿지 않았다")
                assertEquals(snapshot, bench.cell.snapshot, "셀 투영·신호가 그대로다")
                assertEquals("LOT-A", bench.cell.snapshot.signals.single { it.name == "lot_code" }.value)
                assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))
                assertEquals(2L to 2L, bench.cell.httpFence.floor(SiteHttpFence.FLEET) to bench.cell.httpFence.floor(SiteHttpFence.CELL))

                // 새 세대의 변경은 받고, 더 높은 세대는 바닥값을 (파일에 적은 뒤) 올린다.
                assertEquals(200, post(base, "/fleet/transports/$handle/cancel", "{}", 2).statusCode())
                val raised = post(base, "/cell/signals/lot_code", """{"value":"LOT-D"}""", 3)
                assertEquals(200 to "3", raised.statusCode() to raised.floor())
                assertEquals("3", Files.readString(floors.dir.resolve("cell.floor")).trim())
                assertEquals("2", Files.readString(floors.dir.resolve("fleet.floor")).trim())
            }
        }
    }

    @Test
    fun `읽기와 세계 채우기는 낮은 세대도 받고 바닥값을 올리지 않는다`() {
        Floors().use { floors ->
            Bench(fenced(floors.store)).use { bench ->
                val base = bench.base
                assertEquals(200, post(base, "/fleet/controller/confirm", "{}", 5).statusCode())
                val handle = dispatch(base, "JO-1#$CONTAINER", 5).json()["handle"].asText()
                listOf(1, 0, null, 9).forEach { epoch ->
                    listOf(
                        get(base, "/fleet/transports?reference=JO-1%23$CONTAINER", epoch),
                        get(base, "/fleet/transports/$handle", epoch),
                        get(base, "/fleet/world", epoch),
                        post(base, "/fleet/world/block", """{"location":"$IN"}""", epoch),
                        post(base, "/fleet/world/unblock", """{"location":"$IN"}""", epoch),
                    ).forEach { response ->
                        assertEquals(200 to "5", response.statusCode() to response.floor(), "${response.uri()} 세대 $epoch ${response.body()}")
                    }
                    val cell = get(base, "/cell", epoch)
                    assertEquals(200 to "0", cell.statusCode() to cell.floor())
                }
                assertEquals(5L to 0L, bench.cell.httpFence.floor(SiteHttpFence.FLEET) to bench.cell.httpFence.floor(SiteHttpFence.CELL))
                assertTrue(Files.notExists(floors.dir.resolve("cell.floor")), "읽기는 셀 바닥값을 적지 않는다")
            }
        }
    }

    @Test
    fun `인수 확인은 대조하지 않으면 UNSPECIFIED 이고 대조하면 세대 0 과 낮은 세대를 409 로 거절하며 기동 ID 를 싣는다`() {
        Bench().use { bench ->
            listOf("/fleet/controller/confirm", "/cell/controller/confirm").forEach { path ->
                val answer = post(bench.base, path, "{}", 3)
                assertEquals(200, answer.statusCode(), answer.body())
                assertEquals(listOf("controllerEpochFloor", "fenceStatus", "receiverBootId"), answer.json().fieldNames().asSequence().toList())
                assertEquals(listOf("0", "UNSPECIFIED", bench.cell.fleet.boot), listOf("controllerEpochFloor", "fenceStatus", "receiverBootId").map { answer.json()[it].asText() })
                assertEquals("0", answer.floor())
            }
        }
        Floors().use { floors ->
            Bench(fenced(floors.store)).use { bench ->
                listOf(SiteHttpFence.FLEET to "/fleet/controller/confirm", SiteHttpFence.CELL to "/cell/controller/confirm").forEach { (key, path) ->
                    assertStale(post(bench.base, path, "{}", null), "EPOCH_MISSING", "0", "0")
                    val confirmed = post(bench.base, path, "{}", 4)
                    assertEquals(listOf("4", "DURABLE_CONFIRMED"), listOf("controllerEpochFloor", "fenceStatus").map { confirmed.json()[it].asText() })
                    assertStale(post(bench.base, path, "{}", 3), "BELOW_FLOOR", "3", "4")
                    assertStale(post(bench.base, path, "{}", 0), "EPOCH_MISSING", "0", "4")
                    // 같은 세대는 다시 확인한다(바닥값 그대로).
                    assertEquals(200, post(bench.base, path, "{}", 4).statusCode())
                    assertEquals(4L, bench.cell.httpFence.floor(key))
                    assertEquals(405, get(bench.base, path, 4).statusCode())
                    assertEquals(415, request(bench.base, "POST", path, 4, null).statusCode())
                }
            }
        }
        // 메모리 저장은 VOLATILE 이다(다시 뜨면 바닥값을 잃으므로 확인이 아니다).
        Bench(fenced(FenceStore.memory())).use { bench ->
            assertEquals("VOLATILE", post(bench.base, "/fleet/controller/confirm", "{}", 1).json()["fenceStatus"].asText())
        }
    }

    @Test
    fun `앞 세대 맡기기가 셀 투영을 마치기 전이면 인수 확인이 DURABLE_PENDING 이고 거절된 맡기기·취소는 셀 투영을 다시 하지 않는다`() {
        Floors().use { floors ->
            val mimic = mimic()
            val fence = fenced(floors.store, Duration.ofMillis(200))
            val fleet = SiteFleet(mimic, listOf(OUT, IN), mapOf(OUT to CONTAINER), fence = fence)
            val projections = AtomicInteger()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holdNext = java.util.concurrent.atomic.AtomicBoolean(false)
            // 실제로 겹치게 여러 스레드로 받는다 — 현장 서버의 직렬 실행기에 기대지 않는다.
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext(SiteFleet.PATH) { exchange ->
                    fleet.handle(exchange) {
                        projections.incrementAndGet()
                        if (holdNext.compareAndSet(true, false)) {
                            entered.countDown()
                            release.await(10, TimeUnit.SECONDS)
                        }
                    }
                }
                executor = Executors.newCachedThreadPool()
                start()
            }
            try {
                val base = "http://127.0.0.1:${server.address.port}"
                holdNext.set(true)
                // 세대 1 의 맡기기가 세계를 바꾼 뒤 셀 투영(changed) 안에서 멈춘다.
                val slow = CompletableFuture.supplyAsync { dispatch(base, "JO-1#$CONTAINER", 1) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val pending = post(base, SiteFleet.CONFIRM, "{}", 2)
                assertEquals(200, pending.statusCode(), pending.body())
                assertEquals("2" to "DURABLE_PENDING", pending.json()["controllerEpochFloor"].asText() to pending.json()["fenceStatus"].asText())
                release.countDown()
                assertEquals(200, slow.get(10, TimeUnit.SECONDS).statusCode())
                assertEquals("DURABLE_CONFIRMED", post(base, SiteFleet.CONFIRM, "{}", 2).json()["fenceStatus"].asText())

                val handle = fleet.external(fleet.world.find("JO-1#$CONTAINER")!!)
                val before = projections.get()
                assertStale(dispatch(base, "JO-2#$CONTAINER", 1), "BELOW_FLOOR", "1", "2")
                assertStale(post(base, "/fleet/transports/$handle/cancel", "{}", 1), "BELOW_FLOOR", "1", "2")
                assertEquals(before, projections.get(), "거절된 변경은 셀 투영을 다시 하지 않는다")
                assertEquals(200, post(base, "/fleet/world/block", """{"location":"$IN"}""", 1).statusCode())
                assertEquals(before + 1, projections.get(), "세계 채우기는 투영을 다시 한다(세는 문이 산다)")
            } finally {
                release.countDown()
                server.stop(0)
                mimic.server.shutdown()
            }
        }
    }

    @Test
    fun `플릿 인수 확인은 답이 200 이든 409 든 503 이든 셀 투영을 다시 하지 않는다`() {
        val mimic = mimic()
        val failing = java.util.concurrent.atomic.AtomicBoolean(false)
        val floors = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val store = object : FenceStore {
            override val durability = FenceDurability.DURABLE
            override fun load(): Map<String, Long> = floors.toMap()
            override fun save(robotId: String, floor: Long) {
                if (failing.get()) throw java.io.IOException("디스크가 찼다")
                floors[robotId] = floor
            }
        }
        val fleet = SiteFleet(mimic, listOf(OUT, IN), mapOf(OUT to CONTAINER), fence = fenced(store))
        val projections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext(SiteFleet.PATH) { exchange -> fleet.handle(exchange) { projections.incrementAndGet() } }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(200, post(base, SiteFleet.CONFIRM, "{}", 2).statusCode())
            assertStale(post(base, SiteFleet.CONFIRM, "{}", 1), "BELOW_FLOOR", "1", "2")
            failing.set(true)
            val failed = post(base, SiteFleet.CONFIRM, "{}", 3)
            assertEquals(503 to SiteHttpFence.FENCE_STORE_FAILED, failed.statusCode() to failed.error())
            assertEquals(0, projections.get(), "확인은 세계를 바꾸지 않는다")
            assertEquals(200, post(base, "/fleet/world/block", """{"location":"$IN"}""", 1).statusCode())
            assertEquals(1, projections.get(), "세계 채우기는 투영을 다시 한다(세는 문이 산다)")
        } finally {
            server.stop(0)
            mimic.server.shutdown()
        }
    }

    @Test
    fun `앞 세대 셀 신호 쓰기가 스냅숏을 갈기 전이면 셀 인수 확인이 DURABLE_PENDING 이다`() {
        Floors().use { floors ->
            Bench(fenced(floors.store, Duration.ofMillis(200))).use { bench ->
                val locked = CountDownLatch(1)
                val release = CountDownLatch(1)
                // 엔진 잠금을 쥐어 통과한 신호 쓰기가 스냅숏을 갈지 못하게 한다.
                val holder = Thread {
                    bench.mimic.server.exclusive {
                        locked.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }.apply { start() }
                try {
                    assertTrue(locked.await(10, TimeUnit.SECONDS))
                    val slow = CompletableFuture.supplyAsync { post(bench.base, "/cell/signals/lot_code", """{"value":"LOT-A"}""", 1) }
                    // 통과(바닥값 1 로 상승) 뒤 잠금을 기다린다.
                    val deadline = Instant.now().plusSeconds(10)
                    while (bench.cell.httpFence.floor(SiteHttpFence.CELL) != 1L) {
                        check(Instant.now().isBefore(deadline)) { "신호 쓰기가 통과하지 않았다" }
                        Thread.sleep(10)
                    }
                    // 현장 서버는 직렬이라 확인을 같은 서버로 보내면 앞 요청 뒤에 줄을 선다 — 같은 대조를 다른 스레드에서 직접 부른다.
                    val (status, body) = bench.cell.httpFence.confirm(SiteHttpFence.CELL, 2, bench.cell.fleet.boot)
                    assertEquals(200 to "DURABLE_PENDING", status to body["fenceStatus"])
                    release.countDown()
                    assertEquals(200, slow.get(10, TimeUnit.SECONDS).statusCode(), "이미 통과한 쓰기는 끝까지 간다")
                    assertEquals("DURABLE_CONFIRMED", bench.cell.httpFence.confirm(SiteHttpFence.CELL, 2, bench.cell.fleet.boot).second["fenceStatus"])
                } finally {
                    release.countDown()
                    holder.join(10_000)
                }
            }
        }
    }

    @Test
    fun `바닥값을 저장하지 못하면 변경 없이 503 이고 바닥값도 오르지 않는다`() {
        val broken = object : FenceStore {
            override val durability = FenceDurability.DURABLE
            override fun load(): Map<String, Long> = emptyMap()
            override fun save(robotId: String, floor: Long) = throw java.io.IOException("디스크가 찼다")
        }
        Bench(fenced(broken)).use { bench ->
            listOf(
                dispatch(bench.base, "JO-1#$CONTAINER", 1),
                post(bench.base, "/cell/signals/lot_code", """{"value":"LOT-A"}""", 1),
                post(bench.base, "/faults", """{"robotId":"$HUMANOID","kind":"CONNECTION","state":"OFFLINE"}""", 1),
                post(bench.base, "/cell/controller/confirm", "{}", 1),
            ).forEach { response ->
                assertEquals(503 to SiteHttpFence.FENCE_STORE_FAILED, response.statusCode() to response.error(), response.body())
                assertEquals("0", response.floor())
            }
            assertEquals(0, bench.cell.fleet.world.dispatches)
            assertEquals("LOT-0001", bench.cell.snapshot.signals.single { it.name == "lot_code" }.value)
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, bench.connection(HUMANOID))
        }
    }

    @Test
    fun `엔진 거부의 409 FAULT_REFUSED 는 세대 거부와 error 로 갈린다`() {
        Floors().use { floors ->
            Bench(fenced(floors.store)).use { bench ->
                val refused = post(bench.base, "/faults", """{"robotId":"$QUADRUPED","kind":"CONTROL_AUTHORITY_LOST"}""", 1)
                assertEquals(409 to SiteFaults.FAULT_REFUSED, refused.statusCode() to refused.error(), refused.body())
                assertEquals(listOf("error", "detail"), refused.json().fieldNames().asSequence().toList())
                assertEquals("1", refused.floor(), "세대 검증을 지난 변경이라 바닥값은 올랐다")
            }
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
