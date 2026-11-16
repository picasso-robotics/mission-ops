package dev.picasso.ops.host

import com.fasterxml.jackson.databind.JsonNode
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.JSON
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 비정상 종료 뒤 만료 인수(HA 설계 기록 §7, 능동-대기 1단계 기반). 실제 실행 호스트(Spring 컨텍스트) 둘을 같은 시험 DB·같은 mimic 에 차례로
 * 띄운다. 첫째는 멈춤을 적지 못하고 리스를 내놓지 못한 채 죽는다([HostBench.killHost] — 커넥션 풀을 먼저 닫고 컨텍스트를 닫는 같은 JVM 안의
 * 흉내이고, 그 한계는 그 함수에 적었다).
 *
 * 둘째는 리스가 만료할 때까지 대기다(변경 요청 503, 실행을 세우지 않음). 만료 뒤 리스를 얻어 같은 태스크에 다시 붙지만 만료 인수라 관측만 하고
 * ([LeaderState.HOLDING]) 가상 시계가 흘러 다음 단위가 출발할 때가 되어도 새 출발을 내지 않는다. 운영자가 옛 프로세스 종료를 확인하면 발행을 열고
 * 남은 단위를 끝까지 낸다. 만료 뒤 깨어난 옛 프로세스와 두 프로세스가 함께 도는 구성은 [HostLeaderTest] 가 본다. 인수 공백의 시간 수치는 시험이
 * 아니라 부하 하네스의 `takeover` 시나리오가 잰다(`docs/measurements/2026-11-16-host-lease.md`).
 */
class HostTakeoverTest {

