package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R-2: SQLite FTS5 keyword index over chunk content + extracted keywords.
 * Provides a BM25-ranked lexical search axis that complements vector similarity
 * (recovers exact terms — product codes, error codes, API names — that embeddings miss).
 *
 * <p>Degrades gracefully: if the SQLite build lacks FTS5, {@link #isAvailable()} stays false
 * and all operations become no-ops, so neither startup nor indexing is affected.
 * Populated on every index; consumed by retrieval only when hybrid search is enabled.
 */
@Component
public class KeywordSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchRepository.class);

    private final JdbcTemplate jdbc;
    private volatile boolean available = false;

    public KeywordSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        try {
            jdbc.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
                        spring_doc_id UNINDEXED,
                        doc_id        UNINDEXED,
                        version       UNINDEXED,
                        filename      UNINDEXED,
                        page          UNINDEXED,
                        chunk_index   UNINDEXED,
                        content,
                        keywords,
                        tokenize = 'unicode61'
                    )
                    """);
            available = true;
            log.info("[KEYWORD] FTS5 chunk_fts ready — hybrid search available");
        } catch (Exception e) {
            available = false;
            log.warn("[KEYWORD] FTS5 unavailable — hybrid search disabled: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Bulk-inserts chunk rows for one document. No-op when FTS5 is unavailable. */
    public void indexChunks(List<Document> chunks) {
        if (!available || chunks == null || chunks.isEmpty()) return;
        try {
            jdbc.batchUpdate("""
                    INSERT INTO chunk_fts
                        (spring_doc_id, doc_id, version, filename, page, chunk_index, content, keywords)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunks.stream().map(d -> {
                        Map<String, Object> m = d.getMetadata();
                        return new Object[]{
                                d.getId(),
                                str(m.get(MetaKey.DOC_ID)),
                                str(m.get(MetaKey.VERSION)),
                                str(m.get(MetaKey.FILENAME)),
                                str(m.get(MetaKey.PAGE_OR_SLIDE)),
                                str(m.get(MetaKey.CHUNK_INDEX)),
                                d.getText() == null ? "" : d.getText(),
                                str(m.get(MetaKey.EXCERPT_KEYWORDS))
                        };
                    }).toList());
        } catch (Exception e) {
            log.debug("[KEYWORD] indexChunks failed: {}", e.getMessage());
        }
    }

    /** Removes all FTS rows for a document. No-op when FTS5 is unavailable. */
    public void deleteByDocId(String docId) {
        if (!available || docId == null) return;
        try {
            jdbc.update("DELETE FROM chunk_fts WHERE doc_id = ?", docId);
        } catch (Exception e) {
            log.debug("[KEYWORD] deleteByDocId failed docId={}: {}", docId, e.getMessage());
        }
    }

    /**
     * BM25-ranked lexical search over content + keywords, filtered by version.
     * Returns Documents carrying the same metadata keys vector results use so RRF dedup
     * (via {@code doc_id:chunk_index}) merges the two sources cleanly.
     */
    public List<Document> search(String version, String question, int topK) {
        if (!available) return List.of();
        String match = toMatchQuery(question);
        if (match == null) return List.of();
        try {
            return jdbc.query("""
                    SELECT spring_doc_id, doc_id, version, filename, page, chunk_index, content
                    FROM chunk_fts
                    WHERE chunk_fts MATCH ? AND version = ?
                    ORDER BY bm25(chunk_fts)
                    LIMIT ?
                    """,
                    (rs, n) -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put(MetaKey.DOC_ID, rs.getString("doc_id"));
                        meta.put(MetaKey.VERSION, rs.getString("version"));
                        meta.put(MetaKey.FILENAME, rs.getString("filename"));
                        meta.put(MetaKey.PAGE_OR_SLIDE, rs.getString("page"));
                        meta.put(MetaKey.CHUNK_INDEX, rs.getString("chunk_index"));
                        return Document.builder()
                                .id(rs.getString("spring_doc_id"))
                                .text(rs.getString("content"))
                                .metadata(meta)
                                .build();
                    },
                    match, version, topK);
        } catch (Exception e) {
            log.debug("[KEYWORD] search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Builds a safe FTS5 MATCH expression: each token is double-quoted (so punctuation/operators
     * cannot break the query) and OR-combined for recall.
     */
    static String toMatchQuery(String question) {
        if (question == null || question.isBlank()) return null;
        String[] tokens = question.split("[\\s\\p{Punct}]+");
        List<String> terms = new ArrayList<>();
        for (String t : tokens) {
            String s = t.trim().replace("\"", "");
            if (s.length() >= 2) terms.add("\"" + s + "\"");
        }
        return terms.isEmpty() ? null : String.join(" OR ", terms);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
