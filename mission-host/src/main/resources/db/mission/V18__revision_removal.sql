-- 리비전 제거 사건(picasso §15.329, 내보내기 버전 10). 리비전이 작업 지시에서 항목을 빼며 손대지 않은 단위(아직 내지 않은 PENDING, 태스크 없는
-- 계획 실패 FAILED)를 실행에서 지운 사건 하나가 한 행이다. picasso `Middleware.revisionRemovals()` 는 프로세스 수명이고 판단 기록은 이 사실을
-- 싣지 않으므로, 받은 리비전의 개정판 행·판단 기록과 같은 트랜잭션에 옮겨 적어 재기동을 넘어 «무엇이 어느 리비전에서 빠졌나» 를 남긴다.
-- 이 사건은 실패·취소·완료가 아니다 — 인시던트를 열지 않고 사람의 판단도 아니다.
-- 줄은 picasso `LedgerExport.revisionRemovalLine` 의 글자 그대로이고 담는 쪽이 조립하지 않는다. 줄은 판을 싣지 않으므로 `LINE_SCHEMA_VERSION` 을
-- wire_version 칸에 곁에 적는다. 줄에는 인스턴스가 없으므로 instance_id 칸이 그것이다. 한 사본은 (instance_id, execution_id, to_version)이 가리킨다
-- — 한 실행의 한 버전은 한 번만 받는다. 덧붙이기만 한다(V2 의 mission_record_append_only()).
CREATE TABLE revision_removal (
    removal_id    BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    instance_id   TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id  TEXT        NOT NULL CHECK (execution_id <> ''),
    job_order_id  TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    robot_id      TEXT        NOT NULL CHECK (robot_id <> ''),
    from_version  INT         NOT NULL CHECK (from_version >= 1),
    to_version    INT         NOT NULL,
    removed       JSONB       NOT NULL CHECK (jsonb_typeof(removed) = 'array' AND jsonb_array_length(removed) > 0),
    removed_at    TIMESTAMPTZ NOT NULL,
    wall_clock_at TIMESTAMPTZ NOT NULL,
    wire_version  TEXT        NOT NULL CHECK (wire_version <> ''),
    line          TEXT        NOT NULL CHECK (line <> ''),
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, execution_id, to_version),
    CHECK (to_version > from_version),
    -- 칸은 줄에서 읽은 것이다 — 줄과 칸이 어긋난 행은 받지 않는다.
    CONSTRAINT revision_removal_line_matches CHECK (
        (CAST(line AS jsonb) ->> 'executionId') IS NOT DISTINCT FROM execution_id
        AND (CAST(line AS jsonb) ->> 'jobOrderId') IS NOT DISTINCT FROM job_order_id
        AND (CAST(line AS jsonb) ->> 'toVersion') IS NOT DISTINCT FROM CAST(to_version AS TEXT)
        AND (CAST(line AS jsonb) ->> 'fromVersion') IS NOT DISTINCT FROM CAST(from_version AS TEXT)
        AND (CAST(line AS jsonb) -> 'removed') IS NOT DISTINCT FROM removed
    )
);

CREATE INDEX revision_removal_order ON revision_removal (job_order_id, removal_id);

CREATE TRIGGER revision_removal_no_update_delete
    BEFORE UPDATE OR DELETE ON revision_removal
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER revision_removal_no_truncate
    BEFORE TRUNCATE ON revision_removal
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
