package com.example.ragagent.ingestion;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.function.BiConsumer;

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

    /**
     * Same as {@link #add(String, String, List)} but reports incremental progress via
     * {@code onProgress.accept(done, total)} as sub-batches of the embedding call complete.
     * Default has no sub-batch visibility and reports a single 0→total jump; override where
     * real incremental progress is available (see {@link SqliteVecVectorStoreProvider}).
     */
    default void add(String userId, String version, List<Document> docs,
                      BiConsumer<Integer, Integer> onProgress) {
        int total = docs == null ? 0 : docs.size();
        onProgress.accept(0, total);
        add(userId, version, docs);
        onProgress.accept(total, total);
    }

    void deleteByDocIds(String userId, String version, List<String> springDocIds);
}
