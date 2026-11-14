package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.picasso.client.PicassoClient
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.mimic.cli.MimicCli
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import dev.picasso.ops.host.timings.SiteTimingsView
import dev.picasso.registry.PostgresSupport
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 호스트 시험 세트. 이 JVM 의 Netty 포트에 mimic(humanoid-01·quadruped-01, 가상 시계, registry 없음)을 띄우고, 고정 본문을
 * 내는 셀 대역 대역(stub)과 실행 호스트를 띄운다. 호스트 시계는 mimic 의 가상 시계다(통합 시험이 현장 시계를 넣는 것과 같은 배선).
 *
 * 셀 대역은 현장 모듈에 의존하지 않고 같은 본문 모양을 직접 낸다. 현장 쪽 모양은 현장 시험이 본다.
 *
 * 임무 버전 저장은 registry 시험 픽스처의 Postgres 다(S3b 스펙 §6.1). 띄울 때마다 `mission` 스키마를 지운다. 덧붙이기 전용
 * 트리거가 DELETE·TRUNCATE 를 막으므로 스키마째 지운다. [restartHost] 는 DB 를 그대로 두고 호스트만 다시 띄운다.
 *
 * 현장 시간값 뷰(S3c 스펙 §7.1)는 운영 서비스의 마이그레이션이 만들지만 이 세트에는 운영 서비스가 없다. 그래서 띄울 때마다 ops
 * 스키마를 지우고, 호스트 main 의 뷰 상수([SiteTimingsView])로 `VALUES` 한 행의 대역 뷰를 만든다([timingsView]). 실제 뷰와 칸이
 * 같은지는 통합 시험이 대조한다.
 *
 * @param mockVirtualLimit 주면 모의 실행의 가상 시간 상한을 이것으로 덮는다.
 * @param readInterval 주면 현장 시간값 읽기 주기를 이것으로 덮는다. 길게 주면 기동 안의 첫 읽기만 일어난다.
 * @param timings 대역 뷰의 한 행(버전, 앞 폭, 뒤 폭, inDoubtGrace, stallWindow). `null` 이면 뷰를 만들지 않아 호스트가 미적용으로 뜬다.
 * @param gated 참이면 호스트의 하위 포트를 [blockedSnapshots] 에 든 기체의 스냅숏을 못 읽는 포트로 넣는다(S4b 의 복원 미룸).
 *   기동마다 새 채널과 새 `ClientRobotPort` 다(재기동한 호스트가 핸들을 처음부터 다시 받는 것과 같게).
 * @param permitMapping 주면 현장이 이 작업 허가 대응을 선언한 것이다(`host.work-permit.mapping`, ADR 52). 셀 대역 대역의 안전 신호가 원천이다.
 * @param heartbeat 주면 인스턴스 심장박동의 주기와 만료를 이것으로 덮는다(`host.instance.heartbeat-period`·`-expiry`).
 * @param registryUrl 주면 호스트가 이 주소의 registry 진단 읽기로 기체 퇴역을 읽는다(`host.registry.url`). 안 주면 비워서 포트를 붙이지
 *   않는다. 기본 설정의 `REGISTRY_PORT` 는 이 기계에 뜬 실제 registry 일 수 있다.
 * @param retirementInterval 주면 퇴역 읽기 주기를 이것으로 덮는다(`host.retirement.read-interval`).
 * @param incidentLoop 인시던트 고리 설정(`host.incident-loop.<키>`). 키는 `entitlements`·`approval-port`·`export` 다.
 */
