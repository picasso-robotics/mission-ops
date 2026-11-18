package dev.picasso.ops.load

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.adapter.host.FileFenceStore
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FencedCall
import dev.picasso.mimic.cli.MimicCli
import dev.picasso.ops.host.HostClock
import dev.picasso.ops.host.MissionHost
import dev.picasso.ops.host.MissionHostApplication
import dev.picasso.ops.host.lease.HostExit
import dev.picasso.ops.service.OpsApplication
import dev.picasso.ops.service.store.OpsSchema
import dev.picasso.ops.site.SiteCell
import dev.picasso.ops.site.SiteFleet
import dev.picasso.ops.site.SiteHttpFence
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 측정 한 세트. e2e `E2eStack`·mission-host `HostBench` 의 배선을 옮겨 왔고(그 파일은 고치지 않는다) 다른 점은 넷이다.
 *
 * - Postgres 는 이 세트가 따로 띄운다. `pg_stat_statements` 를 싣고(track=top, 유틸리티 문장 제외) 세트마다 새 컨테이너다.
 *   `fsync=off` 는 Testcontainers 기본값 그대로다(registry `PostgresSupport` 와 같은 조건).
 * - mimic 기체 수를 [robots] 로 정한다(humanoid-a 프로파일, `humanoid-001`…).
 * - 셀 대역은 대역(stub)이고 [cellBody] 를 바꾸면 다음 pump 부터 호스트가 읽는다. 스테이션 신호 쌍을 여기서 늘린다.
 * - 운영 서비스의 주기 작업 셋(스테이션 tick·결과 수집·결과 전달)은 하루 주기로 미뤄 끈다. 하네스가 같은 코드를 직접 불러 잰다.
 *   registry 는 띄우지 않는다(측정 경로가 registry 를 부르지 않는다).
 *
 * [fenced] 면 mimic 이 제어자 세대를 대조하고(`FENCED`, picasso ADR 54) 바닥값을 세트마다 새 임시 디렉터리의 `FileFenceStore` 에 둔다(컨테이너 DB 와
 * 같은 수명). 인수 시나리오의 대조 변형만 켠다 — 나머지 측정은 오늘 거동(`UNFENCED`)이다. 그때 셀 대역 대역도 현장 HTTP 면 대조(`SITE_HTTP_FENCE=on` 과
 * 같은 [SiteHttpFence], 바닥값은 같은 디렉터리의 `site/`)로 신호 쓰기를 맞대고 인수 확인에 답한다.
 *
 * [fleet] 면 같은 대역 서버에 현장 플릿 세계 면(`/fleet`, 현장의 [SiteFleet] 그대로 — 인계 자리 `OUT-07`·`LINE-IN-03`, 처음 용기 `HU-1042`, 시계는 mimic
 * 가상 시계라 하네스가 밀지 않으면 운반이 걷지 않음)을 붙이고 실행 호스트의 `host.fleet.url` 을 그리로 둔다. 대조는 [fenced] 를 따른다(키 `fleet`).
 */
