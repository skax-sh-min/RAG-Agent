package com.example.ragagent.repository;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.HistoryPolicy;
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

    /**
     * 재사용 턴({@code reused_from_turn_id})의 답변을 원본 턴에서 가져오는 조인 — 답변을 읽는
     * 네 조회({@code getHistory}/{@code getTurns}/{@code getRecentTurns}/{@code getTurn})가 공유한다.
     *
     * <p><b>사용자 조건이 없다 — 일부러다.</b> 예전에는 {@code AND src.user_id = t.user_id} 가
     * 붙어 있었는데, 추천 범위는 항상 shared 라 원본이 <em>다른 사용자</em>의 턴인 것이 정상
     * 경로다. 그 조건은 바로 그 경우 조인을 비게 만들어, 원본이 멀쩡히 있는데도 답변이
     * {@code "참조 원문 삭제됨"} 으로 떨어졌다 — 화면에는 재사용 직후 정상으로 보이고(응답 본문은
     * {@code /reuse} 가 직접 실어 준다) 대화를 다시 열 때만 사라지며, 이력·지식 제안 프리필·
     * 재사용의 재사용까지 같은 조인을 타서 전부 그 문구를 받았다. 모든 방문자가 한 게스트 id 를
     * 쓰는 기본 전략({@code guest-identity=shared})에서는 두 id 가 늘 같아 드러나지 않았고,
     * §6.22 방문자별 id·full-auth 에서만 재현됐다.
     *
     * <p>격리는 바깥 행 {@code t} 의 {@code WHERE t.user_id = ?} 가 맡는다. {@code src} 에는
     * {@code t.reused_from_turn_id} 로만 닿고, 그 id 는 {@code /reuse} 가 shared 범위에서 이미 그
     * 사용자에게 내준 것이라 여기서 다시 거를 것이 없다. 원본이 실제로 지워진 경우의 폴백
     * ({@link #DELETED_REFERENCE_TEXT})은 LEFT JOIN 이 그대로 보장한다.
     */
    private static final String REUSE_SOURCE_JOIN =
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id ";
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

    @Override
    public String getHistory(String userId, String threadId, int maxChars, boolean askingDirect) {
        // fetch last fetchLimit turns newest-first, then reverse for chronological order.
        // DISLIKE-tagged turns are excluded from context (hard exclusion, §6.9).
        //
        // §10.13 — 답변은 그대로 싣지 않고 HistoryPolicy 를 거친다. 요약 경로의 [Recent] 블록이
        // 쓰는 것과 같은 규칙이어야 한다: 안 그러면 같은 스레드가 요약 캐시 유무에 따라 다른
        // 맥락을 보고, 캐시 TTL 이 지나는 순간 이력이 갑자기 달라진다.
        // response_mode 는 그 규칙의 입력이다 — 이전 턴이 S 였으면 '## 요약' 이 답변 전부다.
        // 싣는 턴 수는 가져오는 창(fetchLimit)이 아니라 HistoryPolicy.promptTurnCap() 이 정한다 —
        // 이 경로는 가져온 것을 그대로 싣기 때문에 LIMIT 이 곧 프롬프트에 들어가는 양이다.
        // 요약 경로의 [Recent] 와 같은 상한을 쓴다(둘이 갈라지면 요약 캐시 유무에 따라 맥락이 달라진다).
        List<String> rows = jdbc.query(
            "SELECT t.question AS question, t.response_mode AS response_mode, " +
            "COALESCE(t.direct_mode, 0) AS direct_mode, " +
            "COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer " +
            "FROM conversation_turns t " +
            REUSE_SOURCE_JOIN +
            "WHERE t.user_id = ? AND t.thread_id = ? AND (t.feedback IS NULL OR t.feedback <> 'DISLIKE') " +
            "ORDER BY t.id DESC LIMIT ?",
            (rs, n) -> "Q: %s\nA: %s".formatted(rs.getString("question"),
                    HistoryPolicy.renderAnswer(rs.getString("answer"),
                            rs.getString("response_mode"), askingDirect,
                            // 재사용 턴이면 답변은 원본(src)에서 오지만 모드는 이 턴의 것이다
                            // (response_mode 도 같은 규칙) — 지금 이력에 실리는 것은 이 턴이다.
                            rs.getInt("direct_mode") == 1)),
                userId, threadId, HistoryPolicy.promptTurnCap(fetchLimit));

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
        jdbc.update("DELETE FROM turn_source_ref WHERE user_id = ? AND thread_id = ?", userId, threadId);
        jdbc.update("DELETE FROM turn_image_ref WHERE user_id = ? AND thread_id = ?", userId, threadId);
        jdbc.update("DELETE FROM conversation_turns WHERE user_id = ? AND thread_id = ?", userId, threadId);
    }

    @Override
    public boolean deleteTurn(String userId, String threadId, long turnId) {
        // Same table set and the same order as clearHistory(), narrowed to one turn. The
        // conversation_turns row goes last so a failure part-way through can only leave orphaned
        // child rows (invisible to every read path, all of which start from conversation_turns),
        // never a turn whose sources have silently vanished.
        jdbc.update("DELETE FROM turn_source_ref WHERE user_id = ? AND thread_id = ? AND turn_id = ?",
                userId, threadId, turnId);
        jdbc.update("DELETE FROM turn_image_ref WHERE user_id = ? AND thread_id = ? AND turn_id = ?",
                userId, threadId, turnId);
        int removed = jdbc.update(
                "DELETE FROM conversation_turns WHERE user_id = ? AND thread_id = ? AND id = ?",
                userId, threadId, turnId);
        return removed > 0;
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
            REUSE_SOURCE_JOIN +
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
            REUSE_SOURCE_JOIN +
            "WHERE t.user_id = ? AND t.thread_id = ? ORDER BY t.id DESC LIMIT ?",
                TURN_ROW_MAPPER,
                userId, threadId, fetchLimit);
        return rows.reversed();
    }

    @Override
    public List<String> findQuestionsBefore(String userId, String threadId, long turnId, int limit) {
        List<String> newestFirst = jdbc.queryForList(
                "SELECT question FROM conversation_turns " +
                "WHERE user_id = ? AND thread_id = ? AND id < ? ORDER BY id DESC LIMIT ?",
                String.class, userId, threadId, turnId, Math.max(0, limit));
        return newestFirst.reversed();
    }

    @Override
    public Optional<Turn> getTurn(String userId, String threadId, long turnId) {
        List<Turn> rows = jdbc.query(
            "SELECT t.id, t.question, COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" + DELETED_REFERENCE_TEXT + "') AS answer, t.asked_at, t.created_at, " +
            "t.input_tokens, t.output_tokens, t.elapsed_ms, t.provider, t.llm_calls, t.feedback, t.response_mode, t.selected_tags, " +
            "COALESCE(t.direct_mode, 0) AS direct_mode " +
            "FROM conversation_turns t " +
            REUSE_SOURCE_JOIN +
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
    public void saveClarifiedQuestion(long turnId, String clarifiedQuestion) {
        if (clarifiedQuestion == null || clarifiedQuestion.isBlank()) return;
        jdbc.update("UPDATE conversation_turns SET clarified_question = ? WHERE id = ?",
                clarifiedQuestion, turnId);
    }

    @Override
    public Map<Long, String> findClarifiedQuestionsByTurnIds(List<Long> turnIds) {
        if (turnIds == null || turnIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(turnIds.size(), "?"));
        Map<Long, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT id, clarified_question FROM conversation_turns " +
                   "WHERE clarified_question IS NOT NULL AND id IN (" + placeholders + ")",
                rs -> { out.put(rs.getLong("id"), rs.getString("clarified_question")); },
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
