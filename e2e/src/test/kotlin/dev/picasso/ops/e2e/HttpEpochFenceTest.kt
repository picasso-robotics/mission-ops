package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.TransportOrder
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHostApplication
import dev.picasso.ops.host.cell.CellBandClient
import dev.picasso.ops.host.fleet.HttpFleetPort
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.host.lease.HostLeadership
import dev.picasso.ops.host.lease.HttpEpoch
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.ops.site.SiteHttpFence
import dev.picasso.registry.PostgresSupport
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 현장 HTTP 면(플릿·셀)의 제어자 세대 대조를 실제 현장(`SITE_HTTP_FENCE=on`)과 실제 실행 호스트로 본다(HA 3단계 PR B1). 새 보유자의 인수 확인은 그 보유자가
 * 내는 것과 같은 요청(세대를 실은 `POST .../controller/confirm`)을 시험이 직접 낸다. 옛 능동의 늦은 변경은 로컬 관문(송신 직전 확인)을 이미 지나 세대를
 * 실은 채 늦게 닿은 요청이다 — 운영 배선과 같은 밀려남 판정([HostLeadership.superseded])에 세대만 지나간 값으로 고정한다.
 *
 * 가상 시계는 밀지 않는다 — 플릿 세계는 시계로 걸으므로, 바뀐 것이 있으면 거절된 요청 탓이다.
 */
class HttpEpochFenceTest {

    private val root: Path = Path.of("..").toAbsolutePath().normalize()
    private val http = HttpClient.newHttpClient()
    private val json = ObjectMapper()

    private class Stack(val site: Site, var host: ConfigurableApplicationContext, val dir: Path, val exits: List<String>, val startHost: () -> ConfigurableApplicationContext) : AutoCloseable {
        /** 실행 호스트를 정상 종료하고 같은 DB·현장으로 새로 띄운다(다음 세대). */
        fun restartHost() {
            host.close()
            host = startHost()
        }

        val leadership: HostLeadership get() = host.getBean(HostLeadership::class.java)
        val base: String get() = "http://127.0.0.1:${site.cellPort}"

