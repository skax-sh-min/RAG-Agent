-- V8__thread_follow_up.sql
-- Follow-up questions suggested under an answer (PostAnswerService). One row per conversation
-- holding only the latest answer's suggestions: a newer answer overwrites it, and an answer that
-- produced none deletes it. The chat shows suggestions under the most recent answer only, so
-- older ones have no reader. Before this table they lived in memory for 10 minutes and were gone
-- after a restart or a longer break.
--   turn_id   — the turn the suggestions belong to; the read checks it, so a stale row from an
--               earlier turn is never shown under a newer answer
--   questions — one question per line (each is a single line by construction, see
--               PostAnswerService.cleanFollowUps)
-- Deleted together with the conversation (SqliteMemoryRepository.clearHistory) and with its turn
-- (deleteTurn).

CREATE TABLE thread_follow_up (
    user_id    TEXT    NOT NULL,
    thread_id  TEXT    NOT NULL,
    turn_id    INTEGER NOT NULL,
    questions  TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (user_id, thread_id)
);