class LoadStack(
    val robots: Int,
    withOps: Boolean = true,
    private val hostArgs: List<String> = emptyList(),
    val fenced: Boolean = false,
    val fleet: Boolean = false,
) : AutoCloseable {

    val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("picasso").withUsername("picasso").withPassword("picasso")
        .withCommand(
            "postgres", "-c", "fsync=off",
            "-c", "shared_preload_libraries=pg_stat_statements",
            "-c", "pg_stat_statements.track=top",
            "-c", "pg_stat_statements.track_utility=off",
            "-c", "pg_stat_statements.max=10000",
        )
        .also { it.start() }

    fun connection(): Connection = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    init {
        // 실행 호스트는 기동 안에서 ops 의 현장 시간값 뷰를 읽는다. 호스트가 운영 서비스보다 먼저 뜨므로 ops 스키마를 먼저 올린다(E2eStack 과 같음).
        OpsSchema.migrate(pg.jdbcUrl, pg.username, pg.password)
        sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
    }

    val robotIds: List<String> = (1..robots).map { "humanoid-%03d".format(it) }

    private val fenceDir: Path? = if (fenced) java.nio.file.Files.createTempDirectory("load-fence") else null
    private val fenceStore: FileFenceStore? = fenceDir?.let(::FileFenceStore)

    /** 셀 대역 대역의 현장 HTTP 면 대조. [fenced] 가 아니면 대조하지 않는다(응답 바닥값 0, 인수 확인은 `UNSPECIFIED`). */
    private val siteFenceStore: FileFenceStore? = fenceDir?.let { FileFenceStore(it.resolve("site")) }
    private val cellFence = SiteHttpFence(siteFenceStore?.let { ControllerFence(FenceMode.FENCED, it) } ?: ControllerFence())

    val mimic: MimicCli.Started = checkNotNull(
        MimicCli().start(
            robots = robotIds.associateWith { ROOT.resolve("picasso/profile/profiles/humanoid-a.json") },
            schema = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json"),
            port = 0,
            virtual = true,
            seed = 0L,
            err = System.err,
            fence = fenceStore?.let { ControllerFence(FenceMode.FENCED, it) } ?: ControllerFence(),
        ),
    ) { "mimic 기동 거부" }

    init {
        robotIds.forEach { mimic.instance(it)!!.knownSiteNames = listOf("dock-3", "bay-7") }
    }

    @Volatile
    var cellBody: String = cell(0)

    /** 현장 플릿 세계([fleet] 일 때만). 하네스가 맡긴 수·운반 상태를 본다. */
    val fleetSite: SiteFleet? = if (fleet) SiteFleet(mimic, listOf("OUT-07", "LINE-IN-03"), mapOf("OUT-07" to "HU-1042"), fence = cellFence) else null

    /** 대역의 현장 HTTP 면 대조(거절 수를 본다). */
    val httpFence: SiteHttpFence get() = cellFence

    private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/cell") { exchange ->
            exchange.requestBody.readAllBytes()
            val scope = cellFence.scope(exchange, SiteHttpFence.CELL)
            val (status, bytes) = scope.use {
                when {
                    scope.malformed != null -> 400 to ByteArray(0)
                    exchange.requestURI.path == SiteCell.CONFIRM -> cellFence.confirmReply(SiteHttpFence.CELL, scope.requested, "load")
                    exchange.requestMethod == "POST" -> scope.mutation(FencedCall.START) { 200 to """{"name":"x","value":"true"}""".toByteArray() }
                    else -> 200 to cellBody.toByteArray()
                }
            }
            scope.stamp()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
        fleetSite?.let { world -> createContext(SiteFleet.PATH) { exchange -> world.handle(exchange) {} } }
        start()
    }

    /** 결과 전달(callback) 대상. 200 을 돌려주고 받은 수만 센다. */
    val callbackHits = AtomicInteger()
    private val callback: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/results") { exchange ->
            exchange.requestBody.readAllBytes()
            callbackHits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        start()
    }
    val callbackUrl: String get() = "http://127.0.0.1:${callback.address.port}/results"

    fun now(): Instant = mimic.server.exclusive { mimic.instance(robotIds.first())!!.clock.now() }

    /** 실행 호스트 하나를 이 세트의 DB·mimic·셀 대역에 띄운다. [hostArgs] 는 기본 인자 뒤에 붙는다(뒤가 이긴다). */
    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }, exit = LOST).run(
        "--server.port=0",
        "--host.mimic.port=${mimic.server.port}",
        "--host.cell.url=http://127.0.0.1:${cell.address.port}",
        "--spring.datasource.url=${pg.jdbcUrl}",
        "--spring.datasource.username=${pg.username}",
        "--spring.datasource.password=${pg.password}",
        "--host.mock-run.profile=${ROOT.resolve("mission-host/mock-run/humanoid-a.json")}",
        "--host.mock-run.schema=${ROOT.resolve("picasso/profile/schema/capability-profile.schema.json")}",
        "--host.work-permit.mapping=",
        "--host.registry.url=",
        "--host.fleet.url=${if (fleet) "http://127.0.0.1:${cell.address.port}" else ""}",
        "--logging.level.root=WARN",
        *hostArgs.toTypedArray(),
    )

    /** 지금 뜬 실행 호스트. [killHost] 뒤 [replaceHost] 로 바뀐다. */
    var host: ConfigurableApplicationContext = startHost()
        private set
    val hostUrl: String get() = "http://127.0.0.1:${(host as WebServerApplicationContext).webServer.port}"

    /**
     * 실행 호스트를 멈춤 기록 없이 죽인다(mission-host `HostBench.killHost` 와 같은 흉내). 커넥션 풀을 먼저 닫아 심장박동과 멈춤 쓰기를 끊고 그 뒤
     * 컨텍스트를 닫는다. [replaceHost] 전에는 [host] 가 닫힌 컨텍스트다.
     */
    fun killHost() {
        (host.getBean(javax.sql.DataSource::class.java) as AutoCloseable).close()
        host.close()
    }

    /**
     * 지금 호스트의 주기 pump 만 멈춘다(하네스가 `pumpOnce()` 를 직접 부를 때). 리스와 심장박동은 그대로다 — 호스트를 닫으면 리스를 내놓아
     * pump 의 기록이 리스 가드에 막힌다. 운영 코드는 그대로 두고 반사로 pump 실행기를 내린다.
     */
    fun stopPump() {
        val pumper = MissionHost::class.java.getDeclaredField("pumper").apply { isAccessible = true }.get(missionHost) as java.util.concurrent.ExecutorService
        pumper.shutdownNow()
        check(pumper.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) { "pump 가 멈추지 않았다" }
    }

    /** 새 실행 호스트를 같은 인자로 띄워 [host] 로 둔다. */
    fun replaceHost() {
        host = startHost()
    }
    val missionHost: MissionHost get() = host.getBean(MissionHost::class.java)

    val ops: ConfigurableApplicationContext? = if (!withOps) null else OpsApplication.builder().run(
        "--server.port=0",
        "--spring.datasource.url=${pg.jdbcUrl}",
        "--spring.datasource.username=${pg.username}",
        "--spring.datasource.password=${pg.password}",
        "--ops.registry.url=http://127.0.0.1:9",
        "--ops.registry.operator-token=load",
        "--ops.site-id=${System.getenv("SITE_ID") ?: "site-01"}",
        "--ops.host.url=$hostUrl",
        "--ops.stations.poll-ms=86400000",
        "--ops.results.poll-ms=86400000",
        "--ops.results.delivery-ms=86400000",
        "--logging.level.root=WARN",
    )

    val opsUrl: String get() {
        val context = checkNotNull(ops) { "운영 서비스 없이 띄운 세트다" } as WebServerApplicationContext
        return "http://127.0.0.1:" + context.webServer.port
    }

    /** 운영 서비스 REST 하나(화면이 부르는 `/api` 아래 경로). 본문과 걸린 시간(ms), 응답 바이트 수. */
    fun opsGet(path: String): Triple<JsonNode, Double, Int> {
        val started = System.nanoTime()
        val response = HTTP.send(HttpRequest.newBuilder(URI.create(opsUrl + path)).build(), HttpResponse.BodyHandlers.ofString())
        val ms = (System.nanoTime() - started) / 1e6
        check(response.statusCode() == 200) { "$path → ${response.statusCode()} ${response.body()}" }
        return Triple(JSON.readTree(response.body()), ms, response.body().toByteArray().size)
    }

    /** 이 JVM 에서 컨테이너까지의 JDBC 왕복(`SELECT 1`, 연결 하나, 예열 50회 뒤 200회). 회차 시간의 바닥이다. */
    fun roundTrip(): Timings = connection().use { c ->
        c.prepareStatement("SELECT 1").use { p ->
            repeat(50) { p.executeQuery().close() }
            Timings().also { t -> repeat(200) { t.time { p.executeQuery().close() } } }
        }
    }

    /** 컨테이너의 Postgres 판. */
    fun serverVersion(): String = query("SHOW server_version") { it.getString(1) }.single()

    fun <T : Any> opsBean(type: Class<T>): T = checkNotNull(ops) { "운영 서비스 없이 띄운 세트다" }.getBean(type)

    fun sql(statement: String) = connection().use { c -> c.createStatement().use { it.execute(statement) } }

    fun <T> query(statement: String, read: (java.sql.ResultSet) -> T): List<T> = connection().use { c ->
        c.createStatement().use { s -> s.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }
    }

    /** 호스트 REST 하나. 본문과 걸린 시간(ms), 응답 바이트 수. */
    fun hostGet(path: String): Triple<JsonNode, Double, Int> {
        val started = System.nanoTime()
        val response = HTTP.send(HttpRequest.newBuilder(URI.create(hostUrl + path)).build(), HttpResponse.BodyHandlers.ofString())
        val ms = (System.nanoTime() - started) / 1e6
        check(response.statusCode() == 200) { "$path → ${response.statusCode()} ${response.body()}" }
        return Triple(JSON.readTree(response.body()), ms, response.body().toByteArray().size)
    }

    fun hostPost(path: String, body: String): JsonNode {
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create(hostUrl + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return JSON.readTree(response.body())
    }

    override fun close() {
        runCatching { ops?.close() }
        runCatching { host.close() }
        runCatching { cell.stop(0) }
        runCatching { callback.stop(0) }
        runCatching { mimic.server.shutdown() }
        runCatching { fenceStore?.close() }
        runCatching { siteFenceStore?.close() }
        runCatching { fenceDir?.toFile()?.deleteRecursively() }
        runCatching { pg.stop() }
    }

    companion object {
        val ROOT: Path = Path.of("").toAbsolutePath().normalize()
        val HTTP: HttpClient = HttpClient.newHttpClient()
        val JSON = ObjectMapper()

        /** 리스를 잃으면 하네스 JVM 을 끝내지 않고 알린다(하네스는 잃을 일이 없어야 한다 — 잃으면 그 회차 수치는 쓰지 않는다). */
        val LOST = HostExit { reason -> System.err.println("실행 호스트가 리스를 잃었다(이 회차 수치는 쓰지 않는다): $reason") }

        /**
         * `HostBench.STANDARD_CELL` 모양에 스테이션 [stations] 개의 신호 쌍(준비 참·완료 거짓, 일반 BOOLEAN)을 더한다.
         * 스테이션 `st-NNN` 의 준비 신호는 `st-NNN-ready`, 완료 신호는 `st-NNN-done` 이다.
         */
        fun cell(stations: Int): String {
            val extra = (1..stations).joinToString("") { i ->
                val id = stationId(i)
                """,{"name":"$id-ready","location":null,"kind":"BOOLEAN","safety":false,"value":"true","observedAt":null}""" +
                    """,{"name":"$id-done","location":null,"kind":"BOOLEAN","safety":false,"value":"false","observedAt":null}"""
            }
            return """
                {"presentations":[{"id":"SEQ-IN-02.BIN-A","occupied":true,"material":"ENGINE-COVER-A","observedAt":null}],
                 "slots":[{"id":"RACK-204.S01","occupied":false,"material":null,"observedAt":null}],
                 "signals":[{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"false","observedAt":null},
                            {"name":"guard_closed","location":null,"kind":"BOOLEAN","safety":true,"value":"true","observedAt":null}$extra]}
            """.trimIndent()
        }

        fun stationId(i: Int) = "st-%03d".format(i)
    }
}
