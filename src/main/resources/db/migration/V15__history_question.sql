-- V15__history_question.sql
-- 1) conversation_turns.history_question — for a LONG question (over 1,000 chars, PLAN §6.30 related work /
--    LongQuestionQuery stage 2) the LLM-written search sentence, kept so that LATER turns can show the history a
--    short stand-in instead of the whole 10,000-char paste. `question` stays the user's own words, untouched:
--    the chat screen, reuse and the per-turn prompt's [현재 질문] all read it.
--      NULL — not a long question, the LLM stage did not run or failed (the deterministic head+tail excerpt is
--             NOT stored: it is cut text, not a summary) — the history shows `question`
--      text — the LLM's search sentence; getHistory()/getRecentTurns() (prompt-facing reads only) prefix it with
--             a marker and use it in place of `question`
ALTER TABLE conversation_turns ADD COLUMN history_question TEXT;

-- 2) clarified_question no longer stores a copy of the question. "Tried, and the original already reads on its
--    own" is now the EMPTY STRING; NULL still means "not generated yet / not a target / the call failed".
--    Old rows that hold a copy (same text as the question once stripped) become '' — nothing reads the copy.
UPDATE conversation_turns
   SET clarified_question = ''
 WHERE clarified_question IS NOT NULL
   AND clarified_question <> ''
   AND clarified_question = trim(question);
