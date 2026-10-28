-- V11__turn_exclude_history.sql
-- PLAN §6.31 — the user ticked "이력 제외" for this message: its RAG answer prompt carried no
-- [이전 대화] block and retrieval loaded extra documents in its place. Stored per turn so the
-- bubble can say so after a reload, the record explains why earlier context was ignored, and
-- question reuse copies it with the answer's other shape values (mode, direct_mode, tags).
--   0 — the default; every turn before this column, and every Direct / meta turn
--   1 — the answer was given without the conversation history
-- The turn itself still feeds later turns' history as usual — this is not a "dislike".

ALTER TABLE conversation_turns ADD COLUMN exclude_history INTEGER NOT NULL DEFAULT 0;
