package dev.picasso.ops.host

import dev.picasso.ops.host.store.HostSchema
import dev.picasso.ops.host.store.MissionStore
import dev.picasso.registry.PostgresSupport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.SQLException
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `mission` 스키마의 표 셋(S3b 스펙 §6.1, T3). 호스트를 띄우지 않고 마이그레이션과 저장만 본다. */
class MissionStoreTest {

    private val dataSource = DriverManagerDataSource(PostgresSupport.jdbcUrl, PostgresSupport.username, PostgresSupport.password)
    private val store = MissionStore(JdbcClient.create(dataSource))

    @BeforeTest
    fun freshSchema() {
        PostgresSupport.execute("DROP SCHEMA IF EXISTS mission CASCADE")
        assertEquals(16, HostSchema.flyway(dataSource).migrate().migrationsExecuted)
    }

    private fun draft(workMaster: String = PSR) = store.saveDraft(workMaster, "{}", "lee", UUID.randomUUID())

    @Test
    fun `mission 마이그레이션은 mission 스키마에만 들어간다`() {
        val placed = PostgresSupport.queryAll(
            """
            SELECT table_name FROM information_schema.tables WHERE table_schema = 'mission' ORDER BY table_name
            """.trimIndent(),
        ) { it.getString(1) }
        assertEquals(
            listOf(
                "draft", "execution_cancel", "execution_close", "execution_close_confirmed", "execution_fault_release", "execution_journal", "execution_journal_event", "execution_journal_revision", "execution_judgment_record",
                "flyway_schema_history",
                "host_instance", "incident_copy", "incident_copy_resolution", "job_order_withdrawal", "job_response_log", "mission_version", "mock_run", "result_outbox",
                "result_producer", "robot_residual_hold", "robot_residual_hold_clearing", "robot_residual_hold_conflict", "robot_residual_hold_recovery",
            ),
            placed,
        )
        assertNull(PostgresSupport.queryOne("SELECT to_regclass('public.mission_version')::text") { it.getString(1) })
    }

