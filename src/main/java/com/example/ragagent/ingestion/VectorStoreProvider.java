package com.example.ragagent.ingestion;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Backend-agnostic vector-store operations. One implementation per backend
 * ({@link ChromaVectorStoreProvider}, {@link SqliteVecVectorStoreProvider}).
 * Selected at runtime via {@code app.vectorstore.type}.
 *
 * <p>Contract: {@code version} strings are assumed already validated/normalised
 * by {@link VectorStoreFacade#safe(String)} — implementations must not re-validate.
 */
public interface VectorStoreProvider {

    /** Single-query ANN search. */
    List<Document> search(String userId, String query, String version, int topK);

    /**
     * Batched multi-query search. Returns one ranked {@code List<Document>} per
     * input query (input order preserved), ready to feed RRF fusion.
     */
    List<List<Document>> searchBatch(String userId, List<String> queries, String version, int topK);

    void add(String userId, String version, List<Document> docs);

    void deleteByDocIds(String userId, String version, List<String> springDocIds);
}
