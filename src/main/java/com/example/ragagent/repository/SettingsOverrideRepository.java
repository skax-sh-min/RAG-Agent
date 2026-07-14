package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent store for runtime settings overrides (key → value).
 *
 * <p>Lives in the operational {@code memory.db} (the {@code @Primary} JdbcTemplate), not the vector
 * DB — these are app-config rows, unrelated to embeddings/FTS. Overrides survive restarts; deleting
 * a row reverts that key to its {@code application.properties} default (see
 * {@code AppProperties.xxxSafe()}). Uses the same idempotent {@code CREATE TABLE IF NOT EXISTS} +
 * raw {@link JdbcTemplate} pattern as {@link LlmUsageRepository} (SQLite is incompatible with JPA).
 */
@Repository
public class SettingsOverrideRepository {

    private final JdbcTemplate jdbc;

    public SettingsOverrideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS settings_override (
                    key        TEXT NOT NULL PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
    }

    /** All persisted overrides as an insertion-ordered key→value map (empty when none). */
    public Map<String, String> findAll() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT key, value FROM settings_override ORDER BY key",
                rs -> { out.put(rs.getString("key"), rs.getString("value")); });
        return out;
    }

    /** Inserts or replaces the override for {@code key}. */
    public void upsert(String key, String value) {
        jdbc.update("""
                INSERT INTO settings_override (key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """, key, value, Instant.now().toString());
    }

    /** Removes the override for {@code key} (reverting to the property default). Returns rows deleted. */
    public int delete(String key) {
        return jdbc.update("DELETE FROM settings_override WHERE key = ?", key);
    }
}
