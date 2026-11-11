package dev.picasso.ops.load

import com.sun.management.GarbageCollectionNotificationInfo
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

/**
 * 일하는 pump 의 벽시계 표본. [during] 안에서 그 스레드의 스택을 [periodMs] 마다 읽어 `MissionHost.pumpOnce` 아래 어느 단계에 있었는지
 * 센다. JFR 의 실행 표본은 gRPC·HTTP 응답을 기다리며 멈춘 스레드를 세지 않아 pump 처럼 기다림이 대부분인 일에서는 시간이 보이지 않는다.
 *
 * 단계: 셀 대역 읽기(`CellBand`), picasso `Middleware.pump`(그 아래 첫 프레임, gRPC 를 지나면 `[grpc]`), pump 뒤 기록
 * (`recordAfterPump`; DB 를 지나면 그 아래 가장 바깥의 `HostRecords` 호출과 `[db]`), 그 밖. 표본은 비율로만 읽는다.
 */
class WallSampler(private val periodMs: Long = 2) : AutoCloseable {
    private val counts = ConcurrentHashMap<Pair<Int, String>, AtomicLong>()
    @Volatile private var target: Thread? = null
    @Volatile private var robots = 0
    @Volatile private var open = true
    private val thread = Thread {
        while (open) {
            target?.let(::sample)
            Thread.sleep(periodMs)
        }
    }.apply { isDaemon = true; name = "wall-sampler"; start() }

    private fun sample(t: Thread) {
        val frames = t.stackTrace.map { it.className + "." + it.methodName }
        val pump = frames.indexOfFirst { it.endsWith("MissionHost.pumpOnce") }
        if (pump < 0) return
        val callees = frames.subList(0, pump) // 잎이 먼저
        val step = callees.asReversed().firstNotNullOfOrNull { f -> STEPS.firstOrNull { it in f } } ?: "그 밖"
        val label = when (step) {
            "recordAfterPump" -> {
                val records = callees.indexOfLast { "HostRecords." in it && !it.endsWith("inTransaction") && "$" !in it.substringAfterLast('.') }
                val db = callees.any { "postgresql" in it }
                "pump 뒤 기록 · " + (if (records >= 0) callees[records].substringAfterLast("store.") else under(callees, step)) + if (db) " [db]" else ""
            }
            "Middleware.pump" -> "picasso Middleware.pump · " + under(callees, step) + if (callees.any { "grpc" in it.lowercase() }) " [grpc]" else ""
            "CellBand" -> "셀 대역 읽기"
            else -> step
        }
        counts.computeIfAbsent(robots to label) { AtomicLong() }.incrementAndGet()
    }

    private fun under(callees: List<String>, step: String): String {
        val j = callees.indexOfLast { step in it }
        return callees.getOrNull(j - 1)?.substringAfterLast("middleware.")?.substringAfterLast("host.") ?: "-"
    }

    fun <T> during(robots: Int, block: () -> T): T {
        this.robots = robots
        target = Thread.currentThread()
        try {
            return block()
        } finally {
            target = null
        }
    }

    /** (기체 M, 단계, 표본, 그 M 의 표본 가운데 비율 %). 상위 [top] 개와 나머지 합. */
    fun rows(top: Int = 10): List<List<String>> = counts.entries.groupBy({ it.key.first }) { it.key.second to it.value.get() }.toSortedMap().flatMap { (m, list) ->
        val total = list.sumOf { it.second }
        val sorted = list.sortedByDescending { it.second }
        val shown = sorted.take(top).map { (label, n) -> listOf("$m", label, "$n", f(100.0 * n / total)) }
        val rest = sorted.drop(top).sumOf { it.second }
        shown + (if (rest > 0) listOf(listOf("$m", "나머지", "$rest", f(100.0 * rest / total))) else emptyList()) +
            listOf(listOf("$m", "합", "$total", "100"))
    }

    override fun close() {
        open = false
        thread.join()
    }

    private companion object {
        val STEPS = listOf("CellBand", "retryDeferred", "recordReruns", "journalBlocked", "recordAfterPump", "Middleware.pump")
    }
}

/** 그동안의 GC 정지 가운데 가장 긴 것(ms). HA 설계 §4.5 의 만료 하한 식에 드는 항이다. */
class GcPauses : AutoCloseable {
    private val max = AtomicLong()
    private val listener = javax.management.NotificationListener { n, _ ->
        if (n.type == GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) {
            val info = GarbageCollectionNotificationInfo.from(n.userData as CompositeData)
            // G1 의 동시 주기(«G1 Concurrent GC»)는 정지가 아니다.
            if ("Concurrent" !in info.gcName) max.accumulateAndGet(info.gcInfo.duration) { a, b -> maxOf(a, b) }
        }
    }
    private val emitters = ManagementFactory.getGarbageCollectorMXBeans().map { it as NotificationEmitter }.onEach { it.addNotificationListener(listener, null, null) }

    fun maxMs(): Long = max.get()

    override fun close() = emitters.forEach { it.removeNotificationListener(listener) }
}
