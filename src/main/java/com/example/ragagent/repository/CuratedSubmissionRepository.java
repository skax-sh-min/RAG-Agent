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
 * 청크 추가 게시판 — user-submitted chunks awaiting an admin's "임베딩 실행" or "거부".
 *
 * <p>Deliberately a separate table from {@code curated_qa} rather than an extra status there:
 * {@code curated_qa.status='active'} means "currently contributing to search", an invariant the
 * retrieval/moderation code already relies on, and a pending submission is by definition not that.
 * Approval copies the (possibly admin-edited) text into a real {@code curated_qa} row and links it
 * back via {@link #markApproved}.
 */
@Repository
public class CuratedSubmissionRepository {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String STATUS_PENDING   = "pending";
    public static final String STATUS_APPROVED  = "approved";
    public static final String STATUS_REJECTED  = "rejected";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    /**
     * Not a stored status — derived at read time from the linked {@code curated_qa} row (see
     * {@link Submission#displayStatus()}). An admin removing an approved entry from the curated
     * panel deactivates the vector row; deriving "revoked" from that avoids a write-side hook back
     * into this repository (and the service cycle that would come with it).
     */
    public static final String STATUS_REVOKED = "revoked";

    /**
     * The linked curated rows' aggregate state travels with every read, so the caller can show
     * "검색에 반영됨" / "임베딩 실패" / "회수됨" without a second query.
     *
     * <p>A submission maps to <b>N</b> curated rows, not one — an over-long body is split into
     * chunks at approval time ({@code CuratedSubmissionService.approve}). So the join is a
     * correlated aggregate over {@code source_submission_id} rather than a lookup of the single
     * {@code curated_qa_id} FK (which is kept only as the "first chunk" pointer for the admin
     * edit panel). Counting active/failed rows separately is what lets
     * {@link Submission#displayStatus()} enforce the 전부/전무 rule.
     */
    private static final String SELECT_BASE = """
            SELECT s.id, s.author_user_id, s.title, s.body, s.status, s.reviewer_user_id,
                   s.review_note, s.curated_qa_id, s.created_at, s.updated_at, s.reviewed_at,
                   s.author_read_at, s.tags,
                   (SELECT COUNT(*) FROM curated_qa c
                     WHERE c.source_submission_id = s.id) AS curated_total,
                   (SELECT COUNT(*) FROM curated_qa c
                     WHERE c.source_submission_id = s.id AND c.status = 'active') AS curated_active,
                   (SELECT COUNT(*) FROM curated_qa c
                     WHERE c.source_submission_id = s.id AND c.status = 'active'
                       AND c.embed_status = 'failed') AS curated_failed
              FROM curated_submission s
            """;

    private final JdbcTemplate jdbc;

    private static final RowMapper<Submission> ROW_MAPPER = (rs, n) -> {
        long curatedId = rs.getLong("curated_qa_id");
        Long curatedQaId = rs.wasNull() ? null : curatedId;
        return new Submission(
                rs.getLong("id"),
                rs.getString("author_user_id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("status"),
                rs.getString("reviewer_user_id"),
                rs.getString("review_note"),
                curatedQaId,
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("reviewed_at"),
                rs.getString("author_read_at"),
                rs.getString("tags"),
                rs.getInt("curated_total"),
                rs.getInt("curated_active"),
                rs.getInt("curated_failed"));
    };

    public CuratedSubmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS curated_submission (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    author_user_id    TEXT NOT NULL,
                    title             TEXT NOT NULL,
                    body              TEXT NOT NULL,
                    status            TEXT NOT NULL DEFAULT 'pending',
                    reviewer_user_id  TEXT,
                    review_note       TEXT,
                    curated_qa_id     INTEGER,
                    created_at        TEXT NOT NULL,
                    updated_at        TEXT NOT NULL,
                    reviewed_at       TEXT,
                    author_read_at    TEXT,
                    tags              TEXT
                )
                """);
        // `tags` shipped after the initial table — plain ADD COLUMN (nullable TEXT).
        if (jdbc.queryForList("PRAGMA table_info(curated_submission)").stream()
                .noneMatch(c -> "tags".equals(c.get("name")))) {
            jdbc.execute("ALTER TABLE curated_submission ADD COLUMN tags TEXT");
        }
        // (status, id DESC) — the admin panel's default "pending, newest first" listing.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_curated_sub_status " +
                "ON curated_submission(status, id DESC)");
        // (author_user_id, id DESC) — "내 제안" listing + the unread-badge count.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_curated_sub_author " +
                "ON curated_submission(author_user_id, id DESC)");
    }

    /** Returns the new submission id. Always starts {@code pending}. {@code tags} is the
     *  comma-joined search scope the author picked (nullable). */
    public long insert(String authorUserId, String title, String body, String tags) {
        String now = now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curated_submission (author_user_id, title, body, status, " +
                    "created_at, updated_at, tags) VALUES (?, ?, ?, '" + STATUS_PENDING + "', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, authorUserId);
            ps.setString(2, title);
            ps.setString(3, body);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, tags);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    public Optional<Submission> findById(long id) {
        List<Submission> rows = jdbc.query(SELECT_BASE + " WHERE s.id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Admin listing. {@code status} null/blank = every status. */
    public List<Submission> findByStatus(String status, int offset, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query(SELECT_BASE + " ORDER BY s.id DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER, limit, offset);
        }
        return jdbc.query(SELECT_BASE + " WHERE s.status = ? ORDER BY s.id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, status, limit, offset);
    }

    /** "내 제안" listing — every status, since the author needs to see rejections too. */
    public List<Submission> findByAuthor(String authorUserId, int offset, int limit) {
        return jdbc.query(SELECT_BASE + " WHERE s.author_user_id = ? ORDER BY s.id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, authorUserId, limit, offset);
    }

    /** Drives the admin header badge. */
    public int countPending() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curated_submission WHERE status = ?", Integer.class, STATUS_PENDING);
        return n != null ? n : 0;
    }

    /**
     * Drives the author's header badge: submissions this user has had reviewed (either way) but
     * hasn't opened the list since. Cleared by {@link #markAllReadForAuthor}.
     */
    public int countUnreviewedNotificationsForAuthor(String authorUserId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curated_submission WHERE author_user_id = ? " +
                "AND status IN (?, ?) AND author_read_at IS NULL",
                Integer.class, authorUserId, STATUS_APPROVED, STATUS_REJECTED);
        return n != null ? n : 0;
    }

    /** Stamped when the author opens "내 제안" — only touches rows that were actually reviewed. */
    public void markAllReadForAuthor(String authorUserId) {
        jdbc.update("UPDATE curated_submission SET author_read_at = ? " +
                    "WHERE author_user_id = ? AND status IN (?, ?) AND author_read_at IS NULL",
                now(), authorUserId, STATUS_APPROVED, STATUS_REJECTED);
    }

    /**
     * pending → approved, linking the created curated row. {@code author_read_at} is left NULL so
     * the author's badge lights up. The {@code status = 'pending'} guard makes this a compare-and-set:
     * two admins clicking 임베딩 실행 at once produce one approval, not two curated rows (the loser
     * gets {@code false} back and its already-created curated row is rolled back by the caller).
     */
    public boolean markApproved(long id, String reviewerUserId, String title, String body,
                                String tags, long firstCuratedQaId) {
        String now = now();
        return jdbc.update(
                "UPDATE curated_submission SET status = ?, reviewer_user_id = ?, title = ?, body = ?, " +
                "tags = ?, curated_qa_id = ?, reviewed_at = ?, updated_at = ?, author_read_at = NULL " +
                "WHERE id = ? AND status = ?",
                STATUS_APPROVED, reviewerUserId, title, body, tags, firstCuratedQaId, now, now,
                id, STATUS_PENDING) > 0;
    }

    /** pending → rejected. Same compare-and-set guard as {@link #markApproved}. */
    public boolean markRejected(long id, String reviewerUserId, String reviewNote) {
        String now = now();
        return jdbc.update(
                "UPDATE curated_submission SET status = ?, reviewer_user_id = ?, review_note = ?, " +
                "reviewed_at = ?, updated_at = ?, author_read_at = NULL WHERE id = ? AND status = ?",
                STATUS_REJECTED, reviewerUserId, reviewNote, now, now, id, STATUS_PENDING) > 0;
    }

    /** pending → withdrawn, scoped to the author so one user can't withdraw another's submission. */
    public boolean markWithdrawn(long id, String authorUserId) {
        return jdbc.update(
                "UPDATE curated_submission SET status = ?, updated_at = ? " +
                "WHERE id = ? AND author_user_id = ? AND status = ?",
                STATUS_WITHDRAWN, now(), id, authorUserId, STATUS_PENDING) > 0;
    }

    /** Anti-flood guard — see {@code CuratedSubmissionService.MAX_PENDING_PER_USER}. */
    public int countPendingByAuthor(String authorUserId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curated_submission WHERE author_user_id = ? AND status = ?",
                Integer.class, authorUserId, STATUS_PENDING);
        return n != null ? n : 0;
    }

    private static String now() {
        return LocalDateTime.now().format(DT_FMT);
    }

    /**
     * @param curatedQaId    first chunk's curated row id — the admin edit panel's entry point.
     *                       Prefer the counts below for status; this is a pointer, not the whole set.
     * @param curatedTotal   curated rows created for this submission (chunks), 0 until approved
     * @param curatedActive  how many of those are still contributing to search
     * @param curatedFailed  how many active ones are stuck in {@code embed_status='failed'}
     */
    public record Submission(long id, String authorUserId, String title, String body,
                             String status, String reviewerUserId, String reviewNote,
                             Long curatedQaId, String createdAt, String updatedAt,
                             String reviewedAt, String authorReadAt, String tags,
                             int curatedTotal, int curatedActive, int curatedFailed) {

        /**
         * The status to show. Everything is the stored value except an approved submission whose
         * curated rows are no longer active — an admin removed it from the curated panel afterwards,
         * which the author should see as 회수됨 rather than as a still-live 등록 완료.
         *
         * <p><b>전부/전무</b>: an approved submission is 등록 완료 while <em>any</em> of its chunks is
         * still active, and 회수됨 once none are. There is deliberately no "N개 중 M개" partial state
         * — {@code CuratedQaService.forceRemove} takes down every chunk of a submission together, so
         * a partial set can only arise from direct DB surgery, and reporting a half-registered
         * proposal to its author would be more confusing than useful.
         */
        public String displayStatus() {
            if (STATUS_APPROVED.equals(status) && curatedTotal > 0 && curatedActive == 0) {
                return STATUS_REVOKED;
            }
            return status;
        }

        /** Approved and indexed, but at least one chunk's embedding failed — needs a retry. */
        public boolean embedFailed() {
            return STATUS_APPROVED.equals(displayStatus()) && curatedFailed > 0;
        }

        /** How many chunks this submission was split into (0 until approved). */
        public int chunkCount() {
            return curatedTotal;
        }

        public boolean isPending()  { return STATUS_PENDING.equals(status); }

        /** Short one-line preview for list views. */
        public String bodyPreview() {
            if (body == null) return "";
            String flat = body.replaceAll("\\s+", " ").trim();
            return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
        }
    }
}
