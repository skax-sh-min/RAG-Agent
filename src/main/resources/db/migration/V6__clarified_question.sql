-- V6__clarified_question.sql
-- The question rewritten after the answer so it reads on its own and says what that answer covers
-- (PostAnswerService). Kept beside the user's own words, never instead of them: `question` stays
-- untouched. Question reuse suggests and copies this text, and the chat shows it under the
-- original when the two differ.
--   NULL      — not generated yet, not a target, or the LLM call failed (a later backfill may retry)
--   = question — generated, and the original already read on its own (or the rewrite was rejected)
--   other      — the clarified question

ALTER TABLE conversation_turns ADD COLUMN clarified_question TEXT;
