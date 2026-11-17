-- 수신 측 제어자 세대 대조 소비(HA 3단계, picasso ADR 54·한계 §15.330).
--
-- 1) 세대별 보유자 이력. 획득 트랜잭션 안에서 한 줄을 넣고(리스 전용 연결), 발행 표시를 세울 때 그 세대 줄의 issuing_at 을 채운다. 만료 인수
--    뒤 운영자 확인이 «미해소 원천 보유자» — 발행을 연 가장 최근 세대의 보유자부터 직전 세대까지 가운데 멈춤·자기 차단을 적지 않은 인스턴스 —
--    를 이것으로 센다. 리스 표처럼 쓰기 펜싱 트리거의 예외다(대기·획득 중인 인스턴스가 적는다). issuing_at 을 고치므로 덧붙이기 전용이 아니다.
CREATE TABLE host_lease_grant (
    generation  BIGINT      PRIMARY KEY CHECK (generation >= 1),
    holder      TEXT        NOT NULL CHECK (holder <> ''),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    issuing_at  TIMESTAMPTZ
);

-- 이 마이그레이션 전의 세대는 지금 세대 하나만 안다(앞 세대의 보유자는 남지 않았다). 지금 세대가 발행을 열었으면 그 표시도 옮긴다.
INSERT INTO host_lease_grant (generation, holder, acquired_at, issuing_at)
SELECT l.generation, l.holder, clock_timestamp(), CASE WHEN t.issuing THEN clock_timestamp() END
FROM host_lease l JOIN host_lease_term t USING (role)
WHERE l.role = 'host' AND l.generation > 0;

-- 2) 운영자 확인이 확인한 미해소 원천 보유자 집합과, 그 셈이 발행을 연 세대(또는 세대 1)까지 이력을 다 보았는가. 확인 행과 함께 적는다(덧붙이기
--    전용 표에 칸을 더할 뿐 행은 고치지 않는다). 이력이 끊긴 셈(이 표 전의 세대)은 운영자가 그것을 알고 확인했다는 표시가 있어야 받는다.
ALTER TABLE host_takeover_confirmation
    ADD COLUMN unresolved_holders TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN history_complete BOOLEAN NOT NULL DEFAULT true;

-- 3) 자동 확인 근거. 관측(HOLDING) 중 수신자가 이 세대를 확인한 기체 하나에 한 줄 — 그 기체의 새 발행을 연 근거(지금 읽은 스냅숏의 응답 바닥값과
--    fence_status). 쓰기는 리스 가드를 지나고(아래 트리거), 덧붙이기만 한다.
CREATE TABLE host_readiness_evidence (
    evidence_id      BIGSERIAL   PRIMARY KEY,
    generation       BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id      TEXT        NOT NULL CHECK (instance_id <> ''),
    robot_id         TEXT        NOT NULL CHECK (robot_id <> ''),
    controller_floor BIGINT      NOT NULL CHECK (controller_floor >= 1),
    fence_status     TEXT        NOT NULL CHECK (fence_status <> ''),
    observed_at      TIMESTAMPTZ NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (generation, robot_id)
);

CREATE TRIGGER host_readiness_evidence_no_update_delete
    BEFORE UPDATE OR DELETE ON host_readiness_evidence
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_readiness_evidence_no_truncate
    BEFORE TRUNCATE ON host_readiness_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_readiness_evidence_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_readiness_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();
