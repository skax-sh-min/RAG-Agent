package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.VectorStoreRegistry;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Thin façade over VectorStoreRegistry.
 * Centralises SAFE_VERSION validation and all vector-store I/O.
 */
@Component
public class VectorStoreFacade {

    private static final Pattern SAFE_VERSION = Pattern.compile("^[a-zA-Z0-9._\\-]{1,50}$");

    private final VectorStoreRegistry registry;

    public VectorStoreFacade(VectorStoreRegistry registry) {
        this.registry = registry;
    }

    public List<Document> search(String query, String version, int topK) {
        String safeVersion = safe(version);
        VectorStore store = registry.getStore(safeVersion);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq(MetaKey.VERSION, safeVersion).build())
                .build();
        return store.similaritySearch(request);
    }

    public void add(String version, List<Document> docs) {
        registry.getStore(version).add(docs);
    }

    public void deleteByDocIds(String version, List<String> springDocIds) {
        if (springDocIds == null || springDocIds.isEmpty()) return;
        registry.getStore(version).delete(springDocIds);
    }

    private static String safe(String version) {
        return (version != null && SAFE_VERSION.matcher(version).matches()) ? version : "latest";
    }
}
