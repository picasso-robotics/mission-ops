package dev.picasso.ops.site

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.mimic.engine.TaskState
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 셀 대역(S3a 스펙 §6.3)과 이름 있는 신호(S3b 스펙 §5). registry 없이 mimic 하나와 셀 대역만 띄우고, 기체에 `pick_place` 를 직접 걸어 가상 시계로 끝낸다.
 * 시계를 미는 순서는 [Site.advance] 와 같다(민 직후 훑기).
 */
class SiteCellTest {

    private val root = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    /** mimic 하나와 셀 대역, 그리고 그 mimic 에 붙은 클라이언트. */
    private inner class Bench : AutoCloseable {
        val mimic: MimicCli.Started = checkNotNull(
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
        val cell = SiteCell(mimic)
        private val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
        val client = PicassoClient(channel, "site-cell-test")

        init {
            // EPOCH 에서 멀리 떨어진 시각에서 시작한다. 관측 시각을 EPOCH 로 적는 결함이 «거의 같은 값» 으로 숨지 않게 한다.
            advance(Duration.between(Instant.EPOCH, START))
        }

        fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

        fun advance(by: Duration) = mimic.server.exclusive {
            mimic.server.advance(by)
            cell.scan()
        }

        fun pickPlace(robotId: String, taskId: String, objectId: String, destination: String) {
            val response = client.start(
                robotId, taskId, 1, "pick_place",
                listOf(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue(objectId).build(),
                    ParameterValue.newBuilder().setKey("destination").setStringValue(destination).build(),
                ),
            )
            assertTrue(response.hasHandle(), "태스크 시작이 거부됐다: ${response.rejection}")
        }

        fun state(robotId: String, taskId: String): TaskState? =
            mimic.server.exclusive { mimic.instance(robotId)!!.tasks.find(taskId)?.machine?.state }

        /** 태스크가 종료할 때까지 5초씩 민다. 종료한 그 밀기 직후의 가상 시각을 돌려준다. */
        fun runToEnd(robotId: String, taskId: String): Instant {
            repeat(40) {
                advance(Duration.ofSeconds(5))
                if (state(robotId, taskId)?.isTerminal == true) return now()
            }
            error("태스크가 끝나지 않았다: ${state(robotId, taskId)}")
        }

        fun slot(id: String): CellPlace = cell.snapshot.slots.single { it.id == id }

        override fun close() {
            channel.shutdownNow()
            cell.close()
            mimic.server.shutdown()
        }
    }

    @Test
    fun `pick_place 가 성공하면 그 슬롯을 제시 자리의 자재로 채우고 관측 시각은 채운 가상 시각이다`() {
        Bench().use { bench ->
            assertEquals(false, bench.slot(S01).occupied)
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)

            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            assertEquals(TaskState.SUCCEEDED, bench.state(HUMANOID, "JO-1#$S01"))
            assertEquals(CellPlace(S01, true, MATERIAL, doneAt), bench.slot(S01))
            // 다른 슬롯과 제시 자리는 그대로다.
            assertTrue(bench.cell.snapshot.slots.filter { it.id != S01 }.none { it.occupied })
            assertEquals(listOf(CellPlace(SOURCE, true, MATERIAL, null)), bench.cell.snapshot.presentations)
        }
    }

