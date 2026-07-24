-- V3__thread_tags.sql
-- Persists the tag selection last used when sending a message in a thread, shown in the
-- sidebar thread list alongside the version.

ALTER TABLE thread_meta ADD COLUMN tags TEXT NOT NULL DEFAULT '';
