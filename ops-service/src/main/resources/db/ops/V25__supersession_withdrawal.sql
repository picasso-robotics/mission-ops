-- Upstream supersession of a request that ended without executing now first withdraws the original's job order on the execution host (B3).
-- The ops database alone cannot prove the original was never sent: a no-value approval on the host can start it without passing through ops.
-- The link records the host's withdrawal request id (mission.job_order_withdrawal.request_id); after the host withdrew a job order it never
-- runs it. Links made before this migration and links of an operator-closed original (ACCEPTED) have none. The table's trigger blocks UPDATE
-- and DELETE only, so adding a nullable column is safe.
ALTER TABLE ops.work_request_supersession ADD COLUMN withdrawal_request_id UUID;
ALTER TABLE ops.work_request_supersession ADD CONSTRAINT work_request_supersession_withdrawal_check CHECK (
 original_state <> 'ACCEPTED' OR withdrawal_request_id IS NULL);
