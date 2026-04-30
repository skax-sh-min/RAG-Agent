package com.example.ragagent.repository;

import com.example.ragagent.model.ThreadMeta;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
public class ThreadMetaRepository {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public ThreadMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS thread_meta (
                    thread_id    TEXT PRIMARY KEY,
                    title        TEXT NOT NULL DEFAULT '새 대화',
                    version      TEXT NOT NULL DEFAULT 'latest',
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL,
                    routing_mode TEXT NOT NULL DEFAULT 'COST_FIRST'
                )
                """);
        // Migration: add routing_mode for existing databases
        var cols = jdbc.queryForList("PRAGMA table_info(thread_meta)");
        boolean hasRoutingMode = cols.stream().anyMatch(c -> "routing_mode".equals(c.get("name")));
        if (!hasRoutingMode) {
            jdbc.execute("ALTER TABLE thread_meta ADD COLUMN routing_mode TEXT NOT NULL DEFAULT 'COST_FIRST'");
        }
    }

    public Optional<ThreadMeta> findById(String threadId) {
        List<ThreadMeta> rows = jdbc.query(
                "SELECT thread_id, title, version, created_at, updated_at, routing_mode FROM thread_meta WHERE thread_id = ?",
                (rs, n) -> new ThreadMeta(
                        rs.getString("thread_id"),
                        rs.getString("title"),
                        rs.getString("version"),
                        rs.getString("created_at"),
                        rs.getString("updated_at"),
                        rs.getString("routing_mode")),
                threadId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ThreadMeta> findAllRecent(int limit) {
        return jdbc.query(
                "SELECT thread_id, title, version, created_at, updated_at, routing_mode FROM thread_meta ORDER BY updated_at DESC LIMIT ?",
                (rs, n) -> new ThreadMeta(
                        rs.getString("thread_id"),
                        rs.getString("title"),
                        rs.getString("version"),
                        rs.getString("created_at"),
                        rs.getString("updated_at"),
                        rs.getString("routing_mode")),
                limit);
    }

    public void save(ThreadMeta meta) {
        jdbc.update("""
                INSERT INTO thread_meta (thread_id, title, version, created_at, updated_at, routing_mode)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(thread_id) DO UPDATE SET
                    title      = excluded.title,
                    version    = excluded.version,
                    updated_at = excluded.updated_at
                """,
                meta.threadId(), meta.title(), meta.version(),
                meta.createdAt(), meta.updatedAt(), meta.routingMode());
    }

    public void updateTitle(String threadId, String title) {
        jdbc.update(
                "UPDATE thread_meta SET title = ?, updated_at = ? WHERE thread_id = ?",
                title, now(), threadId);
    }

    public void updateRoutingMode(String threadId, String routingMode) {
        jdbc.update(
                "UPDATE thread_meta SET routing_mode = ? WHERE thread_id = ?",
                routingMode, threadId);
    }

    public void delete(String threadId) {
        jdbc.update("DELETE FROM thread_meta WHERE thread_id = ?", threadId);
    }

    public int countTurns(String threadId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_turns WHERE thread_id = ?",
                Integer.class, threadId);
        return count != null ? count : 0;
    }

    public static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }
}
