-- 작업 지시 철회(B3). 덧붙이기만 한다. 시각은 DB 의 clock_timestamp() 다.
-- 상위 시스템의 대체 접수와 스테이션 현장 해제는 운영 DB 만으로는 «보내지 않았다» 를 증명하지 못한다 — 값 없는 승인(승인 호스트)이 운영 서비스를
-- 거치지 않고 실행을 세우기 때문이다. 실행 호스트가 자기 잠금 아래에서 그 작업 지시의 실행이 없음을 보고 이 행을 적으면, 그 뒤로 그 작업 지시는
-- 값 없는 승인으로도 새 접수로도 실행을 짓지 않는다. 영구다 — 풀어 주는 호출은 없다.
--
-- 작업 지시마다 한 행이다(job_order_id 기본 키, 다른 요청 id 로 다시 철회해도 이 행을 돌려준다). 요청 id 는 한 번이다(request_id UNIQUE).
-- requested_by 는 철회를 부른 쪽(운영 서비스가 싣는 행위자), reason 은 그 까닭이다.
CREATE TABLE job_order_withdrawal (
    job_order_id TEXT        PRIMARY KEY,
    request_id   UUID        NOT NULL UNIQUE,
    requested_by TEXT        NOT NULL CHECK (requested_by <> ''),
    reason       TEXT        NOT NULL CHECK (btrim(reason) <> ''),
    withdrawn_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 덧붙이기만 한다. V2 의 mission_record_append_only() 를 그대로 쓴다.
CREATE TRIGGER job_order_withdrawal_no_update_delete
    BEFORE UPDATE OR DELETE ON job_order_withdrawal
    FOR EACH ROW EXECUTE FUNCTION mission_record_append_only();

CREATE TRIGGER job_order_withdrawal_no_truncate
    BEFORE TRUNCATE ON job_order_withdrawal
    FOR EACH STATEMENT EXECUTE FUNCTION mission_record_append_only();
