package com.example.ragagent.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
