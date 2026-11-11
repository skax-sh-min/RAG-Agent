-- V14__agent_tool_call.sql
-- Tool-selection observations for the /admin "Tool 호출 추적" panel (PLAN 6.33 phase 5, AGENT_MCP_DESIGN 5.6).
-- One row per planning call (phase 'plan') and per executed tool (phase 'exec'). No arguments and no question
-- text are stored: the panel needs distributions and timings, and a log of what users asked would be a second copy
-- of conversation content that nothing else guards.
--   phase       - 'plan' | 'exec'
--   tool_name   - the chosen tool; 'NONE' when the model chose no tool or its answer was unusable
--   status      - plan: planned | none | invalid | rejected | error
--                 exec: ok | empty | error
--   result_size - exec only: characters of the tool result
--   duration_ms - wall time of the planning call / the tool execution
-- Rows older than 90 days are purged at startup (AgentToolCallRepository).

CREATE TABLE agent_tool_call (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    thread_id   TEXT,
    user_id     TEXT,
    phase       TEXT NOT NULL,
    tool_name   TEXT NOT NULL,
    status      TEXT NOT NULL,
    result_size INTEGER,
    duration_ms INTEGER,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_agent_tool_call_created ON agent_tool_call (created_at);
