package dev.picasso.ops.host

import dev.picasso.ops.host.HostBench.Companion.HUMANOID
import dev.picasso.ops.host.HostBench.Companion.QUADRUPED
import dev.picasso.ops.host.HostBench.Companion.inspect
import dev.picasso.ops.host.HostBench.Companion.request
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.awaitHeartbeatExpired
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.confirm
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.driveAll
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.evidence
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.executionOf
import dev.picasso.ops.host.HostEpochTakeoverTest.Companion.units
import dev.picasso.ops.host.HostReceiverTest.Companion.intents
import dev.picasso.ops.host.HostReceiverTest.World
import dev.picasso.ops.host.lease.LeaderState
import dev.picasso.ops.host.lease.Readiness
import dev.picasso.ops.host.lease.WaitReason
import dev.picasso.registry.PostgresSupport
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 수신자 재시작 의심 보류(HA 후속 D1 완화, picasso 한계 대장 §15.330 의 실행 호스트 쪽 완화)를 실제 수신자(picasso `adapter-host`, `FENCED` + 파일
 * 바닥값)로 본다. 만료 인수 뒤 앞 세대가 보냈을 수 있는 단위(판단 기록의 요청 흔적이나 송신 의도 행)를 먼저 막고, 수신자 장부에 같은 리비전 이상으로
 * 붙어 보이면 풀며, 적격 읽기에서 보이지 않으면 의심으로 적어 운영자 단위 해제까지 그 기체로 가는 출발을 막는다. 특성 시험 두 건의 뒤집기와 짝 장면은
 * [HostReceiverTest] 에 있다.
 *
 * 단언은 RPC 수(수신자가 받은 `StartTask`)·벤더 접수 수·효과 수를 따로 본다. 효과는 기다린 뒤 센다(고정물은 접수를 먼저 세고 효과를 뒤에 적는다).
 */
class HostRestartHoldTest {

    private companion object {
        /** 그 인스턴스가 그 작업 지시에 적은 RESTORED 이벤트 수. */
        fun restoredBy(instanceId: String, jobOrderId: String): Int = PostgresSupport.queryOne(
            "SELECT count(*) FROM mission.execution_journal_event WHERE kind = 'RESTORED' AND instance_id = '$instanceId' AND job_order_id = '$jobOrderId'",
        ) { it.getInt(1) }

        /** 복원 이벤트(RESTORED·DEFERRED·GAVE_UP) 수. */
        fun restoreEvents(): Int = PostgresSupport.queryOne(
            "SELECT count(*) FROM mission.execution_journal_event WHERE kind IN ('RESTORED', 'DEFERRED', 'GAVE_UP')",
        ) { it.getInt(1) }

        /** 그 작업 지시의 송신 기록(작업 응답) 수. */
        fun responses(jobOrderId: String): Int = PostgresSupport.queryOne(
            "SELECT count(*) FROM mission.job_response_log WHERE job_order_id = '$jobOrderId'",
        ) { it.getInt(1) }

        /** 그 작업 지시의 SETTLED 이벤트 수. */
        fun settledEvents(jobOrderId: String): Int = PostgresSupport.queryOne(
            "SELECT count(*) FROM mission.execution_journal_event WHERE kind = 'SETTLED' AND job_order_id = '$jobOrderId'",
        ) { it.getInt(1) }
    }

