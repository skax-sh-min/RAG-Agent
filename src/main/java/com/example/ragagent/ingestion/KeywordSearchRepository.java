package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
 *
 * <p><b>{@code chunk_fts_key} — 청크 id 로 FTS 행을 찾는 유일한 길.</b> FTS5 는 {@code MATCH} 와
 * {@code rowid} 외의 조건에 인덱스를 쓰지 못한다: {@code UNINDEXED} 컬럼인 {@code spring_doc_id}
 * 나 {@code doc_id} 로 거는 {@code WHERE} 는 본문까지 읽는 <b>코퍼스 전체 스캔</b>이다. 그 조회가
 * 대화를 열 때(턴마다 출처 미리보기), 턴을 저장할 때(청크 해시 스냅샷), 재사용 검증·원문 보기·
 * 문서 삭제·태그 갱신마다 돌았고, 전부 유일한 커넥션을 잡은 채였다(태그를 {@code doc_registry} 로
 * 옮긴 것과 같은 함정). 그래서 {@code chunk_fts} 에 행을 넣을 때마다 일반 테이블
 * {@code chunk_fts_key} 에 {@code spring_doc_id → rowid} 와 그 행의 위치 컬럼·본문 해시를 함께
 * 적는다. <b>id 로 찾는 조회는 전부 이 테이블을 먼저 타고, FTS 행이 필요하면 {@code rowid}
 * 로 조인한다</b>(FTS5 가 인덱스로 처리하는 유일한 비-MATCH 조건). 두 테이블은 한 트랜잭션에서
 * 함께 쓰고 함께 지운다. 이 테이블이 없던 배포는 {@link #init()} 이 한 번 훑어 채운다
 * ({@code DocTagsBackfill} 과 같은 일회성 이관).
 */
@Component
public class KeywordSearchRepository {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchRepository.class);

    private static final String CHUNK_FTS = "chunk_fts";
    /** {@code chunk_fts} 의 동반 일반 테이블 — 클래스 주석 참고. */
    static final String CHUNK_FTS_KEY = "chunk_fts_key";
    private static final String CREATE_CHUNK_FTS_KEY_SQL = """
            CREATE TABLE IF NOT EXISTS chunk_fts_key (
                spring_doc_id TEXT PRIMARY KEY,
                fts_rowid     INTEGER NOT NULL,
                doc_id        TEXT,
                version       TEXT,
                filename      TEXT,
                page          TEXT,
                chapter       TEXT,
                content_hash  TEXT NOT NULL
            )
            """;
    private static final String CREATE_CHUNK_FTS_KEY_DOC_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_chunk_fts_key_doc ON chunk_fts_key(doc_id)";
    private static final String INSERT_CHUNK_FTS_KEY_SQL = """
            INSERT OR REPLACE INTO chunk_fts_key
                (spring_doc_id, fts_rowid, doc_id, version, filename, page, chapter, content_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    /** 기동 시 백필이 한 번에 읽는 FTS 행 수 — rowid 범위 스캔이라 페이지가 커도 인덱스를 탄다. */
    private static final int KEY_BACKFILL_PAGE = 500;
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
    /** Lazily built from the template's DataSource; null only for a fully mocked template
     *  (unit tests), in which case the two-table writes below simply run unwrapped. */
    private volatile TransactionTemplate transactionTemplate;

    // chunk_fts lives with the vector tables: vectorJdbcTemplate → vector.db when the
    // separate-vector-DB switch is on, else the operational memory.db (chroma / non-separated).
    public KeywordSearchRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        try {
            ensureChunkFtsSchema();
            ensureChunkFtsKey();
            available = true;
            log.info("[KEYWORD] FTS5 chunk_fts ready — hybrid search available");
        } catch (Exception e) {
            available = false;
            log.warn("[KEYWORD] FTS5 unavailable — hybrid search disabled: {}", e.getMessage());
        }
    }

    /**
     * {@code chunk_fts_key} 를 만들고 {@code chunk_fts} 와 맞춘다. FTS 행보다 키가 적으면(이 테이블이
     * 없던 배포의 첫 기동) 빠진 것을 채우고, 많으면(삭제가 중간에 끊긴 흔적) FTS 행이 사라진 키를
     * 지운다. 두 COUNT 는 FTS 전체를 한 번 훑지만 기동 시 한 번뿐이고, 백필 자체는 rowid 범위
     * 스캔이라 페이지마다 인덱스를 탄다.
     */
    private void ensureChunkFtsKey() {
        jdbc.execute(CREATE_CHUNK_FTS_KEY_SQL);
        jdbc.execute(CREATE_CHUNK_FTS_KEY_DOC_INDEX_SQL);
        long ftsRows = countRows(CHUNK_FTS);
        long keyRows = countRows(CHUNK_FTS_KEY);
        if (keyRows > ftsRows) {
            int dropped = jdbc.update(
                    "DELETE FROM chunk_fts_key WHERE fts_rowid NOT IN (SELECT rowid FROM chunk_fts)");
            log.warn("[KEYWORD] chunk_fts_key 정리 — FTS 행이 사라진 키 {}건 삭제", dropped);
            keyRows = countRows(CHUNK_FTS_KEY);
        }
        if (keyRows < ftsRows) {
            int added = backfillChunkFtsKey();
            log.info("[KEYWORD] chunk_fts_key 백필 완료 — FTS {}행 중 키 {}건 추가 (출처: chunk_fts rowid 스캔)",
                    ftsRows, added);
        }
    }

    private long countRows(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    /** rowid 순으로 페이지를 넘기며 빠진 키만 넣는다({@code INSERT OR IGNORE}). 추가한 행 수를 돌려준다. */
    private int backfillChunkFtsKey() {
        int added = 0;
        long lastRowid = 0;
        while (true) {
            List<Object[]> keyRows = new ArrayList<>(KEY_BACKFILL_PAGE);
            List<Long> rowids = jdbc.query("""
                    SELECT rowid, spring_doc_id, doc_id, version, filename, page, chapter, content
                    FROM chunk_fts
                    WHERE rowid > ?
                    ORDER BY rowid
                    LIMIT ?
                    """,
                    (rs, n) -> {
                        long rowid = rs.getLong("rowid");
                        keyRows.add(new Object[]{
                                rs.getString("spring_doc_id"), rowid,
                                rs.getString("doc_id"), rs.getString("version"), rs.getString("filename"),
                                rs.getString("page"), rs.getString("chapter"),
                                contentHash(rs.getString("content"))});
                        return rowid;
                    },
                    lastRowid, KEY_BACKFILL_PAGE);
            if (rowids.isEmpty()) return added;
            int[] counts = jdbc.batchUpdate("""
                    INSERT OR IGNORE INTO chunk_fts_key
                        (spring_doc_id, fts_rowid, doc_id, version, filename, page, chapter, content_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, keyRows);
            for (int c : counts) if (c > 0) added += c;
            lastRowid = rowids.get(rowids.size() - 1);
        }
    }

    /**
     * {@code turn_source_ref.chunk_hash} 와 {@code chunk_fts_key.content_hash} 가 공유하는 해시 —
     * 입력은 FTS 에 저장된 파생 검색 텍스트({@link SearchTextBuilder#build})다. 이 함수를 바꾸면
     * 저장된 스냅샷 전부가 "수정됨"으로 읽히므로, 바꿀 일이 있으면 마이그레이션이 함께 가야 한다.
     */
    public static String contentHash(String searchText) {
        String src = searchText == null ? "" : searchText;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(src.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 두 테이블을 함께 쓰는 자리는 전부 한 트랜잭션이다 — 한쪽만 남으면 id 조회가 조용히 빈다. */
    private void inTransaction(Runnable work) {
        TransactionTemplate tx = transactionTemplate();
        if (tx == null) {
            work.run();
        } else {
            tx.executeWithoutResult(status -> work.run());
        }
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate tt = transactionTemplate;
        if (tt == null) {
            DataSource ds = jdbc.getDataSource();
            tt = ds != null ? new TransactionTemplate(new DataSourceTransactionManager(ds)) : null;
            transactionTemplate = tt;
        }
        return tt;
    }

    /** 다음 FTS 행이 받을 rowid 의 바닥 — {@code ORDER BY rowid DESC LIMIT 1} 은 FTS5 가 인덱스로 푼다. */
    private long maxRowid() {
        List<Long> rows = jdbc.query("SELECT rowid FROM chunk_fts ORDER BY rowid DESC LIMIT 1",
                (rs, n) -> rs.getLong(1));
        return rows.isEmpty() ? 0L : rows.get(0);
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
                // rowid 를 그대로 옮긴다 — chunk_fts_key 가 그 값으로 FTS 행을 가리킨다.
                jdbc.update(("""
                        INSERT INTO %s
                            (rowid, spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content, keywords)
                        SELECT rowid, spring_doc_id, doc_id, version, filename, page, %s, chunk_index, %s, content, keywords
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

    /**
     * Bulk-inserts chunk rows for one document, plus their {@code chunk_fts_key} rows, in one
     * transaction. No-op when FTS5 is unavailable.
     *
     * <p>rowid 를 직접 정한다({@code MAX(rowid)+1..n}) — 그래야 두 테이블을 배치 하나씩으로 쓸 수
     * 있다. pool=1 이라 트랜잭션 안에서는 다른 스레드가 끼어들 수 없으므로 이 할당은 안전하다.
     */
    public void indexChunks(List<Document> chunks) {
        if (!available || chunks == null || chunks.isEmpty()) return;
        try {
            inTransaction(() -> {
                long base = maxRowid();
                List<Object[]> ftsRows = new ArrayList<>(chunks.size());
                List<Object[]> keyRows = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    Document d = chunks.get(i);
                    Map<String, Object> m = d.getMetadata();
                    long rowid = base + 1 + i;
                    String content = SearchTextBuilder.build(d);   // 맥락+정규화 텍스트 (Contextual BM25, §10.1)
                    ftsRows.add(new Object[]{
                            rowid,
                            d.getId(),
                            str(m.get(MetaKey.DOC_ID)),
                            str(m.get(MetaKey.VERSION)),
                            str(m.get(MetaKey.FILENAME)),
                            str(m.get(MetaKey.PAGE_OR_SLIDE)),
                            str(m.get(MetaKey.CHAPTER_NO)),
                            str(m.get(MetaKey.CHUNK_INDEX)),
                            str(m.get(MetaKey.TAGS)),     // 태그(쉼표 결합) — 검색 결과에 동행
                            content,
                            str(m.get(MetaKey.EXCERPT_KEYWORDS))
                    });
                    keyRows.add(new Object[]{
                            d.getId(), rowid,
                            str(m.get(MetaKey.DOC_ID)),
                            str(m.get(MetaKey.VERSION)),
                            str(m.get(MetaKey.FILENAME)),
                            str(m.get(MetaKey.PAGE_OR_SLIDE)),
                            str(m.get(MetaKey.CHAPTER_NO)),
                            contentHash(content)
                    });
                }
                jdbc.batchUpdate("""
                        INSERT INTO chunk_fts
                            (rowid, spring_doc_id, doc_id, version, filename, page, chapter, chunk_index, doc_tags, content, keywords)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, ftsRows);
                jdbc.batchUpdate(INSERT_CHUNK_FTS_KEY_SQL, keyRows);
            });
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
            return jdbc.update(
                    "UPDATE chunk_fts SET doc_tags = ? WHERE rowid IN (SELECT fts_rowid FROM chunk_fts_key WHERE doc_id = ?)",
                    tagsCsv, docId);
        } catch (Exception e) {
            log.warn("[KEYWORD] updateDocTags failed docId={}: {}", docId, e.getMessage());
            return 0;
        }
    }

    /** Removes all FTS rows (and their keys) for a document. No-op when FTS5 is unavailable. */
    public void deleteByDocId(String docId) {
        if (!available || docId == null) return;
        try {
            inTransaction(() -> {
                jdbc.update("DELETE FROM chunk_fts WHERE rowid IN (SELECT fts_rowid FROM chunk_fts_key WHERE doc_id = ?)",
                        docId);
                jdbc.update("DELETE FROM chunk_fts_key WHERE doc_id = ?", docId);
            });
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
            Object[] args = springDocIds.toArray();
            inTransaction(() -> {
                jdbc.update("DELETE FROM chunk_fts WHERE rowid IN "
                        + "(SELECT fts_rowid FROM chunk_fts_key WHERE spring_doc_id IN (" + placeholders + "))", args);
                jdbc.update("DELETE FROM chunk_fts_key WHERE spring_doc_id IN (" + placeholders + ")", args);
            });
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

    // distinctTags(version) / distinctTagsExcludingCommon(version) 은 DocRegistry 로 옮겼다.
    // 둘 다 chunk_fts 를 통째로 훑는 쿼리였는데(UNINDEXED 컬럼 + DISTINCT), 세는 단위가 청크가
    // 아니라 문서였으므로 애초에 문서 단위 테이블에 있어야 할 것들이었다.

    /**
     * Returns distinct tags per doc_id (comma-split from doc_tags), sorted and de-duplicated.
     * No-op (empty map) when FTS5 is unavailable or docIds is empty.
     *
     * <p><b>{@link DocTagsBackfill} 전용이다 — 새 호출자를 붙이지 말 것.</b> {@code doc_tags} 는
     * FTS5 의 {@code UNINDEXED} 컬럼이라(FTS5 는 인덱스를 만들 수 없다) 이 {@code WHERE doc_id IN
     * (...)} 은 <b>코퍼스 전체 스캔</b>이고, 스캔하는 행에는 청크 본문이 들어 있다. 예전에는
     * {@code RagService.listDocuments()} 가 이걸 불러서 문서 목록·관리자 화면·{@code /admin/chunks}
     * 페이지 넘김마다 코퍼스를 훑었다. 태그의 출처는 이제 {@code doc_registry.tags} 이고
     * ({@link DocRegistry#tagsByDocIds}), 행 수가 문서 수인 인덱스된 테이블이다.
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
