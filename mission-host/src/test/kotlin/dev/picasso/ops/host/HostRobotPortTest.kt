package dev.picasso.ops.host

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.middleware.PrefetchedRead
import dev.picasso.middleware.Replay
import dev.picasso.middleware.RobotPort
import dev.picasso.middleware.RobotSnapshot
import dev.picasso.contracts.v1.TaskHandle
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HostRobotPortTest {

    /** 부른 스레드를 남기는 기체 포트. 시험 스레드에서 불린 것이 «지금 읽기» 다. */
    private class Recording : RobotPort {
        val snapshots = CopyOnWriteArrayList<Thread>()
        val replays = CopyOnWriteArrayList<Thread>()
        val declarations = CopyOnWriteArrayList<Thread>()

        override fun capabilities(robotId: String): Capability {
            declarations += Thread.currentThread()
            return Capability.getDefaultInstance()
        }

        override fun snapshot(robotId: String): RobotSnapshot {
            snapshots += Thread.currentThread()
            return RobotSnapshot(sequence = 7, faults = emptyList(), connection = ConnectionState.CONNECTION_STATE_ONLINE, tasks = emptyMap(), session = "s1")
        }

        override fun replay(robotId: String, from: Long): Replay {
            replays += Thread.currentThread()
            return Replay.Events(emptyList())
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            throw UnsupportedOperationException()

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = throw UnsupportedOperationException()

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = throw UnsupportedOperationException()
    }

    private fun Recording.onThisThread(calls: List<Thread>) = calls.count { it == Thread.currentThread() }

    @Test
    fun `미리 읽은 선언이 없으면 선언을 지금 묻고 출발을 미루지 않는다`() {
        val robot = Recording()
        var now = Instant.parse("2026-11-11T00:00:00Z")
        HostRobotPort(robot, HostClock { now }).use { port ->
            // 가상 시계가 매번 maxAge 를 넘겨 건너뛰어도 «읽는 중» 이 되지 않는다 — 미리 읽는 포트 그대로면 기다림이 매번 다시 시작된다.
            assertFalse(port.capabilitiesPending("r1"))
            assertEquals(Capability.getDefaultInstance(), port.capabilities("r1"))
            assertEquals(1, robot.onThisThread(robot.declarations), "미리 읽은 선언이 없으면 지금 묻는다")
            repeat(3) {
                now = now.plus(Duration.ofSeconds(5))
                assertFalse(port.capabilitiesPending("r1"))
                assertEquals(Capability.getDefaultInstance(), port.capabilities("r1"))
            }
        }
    }

    /** 미리 읽기를 쌓아 두었다가 [drain] 이 시험 스레드 아닌 스레드에서 다 돌리는 실행기. 읽기가 끝나는 때를 시험이 정한다. */
    private class Reads : Executor {
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun execute(command: Runnable) {
            queue += command
        }

        fun drain() {
            Thread { while (true) (queue.poll() ?: break).run() }.apply { start(); join() }
        }
    }

    @Test
    fun `출발을 앞둔 기체는 데워져 있어도 지금 읽고 그 라운드의 재생도 지금 묻는다`() {
        val robot = Recording()
        val reads = Reads()
        HostRobotPort(robot, HostClock { Instant.parse("2026-11-11T00:00:00Z") }, reads).use { port ->
            port.prefetched("r1")
            port.replay("r1", 7)
            reads.drain()
            assertEquals(1, robot.onThisThread(robot.snapshots))

            // 미리 읽은 값이 있다. 그래도 출발 관문은 이 라운드 뷰로 결함을 보므로 지금 읽는다. 한 라운드 앞에 보낸 읽기로는 그 사이 선
            // 결함을 못 본다.
            port.readNowFor(setOf("r1"))
            assertIs<PrefetchedRead.Read>(port.prefetched("r1"))
            assertEquals(2, robot.onThisThread(robot.snapshots), "출발을 앞둔 기체는 미리 읽은 값이 있어도 지금 읽는다")
            port.replay("r1", 7)
            assertEquals(2, robot.onThisThread(robot.replays), "지금 읽은 라운드의 재생은 위임 포트에 지금 묻는다")

            // 집합에서 빠지면 다시 미리 읽은 값을 쓴다.
            port.readNowFor(emptySet())
            reads.drain()
            assertIs<PrefetchedRead.Read>(port.prefetched("r1"))
            assertEquals(2, robot.onThisThread(robot.snapshots), "출발을 앞두지 않은 기체는 미리 읽은 값을 쓴다")
        }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = Instant.now().plusSeconds(5)
        while (!condition()) {
            check(Instant.now().isBefore(deadline)) { "5초 안에 이르지 않았다: $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `쓸 미리 읽기가 없으면 지금 읽고 그 라운드의 재생도 지금 묻고 데워지면 미리 읽은 값을 쓰며 낡으면 다시 지금 읽는다`() {
        val robot = Recording()
        var now = Instant.parse("2026-11-11T00:00:00Z")
        HostRobotPort(robot, HostClock { now }).use { port ->
            // 처음: 미리 읽은 값이 없다. 못 본 기체로 넘기지 않고 지금 읽는다 — 출발 관문이 결함을 못 본 채 지나가지 않게.
            val first = port.prefetched("r1")
            assertIs<PrefetchedRead.Read>(first)
            assertEquals(7, first.snapshot!!.sequence)
            assertEquals(1, robot.onThisThread(robot.snapshots))
            port.replay("r1", 7)
            assertEquals(1, robot.onThisThread(robot.replays), "지금 읽은 라운드의 재생은 위임 포트에 지금 묻는다")

            // 첫 호출이 건 미리 읽기가 끝나면 그 값을 쓴다. 시험 스레드에서 더 읽지 않는다.
            eventually("미리 읽기") { robot.snapshots.size >= 2 }
            assertIs<PrefetchedRead.Read>(port.prefetched("r1"))
            assertEquals(1, robot.onThisThread(robot.snapshots), "데워진 뒤에는 잠금 아래에서 읽지 않는다")
            port.replay("r1", 7)
            assertEquals(1, robot.onThisThread(robot.replays), "미리 읽은 라운드의 재생은 미리 읽는 포트가 준다")

            // snapshot 은 데워진 값이 있어도 지금 묻는다 — 부르는 자리(복원, 종결 확인)는 지금의 기체 상태로 정한다.
            port.snapshot("r1")
            assertEquals(2, robot.onThisThread(robot.snapshots), "snapshot 은 미리 읽은 값을 주지 않는다")

            // maxAge(2초)를 넘겨 낡으면 다시 지금 읽는다.
            eventually("다음 미리 읽기") { robot.snapshots.size >= 3 }
            now = now.plus(Duration.ofSeconds(5))
            assertIs<PrefetchedRead.Read>(port.prefetched("r1"))
            assertEquals(3, robot.onThisThread(robot.snapshots))
            assertTrue(robot.snapshots.any { it != Thread.currentThread() })
        }
    }
}
