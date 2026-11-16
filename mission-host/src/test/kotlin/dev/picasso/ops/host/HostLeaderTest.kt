package dev.picasso.ops.host

import dev.picasso.fleetmimic.AmrFleetMimic
import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.Approver
import dev.picasso.middleware.ApproverKind
import dev.picasso.middleware.CommandNotSent
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.cell.CellBandClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.LeaseTiming
import dev.picasso.ops.host.store.AuthorityLost
import dev.picasso.ops.host.store.HostRecords
import dev.picasso.ops.host.store.JournalEventKind
import dev.picasso.registry.PostgresSupport
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실행 호스트 둘을 같은 시험 DB·같은 mimic 에 함께 띄운 능동-대기(HA 설계 기록 §7). 리스를 쥔 하나만 복원·pump·발행하고, 다른 하나는 대기로
 * 변경 요청을 503 으로 돌려준다. 정상 종료는 곧바로 넘겨주고, 만료 뒤 깨어난 옛 능동은 DB 쓰기를 거부당하고 리스를 잃으며 새 명령을 내지 않는다.
 */
class HostLeaderTest {

    @Test
    fun `함께 띄운 두 호스트 중 하나만 리스를 얻고 다른 하나는 대기로 변경 요청을 503 으로 돌려주며 정상 종료하면 대기가 곧바로 넘겨받아 발행한다`() {
        HostBench(lease = TIMING).use { bench ->
            val first = bench.leadership
            assertEquals(LeaderState.ISSUING, first.state)
            // 공용 풀 연결은 열 때부터 유휴 트랜잭션 제한·문장 제한을 건다(쓰기 가드가 보유자 행을 잠그기 전부터).
            val jdbc = bench.bean(org.springframework.jdbc.core.simple.JdbcClient::class.java)
            assertEquals("2s", jdbc.sql("SHOW idle_in_transaction_session_timeout").query(String::class.java).single())
            assertEquals("2s", jdbc.sql("SHOW statement_timeout").query(String::class.java).single())
            val standby = bench.startHost()
            val second = bench.leadershipOf(standby)
            bench.idlePumps()
            assertEquals(LeaderState.STANDBY, second.state)
            assertEquals(first.instanceId to 1L, lease())
            assertEquals(200, bench.fetch("/host/ready").status)
            val url = bench.urlOf(standby)
            assertEquals(503, status(url, "/host/ready"))
            val refused = post(url, "/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
            assertEquals(503, refused.statusCode(), refused.body())
            assertTrue(""""holder":"${first.instanceId}"""" in refused.body() && """"generation":1""" in refused.body(), refused.body())
            assertEquals(503, post(url, "/host/cell/signals/rack_present", """{"value":"true"}""").statusCode())
            assertTrue(bench.signalWrites.isEmpty())

            // 정상 종료: 첫째가 멈춤을 적고 리스를 내놓으면 대기가 다음 획득 주기에 넘겨받아 곧바로 발행한다(운영자 확인 없음).
            bench.handOver(standby)
            bench.awaitLeader(LeaderState.ISSUING)
            assertEquals(second.instanceId to 2L, lease())
            val leader = bench.get("/host/leader")
            assertEquals(listOf("true", first.instanceId, "true"), listOf(leader["ready"], leader["takeover"]["previousHolder"], leader["takeover"]["graceful"]).map { it.asText() })
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            assertTrue(bench.exits.isEmpty(), bench.exits.toString())
        }
    }

    @Test
    fun `만료 뒤 깨어난 옛 능동은 DB 쓰기를 거부당하고 리스를 잃어 자기 차단을 적고 기체와 셀에 새 요청을 내지 않는다`() {
        HostBench(lease = TIMING, gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.eventually("첫 출발") { bench.starts.isNotEmpty() }
            val old = bench.leadership
            val oldRecords = bench.bean(HostRecords::class.java)
            val standby = bench.startHost()
            val next = bench.leadershipOf(standby)
            assertEquals(LeaderState.STANDBY, next.state)

            // 옛 능동이 멎는다(긴 GC 정지 따위): 리스 갱신·감시·pump·심장박동이 모두 선다. 그 사이 리스가 만료해 대기가 넘겨받는다.
            old.stall.close()
            try {
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10), next)
                assertEquals(next.instanceId to 2L, lease())
                val starts = bench.starts.toList()
                val cancels = bench.cancels.toList()
                // 멎은 동안 옛 능동 안에서는 아직 발행 중이다(아무것도 보지 못했다). 그 쓰기를 DB 가 리스 세대로 거부하고 옛 능동은 잃음으로 간다.
                assertEquals(LeaderState.ISSUING, old.state)
                assertFailsWith<AuthorityLost> { oldRecords.event("JO-1", JournalEventKind.DEFERRED, old.instanceId, null, "늦은 쓰기") }
                assertEquals(LeaderState.LOST, old.state)
                assertTrue(old.lostReason!!.startsWith("쓰기 직전 리스가 이 인스턴스의 것이 아니다"), old.lostReason)
                bench.eventually("자기 차단 기록과 종료 훅") { bench.exits.any { it.first == old.instanceId } && fencedAt(old.instanceId) }
                assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event WHERE detail = '늦은 쓰기'") { it.getInt(1) })
                assertEquals(null, PostgresSupport.queryOne("SELECT stopped_at FROM mission.host_instance WHERE instance_id = '${old.instanceId}'") { it.getTimestamp(1) })

                old.stall.open()
                // 깨어난 뒤에도 새 출발·취소·셀 쓰기가 없다. 변경 요청은 503 이다.
                bench.mimic.server.advance(Duration.ofSeconds(30))
                bench.idlePumps(4)
                assertEquals(starts, bench.starts.toList())
                assertEquals(cancels, bench.cancels.toList())
                assertEquals(503, bench.post("/host/cell/signals/rack_present", """{"value":"true"}""").status)
                assertTrue(bench.signalWrites.isEmpty())
                assertEquals(503, bench.post("/host/faults", """{"robotId":"$HUMANOID","kind":"CONNECTION"}""").status)
                assertTrue(bench.faultWrites.isEmpty())
                assertEquals(LeaderState.HOLDING, next.state)
                assertEquals(next.instanceId to 2L, lease())
            } finally {
                old.stall.open()
                standby.close()
            }
        }
    }

    @Test
    fun `만료 뒤 깨어난 옛 능동의 pump 가 잃음을 알기 전에 한 라운드 돌아도 송신 직전 확인이 출발을 막고 DB 가드가 쓰기를 막는다`() {
        HostBench(lease = TIMING, gated = true).use { bench ->
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7", "T2" to "dock-3"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.eventually("첫 출발") { bench.starts.isNotEmpty() }
            bench.idlePumps()
            val old = bench.leadership
            val standby = bench.startHost()
            val next = bench.leadershipOf(standby)
            try {
                old.stall.close()
                bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10), next)
                val starts = bench.starts.toList()
                // 멎은 동안 기체 쪽은 흘러 첫 단위(이동)가 끝난다 — 깨어난 pump 는 다음 단위의 출발을 내려 하고 바뀐 상태를 적으려 한다.
                repeat(6) {
                    bench.mimic.server.advance(Duration.ofSeconds(5))
                    bench.idlePumps(1)
                }
                assertEquals(LeaderState.ISSUING, old.state)
                // 감시·리스 스레드는 멎은 채 pump 만 깨운다.
                old.stall.open(dev.picasso.ops.host.lease.HostLeadership.Stall.Part.PUMP)
                bench.eventually("옛 능동이 잃음으로 감") { old.state == LeaderState.LOST }
                assertTrue(old.lostReason!!.startsWith("쓰기 직전 리스가 이 인스턴스의 것이 아니다"), old.lostReason)
                bench.idlePumps()
                assertEquals(starts, bench.starts.toList(), "깨어난 옛 능동이나 관측 중인 새 보유자가 출발을 냈다")
                assertEquals(next.instanceId to 2L, lease())
            } finally {
                old.stall.open()
                standby.close()
            }
        }
    }

    @Test
    fun `만료 인수 뒤 관측 중에는 플릿 맡기기와 기체 취소와 셀 쓰기가 나가지 않고 운영자 확인 뒤에 나간다`() {
        val fleet = CountingFleet(AmrFleetMimic().apply { place(OUT, CONTAINER) })
        HostBench(lease = TIMING, gated = true, fleet = fleet).use { bench ->
            val delivery = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", delivery["result"].asText(), delivery.toString())
            val inspection = bench.post("/host/job-orders", request(inspect("JO-R", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", inspection["result"].asText(), inspection.toString())
            bench.eventually("기체 출발") { bench.starts.isNotEmpty() }
            val first = bench.host.instanceId
            // 첫 호스트는 플릿에 맡기지 못한 채(보내지 않음) 죽는다. 그 뒤로 플릿은 받는다.
            bench.killHost()
            fleet.refuse.set(false)
            val second = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            val executions = bench.get("/host/executions")["executions"].associateBy { it["jobOrderId"].asText() }
            assertEquals(setOf("JO-F", "JO-R"), executions.keys)

            // 관측 중: 운영자 취소를 HTTP 관문 밖에서 직접 걸어도(취소 행은 적힘) 기체에 취소가 나가지 않고, 플릿에도 맡기지 않으며, 셀 쓰기도 막힌다.
            bench.host.cancel(executions.getValue("JO-R")["executionId"].asText(), Approver("kim", ApproverKind.PERSON), "관측 중 취소", java.util.UUID.randomUUID().toString())
            repeat(4) {
                bench.mimic.server.advance(Duration.ofSeconds(2))
                bench.idlePumps(2)
            }
            assertEquals(emptyList<Pair<String, String>>(), bench.cancels.toList())
            assertEquals(0 to 0, fleet.dispatched.get() to fleet.cancelled.get())
            val cell = bench.bean(CellBandClient::class.java)
            assertFailsWith<CommandNotSent> { cell.writeSignal("rack_present", """{"value":"true"}""".toByteArray()) }
            assertFailsWith<CommandNotSent> { cell.injectFault("""{"robotId":"$HUMANOID","kind":"CONNECTION"}""".toByteArray()) }
            assertTrue(bench.signalWrites.isEmpty() && bench.faultWrites.isEmpty())

            // 운영자 확인 뒤에는 미뤄 둔 취소와 맡기기가 나간다(막은 것이 확인이었다).
            bench.eventually("앞 보유자 심장박동 만료") {
                !PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_instance WHERE instance_id = '$first'") { it.getBoolean(1) }
            }
            val confirmed = bench.post(
                "/host/leader/takeover-confirmation",
                """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$second","generation":2,"previousHolder":"$first"}""",
            )
            assertEquals(200, confirmed.status, confirmed.body.toString())
            bench.eventually("취소와 맡기기가 나감") { bench.cancels.isNotEmpty() && fleet.dispatched.get() >= 1 }
        }
    }

    @Test
    fun `송신 중인 명령이 제한 안에 끝나지 않으면 정상 종료가 멈춤도 리스 반환도 하지 않아 다음 호스트는 만료 인수로 관측한다`() {
        HostBench(lease = TIMING, gated = true).use { bench ->
            bench.startDelay = MissionHost.SEND_QUIET_TIMEOUT.plusSeconds(3)
            val accepted = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            bench.eventually("출발 송신 시작") { bench.startsBegun.get() > 0 }
            val first = bench.host.instanceId
            bench.restartHost()
            assertEquals(null, PostgresSupport.queryOne("SELECT stopped_at FROM mission.host_instance WHERE instance_id = '$first'") { it.getTimestamp(1) })
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            val leader = bench.get("/host/leader")
            assertEquals(listOf(first, "false"), listOf(leader["takeover"]["previousHolder"].asText(), leader["takeover"]["graceful"].asText()), leader.toString())
        }
    }

    @Test
    fun `정상 종료는 송신 직전 확인을 지난 셀 쓰기가 끝난 뒤에 멈춤을 적고 리스를 내놓는다`() {
        HostBench(lease = TIMING).use { bench ->
            bench.signalDelay = Duration.ofSeconds(2)
            val cell = bench.bean(CellBandClient::class.java)
            val writer = Thread { runCatching { cell.writeSignal("rack_present", """{"value":"true"}""".toByteArray()) } }.apply { start() }
            bench.eventually("셀 쓰기 송신") { bench.signalWrites.isNotEmpty() }
            val first = bench.host.instanceId
            bench.restartHost()
            writer.join(5000)
            val stopped = PostgresSupport.queryOne("SELECT stopped_at FROM mission.host_instance WHERE instance_id = '$first'") { it.getTimestamp(1)?.toInstant() }
            val replied = assertNotNull(bench.signalRepliedAt)
            assertTrue(stopped != null && stopped.isAfter(replied), "셀 쓰기가 끝나기 전에 멈춤을 적었다: 멈춤 $stopped, 셀 답 $replied")
            assertEquals(LeaderState.ISSUING, bench.leadership.state)
        }
    }

    @Test
    fun `요청 도중 리스를 잃으면 일지 쓰기 실패 500 이 아니라 503 과 리스 보유자로 답한다`() {
        HostBench(lease = TIMING).use { bench ->
            val leadership = bench.leadership
            // 갱신·감시를 멈추고(자기 차단 기한 안) 리스를 DB 에서 다른 보유자에게 넘긴다 — 관문은 지나지만 쓰기 가드가 막는다.
            leadership.stall.close(dev.picasso.ops.host.lease.HostLeadership.Stall.Part.LEASE, dev.picasso.ops.host.lease.HostLeadership.Stall.Part.WATCH)
            try {
                PostgresSupport.execute("UPDATE mission.host_lease SET holder = 'mw-other', generation = generation + 1 WHERE role = 'host'")
                val reply = bench.post("/host/job-orders", request(inspect("JO-1", "T1" to "bay-7"), "candidates", HUMANOID))
                assertEquals(503, reply.status, reply.body.toString())
                assertEquals(listOf("NOT_ISSUING", "mw-other", "2"), listOf("error", "holder", "generation").map { reply.body!![it].asText() })
                assertEquals(LeaderState.LOST, leadership.state)
            } finally {
                leadership.stall.open()
            }
        }
    }

    @Test
    fun `만료 인수 뒤 관측 중에는 운반 중인 플릿 단위의 취소가 플릿에 나가지 않고 운영자 확인 뒤에 나간다`() {
        val fleet = CountingFleet(AmrFleetMimic().apply { place(OUT, CONTAINER) }).apply { refuse.set(false) }
        HostBench(lease = TIMING, gated = true, fleet = fleet).use { bench ->
            val delivery = bench.post("/host/job-orders", request(deliver("JO-F"), "candidates", QUADRUPED)).body!!
            assertEquals("ACCEPTED", delivery["result"].asText(), delivery.toString())
            bench.eventually("플릿에 맡김") { fleet.dispatched.get() == 1 }
            bench.idlePumps()
            val first = bench.host.instanceId
            bench.killHost()
            val second = bench.host.instanceId
            bench.awaitLeader(LeaderState.HOLDING, TIMING.expiry.plusSeconds(10))
            val executionId = bench.get("/host/executions")["executions"].single()["executionId"].asText()
            bench.host.cancel(executionId, Approver("kim", ApproverKind.PERSON), "관측 중 운반 취소", java.util.UUID.randomUUID().toString())
            bench.idlePumps(6)
            assertEquals(1 to 0, fleet.dispatched.get() to fleet.cancelled.get(), "관측 중에 플릿 취소나 새 맡기기가 나갔다")

            bench.eventually("앞 보유자 심장박동 만료") {
                !PostgresSupport.queryOne("SELECT expires_at > clock_timestamp() FROM mission.host_instance WHERE instance_id = '$first'") { it.getBoolean(1) }
            }
            val confirmed = bench.post(
                "/host/leader/takeover-confirmation",
                """{"confirmedBy":"kim","reason":"옛 호스트 종료 확인","instanceId":"$second","generation":2,"previousHolder":"$first"}""",
            )
            assertEquals(200, confirmed.status, confirmed.body.toString())
            bench.eventually("플릿 취소가 나감") { fleet.cancelled.get() >= 1 }
            assertEquals(1, fleet.dispatched.get())
        }
    }

    @Test
    fun `마이그레이션은 리스용 세션 제한이 걸린 공용 풀이 아니라 제한 없는 따로 연 연결로 돈다`() {
        val migration = MissionHostApplication.migrationDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
        migration.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SHOW statement_timeout").use { rs -> rs.next(); assertEquals("0", rs.getString(1)) }
                s.executeQuery("SHOW idle_in_transaction_session_timeout").use { rs -> rs.next(); assertEquals("0", rs.getString(1)) }
            }
        }
    }

    /** 맡기기·취소를 세는 플릿. [refuse] 동안은 맡기기를 보내지 않음(`CommandNotSent`)으로 답하고 세지 않는다. */
    private class CountingFleet(private val inner: AmrFleetMimic) : AmrFleetPort by inner {
        val refuse = AtomicBoolean(true)
        val dispatched = AtomicInteger()
        val cancelled = AtomicInteger()

        override fun dispatch(order: TransportOrder): TransportHandle? {
            if (refuse.get()) throw CommandNotSent("시험: 첫 호스트는 맡기지 못한다")
            dispatched.incrementAndGet()
            return inner.dispatch(order)
        }

        override fun cancel(handle: TransportHandle): Boolean {
            cancelled.incrementAndGet()
            return inner.cancel(handle)
        }
    }

    private companion object {
        const val OUT = "OUT-07"
        const val IN = "LINE-IN-03"
        const val CONTAINER = "HU-1042"

        fun deliver(jobOrderId: String) =
            """{"jobOrderId":"$jobOrderId","workMasterId":"${DeliverContainer.WORK_MASTER}","version":1,"requiredEvidence":"E2",""" +
                """"parameters":{},"materialRequirements":[],"equipmentRequirements":[""" +
                """{"id":"$OUT","equipmentUse":"source","properties":{"container":"$CONTAINER"}},""" +
                """{"id":"$IN","equipmentUse":"destination","properties":{}}]}"""

        /** 리스 주기 0.3초·만료 3초·여유 1초·유휴 트랜잭션 제한 2초. */
        val TIMING = LeaseTiming(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(2))

        fun lease(): Pair<String?, Long> = PostgresSupport.queryOne("SELECT holder, generation FROM mission.host_lease") { it.getString(1) to it.getLong(2) }

        fun fencedAt(instanceId: String): Boolean =
            PostgresSupport.queryOne("SELECT fenced_at IS NOT NULL FROM mission.host_instance WHERE instance_id = '$instanceId'") { it.getBoolean(1) }

        fun status(url: String, path: String): Int =
            HostBench.HTTP.send(HttpRequest.newBuilder(URI.create(url + path)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

        fun post(url: String, path: String, body: String): HttpResponse<String> = HostBench.HTTP.send(
            HttpRequest.newBuilder(URI.create(url + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        ).also { assertNotNull(it) }
    }
}
