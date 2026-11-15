package dev.picasso.ops.site

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.mimic.cli.MimicCli
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 현장의 플릿 세계 면(`/fleet`, [SiteFleet])과 셀 대역 투영. registry 없이 mimic 하나와 셀 대역만 띄우고 HTTP 로 부른다. 시계를 미는 순서는
 * [Site.advance] 와 같다(민 직후 훑기 — 훑기가 플릿 세계를 셀 자리로 올린다).
 */
class SiteFleetTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private inner class Bench : AutoCloseable {
        val mimic: MimicCli.Started = checkNotNull(
            MimicCli().start(
                robots = mapOf("humanoid-01" to root.resolve("picasso/profile/profiles/humanoid-a.json")),
                schema = root.resolve(SiteConfig.PROFILE_SCHEMA),
                port = 0,
                virtual = true,
                seed = 0L,
                err = System.err,
            ),
        ) { "mimic 기동 거부" }
        val cell = SiteCell(mimic)

        init {
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun now(): Instant = mimic.server.exclusive { mimic.instance("humanoid-01")!!.clock.now() }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        val base: String get() = "http://127.0.0.1:${cell.port}"

        fun get(path: String): HttpResponse<String> =
            http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString())

        fun post(path: String, body: String, contentType: String = "application/json"): HttpResponse<String> =
            http.send(
                HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        fun body(response: HttpResponse<String>): JsonNode = json.readTree(response.body())

        fun dispatch(reference: String, container: String = CONTAINER, source: String = OUT, destination: String = IN): String? {
            val reply = post("/fleet/transports", """{"reference":"$reference","containerId":"$container","source":"$source","destination":"$destination"}""")
            assertEquals(200, reply.statusCode(), reply.body())
            return body(reply)["handle"].takeUnless { it.isNull }?.asText()
        }

        fun status(handle: String): JsonNode = body(get("/fleet/transports/$handle").also { assertEquals(200, it.statusCode(), it.body()) })

        fun handover(id: String): CellPlace = cell.snapshot.handovers.single { it.id == id }

        override fun close() {
            cell.close()
            mimic.server.shutdown()
        }
    }

    @Test
    fun `맡긴 운반은 세 걸음 뒤 인계되고 목적지 인계 자리가 그 용기와 놓인 시각으로 GET cell 에 오른다`() {
        Bench().use { bench ->
            // 처음 세계는 픽스처 그대로다 — 출고 인계점에 용기가 있고 놓인 시각은 셀 대역을 지은 가상 시각이다(시험은 EPOCH 에서 짓는다).
            assertEquals(CellPlace(OUT, true, CONTAINER, Instant.EPOCH), bench.handover(OUT))
            assertEquals(CellPlace(IN, false, null, null), bench.handover(IN))
            val handle = bench.dispatch("JO-1#$CONTAINER")!!
            assertEquals("ACCEPTED", bench.status(handle)["state"].asText())

            bench.advance(SiteFleet.STEP)
            val picked = bench.status(handle)
            assertEquals(listOf("PICKED_UP", CONTAINER, "true"), listOf(picked["state"].asText(), picked["observedContainer"].asText(), picked["holding"].asText()))
            // 인수한 출발지는 비었다(셀 투영도 같은 세계다).
            assertEquals(false, bench.handover(OUT).occupied)

            bench.advance(SiteFleet.STEP)
            bench.advance(SiteFleet.STEP)
            val delivered = bench.status(handle)
            assertEquals("DELIVERED" to false, delivered["state"].asText() to delivered["holding"].asBoolean())
            val place = bench.handover(IN)
            assertEquals(true to CONTAINER, place.occupied to place.material)
            assertEquals(START.plus(SiteFleet.STEP.multipliedBy(3)), place.observedAt, "놓인 시각은 인계한 걸음의 가상 시각이다")

            val cell = bench.body(bench.get("/cell"))
            val wire = cell["handovers"].map { it["id"].asText() to it["material"].takeUnless { m -> m.isNull }?.asText() }
            assertEquals(listOf(OUT to null, IN to CONTAINER), wire)
            assertEquals(place.observedAt.toString(), cell["handovers"][1]["observedAt"].asText())
        }
    }

