-- HA design §10 hotspot: the work request list search. It matched a substring of three columns with strpos, which no index serves, so
-- every search read the whole table. The search is now LIKE '%…%' (case-sensitive, same rows as strpos) and these trigram indexes serve
-- it through a bitmap OR. A search shorter than three characters has no trigram and still reads the whole table.
-- pg_trgm is a trusted extension (PostgreSQL 13+): the database owner may create it. It goes to public so the operator class has a fixed
-- name whatever the search path is during the migration.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE INDEX work_request_external_order_trgm ON ops.work_request USING gin (external_order_id public.gin_trgm_ops);
CREATE INDEX work_request_job_order_trgm ON ops.work_request USING gin (job_order_id public.gin_trgm_ops);
CREATE INDEX work_request_source_system_trgm ON ops.work_request USING gin (source_system_id public.gin_trgm_ops);
