package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQLite FTS5 keyword index over chunk content + extracted keywords.
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

    private static final String CHUNK_FTS = "chunk_fts";

    private static final String CREATE_CHUNK_FTS_SQL = """
            CREATE VIRTUAL TABLE IF NOT EXISTS %s USING fts5(
                spring_doc_id UNINDEXED,
                doc_id        UNINDEXED,
                version       UNINDEXED,
                filename      UNINDEXED,
                page          UNINDEXED,
                chunk_index   UNINDEXED,
                doc_tags      UNINDEXED,
                content,
                keywords,
                tokenize = 'unicode61'
            )
            """;

    private final JdbcTemplate jdbc;
    private volatile boolean available = false;

    // chunk_fts lives with the vector tables: vectorJdbcTemplate → vector.db when the
    // separate-vector-DB switch is on, else the operational memory.db (chroma / non-separated).
    public KeywordSearchRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        try {
            ensureChunkFtsSchema();
            available = true;
            log.info("[KEYWORD] FTS5 chunk_fts ready — hybrid search available");
        } catch (Exception e) {
            available = false;
            log.warn("[KEYWORD] FTS5 unavailable — hybrid search disabled: {}", e.getMessage());
        }
    }

    private void ensureChunkFtsSchema() {
        createChunkFtsTable(CHUNK_FTS);
        Set<String> columns = tableColumns(CHUNK_FTS);
        if (!columns.isEmpty() && !columns.contains("doc_tags")) {
            log.warn("[KEYWORD] Legacy chunk_fts schema detected: doc_tags column is missing. Rebuilding FTS table.");
            rebuildChunkFtsWithDocTags();
            log.warn("[KEYWORD] chunk_fts rebuild completed. Run document sync once to repopulate historical tags and keywords.");
        }
    }

    private void createChunkFtsTable(String tableName) {
        jdbc.execute(CREATE_CHUNK_FTS_SQL.formatted(tableName));
    }

    private Set<String> tableColumns(String tableName) {
        return new java.util.HashSet<>(jdbc.query(
                "PRAGMA table_info(" + tableName + ")",
                (rs, n) -> rs.getString("name")
        ));
    }

    private void rebuildChunkFtsWithDocTags() {
        final String tempTable = CHUNK_FTS + "_v2";
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + tempTable);
            createChunkFtsTable(tempTable);
            try {
                jdbc.update(("""
                        INSERT INTO %s
                            (spring_doc_id, doc_id, version, filename, page, chunk_index, doc_tags, content, keywords)
                        SELECT spring_doc_id, doc_id, version, filename, page, chunk_index, '', content, keywords
                        FROM %s
                        """).formatted(tempTable, CHUNK_FTS));
            } catch (Exception copyErr) {
                // Keep rebuilding even if legacy rows cannot be copied; this table is a derived index.
                log.warn("[KEYWORD] Skipped legacy row copy during rebuild (derived index will be refilled on next indexing): {}", copyErr.getMessage());
            }
            jdbc.execute("DROP TABLE IF EXISTS " + CHUNK_FTS);
            jdbc.execute("ALTER TABLE " + tempTable + " RENAME TO " + CHUNK_FTS);
        } catch (Exception e) {
            log.warn("[KEYWORD] chunk_fts rebuild failed; recreating an empty FTS table as fallback: {}", e.getMessage());
            jdbc.execute("DROP TABLE IF EXISTS " + tempTable);
            jdbc.execute("DROP TABLE IF EXISTS " + CHUNK_FTS);
            createChunkFtsTable(CHUNK_FTS);
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
                        (spring_doc_id, doc_id, version, filename, page, chunk_index, doc_tags, content, keywords)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                                str(m.get(MetaKey.TAGS)),     // 태그(쉼표 결합) — 검색 결과에 동행
                                d.getText() == null ? "" : d.getText(),
                                str(m.get(MetaKey.EXCERPT_KEYWORDS))
                        };
                    }).toList());
        } catch (Exception e) {
            log.debug("[KEYWORD] indexChunks failed: {}", e.getMessage());
        }
    }

    /** Overwrites {@code doc_tags} for every chunk row of a document. No-op when FTS5 is unavailable. */
    public void updateDocTags(String docId, String tagsCsv) {
        if (!available || docId == null) return;
        try {
            jdbc.update("UPDATE chunk_fts SET doc_tags = ? WHERE doc_id = ?", tagsCsv, docId);
        } catch (Exception e) {
            log.debug("[KEYWORD] updateDocTags failed docId={}: {}", docId, e.getMessage());
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
     * Removes specific rows by their {@code spring_doc_id} (chunk identity), not by {@code doc_id}
     * (document identity) — needed when new and old chunk rows momentarily share the same
     * {@code doc_id} (reindex-in-place), where a {@code doc_id}-based delete would also wipe the
     * rows just inserted. No-op when FTS5 is unavailable.
     */
    public void deleteBySpringDocIds(List<String> springDocIds) {
        if (!available || springDocIds == null || springDocIds.isEmpty()) return;
        try {
            String placeholders = springDocIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            jdbc.update("DELETE FROM chunk_fts WHERE spring_doc_id IN (" + placeholders + ")", springDocIds.toArray());
        } catch (Exception e) {
            log.debug("[KEYWORD] deleteBySpringDocIds failed: {}", e.getMessage());
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
                    SELECT spring_doc_id, doc_id, version, filename, page, chunk_index, doc_tags, content
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
                        meta.put(MetaKey.TAGS, rs.getString("doc_tags"));  // 태그 동행
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
     * Distinct tags currently in use, derived from the {@code doc_tags} column (comma-joined,
     * already normalized at index time). Optionally scoped to a version. Sorted, de-duplicated.
     * No-op (empty) when FTS5 is unavailable.
     */
    public List<String> distinctTags(String version) {
        if (!available) return List.of();
        String base = "SELECT DISTINCT doc_tags FROM chunk_fts WHERE doc_tags IS NOT NULL AND doc_tags <> ''";
        try {
            List<String> rows = (version != null && !version.isBlank())
                    ? jdbc.queryForList(base + " AND version = ?", String.class, version)
                    : jdbc.queryForList(base, String.class);
            java.util.TreeSet<String> tags = new java.util.TreeSet<>();
            for (String row : rows) {
                if (row == null) continue;
                for (String t : row.split(",")) {
                    String s = t.strip();
                    if (!s.isEmpty()) tags.add(s);
                }
            }
            return List.copyOf(tags);
        } catch (Exception e) {
            log.debug("[KEYWORD] distinctTags failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns distinct tags per doc_id (comma-split from doc_tags), sorted and de-duplicated.
     * No-op (empty map) when FTS5 is unavailable or docIds is empty.
     */
    public Map<String, List<String>> tagsByDocIds(List<String> docIds) {
        if (!available || docIds == null || docIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(docIds.size(), "?"));
        String sql = "SELECT doc_id, doc_tags FROM chunk_fts " +
                "WHERE doc_id IN (" + placeholders + ") AND doc_tags IS NOT NULL AND doc_tags <> ''";
        try {
            Map<String, Set<String>> grouped = new HashMap<>();
            jdbc.query(sql, rs -> {
                String docId = rs.getString("doc_id");
                String row = rs.getString("doc_tags");
                if (docId == null || row == null || row.isBlank()) return;
                Set<String> bucket = grouped.computeIfAbsent(docId, __ -> new TreeSet<>());
                for (String t : row.split(",")) {
                    String s = t.strip();
                    if (!s.isEmpty()) bucket.add(s);
                }
            }, docIds.toArray());

            Map<String, List<String>> out = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : grouped.entrySet()) {
                out.put(e.getKey(), List.copyOf(e.getValue()));
            }
            return out;
        } catch (Exception e) {
            log.debug("[KEYWORD] tagsByDocIds failed: {}", e.getMessage());
            return Map.of();
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