    @Test
    fun `같은 참조로 다시 맡기면 같은 핸들이고 맡긴 횟수는 하나이며 찾기는 새로 맡지 않는다`() {
        Bench().use { bench ->
            assertTrue(bench.body(bench.get("/fleet/transports?reference=JO-1%23HU")).get("handle").isNull, "아직 맡지 않은 참조는 찾지 못한다")
            val first = bench.dispatch("JO-1#HU")
            val again = bench.dispatch("JO-1#HU")
            assertEquals(first, again)
            assertEquals(first, bench.body(bench.get("/fleet/transports?reference=JO-1%23HU"))["handle"].asText())
            assertEquals(1, bench.body(bench.get("/fleet/world"))["dispatches"].asInt())
            assertEquals(1, bench.cell.fleet.world.dispatches)
        }
    }

    @Test
    fun `출발지 용기가 요청과 다르면 인수하지 않고 관측한 용기를 싣는다`() {
        Bench().use { bench ->
            val handle = bench.dispatch("JO-2#OTHER", container = "HU-9999")!!
            val status = bench.status(handle)
            assertEquals(listOf("REJECTED_AT_SOURCE", CONTAINER, "false"), listOf(status["state"].asText(), status["observedContainer"].asText(), status["holding"].asText()))
            bench.advance(SiteFleet.STEP)
            // 출발지의 용기는 그대로다.
            assertEquals(CellPlace(OUT, true, CONTAINER, Instant.EPOCH), bench.handover(OUT))
        }
    }

    @Test
    fun `모르는 핸들은 던지지 않고 FAILED 모르는 운반이며 취소는 false 다`() {
        Bench().use { bench ->
            val status = bench.status("tr-404")
            assertEquals("FAILED", status["state"].asText())
            assertTrue(status["detail"].asText().startsWith("모르는 운반"), status.toString())
            val cancel = bench.post("/fleet/transports/tr-404/cancel", "{}")
            assertEquals(200 to false, cancel.statusCode() to bench.body(cancel)["accepted"].asBoolean())
        }
    }

    @Test
    fun `싣고 가던 운반을 취소하면 용기가 출발지로 돌아가고 셀 투영이 곧 바뀐다`() {
        Bench().use { bench ->
            val handle = bench.dispatch("JO-3#$CONTAINER")!!
            bench.advance(SiteFleet.STEP)
            assertEquals(false, bench.handover(OUT).occupied)
            val cancel = bench.post("/fleet/transports/$handle/cancel", "{}")
            assertEquals(true, bench.body(cancel)["accepted"].asBoolean(), cancel.body())
            // 시계를 밀지 않아도 명령 뒤 투영이 갈린다.
            assertEquals(true to CONTAINER, bench.handover(OUT).occupied to bench.handover(OUT).material)
            assertEquals("CANCELLED", bench.status(handle)["state"].asText())
            bench.advance(SiteFleet.STEP.multipliedBy(4))
            assertEquals(false, bench.handover(IN).occupied, "취소한 운반은 인계하지 않는다")
        }
    }

    @Test
    fun `막힌 목적지 앞에서는 인계를 기다리고 풀면 인계한다`() {
        Bench().use { bench ->
            val blocked = bench.post("/fleet/world/block", """{"location":"$IN"}""")
            assertEquals(listOf(IN), bench.body(blocked)["blocked"].map { it.asText() })
            val handle = bench.dispatch("JO-4#$CONTAINER")!!
            repeat(4) { bench.advance(SiteFleet.STEP) }
            assertEquals("WAITING_HANDOVER", bench.status(handle)["state"].asText())
            bench.post("/fleet/world/unblock", """{"location":"$IN"}""")
            bench.advance(SiteFleet.STEP)
            assertEquals("DELIVERED", bench.status(handle)["state"].asText())
            assertEquals(CONTAINER, bench.handover(IN).material)
        }
    }