        override fun close() {
            try {
                host.close()
            } finally {
                try {
                    site.close()
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    }

    private fun start(): Stack {
        PostgresSupport.reset()
        PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        OpsSchema.migrate(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        val db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        val dir = Files.createTempDirectory("e2e-http-fence")
        val site = Site.start(
            SiteConfig(
                root = root, siteId = "site-http-fence", db = db, registryPort = 0, operatorToken = "op", ingestToken = "in",
                roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)), fenceDir = dir, httpFence = true,
            ),
        )
        val exits = CopyOnWriteArrayList<String>()
        val startHost = {
            MissionHostApplication.builder(HostClock { site.now() }, exit = HostExit { exits += it }).run(
                "--server.port=0",
                "--host.work-permit.mapping=",
                "--host.registry.url=",
                "--host.mimic.port=${site.mimicPort}",
                "--host.cell.url=http://127.0.0.1:${site.cellPort}",
                "--host.fleet.url=http://127.0.0.1:${site.cellPort}",
                "--host.fence-confirm.interval=PT1H",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--host.mock-run.profile=${root.resolve("mission-host/mock-run/humanoid-a.json")}",
                "--host.mock-run.schema=${root.resolve(SiteConfig.PROFILE_SCHEMA)}",
            )
        }
        val host = try {
            startHost()
        } catch (e: Exception) {
            site.close()
            dir.toFile().deleteRecursively()
            throw e
        }
        return Stack(site, host, dir, exits, startHost)
    }

    private fun eventually(what: String, within: Duration = Duration.ofSeconds(10), check: () -> Boolean) {
        val deadline = Instant.now().plus(within)
        while (!check()) {
            check(Instant.now().isBefore(deadline)) { "$within 안에 $what 이(가) 되지 않았다" }
            Thread.sleep(20)
        }
    }

    /** 새 보유자의 인수 확인 한 번. */
    private fun confirm(stack: Stack, path: String, epoch: Long): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(stack.base + path)).header("Content-Type", "application/json")
            .header(SiteHttpFence.EPOCH_HEADER, epoch.toString()).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `셀 인수 확인이 획득 직후 셀 바닥값을 리스 세대로 올리고 새 세대 확인 뒤 옛 능동의 늦은 맡기기·취소·셀 쓰기·결함 주입은 효과 없이 거절되며 옛 능동은 잃는다`() {
        start().use { stack ->
            eventually("발행") { stack.leadership.state == LeaderState.ISSUING }
            val old = stack.leadership
            val generation = old.controllerEpoch()
            eventually("셀 인수 확인") { stack.site.httpFence.floor(SiteHttpFence.CELL) == generation }
            eventually("플릿 인수 확인(맡기기 전)") { stack.site.httpFence.floor(SiteHttpFence.FLEET) == generation }
            assertEquals(0, stack.site.fleet.world.dispatches)

            // 옛 능동이 정상으로 맡긴다(실행 호스트의 플릿 포트 — 로컬 관문을 지나 세대를 싣는다). 플릿 바닥값이 이 세대로 오른다.
            val fleet = stack.host.getBean(AmrFleetPort::class.java)
            val handle = assertNotNull(fleet.dispatch(TransportOrder("JO-1#HU-1042", "HU-1042", "OUT-07", "LINE-IN-03")))
            assertEquals(generation, stack.site.httpFence.floor(SiteHttpFence.FLEET))

            // 늦게 닿을 변경 — 세대는 지나간 값(옛 능동이 보낼 때 실은 값), 밀려남은 옛 능동의 리더로 간다(운영 배선의 판정).
            val lateFleet = HttpFleetPort(stack.base, epoch = HttpEpoch("플릿", { generation }) { r, m, f -> old.superseded(r, m, f) })
            val lateCell = CellBandClient(stack.base, epoch = HttpEpoch("셀", { generation }) { r, m, f -> old.superseded(r, m, f) })

            // 새 보유자(다음 세대)의 플릿 인수 확인.
            val next = generation + 1
            val confirmed = confirm(stack, "/fleet/controller/confirm", next)
            assertEquals(200, confirmed.statusCode(), confirmed.body())
            assertEquals("DURABLE_CONFIRMED", json.readTree(confirmed.body())["fenceStatus"].asText())

            val dispatches = stack.site.fleet.world.dispatches
            val mimicHandle = with(stack.site.fleet) { internal(handle.id)!! }
            val status = stack.site.fleet.world.status(mimicHandle)
            val cell = stack.site.cellSnapshot
            assertFailsWith<CommandNotSent> { lateFleet.dispatch(TransportOrder("JO-2#HU-1042", "HU-1042", "OUT-07", "LINE-IN-03")) }
            assertFailsWith<CommandNotSent> { lateFleet.cancel(handle) }
            assertEquals(dispatches, stack.site.fleet.world.dispatches)
            assertEquals(status, stack.site.fleet.world.status(mimicHandle), "취소가 닿지 않았다")
            // 바닥값(다음 세대) > 내 세대 — 옛 능동은 플릿 응답으로 밀려나 잃는다.
            assertEquals(LeaderState.LOST, old.state)
            assertTrue(old.lostReason!!.contains("robot=플릿"), old.lostReason)
            eventually("종료 훅") { stack.exits.isNotEmpty() }
            // 잃은 능동의 실행 호스트 포트는 더 보내지 않는다(로컬 관문).
            assertFailsWith<CommandNotSent> { fleet.dispatch(TransportOrder("JO-3#HU-1042", "HU-1042", "OUT-07", "LINE-IN-03")) }

            // 셀 면: 새 보유자의 셀 인수 확인 뒤 옛 능동의 늦은 신호 쓰기·결함 주입.
            assertEquals(200, confirm(stack, "/cell/controller/confirm", next).statusCode())
            val written = assertNotNull(lateCell.writeSignal("lot_code", """{"value":"LOT-LATE"}""".toByteArray()))
            assertEquals(409, written.status)
            assertEquals("STALE_CONTROLLER" to "BELOW_FLOOR", json.readTree(written.body).let { it["error"].asText() to it["reason"].asText() })
            val fault = assertNotNull(lateCell.injectFault("""{"robotId":"humanoid-01","kind":"CONNECTION","state":"OFFLINE"}""".toByteArray()))
            assertEquals(409, fault.status)
            assertEquals(cell.signals, stack.site.cellSnapshot.signals, "신호 값이 그대로다")
            assertEquals(cell, stack.site.cellSnapshot)
            // 결함 주입이 닿지 않았다 — 새 세대로 ONLINE 을 넣으면 바뀐 것이 없다.
            val online = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(stack.base + "/faults")).header("Content-Type", "application/json")
                    .header(SiteHttpFence.EPOCH_HEADER, next.toString())
                    .POST(HttpRequest.BodyPublishers.ofString("""{"robotId":"humanoid-01","kind":"CONNECTION","state":"ONLINE"}""")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, online.statusCode(), online.body())
            assertEquals(false, json.readTree(online.body())["changed"].asBoolean())
        }
    }

    @Test
    fun `새 보유자의 플릿 인수 확인이 첫 맡기기 없이 플릿 바닥값을 올려 옛 세대의 늦은 맡기기를 거절한다`() {
        start().use { stack ->
            eventually("발행") { stack.leadership.state == LeaderState.ISSUING }
            val first = stack.leadership.controllerEpoch()
            eventually("앞 보유자의 플릿 인수 확인") { stack.site.httpFence.floor(SiteHttpFence.FLEET) == first }
            // 정상 종료 인수 — 새 보유자는 맡기기를 하나도 내지 않는다.
            stack.restartHost()
            eventually("새 보유자의 발행") { stack.leadership.state == LeaderState.ISSUING }
            val next = stack.leadership.controllerEpoch()
            assertTrue(next > first)
            eventually("새 보유자의 플릿 인수 확인") { stack.site.httpFence.floor(SiteHttpFence.FLEET) == next }
            assertEquals(0, stack.site.fleet.world.dispatches)

            val superseded = CopyOnWriteArrayList<Triple<String, Long, Long>>()
            val late = HttpFleetPort(stack.base, epoch = HttpEpoch("플릿", { first }) { r, m, f -> superseded += Triple(r, m, f) })
            assertFailsWith<CommandNotSent> { late.dispatch(TransportOrder("JO-9#HU-1042", "HU-1042", "OUT-07", "LINE-IN-03")) }
            assertEquals(0, stack.site.fleet.world.dispatches, "옛 세대의 늦은 맡기기가 닿지 않았다")
            assertEquals(listOf(Triple("플릿", first, next)), superseded.toList())
            assertEquals(LeaderState.ISSUING, stack.leadership.state)
        }
    }
}
