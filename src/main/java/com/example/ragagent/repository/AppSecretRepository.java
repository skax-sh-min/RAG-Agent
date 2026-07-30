package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Tiny {@code name → secret} store for server-side secrets that must survive restarts but are not
 * operator-facing configuration — so they deliberately do <b>not</b> live in {@code settings_override}
 * (which {@code /settings} renders and {@code SettingsService} loads wholesale into an in-memory cache).
 *
 * <p>Currently holds only the guest-identity HMAC key. Persistence is the whole point: a per-boot
 * random key would re-hash every visitor on restart, orphaning their entire chat history.
 *
 * <p>Lives in the operational {@code memory.db} (the {@code @Primary} JdbcTemplate) and uses the same
 * idempotent {@code CREATE TABLE IF NOT EXISTS} + raw {@link JdbcTemplate} pattern as
 * {@link SettingsOverrideRepository} (SQLite is incompatible with JPA).
 */
@Repository
public class AppSecretRepository {

    private static final int SECRET_BYTES = 32;

    private final JdbcTemplate jdbc;

    public AppSecretRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS app_secret (
                    name       TEXT NOT NULL PRIMARY KEY,
                    value      TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
    }

    /**
     * Returns the stored secret for {@code name}, generating and persisting a fresh 256-bit one on
     * first call. Stable across restarts by construction.
     *
     * <p>The insert is {@code INSERT OR IGNORE} followed by a re-read rather than "insert then return
     * what I generated": two threads racing on first use must converge on the row that actually won,
     * not each keep its own value.
     */
    public byte[] getOrCreate(String name) {
        List<String> existing = jdbc.queryForList(
                "SELECT value FROM app_secret WHERE name = ?", String.class, name);
        if (!existing.isEmpty()) return HexFormat.of().parseHex(existing.get(0));

        byte[] fresh = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(fresh);
        jdbc.update("INSERT OR IGNORE INTO app_secret (name, value, created_at) VALUES (?, ?, ?)",
                name, HexFormat.of().formatHex(fresh), Instant.now().toString());

        return HexFormat.of().parseHex(
                jdbc.queryForObject("SELECT value FROM app_secret WHERE name = ?", String.class, name));
    }
}
