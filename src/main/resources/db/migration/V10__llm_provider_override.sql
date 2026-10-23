-- V10__llm_provider_override.sql
-- LLM providers edited on the /settings routing table (ProviderCatalog). application.properties'
-- app.llm.providers[N] is the starting point and a row here sits on top of it, keyed by name —
-- providers that share a name are one logical model (ProviderToggle treats them the same way).
-- A NULL column means "keep the application.properties value". There is deliberately no api-key
-- column: keys stay in the environment (.env) only.
--   added        — 1 = a provider the screen created (no application.properties entry). All of
--                  base_url / model / type / role / priority are then filled in.
--   deleted      — 1 = hide this provider: it is not registered after the next restart.
--   context_size — a manually entered context window (tokens). Unlike the other columns it takes
--                  effect immediately (ProviderContextWindows, recorded as declared); the rest apply
--                  on the next restart, when LlmConfig builds the router from the merged list.
-- Deleting a row reverts that provider to its application.properties definition.

CREATE TABLE llm_provider_override (
    name         TEXT    PRIMARY KEY,
    base_url     TEXT,
    model        TEXT,
    type         TEXT,
    role         TEXT,
    priority     INTEGER,
    context_size INTEGER,
    added        INTEGER NOT NULL DEFAULT 0,
    deleted      INTEGER NOT NULL DEFAULT 0,
    updated_at   TEXT    NOT NULL
);
