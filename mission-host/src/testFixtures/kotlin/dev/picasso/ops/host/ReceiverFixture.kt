package dev.picasso.ops.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.FileFenceStore
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FenceStore
import dev.picasso.profile.ProfileDocument
import io.grpc.ForwardingServerCallListener
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.ServerBuilder
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 시험용 벤더 어댑터 — 실제 수신자(picasso `adapter-host`) 뒤에 서는 기체 하나. 기종을 흉내 내지 않고, 실제 수신자가 미확정을 만드는 길만 대본으로 연다.
 *
 * - 접수의 답은 [startScript] 에서 하나씩 꺼낸다(비면 [Answer.OK]). [Answer.IN_DOUBT] 는 **로봇에 닿았으나 답을 잃음**이다 — 벤더 효과([effects])는
 *   남고 어댑터의 지역 태스크는 서지 않는다(응답을 잃은 어댑터는 지역 태스크를 안 세운다, picasso 한계 «§15.330 · 미확정 접수»). [Answer.LINK_ERROR] 는
 *   보내지 못함이라 효과가 없다.
 * - [holdNext] 를 걸면 다음 접수가 벤더 호출 **안에서** 그것이 열릴 때까지 멈춘다 — 앞 세대로 통과한 명령이 안 닫힌 채인 장면이다. 들어오면 [held] 가 오른다.
 * - 태스크는 시작 시각에서 [durations] 만큼 가상 시간이 흐르면 끝난다(값을 바꾸면 곧바로 미친다).
 *
 * [accepts] 는 태스크 id 마다 벤더 접수 호출 수, [effects] 는 로봇이 실제로 받은 접수(결과 모름 포함)다. 수신자를 다시 띄워도 이 객체(벤더 쪽)는 남는다.
 */
class ScriptedAdapter(private val now: () -> Instant) : RobotAdapter {
    enum class Answer { OK, IN_DOUBT, LINK_ERROR }

    val startScript = ConcurrentLinkedQueue<Answer>()
    val cancelScript = ConcurrentLinkedQueue<Answer>()
    val holdNext = AtomicReference<CountDownLatch?>()
    val held = AtomicInteger()
    val accepts = ConcurrentHashMap<String, AtomicInteger>()
    val effects = CopyOnWriteArrayList<String>()
    val cancels = AtomicInteger()
    val durations = ConcurrentHashMap(mapOf("navigate_to" to Duration.ofSeconds(2), "inspect" to Duration.ofSeconds(4)))

    private class Running(val taskId: String, val skillType: String, val startedAt: Instant) {
        @Volatile var canceled = false
    }

    @Volatile private var current: Running? = null

    fun acceptsOf(taskId: String): Int = accepts[taskId]?.get() ?: 0
    fun effectsOf(taskId: String): Int = effects.count { it == taskId }

    private fun stateOf(r: Running, at: Instant): TaskState = when {
        r.canceled -> TaskState.TASK_STATE_CANCELLED
        !at.isBefore(r.startedAt.plus(durations[r.skillType] ?: Duration.ofSeconds(1))) -> TaskState.TASK_STATE_SUCCEEDED
        else -> TaskState.TASK_STATE_RUNNING
    }

    override val state: TaskState get() = current?.let { stateOf(it, now()) } ?: TaskState.TASK_STATE_UNSPECIFIED

    override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
        accepts.computeIfAbsent(taskId) { AtomicInteger() }.incrementAndGet()
        holdNext.getAndSet(null)?.let { gate ->
            held.incrementAndGet()
            check(gate.await(10, TimeUnit.MINUTES)) { "막힌 접수가 10분 안에 풀리지 않았다" }
        }
        return when (startScript.poll() ?: Answer.OK) {
            Answer.OK -> {
                effects += taskId
                current = Running(taskId, skillType, now())
                Acceptance.Accepted(taskId)
            }
            Answer.IN_DOUBT -> {
                effects += taskId
                Acceptance.Refused(Refusal.LINK_IN_DOUBT, "접수 응답을 잃었다(시험)")
            }
            Answer.LINK_ERROR -> Acceptance.Refused(Refusal.LINK_ERROR, "링크 오류(시험)")
        }
    }

    override fun poll(now: Instant): TaskState = current?.let { stateOf(it, now) } ?: TaskState.TASK_STATE_UNSPECIFIED

    override fun pause(): Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "시험 어댑터는 일시정지를 안 든다")

    override fun cancel(): Applied {
        cancels.incrementAndGet()
        return when (cancelScript.poll() ?: Answer.OK) {
            Answer.OK -> Applied.Ok.also { current?.canceled = true }
            Answer.IN_DOUBT -> Applied.Refused(Refusal.LINK_IN_DOUBT, "취소 응답을 잃었다(시험)")
            Answer.LINK_ERROR -> Applied.Refused(Refusal.LINK_ERROR, "링크 오류(시험)")
        }
    }

    override fun hold(): HoldObservation = HoldObservation.Empty
    override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
    override fun knownSiteNames(): SiteNames = SiteNames.Known(listOf("bay-7", "dock-3"))
}