    @Test
    fun `같은 태스크는 한 번만 채운다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            // 끝난 태스크가 엔진에 남아 있는 채로 더 민다. 다시 처리하면 관측 시각이 뒤로 옮겨진다.
            bench.advance(Duration.ofSeconds(30))
            bench.advance(Duration.ofSeconds(30))

            assertEquals(doneAt, bench.slot(S01).observedAt)
        }
    }

    @Test
    fun `셀 밖 목적지와 제시 자리가 아닌 출발지는 각자 규칙대로 다룬다`() {
        Bench().use { bench ->
            // 셀 밖 목적지: 아무 슬롯도 안 바뀐다.
            bench.pickPlace(HUMANOID, "JO-2#dock-3", SOURCE, "dock-3")
            bench.runToEnd(HUMANOID, "JO-2#dock-3")
            assertTrue(bench.cell.snapshot.slots.none { it.occupied })

            // 제시 자리가 아닌 출발지: 놓였다는 보고는 맞으므로 점유로 채우되 자재는 모른다.
            bench.pickPlace(HUMANOID, "JO-3#$S02", "box-7", S02)
            val doneAt = bench.runToEnd(HUMANOID, "JO-3#$S02")
            assertEquals(CellPlace(S02, true, null, doneAt), bench.slot(S02))
        }
    }

    @Test
    fun `GET cell 은 제시 자리와 슬롯을 내고 다른 방법과 경로는 받지 않는다`() {
        Bench().use { bench ->
            bench.pickPlace(HUMANOID, "JO-1#$S01", SOURCE, S01)
            val doneAt = bench.runToEnd(HUMANOID, "JO-1#$S01")

            val base = "http://127.0.0.1:${bench.cell.port}"
            val got = http.send(HttpRequest.newBuilder(URI.create("$base/cell")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, got.statusCode())
            assertEquals("application/json", got.headers().firstValue("Content-Type").orElse(null))
            val body = json.readTree(got.body())
            assertEquals(listOf("presentations", "slots", "signals", "handovers"), body.fieldNames().asSequence().toList())

            val source = body["presentations"].single()
            assertEquals(SOURCE, source["id"].asText())
            assertEquals(true, source["occupied"].asBoolean())
            assertEquals(MATERIAL, source["material"].asText())
            assertTrue(source["observedAt"].isNull)

            val slots = body["slots"]
            assertEquals(CellFixture.STANDARD.slots, slots.map { it["id"].asText() })
            assertEquals(doneAt.toString(), slots[0]["observedAt"].asText())
            assertEquals(MATERIAL, slots[0]["material"].asText())
            assertEquals(false, slots[1]["occupied"].asBoolean())
            assertTrue(slots[1]["material"].isNull)
            assertTrue(slots[1]["observedAt"].isNull)

            val post = http.send(
                HttpRequest.newBuilder(URI.create("$base/cell")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(405, post.statusCode())
            val other = http.send(HttpRequest.newBuilder(URI.create("$base/cells")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(404, other.statusCode())
        }
    }

    @Test
    fun `처음 스냅숏은 슬롯이 비고 제시 자리가 찬 픽스처 그대로다`() {
        Bench().use { bench ->
            val snapshot = bench.cell.snapshot
            assertEquals(CellFixture.STANDARD.slots.map { CellPlace(it, false, null, null) }, snapshot.slots)
            assertNotNull(snapshot.presentations.singleOrNull { it.id == SOURCE && it.occupied })
            assertNull(snapshot.presentations.single().observedAt)
        }
    }

    /** `POST /cell/signals/{name}` 한 번. */
    private fun Bench.post(name: String, body: String, contentType: String = "application/json"): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${cell.port}/cell/signals/$name"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun Bench.signal(name: String): CellSignal = cell.snapshot.signals.single { it.name == name }

    @Test
    fun `표준 픽스처는 신호 일곱을 처음 값으로 내고 GET cell 에 사양과 값이 실린다`() {
        Bench().use { bench ->
            assertEquals(
                listOf(
                    CellSignal("inspection_ready", null, SignalKind.BOOLEAN, false, "true", null),
                    CellSignal("inspection_complete", null, SignalKind.BOOLEAN, false, "false", null),
                    CellSignal("inspection_job", null, SignalKind.TEXT, false, "", null),
                    CellSignal("inspection_job_done", null, SignalKind.TEXT, false, "", null),
                    CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "false", null),
                    CellSignal(GUARD_CLOSED, null, SignalKind.BOOLEAN, true, "true", null),
                    CellSignal(LOT_CODE, null, SignalKind.TEXT, false, "LOT-0001", null),
                ),
                bench.cell.snapshot.signals,
            )

            val got = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bench.cell.port}/cell")).build(), HttpResponse.BodyHandlers.ofString())
            val signals = json.readTree(got.body())["signals"]
            assertEquals(listOf("inspection_ready", "inspection_complete", "inspection_job", "inspection_job_done", RACK_PRESENT, GUARD_CLOSED, LOT_CODE), signals.map { it["name"].asText() })
            val rack = signals.single { it["name"].asText() == RACK_PRESENT }
            assertEquals(listOf("name", "location", "kind", "safety", "value", "observedAt"), rack.fieldNames().asSequence().toList())
            assertEquals("RACK-204", rack["location"].asText())
            assertEquals("BOOLEAN", rack["kind"].asText())
            assertEquals(false, rack["safety"].asBoolean())
            // 값은 종류와 상관없이 문자열이다.
            assertTrue(rack["value"].isTextual, rack.toString())
            assertEquals("false", rack["value"].asText())
            assertTrue(rack["observedAt"].isNull)
            assertTrue(signals.single { it["name"].asText() == GUARD_CLOSED }["location"].isNull)
            assertEquals(true, signals.single { it["name"].asText() == GUARD_CLOSED }["safety"].asBoolean())
            assertEquals("TEXT", signals.single { it["name"].asText() == LOT_CODE }["kind"].asText())
        }
    }

    @Test
    fun `BOOLEAN 신호를 쓰면 값과 관측 시각이 지금 가상 시각으로 바뀌고 응답은 바뀐 신호 하나다`() {
        Bench().use { bench ->
            bench.advance(Duration.ofSeconds(7))
            val first = bench.post(RACK_PRESENT, """{"value":"true"}""")
            assertEquals(200, first.statusCode(), first.body())
            assertEquals("application/json", first.headers().firstValue("Content-Type").orElse(null))
            val body = json.readTree(first.body())
            assertEquals(RACK_PRESENT, body["name"].asText())
            assertEquals("true", body["value"].asText())
            assertEquals(bench.now().toString(), body["observedAt"].asText())
            assertEquals(CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "true", bench.now()), bench.signal(RACK_PRESENT))

            // 시계를 더 민 뒤 다시 쓰면 관측 시각이 따라온다. 다른 신호와 슬롯은 그대로다.
            bench.advance(Duration.ofSeconds(30))
            assertEquals(200, bench.post(RACK_PRESENT, """{"value":"false"}""").statusCode())
            assertEquals("false", bench.signal(RACK_PRESENT).value)
            assertEquals(bench.now(), bench.signal(RACK_PRESENT).observedAt)
            assertEquals("LOT-0001", bench.signal(LOT_CODE).value)
            assertTrue(bench.cell.snapshot.slots.none { it.occupied })
        }
    }

    @Test
    fun `BOOLEAN 신호에 true 나 false 가 아닌 값은 400 이고 값이 그대로다`() {
        Bench().use { bench ->
            listOf("yes", "TRUE", "1", "").forEach { value ->
                val refused = bench.post(RACK_PRESENT, """{"value":"$value"}""")
                assertEquals(400, refused.statusCode(), value)
                assertEquals(SiteCell.SIGNAL_VALUE_INVALID, json.readTree(refused.body())["error"].asText())
            }
            assertEquals(CellSignal(RACK_PRESENT, "RACK-204", SignalKind.BOOLEAN, false, "false", null), bench.signal(RACK_PRESENT))
        }
    }

    @Test
    fun `안전 신호는 값이 맞아도 쓰기를 403 으로 거부하고 값이 그대로다`() {
        Bench().use { bench ->
            listOf("false", "true").forEach { value ->
                val refused = bench.post(GUARD_CLOSED, """{"value":"$value"}""")
                assertEquals(403, refused.statusCode(), refused.body())
                assertEquals(SiteCell.SAFETY_SIGNAL_READ_ONLY, json.readTree(refused.body())["error"].asText())
            }
            assertEquals(CellSignal(GUARD_CLOSED, null, SignalKind.BOOLEAN, true, "true", null), bench.signal(GUARD_CLOSED))
        }
    }

    @Test
    fun `안전 계통 대역은 안전 신호의 값과 관측 시각을 바꾸고 일반 신호와 틀린 값은 거부하며 소프트웨어 쓰기는 여전히 403 이다`() {
        Bench().use { bench ->
            bench.advance(Duration.ofSeconds(3))
            val opened = bench.cell.actuateSafety(GUARD_CLOSED, "false")
            assertEquals(SignalWrite.Written(CellSignal(GUARD_CLOSED, null, SignalKind.BOOLEAN, true, "false", bench.now())), opened)
            assertEquals("false", bench.signal(GUARD_CLOSED).value)

            assertEquals(SiteCell.NOT_SAFETY_SIGNAL, (bench.cell.actuateSafety(RACK_PRESENT, "true") as SignalWrite.Refused).error)
            assertEquals(SiteCell.SIGNAL_VALUE_INVALID, (bench.cell.actuateSafety(GUARD_CLOSED, "open") as SignalWrite.Refused).error)
            assertEquals(SiteCell.UNKNOWN_SIGNAL, (bench.cell.actuateSafety("door_b_closed", "true") as SignalWrite.Refused).error)
            assertEquals("false", bench.signal(RACK_PRESENT).value)

            assertEquals(403, bench.post(GUARD_CLOSED, """{"value":"true"}""").statusCode())
            assertEquals("false", bench.signal(GUARD_CLOSED).value)
        }
    }

    @Test
    fun `모르는 신호는 404 이고 TEXT 신호는 어떤 문자열이든 받는다`() {
        Bench().use { bench ->
            val unknown = bench.post("rack_ready", """{"value":"true"}""")
            assertEquals(404, unknown.statusCode())
            assertEquals(SiteCell.UNKNOWN_SIGNAL, json.readTree(unknown.body())["error"].asText())

            val text = bench.post(LOT_CODE, """{"value":"LOT 7 / 두 번째"}""")
            assertEquals(200, text.statusCode(), text.body())
            assertEquals("LOT 7 / 두 번째", bench.signal(LOT_CODE).value)
            assertEquals(200, bench.post(LOT_CODE, """{"value":""}""").statusCode())
            assertEquals("", bench.signal(LOT_CODE).value)
        }
    }

    @Test
    fun `신호 쓰기는 JSON 객체의 문자열 값만 받고 POST 만 받으며 다른 경로는 404 다`() {
        Bench().use { bench ->
            listOf("""{"value":true}""", """{"value":null}""", """{"other":"true"}""", """["true"]""", "{not json").forEach { body ->
                val reply = bench.post(RACK_PRESENT, body)
                assertEquals(400, reply.statusCode(), body)
                assertEquals(SiteCell.BAD_REQUEST, json.readTree(reply.body())["error"].asText(), body)
            }
            assertEquals(415, bench.post(RACK_PRESENT, """{"value":"true"}""", contentType = "text/plain").statusCode())
            assertEquals("false", bench.signal(RACK_PRESENT).value)

            val base = "http://127.0.0.1:${bench.cell.port}"
            val get = http.send(HttpRequest.newBuilder(URI.create("$base/cell/signals/$RACK_PRESENT")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(405, get.statusCode())
            assertEquals("POST", get.headers().firstValue("Allow").orElse(null))
            listOf("$base/cell/signals/", "$base/cell/signals/$RACK_PRESENT/x", "$base/cell/x").forEach { url ->
                val other = http.send(
                    HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"value":"true"}""")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(404, other.statusCode(), url)
            }
        }
    }

    @Test
    fun `신호 쓰기는 mimic 엔진 잠금을 기다린다`() {
        Bench().use { bench ->
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                bench.mimic.server.exclusive {
                    held.countDown()
                    release.await()
                }
            }.apply { start() }
            held.await()
            val write = CompletableFuture.supplyAsync { bench.post(RACK_PRESENT, """{"value":"true"}""").statusCode() }
            try {
                Thread.sleep(300)
                assertTrue(!write.isDone, "엔진 잠금을 쥔 동안 신호 쓰기가 끝났다")
            } finally {
                release.countDown()
                holder.join()
            }
            assertEquals(200, write.get(5, TimeUnit.SECONDS))
            assertEquals("true", bench.signal(RACK_PRESENT).value)
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"
        const val S01 = "RACK-204.S01"
        const val S02 = "RACK-204.S02"
        const val RACK_PRESENT = "rack_present"
        const val GUARD_CLOSED = "guard_closed"
        const val LOT_CODE = "lot_code"
        val START: Instant = Instant.parse("2026-10-08T00:00:00Z")
    }
}
