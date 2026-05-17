package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Tracks per-provider token usage in SQLite (daily UPSERT, period aggregation).
 * Shares the existing memory.db DataSource.
 */
@Repository
public class LlmUsageRepository {

    private final JdbcTemplate jdbc;

    public LlmUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS llm_usage (
                    provider_name  TEXT    NOT NULL,
                    usage_date     TEXT    NOT NULL,
                    input_tokens   INTEGER NOT NULL DEFAULT 0,
                    output_tokens  INTEGER NOT NULL DEFAULT 0,
                    call_count     INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (provider_name, usage_date)
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_llm_usage_date ON llm_usage(usage_date)");
        try {
            jdbc.execute("ALTER TABLE llm_usage ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous'");
        } catch (Exception ignored) {}
    }

    // ── Write ──────────────────────────────────────────────────────────────

    public void record(String provider, long inputTokens, long outputTokens) {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        jdbc.update("""
                INSERT INTO llm_usage (provider_name, usage_date, input_tokens, output_tokens, call_count)
                VALUES (?, ?, ?, ?, 1)
                ON CONFLICT (provider_name, usage_date) DO UPDATE SET
                    input_tokens  = input_tokens  + excluded.input_tokens,
                    output_tokens = output_tokens + excluded.output_tokens,
                    call_count    = call_count    + 1
                """, provider, today, inputTokens, outputTokens);
    }

    // ── Period aggregation ─────────────────────────────────────────────────

    public PeriodSummary getByPeriod(String provider, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(input_tokens), 0),
                       COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(call_count), 0)
                FROM llm_usage
                WHERE provider_name = ? AND usage_date BETWEEN ? AND ?
                """,
                (rs, i) -> new PeriodSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                provider, from.toString(), to.toString());
    }

    public PeriodSummary getDaily(String provider) {
        return getByPeriod(provider, LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC));
    }

    public PeriodSummary getWeekly(String provider) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return getByPeriod(provider, today.minusDays(6), today);
    }

    public PeriodSummary getMonthly(String provider) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return getByPeriod(provider, today.withDayOfMonth(1), today);
    }

    // ── Daily history (for Chart.js) ───────────────────────────────────────

    public List<DailyRow> getDailyHistory(String provider, int days) {
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1).toString();
        return jdbc.query("""
                SELECT usage_date, input_tokens, output_tokens, call_count
                FROM llm_usage
                WHERE provider_name = ? AND usage_date >= ?
                ORDER BY usage_date
                """,
                (rs, i) -> new DailyRow(
                        rs.getString("usage_date"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("call_count")),
                provider, from);
    }

    // ── Record types ───────────────────────────────────────────────────────

    public record PeriodSummary(long inputTokens, long outputTokens, long callCount) {
        public long totalTokens() { return inputTokens + outputTokens; }
    }

    public record DailyRow(String date, long inputTokens, long outputTokens, long callCount) {}
}
