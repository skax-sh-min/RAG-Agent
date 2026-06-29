package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.VectorStoreAdminView;
import com.example.ragagent.model.VectorStoreAdminView.VersionCount;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaApi.AddEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.Collection;
import org.springframework.ai.chroma.vectorstore.ChromaApi.DeleteEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingResponse;
import org.springframework.ai.chroma.vectorstore.ChromaApi.GetEmbeddingsRequest;
import org.springframework.ai.chroma.vectorstore.ChromaApi.QueryRequest.Include;
import org.springframework.ai.chroma.vectorstore.common.ChromaApiConstants;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Admin-level access to the active vector store: ChromaDB collection/chunk browsing,
 * plus a backend-agnostic status view ({@link #vectorStoreView()}) covering both
 * chroma and sqlite-vec (Step 5.8).
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final String TENANT   = ChromaApiConstants.DEFAULT_TENANT_NAME;
    private static final String DATABASE = ChromaApiConstants.DEFAULT_DATABASE_NAME;

    /** Nullable — sqlite-vec 백엔드에서는 ChromaApi 빈이 없으므로 Optional로 주입된다. */
    private final ChromaApi chromaApi;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public AdminService(Optional<ChromaApi> chromaApi, JdbcTemplate jdbc, AppProperties props,
                        ObjectMapper objectMapper) {
        this.chromaApi = chromaApi.orElse(null);
        this.jdbc = jdbc;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Active backend is sqlite-vec? Null-safe so unit tests with mocked props don't NPE. */
    private boolean isSqliteVec() {
        return props != null && props.vectorStoreSafe() != null
                && "sqlite-vec".equals(props.vectorStoreSafe().type());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CollectionSummary(String id, String name, String version, long chunkCount) {}

    public record ChunkRow(String id, String textPreview, String fullText,
                           Map<String, String> metadata) {
        public String docId()     { return metadata.getOrDefault(MetaKey.DOC_ID, ""); }
        public String filename()  { return metadata.getOrDefault(MetaKey.FILENAME, ""); }
        public String pageSlide() { return metadata.getOrDefault(MetaKey.PAGE_OR_SLIDE, ""); }
        public String keywords()  { return metadata.getOrDefault("excerpt_keywords", ""); }
    }

    // ── DTOs (public) ─────────────────────────────────────────────────────────

    /** Wraps listCollections result with a ChromaDB availability flag. */
    public record CollectionsResult(List<CollectionSummary> items, boolean available) {}

    // ── Vector store status (backend-agnostic, Step 5.8) ───────────────────────

    /** Active-backend status for the {@code /admin} "Vector Store 상태" card. */
    public VectorStoreAdminView vectorStoreView() {
        return "sqlite-vec".equals(props.vectorStoreSafe().type()) ? sqliteVecView() : chromaView();
    }

    private VectorStoreAdminView chromaView() {
        CollectionsResult r = listCollections();
        long totalChunks = r.items().stream().mapToLong(CollectionSummary::chunkCount).sum();
        List<VersionCount> perVersion = r.items().stream()
                .map(c -> new VersionCount(c.version(), c.chunkCount()))
                .toList();
        // Chroma collections don't track distinct document counts → unknown (-1).
        return new VectorStoreAdminView("chroma", r.available(), -1, totalChunks,
                perVersion, r.items().size(), null, null);
    }

    private VectorStoreAdminView sqliteVecView() {
        String vecVersion = null;
        boolean healthy = false;
        try {
            vecVersion = jdbc.queryForObject("SELECT vec_version()", String.class);
            healthy = vecVersion != null;
        } catch (Exception e) {
            log.warn("vec_version() 조회 실패 (sqlite-vec 확장 미로드?): {}", e.getMessage());
        }
        long totalChunks = safeCount("SELECT COUNT(*) FROM vec_document_chunks");
        long totalDocs   = safeCount("SELECT COUNT(DISTINCT doc_id) FROM vec_document_chunks");
        List<VersionCount> perVersion;
        try {
            perVersion = jdbc.query(
                    "SELECT version, COUNT(*) AS c FROM vec_document_chunks GROUP BY version ORDER BY version",
                    (rs, n) -> new VersionCount(rs.getString("version"), rs.getLong("c")));
        } catch (Exception e) {
            log.warn("버전별 청크 집계 실패: {}", e.getMessage());
            perVersion = List.of();
        }
        Integer dim = props.embeddingSafe().dimensions();
        return new VectorStoreAdminView("sqlite-vec", healthy, totalDocs, totalChunks,
                perVersion, null, vecVersion, dim);
    }

    private long safeCount(String sql) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class);
            return c != null ? c : 0L;
        } catch (Exception e) {
            log.warn("count 쿼리 실패 [{}]: {}", sql, e.getMessage());
            return 0L;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public CollectionsResult listCollections() {
        if (isSqliteVec()) return sqliteVecCollections();
        if (chromaApi == null) return new CollectionsResult(List.of(), false);
        try {
            List<Collection> cols = chromaApi.listCollections(TENANT, DATABASE);
            if (cols == null) return new CollectionsResult(List.of(), true);
            List<CollectionSummary> items = cols.stream().map(col -> {
                long count = 0;
                try { Long c = chromaApi.countEmbeddings(TENANT, DATABASE, col.id()); count = c != null ? c : 0; }
                catch (Exception e) { log.warn("Count failed for {}: {}", col.name(), e.getMessage()); }
                String version = col.name().startsWith("manual_")
                        ? col.name().substring("manual_".length()) : col.name();
                return new CollectionSummary(col.id(), col.name(), version, count);
            }).toList();
            return new CollectionsResult(items, true);
        } catch (Exception e) {
            log.error("listCollections failed: {}", e.getMessage());
            return new CollectionsResult(List.of(), false);
        }
    }

    public List<ChunkRow> getChunks(String collectionName, String docId,
                                    int offset, int limit) {
        if (isSqliteVec()) return sqliteVecChunks(collectionName, docId, offset, limit);
        if (chromaApi == null) return List.of();
        Map<String, Object> where = null;
        if (docId != null && !docId.isBlank()) {
            where = Map.of(MetaKey.DOC_ID, Map.of("$eq", docId));
        }
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                null, where, limit, offset,
                List.of(Include.DOCUMENTS, Include.METADATAS));
        try {
            GetEmbeddingResponse resp = chromaApi.getEmbeddings(TENANT, DATABASE, resolveId(collectionName), req);
            if (resp == null || resp.ids() == null) return List.of();

            List<String> ids  = resp.ids();
            List<String> docs = resp.documents();
            List<Map<String, String>> metas = resp.metadata();
            List<ChunkRow> rows = new ArrayList<>(ids.size());

            for (int i = 0; i < ids.size(); i++) {
                String text    = (docs  != null && i < docs.size())  ? docs.get(i)  : "";
                Map<String, String> meta = (metas != null && i < metas.size()) ? metas.get(i) : Map.of();
                String preview = text != null && text.length() > 250
                        ? text.substring(0, 250) + "…" : Objects.requireNonNullElse(text, "");
                rows.add(new ChunkRow(ids.get(i), preview, text, meta));
            }
            return rows;
        } catch (Exception e) {
            log.error("getChunks failed collection={} docId={}: {}", collectionName, docId, e.getMessage());
            return List.of();
        }
    }

    public long countChunks(String collectionName, String docId) {
        if (isSqliteVec()) return sqliteVecCount(collectionName, docId);
        if (chromaApi == null) return 0;
        if (docId == null || docId.isBlank()) {
            try { Long c = chromaApi.countEmbeddings(TENANT, DATABASE, resolveId(collectionName)); return c != null ? c : 0; }
            catch (Exception e) { return 0; }
        }
        // Approximate: fetch limit=0 with where filter → real count not possible via get, return chunk list size
        List<ChunkRow> all = getChunks(collectionName, docId, 0, 10_000);
        return all.size();
    }

    public ChunkRow getChunk(String collectionName, String chunkId) {
        if (isSqliteVec()) return sqliteVecChunk(chunkId);
        if (chromaApi == null) return null;
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                List.of(chunkId), null, 1, 0,
                List.of(Include.DOCUMENTS, Include.METADATAS));
        try {
            GetEmbeddingResponse resp = chromaApi.getEmbeddings(TENANT, DATABASE, resolveId(collectionName), req);
            if (resp == null || resp.ids() == null || resp.ids().isEmpty()) return null;
            String text = resp.documents() != null && !resp.documents().isEmpty()
                    ? resp.documents().get(0) : "";
            Map<String, String> meta = resp.metadata() != null && !resp.metadata().isEmpty()
                    ? resp.metadata().get(0) : Map.of();
            return new ChunkRow(resp.ids().get(0), text, text, meta);
        } catch (Exception e) {
            log.error("getChunk failed id={}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    public void deleteChunk(String collectionName, String chunkId) {
        if (isSqliteVec()) {
            // Two-table consistency: drop the chunk from both the metadata table and the vec0 store.
            jdbc.update("DELETE FROM vec_document_chunks WHERE spring_doc_id = ?", chunkId);
            jdbc.update("DELETE FROM vec_embeddings WHERE spring_doc_id = ?", chunkId);
            return;
        }
        if (chromaApi == null) { log.warn("deleteChunk ignored — no ChromaApi"); return; }
        chromaApi.deleteEmbeddings(TENANT, DATABASE, collectionName,
                new DeleteEmbeddingsRequest(List.of(chunkId)));
    }

    private String resolveId(String collectionName) {
        try {
            Collection col = chromaApi.getCollection(TENANT, DATABASE, collectionName);
            return col != null ? col.id() : collectionName;
        } catch (Exception e) {
            return collectionName;
        }
    }

    /**
     * Metadata-only update: fetches the existing embedding and re-upserts
     * with new text/metadata while preserving the original vector.
     */
    public void updateChunk(String collectionName, String chunkId,
                            String newText, Map<String, String> newMeta) {
        if (isSqliteVec()) {
            // Metadata/text only — the stored vector (vec_embeddings) is intentionally preserved
            // (same caveat as the Chroma path: editing text does not re-embed).
            if (newText != null) {
                jdbc.update("UPDATE vec_document_chunks SET content = ? WHERE spring_doc_id = ?",
                        newText, chunkId);
            }
            if (newMeta != null) {
                jdbc.update("UPDATE vec_document_chunks SET metadata = ? WHERE spring_doc_id = ?",
                        toJson(newMeta), chunkId);
            }
            return;
        }
        if (chromaApi == null) { log.warn("updateChunk ignored — no ChromaApi"); return; }
        // Fetch existing embedding to avoid re-embedding
        GetEmbeddingsRequest req = new GetEmbeddingsRequest(
                List.of(chunkId), null, 1, 0,
                List.of(Include.EMBEDDINGS, Include.DOCUMENTS));
        GetEmbeddingResponse existing;
        String collectionId = resolveId(collectionName);
        try {
            existing = chromaApi.getEmbeddings(TENANT, DATABASE, collectionId, req);
        } catch (Exception e) {
            log.error("updateChunk fetch failed id={}: {}", chunkId, e.getMessage());
            return;
        }

        float[] embedding = (existing != null && existing.embeddings() != null
                && !existing.embeddings().isEmpty())
                ? existing.embeddings().get(0) : new float[0];

        String text = newText != null ? newText
                : (existing != null && existing.documents() != null
                        && !existing.documents().isEmpty()
                        ? existing.documents().get(0) : "");

        Map<String, Object> metaObj = newMeta == null ? Map.of() : new HashMap<>(newMeta);

        try {
            chromaApi.upsertEmbeddings(TENANT, DATABASE, collectionName,
                    new AddEmbeddingsRequest(chunkId, embedding, metaObj, text));
        } catch (Exception e) {
            log.error("updateChunk upsert failed id={}: {}", chunkId, e.getMessage());
        }
    }

    // ── sqlite-vec chunk browsing (Step 5.8 parity) ────────────────────────────
    // For sqlite-vec the "collection" identifier passed from the UI is the version
    // string (vec0 partition key). Chunks live in vec_document_chunks(content/metadata).

    private CollectionsResult sqliteVecCollections() {
        try {
            List<CollectionSummary> items = jdbc.query(
                    "SELECT version, COUNT(*) AS c FROM vec_document_chunks GROUP BY version ORDER BY version",
                    (rs, n) -> {
                        String v = rs.getString("version");
                        return new CollectionSummary(v, v, v, rs.getLong("c"));
                    });
            return new CollectionsResult(items, true);
        } catch (Exception e) {
            log.error("sqlite-vec listCollections failed: {}", e.getMessage());
            return new CollectionsResult(List.of(), false);
        }
    }

    private List<ChunkRow> sqliteVecChunks(String version, String docId, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT spring_doc_id, content, metadata FROM vec_document_chunks WHERE version = ?");
        List<Object> args = new ArrayList<>();
        args.add(version);
        if (docId != null && !docId.isBlank()) { sql.append(" AND doc_id = ?"); args.add(docId); }
        sql.append(" ORDER BY created_at, spring_doc_id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        try {
            return jdbc.query(sql.toString(), (rs, n) -> {
                String text = rs.getString("content");
                String preview = text != null && text.length() > 250
                        ? text.substring(0, 250) + "…" : Objects.requireNonNullElse(text, "");
                return new ChunkRow(rs.getString("spring_doc_id"), preview, text,
                        parseMeta(rs.getString("metadata")));
            }, args.toArray());
        } catch (Exception e) {
            log.error("sqlite-vec getChunks failed version={} docId={}: {}", version, docId, e.getMessage());
            return List.of();
        }
    }

    private long sqliteVecCount(String version, String docId) {
        String sql = "SELECT COUNT(*) FROM vec_document_chunks WHERE version = ?";
        Object[] args = (docId != null && !docId.isBlank())
                ? new Object[]{version, docId} : new Object[]{version};
        if (docId != null && !docId.isBlank()) sql += " AND doc_id = ?";
        try { Long c = jdbc.queryForObject(sql, Long.class, args); return c != null ? c : 0; }
        catch (Exception e) { return 0; }
    }

    private ChunkRow sqliteVecChunk(String chunkId) {
        try {
            return jdbc.query(
                    "SELECT spring_doc_id, content, metadata FROM vec_document_chunks WHERE spring_doc_id = ?",
                    (rs, n) -> {
                        String text = rs.getString("content");
                        return new ChunkRow(rs.getString("spring_doc_id"), text, text,
                                parseMeta(rs.getString("metadata")));
                    }, chunkId).stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.error("sqlite-vec getChunk failed id={}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseMeta(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.warn("metadata JSON 파싱 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Map<String, String> meta) {
        try { return objectMapper.writeValueAsString(meta); }
        catch (Exception e) { log.warn("metadata 직렬화 실패: {}", e.getMessage()); return "{}"; }
    }
}
