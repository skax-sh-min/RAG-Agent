-- V13__change_proposal.sql
-- Maintenance proposals (PLAN 6.33 phase 4b, AGENT_MCP_DESIGN 5.5/5.6). One row per chunk the maintenance workflow wants to change.
-- This table is only the review queue: creating a row never touches the corpus (chunk text, vectors, FTS, converted MD).
-- Applying an approved proposal is a separate, later step.
--   job_id         - change_job.id of the maintenance job that produced it
--   doc_id         - the document the chunk belongs to; approval is per document (a partial edit would make
--                    neighbouring chunks' overlap disagree and duplicate text on re-assembly)
--   chunk_id       - the vector-store chunk id (kept by an in-place edit)
--   kind           - 'literal' (deterministic replacement); 'semantic' (LLM rewrite) is reserved
--   before_hash    - SHA-256 of before_text; apply compares it with the live chunk and marks the row 'stale' on mismatch
--   *_context / *_keywords - chunk_context and excerpt_keywords before/after: the search text is chunk_context + body,
--                    so replacing only the body would keep matching the old term
--   status         - pending | approved | rejected | applied | stale | failed
--   md_status      - outcome of writing the change back to the converted Markdown (set at apply time)

CREATE TABLE change_proposal (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id          INTEGER NOT NULL,
    doc_id          TEXT    NOT NULL,
    filename        TEXT    NOT NULL DEFAULT '',
    chunk_id        TEXT    NOT NULL,
    kind            TEXT    NOT NULL,
    before_text     TEXT    NOT NULL,
    after_text      TEXT    NOT NULL,
    before_hash     TEXT    NOT NULL,
    before_context  TEXT,
    after_context   TEXT,
    before_keywords TEXT,
    after_keywords  TEXT,
    rationale       TEXT    NOT NULL DEFAULT '',
    status          TEXT    NOT NULL DEFAULT 'pending',
    md_status       TEXT,
    reviewed_by     TEXT,
    reviewed_at     TEXT,
    applied_at      TEXT,
    created_at      TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_change_proposal_job ON change_proposal (job_id, doc_id);
CREATE INDEX idx_change_proposal_chunk ON change_proposal (chunk_id);
