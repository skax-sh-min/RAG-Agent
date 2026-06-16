package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.VectorStoreRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaFilterExpressionConverter;
import org.springframework.ai.chroma.vectorstore.common.ChromaApiConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Thin façade over VectorStoreRegistry.
 * Centralises SAFE_VERSION validation and all vector-store I/O.
 * Each (userId, version) pair maps to its own Chroma collection.
 */
@Component
public class VectorStoreFacade {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreFacade.class);

    private static final Pattern SAFE_VERSION = Pattern.compile("^[a-zA-Z0-9._\\-]{1,50}$");
    private static final String TENANT = ChromaApiConstants.DEFAULT_TENANT_NAME;
    private static final String DATABASE = ChromaApiConstants.DEFAULT_DATABASE_NAME;

    private final VectorStoreRegistry registry;
    private final ChromaApi chromaApi;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final double similarityThreshold;

    private final FilterExpressionConverter filterConverter = new ChromaFilterExpressionConverter();
    private final ConcurrentHashMap<String, String> collectionIdCache = new ConcurrentHashMap<>();

    public VectorStoreFacade(VectorStoreRegistry registry, ChromaApi chromaApi,
                             EmbeddingModel embeddingModel, ObjectMapper objectMapper, AppProperties props) {
        this.registry = registry;
        this.chromaApi = chromaApi;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.similarityThreshold = props.searchSimilarityThresholdSafe();
    }

    public List<Document> search(String userId, String query, String version, int topK) {
        String safeVersion = safe(version);
        VectorStore store = registry.getStore(userId, safeVersion);
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(b.eq(MetaKey.VERSION, safeVersion).build());
        // R-1: only set when configured (>0) so 0.0 keeps Spring AI accept-all behavior.
        if (similarityThreshold > 0.0) {
            request.similarityThreshold(similarityThreshold);
        }
        return store.similaritySearch(request.build());
    }

    /**
     * S-3: Batched multi-query search. Embeds all query variants in a single
     * {@link EmbeddingModel#embed(List)} call and issues one Chroma {@code queryCollection}
     * with all embeddings, instead of N separate embed+query round-trips.
     * Returns one ranked {@code List<Document>} per input query (order preserved),
     * ready to feed RRF fusion.
     *
     * <p>Mirrors {@code ChromaVectorStore.doSimilaritySearch} semantics:
     * {@code similarity = 1 - distance}, R-1 threshold applied, same filter converter.
     */
    public List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK) {
        if (queries == null || queries.isEmpty()) return List.of();
        String safeVersion = safe(version);
        String collectionId = resolveCollectionId(userId, safeVersion);
        if (collectionId == null) {
            return queries.stream().map(q -> List.<Document>of()).toList();
        }
        List<float[]> embeddings = embeddingModel.embed(queries);          // single batched HTTP call
        Map<String, Object> where = whereForVersion(safeVersion);
        var request = new ChromaApi.QueryRequest(embeddings, topK, where, ChromaApi.QueryRequest.Include.all);
        var response = chromaApi.queryCollection(TENANT, DATABASE, collectionId, request);
        return mapPerQuery(response);
    }

    public void add(String userId, String version, List<Document> docs) {
        registry.getStore(userId, version).add(docs);
    }

    public void deleteByDocIds(String userId, String version, List<String> springDocIds) {
        if (springDocIds == null || springDocIds.isEmpty()) return;
        registry.getStore(userId, version).delete(springDocIds);
    }

    // ── S-3 helpers ──────────────────────────────────────────────────────────

    /** Resolves (and caches) the Chroma collection id. null when the collection does not exist yet. */
    private String resolveCollectionId(String userId, String version) {
        String name = registry.collectionName(userId, version);
        // computeIfAbsent does not store null → unresolved names are retried on the next call.
        return collectionIdCache.computeIfAbsent(name, n -> {
            registry.getStore(userId, version); // ensure the collection is created (idempotent)
            ChromaApi.Collection c = chromaApi.getCollection(TENANT, DATABASE, n);
            return c == null ? null : c.id();
        });
    }

    /** Builds the Chroma {@code where} map from a version-eq filter, reusing the library converter. */
    private Map<String, Object> whereForVersion(String version) {
        Filter.Expression expr = new FilterExpressionBuilder().eq(MetaKey.VERSION, version).build();
        try {
            String json = filterConverter.convertExpression(expr);
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[searchBatch] filter conversion failed, querying without filter: {}", e.getMessage());
            return null;
        }
    }

    /** Maps a batched QueryResponse into one Document list per query (outer index = query). */
    private List<List<Document>> mapPerQuery(ChromaApi.QueryResponse resp) {
        if (resp == null || resp.ids() == null) return List.of();
        List<List<Document>> out = new ArrayList<>(resp.ids().size());
        for (int i = 0; i < resp.ids().size(); i++) {
            List<String> ids = resp.ids().get(i);
            List<String> docs = nth(resp.documents(), i);
            List<Map<String, Object>> metas = nth(resp.metadata(), i);
            List<Double> dists = nth(resp.distances(), i);

            List<Document> perQuery = new ArrayList<>();
            for (int j = 0; ids != null && j < ids.size(); j++) {
                double distance = (dists != null && j < dists.size() && dists.get(j) != null) ? dists.get(j) : 0.0;
                double similarity = 1.0 - distance;
                if (similarity < similarityThreshold) continue;   // R-1 (0.0 = accept all)
                String text = (docs != null && j < docs.size()) ? docs.get(j) : "";
                Map<String, Object> meta = (metas != null && j < metas.size() && metas.get(j) != null)
                        ? new HashMap<>(metas.get(j)) : new HashMap<>();
                perQuery.add(Document.builder()
                        .id(ids.get(j))
                        .text(text)
                        .metadata(meta)
                        .score(similarity)
                        .build());
            }
            out.add(perQuery);
        }
        return out;
    }

    private static <T> List<T> nth(List<List<T>> outer, int i) {
        return (outer != null && i < outer.size()) ? outer.get(i) : null;
    }

    private static String safe(String version) {
        return (version != null && SAFE_VERSION.matcher(version).matches()) ? version : "latest";
    }
}
