package dev.picasso.ops.site

import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.registry.PostgresSupport
import io.grpc.ManagedChannelBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 현장 수신자의 제어자 세대 대조(picasso ADR 54). `SITE_FENCE_DIR`([SiteConfig.fenceDir])이 있으면 mimic 이 `FENCED` 로 뜨고 기체 바닥값을 그 아래
 * `robots/` 의 파일에 둔다. 없으면 오늘 거동(`UNFENCED`)이다. 다른 현장 시험은 대조를 켜지 않는다.
 */
class SiteFenceTest {

    private val root = Path.of("..").toAbsolutePath().normalize()

    private fun config(fenceDir: Path?, httpFence: Boolean = false) = SiteConfig(
        root = root,
        siteId = "site-fence",
        db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
        registryPort = 0,
        operatorToken = "op-test",
        ingestToken = "in-test",
        roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
        fenceDir = fenceDir,
        httpFence = httpFence,
    )

    private fun client(site: Site, epoch: Long): PicassoClient =
        PicassoClient(ManagedChannelBuilder.forAddress("127.0.0.1", site.mimicPort).usePlaintext().build(), "site-fence-test", controllerEpoch = { epoch })

    @Test
    fun `바닥값 디렉터리가 있으면 mimic 이 세대를 대조하고 바닥값을 파일에 두어 현장을 다시 띄워도 옛 세대를 거절한다`() {
        val dir = Files.createTempDirectory("site-fence")
        try {
            PostgresSupport.reset()
            Site.start(config(dir)).use { site ->
                assertTrue(site.fenced)
                // 세대를 싣지 않은 명령은 거절된다.
                val missing = client(site, 0).start(HUMANOID, "JO-0#T1", 1, "navigate_to", emptyList())
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, missing.rejection.code)
                // 세대 3 의 인수 확인 읽기: 바닥값을 파일에 적고 내구 저장·정리 확인으로 답한다.
                val confirmed = client(site, 3).snapshot(HUMANOID)
                assertEquals(3L, confirmed.header.controllerEpoch)
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, confirmed.fenceStatus)
                assertEquals("3", Files.readString(dir.resolve(SiteConfig.ROBOT_FENCE).resolve("$HUMANOID.floor")).trim())
                // 같은 디렉터리를 다른 현장이 함께 쓰지 못한다.
                assertFailsWith<IllegalStateException> { Site.start(config(dir)) }
            }
            PostgresSupport.reset()
            Site.start(config(dir)).use { site ->
                // 다시 뜬 수신자는 파일의 바닥값(3)을 든다 — 옛 세대 2 의 명령은 거절된다.
                val stale = client(site, 2).start(HUMANOID, "JO-2#T1", 1, "navigate_to", emptyList())
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, stale.rejection.code)
                assertEquals(2L to 3L, stale.rejection.requestedControllerEpoch to stale.rejection.controllerEpochFloor)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `바닥값 디렉터리가 없으면 대조하지 않는다`() {
        PostgresSupport.reset()
        Site.start(config(null)).use { site ->
            assertFalse(site.fenced)
            val read = client(site, 3).snapshot(HUMANOID)
            assertEquals(0L, read.header.controllerEpoch)
            assertEquals(FenceStatus.FENCE_STATUS_UNSPECIFIED, read.fenceStatus)
        }
    }

    @Test
    fun `환경 변수 SITE_FENCE_DIR 이 바닥값 디렉터리를 정하고 비어 있으면 대조하지 않는다`() {
        val env = mapOf(
            "SITE_ID" to "site-x", "PICASSO_DB_URL" to "jdbc:postgresql://h/db", "PICASSO_DB_USER" to "u", "PICASSO_DB_PASSWORD" to "p",
            "REGISTRY_PORT" to "8781", "MIMIC_GRPC_PORT" to "8783", "SITE_CELL_PORT" to "8784", "PICASSO_OPERATOR_TOKEN" to "op", "PICASSO_INGEST_TOKEN" to "in",
        )
        assertEquals(null, SiteConfig.fromEnv(env, root).fenceDir)
        assertEquals(null, SiteConfig.fromEnv(env + ("SITE_FENCE_DIR" to " "), root).fenceDir)
        val config = SiteConfig.fromEnv(env + ("SITE_FENCE_DIR" to "var/fence") + ("SITE_HTTP_FENCE" to "off"), root)
        assertEquals(root.resolve("var/fence"), config.fenceDir)
        assertEquals(root.resolve("var/fence/robots"), config.robotFenceDir)
    }

