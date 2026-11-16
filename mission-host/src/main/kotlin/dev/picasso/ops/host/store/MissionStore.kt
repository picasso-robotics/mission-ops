package dev.picasso.ops.host.store

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** 초안 한 행(S3b 스펙 §6.1). [definition] 은 받은 글자 그대로이고 읽을 수 없는 문서일 수 있다. */
data class DraftRow(
    val draftId: Long,
    val workMasterId: String,
    val definition: String,
    val savedBy: String,
    val requestId: UUID,
    val savedAt: Instant,
    val sampleOrder:String?=null,
)

/** 모의 실행 한 행. [result] 는 JSON 글자다(모양은 S3b JSON 계약 §4.5). */
data class MockRunRow(
    val mockRunId: Long,
    val draftId: Long,
    val passed: Boolean,
    val result: String,
    val requestId: UUID,
    val startedAt: Instant,
    val finishedAt: Instant,
)

/** 임무 버전 한 행. 키는 ([workMasterId], [version]) 이다. */
data class VersionRow(
    val workMasterId: String,
    val version: Int,
    val draftId: Long,
    val definition: String,
    val activatedBy: String,
    val reason: String,
    val requestId: UUID,
    val activatedAt: Instant,
)

/** 요청 id 하나로 남은 행(T9). 셋 다 없으면 그 요청은 반영되지 않았다. */
data class RequestRows(val draft: DraftRow?, val mockRun: MockRunRow?, val version: VersionRow?) {
    val found: Boolean get() = draft != null || mockRun != null || version != null
}

/**
 * `mission` 스키마의 표 셋(S3b 스펙 §6.1). 덧붙이기와 읽기만 있다. 고치기·지우기는 스키마의 트리거가 막는다.
 *
 * 문장 하나가 한 트랜잭션이다(쓰기는 리스를 확인하는 트랜잭션). 호출은 짧고 연결을 쥔 채 호스트 잠금을 기다리지 않는다(T2). 활성화는 호스트 잠금 아래에서
 * 이것을 부르므로 잠금 순서는 «호스트 잠금 → DB» 한 방향뿐이다.
 */
class MissionStore(private val jdbc: JdbcClient, guard: AuthorityGuard) {

    /** 쓰기 문장은 리스 가드를 지난다(V19, [AuthorityGuard]). 리스를 쥔 리더가 아니면 적지 않는다. */
    private val writes: JdbcClient = guard.writes

    /** DB 의 지금 시각. 모의 실행의 시작 시각을 DB 시계로 적으려고 쓴다. */
    fun now(): Instant = jdbc.sql("SELECT clock_timestamp()").query { rs, _ -> instant(rs, 1) }.single()

    /** 그 요청 id 가 표 셋 중 어디에든 이미 있는가. 요청 id 하나는 운영 서비스의 조작 하나다. */
    fun requestIdUsed(requestId: UUID): Boolean = byRequest(requestId).found

    fun saveDraft(workMasterId: String, definition: String, savedBy: String, requestId: UUID,sampleOrder:String?=null): DraftRow =
        writes.sql(
            """
            INSERT INTO mission.draft (work_master_id, definition, saved_by, request_id,sample_order)
            VALUES (:workMaster, :definition, :savedBy, :requestId,CAST(:sample AS JSONB))
            RETURNING $DRAFT_COLUMNS
            """.trimIndent(),
        )
            .param("workMaster", workMasterId)
            .param("definition", definition)
            .param("savedBy", savedBy)
            .param("sample",sampleOrder,java.sql.Types.VARCHAR)
            .param("requestId", requestId)
            .query { rs, _ -> draft(rs) }
            .single()

    fun draft(draftId: Long): DraftRow? =
        jdbc.sql("SELECT $DRAFT_COLUMNS FROM mission.draft WHERE draft_id = :id")
            .param("id", draftId)
            .query { rs, _ -> draft(rs) }
            .optional()
            .orElse(null)

    /** 그 WorkMaster 의 최근 초안부터 [limit] 개. */
    fun drafts(workMasterId: String, limit: Int): List<DraftRow> =
        jdbc.sql(
            "SELECT $DRAFT_COLUMNS FROM mission.draft WHERE work_master_id = :workMaster ORDER BY draft_id DESC LIMIT :limit",
        )
            .param("workMaster", workMasterId)
            .param("limit", limit)
            .query { rs, _ -> draft(rs) }
            .list()

    /** 모의 실행 한 건을 남긴다. [startedAt] 은 [now] 로 잰 DB 시각이고 끝 시각은 넣는 순간의 DB 시각이다. */
    fun saveMockRun(draftId: Long, passed: Boolean, resultJson: String, requestId: UUID, startedAt: Instant): MockRunRow =
        writes.sql(
            """
            INSERT INTO mission.mock_run (draft_id, passed, result, request_id, started_at)
            VALUES (:draftId, :passed, CAST(:result AS jsonb), :requestId, :startedAt)
            RETURNING $MOCK_RUN_COLUMNS
            """.trimIndent(),
        )
            .param("draftId", draftId)
            .param("passed", passed)
            .param("result", resultJson)
            .param("requestId", requestId)
            .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
            .query { rs, _ -> mockRun(rs) }
            .single()

