package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 비정상 종료 뒤 순차 인수(HA 설계 기록 §7·§8 단계 0, 보장 문서 «비정상 종료 뒤 재기동»). [HostInstanceTest] 는 다른 인스턴스를
 * `mission.host_instance` 행으로 흉내 내지만, 여기서는 실제 실행 호스트(Spring 컨텍스트) 둘을 같은 시험 DB·같은 mimic 에 차례로 띄운다.
 * 첫째는 멈춤을 적지 못하고 죽는다([HostBench.killHost] — 커넥션 풀을 먼저 닫고 컨텍스트를 닫는 같은 JVM 안의 흉내이고, 그 한계는 그
 * 함수에 적었다).
 *
 * 이 시험은 «앞 프로세스가 정말 죽은 뒤 둘째가 뜨는» 순차 인수만 본다. 만료 뒤 깨어난 옛 프로세스, 긴 GC 정지, 두 프로세스가 함께 도는
 * 구성, 리더 선출·펜싱은 보지 않는다(그것들은 지어지지 않았다). 인수 공백의 시간 수치는 시험이 아니라 부하 하네스의 `takeover`
 * 시나리오가 잰다(`docs/measurements/2026-11-16-host-takeover.md`).
 */
class HostTakeoverTest {

    @Test
    fun `멈춤을 적지 못하고 죽은 호스트의 도는 실행은 다음 호스트가 만료까지 미루고 그 기체를 판정과 배정에서 빼며 만료 뒤 같은 태스크에 다시 붙어 새 출발 없이 끝까지 간다`() {
        HostBench(heartbeat = PERIOD to EXPIRY).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.driveUnits("exec-1") { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
            // 앞 인스턴스가 낼 기체 명령을 다 낸 뒤에 죽인다. 가상 시계를 밀지 않으므로 그 뒤 단위 상태는 바뀌지 않는다.
            bench.idlePumps()
            val tasksBefore = bench.taskIds()
            assertEquals(listOf("JO-1#T1", "JO-1#T1.travel"), tasksBefore)
            val first = bench.host.instanceId

            bench.killHost()
            val second = bench.host.instanceId
            assertNotEquals(first, second)

            // 만료 전: 첫째는 멈춤이 없고 아직 살아 있다. 둘째는 그 행을 미루고 실행을 세우지 않는다.
            val dead = instance(first)
            assertNull(dead.stoppedAt, "죽은 인스턴스가 멈춤을 적었다: $dead")
            assertTrue(dead.live, "둘째가 뜨기 전에 첫째가 만료됐다(기동이 만료 폭 $EXPIRY 보다 오래 걸림): $dead")
            val row = restoreRow(bench)
            assertEquals(
                listOf("JO-1", HUMANOID, first, "exec-1", "DEFERRED"),
                listOf("jobOrderId", "robotId", "previousInstanceId", "previousExecutionId", "result").map { row[it].asText() },
                row.toString(),
            )
            assertEquals("${MissionHost.HELD_BY_LIVE_INSTANCE}: instance=$first", row["reason"].asText())
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-1"), reasons(bench))
            val refused = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("UNASSIGNED", refused["result"].asText(), refused.toString())
            assertEquals(listOf("${MissionHost.UNRESTORED_REASON}: JO-1"), refused["excluded"].single()["reasons"].map { it.asText() })
            bench.idlePumps()
            assertEquals(tasksBefore, bench.taskIds())
            assertEquals(listOf("DEFERRED" to second), events("JO-1"))
            // 위 관찰이 모두 만료 전에 끝났다. 아니면 위 단언은 «만료 전» 의 증거가 아니다.
            assertTrue(instance(first).live, "만료 전 관찰이 만료 폭 $EXPIRY 을 넘겼다")

            // 만료 뒤: pump 가 그 행을 둘째의 실행으로 다시 짓는다. 첫째의 행은 멈춤 없이 만료된 채다.
            awaitRestored(bench)
            val expired = instance(first)
            assertTrue(!expired.live && expired.stoppedAt == null, expired.toString())
            val restored = restoreRow(bench)
            assertTrue(restored["reason"].isNull, restored.toString())
            val executionId = restored["executionId"].asText()
            assertEquals(listOf("DEFERRED" to second, "RESTORED" to second), events("JO-1"))
            assertEquals(JSON.readTree("""{"instanceId":"$first","executionId":"exec-1"}"""), bench.execution(executionId)!!["restoredFrom"])
            // 가상 시계를 밀지 않고 다시 관측하면 끝난 단위는 끝난 대로, 도는 단위는 같은 태스크로 선다. 새 출발은 없다.
            bench.eventually("단위 다시 관측") {
                units(bench.execution(executionId)!!) == mapOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING")
            }
            bench.idlePumps()
            assertEquals(tasksBefore, bench.taskIds())
            assertEquals(listOf("진행 중인 실행이 있다: $executionId"), reasons(bench))

            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            // 태스크마다 한 번씩만 났다(첫째가 낸 둘에 둘째가 남은 둘을 더했다).
            assertEquals(listOf("JO-1#T1", "JO-1#T1.travel", "JO-1#T2", "JO-1#T2.travel"), bench.taskIds())
            bench.eventually("정착 이벤트") { events("JO-1").size == 3 }
            assertEquals("SETTLED" to second, events("JO-1")[2])
        }
    }