    private val env = mapOf(
        "SITE_ID" to "site-x", "PICASSO_DB_URL" to "jdbc:postgresql://h/db", "PICASSO_DB_USER" to "u", "PICASSO_DB_PASSWORD" to "p",
        "REGISTRY_PORT" to "8781", "MIMIC_GRPC_PORT" to "8783", "SITE_CELL_PORT" to "8784", "PICASSO_OPERATOR_TOKEN" to "op", "PICASSO_INGEST_TOKEN" to "in",
    )

    @Test
    fun `SITE_HTTP_FENCE 는 on 과 off 만 받고 디렉터리가 있는데 빠지거나 틀리면 또 디렉터리 없이 on 이면 저장소를 열기 전에 기동을 거부한다`() {
        val dir = Files.createTempDirectory("site-http-switch").resolve("fence")
        val relative = root.relativize(dir).toString()
        fun envOf(vararg pairs: Pair<String, String>) = SiteConfig.fromEnv(env + pairs, root)

        val on = envOf("SITE_FENCE_DIR" to relative, "SITE_HTTP_FENCE" to "on")
        assertEquals(true to dir.resolve(SiteConfig.SITE_FENCE), on.httpFence to on.siteFenceDir)
        assertEquals(true, envOf("SITE_FENCE_DIR" to relative, "SITE_HTTP_FENCE" to " on ").httpFence)
        val off = envOf("SITE_FENCE_DIR" to relative, "SITE_HTTP_FENCE" to "off")
        assertEquals(false to null, off.httpFence to off.siteFenceDir)
        assertEquals(dir.resolve(SiteConfig.ROBOT_FENCE), off.robotFenceDir, "off 여도 기체 대조는 그대로다")
        assertEquals(false, envOf().httpFence, "디렉터리도 스위치도 없으면 오늘 거동이다")
        assertEquals(false, envOf("SITE_HTTP_FENCE" to "off").httpFence)

        // 디렉터리가 있는데 빠졌거나(빈 값 포함) 오타면 거부한다 — 두 선택의 뜻과 켜는 순서를 안내한다.
        listOf(emptyArray<Pair<String, String>>(), arrayOf("SITE_HTTP_FENCE" to " "), arrayOf("SITE_HTTP_FENCE" to "of"), arrayOf("SITE_HTTP_FENCE" to "ON"), arrayOf("SITE_HTTP_FENCE" to "true"))
            .forEach { extra ->
                val e = assertFailsWith<IllegalArgumentException>(extra.toList().toString()) { envOf("SITE_FENCE_DIR" to relative, *extra) }
                assertTrue("on" in e.message!! && "off" in e.message!! && "실행 호스트를 모두" in e.message!!, e.message)
            }
        // 디렉터리 없이 on, 디렉터리 없이 오타도 거부한다.
        assertTrue("SITE_FENCE_DIR" in assertFailsWith<IllegalArgumentException> { envOf("SITE_HTTP_FENCE" to "on") }.message!!)
        assertFailsWith<IllegalArgumentException> { envOf("SITE_HTTP_FENCE" to "yes") }
        // 거부는 저장소를 열기 전이다 — 바닥값 디렉터리가 생기지 않았다.
        assertTrue(Files.notExists(dir), "거부한 설정이 바닥값 디렉터리를 만들었다")
        // 코드로 짓는 설정도 디렉터리 없이 켜지 못한다.
        assertFailsWith<IllegalArgumentException> { config(null, httpFence = true) }
        dir.parent.toFile().deleteRecursively()
    }

