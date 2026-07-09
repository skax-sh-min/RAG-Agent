package com.example.ragagent.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Backend-agnostic façade over {@link VectorStoreProvider}.
 *
 * <p>Owns the cross-cutting {@code SAFE_VERSION} validation and delegates all
 * vector-store I/O to the active provider (Chroma or sqlite-vec, selected via
 * {@code app.vectorstore.type}). Call sites ({@code RagService}, {@code DocumentIndexer})
 * inject this façade — the backend swap is invisible to them.
 */
@Component
public class VectorStoreFacade {

    private static final Pattern SAFE_VERSION = Pattern.compile("^[a-zA-Z0-9._\\-]{1,50}$");

    private final VectorStoreProvider provider;

    public VectorStoreFacade(VectorStoreProvider provider) {
        this.provider = provider;
    }

    public List<Document> search(String userId, String query, String version, int topK) {
        return provider.search(userId, query, safe(version), topK);
    }

    public List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK) {
        return provider.searchBatch(userId, queries, safe(version), topK);
    }

    public void add(String userId, String version, List<Document> docs) {
        provider.add(userId, safe(version), docs);
    }

    public void add(String userId, String version, List<Document> docs,
                     BiConsumer<Integer, Integer> onProgress) {
        provider.add(userId, safe(version), docs, onProgress);
    }

    public void deleteByDocIds(String userId, String version, List<String> springDocIds) {
        provider.deleteByDocIds(userId, safe(version), springDocIds);
    }

    public void updateTags(String userId, String version, List<String> springDocIds, String tagsCsv) {
        provider.updateTags(userId, safe(version), springDocIds, tagsCsv);
    }

    static String safe(String version) {
        return (version != null && SAFE_VERSION.matcher(version).matches()) ? version : "latest";
    }
}
