package com.example.ragagent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * §6.25 — the read side of the {@code /admin} 대화 목록 panel: every conversation in the
 * deployment with the counters an operator needs to decide what to look at and what to delete.
 *
 * <p><b>Deliberately not user-scoped.</b> Every method on {@link ThreadMetaRepository} takes a
 * {@code userId} and filters by it, and that is an invariant worth keeping legible — a
 * cross-user query mixed in there would quietly break "this class can only ever see one user's
 * threads". So the operator view lives in its own class, gated by {@code /admin/**}'s ROLE_ADMIN
 * like {@code MemoryRepository.findRecentRetrievalMetrics}, which made the same call for the same
 * reason.
 *
 * <p>Read-only: this class owns no DDL. {@code thread_meta} is created by
 * {@link ThreadMetaRepository} and {@code conversation_turns} by {@link SqliteMemoryRepository};
 * both live in {@code memory.db} on the primary {@code JdbcTemplate}, which is what lets the
 * aggregate below be one join rather than two round-trips stitched in Java.
 */
@Repository
public class ThreadAdminRepository {

    /**
     * One conversation in the admin list.
     *
     * <p>Two different reuse counters, because they answer different questions and only one of
     * them belongs next to a delete button:
     * <ul>
     *   <li>{@code reusedIn} — turns <em>in</em> this conversation that were answered by reusing
     *       an older answer. "How much of this conversation was free."</li>
     *   <li>{@code reusedOut} — turns anywhere that reused an answer <em>from</em> this
     *       conversation. "How much other work rests on it" — deleting a thread with a high
     *       count drops every one of those turns to the {@code 참조 원문 삭제됨} fallback.</li>
     * </ul>
     *
     * @param lastAskedAt {@code MAX(asked_at)} — the real last-message time, <b>UTC</b>, unlike
     *                    {@code updatedAt} which {@link ThreadMetaRepository#now()} writes in
     *                    system-local time. The two must never be mixed in one column or sorted
     *                    against each other; see PLAN §6.25 결정 2. Null when the thread has no
     *                    turns at all.
     * @param diagCount   turns carrying retrieval diagnostics. Reuse/Direct/meta turns run no
     *                    search, so a large {@code turnCount - diagCount} gap is itself the
     *                    signal that this conversation mostly did not retrieve anything.
     */
    public record ThreadRow(String threadId, String userId, String title, String tags,
                            String createdAt, String updatedAt, String lastAskedAt,
                            int turnCount, int reusedIn, int reusedOut, int diagCount,
                            int likeCount, int dislikeCount) {}

    /** Deployment-wide totals for the panel's summary strip. */
    public record Summary(int threadCount, int userCount, int turnCount, int reusedTurnCount,
                          int orphanTurnCount) {}

    /**
     * Sort orders the panel offers. An enum rather than a string because the value is concatenated
     * into SQL — {@code ORDER BY} cannot be parameterized, so the only safe form is a closed set
     * chosen in code.
     */
    public enum Sort {
        /** Most recent activity first — the same order the chat sidebar uses. */
        RECENT("m.updated_at DESC"),
        TURNS("turn_count DESC, m.updated_at DESC"),
        REUSED("reused_out DESC, m.updated_at DESC");

        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }

        /** Every order ends with the primary key so pagination can't drop or repeat a row when
         *  the leading key ties (common: many threads with the same turn count, or 0 reuse). */
        String sql() {
            return orderBy + ", m.thread_id";
        }