    @Test
    fun `표 셋은 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        val draft = draft()
        store.saveMockRun(draft.draftId, true, """{"passed":true}""", UUID.randomUUID(), store.now())
        store.insertVersion(PSR, draft.draftId, "{}", "lee", "사유", UUID.randomUUID())
        listOf("draft" to "saved_by = 'x'", "mock_run" to "passed = false", "mission_version" to "reason = 'x'").forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만" in e.message!!, "$sql: ${e.message}")
            }
        }
        assertEquals(1, store.versions(PSR).size)
        assertEquals(1, store.drafts(PSR, 10).size)
    }

    @Test
    fun `S4b 표 다섯도 고칠 수도 지울 수도 통째로 비울 수도 없다`() {
        PostgresSupport.execute(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                "VALUES ('JO-1', 'r', '{}', 'InspectAsset', 'mw-1', 'exec-1')",
        )
        PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-1', 'SETTLED', 'mw-1', 'exec-1', 'FAILED')")
        PostgresSupport.execute(
            "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
                "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.incident_copy (instance_id, incident_id, execution_id, job_order_id, unit_id, detail) VALUES ('mw-1', 'incident-1', 'exec-1', 'JO-1', 'u', '{}')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.incident_copy_resolution (instance_id, incident_id, decision, decided_at, wall_clock_at, decided_by_id, decided_by_kind) " +
                "VALUES ('mw-1', 'incident-1', 'REWORK', now(), now(), 'kim', 'PERSON')",
        )
        listOf(
            "execution_journal" to "robot_id = 'x'", "execution_journal_event" to "detail = 'x'", "job_response_log" to "disposition = 'SENT'",
            "incident_copy" to "unit_id = 'x'", "incident_copy_resolution" to "decision = 'x'",
        ).forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("실행 일지·송신 기록·인시던트 사본은 덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        }
        // 같은 인스턴스·응답 id 는 두 번 적지 못한다. 일지 이벤트 종류는 다섯뿐이다(V10 이 REOPENED 를 더했다).
        assertFailsWith<SQLException> {
            PostgresSupport.execute(
                "INSERT INTO mission.job_response_log (instance_id, job_response_id, job_order_id, execution_id, version, physical_state, required_evidence, " +
                    "reached_evidence, completed_units, unverified_units, incomplete_units, in_doubt_units, operator_required, residual_hold, blocked_by, disposition) " +
                    "VALUES ('mw-1', 'resp-1', 'JO-1', 'exec-1', 1, 'FAILED', 'E0', 'E0', '[]', '[]', '[]', '[]', false, 'HOLD_KIND_EMPTY', '[]', 'SENT')",
            )
        }
        assertFailsWith<SQLException> {
            PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES ('JO-1', 'FORGOTTEN', 'mw-1', 'x')")
        }
    }

    @Test
    fun `V10 받은 개정판 표는 덧붙이기만 하고 판마다 한 행이며 다시 돎 이벤트는 실행 id 와 상세를 든다`() {
        PostgresSupport.execute(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                "VALUES ('JO-1', 'r', '{}', 'PrepareSequencedRack', 'mw-1', 'exec-1')",
        )
        val revision = "INSERT INTO mission.execution_journal_revision (job_order_id, version, job_order, instance_id, execution_id) " +
            "VALUES ('JO-1', 2, '{}', 'mw-1', 'exec-1')"
        PostgresSupport.execute(revision)
        PostgresSupport.execute("INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-1', 'REOPENED', 'mw-1', 'exec-1', 'RUNNING')")
        listOf("UPDATE mission.execution_journal_revision SET version = 3", "DELETE FROM mission.execution_journal_revision", "TRUNCATE mission.execution_journal_revision CASCADE")
            .forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        listOf(
            // 같은 판은 두 번 적지 못한다.
            revision,
            // 일지에 없는 작업 지시의 개정판은 없다.
            revision.replace("'JO-1'", "'JO-X'"),
            // 다시 돎은 실행 id 와 상세(물리 상태)를 든다.
            "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, detail) VALUES ('JO-1', 'REOPENED', 'mw-1', 'RUNNING')",
            "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id) VALUES ('JO-1', 'REOPENED', 'mw-1', 'exec-1')",
            // 미룸·포기는 여전히 실행 id 가 없다.
            "INSERT INTO mission.execution_journal_event (job_order_id, kind, instance_id, execution_id, detail) VALUES ('JO-1', 'GAVE_UP', 'mw-1', 'exec-1', 'x')",
        ).forEach { sql -> assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) } }
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_revision") { it.getInt(1) })
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM mission.execution_journal_event") { it.getInt(1) })
    }

    @Test
    fun `P0-b2 표 셋도 덧붙이기만 하고 승인자는 사람뿐이며 표시는 작업 지시마다 하나고 해소는 표시마다 하나다`() {
        PostgresSupport.execute(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                "VALUES ('JO-1', 'r', '{}', 'PrepareSequencedRack', 'mw-1', 'exec-1')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.execution_cancel (instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason) " +
                "VALUES ('mw-1', 'exec-1', 'JO-1', 'PARTIAL', 'kim', 'PERSON', '닫기')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id, object_ref) " +
                "VALUES ('r', 'JO-1', 'exec-1', 'mw-1', 'S01', 'C-1')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.robot_residual_hold_clearing (marker_id, approver_id, approver_kind, reason) " +
                "SELECT marker_id, 'park', 'PERSON', '빈손 확인' FROM mission.robot_residual_hold",
        )
        listOf(
            "execution_cancel" to "reason = 'x'", "robot_residual_hold" to "unit_id = 'x'", "robot_residual_hold_clearing" to "reason = 'x'",
        ).forEach { (table, set) ->
            listOf("UPDATE mission.$table SET $set", "DELETE FROM mission.$table", "TRUNCATE mission.$table CASCADE").forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        }
        listOf(
            // 실행 하나에 취소 하나, 에이전트 승인자 없음, 빈 사유 없음
            "INSERT INTO mission.execution_cancel (instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason) " +
                "VALUES ('mw-1', 'exec-1', 'JO-1', 'PARTIAL', 'kim', 'PERSON', '또')",
            "INSERT INTO mission.execution_cancel (instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason) " +
                "VALUES ('mw-1', 'exec-2', 'JO-1', 'PARTIAL', 'bot', 'AGENT', '닫기')",
            "INSERT INTO mission.execution_cancel (instance_id, execution_id, job_order_id, physical_state, approver_id, approver_kind, reason) " +
                "VALUES ('mw-1', 'exec-3', 'JO-1', 'PARTIAL', 'kim', 'PERSON', '  ')",
            // 작업 지시 하나에 표시 하나, 일지에 없는 작업 지시 없음
            "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) VALUES ('r', 'JO-1', 'exec-9', 'mw-2', 'S02')",
            "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) VALUES ('r', 'JO-X', 'exec-9', 'mw-2', 'S02')",
            // 표시 하나에 해소 하나
            "INSERT INTO mission.robot_residual_hold_clearing (marker_id, approver_id, approver_kind, reason) " +
                "SELECT marker_id, 'lee', 'PERSON', '또' FROM mission.robot_residual_hold",
        ).forEach { sql -> assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) } }
    }

    /**
     * V15: 충돌표는 HOLDING 관측만 적고 덧붙이기만 한다. 해소 행의 어긋남 인지는 지금 HOLDING 일 때만이고 늘 같은 표시의 충돌을 지목한다.
     * 관측을 적지 않은 것(NOT_RECORDED)은 호스트가 읽은 시각이 없는 옛 행뿐이다.
     */
    @Test
    fun `V15 충돌표와 해소 행의 관측 칸은 어긋남 인지와 지목을 DB 제약으로 묶는다`() {
        PostgresSupport.execute(
            "INSERT INTO mission.execution_journal (job_order_id, robot_id, job_order, work_master_id, instance_id, execution_id) " +
                "VALUES ('JO-1', 'r', '{}', 'PrepareSequencedRack', 'mw-1', 'exec-1'), ('JO-2', 'r', '{}', 'PrepareSequencedRack', 'mw-1', 'exec-2')",
        )
        PostgresSupport.execute(
            "INSERT INTO mission.robot_residual_hold (robot_id, job_order_id, execution_id, instance_id, unit_id) " +
                "VALUES ('r', 'JO-1', 'exec-1', 'mw-1', 'S01'), ('r', 'JO-2', 'exec-2', 'mw-1', 'S01')",
        )
        val (first, second) = PostgresSupport.queryAll("SELECT marker_id FROM mission.robot_residual_hold ORDER BY marker_id") { it.getLong(1) }
        val read = "host_read_started_at, host_read_received_at"
        val conflict = "11111111-1111-1111-1111-111111111111"
        PostgresSupport.execute(
            "INSERT INTO mission.robot_residual_hold_conflict (request_id, marker_id, approver_id, approver_kind, reason, observed_kind, observed_object_ref, $read) " +
                "VALUES ('$conflict', $first, 'park', 'PERSON', '빈손 확인', 'HOLDING', 'C-1', now(), now())",
        )
        listOf("UPDATE mission.robot_residual_hold_conflict SET reason = 'x'", "DELETE FROM mission.robot_residual_hold_conflict", "TRUNCATE mission.robot_residual_hold_conflict CASCADE")
            .forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        fun clearing(marker: Long, request: String, kind: String, ack: Boolean, cites: String?, readAt: Boolean = true) =
            "INSERT INTO mission.robot_residual_hold_clearing (marker_id, approver_id, approver_kind, reason, request_id, observed_kind, conflict_request_id, " +
                "acknowledged_mismatch${if (readAt) ", $read" else ""}) VALUES ($marker, 'park', 'PERSON', '빈손 확인', '$request', '$kind', " +
                "${cites?.let { "'$it'" } ?: "NULL"}, $ack${if (readAt) ", now(), now()" else ""})"
        listOf(
            // 충돌표는 HOLDING 만, 빈 물품 이름 없음
            "INSERT INTO mission.robot_residual_hold_conflict (request_id, marker_id, approver_id, approver_kind, reason, observed_kind, $read) " +
                "VALUES ('44444444-4444-4444-4444-444444444444', $first, 'park', 'PERSON', '빈손 확인', 'EMPTY', now(), now())",
            "INSERT INTO mission.robot_residual_hold_conflict (request_id, marker_id, approver_id, approver_kind, reason, observed_kind, observed_object_ref, $read) " +
                "VALUES ('55555555-5555-5555-5555-555555555555', $first, 'park', 'PERSON', '빈손 확인', 'HOLDING', '', now(), now())",
            // 어긋남 인지인데 EMPTY, HOLDING 인데 인지 없음, 지목 없는 인지
            clearing(first, "22222222-2222-2222-2222-222222222222", "EMPTY", true, conflict),
            clearing(first, "22222222-2222-2222-2222-222222222222", "HOLDING", false, conflict),
            clearing(first, "22222222-2222-2222-2222-222222222222", "HOLDING", true, null),
            // 다른 표시의 충돌 지목, 제 요청 id 지목, 없는 종류, 새 관측인데 읽은 시각 없음, 옛 행 표지인데 읽은 시각 있음
            clearing(second, "22222222-2222-2222-2222-222222222222", "HOLDING", true, conflict),
            clearing(first, conflict, "HOLDING", true, conflict),
            clearing(first, "22222222-2222-2222-2222-222222222222", "MAYBE", false, null),
            clearing(first, "22222222-2222-2222-2222-222222222222", "EMPTY", false, null, readAt = false),
            clearing(first, "22222222-2222-2222-2222-222222222222", "NOT_RECORDED", false, null),
        ).forEach { sql -> assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) } }
        assertEquals(0, PostgresSupport.queryOne("SELECT count(*) FROM mission.robot_residual_hold_clearing") { it.getInt(1) })

        // 지목한 충돌로 어긋남을 알고 해소하는 행과, 지목 뒤 손이 비어 인지 없이 해소하는 행은 받는다.
        PostgresSupport.execute(clearing(first, "22222222-2222-2222-2222-222222222222", "HOLDING", true, conflict))
        PostgresSupport.execute(clearing(second, "33333333-3333-3333-3333-333333333333", "EMPTY", false, null))
    }

    /** V16(B3): 철회는 작업 지시마다 하나, 요청 id 는 한 번, 철회한 쪽과 까닭은 비지 않고 덧붙이기만 한다(풀어 주는 길이 없다). */
    @Test
    fun `V16 철회표는 작업 지시와 요청 id 마다 하나이고 덧붙이기만 한다`() {
        fun withdraw(job: String, request: String, by: String = "ops/supersede", reason: String = "대체 접수") =
            "INSERT INTO mission.job_order_withdrawal (job_order_id, request_id, requested_by, reason) VALUES ('$job', '$request', '$by', '$reason')"
        PostgresSupport.execute(withdraw("JO-1", "11111111-1111-1111-1111-111111111111"))
        listOf(
            withdraw("JO-1", "22222222-2222-2222-2222-222222222222"),
            withdraw("JO-2", "11111111-1111-1111-1111-111111111111"),
            withdraw("JO-2", "33333333-3333-3333-3333-333333333333", by = ""),
            withdraw("JO-2", "33333333-3333-3333-3333-333333333333", reason = "  "),
        ).forEach { sql -> assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) } }
        listOf("UPDATE mission.job_order_withdrawal SET reason = 'x'", "DELETE FROM mission.job_order_withdrawal", "TRUNCATE mission.job_order_withdrawal")
            .forEach { sql ->
                val e = assertFailsWith<SQLException>(sql) { PostgresSupport.execute(sql) }
                assertTrue("덧붙이기만 한다" in e.message!!, "$sql: ${e.message}")
            }
        assertEquals(1, PostgresSupport.queryOne("SELECT count(*) FROM mission.job_order_withdrawal") { it.getInt(1) })
    }

    @Test
    fun `임무 버전 하나를 WorkMaster 와 번호로 읽고 없으면 null 이다`() {
        val draft = draft()
        store.insertVersion(PSR, draft.draftId, "v1", "lee", "r", UUID.randomUUID())
        store.insertVersion(PSR, draft.draftId, "v2", "lee", "r", UUID.randomUUID())
        assertEquals("v1", store.version(PSR, 1)!!.definition)
        assertEquals("v2", store.version(PSR, 2)!!.definition)
        assertNull(store.version(PSR, 3))
        assertNull(store.version("OtherWorkMaster", 1))
    }

    @Test
    fun `버전 번호는 WorkMaster 마다 가장 큰 번호 더하기 1 이고 활성 버전은 WorkMaster 마다 가장 큰 번호다`() {
        val a = draft()
        val b = draft("OtherWorkMaster")
        assertEquals(1, store.insertVersion(PSR, a.draftId, "v1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(2, store.insertVersion(PSR, a.draftId, "v2", "lee", "r", UUID.randomUUID()).version)
        assertEquals(1, store.insertVersion("OtherWorkMaster", b.draftId, "o1", "lee", "r", UUID.randomUUID()).version)
        assertEquals(3, store.insertVersion(PSR, a.draftId, "v3", "lee", "r", UUID.randomUUID()).version)

        assertEquals(listOf(3, 2, 1), store.versions(PSR).map { it.version })
        assertEquals(mapOf(PSR to "v3", "OtherWorkMaster" to "o1"), store.activeVersions().associate { it.workMasterId to it.definition })
    }

    @Test
    fun `마지막 모의 실행은 그 초안의 가장 늦은 행이고 요청 id 로 행을 다시 찾는다`() {
        val draft = draft()
        val other = draft()
        assertNull(store.lastMockRun(draft.draftId))
        val first = store.saveMockRun(draft.draftId, true, """{"n":1}""", UUID.randomUUID(), store.now())
        val requestId = UUID.randomUUID()
        val second = store.saveMockRun(draft.draftId, false, """{"n":2}""", requestId, store.now())
        store.saveMockRun(other.draftId, true, """{"n":3}""", UUID.randomUUID(), store.now())
        assertEquals(second.mockRunId, store.lastMockRun(draft.draftId)!!.mockRunId)
        assertTrue(second.mockRunId > first.mockRunId)
        assertEquals(false, store.lastMockRun(draft.draftId)!!.passed)

        val found = store.byRequest(requestId)
        assertEquals(second.mockRunId, found.mockRun!!.mockRunId)
        assertNull(found.draft)
        assertEquals(draft.draftId, store.byRequest(draft.requestId).draft!!.draftId)
        assertEquals(false, store.byRequest(UUID.randomUUID()).found)
    }

    private companion object {
        const val PSR = "PrepareSequencedRack"
    }
}
