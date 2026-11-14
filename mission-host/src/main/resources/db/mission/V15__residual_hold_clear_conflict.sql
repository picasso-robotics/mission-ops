-- 빈손 확인과 발신자 관측의 어긋남(§15.317 이후 B2). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 사람이 «빈손 확인» 을 보낸 순간 실행 호스트는 그 기체의 스냅숏을 지금 한 번 읽는다. 발신자가 지금 든 채(HOLDING)라고 말하면 해소하지 않고
-- 그 관측을 충돌 행으로 적어 409 로 답한다. 사람이 현장에서 빈손을 확인했으면 그 저장된 충돌을 지목해 다시 확인하고, 그때만 «어긋남을 알고 해소» 한다.
--
-- 관측 칸 여덟(두 표가 같은 묶음이다 — 충돌과 해소를 칸 단위로 비교·제약한다):
--   observed_kind        HOLDING·EMPTY·NOT_OBSERVABLE·UNSPECIFIED(발신자가 말하지 않음)·UNREADABLE(스냅숏을 못 읽음)·NOT_RECORDED(V15 전 옛 해소 행만)
--   observed_object_ref  HOLDING 의 물품 이름. 빈 값은 NULL 로 적는다
--   observed_detail      관측 불가 사유(HoldState.reason)나 읽기 실패 사유. 사람이 읽는다
--   snapshot_session     스냅숏 응답 헤더의 세션. 미상이면 NULL
--   snapshot_sequence    그 세션 안의 스냅숏 번호. 못 읽었으면 NULL
--   snapshot_occurred_at 발신자가 자기 시계로 이 응답을 찍은 시각 — 센서가 손을 관측한 시각이 아니다. 미상이면 NULL
--   host_read_started_at 실행 호스트가 스냅숏 읽기를 시작한 시각(호스트 시계)
--   host_read_received_at 실행 호스트가 답(또는 실패)을 받은 시각(호스트 시계)

-- 해소를 거절한 관측. 같은 요청 id 의 재전송은 이 행을 그대로 돌려준다(새 행 없음). 저장된 관측은 늘 HOLDING 이다.
CREATE TABLE robot_residual_hold_conflict (
    request_id            UUID        PRIMARY KEY,
    marker_id             BIGINT      NOT NULL REFERENCES robot_residual_hold (marker_id),
    approver_id           TEXT        NOT NULL CHECK (approver_id <> ''),
    approver_kind         TEXT        NOT NULL CHECK (approver_kind = 'PERSON'),
    reason                TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    observed_kind         TEXT        NOT NULL CHECK (observed_kind = 'HOLDING'),
    observed_object_ref   TEXT        CHECK (observed_object_ref <> ''),
    observed_detail       TEXT,
    snapshot_session      TEXT        CHECK (snapshot_session <> ''),
    snapshot_sequence     BIGINT,
    snapshot_occurred_at  TIMESTAMPTZ,
    host_read_started_at  TIMESTAMPTZ NOT NULL,
    host_read_received_at TIMESTAMPTZ NOT NULL,
    refused_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (marker_id, request_id)
);

CREATE TRIGGER robot_residual_hold_conflict_no_update_delete
    BEFORE UPDATE OR DELETE ON robot_residual_hold_conflict
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_conflict_no_truncate
    BEFORE TRUNCATE ON robot_residual_hold_conflict
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();

-- 해소 행에 같은 관측 칸과 요청 id·지목한 충돌·어긋남 인지를 더한다. 옛 행은 request_id 가 NULL 이고 관측은 NOT_RECORDED 다.
-- ADD COLUMN 은 행을 고쳐 쓰지 않는다(상수 기본값은 카탈로그에만 적힌다) — 덧붙이기 전용 행 트리거가 걸리지 않는다. 새 행의 request_id 는
-- 실행 호스트가 늘 싣는다(옛 행이 NULL 이라 NOT NULL 로 막지 못한다).
ALTER TABLE robot_residual_hold_clearing
    ADD COLUMN request_id            UUID UNIQUE,
    ADD COLUMN observed_kind         TEXT NOT NULL DEFAULT 'NOT_RECORDED',
    ADD COLUMN observed_object_ref   TEXT CHECK (observed_object_ref <> ''),
    ADD COLUMN observed_detail       TEXT,
    ADD COLUMN snapshot_session      TEXT CHECK (snapshot_session <> ''),
    ADD COLUMN snapshot_sequence     BIGINT,
    ADD COLUMN snapshot_occurred_at  TIMESTAMPTZ,
    ADD COLUMN host_read_started_at  TIMESTAMPTZ,
    ADD COLUMN host_read_received_at TIMESTAMPTZ,
    ADD COLUMN conflict_request_id   UUID,
    ADD COLUMN acknowledged_mismatch BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE robot_residual_hold_clearing
    ADD CONSTRAINT robot_residual_hold_clearing_observed_kind
        CHECK (observed_kind IN ('HOLDING', 'EMPTY', 'NOT_OBSERVABLE', 'UNSPECIFIED', 'UNREADABLE', 'NOT_RECORDED')),
    -- 어긋남 인지는 지금 HOLDING 일 때만이고 HOLDING 이면 늘 인지다(HOLDING 인데 지목 없이 해소한 행은 없다).
    ADD CONSTRAINT robot_residual_hold_clearing_acknowledged
        CHECK (acknowledged_mismatch = (observed_kind = 'HOLDING')),
    ADD CONSTRAINT robot_residual_hold_clearing_acknowledged_cites
        CHECK (NOT acknowledged_mismatch OR conflict_request_id IS NOT NULL),
    -- 관측을 적지 않은 것은 옛 행뿐이다. 새 행은 호스트가 답을 받은 시각이 늘 있다.
    ADD CONSTRAINT robot_residual_hold_clearing_recorded
        CHECK ((observed_kind = 'NOT_RECORDED') = (host_read_received_at IS NULL)),
    ADD CONSTRAINT robot_residual_hold_clearing_cites_other
        CHECK (conflict_request_id IS NULL OR conflict_request_id <> request_id),
    -- 지목한 충돌은 같은 표시의 것이다.
    ADD CONSTRAINT robot_residual_hold_clearing_conflict
        FOREIGN KEY (marker_id, conflict_request_id) REFERENCES robot_residual_hold_conflict (marker_id, request_id);
