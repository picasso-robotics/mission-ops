-- 관측 중 플릿 준비의 자동 확인 근거(HA 3단계 PR B2).
--
-- 만료 인수 뒤 관측(HOLDING) 중 현장 플릿 면이 이 세대를 확인했고(인수 확인 응답의 바닥값 = 이 세대 ∧ DURABLE_CONFIRMED), 그 확인 요청보다 뒤에 보낸
-- 플릿 읽기로 한 pump 의 재대조를 마친 뒤 그 플릿을 연 근거 한 줄. 기체 근거(host_readiness_evidence)와 칸이 달라 따로 둔다 — 현장 기동 ID 와
-- 확인 순번(재대조 경계)을 든다. 같은 확인의 재시도(같은 세대·기동·순번)는 한 줄이고(멱등), 후퇴·해제 뒤 새 경계로 다시 열면 새 줄이다.
-- 쓰기는 리스 가드를 지나고(아래 트리거), 덧붙이기만 한다.
CREATE TABLE host_fleet_readiness_evidence (
    evidence_id      BIGSERIAL   PRIMARY KEY,
    generation       BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id      TEXT        NOT NULL CHECK (instance_id <> ''),
    fleet_id         TEXT        NOT NULL CHECK (fleet_id <> ''),
    receiver_boot_id TEXT        NOT NULL CHECK (receiver_boot_id <> ''),
    controller_floor BIGINT      NOT NULL CHECK (controller_floor >= 1),
    fence_status     TEXT        NOT NULL CHECK (fence_status <> ''),
    confirm_seq      BIGINT      NOT NULL CHECK (confirm_seq >= 1),
    observed_at      TIMESTAMPTZ NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (generation, receiver_boot_id, confirm_seq)
);

CREATE TRIGGER host_fleet_readiness_evidence_no_update_delete
    BEFORE UPDATE OR DELETE ON host_fleet_readiness_evidence
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_fleet_readiness_evidence_no_truncate
    BEFORE TRUNCATE ON host_fleet_readiness_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_fleet_readiness_evidence_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_fleet_readiness_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();

-- 현장 플릿 기동 ID 의 기준 기록. kind = ISSUING: 발행 중(발행에 든 순간 포함) 확인 스레드가 본 기동(바뀔 때만). kind = RESTART_BASIS: 관측 중 재시작을
-- 판정했을 때 그 판정의 기준(옛 장부의) 기동. 새 보유자는 가장 높은 세대의 기록을 기준으로 쓰고, 같은 세대 안에서는 ISSUING > 자동 확인 근거 >
-- RESTART_BASIS 순이다 — 늦게 적힌 재시작 기준이 발행에 든 뒤의 기동을 덮지 않게. 같은 세대·종류 안에서는 행 번호가 아니라 그 기동을 본 확인의 관측
-- 순번(observed_seq, 그 프로세스의 플릿 요청 순번 — 한 세대는 한 프로세스만 쓴다)이 큰 것이 앞선다 — 발행에 든 순간의 기록이 쓰기 직전에 멈춘 사이
-- 확인 스레드가 새 기동을 적어도, 늦게 들어간 옛 기동이 새 기동을 덮지 않게. 같은 기동을 더 뒤의 확인으로 다시 적으면(덧붙이기 전용이라 갱신 대신)
-- 새 줄이다. 재시작 기준은 순번 0 이다. 자동 확인 근거만 기준으로 두면 정상 운영 중의 현장 재시작 뒤로 모든 인수가 재시작으로 보인다. 같은
-- 세대·기동·종류·순번은 한 줄이다. 리스 가드를 지나고 덧붙이기만 한다.
CREATE TABLE host_fleet_receiver_boot (
    boot_id          BIGSERIAL   PRIMARY KEY,
    generation       BIGINT      NOT NULL CHECK (generation >= 1),
    instance_id      TEXT        NOT NULL CHECK (instance_id <> ''),
    fleet_id         TEXT        NOT NULL CHECK (fleet_id <> ''),
    receiver_boot_id TEXT        NOT NULL CHECK (receiver_boot_id <> ''),
    kind             TEXT        NOT NULL CHECK (kind IN ('ISSUING', 'RESTART_BASIS')),
    observed_seq     BIGINT      NOT NULL CHECK (observed_seq >= 0),
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (generation, receiver_boot_id, kind, observed_seq)
);

CREATE TRIGGER host_fleet_receiver_boot_no_update_delete
    BEFORE UPDATE OR DELETE ON host_fleet_receiver_boot
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_fleet_receiver_boot_no_truncate
    BEFORE TRUNCATE ON host_fleet_receiver_boot
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER host_fleet_receiver_boot_fenced
    BEFORE INSERT OR UPDATE OR DELETE ON host_fleet_receiver_boot
    FOR EACH STATEMENT EXECUTE FUNCTION mission_require_fenced_generation();
