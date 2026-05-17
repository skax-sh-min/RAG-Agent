-- V1__baseline.sql
-- Baseline schema as of Flyway adoption (2026-05-17).
-- On existing databases this file is SKIPPED (baseline-on-migrate=true detects non-empty schema).
-- On new databases this creates the complete initial schema.

CREATE TABLE conversation_turns (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    thread_id     TEXT    NOT NULL,
    question      TEXT    NOT NULL,
    answer        TEXT    NOT NULL,
    created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
    asked_at      TEXT,
    input_tokens  INTEGER          DEFAULT 0,
    output_tokens INTEGER          DEFAULT 0,
    elapsed_ms    INTEGER          DEFAULT 0,
    provider      TEXT,
    llm_calls     INTEGER          DEFAULT 0,
    user_id       TEXT    NOT NULL DEFAULT 'anonymous'
);
CREATE INDEX idx_thread_id         ON conversation_turns(thread_id);
CREATE INDEX idx_turns_user_thread ON conversation_turns(user_id, thread_id);

CREATE TABLE image_descriptions (
    image_path  TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    image_type  TEXT,
    provider    TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    user_id     TEXT NOT NULL DEFAULT 'anonymous'
);
CREATE INDEX idx_img_user ON image_descriptions(user_id);

CREATE TABLE llm_usage (
    provider_name  TEXT    NOT NULL,
    usage_date     TEXT    NOT NULL,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    call_count     INTEGER NOT NULL DEFAULT 0,
    user_id        TEXT    NOT NULL DEFAULT 'anonymous',
    PRIMARY KEY (provider_name, usage_date)
);
CREATE INDEX idx_llm_usage_date ON llm_usage(usage_date);

CREATE TABLE thread_meta (
    thread_id    TEXT PRIMARY KEY,
    title        TEXT NOT NULL DEFAULT '새 대화',
    version      TEXT NOT NULL DEFAULT 'latest',
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    routing_mode TEXT NOT NULL DEFAULT 'COST_FIRST',
    user_id      TEXT NOT NULL DEFAULT 'anonymous'
);
CREATE INDEX idx_thread_meta_user ON thread_meta(user_id);
