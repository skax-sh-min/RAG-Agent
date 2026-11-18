-- V17__change_job_report_chunk.sql
-- Natural-language knowledge editing, phase 6d (AGENT_MCP_DESIGN section 10.12): a maintenance job started from a
-- chunk error report (section 10.14).
-- change_job:
--   report_chunk_id - the reported chunk the job was started from. Non-NULL means the job's only target is that chunk
--                     (no corpus-wide search) and applying an approved proposal for it closes the chunk's open reports
--                     as resolved. NULL for every other job and every job created before this migration.

ALTER TABLE change_job ADD COLUMN report_chunk_id TEXT;

CREATE INDEX IF NOT EXISTS idx_change_job_report_chunk ON change_job (report_chunk_id) WHERE report_chunk_id IS NOT NULL;