    private fun post(site: Site, path: String, body: String, epoch: Long?): java.net.http.HttpResponse<String> {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:${site.cellPort}$path"))
            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        epoch?.let { builder.header(SiteHttpFence.EPOCH_HEADER, it.toString()) }
        return java.net.http.HttpClient.newHttpClient().send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    }

    private fun dispatch(site: Site, reference: String, epoch: Long) =
        post(site, "/fleet/transports", """{"reference":"$reference","containerId":"HU-1042","source":"OUT-07","destination":"LINE-IN-03"}""", epoch)

    @Test
    fun `SITE_HTTP_FENCE on 이면 플릿·셀 바닥값을 site 아래 파일에 두어 다시 띄워도 옛 세대를 거절하고 off 는 그 파일을 건드리지 않는다`() {
        val dir = Files.createTempDirectory("site-http-fence")
        try {
            PostgresSupport.reset()
            Site.start(config(dir, httpFence = true)).use { site ->
                assertTrue(site.httpFence.fenced)
                assertEquals(200, post(site, "/fleet/controller/confirm", "{}", 3).statusCode())
                assertEquals(200, post(site, "/cell/controller/confirm", "{}", 4).statusCode())
                val siteDir = dir.resolve(SiteConfig.SITE_FENCE)
                assertEquals("3" to "4", Files.readString(siteDir.resolve("fleet.floor")).trim() to Files.readString(siteDir.resolve("cell.floor")).trim())
                assertTrue(Files.notExists(dir.resolve(SiteConfig.ROBOT_FENCE).resolve("fleet.floor")), "기체 바닥값과 섞이지 않는다")
            }
            PostgresSupport.reset()
            Site.start(config(dir, httpFence = true)).use { site ->
                assertEquals(3L to 4L, site.httpFence.floor(SiteHttpFence.FLEET) to site.httpFence.floor(SiteHttpFence.CELL))
                val stale = dispatch(site, "JO-1#HU-1042", 2)
                assertEquals(409, stale.statusCode(), stale.body())
                assertEquals(0, site.fleet.world.dispatches)
                assertEquals(409, post(site, "/cell/signals/lot_code", """{"value":"X"}""", 3).statusCode())
            }
            // off 로 띄우면 대조하지 않고(바닥값 0) 남은 파일은 그대로다.
            PostgresSupport.reset()
            val siteDir = dir.resolve(SiteConfig.SITE_FENCE)
            val before = Files.list(siteDir).use { files -> files.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() } }
            Site.start(config(dir, httpFence = false)).use { site ->
                assertTrue(!site.httpFence.fenced)
                val answered = dispatch(site, "JO-1#HU-1042", 2)
                assertEquals(200 to "0", answered.statusCode() to answered.headers().firstValue(SiteHttpFence.FLOOR_HEADER).orElse(null))
            }
            val after = Files.list(siteDir).use { files -> files.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() } }
            assertEquals(before, after)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `플릿·셀 바닥값 파일이 손상이거나 다른 프로세스가 그 디렉터리를 쥐면 현장이 뜨지 않고 기체 바닥값 잠금도 놓는다`() {
        val dir = Files.createTempDirectory("site-http-broken")
        try {
            val siteDir = Files.createDirectories(dir.resolve(SiteConfig.SITE_FENCE))
            Files.writeString(siteDir.resolve("fleet.floor"), "x\n")
            PostgresSupport.reset()
            val corrupt = assertFailsWith<IllegalStateException> { Site.start(config(dir, httpFence = true)) }
            assertTrue("손상" in corrupt.message!!, corrupt.message)
            Files.delete(siteDir.resolve("fleet.floor"))
            dev.picasso.adapter.host.FileFenceStore(siteDir).use {
                PostgresSupport.reset()
                val locked = assertFailsWith<IllegalStateException> { Site.start(config(dir, httpFence = true)) }
                assertTrue("다른 호스트" in locked.message!!, locked.message)
            }
            // 실패한 기동이 기체 바닥값 디렉터리 잠금을 놓았다 — 같은 디렉터리로 다시 뜬다.
            PostgresSupport.reset()
            Site.start(config(dir, httpFence = true)).use { site -> assertTrue(site.fenced && site.httpFence.fenced) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
    }
}