        public static Sort parse(String raw) {
            if (raw == null) return RECENT;
            for (Sort s : values()) {
                if (s.name().equalsIgnoreCase(raw.strip())) return s;
            }
            return RECENT;
        }
    }

    private final JdbcTemplate jdbc;

    public ThreadAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code reusedOut} as a correlated subquery rather than a second join: joining
     * {@code conversation_turns} twice would multiply the row count the outer aggregate is
     * counting and silently inflate every other counter here. Backed by
     * {@code idx_turns_reused_from}.
     *
     * <p>A turn that reused an answer from its own thread counts too — the question is "was this
     * answer reused", not "was it reused elsewhere".
     */
    private static final String REUSED_OUT_SUBQUERY = """
            (SELECT COUNT(*) FROM conversation_turns r
               JOIN conversation_turns src ON src.id = r.reused_from_turn_id
              WHERE src.thread_id = m.thread_id AND src.user_id = m.user_id)""";

    private static final String LIST_SQL = """
            SELECT m.thread_id, m.user_id, m.title, m.tags, m.created_at, m.updated_at,
                   COUNT(t.id)                                                        AS turn_count,
                   SUM(CASE WHEN t.reused_from_turn_id IS NOT NULL THEN 1 ELSE 0 END) AS reused_in,
                   SUM(CASE WHEN t.retrieval_metrics   IS NOT NULL THEN 1 ELSE 0 END) AS diag_count,
                   SUM(CASE WHEN t.feedback = 'LIKE'    THEN 1 ELSE 0 END)            AS like_count,
                   SUM(CASE WHEN t.feedback = 'DISLIKE' THEN 1 ELSE 0 END)            AS dislike_count,
                   MAX(t.asked_at)                                                    AS last_asked_at,
                   %s                                                                 AS reused_out
              FROM thread_meta m
              LEFT JOIN conversation_turns t
                     ON t.thread_id = m.thread_id AND t.user_id = m.user_id
             %s
             GROUP BY m.thread_id
             ORDER BY %s
             LIMIT ? OFFSET ?""";

    /**
     * One page of conversations.
     *
     * @param userId optional owner filter; null/blank means every user
     */
    public List<ThreadRow> findAll(String userId, Sort sort, int offset, int limit) {
        boolean byUser = userId != null && !userId.isBlank();
        String sql = LIST_SQL.formatted(
                REUSED_OUT_SUBQUERY,
                byUser ? "WHERE m.user_id = ?" : "",
                (sort == null ? Sort.RECENT : sort).sql());

        Object[] args = byUser
                ? new Object[]{userId, limit, offset}
                : new Object[]{limit, offset};

        return jdbc.query(sql, (rs, n) -> new ThreadRow(
                rs.getString("thread_id"),
                rs.getString("user_id"),
                rs.getString("title"),
                rs.getString("tags"),
                rs.getString("created_at"),
                rs.getString("updated_at"),
                rs.getString("last_asked_at"),
                rs.getInt("turn_count"),
                rs.getInt("reused_in"),
                rs.getInt("reused_out"),
                rs.getInt("diag_count"),
                rs.getInt("like_count"),
                rs.getInt("dislike_count")), args);
    }

    /** Total conversations matching the same filter {@link #findAll} was given — the panel's
     *  "전체 N개" badge must move with the list or it reports on a set the operator isn't seeing. */
    public int count(String userId) {
        boolean byUser = userId != null && !userId.isBlank();
        Integer n = byUser
                ? jdbc.queryForObject("SELECT COUNT(*) FROM thread_meta WHERE user_id = ?",
                        Integer.class, userId)
                : jdbc.queryForObject("SELECT COUNT(*) FROM thread_meta", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Deployment-wide totals. Unfiltered on purpose — the strip describes the deployment, so it
     * stays put while the operator filters the list underneath it.
     */
    public Summary summary() {
        Summary threads = jdbc.queryForObject(
                "SELECT COUNT(*) AS c, COUNT(DISTINCT user_id) AS u FROM thread_meta",
                (rs, n) -> new Summary(rs.getInt("c"), rs.getInt("u"), 0, 0, 0));
        Summary turns = jdbc.queryForObject("""
                SELECT COUNT(*) AS c,
                       SUM(CASE WHEN reused_from_turn_id IS NOT NULL THEN 1 ELSE 0 END) AS r
                  FROM conversation_turns""",
                (rs, n) -> new Summary(0, 0, rs.getInt("c"), rs.getInt("r"), 0));
        return new Summary(
                threads == null ? 0 : threads.threadCount(),
                threads == null ? 0 : threads.userCount(),
                turns == null ? 0 : turns.turnCount(),
                turns == null ? 0 : turns.reusedTurnCount(),
                countOrphanTurns());
    }

    /**
     * Turns whose conversation has no {@code thread_meta} row — invisible to {@link #findAll},
     * which starts from {@code thread_meta}. Surfaced as a number only: this is a consistency
     * readout in the spirit of {@code /admin/registry/reconcile-chunks}, not a feature, and a
     * non-zero value means a delete or a write went half-finished somewhere.
     */
    public int countOrphanTurns() {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_turns t
                 WHERE NOT EXISTS (SELECT 1 FROM thread_meta m
                                    WHERE m.thread_id = t.thread_id AND m.user_id = t.user_id)""",
                Integer.class);
        return n == null ? 0 : n;
    }

    /** Owners that actually have conversations — the panel's filter dropdown. */
    public List<String> distinctUserIds() {
        return jdbc.queryForList(
                "SELECT DISTINCT user_id FROM thread_meta ORDER BY user_id", String.class);
    }

    /**
     * The owner of a conversation, looked up by thread id alone.
     *
     * <p>This is what lets the admin delete endpoint take only a {@code threadId}:
     * {@code thread_meta.thread_id} is the primary key, so the server can resolve the owner
     * itself instead of accepting a {@code userId} from the client — a parameter that would let a
     * caller name whose conversation to act on.
     */
    public Optional<String> findOwner(String threadId) {
        List<String> owners = jdbc.queryForList(
                "SELECT user_id FROM thread_meta WHERE thread_id = ?", String.class, threadId);
        return owners.isEmpty() ? Optional.empty() : Optional.of(owners.get(0));
    }
}
