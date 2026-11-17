package dev.picasso.ops.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHostApplication
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.site.DbConfig
import dev.picasso.ops.site.RobotRoster
import dev.picasso.ops.site.Site
import dev.picasso.ops.site.SiteConfig
import dev.picasso.registry.PostgresSupport
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * 통합 시험 한 세트. 한 JVM 에 Postgres(registry testFixtures)·registry·mimic·실행 호스트·운영 서비스를 띄운다(스펙 §10,
 * S3a 스펙 §11).
 *
 * 시험 클래스마다 새로 띄우고 닫는다. 클래스 사이에 registry 를 멈추는 시험이 있어 공유하지 않는다.
 * DB 는 띄울 때마다 비운다. `PostgresSupport.reset()` 은 public 만 지우므로 ops·mission 스키마는 따로 지운다. 두 스키마의
 * 덧붙이기 전용 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다.
 *
 * [restartHost] 는 DB 를 지우지 않고 실행 호스트만 같은 포트로 다시 띄운다. 운영 서비스는 기동 때 호스트 주소를 한 번 받으므로
 * 포트가 같아야 다시 띄운 호스트에 닿는다(S3b 스펙 §10 통합 행).
 *
 * 현장 mimic 은 제어자 세대를 대조한다(`FENCED`, picasso ADR 54). 바닥값 파일은 띄울 때마다 새 임시 디렉터리에 두고 닫을 때 지운다 — DB 와 같은
 * 수명이다(DB 를 비우면 리스 세대가 1 부터 다시 시작하므로 앞 바닥값이 남으면 새 세대가 모두 거절된다). [restartHost] 는 같은 현장(같은 바닥값)을
 * 쓴다. 이 세트는 미믹에 직접 명령하지 않는다 — 명령은 모두 세대를 싣는 실행 호스트를 거친다.
 *
 * 실행 호스트의 시계는 현장 시계(`Site.now()`)다. 시험은 `Site.advance` 로 가상 시각을 실제 시각보다 앞으로 밀고, 미들웨어는
 * E2 시간 윈도우의 기준 시각을 mimic 응답 헤더에서 가져오면서 마감은 호스트 시계로 보므로 둘이 같아야 한다(S3a 스펙 §7.2).
 */
