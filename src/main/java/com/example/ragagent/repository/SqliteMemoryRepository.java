package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class SqliteMemoryRepository implements MemoryRepository {

    // fetch at most this many recent turns before applying char truncation
    private static final int FETCH_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public SqliteMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        // WAL mode allows concurrent reads while one writer is active
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS conversation_turns (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    thread_id  TEXT NOT NULL,
                    question   TEXT NOT NULL,
                    answer     TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_thread_id ON conversation_turns(thread_id)");
        // Add metadata columns (ALTER TABLE fails silently if column already exists)
        for (String ddl : List.of(
                "ALTER TABLE conversation_turns ADD COLUMN asked_at TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN input_tokens INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN output_tokens INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN elapsed_ms INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN provider TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN llm_calls INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous'",
                "ALTER TABLE conversation_turns ADD COLUMN feedback TEXT"
        )) {
            try { jdbc.execute(ddl); } catch (Exception ignored) {}
        }
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_turns_user_thread ON conversation_turns(user_id, thread_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS image_descriptions (
                    image_path  TEXT    PRIMARY KEY,
                    description TEXT    NOT NULL,
                    image_type  TEXT,
                    provider    TEXT,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
                )
                """);
        try {
            jdbc.execute("ALTER TABLE image_descriptions ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous'");
        } catch (Exception ignored) {}
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_img_user ON image_descriptions(user_id)");
    }

    @Override
    public String getHistory(String userId, String threadId, int maxChars) {
        // fetch last FETCH_LIMIT turns newest-first, then reverse for chronological order.
        // DISLIKE-tagged turns are excluded from context (hard exclusion, §6.9).
        List<String> rows = jdbc.query(
                "SELECT question, answer FROM conversation_turns " +
                "WHERE user_id = ? AND thread_id = ? AND (feedback IS NULL OR feedback <> 'DISLIKE') " +
                "ORDER BY id DESC LIMIT ?",
                (rs, n) -> "Q: %s\nA: %s".formatted(rs.getString("question"), rs.getString("answer")),
                userId, threadId, FETCH_LIMIT);

        if (rows.isEmpty()) return "";

        // reverse to chronological order (oldest first)
        List<String> entries = new ArrayList<>(rows.reversed());

        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (sb.length() + entry.length() > maxChars) break;
            sb.insert(0, entry + "\n\n");
        }
        // single turn larger than budget → include it truncated rather than returning empty
        if (sb.isEmpty() && !entries.isEmpty()) {
            String newest = entries.get(entries.size() - 1);
            sb.append(newest, 0, Math.min(newest.length(), maxChars))
              .append("\n[이전 대화 일부 생략]");
        }
        return sb.toString().strip();
    }

    @Override
    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO conversation_turns " +
                    "(user_id, thread_id, question, answer, asked_at, input_tokens, output_tokens, elapsed_ms, provider, llm_calls) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userId);
            ps.setString(2, threadId);
            ps.setString(3, question);
            ps.setString(4, answer);
            ps.setString(5, askedAt);
            ps.setInt(6, inputTokens);
            ps.setInt(7, outputTokens);
            ps.setInt(8, elapsedMs);
            ps.setString(9, provider);
            ps.setInt(10, llmCalls);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    @Override
    public void clearHistory(String userId, String threadId) {
        jdbc.update("DELETE FROM conversation_turns WHERE user_id = ? AND thread_id = ?", userId, threadId);
    }

    @Override
    public List<Turn> getTurns(String userId, String threadId) {
        return jdbc.query(
                "SELECT id, question, answer, asked_at, created_at, " +
                "input_tokens, output_tokens, elapsed_ms, provider, llm_calls, feedback " +
                "FROM conversation_turns WHERE user_id = ? AND thread_id = ? ORDER BY id ASC",
                (rs, n) -> new Turn(
                        rs.getLong("id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("asked_at"),
                        rs.getString("created_at"),
                        rs.getInt("input_tokens"),
                        rs.getInt("output_tokens"),
                        rs.getInt("elapsed_ms"),
                        rs.getString("provider"),
                        rs.getInt("llm_calls"),
                        rs.getString("feedback")),
                userId, threadId);
    }

    @Override
    public Optional<FeedbackRow> getFeedback(String userId, String threadId, long turnId) {
        List<FeedbackRow> rows = jdbc.query(
                "SELECT feedback FROM conversation_turns WHERE id = ? AND user_id = ? AND thread_id = ?",
                (rs, n) -> new FeedbackRow(rs.getString("feedback")),
                turnId, userId, threadId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void updateFeedback(String userId, String threadId, long turnId, String feedback) {
        jdbc.update(
                "UPDATE conversation_turns SET feedback = ? WHERE id = ? AND user_id = ? AND thread_id = ?",
                feedback, turnId, userId, threadId);
    }
}
