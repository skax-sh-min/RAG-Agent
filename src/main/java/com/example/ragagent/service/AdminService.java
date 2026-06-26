package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
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
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Admin-level access to ChromaDB: list collections, browse/delete/update chunks.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final String TENANT   = ChromaApiConstants.DEFAULT_TENANT_NAME;
    private static final String DATABASE = ChromaApiConstants.DEFAULT_DATABASE_NAME;

    /** Nullable — sqlite-vec 백엔드에서는 ChromaApi 빈이 없으므로 Optional로 주입된다. */
    private final ChromaApi chromaApi;

    public AdminService(Optional<ChromaApi> chromaApi) {
        this.chromaApi = chromaApi.orElse(null);
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

    // ── Public API ────────────────────────────────────────────────────────────

    public CollectionsResult listCollections() {
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
        if (chromaApi == null) { log.warn("deleteChunk ignored — no ChromaApi (sqlite-vec backend)"); return; }
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
        if (chromaApi == null) { log.warn("updateChunk ignored — no ChromaApi (sqlite-vec backend)"); return; }
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
}