    private data class InstanceRow(val stoppedAt: Instant?, val live: Boolean)

    private companion object {
        /** 심장박동 주기와 만료. 만료는 주기의 세 배 이상이어야 하고([HostHeartbeat]), 둘째 컨텍스트의 기동보다 넉넉히 길어야 한다. */
        val PERIOD: Duration = Duration.ofMillis(500)
        val EXPIRY: Duration = Duration.ofSeconds(6)

        fun instance(id: String): InstanceRow = PostgresSupport.queryAll(
            "SELECT stopped_at, (stopped_at IS NULL AND expires_at > clock_timestamp()) FROM mission.host_instance WHERE instance_id = '$id'",
        ) { InstanceRow(it.getTimestamp(1)?.toInstant(), it.getBoolean(2)) }.single()

        fun restoreRow(bench: HostBench): JsonNode = bench.get("/host/executions")["restore"]["rows"].single()

        /** 만료 폭과 pump 몇 번이 지나 그 행이 RESTORED 가 될 때까지 기다린다. */
        fun awaitRestored(bench: HostBench) {
            val deadline = Instant.now().plus(EXPIRY).plusSeconds(10)
            while (restoreRow(bench)["result"].asText() != "RESTORED") {
                check(Instant.now().isBefore(deadline)) { "만료 뒤에도 다시 짓지 않았다: ${restoreRow(bench)}" }
                Thread.sleep(100)
            }
        }

        fun reasons(bench: HostBench): List<String> =
            bench.post("/host/eligibility", request(inspect("JO-9", "T2" to "dock-3"), "robotIds", HUMANOID)).body!!["robots"].single()["reasons"].map { it.asText() }

        fun events(jobOrderId: String): List<Pair<String, String>> = PostgresSupport.queryAll(
            "SELECT kind, instance_id FROM mission.execution_journal_event WHERE job_order_id = '$jobOrderId' ORDER BY event_id",
        ) { it.getString(1) to it.getString(2) }

        fun units(execution: JsonNode): Map<String, String> = execution["units"].associate { it["unitId"].asText() to it["state"].asText() }

        /** mimic 이 든 태스크 id(정렬, 겹침을 접지 않음). 같은 id 의 새 접수나 새 id 의 출발이 있으면 이 목록이 길어진다. */
        fun HostBench.taskIds(): List<String> = mimic.server.exclusive { mimic.instance(HUMANOID)!!.tasks.all.map { it.taskId } }.sorted()

        /** 실행의 단위가 [done] 을 만족할 때까지 가상 시계를 1초씩 민다(가상 120초 상한). */
        fun HostBench.driveUnits(executionId: String, done: (Map<String, String>) -> Boolean) {
            repeat(120) {
                if (done(units(checkNotNull(execution(executionId))))) return
                mimic.server.advance(Duration.ofSeconds(1))
                awaitPump()
                Thread.sleep(MissionHost.PUMP_PERIOD.toMillis())
            }
            error("단위가 바라는 상태에 이르지 않았다: ${execution(executionId)}")
        }
    }
}
