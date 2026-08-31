package com.example.ragagent.repository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * §10.10 — Q&A snapshot for turns promoted by a 👍. Independent of {@code conversation_turns}:
 * it keeps its own {@code question}/{@code answer} copy, so edits never mutate the original turn
 * (the audit record stays immutable) and a row outlives its turn structurally.
 *
 * <p>That independence is <b>not</b> a retention policy. The original §10.10 rule was that a
 * curated row survives thread deletion; §6.25 reversed it — deleting a conversation now retracts
 * the rows it promoted ({@code CuratedQaService.onThreadDeleted}), because an answer still being
 * used as search evidence after its conversation is gone proved more confusing than the shared-
 * knowledge loss the old rule protected, and because turn-level deletion had always retracted
 * ({@code onUnlike}), leaving the two paths inconsistent. Copies still matter: they are what let
 * the row be edited and re-embedded independently of the turn.
 */
@Repository
public class CuratedQaRepository {

    private static final Logger log = LoggerFactory.getLogger(CuratedQaRepository.class);

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** {@code origin} value for a 👍-promoted row (the original §10.10 path). */
    public static final String ORIGIN_LIKE = "like";
    /** {@code origin} value for a row created by admin approval of a user-submitted chunk. */
    public static final String ORIGIN_MANUAL = "manual";

    private static final String COLUMNS =
            "id, source_turn_id, source_user_id, source_thread_id, question, answer, " +
            "status, source_doc_version, created_at, updated_at, embed_status, " +
            "origin, source_submission_id, tags, chunk_count ";

    private final JdbcTemplate jdbc;

