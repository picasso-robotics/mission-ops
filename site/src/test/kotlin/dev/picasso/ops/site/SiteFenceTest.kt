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

    private fun config(fenceDir: Path?) = SiteConfig(
        root = root,
        siteId = "site-fence",
        db = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password),
        registryPort = 0,
        operatorToken = "op-test",
        ingestToken = "in-test",
        roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
        fenceDir = fenceDir,
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
        val config = SiteConfig.fromEnv(env + ("SITE_FENCE_DIR" to "var/fence"), root)
        assertEquals(root.resolve("var/fence"), config.fenceDir)
        assertEquals(root.resolve("var/fence/robots"), config.robotFenceDir)
    }

    private companion object {
        const val HUMANOID = "humanoid-01"
    }
}
