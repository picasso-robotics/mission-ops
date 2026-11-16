-- 실행 호스트 리스와 DB 쓰기 펜싱(HA 설계 기록 §4·§6·§7, 능동-대기 1단계 기반).
--
-- 리스는 행 둘로 나눈다. 역할 하나('host')에 각 한 행.
-- - host_lease(보유자 행): holder 는 리스를 쥔 인스턴스 id(일지의 instance_id 와 같은 값), generation 은 획득마다 1씩 오르는 세대. 획득만 고친다.
--   쓰기 펜싱(withAuthorityTransaction)은 이 행을 FOR SHARE 로 잠근다.
-- - host_lease_term(기간 행): expires_at 은 DB 시각 만료, issuing 은 «이 세대가 발행을 열었다(ISSUING 에 들었다)». 갱신·발행 표시·내놓기가 고친다.
--   쓰기 펜싱은 이 행을 읽기만 하므로 쓰기 트랜잭션이 겹쳐 이어져도 갱신이 공유 잠금 뒤에서 굶지 않는다.
-- 획득은 보유자 행 → 기간 행 순서로 FOR UPDATE 하고(쓰기 트랜잭션이 끝나기를 기다림), 만료했을 때만 보유자·세대를 바꾸고 기간을 새로 둔다(같은
-- 보유자의 재획득 없음). 갱신·발행 표시·내놓기는 보유자 행 FOR SHARE(보유자·세대 확인) → 기간 행 UPDATE(미만료 조건) 순서라 획득과 잠금 순서가
-- 같다. 시각은 모두 clock_timestamp() 다. 처음 행은 보유자 없음·세대 0·과거 만료라 첫 획득이 곧바로 된다.
CREATE TABLE host_lease (
    role       TEXT        PRIMARY KEY CHECK (role = 'host'),
    holder     TEXT        CHECK (holder <> ''),
    generation BIGINT      NOT NULL DEFAULT 0 CHECK (generation >= 0),
    CHECK ((holder IS NULL) = (generation = 0))
);

CREATE TABLE host_lease_term (
    role       TEXT        PRIMARY KEY CHECK (role = 'host'),
    expires_at TIMESTAMPTZ NOT NULL,
    issuing    BOOLEAN     NOT NULL DEFAULT false
);

INSERT INTO host_lease (role, holder, generation) VALUES ('host', NULL, 0);
INSERT INTO host_lease_term (role, expires_at, issuing) VALUES ('host', TIMESTAMPTZ 'epoch', false);

-- 리스를 잃은 인스턴스의 자기 차단 기록(설계 기록 §7 의 FENCED). 잃은 순간 송신·pump 를 멈추고 이 칸을 적은 뒤 프로세스를 끝낸다.
ALTER TABLE host_instance ADD COLUMN fenced_at TIMESTAMPTZ, ADD COLUMN fenced_reason TEXT;

-- 만료 인수 뒤 운영자 확인(«옛 프로세스 종료 확인»). 확인한 세대에 한 행이고 그 세대의 보유자만 적는다. 확인 뒤에야 신규 발행을 연다.
-- 덧붙이기만 한다(V2 의 mission_record_append_only()).
CREATE TABLE host_takeover_confirmation (
    generation   BIGINT      PRIMARY KEY CHECK (generation >= 1),
    instance_id  TEXT        NOT NULL CHECK (instance_id <> ''),
    previous_holder TEXT,
    confirmed_by TEXT        NOT NULL CHECK (confirmed_by <> ''),
    reason       TEXT        NOT NULL CHECK (reason <> ''),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TRIGGER host_takeover_confirmation_no_update_delete
    BEFORE UPDATE OR DELETE ON host_takeover_confirmation
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_takeover_confirmation_no_truncate
    BEFORE TRUNCATE ON host_takeover_confirmation
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

-- 쓰기 펜싱. 쓰기 쪽(withAuthorityTransaction)이 리스 행을 FOR SHARE 로 잠그고 보유자·세대·미만료를 확인한 뒤 트랜잭션 지역 표식
-- mission.fenced_generation 을 세운다. 이 트리거는 표식만 본다(표 조회 없음): 표식이 없거나 빈 값인 쓰기 문장은 거부한다. 표식은
-- set_config(..., true) 라 커밋·롤백과 함께 사라지고, 풀이 연결을 다시 내줘도 남지 않는다.
CREATE FUNCTION mission_require_fenced_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF coalesce(current_setting('mission.fenced_generation', true), '') = '' THEN
        RAISE EXCEPTION 'mission.% 쓰기에 리스 세대 표식이 없다: 리스를 확인하는 쓰기 경로(withAuthorityTransaction) 밖의 쓰기다', TG_TABLE_NAME
            USING ERRCODE = 'MHF01';
    END IF;
    RETURN NULL;
END;
$$;

-- mission 스키마의 쓰기 표 전부에 건다. 예외: 리스 표 둘, 심장박동·자기 차단 표(대기 인스턴스도 적는다), 마이그레이션 이력 표.
-- 뒤 마이그레이션이 새 표를 만들면 그 마이그레이션이 같은 트리거를 건다(빠지면 시험 HostLeaseTest 의 트리거 범위 시험이 실패한다).
-- TRUNCATE 는 보지 않는다(앱은 쓰지 않고, 덧붙이기 전용 표는 V2 의 트리거가 막는다). 뒤 마이그레이션이 mission 표의 데이터를 고치면 그
-- 마이그레이션 안에서 SET LOCAL mission.fenced_generation = 'migration' 처럼 표식을 세워야 한다.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = 'mission' AND tablename NOT IN ('host_lease', 'host_lease_term', 'host_instance', 'flyway_schema_history')
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE INSERT OR UPDATE OR DELETE ON mission.%I FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation()',
            t.tablename || '_fenced', t.tablename
        );
    END LOOP;
END;
$$;
