package dev.picasso.ops.load

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostRobotPort
import dev.picasso.ops.host.MissionHostApplication
import dev.picasso.ops.host.Receiver
import dev.picasso.ops.host.RobotWiring
import dev.picasso.ops.host.RoutedRobotPort
import dev.picasso.ops.host.ScriptedAdapter
import dev.picasso.ops.host.lease.GatedRobotPort
import dev.picasso.ops.host.lease.HostLeadership
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * 실제 수신자(picasso `adapter-host`, `FENCED` + 파일 바닥값) 변형 — 정리 대기(`fenceWait`)가 실행 호스트의 pump·잠금 쓰는 조회를 얼마나 늦추는가
 * (mo26 설계안 D2·D3). **수신자는 하네스 안 `adapter-host` 이고 벤더는 시험 어댑터(mission-host 시험 고정물의 [ScriptedAdapter])다** — 실 벤더 링크의 지연은
 * 없다. 결정적 단언은 mission-host `HostReceiverTest` 에 있고 여기는 시간만 잰다.
 *
 * 한 세트: 기체 N 대(humanoid-a 프로파일)에 InspectAsset(E0, 대상 bay-7·dock-3)을 하나씩 내고 첫 점검(`T1`)이 RUNNING 일 때, 기체마다 다음 출발
 * (`T2.travel`)의 벤더 접수를 벤더 호출 **안에서** 붙잡는다(앞 세대로 통과한 명령이 안 닫힌 채). 그 채로 첫째 호스트를 멈춤·리스 반환 없이 죽이고 곧바로
 * 둘째를 띄운다. 구간 셋:
 *
 * - **복원**: 둘째의 리스 만료 → 복원 완료(`RESTORED` 이벤트, DB 시각)와 그 사이 수신자가 받은 `GetSnapshot` 수.
 * - **관측 중 정상 pump**(`HOLDING`): 앞 세대 호출이 걸린 채 [WINDOW] 동안 — 기체는 `DURABLE_PENDING` 으로 열리지 않는다(«미열림»).
 * - **발행 중 + 앞 세대 호출 걸림**: 운영자 확인(`ISSUING`) 뒤 [WINDOW] 동안. 관측 중의 동기 읽기 간격 묶음이 발행 중에는 없다.
 *
 * 지표: pump 한 바퀴(`MissionHost.pumpOnce` 진입에서 나옴까지, 2 ms 마다 스택 표본 — [PumpRounds])와 같은 스레드의 그 밖 연속 작업(복원 재대조 등), 잠금 쓰는 조회 `GET /host/executions` 의 지연(50 ms 마다 하나씩, 기한 [QUERY_TIMEOUT] — 넘긴 건은 기한 값으로 표본에 넣고 따로 센다).
 * 수신자는 기체마다 하나(독립) 또는 한 `AdapterHost` 에 기체 전부(공유 링크 — 플릿 뒤 기체들처럼 링크 잠금 하나)다. 대조군 한 줄은 걸린 호출이 없다.
 */
object TakeoverReceiverLoad {

    data class Config(val fenceWait: Duration, val robots: Int, val shared: Boolean, val hold: Boolean = true) {
        val label: String get() = "${fenceWait.toMillis()} ms · ${robots} 대 · ${if (shared) "공유 링크" else "독립"}${if (hold) "" else " · 걸린 호출 없음(대조군)"}"
    }

