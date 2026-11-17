-- V16__knowledge_edit.sql
-- Natural-language knowledge editing, phase 6a (AGENT_MCP_DESIGN section 10.11).
-- change_job:
--   intent_json        - the parsed edit intent {mode, type, subject, content, replacement}; NULL for literal jobs
--                        and every job created before this migration
-- change_proposal:
--   ops_json           - anchored edit operations the proposal was built from (display/audit only; apply and the
--                        converted-MD write-back work from before_text/after_text)
--   target_role        - describes | mentions | NULL (judged role of the chunk)
--   evidence_quote     - the quote the judge cited for target_role
--   group_key          - proposals paired across a chunk overlap: withdrawn together, stale together
--   machine_after_text - the machine proposal before an administrator edited after_text; NULL = never edited
--   edited_by/edited_at- who edited after_text and when (UTC, DbTimestamp)
-- New values (no constraint change - the columns are free TEXT): kind 'edit' | 'delete_chunk', status 'withdrawn'.

ALTER TABLE change_job ADD COLUMN intent_json TEXT;

ALTER TABLE change_proposal ADD COLUMN ops_json TEXT;
ALTER TABLE change_proposal ADD COLUMN target_role TEXT;
ALTER TABLE change_proposal ADD COLUMN evidence_quote TEXT;
ALTER TABLE change_proposal ADD COLUMN group_key TEXT;
ALTER TABLE change_proposal ADD COLUMN machine_after_text TEXT;
ALTER TABLE change_proposal ADD COLUMN edited_by TEXT;
ALTER TABLE change_proposal ADD COLUMN edited_at TEXT;
