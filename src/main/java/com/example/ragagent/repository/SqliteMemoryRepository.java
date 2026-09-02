package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqliteMemoryRepository implements MemoryRepository {

    private final JdbcTemplate jdbc;
    private static final String DELETED_REFERENCE_TEXT = "참조 원문 삭제됨";
    // fetch at most this many recent turns before applying char truncation (§6.11: app.memory.*)
    private final int fetchLimit;

    private static final RowMapper<Turn> TURN_ROW_MAPPER = (rs, n) -> new Turn(
            rs.getLong("id"),
            rs.getString("question"),
            rs.getString("answer"),
            rs.getString("asked_at"),
            rs.getString("created_at"),
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("elapsed_ms"),
            rs.getString("provider"),
            rs.getInt("llm_calls"),
            rs.getString("feedback"),
            rs.getString("response_mode"),
            rs.getString("selected_tags"),
            rs.getInt("direct_mode") != 0);

    public SqliteMemoryRepository(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.fetchLimit = props.memorySafe().fetchLimitTurns();
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
        // Add metadata columns (ALTER TABLE fails silently if column already exists)
        for (String ddl : List.of(
                "ALTER TABLE conversation_turns ADD COLUMN asked_at TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN input_tokens INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN output_tokens INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN elapsed_ms INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN provider TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN llm_calls INTEGER DEFAULT 0",
                "ALTER TABLE conversation_turns ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous'",
                "ALTER TABLE conversation_turns ADD COLUMN feedback TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN response_mode TEXT",
                "ALTER TABLE conversation_turns ADD COLUMN selected_tags TEXT",
            "ALTER TABLE conversation_turns ADD COLUMN reused_from_turn_id INTEGER",
            "ALTER TABLE conversation_turns ADD COLUMN direct_mode INTEGER NOT NULL DEFAULT 0",
            // 3단계 — 그 턴의 출처별 검색 진단 수치 + 응답 참여도를 JSON 배열로 보관한다.
            // 정규화 테이블 대신 blob 하나인 이유: 읽는 쪽이 /admin 진단 패널 하나뿐이고 항상
            // "턴 하나의 출처 전부"를 통째로 꺼내므로 조인할 이유가 없다. 스키마도 SourceRef를
            // 따라가야 하는데(필드가 늘어날 수 있다) 컬럼으로 고정하면 그때마다 마이그레이션이다.
            "ALTER TABLE conversation_turns ADD COLUMN retrieval_metrics TEXT",
            // 답변 검증 결과(VerificationSnapshot) JSON — 대화 기록의 검증 배지가 새로고침 후에도
            // 남으려면 저장돼 있어야 한다. NULL 은 "검증 기록 없음"이고, 이 컬럼 이전의 모든
            // 턴과 meta/Direct·S 턴이 그렇다 — 그 경우 배지를 띄우지 않는 예전 동작 그대로다.
            "ALTER TABLE conversation_turns ADD COLUMN verification TEXT"
        )) {
            try { jdbc.execute(ddl); } catch (Exception ignored) {}
        }
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_turns_user_thread ON conversation_turns(user_id, thread_id)");
            jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_turns_reused_from ON conversation_turns(reused_from_turn_id)");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS turn_image_ref (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    turn_id    INTEGER NOT NULL,
                    user_id    TEXT NOT NULL,
                    thread_id  TEXT NOT NULL,
                    image_ref  TEXT NOT NULL,
                    status     TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_image_turn ON turn_image_ref(turn_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_image_user_thread ON turn_image_ref(user_id, thread_id)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS image_descriptions (
                    image_path  TEXT    PRIMARY KEY,
                    description TEXT    NOT NULL,
                    image_type  TEXT,
                    provider    TEXT,
                    created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
                )
                """);
        try {
            jdbc.execute("ALTER TABLE image_descriptions ADD COLUMN user_id TEXT NOT NULL DEFAULT 'anonymous'");
        } catch (Exception ignored) {}
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_img_user ON image_descriptions(user_id)");
    }

    @Override
    public String getHistory(String userId, String threadId, int maxChars) {
        // fetch last fetchLimit turns newest-first, then reverse for chronological order.
        // DISLIKE-tagged turns are excluded from context (hard exclusion, §6.9).
        List<String> rows = jdbc.query(
            "SELECT t.question AS question, COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE t.user_id = ? AND t.thread_id = ? AND (t.feedback IS NULL OR t.feedback <> 'DISLIKE') " +
            "ORDER BY t.id DESC LIMIT ?",
            (rs, n) -> "Q: %s\nA: %s".formatted(rs.getString("question"), rs.getString("answer")),
                userId, threadId, fetchLimit);

        if (rows.isEmpty()) return "";

        // reverse to chronological order (oldest first)
        List<String> entries = new ArrayList<>(rows.reversed());

        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (sb.length() + entry.length() > maxChars) break;
            sb.insert(0, entry + "\n\n");
        }
        // single turn larger than budget → include it truncated rather than returning empty
        if (sb.isEmpty() && !entries.isEmpty()) {
            String newest = entries.get(entries.size() - 1);
            sb.append(newest, 0, Math.min(newest.length(), maxChars))
              .append("\n[이전 대화 일부 생략]");
        }
        return sb.toString().strip();
    }

    @Override
    public long addTurn(String userId, String threadId, String question, String answer,
                        String askedAt, int inputTokens, int outputTokens,
                        int elapsedMs, String provider, int llmCalls, String responseMode,
                        String selectedTags, boolean directMode, Long reusedFromTurnId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO conversation_turns " +
                    "(user_id, thread_id, question, answer, asked_at, input_tokens, output_tokens, elapsed_ms, provider, llm_calls, response_mode, selected_tags, direct_mode, reused_from_turn_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, userId);
            ps.setString(2, threadId);
            ps.setString(3, question);
            ps.setString(4, answer);
            ps.setString(5, askedAt);
            ps.setInt(6, inputTokens);
            ps.setInt(7, outputTokens);
            ps.setInt(8, elapsedMs);
            ps.setString(9, provider);
            ps.setInt(10, llmCalls);
            ps.setString(11, responseMode);
            ps.setString(12, selectedTags);
            ps.setInt(13, directMode ? 1 : 0);
            if (reusedFromTurnId == null) {
                ps.setNull(14, java.sql.Types.BIGINT);
            } else {
                ps.setLong(14, reusedFromTurnId);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key != null ? key.longValue() : -1L;
    }

    @Override
    public void clearHistory(String userId, String threadId) {
        try {
            jdbc.update("DELETE FROM turn_source_ref WHERE user_id = ? AND thread_id = ?", userId, threadId);
        } catch (DataAccessException e) {
            if (!isMissingTurnSourceRef(e)) throw e;
        }
        jdbc.update("DELETE FROM turn_image_ref WHERE user_id = ? AND thread_id = ?", userId, threadId);
        jdbc.update("DELETE FROM conversation_turns WHERE user_id = ? AND thread_id = ?", userId, threadId);
    }

    @Override
    public boolean deleteTurn(String userId, String threadId, long turnId) {
        // Same table set and the same order as clearHistory(), narrowed to one turn. The
        // conversation_turns row goes last so a failure part-way through can only leave orphaned
        // child rows (invisible to every read path, all of which start from conversation_turns),
        // never a turn whose sources have silently vanished.
        try {
            jdbc.update("DELETE FROM turn_source_ref WHERE user_id = ? AND thread_id = ? AND turn_id = ?",
                    userId, threadId, turnId);
        } catch (DataAccessException e) {
            if (!isMissingTurnSourceRef(e)) throw e;
        }
        jdbc.update("DELETE FROM turn_image_ref WHERE user_id = ? AND thread_id = ? AND turn_id = ?",
                userId, threadId, turnId);
        int removed = jdbc.update(
                "DELETE FROM conversation_turns WHERE user_id = ? AND thread_id = ? AND id = ?",
                userId, threadId, turnId);
        return removed > 0;
    }

    /**
     * {@code turn_source_ref} belongs to {@code QuestionReuseRepository} (§6.23 runtime DDL), not to
     * this repository. A context that never ran that init — an isolated repository test — has no such
     * table, and then there is nothing to delete either; anything else must still surface.
     *
     * <p><b>Catch {@link DataAccessException}, not {@code BadSqlGrammarException}.</b> Spring ships no
     * error-code mapping for SQLite, so a missing table arrives as a bare {@code SQLITE_ERROR}
     * (code 1) and is translated to {@code UncategorizedSQLException}. This guard originally caught
     * only {@code BadSqlGrammarException}, so it never actually applied and the four {@code deleteTurn}
     * tests failed on the very case the guard was written for.
     *
     * <p>The message is read off {@code getMostSpecificCause()} — the wrapper's own text varies with
     * the translation path, the underlying driver's does not.
     */
    private static boolean isMissingTurnSourceRef(DataAccessException e) {
        String msg = e.getMostSpecificCause().getMessage();
        return msg != null && msg.contains("no such table") && msg.contains("turn_source_ref");
    }


    @Override
    public void saveTurnImageRefs(long turnId, String userId, String threadId, List<String> imageRefs) {
        if (turnId <= 0 || imageRefs == null || imageRefs.isEmpty()) return;
        List<String> rows = imageRefs.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (rows.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO turn_image_ref (turn_id, user_id, thread_id, image_ref, status) VALUES (?, ?, ?, ?, 'active')",
                rows,
                rows.size(),
                (ps, ref) -> {
                    ps.setLong(1, turnId);
                    ps.setString(2, userId);
                    ps.setString(3, threadId);
                    ps.setString(4, ref);
                }
        );
    }

    @Override
    public Map<Long, List<String>> getTurnImageRefs(String userId, String threadId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT turn_id, image_ref FROM turn_image_ref " +
                "WHERE user_id = ? AND thread_id = ? AND status = 'active' ORDER BY id ASC",
                userId, threadId);
        Map<Long, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Number turn = (Number) row.get("turn_id");
            if (turn == null) continue;
            String ref = String.valueOf(row.getOrDefault("image_ref", "")).strip();
            if (ref.isBlank()) continue;
            out.computeIfAbsent(turn.longValue(), k -> new ArrayList<>()).add(ref);
        }
        return out;
    }

    @Override
    public void excludeTurnImageRef(String userId, String threadId, long turnId, String imageRef) {
        if (turnId <= 0 || imageRef == null || imageRef.isBlank()) return;
        jdbc.update("UPDATE turn_image_ref SET status='inactive' " +
                        "WHERE user_id = ? AND thread_id = ? AND turn_id = ? AND image_ref = ? AND status = 'active'",
                userId, threadId, turnId, imageRef.strip());
    }

    @Override
    public List<Turn> getTurns(String userId, String threadId) {
        return jdbc.query(
            "SELECT t.id, t.question, COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer, t.asked_at, t.created_at, " +
            "t.input_tokens, t.output_tokens, t.elapsed_ms, t.provider, t.llm_calls, t.feedback, t.response_mode, t.selected_tags, " +
            "COALESCE(t.direct_mode, 0) AS direct_mode " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE t.user_id = ? AND t.thread_id = ? ORDER BY t.id ASC",
                TURN_ROW_MAPPER,
                userId, threadId);
    }

    @Override
    public List<Turn> getRecentTurns(String userId, String threadId) {
        // fetch last fetchLimit turns newest-first (same bound as getHistory()), then reverse for
        // chronological order — bounds LLM-facing callers (summarization) to a constant-size input
        // regardless of how long the conversation has grown.
        List<Turn> rows = jdbc.query(
            "SELECT t.id, t.question, COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer, t.asked_at, t.created_at, " +
            "t.input_tokens, t.output_tokens, t.elapsed_ms, t.provider, t.llm_calls, t.feedback, t.response_mode, t.selected_tags, " +
            "COALESCE(t.direct_mode, 0) AS direct_mode " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE t.user_id = ? AND t.thread_id = ? ORDER BY t.id DESC LIMIT ?",
                TURN_ROW_MAPPER,
                userId, threadId, fetchLimit);
        return rows.reversed();
    }

    @Override
    public Optional<Turn> getTurn(String userId, String threadId, long turnId) {
        List<Turn> rows = jdbc.query(
            "SELECT t.id, t.question, COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer, t.asked_at, t.created_at, " +
            "t.input_tokens, t.output_tokens, t.elapsed_ms, t.provider, t.llm_calls, t.feedback, t.response_mode, t.selected_tags, " +
            "COALESCE(t.direct_mode, 0) AS direct_mode " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE t.id = ? AND t.user_id = ? AND t.thread_id = ?",
                TURN_ROW_MAPPER,
                turnId, userId, threadId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<FeedbackRow> getFeedback(String userId, String threadId, long turnId) {
        List<FeedbackRow> rows = jdbc.query(
                "SELECT feedback FROM conversation_turns WHERE id = ? AND user_id = ? AND thread_id = ?",
                (rs, n) -> new FeedbackRow(rs.getString("feedback")),
                turnId, userId, threadId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void updateFeedback(String userId, String threadId, long turnId, String feedback) {
        jdbc.update(
                "UPDATE conversation_turns SET feedback = ? WHERE id = ? AND user_id = ? AND thread_id = ?",
                feedback, turnId, userId, threadId);
    }

    @Override
    public void saveRetrievalMetrics(long turnId, String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) return;
        jdbc.update("UPDATE conversation_turns SET retrieval_metrics = ? WHERE id = ?",
                metricsJson, turnId);
    }

    @Override
    public List<MetricsRow> findRecentRetrievalMetrics(String userId, String threadId,
                                                       int offset, int limit) {
        // Reads thread_meta, which ThreadMetaRepository owns — the same cross-repository reach
        // clearHistory() already makes for turn_source_ref. Both tables are created by a
        // @PostConstruct that always runs, so the only context lacking one is an isolated
        // repository test, which inits the owner explicitly rather than copying its DDL.
        //
        // LEFT JOIN, not JOIN: a turn whose thread_meta row is gone (see ThreadAdminRepository's
        // orphan count) must still appear here — its diagnostics are as valid as any other's, and
        // dropping it would make the panel silently disagree with its own "전체 N턴" badge.
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.asked_at, t.question, t.response_mode, t.provider, " +
                "       t.retrieval_metrics, t.user_id, t.thread_id, m.title AS thread_title " +
                "  FROM conversation_turns t " +
                "  LEFT JOIN thread_meta m ON m.thread_id = t.thread_id AND m.user_id = t.user_id " +
                " WHERE t.retrieval_metrics IS NOT NULL");
        List<Object> args = new java.util.ArrayList<>(4);
        appendMetricsFilters(sql, args, userId, threadId);
        sql.append(" ORDER BY t.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        return jdbc.query(sql.toString(),
                (rs, n) -> new MetricsRow(
                        rs.getLong("id"),
                        rs.getString("asked_at"),
                        rs.getString("question"),
                        rs.getString("response_mode"),
                        rs.getString("provider"),
                        rs.getString("retrieval_metrics"),
                        rs.getString("user_id"),
                        rs.getString("thread_id"),
                        rs.getString("thread_title")),
                args.toArray());
    }

    @Override
    public Map<Long, String> findRetrievalMetricsByTurnIds(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT id, retrieval_metrics FROM conversation_turns " +
                   "WHERE retrieval_metrics IS NOT NULL AND id IN (" + placeholders + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("retrieval_metrics")); },
                turnIds.toArray());
        return out;
    }

    @Override
    public void saveVerification(long turnId, String verificationJson) {
        if (verificationJson == null || verificationJson.isBlank()) return;
        jdbc.update("UPDATE conversation_turns SET verification = ? WHERE id = ?",
                verificationJson, turnId);
    }

    @Override
    public Map<Long, String> findVerificationsByTurnIds(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT id, verification FROM conversation_turns " +
                   "WHERE verification IS NOT NULL AND id IN (" + placeholders + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("verification")); },
                turnIds.toArray());
        return out;
    }

    @Override
    public int countRetrievalMetrics(String userId, String threadId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM conversation_turns t WHERE t.retrieval_metrics IS NOT NULL");
        List<Object> args = new java.util.ArrayList<>(2);
        appendMetricsFilters(sql, args, userId, threadId);
        Integer n = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    @Override
    public List<String> distinctRetrievalMetricsUserIds() {
        return jdbc.queryForList(
                "SELECT DISTINCT user_id FROM conversation_turns " +
                "WHERE retrieval_metrics IS NOT NULL ORDER BY user_id",
                String.class);
    }

    /**
     * The two optional filters, appended identically to the list and the count — if they ever
     * diverge the panel's "전체 N턴" badge starts describing a different set than the rows under it.
     * Blank is treated as absent so one "no filter" form reaches SQL.
     */
    private static void appendMetricsFilters(StringBuilder sql, List<Object> args,
                                             String userId, String threadId) {
        if (userId != null && !userId.isBlank()) {
            sql.append(" AND t.user_id = ?");
            args.add(userId);
        }
        if (threadId != null && !threadId.isBlank()) {
            sql.append(" AND t.thread_id = ?");
            args.add(threadId);
        }
    }
}
