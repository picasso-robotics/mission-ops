-- 두 대장의 사본(picasso §15.327, 한계 §15.166 의 소유자 = 담는 쪽). 영속은 보관·조회다 — picasso 로 되돌려 넣는 복원 입구는 없다.
-- 줄은 picasso `LedgerExport.incidentLine`·`remedySearchLine` 의 글자 그대로이고 담는 쪽이 조립하지 않는다. 줄은 판을 싣지 않으므로
-- `LedgerExport.LINE_SCHEMA_VERSION` 을 wire_version 칸에 곁에 적는다. 줄에는 인스턴스가 없으므로 instance_id 칸이 그것이다.
-- 모두 덧붙이기만 한다(V2 의 mission_record_append_only()).

-- 인시던트 사본의 첫 줄(개정 1). 이 칸이 생기기 전에 적은 행은 둘 다 NULL 이다. 둘은 함께 있거나 함께 없다.
ALTER TABLE incident_copy ADD COLUMN wire_version TEXT, ADD COLUMN line TEXT;
ALTER TABLE incident_copy ADD CONSTRAINT incident_copy_line_pair
    CHECK ((wire_version IS NULL) = (line IS NULL) AND (line IS NULL OR (line <> '' AND wire_version <> '')));

-- 인시던트 줄의 개정(2 부터). 검토·판단이 같은 인시던트 id 의 줄을 바꾸면 바뀐 줄을 새 개정으로 덧붙인다(고치지 않는다).
-- 그 인시던트의 지금 줄은 가장 큰 개정이고, 개정이 없으면 incident_copy.line 이다.
CREATE TABLE incident_copy_revision (
    revision_id  BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    instance_id  TEXT        NOT NULL,
    incident_id  TEXT        NOT NULL,
    revision     INT         NOT NULL CHECK (revision >= 2),
    wire_version TEXT        NOT NULL CHECK (wire_version <> ''),
    line         TEXT        NOT NULL CHECK (line <> ''),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, incident_id, revision),
    FOREIGN KEY (instance_id, incident_id) REFERENCES incident_copy (instance_id, incident_id)
);

-- 조치 탐색 사본. 탐색 기록은 적힌 뒤 바뀌지 않으므로 탐색마다 한 행이다. 실행 일지와 무관하다(거절은 실행이 없다) — 일지 FK 를 두지 않는다.
-- outcome 은 줄의 outcome 과 같아야 하고(줄에 outcome 키가 없으면 NULL 이라 받지 않는다), WITHHELD 줄에는 steps 키가 없어야 한다(가림이 조회 한 번으로 풀리지 않게, DB 도 막는다).
CREATE TABLE remedy_search_copy (
    copy_id      BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    search_id    TEXT        NOT NULL CHECK (search_id <> ''),
    job_order_id TEXT        NOT NULL,
    robot_id     TEXT        NOT NULL,
    outcome      TEXT        NOT NULL CHECK (outcome <> ''),
    wire_version TEXT        NOT NULL CHECK (wire_version <> ''),
    line         TEXT        NOT NULL CHECK (line <> ''),
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (instance_id, search_id),
    CONSTRAINT remedy_search_copy_outcome CHECK ((CAST(line AS jsonb) ->> 'outcome') IS NOT DISTINCT FROM outcome),
    CONSTRAINT remedy_search_copy_withheld_steps CHECK (outcome <> 'WITHHELD' OR NOT (CAST(line AS jsonb) ? 'steps'))
);

-- 같은 조치 승인 사실. 같은 조치의 승인 횟수의 정본은 이 표의 합산이다(인스턴스를 넘어). 서명은 (기체, 순서 있는 조치 유형 열)이고
-- 승인자 종류로 가르지 않는다. 승인을 적는 트랜잭션(실행 일지·판단 기록)에 함께 적는다. 열쇠는 승인 자체(작업 지시, 기체, 승인자, 두 시각)라
-- 재시도와 실행 복원(다시 지은 실행은 판단 기록의 같은 승인을 든다)이 두 번 세지 않는다.
CREATE TABLE remedy_approval (
    approval_id   BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instance_id   TEXT        NOT NULL CHECK (instance_id <> ''),
    execution_id  TEXT        NOT NULL CHECK (execution_id <> ''),
    job_order_id  TEXT        NOT NULL CHECK (job_order_id <> ''),
    robot_id      TEXT        NOT NULL CHECK (robot_id <> ''),
    remedy_types  JSONB       NOT NULL CHECK (jsonb_typeof(remedy_types) = 'array'),
    approver_id   TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind TEXT        NOT NULL CHECK (approver_kind IN ('PERSON', 'AGENT')),
    approved_at   TIMESTAMPTZ NOT NULL,
    wall_clock_at TIMESTAMPTZ NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (job_order_id, robot_id, approver_id, approved_at, wall_clock_at)
);

CREATE INDEX remedy_approval_signature ON remedy_approval (robot_id, remedy_types);

CREATE TRIGGER incident_copy_revision_no_update_delete
    BEFORE UPDATE OR DELETE ON incident_copy_revision
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER incident_copy_revision_no_truncate
    BEFORE TRUNCATE ON incident_copy_revision
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER remedy_search_copy_no_update_delete
    BEFORE UPDATE OR DELETE ON remedy_search_copy
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER remedy_search_copy_no_truncate
    BEFORE TRUNCATE ON remedy_search_copy
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER remedy_approval_no_update_delete
    BEFORE UPDATE OR DELETE ON remedy_approval
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER remedy_approval_no_truncate
    BEFORE TRUNCATE ON remedy_approval
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
