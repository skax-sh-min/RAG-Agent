-- V7__purge_orphan_turn_refs.sql
-- Source/image reference rows whose turn no longer exists (PLAN 6.25 open item: 54 such
-- turn_source_ref rows in a real deployment). Today's delete paths
-- (SqliteMemoryRepository.clearHistory / deleteTurn) remove both tables together with the turn,
-- so no new ones appear; these are left over from before that deletion was added (2026-08-06)
-- and from while its missing-table guard did not work. Every read path starts from
-- conversation_turns, so they were never shown - they only pile up. conversation_turns.id is
-- AUTOINCREMENT, so a deleted turn's id is never handed to a new turn and these rows could not
-- attach to the wrong turn either.

DELETE FROM turn_source_ref WHERE turn_id NOT IN (SELECT id FROM conversation_turns);

DELETE FROM turn_image_ref WHERE turn_id NOT IN (SELECT id FROM conversation_turns);
