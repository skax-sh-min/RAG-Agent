package com.example.ragagent.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ragagent.model.TagUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Registry of indexed documents — persisted in SQLite.
 * All mutations are immediately durable; save()/saveQuiet() are kept as no-ops for API compatibility.
 */
@Component
public class DocRegistry {

    /** Shared owner key used when document isolation per user is not needed. */
    public static final String SHARED = "shared";

    private static final Logger log = LoggerFactory.getLogger(DocRegistry.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DocRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS doc_registry (
                    doc_id         TEXT NOT NULL,
                    user_id        TEXT NOT NULL DEFAULT 'anonymous',
                    sha256         TEXT NOT NULL,
                    version        TEXT NOT NULL,
                    indexed_at     TEXT NOT NULL,
                    chunks         INTEGER NOT NULL,
                    spring_doc_ids TEXT NOT NULL,
                    errors         TEXT NOT NULL,
                    PRIMARY KEY (doc_id, user_id)
                )
                """);
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_doc_registry_user_version ON doc_registry(user_id, version)");
        jdbc.execute(
                "CREATE INDEX IF NOT EXISTS idx_doc_registry_sha_version ON doc_registry(sha256, version, user_id)");
        addChunkOverlapColumn();
        addDisplayNameColumn();
        addTagsColumn();
        log.debug("[REGISTRY] SQLite 초기화 완료");
    }

    /**
     * Defensive ALTER for the {@code chunk_overlap} column (same precedent as
     * {@code SqliteMemoryRepository.init()}'s added columns — {@code V1__baseline.sql} is never
     * edited). Nullable on purpose: a pre-existing row's real overlap is genuinely unknown until
     * {@link #backfillMissingChunkOverlap} fills it in at startup.
     */
    private void addChunkOverlapColumn() {
        try {
            jdbc.execute("ALTER TABLE doc_registry ADD COLUMN chunk_overlap INTEGER");
            log.info("[REGISTRY] doc_registry.chunk_overlap 컬럼 추가");
        } catch (DataAccessException e) {
            log.debug("[REGISTRY] chunk_overlap 컬럼이 이미 존재함");   // duplicate column name
        }
    }

    /**
     * Defensive ALTER for the {@code display_name} column — a purely cosmetic per-document
     * override (see {@link DocRegistryEntry#displayName}). {@code NULL} means "no override, show
     * the real filename", which is also what every pre-existing row gets automatically.
     */
    private void addDisplayNameColumn() {
        try {
            jdbc.execute("ALTER TABLE doc_registry ADD COLUMN display_name TEXT");
            log.info("[REGISTRY] doc_registry.display_name 컬럼 추가");
        } catch (DataAccessException e) {
            log.debug("[REGISTRY] display_name 컬럼이 이미 존재함");   // duplicate column name
        }
    }

    /**
     * Stamps the currently configured overlap onto rows indexed before the column existed. The
     * value is a best guess — the true one wasn't recorded — but it is the only defensible one:
     * these documents were indexed by a build whose overlap came from this same setting. Runs once
     * at startup and never overwrites a known value.
     *
     * @return number of rows backfilled
     */
    public int backfillMissingChunkOverlap(int currentOverlap) {
        int updated = jdbc.update(
                "UPDATE doc_registry SET chunk_overlap = ? WHERE chunk_overlap IS NULL", currentOverlap);
        if (updated > 0) {
            log.info("[REGISTRY] chunk_overlap 미기록 문서 {}건에 현재 설정값({}) 적용", updated, currentOverlap);
        }
        return updated;
    }

    /**
     * Defensive ALTER for the {@code tags} column — 문서의 검색 스코프 태그(CSV).
     *
     * <p><b>왜 여기인가.</b> 태그는 문서 단위 속성인데 그동안 <b>청크마다 복제된</b>
     * {@code chunk_fts.doc_tags} 가 유일한 출처였다. {@code chunk_fts} 는 FTS5 가상 테이블이고
     * 그 컬럼은 {@code UNINDEXED} 라 — FTS5 는 인덱스를 만들 수 없다 — {@code WHERE doc_id IN (...)}
     * 이 <b>코퍼스 전체 스캔</b>이었다. 그런데 그 조회를 {@code RagService.listDocuments()} 가
     * 부르고, 그건 문서 목록·관리자 화면·{@code /admin/chunks} 페이지 넘김마다 돈다. 즉 문서를
     * 나열할 때마다 청크 수만 행(본문 포함)을 읽고 있었다. {@code doc_registry} 는 이미 문서 단위
     * 일반 테이블이라 여기서는 문서 수만큼만 읽고 PK 인덱스도 탄다.
     *
     * <p>{@code chunk_fts.doc_tags} 는 <b>남는다</b> — 검색 결과에 태그를 동행시키는
     * ({@code CHUNK_ROW_MAPPER} → {@code MetaKey.TAGS} → {@code filterByTags}) 비정규화 사본이다.
     * 이 컬럼이 권위 있는 출처이고 그쪽은 검색 경로용 사본이라는 관계만 지키면 된다.
     *
     * <p>{@code NULL} 과 빈 문자열은 다르다: {@code NULL} = "아직 백필되지 않음"(옛 행),
     * 빈 문자열 = "태그 없음"이다. {@code DocTagsBackfill} 이 그 구분으로 멱등성을 얻는다
     * ({@code chunk_overlap} 과 같은 패턴).
     */
    private void addTagsColumn() {
        try {
            jdbc.execute("ALTER TABLE doc_registry ADD COLUMN tags TEXT");
            log.info("[REGISTRY] doc_registry.tags 컬럼 추가");
        } catch (DataAccessException e) {
            log.debug("[REGISTRY] tags 컬럼이 이미 존재함");   // duplicate column name
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    public void put(String docId, String userId, DocRegistryEntry entry) {
        jdbc.update("""
                INSERT INTO doc_registry (doc_id, user_id, sha256, version, indexed_at, chunks, spring_doc_ids, errors, chunk_overlap, display_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(doc_id, user_id) DO UPDATE SET
                  sha256=excluded.sha256, version=excluded.version,
                  indexed_at=excluded.indexed_at, chunks=excluded.chunks,
                  spring_doc_ids=excluded.spring_doc_ids, errors=excluded.errors,
                  chunk_overlap=excluded.chunk_overlap, display_name=excluded.display_name
                """,
                docId, userId, entry.sha256(), entry.version(), entry.indexedAt(),
                entry.chunks(), toJson(entry.springDocIds()), toJson(entry.errors()),
                entry.chunkOverlap(), entry.displayName());
    }

    /** Updates only the display-name override, leaving every other column untouched.
     *  @return rows affected (0 = no such document) */
    public int updateDisplayName(String docId, String userId, String displayName) {
        return jdbc.update(
                "UPDATE doc_registry SET display_name = ? WHERE doc_id = ? AND user_id = ?",
                displayName, docId, userId);
    }

    /** Updates only the search-scope tags, leaving every other column untouched (same shape as
     *  {@link #updateDisplayName}). {@code tagsCsv} 는 {@code TagUtils.toMetaValue()} 형식이며
     *  빈 문자열은 "태그 없음"이다(NULL 과 다르다 — 위 {@link #addTagsColumn} 참조).
     *  @return rows affected (0 = no such document) */
    public int updateTags(String docId, String userId, String tagsCsv) {
        return jdbc.update(
                "UPDATE doc_registry SET tags = ? WHERE doc_id = ? AND user_id = ?",
                tagsCsv == null ? "" : tagsCsv, docId, userId);
    }

    /**
     * 문서별 태그 — {@code RagService.listDocuments()} 의 목록 조회용. 한 번의 질의로 끝내며
     * 행 수는 <b>문서 수</b>다(예전 {@code chunk_fts} 경로는 청크 수만큼을 본문까지 읽었다).
     * 태그가 없는 문서는 결과에 없으므로 호출자는 빈 목록으로 떨어지면 된다.
     */
    public Map<String, List<String>> tagsByDocIds(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return Map.of();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(docIds));
        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> params = new ArrayList<>(ids.size() + 1);
        params.add(SHARED);
        params.addAll(ids);
        Map<String, List<String>> out = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT doc_id, tags FROM doc_registry WHERE user_id = ? AND doc_id IN (" + placeholders + ")"
                        + " AND tags IS NOT NULL AND tags <> ''", params.toArray())) {
            List<String> tags = TagUtils.parseCsv(String.valueOf(row.get("tags")));
            if (!tags.isEmpty()) out.put(String.valueOf(row.get("doc_id")), tags);
        }
        return out;
    }

    /** 사용 중인 태그 전체(정렬·중복 제거), 선택적으로 버전 스코프. 태그 제안 UI 용. */
    public List<String> distinctTags(String version) {
        return new ArrayList<>(collectTags(version).allTags());
    }

    /**
     * {@link #distinctTags} 에서 <b>스코프 안 모든 문서가 공통으로 가진</b> 태그를 뺀 목록.
     * 그런 태그는 칩으로 골라도 아무것도 걸러내지 못해 잡음이다(태그가 하나도 없는 문서가 하나라도
     * 있으면 교집합이 비므로 아무것도 빠지지 않는다).
     */
    public List<String> distinctTagsExcludingCommon(String version) {
        TagScan scan = collectTags(version);
        TreeSet<String> all = new TreeSet<>(scan.allTags());
        all.removeAll(scan.commonTags());
        return List.copyOf(all);
    }

    /** {@code tags} 가 아직 채워지지 않은(NULL) 문서 id — {@code DocTagsBackfill} 전용. */
    public List<String> docIdsWithoutTags() {
        return jdbc.queryForList(
                "SELECT doc_id FROM doc_registry WHERE user_id = ? AND tags IS NULL", String.class, SHARED);
    }

    private record TagScan(TreeSet<String> allTags, Set<String> commonTags) {}

    /** 한 번의 스캔으로 전체 태그 집합과 모든 문서의 교집합을 함께 만든다. */
    private TagScan collectTags(String version) {
        boolean scoped = version != null && !version.isBlank();
        String sql = "SELECT tags FROM doc_registry WHERE user_id = ?" + (scoped ? " AND version = ?" : "");
        Object[] args = scoped ? new Object[]{SHARED, version} : new Object[]{SHARED};

        TreeSet<String> all = new TreeSet<>();
        Set<String> common = null;
        for (String csv : jdbc.queryForList(sql, String.class, args)) {
            Set<String> tags = new TreeSet<>(TagUtils.parseCsv(csv == null ? "" : csv));
            all.addAll(tags);
            if (common == null) {
                common = new TreeSet<>(tags);
            } else {
                common.retainAll(tags);
            }
        }
        return new TagScan(all, common == null ? Set.of() : common);
    }

    public Optional<DocRegistryEntry> findByDocId(String docId, String userId) {
        List<DocRegistryEntry> rows = jdbc.query(
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors, chunk_overlap, display_name " +
                "FROM doc_registry WHERE doc_id = ? AND user_id = ?",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"),
                        rs.getString("version"),
                        rs.getString("indexed_at"),
                        rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors")),
                        nullableInt(rs, "chunk_overlap"),
                        rs.getString("display_name")),
                docId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Batch display-name lookup — chat citations (RetrievalService/QuestionReuseService) substitute
     * a document's display name for the real filename in the source label when one is set (§ 표시
     * 이름). One query for a whole turn's retrieved/reused chunks instead of one per chunk. Only
     * docIds with a non-blank override are present in the returned map, so callers can just fall
     * back to the real filename on a missing key.
     */
    public Map<String, String> findDisplayNames(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return Map.of();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(docIds));
        String placeholders = ids.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> params = new ArrayList<>(ids.size() + 1);
        params.add(SHARED);
        params.addAll(ids);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT doc_id, display_name FROM doc_registry WHERE user_id = ? AND doc_id IN (" + placeholders + ")",
                params.toArray());
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("display_name");
            if (name instanceof String s && !s.isBlank()) {
                out.put(String.valueOf(row.get("doc_id")), s);
            }
        }
        return out;
    }

    /** Finds by docId ignoring owner — for admin/reindex operations. */
    public Optional<DocRegistryEntry> findByDocId(String docId) {
        List<DocRegistryEntry> rows = jdbc.query(
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors, chunk_overlap, display_name " +
                "FROM doc_registry WHERE doc_id = ? LIMIT 1",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"),
                        rs.getString("version"),
                        rs.getString("indexed_at"),
                        rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors")),
                        nullableInt(rs, "chunk_overlap"),
                        rs.getString("display_name")),
                docId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void remove(String docId, String userId) {
        jdbc.update("DELETE FROM doc_registry WHERE doc_id = ? AND user_id = ?", docId, userId);
    }

    public Set<String> docIds(String userId) {
        return new HashSet<>(jdbc.query(
                "SELECT doc_id FROM doc_registry WHERE user_id = ?",
                (rs, n) -> rs.getString("doc_id"),
                userId));
    }

    public Collection<DocRegistryEntry> values(String userId) {
        return jdbc.query(
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors, chunk_overlap, display_name " +
                "FROM doc_registry WHERE user_id = ?",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"), rs.getString("version"),
                        rs.getString("indexed_at"), rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors")),
                        nullableInt(rs, "chunk_overlap"),
                        rs.getString("display_name")),
                userId);
    }

    public Set<Map.Entry<String, DocRegistryEntry>> entries(String userId) {
        Map<String, DocRegistryEntry> map = new LinkedHashMap<>();
        jdbc.query(
                "SELECT doc_id, sha256, version, indexed_at, chunks, spring_doc_ids, errors, chunk_overlap, display_name " +
                "FROM doc_registry WHERE user_id = ? ORDER BY indexed_at DESC",
                rs -> {
                    map.put(rs.getString("doc_id"), new DocRegistryEntry(
                            rs.getString("sha256"), rs.getString("version"),
                            rs.getString("indexed_at"), rs.getInt("chunks"),
                            fromJsonList(rs.getString("spring_doc_ids")),
                            fromJsonList(rs.getString("errors")),
                            nullableInt(rs, "chunk_overlap"),
                            rs.getString("display_name")));
                },
                userId);
        return map.entrySet();
    }

    // ── Query helpers ──────────────────────────────────────────────────────

    /**
     * {@code chunks > 0} excludes a partial row left behind by {@code DocumentIndexer.index()}
     * when MD conversion/correction succeeded but chunking/embedding failed afterward — otherwise
     * {@code syncDirectory()}'s detection step would treat that unfinished document as already
     * indexed (matching sha256+version) and skip it on every future sync, forever.
     */
    public boolean existsBySha256AndVersion(String sha256, String version, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM doc_registry WHERE sha256 = ? AND version = ? AND user_id = ? AND chunks > 0",
                Integer.class, sha256, version, userId);
        return count != null && count > 0;
    }

    /**
     * True if some other doc_id (any user/version) shares this sha256 — the images directory is
     * keyed by content hash, so a delete must not remove it out from under a content-identical
     * duplicate document that is still live.
     */
    public boolean existsOtherBySha256(String sha256, String excludeDocId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM doc_registry WHERE sha256 = ? AND doc_id <> ?",
                Integer.class, sha256, excludeDocId);
        return count != null && count > 0;
    }

    public Optional<String> findStaleDocId(String filename, String newDocId, String version, String userId) {
        return jdbc.query(
                "SELECT doc_id FROM doc_registry WHERE version = ? AND user_id = ?",
                (rs, n) -> rs.getString("doc_id"),
                version, userId)
                .stream()
                .filter(id -> id.startsWith(filename + "_") && !id.equals(newDocId))
                .findFirst();
    }

    // ── Persistence (no-ops — SQLite persists immediately) ────────────────

    public void save() {}

    public void saveQuiet() {}

    // ── Static utility ─────────────────────────────────────────────────────

    public static String filenameFromDocId(String docId) {
        int idx = docId.lastIndexOf('_');
        return idx > 0 ? docId.substring(0, idx) : docId;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private String toJson(List<String> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** {@code getInt()} maps SQL NULL to 0, which is a valid overlap — read it as null instead. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    // ── Registry entry ─────────────────────────────────────────────────────

    public record DocRegistryEntry(
            String sha256,
            String version,
            String indexedAt,
            int chunks,
            List<String> springDocIds,
            List<String> errors,
            /**
             * {@code app.chunk-overlap} this document was actually indexed with. Document export
             * needs the value in force when the chunks were cut, not today's setting — the two
             * differ whenever the operator retunes chunking after indexing, and feeding the wrong
             * one to {@code ChunkReassembler} makes its overlap-removal step look for text that
             * isn't there (or miss text that is). {@code null} only for a row written before this
             * column existed and not yet backfilled ({@link #backfillMissingChunkOverlap}).
             */
            Integer chunkOverlap,
            /**
             * Operator-set cosmetic alias shown instead of the (often long) real filename in the
             * document list and admin registry view. {@code null}/blank = no override, fall back
             * to the filename. Never touched by indexing/re-indexing except to carry it forward
             * onto the new row — the underlying {@code docId}, vector-store ids, and converted MD
             * file paths are entirely unaffected, so setting or clearing it is always safe and
             * instantly reversible.
             */
            String displayName
    ) {
        /** Legacy 7-arg form — display name unknown/unset. Kept so existing call sites and older
         *  fixtures don't have to state a value most rows never had. */
        public DocRegistryEntry(String sha256, String version, String indexedAt, int chunks,
                                List<String> springDocIds, List<String> errors, Integer chunkOverlap) {
            this(sha256, version, indexedAt, chunks, springDocIds, errors, chunkOverlap, null);
        }

        /** Legacy 6-arg form — overlap and display name unknown. Kept so existing call sites and
         *  older fixtures don't have to state values they never had. */
        public DocRegistryEntry(String sha256, String version, String indexedAt, int chunks,
                                List<String> springDocIds, List<String> errors) {
            this(sha256, version, indexedAt, chunks, springDocIds, errors, null, null);
        }
    }
}