    @Test
    fun `도는 중이던 접수된 단위도 수신자가 다시 떠 장부를 잃으면 재시작 의심으로 막히고 다른 기체는 운영자 확인 뒤 그대로 진행한다`() {
        World().use { w ->
            val execution = w.submit()
            val t1 = "JO-Q#T1"
            assertEquals(1, w.adapter.acceptsOf(t1))
            val first = w.takeover { w.receiver.restart() }
            val second = w.bench.leadership.instanceId
            w.await("재시작 의심") { w.reason() == WaitReason.RESTART_SUSPECT }
            val hold = w.awaitSuspicion()
            assertEquals(t1, hold["taskId"].asText())
            val rpcs = w.receiver.startRpcsOf(t1)
            assertEquals(emptyList(), evidence())

            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            // 독립 기체(humanoid-01, 대조하지 않는 mimic)는 발행 중 새 실행을 낸다.
            val accepted = w.bench.post("/host/job-orders", request(inspect("JO-H", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
            assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
            w.bench.driveAll { units(w.bench, executionOf(w.bench, "JO-H"))["T1.travel"] !in setOf("PENDING", null) }
            w.idle(4)
            assertEquals(rpcs, w.receiver.startRpcsOf(t1), "장부를 잃은 수신자에 같은 키를 다시 보냈다")
            assertEquals(1, w.adapter.acceptsOf(t1))
            assertEquals("SUSPECT", w.holds().single()["state"].asText())
            assertTrue(units(w.bench, execution).isNotEmpty())
        }
    }

    @Test
    fun `읽기 실패로 복원을 미룬 채 운영자 확인으로 발행에 든 뒤 수신자가 다시 떠도 미룬 복원의 보류와 판별 의무가 이어져 같은 키를 다시 보내지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            val rpcs = w.receiver.startRpcsOf(travel)
            w.receiver.failSnapshots = true
            val first = w.takeover()
            val second = w.bench.leadership.instanceId
            w.idle()
            assertEquals(WaitReason.UNREAD, w.reason())
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            // 읽기 실패로 복원을 미뤘다(DEFERRED) — 실행이 메모리에 없어도 그 기체를 다시 지어 대조할 때까지 막는다.
            assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED))
            assertTrue(w.bench.leadership.restartHolds.describe(QUADRUPED)!!.contains("복원을 미룬 작업 지시 JO-Q"))
            assertEquals(listOf("JO-Q"), w.bench.get("/host/leader")["restartDeferred"].map { it["jobOrderId"].asText() })
            w.idle()
            assertEquals(rpcs, w.receiver.startRpcsOf(travel))
            // 복원 실패로 선 실제 보류는 같은 기체의 다른 태스크 키 출발도 막는다(위임 호출 0).
            val delegated = java.util.concurrent.atomic.AtomicInteger()
            val counting = object : dev.picasso.middleware.RobotPort {
                override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
                    dev.picasso.contracts.v1.StartTaskResponse.getDefaultInstance().also { delegated.incrementAndGet() }
                override fun watch(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = emptyList<dev.picasso.contracts.v1.WatchTaskResponse>()
                override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = dev.picasso.contracts.v1.CancelTaskResponse.getDefaultInstance()
                override fun snapshot(robotId: String): dev.picasso.middleware.RobotSnapshot? = null
                override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
            }
            val gated = dev.picasso.ops.host.lease.GatedRobotPort(RoutedRobotPort(mapOf(QUADRUPED to counting)), w.bench.leadership)
            kotlin.test.assertFailsWith<dev.picasso.middleware.CommandNotSent> { gated.start(QUADRUPED, "JO-OTHER#T1.travel", 1, "navigate_to", emptyMap()) }
            kotlin.test.assertIs<dev.picasso.middleware.Issue.NotSent>(gated.issue(QUADRUPED, "JO-OTHER#T1.travel", 1, "navigate_to", emptyMap()))
            assertEquals(0, delegated.get(), "미룬 복원의 보류가 있는 기체에 다른 태스크 키 출발을 위임했다")

            // 늦은 복원 직전 송신 흔적을 못 읽으면 다시 짓지 않는다 — 미룬 채 막고, 다시 지은 뒤 후보 없이 복원을 마치는 일이 없다.
            PostgresSupport.execute("ALTER TABLE mission.host_issue_intent RENAME TO host_issue_intent_hidden")
            try {
                w.receiver.restart()
                w.receiver.failSnapshots = false
                w.idle(4)
                assertEquals(listOf("JO-Q"), w.bench.get("/host/leader")["restartDeferred"].map { it["jobOrderId"].asText() })
                assertEquals(0, restoredBy(second, "JO-Q"), "송신 흔적을 못 읽었는데 다시 지었다")
                assertEquals(rpcs, w.receiver.startRpcsOf(travel))
            } finally {
                PostgresSupport.execute("ALTER TABLE mission.host_issue_intent_hidden RENAME TO host_issue_intent")
            }
            w.awaitSuspicion()
            assertEquals(1, restoredBy(second, "JO-Q"))
            w.idle(4)
            assertEquals(rpcs, w.receiver.startRpcsOf(travel), "발행 중 판별 전에 같은 키를 다시 보냈다")
            w.idle()
            assertEquals(1, w.adapter.effectsOf(travel))
            assertEquals(1, w.adapter.acceptsOf(travel))
            assertEquals(LeaderState.ISSUING, w.bench.leadership.state)
        }
    }

    @Test
    fun `송신 의도는 커밋됐는데 판단 기록이 커밋되지 않은 채 죽고 수신자가 다시 떠도 의도 행으로 후보를 세워 막는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            // 판단 기록 쓰기를 막는다 — pump 뒤 기록 트랜잭션이 실패해 다음 출발의 요청 흔적이 기록에 남지 않는다.
            PostgresSupport.execute(
                "CREATE FUNCTION mission.mo27_block_record() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION '주입: 판단 기록 거부'; END; \$\$",
            )
            PostgresSupport.execute(
                "CREATE TRIGGER mo27_block_record BEFORE INSERT ON mission.execution_judgment_record FOR EACH STATEMENT EXECUTE FUNCTION mission.mo27_block_record()",
            )
            fun unblock() {
                PostgresSupport.execute("DROP TRIGGER IF EXISTS mo27_block_record ON mission.execution_judgment_record")
                PostgresSupport.execute("DROP FUNCTION IF EXISTS mission.mo27_block_record()")
            }
            try {
                w.finishT1()
                w.bench.driveAll { w.adapter.acceptsOf(travel) == 1 }
                w.idle()
                assertTrue(intents(travel) >= 1)
                val recorded = PostgresSupport.queryOne(
                    "SELECT record FROM mission.execution_judgment_record WHERE job_order_id = 'JO-Q' ORDER BY record_id DESC LIMIT 1",
                ) { it.getString(1) }
                val traced = dev.picasso.middleware.JudgmentRecordWire.decode(recorded).units.single { it.unitId == "T2.travel" }
                assertEquals(null, traced.requestedAt, "판단 기록이 막히지 않았다(시험 전제)")
            } catch (e: Throwable) {
                unblock()
                throw e
            }

            // 죽인 뒤(커넥션 풀을 먼저 닫아 그 보유자는 더 쓰지 못함)에야 판단 기록 쓰기를 다시 연다 — 그 사이 앞 보유자가 기록을 따라 적지 못하게.
            w.takeover {
                unblock()
                w.receiver.restart()
            }
            w.await("재시작 의심") { w.reason() == WaitReason.RESTART_SUSPECT }
            assertTrue(w.holds().any { it["taskId"].asText() == travel })
            val rpcs = w.receiver.startRpcsOf(travel)
            w.idle(4)
            assertEquals(rpcs, w.receiver.startRpcsOf(travel))
            assertEquals(1, w.adapter.acceptsOf(travel))
            assertEquals(emptyList(), evidence())
        }
    }

    @Test
    fun `의심을 적지 못한 채 보유자가 바뀌어도 다음 보유자가 같은 흔적에서 다시 세워 막고 적는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            PostgresSupport.execute(
                "CREATE FUNCTION mission.mo27_block_suspicion() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION '주입: 의심 쓰기 실패'; END; \$\$",
            )
            PostgresSupport.execute(
                "CREATE TRIGGER mo27_block_suspicion BEFORE INSERT ON mission.host_restart_suspicion FOR EACH STATEMENT EXECUTE FUNCTION mission.mo27_block_suspicion()",
            )
            w.takeover { w.dropStarts = false }
            w.await("재시작 의심(저장 대기)") { w.reason() == WaitReason.RESTART_SUSPECT }
            w.idle()
            assertTrue(w.holds().single()["suspicionId"].isNull)
            assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.host_restart_suspicion") { it.getInt(1) })
            assertEquals(0, w.receiver.startRpcsOf(travel))

            w.takeover {
                PostgresSupport.execute("DROP TRIGGER mo27_block_suspicion ON mission.host_restart_suspicion")
                PostgresSupport.execute("DROP FUNCTION mission.mo27_block_suspicion()")
            }
            val hold = w.awaitSuspicion()
            assertEquals(3L, hold["generation"].asLong())
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
            assertEquals(0, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `해제 뒤 재전송 전에 보유자가 바뀌면 다음 보유자는 해제를 지키고 해제 뒤 새 송신 의도가 생겼으면 새로 판정해 다시 막는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            w.takeover { w.dropStarts = false }
            val first = w.awaitSuspicion()
            // 읽기를 막아 해제 뒤에도 그 보유자가 열지 못하게(재전송 없음) 한 채 해제한다.
            w.receiver.failSnapshots = true
            assertEquals(200, w.release(first).status)
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
            val intentsAfterRelease = intents(travel)

            // 보유자 교체 — 해제가 덮는 흔적 그대로라 다시 막지 않는다. 읽기가 돌아오면 자동으로 열려 한 번 보낸다… 그 송신은 닿지 않게 한다.
            w.dropStarts = true
            w.takeover { w.receiver.failSnapshots = false }
            w.await("해제를 지켜 자동 열림") { w.readiness() is Readiness.Ready }
            assertTrue(w.holds().isEmpty(), w.holds().toString())
            w.bench.driveAll { intents(travel) > intentsAfterRelease }
            assertEquals(0, w.receiver.startRpcsOf(travel))

            // 해제 뒤 새 송신 의도(닿지 않은 재전송)가 생겼다 — 다음 보유자는 새로 판정해 새 의심으로 막는다.
            w.takeover { w.dropStarts = false }
            val renewed = w.awaitSuspicion()
            assertNotEquals(first["suspicionId"].asLong(), renewed["suspicionId"].asLong())
            assertTrue(renewed["basisIntentId"].asLong() > first["basisIntentId"].asLong())
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
            assertEquals(0, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `열린 의심을 읽지 못하면 재대조가 실패해 잃음으로 가고 출발을 내지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            val exits = w.bench.exits.size
            val events = restoreEvents()
            w.bench.killHost { PostgresSupport.execute("ALTER TABLE mission.host_restart_suspicion RENAME TO host_restart_suspicion_hidden") }
            try {
                w.await("적재 실패로 잃음", Duration.ofSeconds(30)) { w.bench.exits.size > exits }
                assertEquals(LeaderState.LOST, w.bench.leadership.state)
                assertFalse(w.bench.leadership.mayIssue())
                assertEquals(0, w.receiver.startRpcsOf(travel))
                // 적재는 복원보다 먼저다 — 복원 이벤트(RESTORED·DEFERRED·GAVE_UP)가 하나도 더 생기지 않았다.
                assertEquals(events, restoreEvents(), "적재 전에 복원했다")
            } finally {
                PostgresSupport.execute("ALTER TABLE mission.host_restart_suspicion_hidden RENAME TO host_restart_suspicion")
            }
        }
    }

    @Test
    fun `저장된 의심은 운영자 확인 뒤 정상 종료 인수에서도 리스 획득 때 올라와 그 기체로 출발을 내지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            val first = w.takeover { w.receiver.restart() }
            val second = w.bench.leadership.instanceId
            val hold = w.awaitSuspicion()
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val rpcs = w.receiver.startRpcsOf(travel)

            // 발행 중인 보유자를 정상 종료 — 다음 보유자는 곧바로 발행하고 신규 판별은 하지 않지만 저장된 의심을 올린다.
            w.bench.restartHost()
            w.bench.awaitLeader(LeaderState.ISSUING, Duration.ofSeconds(30))
            assertEquals(listOf(hold["suspicionId"].asLong()), w.holds().map { it["suspicionId"].asLong() })
            w.idle(4)
            assertEquals(rpcs, w.receiver.startRpcsOf(travel), "정상 종료 인수가 저장된 의심을 놓쳤다")
            assertEquals(1, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `붙어 보여 막지 않던 후보도 그 뒤 수신자가 다시 떠 적격 읽기에서 사라지면 의심으로 다시 막고 같은 키를 다시 보내지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val t1 = "JO-Q#T1"
            // 첫째가 도는 점검(T1)의 취소가 벤더에 닿았는지 모른다 — 수신자는 T1 을 장부에 든 채 DURABLE_PENDING 으로 답한다(래치되지 않음).
            w.adapter.cancelScript += ScriptedAdapter.Answer.IN_DOUBT
            val port = MissionHostApplication.clientPort(w.receiver.channel(), { 1L }, { _, _, _ -> })
            runCatching { port.cancel(QUADRUPED, dev.picasso.contracts.v1.TaskHandle.newBuilder().setTaskId(t1).setRevision(1).setRobotId(QUADRUPED).build()) }
            w.takeover()
            w.await("붙음(막지 않음)과 앞 세대 미확정") {
                w.reason() == WaitReason.DURABLE_PENDING && w.holds().any { it["taskId"].asText() == t1 && it["state"].asText() == "ATTACHED" }
            }
            assertFalse(w.bench.leadership.restartHolds.blocks(QUADRUPED))
            // 래치 전에 수신자가 다시 뜬다 — 장부 소실. 뒤의 적격 읽기에 T1 이 없으니 의심으로 다시 막는다.
            w.receiver.restart()
            w.await("재시작 의심") { w.reason() == WaitReason.RESTART_SUSPECT }
            val hold = w.awaitSuspicion()
            assertEquals(t1 to "ABSENT", hold["taskId"].asText() to hold["basisKind"].asText())
            w.idle(4)
            assertEquals(emptyList(), evidence())
            assertEquals(1, w.adapter.acceptsOf(t1), "장부를 잃은 수신자에 같은 키를 다시 보냈다")
            assertTrue(units(w.bench, execution).isNotEmpty())
        }
    }

    @Test
    fun `발행 중 벤더 호출에 걸린 앞 세대 접수로 의심(발행 중 미확정)이 섰다가 그 접수가 끝나 장부에 붙으면 시스템 해제로 닫고 한 번만 보낸 채 이어 간다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            val gate = java.util.concurrent.CountDownLatch(1)
            w.adapter.holdNext.set(gate)
            w.finishT1()
            w.bench.driveAll { w.adapter.held.get() == 1 }
            val first = w.takeover()
            val second = w.bench.leadership.instanceId
            w.await("앞 세대 명령 정리 미확정") { w.reason() == WaitReason.DURABLE_PENDING }
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val hold = w.awaitSuspicion()
            assertEquals("ISSUING_PENDING", hold["basisKind"].asText())
            val id = hold["suspicionId"].asLong()
            gate.countDown()
            w.await("붙음으로 시스템 해제") {
                PostgresSupport.queryOne("SELECT count(*) FROM mission.host_restart_release WHERE suspicion_id = $id AND released_by = 'system:attached'") { it.getInt(1) } == 1
            }
            w.bench.driveAll { units(w.bench, execution)["T2"] != "PENDING" }
            assertEquals(1, w.adapter.acceptsOf(travel))
            assertEquals(1, w.adapter.effectsOf(travel))
            assertTrue(w.holds().none { it["state"].asText() == "SUSPECT" })
        }
    }

    @Test
    fun `의심 둘 가운데 하나만 운영자가 해제하면 다른 의심이 DB 와 화면에 남고 그 기체의 출발은 계속 막힌다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            // 앞 보유자가 같은 기체에 남긴 열린 의심 하나(다른 작업 지시, 이미 정착).
            Fenced.execute(
                "INSERT INTO mission.host_restart_suspicion (generation, instance_id, robot_id, job_order_id, unit_id, attempt, task_id, basis_kind, basis_intent_id) " +
                    "VALUES (1, 'mw-old', '$QUADRUPED', 'JO-OLD', 'T1', 0, 'JO-OLD#T1', 'RESTORE_ABANDONED', 0)",
            )
            w.takeover { w.dropStarts = false }
            val mine = w.awaitSuspicion(travel)
            val old = w.holds().single { it["taskId"].asText() == "JO-OLD#T1" }
            assertEquals(200, w.release(mine).status)
            w.idle(4)
            assertEquals(listOf(old["suspicionId"].asLong()), w.holds().filter { it["state"].asText() == "SUSPECT" }.map { it["suspicionId"].asLong() })
            assertEquals(
                listOf(old["suspicionId"].asLong()),
                PostgresSupport.queryAll(
                    "SELECT s.suspicion_id FROM mission.host_restart_suspicion s LEFT JOIN mission.host_restart_release r USING (suspicion_id) WHERE r.release_id IS NULL",
                ) { it.getLong(1) },
            )
            assertEquals(WaitReason.RESTART_SUSPECT, w.reason())
            assertEquals(0, w.receiver.startRpcsOf(travel), "남은 의심이 있는데 그 기체로 출발했다")
        }
    }

    @Test
    fun `의심을 적지 못한 채 실행을 취소해 정착해도 정착이 커밋되지 않아 다음 보유자가 같은 흔적에서 다시 막는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            PostgresSupport.execute(
                "CREATE FUNCTION mission.mo27_block_suspicion() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION '주입: 의심 쓰기 실패'; END; \$\$",
            )
            PostgresSupport.execute(
                "CREATE TRIGGER mo27_block_suspicion BEFORE INSERT ON mission.host_restart_suspicion FOR EACH STATEMENT EXECUTE FUNCTION mission.mo27_block_suspicion()",
            )
            val first = w.takeover { w.dropStarts = false }
            val second = w.bench.leadership.instanceId
            w.await("재시작 의심(저장 대기)") { w.reason() == WaitReason.RESTART_SUSPECT }
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val restored = executionOf(w.bench, "JO-Q")
            val cancel = w.bench.post(
                "/host/executions/$restored/cancel", """{"approverId":"kim","reason":"재시작 의심 기체의 실행을 닫음","instanceId":"$second"}""",
            )
            assertEquals(200, cancel.status, cancel.body.toString())
            w.idle(6)
            assertEquals(0, settledEvents("JO-Q"), "의심을 적지 못했는데 정착이 커밋됐다")
            assertEquals(0, w.receiver.startRpcsOf(travel))

            w.takeover {
                PostgresSupport.execute("DROP TRIGGER mo27_block_suspicion ON mission.host_restart_suspicion")
                PostgresSupport.execute("DROP FUNCTION mission.mo27_block_suspicion()")
            }
            w.awaitSuspicion()
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
            assertEquals(0, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `관측 중 정상 종료한 보유자의 후임은 정상 종료 인수가 아니라 판별하고 낡은 해제 본문은 새 보유자에게 409 다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            w.receiver.failSnapshots = true
            w.takeover { w.dropStarts = false }
            assertEquals(LeaderState.HOLDING, w.bench.leadership.state)
            val holdingInstance = w.bench.leadership.instanceId
            // 관측 중인 보유자를 정상 종료 — 후임은 앞 세대가 발행을 열지 않았으므로 관측으로 뜨고 판별한다.
            w.bench.restartHost()
            w.bench.awaitLeader(LeaderState.HOLDING, Duration.ofSeconds(30))
            assertFalse(w.bench.leadership.acquired!!.graceful)
            w.receiver.failSnapshots = false
            val hold = w.awaitSuspicion()
            assertEquals(3L, hold["generation"].asLong())
            // 앞 보유자를 겨눈 해제 본문은 낡았다(409).
            assertEquals(409, w.release(hold, instanceId = holdingInstance, generation = 2).status)
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
        }
    }

    @Test
    fun `읽기 실패로 미룬 복원이 뒤에 포기로 끝나면 판정 없는 송신 흔적을 해제 가능한 의심으로 넘긴 뒤에야 미룬 복원의 보류를 걷는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            // 다음 보유자는 읽기 실패로 복원을 미룬다(DEFERRED).
            w.receiver.failSnapshots = true
            w.takeover { w.dropStarts = false }
            w.await("미룬 복원의 보류") { w.bench.get("/host/leader")["restartDeferred"].any { it["jobOrderId"].asText() == "JO-Q" } }
            // 가장 최근 판단 기록을 못 읽게 한 뒤 읽기를 돌린다 — 늦은 복원은 그 작업 지시를 포기(GAVE_UP, RECORD_UNREADABLE)한다.
            Fenced.execute(
                "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) " +
                    "VALUES ('JO-Q', 'mw-test', 'exec-x', '1', '{못 읽는 기록')",
            )
            w.receiver.failSnapshots = false
            w.await("포기한 실행의 의심") { w.holds().any { it["taskId"].asText() == travel && it["basisKind"]?.asText() == "RESTORE_ABANDONED" && !it["suspicionId"].isNull } }
            assertTrue(w.bench.get("/host/leader")["restartDeferred"].isEmpty)
            assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED))
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
        }
    }

    @Test
    fun `미룬 채 운영자가 종결하면 종결 행과 그 흔적의 의심이 함께 커밋돼 곧바로 보유자가 바뀌어도 다음 호스트가 막고 보내지 않는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            w.receiver.failSnapshots = true
            val first = w.takeover { w.dropStarts = false }
            val second = w.bench.leadership.instanceId
            w.await("미룬 복원의 보류") { w.bench.get("/host/leader")["restartDeferred"].any { it["jobOrderId"].asText() == "JO-Q" } }
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val closed = w.bench.post(
                "/host/job-orders/JO-Q/close",
                """{"approverId":"kim","reason":"현장 확인","requestId":"${java.util.UUID.randomUUID()}","executionInstanceId":"$first",""" +
                    """"executionId":"$execution","attestedStopped":true}""",
            )
            assertEquals("CLOSED", closed.body?.get("result")?.asText(), closed.body.toString())
            // 종결과 같은 트랜잭션 — 응답을 받은 때 이미 열린 의심이 DB 에 있다.
            assertEquals(
                listOf(travel),
                PostgresSupport.queryAll(
                    "SELECT s.task_id FROM mission.host_restart_suspicion s WHERE s.basis_kind = 'RESTORE_ABANDONED' " +
                        "AND NOT EXISTS (SELECT 1 FROM mission.host_restart_release r WHERE r.suspicion_id = s.suspicion_id)",
                ) { it.getString(1) },
            )
            assertTrue(w.bench.get("/host/leader")["restartDeferred"].isEmpty)
            // 곧바로 보유자 교체 — 다음 호스트는 종결한 행을 복원 목록에서 읽지 않지만 저장된 의심으로 막는다.
            w.takeover { w.receiver.failSnapshots = false }
            w.awaitSuspicion(travel)
            assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED))
            w.idle()
            assertEquals(0, w.receiver.startRpcsOf(travel))
        }
    }

    @Test
    fun `처음부터 포기한 실행의 송신 흔적을 읽지 못하면 그 기체를 막은 채 첫 pump 를 돌고 읽히면 의심으로 넘긴다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            Fenced.execute(
                "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) " +
                    "VALUES ('JO-Q', 'mw-test', 'exec-x', '1', '{못 읽는 기록')",
            )
            try {
                w.takeover {
                    w.dropStarts = false
                    PostgresSupport.execute("ALTER TABLE mission.host_issue_intent RENAME TO host_issue_intent_hidden")
                }
                w.idle(3)
                assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED), "포기 흔적을 못 읽었는데 기체를 열었다")
                assertTrue(w.bench.get("/host/leader")["restartDeferred"].any { it["jobOrderId"].asText() == "JO-Q" })
                assertEquals(0, w.receiver.startRpcsOf(travel))
                // 흔적을 못 읽으면 그 작업 지시의 단위가 어느 기체에 갔는지 모른다 — 일지 기체가 아닌 기체(humanoid-01)로 가는 출발도 위임하지 않는다.
                val delegated = java.util.concurrent.atomic.AtomicInteger()
                val counting = object : dev.picasso.middleware.RobotPort {
                    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>) =
                        dev.picasso.contracts.v1.StartTaskResponse.getDefaultInstance().also { delegated.incrementAndGet() }
                    override fun watch(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = emptyList<dev.picasso.contracts.v1.WatchTaskResponse>()
                    override fun cancel(robotId: String, handle: dev.picasso.contracts.v1.TaskHandle) = dev.picasso.contracts.v1.CancelTaskResponse.getDefaultInstance()
                    override fun snapshot(robotId: String): dev.picasso.middleware.RobotSnapshot? = null
                    override fun replay(robotId: String, from: Long): dev.picasso.middleware.Replay? = null
                }
                val gated = dev.picasso.ops.host.lease.GatedRobotPort(RoutedRobotPort(mapOf(HUMANOID to counting)), w.bench.leadership)
                // 관측 중이라 humanoid-01 은 리더 확인으로도 막힌다 — 보류 자체를 본다(리더 상태와 무관한 관문).
                assertFalse(w.bench.leadership.mayStart(HUMANOID), "흔적을 못 읽은 동안 다른 기체의 출발 보류를 풀었다")
                assertIs<dev.picasso.middleware.Issue.NotSent>(gated.issue(HUMANOID, "JO-H#T1.travel", 1, "navigate_to", emptyMap()))
                assertEquals(0, delegated.get(), "흔적을 못 읽은 동안 다른 기체로 출발을 위임했다")
            } finally {
                PostgresSupport.execute("ALTER TABLE IF EXISTS mission.host_issue_intent_hidden RENAME TO host_issue_intent")
            }
            w.await("포기한 실행의 의심") {
                w.holds().any { it["taskId"].asText() == travel && it["basisKind"]?.asText() == "RESTORE_ABANDONED" && !it["suspicionId"].isNull }
            }
            assertTrue(w.bench.get("/host/leader")["restartDeferred"].isEmpty)
        }
    }

    @Test
    fun `시스템 해제 쓰기가 실패해도 다른 실행의 기록은 롤백되지 않고 다시 쓸 때까지 그 기체를 막는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            val gate = java.util.concurrent.CountDownLatch(1)
            w.adapter.holdNext.set(gate)
            w.finishT1()
            w.bench.driveAll { w.adapter.held.get() == 1 }
            val first = w.takeover()
            val second = w.bench.leadership.instanceId
            w.await("앞 세대 명령 정리 미확정") { w.reason() == WaitReason.DURABLE_PENDING }
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            val hold = w.awaitSuspicion(travel)
            PostgresSupport.execute(
                "CREATE FUNCTION mission.mo27_block_release() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION '주입: 해제 쓰기 실패'; END; \$\$",
            )
            PostgresSupport.execute(
                "CREATE TRIGGER mo27_block_release BEFORE INSERT ON mission.host_restart_release FOR EACH STATEMENT EXECUTE FUNCTION mission.mo27_block_release()",
            )
            try {
                gate.countDown()
                // 다른 기체(humanoid-01)의 새 실행은 그동안에도 응답 기록이 커밋된다.
                val accepted = w.bench.post("/host/job-orders", request(inspect("JO-H", "T1" to "bay-7"), "candidates", HUMANOID)).body!!
                assertEquals("ACCEPTED", accepted["result"].asText(), accepted.toString())
                w.bench.driveAll { responses("JO-H") > 0 && units(w.bench, executionOf(w.bench, "JO-H"))["T1.travel"] !in setOf("PENDING", null) }
                assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.host_restart_release WHERE suspicion_id = ${hold["suspicionId"].asLong()}") { it.getInt(1) })
                assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED), "시스템 해제를 적지 못했는데 기체를 열었다")
                assertEquals(1, w.adapter.acceptsOf(travel))
            } finally {
                PostgresSupport.execute("DROP TRIGGER mo27_block_release ON mission.host_restart_release")
                PostgresSupport.execute("DROP FUNCTION mission.mo27_block_release()")
            }
            w.await("시스템 해제") {
                PostgresSupport.queryOne("SELECT count(*) FROM mission.host_restart_release WHERE suspicion_id = ${hold["suspicionId"].asLong()} AND released_by = 'system:attached'") { it.getInt(1) } == 1
            }
            w.bench.driveAll { units(w.bench, execution)["T2"] != "PENDING" }
            assertEquals(1, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `잃음으로 간 옛 보유자에게 보낸 해제는 그 호스트가 지금 리스가 아니라 409 다`() {
        World().use { w ->
            w.submit()
            val old = w.bench.leadership
            val standby = w.bench.startHost()
            val next = w.bench.leadershipOf(standby)
            try {
                old.stall.close()
                w.bench.awaitLeader(LeaderState.HOLDING, HostEpochTakeoverTest.TIMING.expiry.plusSeconds(10), next)
                old.lost("시험: 옛 보유자를 잃음으로 보냄")
                assertEquals(LeaderState.LOST, old.state)
                val reply = w.bench.post(
                    "/host/restart-holds/release",
                    """{"suspicionId":999,"robotId":"$QUADRUPED","taskId":"JO-Q#T1","instanceId":"${old.instanceId}","generation":1,""" +
                        """"releasedBy":"kim","reason":"낡은 화면에서 보냄"}""",
                )
                assertEquals(409, reply.status, reply.body.toString())
                assertEquals("TAKEOVER_TARGET_CHANGED", reply.body?.get("error")?.asText())
            } finally {
                old.stall.open()
                standby.close()
            }
        }
    }

    @Test
    fun `포기한 실행을 종결할 때 읽은 스냅숏으로 시스템 해제된 의심도 그 뒤 출발·판정 없이 수신자 재시작과 인수가 나면 다음 호스트가 다시 대조할 후보로 막는다`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.dropStarts = true
            w.finishT1()
            w.bench.driveAll { units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            // 다음 보유자는 그 작업 지시를 포기한다(판단 기록을 못 읽음) — 판정 없는 송신 흔적(T1.travel·T1·T2.travel)이 의심이 된다.
            Fenced.execute(
                "INSERT INTO mission.execution_judgment_record (job_order_id, instance_id, execution_id, wire_version, record) " +
                    "VALUES ('JO-Q', 'mw-test', 'exec-x', '1', '{못 읽는 기록')",
            )
            val first = w.takeover { w.dropStarts = false }
            val second = w.bench.leadership.instanceId
            w.await("포기한 실행의 의심") { w.holds().count { it["basisKind"]?.asText() == "RESTORE_ABANDONED" && !it["suspicionId"].isNull } >= 3 }
            awaitHeartbeatExpired(first)
            assertEquals(200, confirm(w.bench, second, 2, previous = first, unresolved = listOf(first)).status)
            // 운영자 종결 — 종결이 읽은 스냅숏에 끝난 T1·T1.travel 이 붙어 보여 시스템 해제된다. 장부에 없는 T2.travel 은 운영자가 따로 해제한다.
            val closed = w.bench.post(
                "/host/job-orders/JO-Q/close",
                """{"approverId":"kim","reason":"현장 확인","requestId":"${java.util.UUID.randomUUID()}","executionInstanceId":"$first",""" +
                    """"executionId":"$execution","attestedStopped":false}""",
            )
            assertEquals("CLOSED", closed.body?.get("result")?.asText(), closed.body.toString())
            w.await("종결 스냅숏의 붙음으로 시스템 해제") {
                PostgresSupport.queryOne("SELECT count(*) FROM mission.host_restart_release WHERE released_by = 'system:attached' AND job_order_id = 'JO-Q'") { it.getInt(1) } >= 2
            }
            assertEquals(200, w.release(w.awaitSuspicion(travel)).status)
            assertTrue(w.holds().none { it["state"].asText() != "ATTACHED" }, w.holds().toString())
            // 그 뒤 출발·판정 없이 수신자가 다시 떠 장부를 잃고 만료 인수가 난다 — 다음 호스트는 시스템 해제된 태스크를 다시 대조할 후보로 막는다.
            w.takeover { w.receiver.restart() }
            assertTrue(w.bench.leadership.restartHolds.blocks(QUADRUPED), "시스템 해제된 의심을 다음 호스트가 잃었다")
            assertTrue(w.holds().any { it["taskId"].asText() == "JO-Q#T1" && it["state"].asText() == "CANDIDATE" }, w.holds().toString())
        }
    }

    @Test
    fun `알려진 한계 — 정상 종료 인수는 신규 판별을 하지 않아 수신자가 다시 떠 장부를 잃으면 같은 키가 벤더에 다시 닿는다 · 다음 과제`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            w.bench.restartHost { w.receiver.restart() }
            w.bench.awaitLeader(LeaderState.ISSUING, Duration.ofSeconds(30))
            assertTrue(w.holds().isEmpty())
            w.bench.driveAll { w.adapter.effectsOf(travel) == 2 }
            assertEquals(2, w.adapter.acceptsOf(travel))
        }
    }

    @Test
    fun `알려진 한계 — 같은 세대가 낸 출발이 결과 모름인 채 수신자가 다시 뜨면 미확정 조회의 같은 키가 벤더에 다시 닿는다 · 다음 과제`() {
        World().use { w ->
            val execution = w.submit()
            val travel = "JO-Q#T2.travel"
            w.adapter.startScript += ScriptedAdapter.Answer.IN_DOUBT
            w.finishT1()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 1 && units(w.bench, execution)["T2.travel"] == "IN_DOUBT" }
            assertEquals(LeaderState.ISSUING, w.bench.leadership.state)
            w.receiver.restart()
            w.bench.driveAll { w.adapter.effectsOf(travel) == 2 }
            assertEquals(2, w.adapter.acceptsOf(travel))
            assertTrue(w.holds().isEmpty())
        }
    }
}