    /** 그 초안의 마지막 모의 실행. 활성화 게이트가 이것 하나만 본다(S3b 스펙 §1). */
    fun lastMockRun(draftId: Long): MockRunRow? =
        jdbc.sql("SELECT $MOCK_RUN_COLUMNS FROM mission.mock_run WHERE draft_id = :draftId ORDER BY mock_run_id DESC LIMIT 1")
            .param("draftId", draftId)
            .query { rs, _ -> mockRun(rs) }
            .optional()
            .orElse(null)

    /**
     * 버전 행을 넣는다. 번호는 그 WorkMaster 의 가장 큰 번호 + 1 이다(없으면 1). 번호를 정하는 것과 넣는 것이 한 문장이고
     * 활성화는 호스트 잠금 아래에서만 부르므로 같은 번호를 두 번 내지 않는다. 그래도 겹치면 기본 키가 막는다.
     */
    fun insertVersion(
        workMasterId: String,
        draftId: Long,
        definition: String,
        activatedBy: String,
        reason: String,
        requestId: UUID,
    ): VersionRow =
        writes.sql(
            """
            INSERT INTO mission.mission_version (work_master_id, version, draft_id, definition, activated_by, reason, request_id)
            SELECT :workMaster, COALESCE(MAX(version), 0) + 1, :draftId, :definition, :activatedBy, :reason, :requestId
            FROM mission.mission_version WHERE work_master_id = :workMaster
            RETURNING $VERSION_COLUMNS
            """.trimIndent(),
        )
            .param("workMaster", workMasterId)
            .param("draftId", draftId)
            .param("definition", definition)
            .param("activatedBy", activatedBy)
            .param("reason", reason)
            .param("requestId", requestId)
            .query { rs, _ -> version(rs) }
            .single()

    /** 그 WorkMaster 의 버전 이력. 높은 번호부터. */
    fun versions(workMasterId: String): List<VersionRow> =
        jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE work_master_id = :workMaster ORDER BY version DESC")
            .param("workMaster", workMasterId)
            .query { rs, _ -> version(rs) }
            .list()

    /** 버전 하나. 없으면 `null` 이다. 기동 복원이 일지의 임무 버전을 이것으로 읽는다(S4b 스펙 T3). */
    fun version(workMasterId: String, version: Int): VersionRow? =
        jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE work_master_id = :workMaster AND version = :version")
            .param("workMaster", workMasterId)
            .param("version", version)
            .query { rs, _ -> version(rs) }
            .optional()
            .orElse(null)

    /** WorkMaster 마다 가장 높은 버전 하나. 기동 때 카탈로그를 이것으로 세운다(T1). */
    fun activeVersions(): List<VersionRow> =
        jdbc.sql(
            """
            SELECT DISTINCT ON (work_master_id) $VERSION_COLUMNS
            FROM mission.mission_version
            ORDER BY work_master_id, version DESC
            """.trimIndent(),
        )
            .query { rs, _ -> version(rs) }
            .list()

    fun byRequest(requestId: UUID): RequestRows = RequestRows(
        draft = jdbc.sql("SELECT $DRAFT_COLUMNS FROM mission.draft WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> draft(rs) }.optional().orElse(null),
        mockRun = jdbc.sql("SELECT $MOCK_RUN_COLUMNS FROM mission.mock_run WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> mockRun(rs) }.optional().orElse(null),
        version = jdbc.sql("SELECT $VERSION_COLUMNS FROM mission.mission_version WHERE request_id = :requestId")
            .param("requestId", requestId).query { rs, _ -> version(rs) }.optional().orElse(null),
    )

    private fun draft(rs: ResultSet) = DraftRow(
        draftId = rs.getLong("draft_id"),
        workMasterId = rs.getString("work_master_id"),
        definition = rs.getString("definition"),
        savedBy = rs.getString("saved_by"),
        requestId = rs.getObject("request_id", UUID::class.java),
        savedAt = instant(rs, "saved_at"),
        sampleOrder=rs.getString("sample_order"),
    )

    private fun mockRun(rs: ResultSet) = MockRunRow(
        mockRunId = rs.getLong("mock_run_id"),
        draftId = rs.getLong("draft_id"),
        passed = rs.getBoolean("passed"),
        result = rs.getString("result"),
        requestId = rs.getObject("request_id", UUID::class.java),
        startedAt = instant(rs, "started_at"),
        finishedAt = instant(rs, "finished_at"),
    )

    private fun version(rs: ResultSet) = VersionRow(
        workMasterId = rs.getString("work_master_id"),
        version = rs.getInt("version"),
        draftId = rs.getLong("draft_id"),
        definition = rs.getString("definition"),
        activatedBy = rs.getString("activated_by"),
        reason = rs.getString("reason"),
        requestId = rs.getObject("request_id", UUID::class.java),
        activatedAt = instant(rs, "activated_at"),
    )

    private fun instant(rs: ResultSet, column: String): Instant = rs.getObject(column, OffsetDateTime::class.java).toInstant()

    private fun instant(rs: ResultSet, index: Int): Instant = rs.getObject(index, OffsetDateTime::class.java).toInstant()

    private companion object {
        const val DRAFT_COLUMNS = "draft_id, work_master_id, definition, saved_by, request_id, saved_at, sample_order"
        const val MOCK_RUN_COLUMNS = "mock_run_id, draft_id, passed, result, request_id, started_at, finished_at"
        const val VERSION_COLUMNS = "work_master_id, version, draft_id, definition, activated_by, reason, request_id, activated_at"
    }
}
