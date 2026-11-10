-- V12__change_job.sql
-- Impact-analysis jobs (PLAN 6.33 phase 4a, AGENT_MCP_DESIGN 5.4). One row per job; the report is a JSON
-- document in summary_json that is written once when the job ends. Maintenance proposals (change_proposal)
-- come with phase 4b - nothing here writes to the corpus.
--   kind         - 'impact' now; 'maintenance' reserved for 4b
--   status       - running | done | failed | interrupted (a job still 'running' at startup is marked
--                  'interrupted' by ChangeJobRepository.markRunningInterrupted)
--   requested_by - the caller's user id; the report is visible to the requester and to administrators only
--   summary_json - report: counts, judged chunks with their verified quotes, the unjudged remainder

CREATE TABLE change_job (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    kind         TEXT NOT NULL,
    request_text TEXT NOT NULL,
    requested_by TEXT NOT NULL,
    status       TEXT NOT NULL,
    summary_json TEXT,
    error        TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_change_job_user ON change_job (requested_by, created_at);
