package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite FTS5 keyword index over chunk content + extracted keywords.
 * Provides a BM25-ranked lexical search axis that complements vector similarity
 * (recovers exact terms — product codes, error codes, API names — that embeddings miss).
 *
 * <p>Tokenizer: {@code trigram} (§10.4) — indexes overlapping 3-character windows instead of
 * whitespace-delimited words, so a query is a substring match rather than a whole-token match.
 * This lets a bare stem query find an inflected/suffixed form it never shares a whole
 * {@code unicode61} word-token with (e.g. querying "인덱싱" finds content containing
 * "인덱싱됩니다"), and lets a partial code/identifier find a longer one containing it (e.g.
 * "ERR45" finds "ERR4521"). It does <b>not</b> bridge two independently-inflected forms of the
 * same word that share no 3+-character run (e.g. "문서를" vs. "문서가" — "문서" alone is only
 * 2 characters) — true morphological stemming needs a custom FTS5 tokenizer (mecab-ko or
 * similar), out of scope here (no maintained loadable extension, same closed-network binary
 * burden as vec0). Trade-off: any search term shorter than 3 characters cannot match anything (no
 * trigram exists), so {@link #toMatchQuery(String)} drops sub-3-char terms — the vector search
 * axis is unaffected.
 *
 * <p>Degrades gracefully: if the SQLite build lacks FTS5, {@link #isAvailable()} stays false
 * and all operations become no-ops, so neither startup nor indexing is affected.
 * Populated on every index; consumed by retrieval only when hybrid search is enabled.
 */
@Component
public class KeywordSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchRepository.class);

    private static final String CHUNK_FTS = "chunk_fts";
    private static final Pattern IMAGE_PATH_MARKER = Pattern.compile("\\[이미지:\\s*([^\\]]+)]");

    private static final String CREATE_CHUNK_FTS_SQL = """
            CREATE VIRTUAL TABLE IF NOT EXISTS %s USING fts5(
                spring_doc_id UNINDEXED,
                doc_id        UNINDEXED,
                version       UNINDEXED,
                filename      UNINDEXED,
                page          UNINDEXED,
                chapter       UNINDEXED,
                chunk_index   UNINDEXED,
                doc_tags      UNINDEXED,
                content,
                keywords,
                tokenize = 'trigram'
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
        if (columns.isEmpty()) return; // freshly created above — already correct schema

        boolean hasDocTags = columns.contains("doc_tags");
        boolean hasChapter = columns.contains("chapter");
        boolean isTrigram = usesTrigramTokenizer(CHUNK_FTS);
        if (!hasDocTags || !hasChapter || !isTrigram) {
            log.warn("[KEYWORD] Legacy chunk_fts schema detected (doc_tags={}, chapter={}, trigram={}). Rebuilding FTS table.",
                    hasDocTags, hasChapter, isTrigram);
            // §10.4: a straight INSERT...SELECT into the new table re-tokenizes every row under
            // trigram (FTS5 tokenizes column values at insert time, not at read time), so existing
            // content/keywords/doc_tags survive the rebuild whenever the source already has doc_tags.
            rebuildChunkFts(hasDocTags, hasChapter);
            log.warn("[KEYWORD] chunk_fts rebuild completed.");
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

    /** FTS5 stores the {@code CREATE VIRTUAL TABLE} text verbatim in sqlite_master — no PRAGMA exposes the tokenizer. */
    private boolean usesTrigramTokenizer(String tableName) {
        String sql = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", String.class, tableName);
        return sql != null && sql.contains("trigram");
    }

    private void rebuildChunkFts(boolean sourceHasDocTags, boolean sourceHasChapter) {
        final String tempTable = CHUNK_FTS + "_v2";
        String docTagsSelect = sourceHasDocTags ? "doc_tags" : "''"; // ancient schema predates doc_tags entirely
        String chapterSelect = sourceHasChapter ? "chapter" : "''";
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + tempTable);
            createChunkFtsTable(tempTable);
            try {
                jdbc.update(("""
                        INSERT INTO %s
                            (spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content, keywords)
                        SELECT spring_doc_id, doc_id, version, filename, page, %s, chunk_index, %s, content, keywords
                        FROM %s
                        """).formatted(tempTable, chapterSelect, docTagsSelect, CHUNK_FTS));
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
                        (spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content, keywords)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunks.stream().map(d -> {
                        Map<String, Object> m = d.getMetadata();
                        return new Object[]{
                                d.getId(),
                                str(m.get(MetaKey.DOC_ID)),
                                str(m.get(MetaKey.VERSION)),
                                str(m.get(MetaKey.FILENAME)),
                                str(m.get(MetaKey.PAGE_OR_SLIDE)),
                                str(m.get(MetaKey.CHAPTER_NO)),
                                str(m.get(MetaKey.CHUNK_INDEX)),
                                str(m.get(MetaKey.TAGS)),     // 태그(쉼표 결합) — 검색 결과에 동행
                                SearchTextBuilder.build(d),   // 맥락+정규화 텍스트 (Contextual BM25, §10.1)
                                str(m.get(MetaKey.EXCERPT_KEYWORDS))
                        };
                    }).toList());
        } catch (Exception e) {
            log.debug("[KEYWORD] indexChunks failed: {}", e.getMessage());
        }
    }

    /**
     * Overwrites {@code doc_tags} for every chunk row of a document. Returns the number of rows
     * updated (0 when FTS5 is unavailable, on error, or when no chunk_fts row exists for this
     * {@code docId} — e.g. an orphaned {@code doc_registry} entry left behind by a prior indexing
     * failure) so the caller can detect a no-op write instead of reporting a false success.
     */
    public int updateDocTags(String docId, String tagsCsv) {
        if (!available || docId == null) return 0;
        try {
            return jdbc.update("UPDATE chunk_fts SET doc_tags = ? WHERE doc_id = ?", tagsCsv, docId);
        } catch (Exception e) {
            log.warn("[KEYWORD] updateDocTags failed docId={}: {}", docId, e.getMessage());
            return 0;
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

    /** Shared row mapper for both the BM25 MATCH path and the §10.7.3 LIKE fallback path. */
    private static final RowMapper<Document> CHUNK_ROW_MAPPER = (rs, n) -> {
        String content = rs.getString("content");
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, rs.getString("doc_id"));
        meta.put(MetaKey.VERSION, rs.getString("version"));
        meta.put(MetaKey.FILENAME, rs.getString("filename"));
        meta.put(MetaKey.PAGE_OR_SLIDE, rs.getString("page"));
        meta.put(MetaKey.CHAPTER_NO, rs.getString("chapter"));
        meta.put(MetaKey.CHUNK_INDEX, rs.getString("chunk_index"));
        meta.put(MetaKey.TAGS, rs.getString("doc_tags"));  // 태그 동행
        String imagePaths = extractImagePaths(content);
        if (!imagePaths.isBlank()) {
            meta.put(MetaKey.IMAGE_PATHS, imagePaths);
        }
        return Document.builder()
                .id(rs.getString("spring_doc_id"))
                .text(content)
                .metadata(meta)
                .build();
    };

    private static String extractImagePaths(String text) {
        if (text == null || text.isBlank()) return "";
        List<String> paths = new ArrayList<>();
        Matcher m = IMAGE_PATH_MARKER.matcher(text);
        while (m.find()) {
            String path = m.group(1) == null ? "" : m.group(1).strip();
            if (!path.isEmpty() && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return String.join(",", paths);
    }

    /**
     * BM25-ranked lexical search over content + keywords, filtered by version.
     * Returns Documents carrying the same metadata keys vector results use so RRF dedup
     * (via {@code doc_id:chunk_index}) merges the two sources cleanly.
     *
     * <p>§10.7.3 — query terms under 3 characters produce no trigram (the {@code trigram}
     * tokenizer's floor) and are dropped by {@link #toMatchQuery}, so a query like "오류" alone
     * used to return zero BM25-axis candidates. Any such short terms are supplemented with a
     * {@code LIKE} scan over content + keywords, appended after the ranked MATCH results (so they
     * land at a worse RRF position within this axis — no real BM25 score, just an existence
     * signal) and de-duplicated by {@code spring_doc_id} against the MATCH results.
     */
    public List<Document> search(String version, String question, int topK) {
        if (!available) return List.of();
        List<Document> results = new ArrayList<>(matchSearch(question, version, topK));
        List<String> shortTerms = shortTerms(question);
        int remaining = topK - results.size();
        if (!shortTerms.isEmpty() && remaining > 0) {
            Set<String> seen = new java.util.HashSet<>();
            for (Document d : results) seen.add(d.getId());
            for (Document d : likeSearch(shortTerms, version, remaining)) {
                if (seen.add(d.getId())) results.add(d);
            }
        }
        return results;
    }

    private List<Document> matchSearch(String question, String version, int topK) {
        String match = toMatchQuery(question);
        if (match == null) return List.of();
        try {
            return jdbc.query("""
                    SELECT spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content
                    FROM chunk_fts
                    WHERE chunk_fts MATCH ? AND version = ?
                    ORDER BY bm25(chunk_fts)
                    LIMIT ?
                    """,
                    CHUNK_ROW_MAPPER,
                    match, version, topK);
        } catch (Exception e) {
            log.debug("[KEYWORD] search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * §10.7.3 fallback for terms too short to have a trigram — plain substring scan, no BM25 rank
     * available so results are ordered by {@code rowid} for determinism. Full-table scan, but the
     * local-SQLite corpus sizes this targets keep it cheap.
     */
    private List<Document> likeSearch(List<String> terms, String version, int limit) {
        String whereClause = terms.stream()
                .map(t -> "(content LIKE ? OR keywords LIKE ?)")
                .collect(java.util.stream.Collectors.joining(" OR "));
        List<Object> params = new ArrayList<>();
        for (String t : terms) {
            String pattern = "%" + t + "%";
            params.add(pattern);
            params.add(pattern);
        }
        params.add(version);
        params.add(limit);
        try {
            return jdbc.query(("""
                    SELECT spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content
                    FROM chunk_fts
                    WHERE (%s) AND version = ?
                    ORDER BY rowid
                    LIMIT ?
                    """).formatted(whereClause),
                    CHUNK_ROW_MAPPER,
                    params.toArray());
        } catch (Exception e) {
            log.debug("[KEYWORD] short-term LIKE search failed: {}", e.getMessage());
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
     * Same as {@link #distinctTags(String)}, but drops any tag present on <b>every</b> document
     * in scope. Such a tag adds no filtering value as a suggestion chip (selecting it is
     * equivalent to selecting nothing), and the set grows only more useless as more documents
     * are tagged consistently. A document that itself carries no tags makes this intersection
     * empty by definition (there is no tag it shares with the others).
     */
    public List<String> distinctTagsExcludingCommon(String version) {
        if (!available) return List.of();
        String sql = "SELECT DISTINCT doc_id, doc_tags FROM chunk_fts WHERE doc_id IS NOT NULL"
                + (version != null && !version.isBlank() ? " AND version = ?" : "");
        try {
            Map<String, Set<String>> perDoc = new HashMap<>();
            jdbc.query(sql, rs -> {
                String docId = rs.getString("doc_id");
                if (docId == null) return;
                Set<String> tags = perDoc.computeIfAbsent(docId, __ -> new TreeSet<>());
                String row = rs.getString("doc_tags");
                if (row == null) return;
                for (String t : row.split(",")) {
                    String s = t.strip();
                    if (!s.isEmpty()) tags.add(s);
                }
            }, (version != null && !version.isBlank()) ? new Object[]{version} : new Object[]{});

            if (perDoc.isEmpty()) return List.of();

            Set<String> common = null;
            TreeSet<String> all = new TreeSet<>();
            for (Set<String> tags : perDoc.values()) {
                all.addAll(tags);
                common = (common == null) ? new TreeSet<>(tags) : intersect(common, tags);
            }
            all.removeAll(common);
            return List.copyOf(all);
        } catch (Exception e) {
            log.debug("[KEYWORD] distinctTagsExcludingCommon failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.retainAll(b);
        return out;
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
     * cannot break the query, and so multi-trigram tokens are matched as an adjacent phrase rather
     * than an unordered bag of trigrams) and OR-combined for recall. Terms under 3 characters are
     * dropped — the {@code trigram} tokenizer (§10.4) cannot produce a trigram from fewer than 3
     * characters, so shorter terms are guaranteed to match nothing (see {@link #shortTerms} for
     * the §10.7.3 LIKE-scan fallback that recovers a signal for exactly those terms).
     */
    static String toMatchQuery(String question) {
        List<String> terms = tokenize(question).stream()
                .filter(t -> t.length() >= 3)
                .map(t -> "\"" + t + "\"")
                .toList();
        return terms.isEmpty() ? null : String.join(" OR ", terms);
    }

    /**
     * §10.7.3 — tokens shorter than the trigram floor (3 chars), which {@link #toMatchQuery} would
     * otherwise silently drop. De-duplicated; empty when every token is 3+ characters.
     */
    static List<String> shortTerms(String question) {
        return tokenize(question).stream().filter(t -> t.length() < 3).distinct().toList();
    }

    /** Whitespace/punctuation-delimited tokens, trimmed and stray-quote-stripped. Blank-safe. */
    private static List<String> tokenize(String question) {
        if (question == null || question.isBlank()) return List.of();
        String[] raw = question.split("[\\s\\p{Punct}]+");
        List<String> tokens = new ArrayList<>();
        for (String t : raw) {
            String s = t.trim().replace("\"", "");
            if (!s.isEmpty()) tokens.add(s);
        }
        return tokens;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
