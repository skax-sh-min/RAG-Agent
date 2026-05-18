package com.example.ragagent.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SqliteUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbc;

    public SqliteUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        List<AppUserDetails> results = jdbc.query(
                "SELECT id, email, password_hash, display_name, role, enabled, locked_until " +
                "FROM users WHERE email = ?",
                (rs, i) -> new AppUserDetails(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getInt("enabled") == 1,
                        isLocked(rs.getString("locked_until"))
                ),
                email.toLowerCase(Locale.ROOT)
        );
        if (results.isEmpty()) {
            throw new UsernameNotFoundException("User not found");
        }
        return results.get(0);
    }

    private boolean isLocked(String lockedUntil) {
        if (lockedUntil == null || lockedUntil.isBlank()) return false;
        try {
            return LocalDateTime.parse(lockedUntil).isAfter(LocalDateTime.now(ZoneOffset.UTC));
        } catch (Exception e) {
            return false;
        }
    }

    public void createUser(String id, String email, String passwordHash, String displayName) {
        String now = LocalDateTime.now(ZoneOffset.UTC).toString();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                id, email.toLowerCase(Locale.ROOT), passwordHash, displayName, now, now
        );
    }

    public boolean emailExists(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?",
                Integer.class, email.toLowerCase(Locale.ROOT));
        return count != null && count > 0;
    }

    public Optional<AppUserDetails> findFirstAdmin() {
        List<AppUserDetails> results = jdbc.query(
                "SELECT id, email, password_hash, display_name, role, enabled, locked_until " +
                "FROM users WHERE role = 'ADMIN' LIMIT 1",
                (rs, i) -> new AppUserDetails(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getInt("enabled") == 1,
                        isLocked(rs.getString("locked_until"))
                )
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void createAdminUser(String id, String email, String passwordHash, String displayName) {
        String now = LocalDateTime.now(ZoneOffset.UTC).toString();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, display_name, role, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'ADMIN', ?, ?)",
                id, email.toLowerCase(Locale.ROOT), passwordHash, displayName, now, now
        );
    }

    public void incrementFailedCount(String email, int maxAttempts, int lockMinutes) {
        String lockUntil = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(lockMinutes).toString();
        String now = LocalDateTime.now(ZoneOffset.UTC).toString();
        jdbc.update("""
                UPDATE users SET
                    failed_count = failed_count + 1,
                    locked_until = CASE WHEN failed_count + 1 >= ? THEN ? ELSE locked_until END,
                    updated_at   = ?
                WHERE email = ?
                """,
                maxAttempts, lockUntil, now, email.toLowerCase(Locale.ROOT));
    }

    public void resetFailedCount(String email) {
        String now = LocalDateTime.now(ZoneOffset.UTC).toString();
        jdbc.update("UPDATE users SET failed_count = 0, locked_until = NULL, updated_at = ? WHERE email = ?",
                now, email.toLowerCase(Locale.ROOT));
    }
}