/**
 * 실제 수신자 — picasso `AdapterHost` 하나(실 포트)에 [robots] 를 든다. 대조는 `FENCED` 이고 바닥값은 [durable] 이면 운영과 같은 [FileFenceStore](이 수신자의
 * 디렉터리 [dir]), 아니면 메모리다. [restart] 는 수신자 프로세스를 다시 띄운 것처럼 새 `AdapterHost`·새 `HostedRobot`(메모리의 미확정·장부가 비어 있음)을
 * **같은 포트·같은 바닥값 디렉터리**로 세운다. 벤더 쪽([ScriptedAdapter])은 그대로다.
 *
 * 들어온 RPC 를 센다 — [startRpcs] 는 태스크 id 마다 `StartTask` 수(조회 재전송 포함), [snapshotRpcs] 는 `GetSnapshot` 수. [failSnapshots] 가 참이면
 * `GetSnapshot` 을 `UNAVAILABLE` 로 끝낸다(읽기 실패).
 */
class Receiver(
    private val robots: Map<String, Pair<Path, ScriptedAdapter>>,
    private val clock: () -> Instant,
    private val fenceWait: Duration = Duration.ofSeconds(1),
    private val durable: Boolean = true,
    /** 재시작 때 바닥값 디렉터리를 무엇으로 둘지. 기본은 같은 디렉터리다(결함 주입이 새 디렉터리를 준다). */
    private val nextDir: (Path) -> Path = { it },
) : AutoCloseable {
    var dir: Path = Files.createTempDirectory("receiver-fence")
        private set
    private var store: FileFenceStore? = null
    lateinit var host: AdapterHost
        private set
    var port: Int = 0
        private set
    val restarts = AtomicInteger()

    val startRpcs = ConcurrentHashMap<String, AtomicInteger>()
    val snapshotRpcs = AtomicInteger()

    @Volatile var failSnapshots = false

    private val channels = CopyOnWriteArrayList<ManagedChannel>()
    private val dirs = CopyOnWriteArrayList(listOf(dir))

    fun startRpcsOf(taskId: String): Int = startRpcs[taskId]?.get() ?: 0

    private val counting = object : ServerInterceptor {
        override fun <ReqT : Any, RespT : Any> interceptCall(call: ServerCall<ReqT, RespT>, headers: Metadata, next: ServerCallHandler<ReqT, RespT>): ServerCall.Listener<ReqT> {
            val method = call.methodDescriptor.fullMethodName
            if (method == EventServiceGrpc.getGetSnapshotMethod().fullMethodName) {
                snapshotRpcs.incrementAndGet()
                if (failSnapshots) {
                    call.close(Status.UNAVAILABLE.withDescription("읽기 실패(시험)"), Metadata())
                    return object : ServerCall.Listener<ReqT>() {}
                }
            }
            val delegate = next.startCall(call, headers)
            if (method != TaskServiceGrpc.getStartTaskMethod().fullMethodName) return delegate
            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
                override fun onMessage(message: ReqT) {
                    (message as? StartTaskRequest)?.let { startRpcs.computeIfAbsent(it.taskId) { AtomicInteger() }.incrementAndGet() }
                    super.onMessage(message)
                }
            }
        }
    }

    private fun fence(): ControllerFence =
        if (durable) ControllerFence(FenceMode.FENCED, FileFenceStore(dir).also { store = it }) else ControllerFence(FenceMode.FENCED, FenceStore.memory())

    private fun launch(at: Int) {
        val hosted = robots.map { (robotId, entry) ->
            HostedRobot(robotId, ProfileDocument.parse(robotId, Files.readString(entry.first)).getOrThrow(), entry.second, clock = clock)
        }.associateBy { it.robotId }
        val fence = fence()
        // 같은 포트를 다시 잡을 때 앞 서버의 소켓이 아직 안 풀렸을 수 있다 — 잠깐씩 다시 묶는다(5초 상한).
        var attempt = 0
        while (true) {
            val candidate = AdapterHost(hosted, ServerBuilder.forPort(at).intercept(counting), fence = fence, fenceWait = fenceWait)
            try {
                host = candidate.start()
                break
            } catch (e: java.io.IOException) {
                if (at == 0 || ++attempt >= 50) throw e
                Thread.sleep(100)
            }
        }
        port = host.port
    }

    init {
        launch(0)
    }

    /** 수신자만 다시 띄운다(같은 포트, 바닥값 디렉터리는 [nextDir]). 메모리의 미확정·장부는 사라진다. */
    fun restart() {
        host.shutdown()
        // `shutdown` 은 `shutdownNow` 만 부른다 — 서버가 다 내려가 포트를 놓을 때까지 기다린다.
        host.awaitTermination()
        store?.close()
        val next = nextDir(dir)
        if (next != dir) dirs.add(next)
        dir = next
        launch(port)
        restarts.incrementAndGet()
    }

    /** 이 수신자에 붙는 새 채널. 닫는 것은 이 수신자다. */
    fun channel(): ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build().also { channels += it }

    override fun close() {
        channels.forEach { it.shutdownNow() }
        runCatching { host.shutdown() }
        store?.close()
        dirs.forEach { it.toFile().deleteRecursively() }
    }
}
