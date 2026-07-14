package com.example.ragagent.ingestion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
        log.debug("[REGISTRY] SQLite 초기화 완료");
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    public void put(String docId, String userId, DocRegistryEntry entry) {
        jdbc.update("""
                INSERT INTO doc_registry (doc_id, user_id, sha256, version, indexed_at, chunks, spring_doc_ids, errors)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(doc_id, user_id) DO UPDATE SET
                  sha256=excluded.sha256, version=excluded.version,
                  indexed_at=excluded.indexed_at, chunks=excluded.chunks,
                  spring_doc_ids=excluded.spring_doc_ids, errors=excluded.errors
                """,
                docId, userId, entry.sha256(), entry.version(), entry.indexedAt(),
                entry.chunks(), toJson(entry.springDocIds()), toJson(entry.errors()));
    }

    public Optional<DocRegistryEntry> findByDocId(String docId, String userId) {
        List<DocRegistryEntry> rows = jdbc.query(
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors " +
                "FROM doc_registry WHERE doc_id = ? AND user_id = ?",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"),
                        rs.getString("version"),
                        rs.getString("indexed_at"),
                        rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors"))),
                docId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Finds by docId ignoring owner — for admin/reindex operations. */
    public Optional<DocRegistryEntry> findByDocId(String docId) {
        List<DocRegistryEntry> rows = jdbc.query(
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors " +
                "FROM doc_registry WHERE doc_id = ? LIMIT 1",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"),
                        rs.getString("version"),
                        rs.getString("indexed_at"),
                        rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors"))),
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
                "SELECT sha256, version, indexed_at, chunks, spring_doc_ids, errors " +
                "FROM doc_registry WHERE user_id = ?",
                (rs, n) -> new DocRegistryEntry(
                        rs.getString("sha256"), rs.getString("version"),
                        rs.getString("indexed_at"), rs.getInt("chunks"),
                        fromJsonList(rs.getString("spring_doc_ids")),
                        fromJsonList(rs.getString("errors"))),
                userId);
    }

    public Set<Map.Entry<String, DocRegistryEntry>> entries(String userId) {
        Map<String, DocRegistryEntry> map = new LinkedHashMap<>();
        jdbc.query(
                "SELECT doc_id, sha256, version, indexed_at, chunks, spring_doc_ids, errors " +
                "FROM doc_registry WHERE user_id = ? ORDER BY indexed_at DESC",
                rs -> {
                    map.put(rs.getString("doc_id"), new DocRegistryEntry(
                            rs.getString("sha256"), rs.getString("version"),
                            rs.getString("indexed_at"), rs.getInt("chunks"),
                            fromJsonList(rs.getString("spring_doc_ids")),
                            fromJsonList(rs.getString("errors"))));
                },
                userId);
        return map.entrySet();
    }

    // ── Query helpers ──────────────────────────────────────────────────────

    public boolean existsBySha256AndVersion(String sha256, String version, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM doc_registry WHERE sha256 = ? AND version = ? AND user_id = ?",
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

    // ── Registry entry ─────────────────────────────────────────────────────

    public record DocRegistryEntry(
            String sha256,
            String version,
            String indexedAt,
            int chunks,
            List<String> springDocIds,
            List<String> errors
    ) {}
}
