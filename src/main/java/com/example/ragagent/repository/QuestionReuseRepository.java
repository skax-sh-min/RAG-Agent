package com.example.ragagent.repository;

import com.example.ragagent.model.ResponseMode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class QuestionReuseRepository {

    private static final String DELETED_REFERENCE_TEXT = "참조 원문 삭제됨";

    /**
     * 재사용 후보에서 제외할 응답 모드를 거르는 WHERE 술어 (§6.24 Step 3-b).
     *
     * <p>예전에는 {@code COALESCE(NULLIF(TRIM(t.response_mode), ''), 'M') <> 'S'} 라는 리터럴이
     * 두 쿼리에 각각 박혀 있었다. 값 비교가 SQL 문자열 안에 있으면 두 가지가 동시에 깨진다 —
     * 모드를 하나 추가할 때 여기를 고쳐야 한다는 사실이 어디에도 드러나지 않고, 운영 코드의
     * 모드 값 비교를 잡아내는 {@code ResponseModeBranchConventionTest} 도 SQL 문자열 안까지는
     * 보지 못한다. 이제 목록을 {@link ResponseMode#allowsReuse()} 에서
     * 만들어 두 쿼리가 공유한다.
     *
     * <p><b>{@code ResponseMode.parse()} 와 판정이 일치해야 한다.</b> 그래서 컬럼을
     * {@code TRIM(UPPER(...))} 로 정규화한 뒤 <b>제외 목록에 대한 NOT IN</b> 으로 거른다 —
     * 허용 목록에 대한 IN 이 아니다. 그래야 {@code parse()} 의 관대함과 같은 방향으로 떨어진다:
     * NULL·공백·옛 {@code 'M'}/{@code 'L'}·알 수 없는 값은 모두 {@code parse()} 에서 N(재사용 가능)
     * 으로 흡수되고, 여기서도 목록에 없으므로 포함된다. 허용 목록 방식이었다면 같은 행들이
     * 조용히 후보에서 빠졌을 것이다.
     *
     * <p>enum 상수 이름만 들어가므로 SQL 인젝션 여지가 없다(따옴표를 담을 수 없는 식별자다).
     */
    private static final String REUSABLE_MODE_PREDICATE = buildReusableModePredicate();

    private static String buildReusableModePredicate() {
        String excluded = java.util.Arrays.stream(ResponseMode.values())
                .filter(m -> !m.allowsReuse())
                .map(m -> "'" + m.name() + "'")
                .collect(Collectors.joining(", "));
        if (excluded.isEmpty()) return "1 = 1";
        return "TRIM(UPPER(COALESCE(t.response_mode, ''))) NOT IN (" + excluded + ") ";
    }

    private final JdbcTemplate jdbc;
    private final JdbcTemplate vectorJdbc;

    public QuestionReuseRepository(JdbcTemplate jdbc,
                                   @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbc) {
        this.jdbc = jdbc;
        this.vectorJdbc = vectorJdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS turn_source_ref (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    turn_id     INTEGER NOT NULL,
                    user_id     TEXT NOT NULL,
                    thread_id   TEXT NOT NULL,
                    chunk_id    TEXT NOT NULL,
                    doc_id      TEXT,
                    chunk_hash  TEXT NOT NULL,
                    status      TEXT NOT NULL DEFAULT 'active',
                    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
        // 응답 참여도(§2단계 AnswerAttribution)를 스냅샷과 함께 보관 — 기존 테이블에는 없으므로
        // 방어적 ALTER (conversation_turns.response_mode 선례). 구 행은 NULL로 남고, 그 경우
        // QuestionReuseService.validateTurn()이 예전처럼 전체 출처를 검증 대상으로 삼는다.
        try {
            jdbc.execute("ALTER TABLE turn_source_ref ADD COLUMN answer_share REAL");
        } catch (Exception ignored) { /* already present */ }
        // 무효화 시각 — 배지 자체에는 안 쓰지만, "언제부터 이 답변이 낡았나"는 사후 조사에서
        // 가장 먼저 묻는 값이고 상태 전이 시점에만 알 수 있어 지금 남겨두지 않으면 복구 불가다.
        try {
            jdbc.execute("ALTER TABLE turn_source_ref ADD COLUMN invalidated_at TEXT");
        } catch (Exception ignored) { /* already present */ }
        // 사용자가 "현재 대화에서 이 청크 제거"로 직접 숨긴 시각. status와 별도 컬럼인 이유:
        // status(active/deleted/modified)는 "청크가 그 뒤 바뀌었나"라는 사실 관측이고 재사용
        // 검증이 그 값에 의존한다. 여기 숨김은 사실 관측이 아니라 표시 취향이라, 같은 컬럼에
        // 섞으면 화면에서 치웠다는 이유만으로 재사용 판정이 달라진다(§ 표시 전용).
        try {
            jdbc.execute("ALTER TABLE turn_source_ref ADD COLUMN hidden_at TEXT");
        } catch (Exception ignored) { /* already present */ }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_source_turn ON turn_source_ref(turn_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_source_chunk ON turn_source_ref(chunk_id)");
    }

    public void saveTurnSourceRefs(long turnId, String userId, String threadId, List<SourceSnapshot> refs) {
        if (refs == null || refs.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, doc_id, chunk_hash, status, answer_share) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                refs,
                refs.size(),
                (ps, ref) -> {
                    ps.setLong(1, turnId);
                    ps.setString(2, userId);
                    ps.setString(3, threadId);
                    ps.setString(4, ref.chunkId());
                    ps.setString(5, ref.docId());
                    ps.setString(6, ref.chunkHash());
                    ps.setString(7, "active");
                    if (ref.answerShare() == null) {
                        ps.setNull(8, java.sql.Types.REAL);
                    } else {
                        ps.setDouble(8, ref.answerShare());
                    }
                }
        );
    }

    public void cloneTurnSourceRefs(long fromTurnId, long toTurnId, String userId, String threadId) {
        jdbc.update("""
                INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, doc_id, chunk_hash, status, answer_share)
                SELECT ?, ?, ?, chunk_id, doc_id, chunk_hash, status, answer_share
                FROM turn_source_ref
                WHERE turn_id = ?
                """, toTurnId, userId, threadId, fromTurnId);
    }

    public List<CandidateTurn> findSuggestionCandidates(String q, boolean meOnly, String userId, int limit) {
        String resolvedAnswerExpr = "COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" +
                DELETED_REFERENCE_TEXT + "') AS answer";
        String sql = "SELECT t.id, t.user_id, t.thread_id, t.question, " + resolvedAnswerExpr + ", t.created_at " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE lower(t.question) LIKE lower(?) " +
            "AND (t.feedback IS NULL OR t.feedback <> 'DISLIKE') " +
            "AND " + REUSABLE_MODE_PREDICATE +
            "AND (COALESCE(t.direct_mode, 0) = 0 OR t.feedback = 'LIKE') " +
            (meOnly ? "AND t.user_id = ? " : "") +
            "ORDER BY t.id DESC LIMIT ?";

        if (meOnly) {
            return jdbc.query(sql,
                    (rs, n) -> new CandidateTurn(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("thread_id"),
                            rs.getString("question"),
                            rs.getString("answer"),
                            rs.getString("created_at")),
                    "%" + q + "%", userId, Math.max(1, limit));
        }
        return jdbc.query(sql,
                (rs, n) -> new CandidateTurn(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("thread_id"),
                        rs.getString("question"),
                        rs.getString("answer"),
                        rs.getString("created_at")),
                "%" + q + "%", Math.max(1, limit));
    }

    public CandidateTurn findTurnForReuse(long turnId, boolean meOnly, String userId) {
        String resolvedAnswerExpr = "COALESCE(NULLIF(src.answer, ''), NULLIF(t.answer, ''), '" +
                DELETED_REFERENCE_TEXT + "') AS answer";
        String sql = "SELECT t.id, t.user_id, t.thread_id, t.question, " + resolvedAnswerExpr + ", t.created_at " +
            "FROM conversation_turns t " +
            "LEFT JOIN conversation_turns src ON src.id = t.reused_from_turn_id AND src.user_id = t.user_id " +
            "WHERE t.id = ? " +
            "AND (t.feedback IS NULL OR t.feedback <> 'DISLIKE') " +
            "AND " + REUSABLE_MODE_PREDICATE +
            "AND (COALESCE(t.direct_mode, 0) = 0 OR t.feedback = 'LIKE') " +
            (meOnly ? "AND t.user_id = ? " : "") +
            "LIMIT 1";
        List<CandidateTurn> rows = meOnly
                ? jdbc.query(sql,
                    (rs, n) -> new CandidateTurn(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("thread_id"),
                            rs.getString("question"),
                            rs.getString("answer"),
                            rs.getString("created_at")),
                    turnId, userId)
                : jdbc.query(sql,
                    (rs, n) -> new CandidateTurn(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("thread_id"),
                            rs.getString("question"),
                            rs.getString("answer"),
                            rs.getString("created_at")),
                    turnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SourceSnapshot> findSourceRefs(long turnId) {
        return jdbc.query(
                "SELECT chunk_id, doc_id, chunk_hash, answer_share FROM turn_source_ref WHERE turn_id = ? AND status = 'active'",
                SNAPSHOT_MAPPER,
                turnId);
    }

    /**
     * Every recorded source for a turn, <b>including</b> ones already marked inactive.
     *
     * <p>{@link #findSourceRefs} can't back reuse validation on its own: invalidating a chunk that
     * never contributed to the answer would silently shrink that list, and an emptied list reads as
     * "출처 없음 → 재사용 불가" — i.e. exactly the block the answer-share scope exists to avoid.
     * Validation therefore reads all rows and decides scope itself.
     */
    public List<SourceSnapshot> findAllSourceRefs(long turnId) {
        return jdbc.query(
                "SELECT chunk_id, doc_id, chunk_hash, answer_share, status FROM turn_source_ref WHERE turn_id = ?",
                (rs, n) -> {
                    double share = rs.getDouble("answer_share");
                    return new SourceSnapshot(
                            rs.getString("chunk_id"),
                            rs.getString("doc_id"),
                            rs.getString("chunk_hash"),
                            rs.wasNull() ? null : share,
                            rs.getString("status"));
                },
                turnId);
    }

    private static final org.springframework.jdbc.core.RowMapper<SourceSnapshot> SNAPSHOT_MAPPER = (rs, n) -> {
        double share = rs.getDouble("answer_share");
        return new SourceSnapshot(
                rs.getString("chunk_id"),
                rs.getString("doc_id"),
                rs.getString("chunk_hash"),
                rs.wasNull() ? null : share,
                "active");
    };

    public List<SourcePreviewRow> findSourcePreviewRows(long turnId) {
        return vectorJdbc.query("""
                SELECT r.chunk_id,
                       r.doc_id,
                       r.status,
                  COALESCE(NULLIF(TRIM(f.filename), ''), NULLIF(TRIM(json_extract(c.metadata, '$.filename')), '')) AS filename,
                  COALESCE(NULLIF(TRIM(f.page), ''), NULLIF(TRIM(json_extract(c.metadata, '$.page_or_slide')), '')) AS page,
                      COALESCE(
                          NULLIF(NULLIF(NULLIF(TRIM(json_extract(c.metadata, '$.chapter_no')), ''), '0'), '0.0'),
                          NULLIF(NULLIF(NULLIF(TRIM(f.chapter), ''), '0'), '0.0')
                      ) AS chapter,
                  -- c.content (vec_document_chunks, sqlite-vec only) is the untouched stored chunk
                  -- text — the same thing the live/in-session preview shows via Document.getText().
                  -- f.content (chunk_fts) is the derived embedding/FTS search text (§10.1 Contextual
                  -- Retrieval: CHUNK_CONTEXT prefix + normalized/noise-stripped body), populated for
                  -- both vector-store backends but NOT what a user was shown live. Preferring f over
                  -- c (as before) made the reload preview differ from the live one whenever c.content
                  -- existed (sqlite-vec mode); c must win, with f only as the Chroma-mode fallback
                  -- (vec_document_chunks has no rows there).
                  COALESCE(NULLIF(c.content, ''), f.content) AS content
                FROM turn_source_ref r
                                JOIN conversation_turns t ON t.id = r.turn_id AND t.user_id = r.user_id
                LEFT JOIN chunk_fts f ON f.spring_doc_id = r.chunk_id
                LEFT JOIN vec_document_chunks c ON c.spring_doc_id = r.chunk_id
                -- status 필터가 없다: 무효화된 출처를 걸러내면 대화 기록에서 배지가 아니라 출처
                -- 자체가 조용히 사라져(§2번 표시 요구) "원래 없었던 것"처럼 보인다.
                -- hidden_at은 그 반대로 걸러낸다 — 사라지는 것이 사용자가 직접 요청한 결과다.
                WHERE r.turn_id = ? AND r.hidden_at IS NULL
                """,
                (rs, n) -> new SourcePreviewRow(
                        rs.getString("chunk_id"),
                        rs.getString("doc_id"),
                        rs.getString("filename"),
                        rs.getString("page"),
                        rs.getString("chapter"),
                        rs.getString("content"),
                        rs.getString("status")),
                turnId);
    }

    /**
     * 대화 기록의 한 턴에서 출처 청크 하나를 숨긴다 (§ 현재 대화에서 이 청크 제거).
     *
     * <p><b>표시 전용이며 재사용 검증에는 영향이 없다</b> — {@link #findSourceRefs}/
     * {@link #findAllSourceRefs}는 이 컬럼을 보지 않는다. 답변은 그 청크를 근거로 만들어진
     * 사실이 그대로이므로, 사용자가 목록에서 치웠다고 해서 그 답변의 유효성 판정 기준까지
     * 바뀌면 안 된다.
     *
     * <p>소유권은 {@code user_id}/{@code thread_id}까지 WHERE에 넣어 SQL 자체로 강제한다
     * ({@code excludeTurnImageRef}와 같은 방식) — 컨트롤러의 확인과 이중이지만, 이 메서드를
     * 나중에 다른 경로에서 부를 때 조용히 남의 대화를 건드리는 일이 없도록.
     *
     * @return 실제로 숨겨진 행 수 (0 = 없는 청크이거나 이미 숨김)
     */
    public int hideSourceRef(long turnId, String userId, String threadId, String chunkId) {
        if (turnId <= 0 || chunkId == null || chunkId.isBlank()) return 0;
        return jdbc.update(
                "UPDATE turn_source_ref SET hidden_at = datetime('now') " +
                "WHERE turn_id = ? AND user_id = ? AND thread_id = ? AND chunk_id = ? AND hidden_at IS NULL",
                turnId, userId, threadId, chunkId.strip());
    }

    /**
     * Full untruncated stored text for one chunk, keyed by id alone — no {@code turn_source_ref}
     * join, unlike {@link #findSourcePreviewRows}, since the chat "원문 보기" click-to-expand modal
     * only ever has the badge's {@code chunk_id} to go on. Same backend-priority rule as the
     * preview query: {@code vec_document_chunks.content} (raw stored text, sqlite-vec only) wins
     * over {@code chunk_fts.content} (derived embedding/FTS text, populated for both backends) —
     * a plain LEFT JOIN can't express that without a driving row to join from, so this unions the
     * two single-table lookups and keeps the higher-priority match. Returns {@code null} when the
     * chunk no longer exists in either table (deleted/re-indexed since the turn was recorded).
     */
    public String findChunkFullText(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) return null;
        List<String> rows = vectorJdbc.query("""
                SELECT content FROM (
                    SELECT c.content AS content, 0 AS priority
                    FROM vec_document_chunks c WHERE c.spring_doc_id = ?
                    UNION ALL
                    SELECT f.content AS content, 1 AS priority
                    FROM chunk_fts f WHERE f.spring_doc_id = ?
                )
                ORDER BY priority
                LIMIT 1
                """,
                (rs, n) -> rs.getString("content"),
                chunkId, chunkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long findReusedFromTurnId(long turnId) {
        List<Long> rows = jdbc.query(
                "SELECT reused_from_turn_id FROM conversation_turns WHERE id = ? LIMIT 1",
                (rs, n) -> {
                    long value = rs.getLong("reused_from_turn_id");
                    return rs.wasNull() ? null : value;
                },
                turnId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsTurn(long turnId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_turns WHERE id = ?",
                Integer.class,
                turnId);
        return count != null && count > 0;
    }

    /**
     * Current chunk text snapshot from FTS index. If a chunk is deleted/replaced, it simply won't be present.
     */
    public Map<String, String> currentChunkHashes(Set<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();
        List<String> ids = new ArrayList<>(chunkIds);
        String placeholders = ids.stream().map(v -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = vectorJdbc.queryForList(
                "SELECT spring_doc_id, content FROM chunk_fts WHERE spring_doc_id IN (" + placeholders + ")",
                ids.toArray());
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.getOrDefault("spring_doc_id", ""));
            String content = String.valueOf(row.getOrDefault("content", ""));
            out.put(id, sha256(content));
        }
        return out;
    }

    /** Snapshot helper for current retrieval results when turn is being saved. */
    public Map<String, String> currentChunkHashesByDocs(List<org.springframework.ai.document.Document> docs) {
        if (docs == null || docs.isEmpty()) return Map.of();
        Set<String> ids = docs.stream().map(org.springframework.ai.document.Document::getId)
                .filter(v -> v != null && !v.isBlank()).collect(Collectors.toSet());
        return currentChunkHashes(ids);
    }

    /**
     * 스냅샷 이후 청크가 바뀌었음을 표시한다.
     *
     * <p>예전 이름은 {@code markSourceRefsInactiveByChunkIds} 였다 — 그 시절에는 이 컬럼에
     * 실제로 {@code 'inactive'} 를 썼기 때문이다. 지금 쓰는 값은 {@code deleted}/{@code modified}
     * 둘뿐이라 이름이 값과 어긋나 있었고, 코드에 없는 상태가 있는 것처럼 읽혔다. 레거시
     * {@code 'inactive'} 행을 읽는 코드는 남아 있지 않다(상태 판정은 모두 {@code = 'active'}
     * 비교라, 옛 값은 자동으로 "무효화됨" 쪽으로 떨어진다).
     *
     * @param status {@code SourceRef.STALE_DELETED} 또는 {@code STALE_MODIFIED} — 재사용 차단에는
     *        둘 다 똑같이 작용하지만 대화 기록의 배지 규칙이 다르다(삭제는 항상, 수정은 응답 지분이
     *        있는 출처만). 이미 무효화된 행은 건드리지 않는다: 수정된 뒤 삭제되면 최종 상태는
     *        삭제여야 하므로 {@code deleted}만 덮어쓰기를 허용한다.
     */
    public void markSourceRefsStaleByChunkIds(List<String> chunkIds, String status) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        String placeholders = chunkIds.stream().map(v -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(status);
        args.addAll(chunkIds);
        String guard = "deleted".equals(status) ? "" : " AND status = 'active'";
        jdbc.update("UPDATE turn_source_ref SET status = ?, invalidated_at = datetime('now') " +
                    "WHERE chunk_id IN (" + placeholders + ")" + guard,
                args.toArray());
    }


    private static String sha256(String text) {
        String src = text == null ? "" : text;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(src.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param answerShare 이 청크가 답변에서 차지한 글자수 비율(§2단계 응답 참여도). {@code null}은
     *        "측정 안 됨"이지 0이 아니다 — 컬럼 추가 이전에 기록된 행, 그리고 귀속 계산이
     *        {@code Method.NONE}으로 끝난 턴이 여기에 해당한다.
     * @param status 스냅샷 시점 이후 이 청크가 삭제/변경 처리되었는지. {@code findSourceRefs}가
     *        돌려주는 행은 정의상 항상 {@code active}다.
     */
    public record SourceSnapshot(String chunkId, String docId, String chunkHash,
                                 Double answerShare, String status) {

        /** 하위 호환 — 응답 참여도를 모르는 호출부(테스트, 구 경로)용. */
        public SourceSnapshot(String chunkId, String docId, String chunkHash) {
            this(chunkId, docId, chunkHash, null, "active");
        }

        /** 답변에 실제로 지분이 있었던 출처인가. */
        public boolean contributed() {
            return answerShare != null && answerShare > 0.0;
        }

        /** 스냅샷 이후 청크가 삭제·수정됐는가({@code status != 'active'}). "active 가 아니다" 로
         *  판정하므로 레거시 {@code 'inactive'} 행도 그대로 무효로 읽힌다. */
        public boolean stale() {
            return status != null && !"active".equals(status);
        }
    }

    public record SourcePreviewRow(String chunkId, String docId, String filename,
                                   String pageOrSlide, String chapterNo, String content,
                                   String status) {

        public SourcePreviewRow(String chunkId, String docId, String filename,
                                String pageOrSlide, String chapterNo, String content) {
            this(chunkId, docId, filename, pageOrSlide, chapterNo, content, "active");
        }
    }

    public record CandidateTurn(long turnId, String userId, String threadId,
                                String question, String answer, String createdAt) {}
}