    @Test
    fun `멈춤을 적지 못하고 죽은 호스트의 실행은 다음 호스트가 리스 만료까지 대기했다가 넘겨받아 같은 태스크에 다시 붙고 운영자 확인 전에는 새 출발 없이 관측만 하며 확인 뒤 끝까지 간다`() {
        HostBench(lease = TIMING).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.driveUnits("exec-1") { it["T1.travel"] == "DONE" && it["T1"] == "RUNNING" }
            // 앞 인스턴스가 낼 기체 명령을 다 낸 뒤에 죽인다. 가상 시계를 밀지 않으므로 그 뒤 단위 상태는 바뀌지 않는다.
            bench.idlePumps()
            val tasksBefore = bench.taskIds()
            assertEquals(listOf("JO-1#T1", "JO-1#T1.travel"), tasksBefore)
            val first = bench.host.instanceId
            assertEquals(1L, lease().second)

            bench.killHost()
            val second = bench.host.instanceId
            assertNotEquals(first, second)

            // 만료 전: 리스는 죽은 첫째의 것이다. 둘째는 대기라 복원하지 않고 변경 요청을 503 과 보유자·세대로 돌려준다.
            assertEquals(first to 1L, lease())
            assertEquals(LeaderState.STANDBY, bench.leadership.state)
            assertNull(instance(first), "죽은 인스턴스가 멈춤을 적었다")
            val refused = bench.post("/host/job-orders", request(inspect("JO-2", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals(503, refused.status, refused.body.toString())
            assertEquals(listOf("NOT_ISSUING", "STANDBY", first, "1"), listOf("error", "state", "holder", "generation").map { refused.body!![it].asText() })
            assertEquals(503, bench.fetch("/host/ready").status)
            assertTrue(bench.get("/host/executions")["executions"].isEmpty)
            bench.idlePumps()
            assertEquals(tasksBefore, bench.taskIds())
            assertEquals(emptyList<Pair<String, String>>(), events("JO-1"))

            // 만료 뒤: 둘째가 세대 2 로 넘겨받고, 앞 보유자가 멈춤을 적지 않았으므로 관측(HOLDING)에 든다.
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            assertEquals(second to 2L, lease())
            val leader = bench.get("/host/leader")
            assertEquals(JSON.readTree("""{"generation":2,"previousHolder":"$first","graceful":false,"confirmation":null}"""), leader["takeover"], leader.toString())
            assertEquals(false, leader["ready"].asBoolean())
            val restored = restoreRow(bench)
            assertEquals(listOf("JO-1", first, "exec-1", "RESTORED"), listOf("jobOrderId", "previousInstanceId", "previousExecutionId", "result").map { restored[it].asText() })
            val executionId = restored["executionId"].asText()
            assertEquals(listOf("RESTORED" to second), events("JO-1"))
            assertEquals(JSON.readTree("""{"instanceId":"$first","executionId":"exec-1"}"""), bench.execution(executionId)!!["restoredFrom"])
            bench.eventually("단위 다시 관측") {
                units(bench.execution(executionId)!!) == mapOf("T1.travel" to "DONE", "T1" to "RUNNING", "T2.travel" to "PENDING", "T2" to "PENDING")
            }

            // 관측 중: 가상 시계가 흘러 T1 이 끝나고 T2.travel 이 출발할 때가 되어도 출발을 내지 않는다(송신 직전 확인이 막는다). 변경 요청도 503 이다.
            bench.eventually("T1 끝남 관측") {
                bench.mimic.server.advance(Duration.ofSeconds(5))
                bench.idlePumps(1)
                units(bench.execution(executionId)!!)["T1"] == "DONE"
            }
            repeat(4) {
                bench.mimic.server.advance(Duration.ofSeconds(5))
                bench.idlePumps(2)
            }
            assertEquals("PENDING", units(bench.execution(executionId)!!)["T2.travel"])
            assertEquals(tasksBefore, bench.taskIds())
            assertEquals(503, bench.post("/host/job-orders", request(inspect("JO-3", "T1" to "bay-7"), "candidates", HUMANOID)).status)

            // 운영자 확인은 본 대상(인스턴스·세대·앞 보유자)을 싣는다. 대상이 다르면, 앞 보유자가 살아 있다는 증거가 있으면 받지 않는다.
            fun confirm(generation: Long = 2, reason: String = "옛 호스트 프로세스가 끝난 것을 현장에서 확인") = bench.post(
                "/host/leader/takeover-confirmation",
                """{"confirmedBy":"kim","reason":"$reason","instanceId":"$second","generation":$generation,"previousHolder":"$first"}""",
            )
            assertEquals("TAKEOVER_TARGET_CHANGED", confirm(generation = 3).body!!["error"].asText())
            Fenced.execute("UPDATE mission.host_instance SET last_seen_at = clock_timestamp(), expires_at = clock_timestamp() + interval '1 hour' WHERE instance_id = '$first'")
            assertEquals("PREVIOUS_HOLDER_LIVE", confirm().body!!["error"].asText())
            Fenced.execute("UPDATE mission.host_instance SET last_seen_at = clock_timestamp() - interval '2 seconds', expires_at = clock_timestamp() - interval '1 second' WHERE instance_id = '$first'")
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            assertEquals(tasksBefore, bench.taskIds())

            // 운영자 확인: 누가·왜를 적고 발행을 연다. 남은 단위가 같은 실행으로 끝까지 간다.
            val confirmed = confirm()
            assertEquals(200, confirmed.status, confirmed.body.toString())
            assertEquals("ISSUING", confirmed.body!!["state"].asText())
            assertEquals("kim", confirmed.body["takeover"]["confirmation"]["confirmedBy"].asText())
            assertEquals(listOf("2|kim|$first"), PostgresSupport.queryAll("SELECT generation, confirmed_by, previous_holder FROM mission.host_takeover_confirmation") {
                "${it.getLong(1)}|${it.getString(2)}|${it.getString(3)}"
            })
            assertEquals(200, bench.fetch("/host/ready").status)
            assertEquals(true, PostgresSupport.queryOne("SELECT issuing FROM mission.host_lease_term") { it.getBoolean(1) })
            assertEquals(409, confirm(reason = "다시").status)

            val done = bench.driveUntil(executionId, setOf("PHYSICALLY_DONE", "FAILED", "UNVERIFIED", "ABORTED", "PARTIAL"))
            assertEquals("PHYSICALLY_DONE", done["physicalState"].asText(), done.toString())
            // 태스크마다 한 번씩만 났다(첫째가 낸 둘에 둘째가 확인 뒤 남은 둘을 더했다).
            assertEquals(listOf("JO-1#T1", "JO-1#T1.travel", "JO-1#T2", "JO-1#T2.travel"), bench.taskIds())
            bench.eventually("정착 이벤트") { events("JO-1").size == 2 }
            assertEquals("SETTLED" to second, events("JO-1")[1])
        }
    }

    @Test
    fun `만료 인수 뒤 관측 중인 호스트를 정상 종료해도 다음 호스트는 운영자 확인 없이 발행하지 않고 확인이 발행 표시에서 실패하면 같은 대상의 재요청이 이어 간다`() {
        HostBench(lease = TIMING).use { bench ->
            val first = bench.host.instanceId
            bench.killHost()
            val second = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            // 운영자가 확인 대신 관측 중인 호스트를 다시 띄운다(정상 종료: 멈춤을 적고 리스를 내놓음).
            bench.restartHost()
            val third = bench.host.instanceId
            assertEquals(LeaderState.HOLDING, bench.leadership.state, "확인 안 된 인수가 정상 종료로 세탁됐다")
            val leader = bench.get("/host/leader")
            assertEquals(JSON.readTree("""{"generation":3,"previousHolder":"$second","graceful":false,"confirmation":null}"""), leader["takeover"], leader.toString())
            assertEquals(third to 3L, lease())
            assertEquals(503, bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).status)
            assertTrue(first != second && second != third)

            // 확인이 확인 행만 적고 발행 표시에서 실패하면(연결 장애) 같은 대상의 재요청이 그 전이를 이어 간다.
            bench.leadership.lease.beforeMark = {
                bench.leadership.lease.beforeMark = null
                throw java.sql.SQLException("시험: 발행 표시 직전 연결 장애", "08006")
            }
            val body = """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$third","generation":3,"previousHolder":"$second"}"""
            assertEquals(500, bench.post("/host/leader/takeover-confirmation", body).status)
            assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM mission.host_takeover_confirmation WHERE generation = 3") { it.getInt(1) })
            assertEquals(LeaderState.HOLDING, bench.leadership.state)
            val resumed = bench.post("/host/leader/takeover-confirmation", body)
            assertEquals(200, resumed.status, resumed.body.toString())
            assertEquals(LeaderState.ISSUING, bench.leadership.state)
            assertEquals(409, bench.post("/host/leader/takeover-confirmation", body).status)
        }
    }

    private companion object {
        /** 리스 주기 0.5초·만료 6초(둘째 컨텍스트의 기동보다 넉넉히 길게)·여유 1초·유휴 트랜잭션 제한 3초. */
        val TIMING = LeaseTiming(Duration.ofMillis(500), Duration.ofSeconds(6), Duration.ofSeconds(1), Duration.ofSeconds(3))

        /** 그 인스턴스의 멈춤 시각. 없으면 `null`. */
        fun instance(id: String): Instant? = PostgresSupport.queryAll(
            "SELECT stopped_at FROM mission.host_instance WHERE instance_id = '$id'",
        ) { it.getTimestamp(1)?.toInstant() }.single()

        fun lease(): Pair<String?, Long> = PostgresSupport.queryOne("SELECT holder, generation FROM mission.host_lease") { it.getString(1) to it.getLong(2) }

        fun restoreRow(bench: HostBench): JsonNode = bench.get("/host/executions")["restore"]["rows"].single()

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
