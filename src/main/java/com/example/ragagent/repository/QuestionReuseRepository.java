package com.example.ragagent.repository;

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
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_source_turn ON turn_source_ref(turn_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_turn_source_chunk ON turn_source_ref(chunk_id)");
    }

    public void saveTurnSourceRefs(long turnId, String userId, String threadId, List<SourceSnapshot> refs) {
        if (refs == null || refs.isEmpty()) return;
        jdbc.batchUpdate(
                "INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, doc_id, chunk_hash, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
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
                }
        );
    }

    public void cloneTurnSourceRefs(long fromTurnId, long toTurnId, String userId, String threadId) {
        jdbc.update("""
                INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, doc_id, chunk_hash, status)
                SELECT ?, ?, ?, chunk_id, doc_id, chunk_hash, status
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
            "AND COALESCE(NULLIF(TRIM(t.response_mode), ''), 'M') <> 'S' " +
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
            "AND COALESCE(NULLIF(TRIM(t.response_mode), ''), 'M') <> 'S' " +
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
                "SELECT chunk_id, doc_id, chunk_hash FROM turn_source_ref WHERE turn_id = ? AND status = 'active'",
                (rs, n) -> new SourceSnapshot(
                        rs.getString("chunk_id"),
                        rs.getString("doc_id"),
                        rs.getString("chunk_hash")),
                turnId);
    }

    public List<SourcePreviewRow> findSourcePreviewRows(long turnId) {
        return vectorJdbc.query("""
                SELECT r.chunk_id,
                       r.doc_id,
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
                WHERE r.turn_id = ?
                  AND r.status = 'active'
                """,
                (rs, n) -> new SourcePreviewRow(
                        rs.getString("chunk_id"),
                        rs.getString("doc_id"),
                        rs.getString("filename"),
                        rs.getString("page"),
                        rs.getString("chapter"),
                        rs.getString("content")),
                turnId);
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

    public void markSourceRefsInactiveByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        String placeholders = chunkIds.stream().map(v -> "?").collect(Collectors.joining(","));
        jdbc.update("UPDATE turn_source_ref SET status='inactive' WHERE chunk_id IN (" + placeholders + ")",
                chunkIds.toArray());
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

    public record SourceSnapshot(String chunkId, String docId, String chunkHash) {}

    public record SourcePreviewRow(String chunkId, String docId, String filename,
                                   String pageOrSlide, String chapterNo, String content) {}

    public record CandidateTurn(long turnId, String userId, String threadId,
                                String question, String answer, String createdAt) {}
}
