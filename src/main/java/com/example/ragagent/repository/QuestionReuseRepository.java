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
        String sql = """
                SELECT id, user_id, thread_id, question, answer, created_at
                FROM conversation_turns
                WHERE lower(question) LIKE lower(?)
                  AND (feedback IS NULL OR feedback <> 'DISLIKE')
                                    AND (provider IS NULL OR provider <> 'db-reuse')
                """ + (meOnly ? " AND user_id = ? " : "") + " ORDER BY id DESC LIMIT ?";

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
        String sql = """
                SELECT id, user_id, thread_id, question, answer, created_at
                FROM conversation_turns
                WHERE id = ?
                  AND (feedback IS NULL OR feedback <> 'DISLIKE')
                                    AND (provider IS NULL OR provider <> 'db-reuse')
                """ + (meOnly ? " AND user_id = ?" : "") + " LIMIT 1";
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

    public record CandidateTurn(long turnId, String userId, String threadId,
                                String question, String answer, String createdAt) {}
}
