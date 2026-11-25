-- 수신자 재시작 의심 보류(HA 후속 D1 완화, picasso 한계 대장 §15.330 의 실행 호스트 쪽 완화).
--
-- 실제 수신자(adapter-host)는 접수 장부와 결과 모르는 접수의 기록을 메모리에 둔다. 수신자를 다시 띄우면 둘 다 사라져 인수 확인이
-- DURABLE_CONFIRMED 로 답하고, 같은 태스크 키를 다시 보내면 벤더가 같은 일을 두 번 받는다. 만료 인수 뒤 새 보유자는 앞 세대가 보냈을 수 있는
-- 단위(후보)를 먼저 막고, 그 태스크가 스냅숏에 같은 리비전 이상으로 보이면 자동으로 풀며, 적격 읽기에서 보이지 않으면 «재시작 의심» 으로 적어
-- 운영자 단위 해제까지 그 기체로 가는 출발을 막는다. 세 표 모두 덧붙이기만 하고, 쓰기는 리스 가드를 지난다(아래 트리거).

-- 1) 송신 의도. 출발(StartTask — 첫 출발·개정 갱신·미확정 조회)을 실제로 보내기 직전 송신 스레드가 한 줄을 커밋하고, 커밋하지 못하면 보내지
--    않는다. 판단 기록이 커밋되기 전에 죽어도 «보냈을 수 있음» 이 남는다. 작업 지시·실행·단위는 그 출발을 낸 실행에서 받은 값이다(태스크 id 를
--    거꾸로 풀지 않는다). 커밋 뒤 권위를 다시 확인해 보내지 않은 출발에도 줄은 남는다(막는 쪽으로만 틀어진다). session_before 는 그 기체에서 송신 전에
--    마지막으로 본 응답 세션이다(기록만 하고 판정에는 쓰지 않는다 — 세션 동일성 예외는 후속 과제).
CREATE TABLE host_issue_intent (
    intent_id      BIGSERIAL   PRIMARY KEY,
    generation     BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id    TEXT        NOT NULL CHECK (instance_id <> ''),
    robot_id       TEXT        NOT NULL CHECK (robot_id <> ''),
    job_order_id   TEXT        NOT NULL CHECK (job_order_id <> ''),
    execution_id   TEXT,
    unit_id        TEXT        NOT NULL CHECK (unit_id <> ''),
    attempt        INTEGER     NOT NULL CHECK (attempt >= 0),
    task_id        TEXT        NOT NULL CHECK (task_id <> ''),
    revision       INTEGER     NOT NULL,
    session_before TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX host_issue_intent_task ON host_issue_intent (task_id, intent_id);
CREATE INDEX host_issue_intent_order ON host_issue_intent (job_order_id, intent_id);

-- 2) 재시작 의심. basis_kind 는 근거 종류다 — ABSENT(적격 읽기에서 수신자 장부에 없거나 낮은 리비전만 있음), REVISION_UNKNOWN(붙음을 댈 리비전이
--    없음 — 송신 의도 행이 없는 이 버전 전의 흔적), ISSUING_PENDING(운영자 확인 뒤 발행 중 수신자가 같은 세대 DURABLE_PENDING 으로 답함), DRIVE_ENDED
--    (실행의 구동이 끝나 판별을 이어 갈 읽기가 끊김), RESTORE_ABANDONED(복원하지 못한 실행 — 포기·운영자 종결 — 에 판정 없는 송신 흔적이 남음).
--    basis_intent_id 는 판정 때 그 태스크의 가장 큰 송신 의도 순번(없으면 0 — 이 표 전의 흔적), basis_record_id 는 후보를 세운 판단 기록 행이다(출처
--    기록, 해제 비교에는 쓰지 않는다). 같은 근거의 같은 태스크는 열린 줄이 하나다(보유자가 바뀌어 다시 판정해도 — 적는 쪽이 열린 줄을 먼저 찾음).
--    시스템 해제 뒤 다시 사라지면 새 줄이다. 새 의심은 정착·판단 기록과 한 트랜잭션에, 운영자 종결의 의심은 종결 행과 한 트랜잭션에 적는다(정착·종결만
--    커밋되고 의심을 잃는 일이 없게).
CREATE TABLE host_restart_suspicion (
    suspicion_id    BIGSERIAL   PRIMARY KEY,
    generation      BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id     TEXT        NOT NULL CHECK (instance_id <> ''),
    robot_id        TEXT        NOT NULL CHECK (robot_id <> ''),
    job_order_id    TEXT        NOT NULL CHECK (job_order_id <> ''),
    execution_id    TEXT,
    unit_id         TEXT        NOT NULL CHECK (unit_id <> ''),
    attempt         INTEGER     NOT NULL CHECK (attempt >= 0),
    task_id         TEXT        NOT NULL CHECK (task_id <> ''),
    revision        INTEGER,
    basis_kind      TEXT        NOT NULL CHECK (basis_kind IN ('ABSENT', 'REVISION_UNKNOWN', 'ISSUING_PENDING', 'DRIVE_ENDED', 'RESTORE_ABANDONED')),
    basis_intent_id BIGINT      NOT NULL CHECK (basis_intent_id >= 0),
    basis_record_id BIGINT,
    observed_floor  BIGINT,
    observed_status TEXT,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX host_restart_suspicion_task ON host_restart_suspicion (task_id, basis_intent_id);

-- 3) 해제. 의심 하나에 한 줄이고, 효력은 released_upto_intent(해제 때 그 태스크의 가장 큰 송신 의도 순번)까지의 송신에만 있다 — 그 뒤 새 의도
--    행이 생기면 다음 인수가 새로 판정한다. released_by 는 운영자 단위 해제면 사람, 의심 뒤 수신자 장부에 같은 리비전 이상 붙음을 보아 실행 호스트가
--    닫았으면 'system:attached' 다. 작업 지시·태스크는 그 의심의 값을 옮겨 적는다 — 판별이 해제 범위를 의심 표 없이 읽는다. 시스템 해제는 해제 범위에
--    들지 않는다(다음 판별이 그 태스크를 다시 대조한다).
CREATE TABLE host_restart_release (
    release_id           BIGSERIAL   PRIMARY KEY,
    suspicion_id         BIGINT      NOT NULL UNIQUE REFERENCES host_restart_suspicion (suspicion_id),
    job_order_id         TEXT        NOT NULL CHECK (job_order_id <> ''),
    task_id              TEXT        NOT NULL CHECK (task_id <> ''),
    released_upto_intent BIGINT      NOT NULL CHECK (released_upto_intent >= 0),
    generation           BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id          TEXT        NOT NULL CHECK (instance_id <> ''),
    released_by          TEXT        NOT NULL CHECK (released_by <> ''),
    reason               TEXT        NOT NULL CHECK (reason <> ''),
    recorded_at          TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TRIGGER host_issue_intent_no_update_delete
    BEFORE UPDATE OR DELETE ON host_issue_intent
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_issue_intent_no_truncate
    BEFORE TRUNCATE ON host_issue_intent
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_issue_intent_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_issue_intent
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();

CREATE TRIGGER host_restart_suspicion_no_update_delete
    BEFORE UPDATE OR DELETE ON host_restart_suspicion
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_restart_suspicion_no_truncate
    BEFORE TRUNCATE ON host_restart_suspicion
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_restart_suspicion_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_restart_suspicion
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();

CREATE TRIGGER host_restart_release_no_update_delete
    BEFORE UPDATE OR DELETE ON host_restart_release
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_restart_release_no_truncate
    BEFORE TRUNCATE ON host_restart_release
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_restart_release_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_restart_release
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();
