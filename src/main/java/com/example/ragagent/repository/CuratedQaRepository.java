package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * §10.10 — Q&A snapshot for turns promoted by a 👍. Independent of {@code conversation_turns}:
 * stores its own {@code question}/{@code answer} copy so it survives thread deletion (see
 * documents/PLAN.md §10.10 "연쇄 삭제 정책") and edits never mutate the original turn (audit
 * record stays immutable).
 */
@Repository
public class CuratedQaRepository {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String COLUMNS =
            "id, source_turn_id, source_user_id, source_thread_id, question, answer, " +
            "status, source_doc_version, created_at, updated_at ";

    private final JdbcTemplate jdbc;

    private static final RowMapper<CuratedQa> ROW_MAPPER = (rs, n) -> new CuratedQa(
            rs.getLong("id"),
            rs.getLong("source_turn_id"),
            rs.getString("source_user_id"),
            rs.getString("source_thread_id"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getString("status"),
            rs.getString("source_doc_version"),
            rs.getString("created_at"),
            rs.getString("updated_at"));

    public CuratedQaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS curated_qa (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_turn_id      INTEGER NOT NULL,
                    source_user_id      TEXT NOT NULL,
                    source_thread_id    TEXT NOT NULL,
                    question            TEXT NOT NULL,
                    answer              TEXT NOT NULL,
                    status              TEXT NOT NULL DEFAULT 'active',
                    source_doc_version  TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_curated_qa_turn ON curated_qa(source_turn_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_curated_qa_status ON curated_qa(status)");
    }

    /**
     * Upserts an active row keyed by {@code source_turn_id} — a re-like after unlike reactivates
     * and refreshes the existing row instead of accumulating duplicate rows/vectors across
     * like→unlike→like cycles (the vector store's {@code spring_doc_id} is derived from this row's
     * id, so reusing the row keeps re-embedding idempotent). Returns the row id.
     */
    public long upsertActive(long turnId, String userId, String threadId,
                             String question, String answer, String sourceDocVersion) {
        String now = now();
        int updated = jdbc.update(
                "UPDATE curated_qa SET status='active', question=?, answer=?, " +
                "source_doc_version=?, updated_at=? WHERE source_turn_id=?",
                question, answer, sourceDocVersion, now, turnId);
        if (updated > 0) {
            return jdbc.queryForObject(
                    "SELECT id FROM curated_qa WHERE source_turn_id=?", Long.class, turnId);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curated_qa (source_turn_id, source_user_id, source_thread_id, " +
                    "question, answer, status, source_doc_version, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, turnId);
            ps.setString(2, userId);
            ps.setString(3, threadId);
            ps.setString(4, question);
            ps.setString(5, answer);
            ps.setString(6, sourceDocVersion);
            ps.setString(7, now);
            ps.setString(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    /** No-op if no row exists for this turn (never embedded — nothing to deactivate). */
    public void deactivate(long turnId) {
        jdbc.update("UPDATE curated_qa SET status='inactive', updated_at=? WHERE source_turn_id=?",
                now(), turnId);
    }

    public Optional<CuratedQa> findBySourceTurnId(long turnId) {
        List<CuratedQa> rows = jdbc.query(
                "SELECT " + COLUMNS + "FROM curated_qa WHERE source_turn_id = ?", ROW_MAPPER, turnId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CuratedQa> findById(long id) {
        List<CuratedQa> rows = jdbc.query(
                "SELECT " + COLUMNS + "FROM curated_qa WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** §10.10 step ④ — updates the answer text only (question/status/version untouched). No-op
     *  if the row doesn't exist. Callers re-embed separately (this is a pure storage write). */
    public void updateAnswer(long id, String answer) {
        jdbc.update("UPDATE curated_qa SET answer=?, updated_at=? WHERE id=?", answer, now(), id);
    }

    /** §10.10 step ④ — admin curated-Q&A browser listing, most recently created first. Only
     *  {@code active} rows (the ones actually contributing to search) — a deactivated entry is
     *  already out of the index and not actionable from this view. {@code id DESC} as a tiebreaker
     *  since {@code created_at} only has second-level precision (two rows upserted within the same
     *  second would otherwise tie). */
    public List<CuratedQa> findAllActive(int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + "FROM curated_qa WHERE status = 'active' " +
                "ORDER BY created_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, limit);
    }

    private static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }

    public record CuratedQa(long id, long sourceTurnId, String sourceUserId, String sourceThreadId,
                            String question, String answer, String status, String sourceDocVersion,
                            String createdAt, String updatedAt) {}
}
