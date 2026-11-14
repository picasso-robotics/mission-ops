-- 잔여 파지 표시와 회복 실행의 연결(§15.317 이후 B1). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 표시가 있는 기체에 관문 확인으로 선 조치 제안을 값 없이 승인하면 그 기체에 실행이 선다(표시 예외). 그 실행이 어느 표시의 회복인지를 한 행으로 적는다.
-- 표시는 이 연결로 풀리지 않는다 — 해소는 사람이 robot_residual_hold_clearing 에 적는다. 이 행은 사람이 해소할 때 볼 근거(어느 실행이 무엇을 했나)다.
-- 실행 일지와 같은 트랜잭션에 적는다. job_order_id 는 회복 실행의 작업 지시이고 표시의 작업 지시(robot_residual_hold.job_order_id)와 다르다.
CREATE TABLE robot_residual_hold_recovery (
    marker_id    BIGINT      NOT NULL REFERENCES robot_residual_hold (marker_id),
    execution_id TEXT        NOT NULL CHECK (execution_id <> ''),
    job_order_id TEXT        NOT NULL REFERENCES execution_journal (job_order_id),
    approver_id  TEXT        NOT NULL CHECK (approver_id <> ''),
    approved_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (marker_id, execution_id)
);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER robot_residual_hold_recovery_no_update_delete
    BEFORE UPDATE OR DELETE ON robot_residual_hold_recovery
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER robot_residual_hold_recovery_no_truncate
    BEFORE TRUNCATE ON robot_residual_hold_recovery
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