    private static final RowMapper<CuratedQa> ROW_MAPPER = (rs, n) -> {
        long turnId = rs.getLong("source_turn_id");
        Long sourceTurnId = rs.wasNull() ? null : turnId;
        long submissionId = rs.getLong("source_submission_id");
        Long sourceSubmissionId = rs.wasNull() ? null : submissionId;
        return new CuratedQa(
                rs.getLong("id"),
                sourceTurnId,
                rs.getString("source_user_id"),
                rs.getString("source_thread_id"),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getString("status"),
                rs.getString("source_doc_version"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("embed_status"),
                rs.getString("origin"),
                sourceSubmissionId,
                rs.getString("tags"),
                rs.getInt("chunk_count"));
    };

    public CuratedQaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fresh-install schema. Legacy databases are brought here by {@link #migrateLegacySchema()}. */
    private static final String CREATE_TABLE_BODY = """
                (
                    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_turn_id        INTEGER,
                    source_user_id        TEXT NOT NULL,
                    source_thread_id      TEXT NOT NULL,
                    question              TEXT NOT NULL,
                    answer                TEXT NOT NULL,
                    status                TEXT NOT NULL DEFAULT 'active',
                    source_doc_version    TEXT,
                    created_at            TEXT NOT NULL,
                    updated_at            TEXT NOT NULL,
                    embed_status          TEXT NOT NULL DEFAULT 'ok',
                    origin                TEXT NOT NULL DEFAULT 'like',
                    source_submission_id  INTEGER,
                    tags                  TEXT,
                    chunk_count           INTEGER NOT NULL DEFAULT 1
                )
            """;

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS curated_qa " + CREATE_TABLE_BODY);
        // Migration: add column for existing databases (this repository predates Flyway management
        // for curated_qa — same defensive pattern as ThreadMetaRepository). Must run BEFORE
        // migrateLegacySchema(), whose INSERT...SELECT references embed_status by name.
        var cols = jdbc.queryForList("PRAGMA table_info(curated_qa)");
        if (cols.stream().noneMatch(c -> "embed_status".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE curated_qa ADD COLUMN embed_status TEXT NOT NULL DEFAULT 'ok'");
        }
        if (cols.stream().noneMatch(c -> "origin".equals(c.get("name")))) {
            migrateLegacySchema();
        }
        // `tags` shipped one release after `origin`, so a database that already went through the
        // rebuild above still lacks it — plain ADD COLUMN suffices (nullable TEXT). Re-reads
        // PRAGMA because migrateLegacySchema() may have just replaced the table.
        if (jdbc.queryForList("PRAGMA table_info(curated_qa)").stream()
                .noneMatch(c -> "tags".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE curated_qa ADD COLUMN tags TEXT");
        }
        // `chunk_count` shipped with 임베딩 분할. Existing rows hold exactly one vector, which is
        // what the DEFAULT 1 encodes — so de-indexing an old row keeps removing the single id it has.
        if (jdbc.queryForList("PRAGMA table_info(curated_qa)").stream()
                .noneMatch(c -> "chunk_count".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE curated_qa ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 1");
        }
        createIndexes();
    }

    /**
     * User-submitted chunks (the 게시판 → admin approval path) have no originating chat turn, so
     * {@code source_turn_id} must be nullable — which SQLite cannot express as an {@code ALTER}.
     * Rebuilds the table once (guarded by the absence of the {@code origin} column) inside a single
     * transaction: a crash mid-rebuild rolls back rather than leaving the table dropped. Existing
     * rows are all {@link #ORIGIN_LIKE} by definition — the manual path didn't exist before this.
     *
     * <p>The {@code UNIQUE(source_turn_id)} constraint that keeps like→unlike→like idempotent
     * ({@link #upsertActive}) becomes a <em>partial</em> index so the many NULLs of manual rows
     * don't collide — see {@link #createIndexes()}.
     */
    private void migrateLegacySchema() {
        jdbc.execute((ConnectionCallback<Void>) con -> {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.executeUpdate("CREATE TABLE curated_qa_new " + CREATE_TABLE_BODY);
                st.executeUpdate("""
                        INSERT INTO curated_qa_new
                            (id, source_turn_id, source_user_id, source_thread_id, question, answer,
                             status, source_doc_version, created_at, updated_at, embed_status,
                             origin, source_submission_id, tags, chunk_count)
                        SELECT id, source_turn_id, source_user_id, source_thread_id, question, answer,
                               status, source_doc_version, created_at, updated_at, embed_status,
                               'like', NULL, NULL, 1
                          FROM curated_qa
                        """);
                st.executeUpdate("DROP TABLE curated_qa");
                st.executeUpdate("ALTER TABLE curated_qa_new RENAME TO curated_qa");
                con.commit();
                log.info("[CURATED] curated_qa 스키마 마이그레이션 완료 (source_turn_id nullable, origin 추가)");
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(autoCommit);
            }
            return null;
        });
    }

    /** Recreated after {@link #migrateLegacySchema()} too — {@code DROP TABLE} takes its indexes. */
    private void createIndexes() {
        // Partial: manual rows all carry source_turn_id IS NULL, and SQLite's plain UNIQUE index
        // would already tolerate them (NULL != NULL), but the WHERE clause states the intent and
        // keeps the index free of the NULL entries entirely.
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_curated_qa_turn " +
                "ON curated_qa(source_turn_id) WHERE source_turn_id IS NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_curated_qa_status ON curated_qa(status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_curated_qa_submission " +
                "ON curated_qa(source_submission_id) WHERE source_submission_id IS NOT NULL");
    }

    /**
     * Upserts an active row keyed by {@code source_turn_id} — a re-like after unlike reactivates
     * and refreshes the existing row instead of accumulating duplicate rows/vectors across
     * like→unlike→like cycles (the vector store's {@code spring_doc_id} is derived from this row's
     * id, so reusing the row keeps re-embedding idempotent). Returns the row id.
     */
    public long upsertActive(long turnId, String userId, String threadId,
                             String question, String answer, String sourceDocVersion, String tags) {
        String now = now();
        int updated = jdbc.update(
                "UPDATE curated_qa SET status='active', question=?, answer=?, " +
                "source_doc_version=?, tags=?, updated_at=? WHERE source_turn_id=?",
                question, answer, sourceDocVersion, tags, now, turnId);
        if (updated > 0) {
            return jdbc.queryForObject(
                    "SELECT id FROM curated_qa WHERE source_turn_id=?", Long.class, turnId);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curated_qa (source_turn_id, source_user_id, source_thread_id, " +
                    "question, answer, status, source_doc_version, created_at, updated_at, tags) " +
                    "VALUES (?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, turnId);
            ps.setString(2, userId);
            ps.setString(3, threadId);
            ps.setString(4, question);
            ps.setString(5, answer);
            ps.setString(6, sourceDocVersion);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setString(9, tags);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    /**
     * Inserts a row for an admin-approved user submission — no originating chat turn, so
     * {@code source_turn_id} stays NULL (the partial UNIQUE index tolerates any number of these)
     * and {@code source_thread_id} is an empty string rather than a fake id. Always an INSERT,
     * never an upsert: {@link CuratedSubmissionRepository} only lets a {@code pending} submission
     * be approved, so a second row for the same submission can't be created by the normal flow.
     * Returns the new row id.
     */
    public long insertManual(long submissionId, String authorUserId, String question, String answer,
                             String tags) {
        String now = now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curated_qa (source_turn_id, source_user_id, source_thread_id, " +
                    "question, answer, status, source_doc_version, created_at, updated_at, " +
                    "origin, source_submission_id, tags) " +
                    "VALUES (NULL, ?, '', ?, ?, 'active', NULL, ?, ?, '" + ORIGIN_MANUAL + "', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, authorUserId);
            ps.setString(2, question);
            ps.setString(3, answer);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setLong(6, submissionId);
            ps.setString(7, tags);
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

    /**
     * Deactivates by the curated row's own id — the only form that works for a manual row, whose
     * {@code source_turn_id} is NULL (a {@code WHERE source_turn_id = NULL} match is never true, so
     * {@link #deactivate(long)} would silently no-op on those).
     */
    public void deactivateById(long id) {
        jdbc.update("UPDATE curated_qa SET status='inactive', updated_at=? WHERE id=?", now(), id);
    }

    public Optional<CuratedQa> findBySourceTurnId(long turnId) {
        List<CuratedQa> rows = jdbc.query(
                "SELECT " + COLUMNS + "FROM curated_qa WHERE source_turn_id = ?", ROW_MAPPER, turnId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Every still-active 👍-promoted row of one chat thread, in creation order — the unit
     * {@code CuratedQaService.onThreadDeleted} takes down when a whole conversation is deleted
     * (§6.25).
     *
     * <p>{@code source_turn_id IS NOT NULL} excludes 청크 추가 (manual) rows <b>structurally</b>.
     * Those currently carry {@code source_thread_id = ''} (see {@link #insertManual}), so a real
     * thread id can never match one today — but a submission is a 전부/전무 unit owned by
     * {@code forceRemoveBySubmission}, and deleting a conversation must never take half of one
     * down. The guard keeps that true even if manual rows ever gain a real thread id.
     *
     * <p>Scoped by {@code source_user_id} as well as the thread: every other delete path in the
     * app is (userId, threadId)-scoped, and matching on the thread alone would make this the one
     * query that could reach across owners if two threads ever shared an id.
     */
    public List<CuratedQa> findActiveByThread(String userId, String threadId) {
        return jdbc.query("SELECT " + COLUMNS +
                        "FROM curated_qa WHERE source_user_id = ? AND source_thread_id = ? " +
                        "AND source_turn_id IS NOT NULL AND status = 'active' ORDER BY id",
                ROW_MAPPER, userId, threadId);
    }

    /** Every still-active chunk of one submission, in creation order — the 전부/전무 unit for
     *  {@code CuratedQaService.forceRemoveBySubmission}. */
    public List<CuratedQa> findActiveBySubmissionId(long submissionId) {
        return jdbc.query("SELECT " + COLUMNS +
                        "FROM curated_qa WHERE source_submission_id = ? AND status = 'active' ORDER BY id",
                ROW_MAPPER, submissionId);
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

    /** Same as {@link #findAllActive(int, int)} with {@code offset=0}. */
    public List<CuratedQa> findAllActive(int limit) {
        return findAllActive(0, limit);
    }

    /** §10.10 step ④ — admin curated-Q&A browser listing, most recently created first, paginated.
     *  Only {@code active} rows (the ones actually contributing to search) — a deactivated entry is
     *  already out of the index and not actionable from this view. {@code id DESC} as a tiebreaker
     *  since {@code created_at} only has second-level precision (two rows upserted within the same
     *  second would otherwise tie). */
    public List<CuratedQa> findAllActive(int offset, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + "FROM curated_qa WHERE status = 'active' " +
                "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, limit, offset);
    }

    /** §10.10 embedding-fallback — marks a row's last embed attempt as failed (both the full-text
     *  and core-sections-only attempts errored out). Surfaced as a badge in chat.html (owner) and
     *  the /admin curated panel. */
    public void markEmbedFailed(long id) {
        jdbc.update("UPDATE curated_qa SET embed_status='failed', updated_at=? WHERE id=?", now(), id);
    }

    /** How many vectors this row currently owns in the store — the ids are
     *  {@code curated-<id>} (first) plus {@code curated-<id>-<i>} for the rest, so the count is what
     *  lets de-index/re-embed find every one of them. */
    public void updateChunkCount(long id, int chunkCount) {
        jdbc.update("UPDATE curated_qa SET chunk_count=? WHERE id=?", Math.max(1, chunkCount), id);
    }

    /** Clears the failed badge after a (re-)embed attempt succeeds. */
    public void markEmbedOk(long id) {
        jdbc.update("UPDATE curated_qa SET embed_status='ok', updated_at=? WHERE id=?", now(), id);
    }

    /** Turn ids (among the given set) whose active curated row is currently stuck in
     *  {@code embed_status='failed'} — chat.html's turn-history render uses this to show a badge
     *  without threading a new column through {@code MemoryRepository.Turn}. */
    public Set<Long> findFailedTurnIds(Collection<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
        List<Long> rows = jdbc.queryForList(
                "SELECT source_turn_id FROM curated_qa WHERE status='active' AND embed_status='failed' " +
                "AND source_turn_id IN (" + placeholders + ")",
                Long.class, turnIds.toArray());
        return new HashSet<>(rows);
    }

    /**
     * Distinct tags used by active curated rows (comma-joined column → flattened, lowercased).
     * Unioned into the tag-suggestion list because curated entries are <b>not</b> indexed into
     * {@code chunk_fts} (they live on the vector axis only), so a tag that only ever appeared on a
     * user submission would otherwise be invisible to the next author and the vocabulary would split.
     */
    public List<String> distinctActiveTags() {
        List<String> rows = jdbc.queryForList(
                "SELECT DISTINCT tags FROM curated_qa WHERE status='active' AND tags IS NOT NULL AND tags <> ''",
                String.class);
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String row : rows) {
            for (String t : row.split(",")) {
                String s = t.strip().toLowerCase(java.util.Locale.ROOT);
                if (!s.isEmpty()) out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Answers of active rows that still reference a 지식 제안 본문 이미지 — the curated half of
     * {@code CuratedImageStore}'s reference check (an approved submission's text lives here, not
     * only on the submission row). The {@code LIKE} is a pre-filter; the caller re-scans with the
     * real path pattern.
     */
    public List<String> activeAnswersWithImages() {
        return jdbc.queryForList(
                "SELECT answer FROM curated_qa WHERE status = 'active' " +
                "AND answer LIKE '%images/submissions/%'",
                String.class);
    }

    private static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }

    /**
     * {@code sourceTurnId}/{@code sourceSubmissionId} are mutually exclusive and either may be
     * null: a 👍-promoted row has a turn id, an admin-approved user submission has a submission id.
     * {@code origin} says which ({@link #ORIGIN_LIKE} / {@link #ORIGIN_MANUAL}).
     */
    public record CuratedQa(long id, Long sourceTurnId, String sourceUserId, String sourceThreadId,
                            String question, String answer, String status, String sourceDocVersion,
                            String createdAt, String updatedAt, String embedStatus,
                            String origin, Long sourceSubmissionId, String tags, int chunkCount) {

        public boolean isManual() { return ORIGIN_MANUAL.equals(origin); }
    }
}