class E2eStack private constructor(
    val site: Site,
    private var host: ConfigurableApplicationContext,
    private val ops: ConfigurableApplicationContext,
    val hostUrl: String,
    val opsUrl: String,
    val siteId: String,
    private val permitMapping: Path? = null,
    private val fleet: Boolean = false,
    private val fenceDir: Path? = null,
) : AutoCloseable {

    /** 운영 서비스 응답 한 건. 본문이 비면 널이다. */
    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode = read(opsUrl + path)

    /** 화면처럼 모드·사용자 헤더를 싣고 이 스택의 운영 서비스를 부른다. [mode] 가 널이면 헤더를 싣지 않는다. */
    fun send(
        method: String,
        path: String,
        mode: String?,
        user: String = "kim",
        body: String? = null,
        contentType: String = "application/json",
    ): Reply = send(opsUrl, method, path, mode, user, body, contentType)

    /**
     * 실행 호스트만 닫고 같은 DB·현장으로 같은 포트에 다시 띄운다(재기동). 임무 버전은 DB 에 남고, 미들웨어의 실행은 새 인스턴스라
     * 비어 있다. 닫은 포트를 곧바로 다시 여는 것이 막히면(운영체제가 아직 놓지 않음) 짧게 다시 시도한다.
     */
    fun restartHost() {
        val port = (host as WebServerApplicationContext).webServer.port
        host.close()
        val deadline = Instant.now().plus(RESTART_WAIT)
        while (true) {
            try {
                host = startHost(site, port, permitMapping, fleet)
                return
            } catch (e: Exception) {
                if (Instant.now().isAfter(deadline)) throw IllegalStateException("실행 호스트를 포트 $port 에 다시 띄우지 못했다", e)
                Thread.sleep(200)
            }
        }
    }

    /** 같은 registry 에 운영자 토큰만 다른 운영 서비스를 하나 더 띄운다. 닫는 것은 부르는 쪽이다. */
    fun opsWithToken(token: String): Pair<ConfigurableApplicationContext, String> {
        val context = startOps(site.registryUrl, token, siteId, hostUrl)
        return context to "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"
    }

    override fun close() {
        try {
            ops.close()
        } finally {
            try {
                host.close()
            } finally {
                try {
                    site.close()
                } finally {
                    fenceDir?.toFile()?.deleteRecursively()
                }
            }
        }
    }

    companion object {
        const val OPERATOR_TOKEN = "e2e-operator"
        val root: Path = Path.of("..").toAbsolutePath().normalize()

        /** [restartHost] 가 같은 포트를 다시 여는 상한(실제 시간). */
        private val RESTART_WAIT: Duration = Duration.ofSeconds(10)
        private val http = HttpClient.newHttpClient()
        private val json = ObjectMapper()

        /**
         * @param permitMapping 주면 현장이 이 작업 허가 대응을 선언한 것이다(ADR 52). 실행 호스트에 그대로 넘긴다.
         * @param fleet 참이면 실행 호스트의 `host.fleet.url` 을 현장 플릿 세계 면(셀 대역 서버)으로 둔다. 거짓이면 플릿 포트가 없다(기본 설정).
         */
        fun start(integrationToken: String = "", permitMapping: Path? = null, fleet: Boolean = false): E2eStack {
            SlowCallDiagnostic.reset()
            val siteId = checkNotNull(System.getenv("SITE_ID")) { "SITE_ID 가 없다(루트 .env)" }
            PostgresSupport.reset()
            PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
            PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
            // 실행 호스트는 기동 안에서 ops 의 현장 시간값 뷰를 읽는다(S3c 스펙 §7.1). 호스트가 운영 서비스보다 먼저 뜨므로 ops 스키마를
            // 먼저 올려 둔다. 그러지 않으면 호스트가 미적용으로 뜨고, 운영 서비스가 뜬 뒤 첫 주기 읽기까지 작업 지시를 받지 않는다.
            OpsSchema.migrate(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
            // 바닥값 디렉터리는 DB 와 같은 수명이다 — 방금 비운 DB 의 리스 세대는 1 부터다.
            val fenceDir = java.nio.file.Files.createTempDirectory("e2e-fence")
            val site = try {
                Site.start(
                    SiteConfig(
                        root = root,
                        siteId = siteId,
                        db = db(),
                        registryPort = 0,
                        operatorToken = OPERATOR_TOKEN,
                        ingestToken = "e2e-ingest",
                        roster = RobotRoster.read(root.resolve(SiteConfig.ROSTER)),
                        fenceDir = fenceDir,
                    ),
                )
            } catch (e: Exception) {
                fenceDir.toFile().deleteRecursively()
                throw e
            }
            // 실행 호스트는 Site 다음, 운영 서비스 앞이다. 운영 서비스가 기동에서 호스트 주소를 받는다.
            val host = try {
                startHost(site, permitMapping = permitMapping, fleet = fleet)
            } catch (e: Exception) {
                site.close()
                throw e
            }
            val hostUrl = "http://127.0.0.1:${(host as WebServerApplicationContext).webServer.port}"
            val ops = try {
                startOps(site.registryUrl, OPERATOR_TOKEN, siteId, hostUrl, integrationToken)
            } catch (e: Exception) {
                host.close()
                site.close()
                throw e
            }
            return E2eStack(site, host, ops, hostUrl, "http://127.0.0.1:${(ops as WebServerApplicationContext).webServer.port}", siteId, permitMapping, fleet, fenceDir)
        }

        /** [baseUrl] 의 운영 서비스를 화면처럼 부른다. */
        fun send(
            baseUrl: String,
            method: String,
            path: String,
            mode: String?,
            user: String = "kim",
            body: String? = null,
            contentType: String = "application/json",
        ): Reply {
            val headers = buildMap {
                if (mode != null) {
                    put("X-Ops-Mode", mode)
                    put("X-Ops-User", user)
                }
                if (body != null) put("Content-Type", contentType)
            }
            return call(method, baseUrl + path, headers, body)
        }

        /** 운영 서비스 하나를 GET 으로 읽는다. 200 이 아니면 시험을 멈춘다. */
        fun read(url: String): JsonNode {
            val reply = call("GET", url, emptyMap(), null)
            check(reply.status == 200) { "GET $url → ${reply.status} ${reply.body}" }
            return reply.body!!
        }

        private fun db() = DbConfig(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)

        private fun startOps(registryUrl: String, token: String, siteId: String, hostUrl: String, integrationToken: String = ""): ConfigurableApplicationContext {
            val db = db()
            return OpsApplication.builder().run(
                "--server.port=0",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--ops.registry.url=$registryUrl",
                "--ops.registry.operator-token=$token",
                "--ops.site-id=$siteId",
                "--ops.host.url=$hostUrl",
                "--ops.integration.token=$integrationToken",
            )
        }

        /**
         * 실행 호스트를 이 현장의 mimic gRPC 포트·셀 대역에 붙이고 현장 시계로 띄운다(S3b JSON 계약 공통 규칙). 임무 버전 저장은
         * 같은 Postgres 이고, 모의 실행 프로파일·스키마는 절대 경로로 넘긴다(시험의 작업 디렉터리는 모듈 폴더다).
         *
         * @param port 0 이면 무작위다. 다시 띄울 때는 앞 호스트의 포트다.
         * @param fleet 참이면 플릿 포트를 현장 플릿 세계 면(`/fleet`, 셀 대역과 같은 서버)에 붙인다.
         */
        private fun startHost(site: Site, port: Int = 0, permitMapping: Path? = null, fleet: Boolean = false): ConfigurableApplicationContext {
            val db = db()
            return MissionHostApplication.builder(HostClock { site.now() }).run(
                "--host.work-permit.mapping=${permitMapping ?: ""}",
                "--host.registry.url=${site.registryUrl}",
                "--server.port=$port",
                "--host.mimic.port=${site.mimicPort}",
                "--host.cell.url=http://127.0.0.1:${site.cellPort}",
                "--host.fleet.url=${if (fleet) "http://127.0.0.1:${site.cellPort}" else ""}",
                "--spring.datasource.url=${db.url}",
                "--spring.datasource.username=${db.user}",
                "--spring.datasource.password=${db.password}",
                "--host.mock-run.profile=${root.resolve("mission-host/mock-run/humanoid-a.json")}",
                "--host.mock-run.schema=${root.resolve(SiteConfig.PROFILE_SCHEMA)}",
            )
        }

        private fun call(method: String, url: String, headers: Map<String, String>, body: String?): Reply {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            headers.forEach { (name, value) -> builder.header(name, value) }
            // 진단 전용(SlowCallDiagnostic): 2초를 넘기는 호출이나 503 HOST_SILENT 는 JVM 스레드 덤프를 남긴다. 단언·재시도는 건드리지 않는다.
            val watch = SlowCallDiagnostic.watch("$method $url")
            val response = try {
                http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } finally {
                watch.cancel(false)
            }
            val parsed = response.body().takeIf { it.isNotBlank() }?.let { runCatching { json.readTree(it) }.getOrNull() }
            if (response.statusCode() == 503 && response.body().contains("HOST_SILENT")) {
                SlowCallDiagnostic.dump("503 HOST_SILENT: $method $url")
            }
            return Reply(response.statusCode(), parsed)
        }
    }
}