class HostBench(
    private val mockVirtualLimit: Duration? = null,
    private val readInterval: Duration? = null,
    timings: List<Long>? = STANDARD_TIMINGS,
    private val gated: Boolean = false,
    private val permitMapping: Path? = null,
    private val heartbeat: Pair<Duration, Duration>? = null,
    private val registryUrl: String? = null,
    private val retirementInterval: Duration? = null,
    private val incidentLoop: Map<String, String> = emptyMap(),
) : AutoCloseable {

    /** [gated] 일 때 스냅숏을 못 읽는 기체. 바꾸면 다음 호출부터 미친다. */
    val blockedSnapshots: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val channels = CopyOnWriteArrayList<ManagedChannel>()

    /** mimic 에 붙는 새 포트. 시험이 미들웨어 밖에서 태스크를 직접 낼 때(고아 태스크) 쓴다. */
    fun clientPort(clientId: String = MissionHostApplication.CLIENT_ID): ClientRobotPort {
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", mimic.server.port).usePlaintext().build()
        channels += channel
        return ClientRobotPort(PicassoClient(channel, clientId))
    }

    /** [gated] 일 때 호스트가 하위에 낸 `StartTask`(기체, 태스크 id). 같은 태스크 id 의 재전송도 하나씩 든다. */
    val starts = CopyOnWriteArrayList<Pair<String, String>>()

    /**
     * [gated] 일 때 이 스레드가 부른 스냅숏 읽기만 바꾼다(기체, 읽은 값 → 돌려줄 값). 시험 스레드가 호스트 메서드를 직접 부를 때 그 읽기만 바꾸고 pump 와
     * 미리 읽기는 그대로 둔다.
     */
    @Volatile
    var snapshotOverride: Pair<Thread, (RobotSnapshot?) -> RobotSnapshot?>? = null

    /**
     * [gated] 일 때 스냅숏에서 손(`hold`)을 지우는 기체. 손을 싣지 않는 옛 발신자 포트를 흉내 낸다 — 관문은 손을 몰라 받고, 기체에서야 파지
     * 주어의 사전 조건 거부가 난다(picasso §15.320 의 장면). 바꾸면 다음 호출부터 미친다.
     */
    val hiddenHands: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [gated] 일 때 호스트가 하위에 낸 취소(기체, 태스크 id). */
    val cancels = CopyOnWriteArrayList<Pair<String, String>>()

    private fun gatedPort(): RobotPort {
        val delegate = clientPort()
        return object : RobotPort by delegate {
            override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
                delegate.start(robotId, taskId, revision, skillType, parameters).also { starts += robotId to taskId }

            override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) =
                delegate.cancel(robotId, handle).also { cancels += robotId to handle.taskId }

            override fun snapshot(robotId: String): RobotSnapshot? {
                val read = if (robotId in blockedSnapshots) null else delegate.snapshot(robotId)?.let { if (robotId in hiddenHands) it.copy(hold = null) else it }
                val override = snapshotOverride
                return if (override != null && override.first == Thread.currentThread()) override.second(read) else read
            }
        }
    }

    init {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        PostgresSupport.execute("DROP SCHEMA IF EXISTS ops CASCADE")
        timings?.let(::timingsView)
    }

    /** 대역 뷰를 [row] 한 행으로 다시 만든다. 호스트는 다음 읽기(1초 주기)에서 그 행을 읽는다. */
    fun timingsView(row: List<Long>) {
        require(row.size == SiteTimingsView.COLUMNS.size) { "칸이 ${SiteTimingsView.COLUMNS.size} 개여야 한다: $row" }
        PostgresSupport.execute("CREATE SCHEMA IF NOT EXISTS ${SiteTimingsView.NAME.substringBefore('.')}")
        dropTimingsView()
        val values = SiteTimingsView.COLUMNS.zip(row).joinToString(", ") { (column, value) -> "$value::${column.type}" }
        PostgresSupport.execute(
            "CREATE VIEW ${SiteTimingsView.NAME} (${SiteTimingsView.COLUMNS.joinToString(", ") { it.name }}) AS VALUES ($values)",
        )
    }

    /** 대역 뷰를 지운다. 호스트의 다음 읽기가 실패한다. */
    fun dropTimingsView() = PostgresSupport.execute("DROP VIEW IF EXISTS ${SiteTimingsView.NAME}")

    /** `GET /host/site-timings` 가 [done] 을 만족할 때까지 기다린다(실제 시간 상한 5초). 만족한 본문을 돌려준다. */
    fun awaitTimings(done: (JsonNode) -> Boolean): JsonNode {
        val deadline = Instant.now().plusSeconds(5)
        while (true) {
            val view = get("/host/site-timings")
            if (done(view)) return view
            check(Instant.now().isBefore(deadline)) { "5초 안에 현장 시간값 상태가 바뀌지 않았다: $view" }
            Thread.sleep(100)
        }
    }

    /** 호스트가 버전 [version] 을 적용할 때까지 기다린다. */
    fun awaitApplied(version: Long): JsonNode = awaitTimings { it["applied"]?.get("version")?.asLong() == version }

    val mimic: MimicCli.Started = checkNotNull(
        MimicCli().start(
            robots = mapOf(
                HUMANOID to ROOT.resolve("picasso/profile/profiles/humanoid-a.json"),
                QUADRUPED to ROOT.resolve("picasso/profile/profiles/quadruped-b.json"),
            ),
            schema = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json"),
            port = 0,
            virtual = true,
            seed = 0L,
            err = System.err,
        ),
    ) { "mimic 기동 거부" }

    /** 셀 대역 대역이 낼 본문. 바꾸면 다음 pump 부터 호스트가 읽는다. */
    @Volatile
    var cellBody: String = STANDARD_CELL

    /** 셀 대역 대역이 신호 쓰기에 답할 상태 코드와 본문. */
    @Volatile
    var signalReply: Pair<Int, String> = 200 to """{"name":"rack_present","value":"true"}"""

    /**
     * 다음 셀 읽기에서 한 번 돌 갈고리. 호스트 pump 는 잠금 밖에서 셀 대역을 먼저 읽고 그 뒤 미들웨어를 돌리므로, 기동 복원 뒤 첫 pump
     * 의 미들웨어 pump 앞에 현장을 바꿀 때 쓴다([restartHost] 의 `beforeStart` 에서 건다).
     */
    val onCellFetch = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    /** 셀 대역 대역이 받은 신호 쓰기(경로의 이름, Content-Type, 본문). */
    val signalWrites = CopyOnWriteArrayList<Triple<String, String?, String>>()

    /** 셀 대역 대역이 장애 주입에 답할 상태 코드와 본문. */
    @Volatile
    var faultReply: Pair<Int, String> = 200 to """{"robotId":"humanoid-01","kind":"CONNECTION","state":"OFFLINE","changed":true}"""

    /** 셀 대역 대역이 받은 장애 주입(Content-Type, 본문). */
    val faultWrites = CopyOnWriteArrayList<Pair<String?, String>>()

    private val cell: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/faults") { exchange ->
            faultWrites += exchange.requestHeaders.getFirst("Content-Type") to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val (status, body) = faultReply
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        createContext("/cell") { exchange ->
            val path = exchange.requestURI.path
            val (status, body) = if (path.startsWith("/cell/signals/") && exchange.requestMethod == "POST") {
                signalWrites += Triple(
                    path.removePrefix("/cell/signals/"),
                    exchange.requestHeaders.getFirst("Content-Type"),
                    exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                )
                signalReply
            } else {
                // 셀 읽기는 pump 의 첫 걸음이다. 걸어 둔 갈고리는 그 pump 가 미들웨어를 돌리기 전에 한 번 돈다.
                onCellFetch.getAndSet(null)?.invoke()
                200 to cellBody
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }

    private var context: ConfigurableApplicationContext = startHost()

    val url: String get() = "http://127.0.0.1:${(context as WebServerApplicationContext).webServer.port}"

    /** 지금 뜬 호스트의 빈. */
    val host: MissionHost get() = context.getBean(MissionHost::class.java)

    /** 지금 뜬 호스트 컨텍스트의 빈 하나. 시험이 기록 저장소를 직접 부를 때 쓴다. */
    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /** 이 세트와 같은 인자로 호스트를 하나 띄운다. 기동이 실패하면 예외가 그대로 나간다. */
    fun startHost(): ConfigurableApplicationContext = MissionHostApplication.builder(HostClock { now() }, if (gated) gatedPort() else null).run(
        *buildList {
            add("--server.port=0")
            add("--host.mimic.port=${mimic.server.port}")
            add("--host.cell.url=http://127.0.0.1:${cell.address.port}")
            add("--spring.datasource.url=${PostgresSupport.jdbcUrl}")
            add("--spring.datasource.username=${PostgresSupport.username}")
            add("--spring.datasource.password=${PostgresSupport.password}")
            add("--host.mock-run.profile=$MOCK_PROFILE")
            add("--host.mock-run.schema=$SCHEMA")
            mockVirtualLimit?.let { add("--host.mock-run.virtual-limit=$it") }
            readInterval?.let { add("--host.site-timings.read-interval=$it") }
            permitMapping?.let { add("--host.work-permit.mapping=$it") }
            add("--host.registry.url=${registryUrl.orEmpty()}")
            retirementInterval?.let { add("--host.retirement.read-interval=$it") }
            incidentLoop.forEach { (key, value) -> add("--host.incident-loop.$key=$value") }
            heartbeat?.let { (period, expiry) ->
                add("--host.instance.heartbeat-period=$period")
                add("--host.instance.heartbeat-expiry=$expiry")
            }
        }.toTypedArray(),
    )

    /** 호스트만 닫고 같은 DB 로 다시 띄운다(재기동). mimic 과 셀 대역은 그대로다. [beforeStart] 는 닫은 뒤 띄우기 전에 돈다. */
    fun restartHost(beforeStart: () -> Unit = {}) {
        context.close()
        beforeStart()
        context = startHost()
    }

    fun now(): Instant = mimic.server.exclusive { mimic.instance(HUMANOID)!!.clock.now() }

    fun stopCell() = cell.stop(0)

    data class Reply(val status: Int, val body: JsonNode?)

    fun get(path: String): JsonNode {
        val reply = fetch(path)
        check(reply.status == 200) { "$path: ${reply.status} ${reply.body}" }
        return reply.body!!
    }

    fun fetch(path: String): Reply {
        val response = HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let { runCatching { JSON.readTree(it) }.getOrNull() })
    }

    fun post(path: String, body: String, contentType: String = "application/json"): Reply {
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create(url + path))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Reply(response.statusCode(), response.body().takeIf { it.isNotBlank() }?.let { runCatching { JSON.readTree(it) }.getOrNull() })
    }

    /** 빈손 확인 한 번(B2). [cited] 는 지목할 저장된 충돌이다([cite] 가 409 본문에서 만든다). */
    fun clearHold(
        robotId: String,
        markerId: Long,
        approverId: String = "park",
        reason: String = "빈손 확인",
        requestId: String = java.util.UUID.randomUUID().toString(),
        cited: Map<String, Any?>? = null,
    ): Reply = post(
        "/host/robots/$robotId/residual-holds/$markerId/clear",
        JSON.writeValueAsString(
            linkedMapOf<String, Any?>("approverId" to approverId, "reason" to reason, "requestId" to requestId).apply { if (cited != null) put("cited", cited) },
        ),
    )

    /**
     * 손이 아직 든 채인 기체의 빈손 확인(B2). 발신자가 지금 HOLDING 이라 첫 요청은 409 `RESIDUAL_HOLD_OBSERVATION_MISMATCH` 이고 충돌 행이 남는다.
     * 사람이 현장에서 빈손을 확인했다고 보고 그 충돌을 지목해 새 요청 id 로 다시 확인한 답을 돌려준다. mimic 은 사람이 손을 비울 수단이 없어 든 채
     * 정착한 기체는 늘 이 길이다.
     */
    fun clearHeldCiting(robotId: String, markerId: Long, approverId: String = "park", reason: String = "빈손 확인"): Reply {
        val refused = clearHold(robotId, markerId, approverId, reason)
        check(refused.status == 409 && refused.body?.get("error")?.asText() == "RESIDUAL_HOLD_OBSERVATION_MISMATCH") {
            "든 채인 기체의 첫 빈손 확인이 어긋남이 아니다: ${refused.status} ${refused.body}"
        }
        return clearHold(robotId, markerId, approverId, reason, cited = cite(refused.body!!))
    }

    /** 이 실행을 찾는다. 없으면 `null`. */
    fun execution(executionId: String): JsonNode? =
        get("/host/executions")["executions"].firstOrNull { it["executionId"].asText() == executionId }

    /** 지금 가상 시각 이후에 시작한 pump 가 끝날 때까지 기다린다(실제 시간 상한 5초). */
    fun awaitPump() {
        val target = now()
        val deadline = Instant.now().plusSeconds(5)
        while (Instant.now().isBefore(deadline)) {
            val at = get("/host/executions")["pumpedAt"]
            if (!at.isNull && !Instant.parse(at.asText()).isBefore(target)) return
            Thread.sleep(50)
        }
        error("pump 가 $target 에 이르지 않았다")
    }

    /** [condition] 이 참이 될 때까지 기다린다(실제 시간 상한 5초). 가상 시계를 밀지 않는 pump 뒤 기록을 기다릴 때 쓴다. */
    fun eventually(what: String, condition: () -> Boolean) {
        val deadline = Instant.now().plusSeconds(5)
        while (!condition()) {
            check(Instant.now().isBefore(deadline)) { "5초 안에 이르지 않았다: $what" }
            Thread.sleep(50)
        }
    }

    /** pump [count] 번이 돌 만큼 실제 시간을 보낸다. 무엇이 «더 생기지 않음» 을 볼 때 쓴다. */
    fun idlePumps(count: Int = 3) = Thread.sleep(MissionHost.PUMP_PERIOD.toMillis() * count + 100)

    /**
     * 실행이 [states] 중 하나가 될 때까지 가상 시계를 5초씩 민다. 민 뒤에는 스트림 갱신이 클라이언트에 닿을 틈을 주려고
     * pump 두 번을 기다린다.
     */
    fun driveUntil(executionId: String, states: Set<String>, rounds: Int = 40): JsonNode {
        repeat(rounds) {
            mimic.server.advance(Duration.ofSeconds(5))
            awaitPump()
            Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
            val execution = checkNotNull(execution(executionId)) { "실행이 없다: $executionId" }
            if (execution["physicalState"].asText() in states) return execution
        }
        error("실행 $executionId 가 $states 에 이르지 않았다: ${execution(executionId)}")
    }

    override fun close() {
        try {
            context.close()
        } finally {
            cell.stop(0)
            channels.forEach { it.shutdownNow() }
            mimic.server.shutdown()
        }
    }

    companion object {
        const val HUMANOID = "humanoid-01"
        const val QUADRUPED = "quadruped-01"
        const val GHOST = "ghost-01"
        const val SOURCE = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"

        /** 대역 뷰의 처음 행. 버전 1 과 picasso 케이퍼빌리티 기본값이며, 운영 서비스 마이그레이션이 만드는 버전 1 과 같다. */
        val STANDARD_TIMINGS: List<Long> = listOf(1, 30, 15, 60, 300)

        /** 빈손 확인 409 본문에서 지목(`cited`)을 만든다. 화면이 그 응답에서만 만드는 것과 같다. */
        fun cite(mismatch: JsonNode): Map<String, Any?> {
            val seen = mismatch["observation"]
            fun text(field: String) = seen[field]?.takeUnless { it.isNull }?.asText()
            return linkedMapOf(
                "conflictRequestId" to mismatch["conflictRequestId"].asText(),
                "kind" to text("observedKind"),
                "objectRef" to text("observedObjectRef"),
                "session" to text("snapshotSession"),
                "sequence" to seen["snapshotSequence"]?.takeUnless { it.isNull }?.asText(),
            )
        }

        val ROOT: Path = Path.of("..").toAbsolutePath().normalize()
        val HTTP: HttpClient = HttpClient.newHttpClient()
        val JSON = ObjectMapper()

        /** 모의 실행용 프로파일과 프로파일 스키마. 호스트 시험은 절대 경로를 실행 인자로 넘긴다(S3b 스펙 §6.4). */
        val MOCK_PROFILE: Path = ROOT.resolve("mission-host/mock-run/humanoid-a.json")
        val SCHEMA: Path = ROOT.resolve("picasso/profile/schema/capability-profile.schema.json")

        /** 현장 셀 대역 `CellFixture.STANDARD` 의 처음 모양. 신호 셋은 현장 표준 픽스처의 사양과 처음 값이다. */
        val STANDARD_CELL = """
            {"presentations":[{"id":"$SOURCE","occupied":true,"material":"$MATERIAL","observedAt":null}],
             "slots":[{"id":"RACK-204.S01","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S02","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S03","occupied":false,"material":null,"observedAt":null},
                      {"id":"RACK-204.S04","occupied":false,"material":null,"observedAt":null}],
             "signals":[{"name":"rack_present","location":"RACK-204","kind":"BOOLEAN","safety":false,"value":"false","observedAt":null},
                        {"name":"guard_closed","location":null,"kind":"BOOLEAN","safety":true,"value":"true","observedAt":null},
                        {"name":"lot_code","location":null,"kind":"TEXT","safety":false,"value":"LOT-0001","observedAt":null}]}
        """.trimIndent()

        fun inspect(jobOrderId: String, vararg targets: Pair<String, String>, evidence: String = "E0"): String =
            """
            {"jobOrderId":"$jobOrderId","workMasterId":"InspectAsset","version":1,"requiredEvidence":"$evidence",
             "parameters":{},"materialRequirements":[],
             "equipmentRequirements":[${targets.joinToString(",") { (id, location) ->
                """{"id":"$id","equipmentUse":"inspection_target","properties":{"location":"$location"}}"""
            }}]}
            """.trimIndent()

        fun rack(jobOrderId: String, vararg slots: String): String =
            """
            {"jobOrderId":"$jobOrderId","workMasterId":"PrepareSequencedRack","version":1,"requiredEvidence":"E2",
             "parameters":{},"materialRequirements":[{"materialDefinitionId":"$MATERIAL","quantity":${slots.size}}],
             "equipmentRequirements":[${(slots.map { """{"id":"$it","equipmentUse":"destination","properties":{"material":"$MATERIAL"}}""" } +
                """{"id":"$SOURCE","equipmentUse":"source","properties":{"material":"$MATERIAL"}}""").joinToString(",")}]}
            """.trimIndent()

        fun request(order: String, field: String, vararg robots: String): String =
            """{"jobOrder":$order,"$field":[${robots.joinToString(",") { "\"$it\"" }}]}"""
    }
}