    @Test
    fun `인계 자리 밖의 운반은 맡지 않고 세계 채우기와 요청 모양을 가린다`() {
        Bench().use { bench ->
            assertEquals(null, bench.dispatch("JO-5#X", source = "NOWHERE"))
            assertEquals(null, bench.dispatch("JO-5#Y", destination = "NOWHERE"))
            assertEquals(0, bench.cell.fleet.world.dispatches)

            val placed = bench.post("/fleet/world/place", """{"location":"$IN","containerId":"HU-2"}""")
            assertEquals(200, placed.statusCode(), placed.body())
            assertEquals(listOf(CONTAINER, "HU-2"), bench.body(placed)["places"].map { it["containerId"].asText() })
            assertEquals("HU-2", bench.handover(IN).material)
            assertEquals(200, bench.post("/fleet/world/clear", """{"location":"$IN"}""").statusCode())
            assertEquals(false, bench.handover(IN).occupied)

            val unknown = bench.post("/fleet/world/place", """{"location":"NOWHERE","containerId":"HU-2"}""")
            assertEquals(404 to SiteFleet.UNKNOWN_PLACE, unknown.statusCode() to bench.body(unknown)["error"].asText())
            val noContainer = bench.post("/fleet/world/place", """{"location":"$IN"}""")
            assertEquals(400 to SiteFleet.BAD_REQUEST, noContainer.statusCode() to bench.body(noContainer)["error"].asText())
            val plain = bench.post("/fleet/world/clear", """{"location":"$IN"}""", contentType = "text/plain")
            assertEquals(415, plain.statusCode())
            val badBody = bench.post("/fleet/transports", """{"reference":"R"}""")
            assertEquals(400, badBody.statusCode())
            assertEquals(405, bench.get("/fleet/world/place").statusCode())
            assertEquals(404, bench.get("/fleet/elsewhere").statusCode())
            assertEquals(404, bench.post("/fleet/world/teleport", """{"location":"$IN"}""").statusCode())
        }
    }

    @Test
    fun `바깥 핸들은 기동 id 를 앞에 달고 다른 기동의 핸들은 미믹에 묻지 않고 모르는 운반이다`() {
        Bench().use { bench ->
            val handle = bench.dispatch("JO-6#$CONTAINER")!!
            assertTrue(handle.startsWith("${bench.cell.fleet.boot}:"), handle)
            assertEquals("ACCEPTED", bench.status(handle)["state"].asText())
            // 현장 재기동 뒤의 옛 핸들 흉내 — 같은 미믹 번호(tr-1)여도 접두가 다르면 남의 운반을 가리키지 않는다.
            val stale = "deadbeef:" + handle.substringAfter(':')
            val status = bench.status(stale)
            assertEquals("FAILED" to false, status["state"].asText() to status["holding"].asBoolean())
            assertTrue(status["detail"].asText().startsWith("모르는 운반"), status.toString())
            assertEquals(false, bench.body(bench.post("/fleet/transports/$stale/cancel", "{}"))["accepted"].asBoolean())
            assertEquals("ACCEPTED", bench.status(handle)["state"].asText(), "옛 핸들의 취소가 이 운반을 건드리지 않았다")
            assertEquals(handle, bench.body(bench.get("/fleet/transports?reference=JO-6%23$CONTAINER"))["handle"].asText())
        }
        // 다른 기동(새 셀 대역)은 다른 접두다.
        Bench().use { a -> Bench().use { b -> assertTrue(a.cell.fleet.boot != b.cell.fleet.boot) } }
    }

    @Test
    fun `세계 채우기의 place 는 지금 현장 가상 시각을 놓인 시각으로 찍고 처음 용기 다시 찍기는 그 자리에 남은 처음 용기만 찍는다`() {
        Bench().use { bench ->
            bench.advance(Duration.ofSeconds(7))
            val at = bench.now()
            bench.post("/fleet/world/place", """{"location":"$IN","containerId":"HU-7"}""")
            assertEquals(CellPlace(IN, true, "HU-7", at), bench.handover(IN))
            assertEquals(at.toString(), bench.body(bench.get("/fleet/world"))["places"][1]["placedAt"].asText())
            bench.cell.fleet.stampInitial()
            bench.cell.project()
            assertEquals(CellPlace(OUT, true, CONTAINER, at), bench.handover(OUT))
            bench.post("/fleet/world/clear", """{"location":"$OUT"}""")
            bench.cell.fleet.stampInitial()
            bench.cell.project()
            assertEquals(false, bench.handover(OUT).occupied, "옮겨지거나 치운 처음 용기는 되살리지 않는다")
        }
    }

    private companion object {
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