    private val LEASE = TakeoverLoad.Lease(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))
    private val WINDOW: Duration = Duration.ofSeconds(10)
    private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val RESTORE_LIMIT: Duration = Duration.ofSeconds(120)
    private val SHARED_SETTLE: Duration = Duration.ofSeconds(15)
    private val PROFILE: Path = LoadStack.ROOT.resolve("picasso/profile/profiles/humanoid-a.json")
    private val HTTP: HttpClient = HttpClient.newHttpClient()

    fun run(report: Report, configs: List<Config>) {
        report.line("## 실제 수신자(adapter-host)에서 정리 대기가 pump·잠금 쓰는 조회를 늦추는 몫")
        report.line()
        report.line("수신자: 하네스 안 picasso `adapter-host`(FENCED + 파일 바닥값), 벤더: 시험 어댑터(실 벤더 링크 지연 없음). 리스 ${LEASE.label}. 작업 지시: InspectAsset E0, 대상 bay-7·dock-3, 기체마다 하나. T1 RUNNING 뒤 기체마다 T2.travel 접수를 벤더 호출 안에서 붙잡은 채 첫째를 죽이고 둘째를 띄운다. 구간마다 ${WINDOW.seconds} 초, 조회 기한 ${QUERY_TIMEOUT.seconds} 초.")
        report.line()
        val rows = mutableListOf<List<String>>()
        for (config in configs) rows += once(report, config)
        report.table(
            listOf(
                "정리 대기 · 기체 · 수신자", "복원: 리스 만료 → 복원 완료 ms(DB) 또는 복원 수", "복원 구간 GetSnapshot 수", "복원: pump 한 바퀴 최대 ms", "복원: 재대조 등 그 밖 연속 작업 최대 ms",
                "복원: 조회 p50 / p95 / p99 / 최대 ms", "복원: 조회 기한 초과",
                "관측: pump 한 바퀴 최대 ms", "관측: 조회 p50 / p95 / p99 / 최대 ms", "관측: 조회 기한 초과", "관측: 열린 기체",
                "발행: pump 한 바퀴 최대 ms", "발행: 조회 p50 / p95 / p99 / 최대 ms", "발행: 조회 기한 초과",
            ),
            rows,
        )
        report.line("«pump 한 바퀴» 는 pump 스레드가 `MissionHost.pumpOnce` 안에 연속으로 머문 시간이다(2 ms 스택 표본, 진입에서 나옴까지). 그 구간 안에서 시작해 끝난 회만 넣고, 구간 끝에 아직 돌던 회는 «끝나지 않은 회» 로 따로 적고, 뒤 구간에는 최대값에 넣지 않고 «앞 구간에서 이어진 회»(전체 길이) 또는 «앞 구간에서 이어져 아직 도는 회» 로 따로 적는다. 모두 표본 기준 추정이다 — 2 ms 사이에 끝난 호출을 아직 도는 것으로 볼 수 있고 표본 사이에 시작해 끝난 짧은 회는 빠질 수 있다. 표본은 측정 대상과 같은 JVM 의 스레드가 2 ms 마다 대상 스레드의 스택을 읽는 것이라(안전점 정지가 따른다) 그만큼 대상의 일을 늦출 수 있다. «그 밖» 은 같은 스레드에서 `pumpOnce` 밖의 일(복원 재대조 등)이다. «열린 기체» 가 0 이면 그 구간 내내 «미열림»(`DURABLE_PENDING`)이다. «복원» 구간은 둘째 기동부터 관측(`HOLDING`) 진입까지다. 스냅숏 읽기가 기한을 넘긴 실행은 복원을 미루고(`DEFERRED`) 관측 중 pump 마다 다시 시도하므로, 그때는 관측 진입 때와 발행 구간 끝의 `RESTORED` 수를 적는다(리더 상태가 아니다). ${RESTORE_LIMIT.seconds} 초 안에 관측에 들지 못하면 그때의 리더 상태를 적고 뒤 구간을 재지 않는다. 조회 지연의 기한 초과 건은 기한 값(${QUERY_TIMEOUT.toMillis()} ms)으로 백분위에 든다.")
        report.line()
    }

    private fun once(report: Report, config: Config): List<String> {
        val holder = AtomicReference<LoadStack?>()
        fun now(): Instant = holder.get()?.now() ?: Instant.EPOCH
        val ids = (1..config.robots).map { "humanoid-%03d".format(it) }
        val adapters = ids.associateWith { ScriptedAdapter(::now) }
        val receivers = if (config.shared) {
            listOf(Receiver(ids.associateWith { PROFILE to adapters.getValue(it) }, ::now, config.fenceWait))
        } else {
            ids.map { Receiver(mapOf(it to (PROFILE to adapters.getValue(it))), ::now, config.fenceWait) }
        }
        val receiverOf = ids.withIndex().associate { (i, id) -> id to receivers[if (config.shared) 0 else i] }
        val wiring = RobotWiring { leadership, clock ->
            val routes = ids.associateWith { MissionHostApplication.clientPort(receiverOf.getValue(it).channel(), leadership::controllerEpoch, leadership::superseded) }
            HostRobotPort(GatedRobotPort(RoutedRobotPort(routes), leadership), clock)
        }
        val gates = ids.associateWith { CountDownLatch(1) }
        try {
            return LoadStack(
                robots = config.robots, withOps = false, wiring = wiring,
                hostArgs = listOf(
                    "--host.lease.period=${LEASE.period}", "--host.lease.expiry=${LEASE.expiry}", "--host.lease.margin=${LEASE.margin}",
                    "--host.lease.idle-in-transaction-timeout=${LEASE.idle}", "--host.lease.statement-timeout=${LEASE.expiry.minus(LEASE.margin)}",
                ),
            ).use { stack ->
                holder.set(stack)
                report.floor(stack, "세트(${config.label})")
                measure(stack, config, ids, adapters, receivers, gates)
            }
        } finally {
            gates.values.forEach { it.countDown() }
            receivers.forEach { runCatching { it.close() } }
        }
    }

    private fun measure(
        stack: LoadStack, config: Config, ids: List<String>, adapters: Map<String, ScriptedAdapter>, receivers: List<Receiver>, gates: Map<String, CountDownLatch>,
    ): List<String> {
        adapters.values.forEach { it.durations["inspect"] = Duration.ofHours(1) }
        ids.forEachIndexed { i, robot ->
            val reply = stack.hostPost(
                "/host/job-orders",
                """{"jobOrder":{"jobOrderId":"JO-${i + 1}","workMasterId":"InspectAsset","version":1,"requiredEvidence":"E0","parameters":{},
                   "materialRequirements":[],"equipmentRequirements":[{"id":"T1","equipmentUse":"inspection_target","properties":{"location":"bay-7"}},
                   {"id":"T2","equipmentUse":"inspection_target","properties":{"location":"dock-3"}}]},
                   "candidates":["$robot"]}""",
            )
            check(reply["result"].asText() == "ACCEPTED") { "제출 거부: $reply" }
        }
        advanceUntil(stack, "로봇 단위가 모두 T1 RUNNING") { allAt(stack.hostGet("/host/executions").first, ids.size, "T1", "RUNNING") }
        Thread.sleep(1_000)

        // 기체마다 T2.travel 접수를 벤더 호출 안에서 붙잡는다. 공유 링크면 첫 접수가 링크를 쥐어 나머지는 링크 잠금 앞(수신자 입구는 지남)에서 기다린다.
        val travels = ids.indices.map { "JO-${it + 1}#T2.travel" }
        adapters.forEach { (robot, adapter) ->
            if (config.hold) adapter.holdNext.set(gates.getValue(robot))
            adapter.durations["inspect"] = Duration.ofSeconds(4)
        }
        if (config.hold && !config.shared) {
            advanceUntil(stack, "앞 세대 접수가 모두 벤더 호출 안에 걸림") { adapters.values.sumOf { it.held.get() } == ids.size }
        } else if (config.hold) {
            // 공유 링크: 첫 접수가 링크를 쥐면 다른 기체의 읽기(출발 앞 지금 읽기 포함)가 링크 잠금을 기다려, 그 기체의 출발이 수신자에 닿지 않을 수 있다(그 자체가 D3).
            // 첫 접수가 걸린 뒤 [SHARED_SETTLE] 동안 더 닿는 것을 기다리고, 닿은 수를 그 세트의 값으로 적는다.
            advanceUntil(stack, "첫 접수가 벤더 호출 안에 걸림") { adapters.values.sumOf { it.held.get() } == 1 }
            val until = System.nanoTime() + SHARED_SETTLE.toNanos()
            while (System.nanoTime() < until && !travels.all { t -> receivers.any { it.startRpcsOf(t) >= 1 } }) {
                stack.mimic.server.advance(Duration.ofSeconds(1))
                Thread.sleep(600)
            }
        } else {
            advanceUntil(stack, "T2.travel 이 모두 받아들여짐") { travels.all { t -> adapters.values.any { it.acceptsOf(t) == 1 } } }
        }
        Thread.sleep(500)
        val reached = travels.count { t -> receivers.any { it.startRpcsOf(t) >= 1 } }

        val first = stack.host.getBean(HostLeadership::class.java).instanceId
        stack.killHost()
        val expires = stack.query("SELECT t.expires_at FROM mission.host_lease_term t") { it.getTimestamp(1).toInstant() }.single()
        PumpRounds().use { pump ->
            val readsBefore = receivers.sumOf { it.snapshotRpcs.get() }
            stack.replaceHost()
            // 복원 구간(둘째 기동 → 관측 진입)에도 잠금 쓰는 조회를 잰다. 관측 진입이 곧 복원 완료는 아니다 — 스냅숏 읽기가 기한을 넘긴 실행은 복원을 미루고(`DEFERRED`)
            // 관측에 든 뒤 pump 마다 다시 시도한다. 그래서 관측 진입 때와 발행 구간 끝의 `RESTORED` 수를 따로 적는다. [RESTORE_LIMIT] 안에 관측에 들지 못하면 그때의 리더 상태를
            // 적고 뒤 구간은 재지 않는다.
            fun restoredCount() = stack.query("SELECT count(*) FROM mission.execution_journal_event WHERE kind = 'RESTORED'") { it.getInt(1) }.single()
            fun leaderState() = stack.hostGet("/host/leader").first["state"].asText()
            val restoring = sample(stack, RESTORE_LIMIT) { leaderState() == "HOLDING" }
            val restoreReads = receivers.sumOf { it.snapshotRpcs.get() } - readsBefore
            val restorePump = pump.take()
            val restoredAtHolding = restoredCount()
            if (!restoring.third) {
                return listOf(
                    label(config, reached), "관측 미진입(> ${RESTORE_LIMIT.seconds} 초, 리더 ${leaderState()}, 복원 $restoredAtHolding/${ids.size})", "$restoreReads",
                    restorePump.pumpCell(), restorePump.otherCell(), queries(restoring), "${restoring.second}",
                    "-", "-", "-", "-", "-", "-", "-",
                )
            }
            val restoredAt = if (restoredAtHolding >= ids.size) {
                stack.query("SELECT max(recorded_at) FROM mission.execution_journal_event WHERE kind = 'RESTORED'") { it.getTimestamp(1).toInstant() }.single()
            } else {
                null
            }

            val holding = sample(stack)
            val holdingPump = pump.take()
            val opened = stack.query("SELECT count(*) FROM mission.host_readiness_evidence") { it.getInt(1) }.single()

            val leadership = stack.host.getBean(HostLeadership::class.java)
            awaitHeartbeatExpired(stack, first)
            val confirmed = confirm(stack, leadership.instanceId, checkNotNull(leadership.lease.generation), first)
            check(confirmed.first == 200) { "운영자 확인 거부: ${confirmed.first} ${confirmed.second}" }
            poll("발행(ISSUING)") { stack.hostGet("/host/leader").first["state"].asText() == "ISSUING" }
            pump.take()
            val issuing = sample(stack)
            val issuingPump = pump.take()

            val restoredAtEnd = restoredCount()
            val restoreCell = if (restoredAt != null) {
                f(Duration.between(expires, restoredAt).toNanos() / 1e6)
            } else {
                "미완료(리더 HOLDING 진입, 복원 관측 진입 때 $restoredAtHolding/${ids.size} · 발행 구간 끝 $restoredAtEnd/${ids.size}, 나머지 DEFERRED)"
            }
            return listOf(
                label(config, reached), restoreCell, "$restoreReads", restorePump.pumpCell(), restorePump.otherCell(), queries(restoring), "${restoring.second}",
                holdingPump.pumpCell(), queries(holding), "${holding.second}",
                if (config.hold && opened == 0) "0(미열림)" else "$opened",
                issuingPump.pumpCell(), queries(issuing), "${issuing.second}",
            )
        }
    }

    /** 공유 링크 세트는 수신자에 닿은 앞 세대 출발 수를 함께 적는다. */
    private fun label(config: Config, reached: Int): String =
        if (config.shared && config.hold) "${config.label} · 앞 세대 출발 수신 $reached/${config.robots}" else config.label

    private fun queries(sampled: Triple<Timings, Int, Boolean>): String =
        sampled.first.let { "${f(it.p50)} / ${f(it.p95)} / ${f(it.pct(99.0))} / ${f(it.max)}" }

    /**
     * [window] 동안(또는 [done] 이 참이 될 때까지) 50 ms 마다 `GET /host/executions` 를 하나씩 보내 지연을 잰다. 기한을 넘긴 건은 기한 값으로 넣고 센다.
     * 셋째 값은 [done] 이 참이 되었는가(주지 않으면 참).
     */
    private fun sample(stack: LoadStack, window: Duration = WINDOW, done: (() -> Boolean)? = null): Triple<Timings, Int, Boolean> {
        val timings = Timings()
        var timeouts = 0
        val until = System.nanoTime() + window.toNanos()
        while (System.nanoTime() < until) {
            if (done?.invoke() == true) return Triple(timings, timeouts, true)
            val started = System.nanoTime()
            try {
                HTTP.send(HttpRequest.newBuilder(URI.create(stack.hostUrl + "/host/executions")).timeout(QUERY_TIMEOUT).build(), HttpResponse.BodyHandlers.discarding())
                timings.add((System.nanoTime() - started) / 1e6)
            } catch (_: HttpTimeoutException) {
                timeouts++
                timings.add(QUERY_TIMEOUT.toMillis().toDouble())
            }
            Thread.sleep(50)
        }
        return Triple(timings, timeouts, done == null || done())
    }

    /** [done] 이 참이 될 때까지 가상 시계를 1초씩 민다(60회 상한). */
    private fun advanceUntil(stack: LoadStack, what: String, done: () -> Boolean) {
        repeat(60) {
            if (done()) return
            stack.mimic.server.advance(Duration.ofSeconds(1))
            Thread.sleep(600)
        }
        check(done()) { "60초 밀어도 이르지 않았다: $what" }
    }

    private fun allAt(view: JsonNode, n: Int, unit: String, state: String): Boolean {
        val executions = view["executions"]
        return executions.size() == n && executions.all { e -> e["units"].any { it["unitId"].asText() == unit && it["state"].asText() == state } }
    }

    private fun poll(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + LEASE.expiry.plusSeconds(60).toNanos()
        while (!done()) {
            check(System.nanoTime() < deadline) { "만료 폭 + 60초 안에 이르지 않았다: $what" }
            Thread.sleep(50)
        }
    }

    private fun awaitHeartbeatExpired(stack: LoadStack, instanceId: String) = poll("앞 보유자 심장박동 만료") {
        !stack.query("SELECT coalesce(bool_or(expires_at > clock_timestamp()), false) FROM mission.host_instance WHERE instance_id = '$instanceId'") { it.getBoolean(1) }.single()
    }

    private fun confirm(stack: LoadStack, instanceId: String, generation: Long, previous: String): Pair<Int, String> {
        val body = """{"confirmedBy":"load","reason":"옛 호스트 프로세스 종료 확인(측정)","instanceId":"$instanceId","generation":$generation,""" +
            """"previousHolder":"$previous","unresolvedHolders":["$previous"]}"""
        val response = HTTP.send(
            HttpRequest.newBuilder(URI.create(stack.hostUrl + "/host/leader/takeover-confirmation")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.statusCode() to response.body()
    }

    /**
     * pump 스레드(`mission-host-pump`)의 작업 표본. 2 ms 마다 그 스레드의 스택을 읽어 표본마다 종류를 가른다 — 스택에 `MissionHost.pumpOnce` 가 있으면
     * «pump», 스케줄러 대기(`DelayedWorkQueue`) 밖인데 `pumpOnce` 가 없으면 «그 밖»(복원 재대조 등 같은 스레드의 다른 일), 그 밖은 쉼. 같은 종류가 이어지는
     * 구간이 한 번이다 — 그래서 pump 한 바퀴는 `pumpOnce` 진입에서 나옴까지이고, 쉼 없이 이어진 재대조와 첫 pump 는 따로 센다. 죽인 호스트의 스레드는 닫히므로
     * 살아 있는 같은 이름의 스레드를 다시 찾는다(스레드가 바뀌면 그 구간도 끝).
     *
     * [take] 는 구간 하나를 닫는다: 그 구간 안에서 **시작해 끝난** 회만 그 구간 값이고, 구간 끝에 아직 도는 회는 «끝나지 않은 회» 로 따로 보이고(그때까지의 길이,
     * 하한) 뒤 구간에는 넣지 않는다. 구간 앞에서 시작한 회는 그 구간에 넣지 않는다.
     */
    private class PumpRounds : AutoCloseable {
        enum class Kind { PUMP, OTHER }

        class Run(val kind: Kind, val start: Long, val end: Long) {
            val ms: Double get() = (end - start) / 1e6
        }

        /**
         * 한 구간의 값. [unfinished] 는 구간 끝에 아직 돌던 회(종류, 그때까지 ms — 하한). [carried] 는 앞 구간 끝에 돌던 회의 이 구간에서의 모습(종류, 시작부터의
         * 전체 ms, 끝났는가) — 최대값에는 넣지 않고 따로 보인다.
         */
        class Window(val pump: Timings, val other: Timings, val unfinished: Pair<Kind, Double>?, val carried: Triple<Kind, Double, Boolean>?) {
            private fun cell(kind: Kind, timings: Timings): String = buildList {
                add(f(timings.max))
                carried?.takeIf { it.first == kind }?.let { add(if (it.third) "앞 구간에서 이어진 회 표본 기준 약 ${f(it.second)}" else "앞 구간에서 이어져 아직 도는 회 표본 기준 약 ${f(it.second)}") }
                unfinished?.takeIf { it.first == kind }?.let { add("끝나지 않은 회 표본 기준 약 ${f(it.second)}") }
            }.let { parts -> parts.first() + if (parts.size > 1) " (" + parts.drop(1).joinToString(", ") + ")" else "" }

            fun pumpCell(): String = cell(Kind.PUMP, pump)
            fun otherCell(): String = cell(Kind.OTHER, other)
        }

        private val runs = mutableListOf<Run>()
        @Volatile private var open = true
        @Volatile private var current: Pair<Kind, Long>? = null
        private var boundary = System.nanoTime()
        private val thread = Thread {
            var target: Thread? = null
            while (open) {
                val before = target
                if (target == null || !target.isAlive) target = Thread.getAllStackTraces().keys.firstOrNull { it.name == "mission-host-pump" && it.isAlive }
                val t = target
                val frames = t?.stackTrace.orEmpty()
                val kind = when {
                    frames.isEmpty() || frames.any { "DelayedWorkQueue" in it.className } -> null
                    frames.any { it.className.endsWith("MissionHost") && it.methodName == "pumpOnce" } -> Kind.PUMP
                    else -> Kind.OTHER
                }
                val now = System.nanoTime()
                synchronized(runs) {
                    val running = current
                    if (running != null && (running.first != kind || before !== t)) {
                        runs += Run(running.first, running.second, now)
                        current = null
                    }
                    if (kind != null && current == null) current = kind to now
                }
                Thread.sleep(2)
            }
        }.apply { isDaemon = true; name = "pump-rounds"; start() }

        fun take(): Window = synchronized(runs) {
            val now = System.nanoTime()
            val pump = Timings()
            val other = Timings()
            runs.filter { it.start >= boundary }.forEach { (if (it.kind == Kind.PUMP) pump else other).add(it.ms) }
            // 앞 구간 끝에 돌던 회: 이 구간에서 끝났으면 그 전체 길이, 아직 돌면 지금까지의 길이.
            val carried = runs.firstOrNull { it.start < boundary }?.let { Triple(it.kind, it.ms, true) }
                ?: current?.takeIf { it.second < boundary }?.let { Triple(it.first, (now - it.second) / 1e6, false) }
            runs.clear()
            val unfinished = current?.takeIf { it.second >= boundary }?.let { it.first to (now - it.second) / 1e6 }
            boundary = now
            Window(pump, other, unfinished, carried)
        }

        override fun close() {
            open = false
        }
    }
}
