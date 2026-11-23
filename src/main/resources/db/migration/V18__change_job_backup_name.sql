-- V18__change_job_backup_name.sql
-- Change-proposal review screen, phase 6e (AGENT_MCP_DESIGN section 10.15, R2): one DB backup per job.
-- change_job:
--   backup_name - file name of the DB backup taken right before this job's first corpus write. Later applies of the
--                 same job (one chunk at a time from the review screen, or a document approval) reuse it while the
--                 file still exists, instead of taking a backup per confirmation that would push the scheduled
--                 backups out of the shared retention count. NULL = the job has not written anything yet.

ALTER TABLE change_job ADD COLUMN backup_name TEXT;
