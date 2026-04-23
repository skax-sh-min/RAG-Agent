package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

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
    }

    @Override
    public String getHistory(String threadId, int maxChars) {
        // fetch last FETCH_LIMIT turns newest-first, then reverse for chronological order
        List<String> rows = jdbc.query(
                "SELECT question, answer FROM conversation_turns " +
                "WHERE thread_id = ? ORDER BY id DESC LIMIT ?",
                (rs, n) -> "Q: %s\nA: %s".formatted(rs.getString("question"), rs.getString("answer")),
                threadId, FETCH_LIMIT);

        if (rows.isEmpty()) return "";

        // reverse to chronological order (oldest first)
        List<String> entries = new ArrayList<>(rows.reversed());

        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (sb.length() + entry.length() > maxChars) break;
            sb.insert(0, entry + "\n\n");
        }
        return sb.toString().strip();
    }

    @Override
    public void addTurn(String threadId, String question, String answer) {
        jdbc.update(
                "INSERT INTO conversation_turns (thread_id, question, answer) VALUES (?, ?, ?)",
                threadId, question, answer);
    }

    @Override
    public void clearHistory(String threadId) {
        jdbc.update("DELETE FROM conversation_turns WHERE thread_id = ?", threadId);
    }
}
