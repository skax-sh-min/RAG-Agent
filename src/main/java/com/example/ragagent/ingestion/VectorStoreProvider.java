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

    /**
     * Overwrites the {@code tags} metadata field on the given chunks in place — the stored
     * embedding is untouched (tags never influence the vector, so no re-embedding is needed).
     * {@code tagsCsv} is the comma-joined, already-normalized form ({@link
     * com.example.ragagent.model.TagUtils#toMetaValue}); an empty string clears tags.
     */
    void updateTags(String userId, String version, List<String> springDocIds, String tagsCsv);

    /**
     * 청크 id 로 <b>저장된 원문과 메타데이터</b>를 읽는다(임베딩은 읽지 않는다). 없는 id 는 결과에서 빠지고, 순서는
     * 보장하지 않는다. 키워드(BM25) 축으로만 찾힌 청크를 원문으로 되돌리는 데 쓴다 — {@code chunk_fts} 에는 원문이
     * 아니라 검색용 파생 텍스트(맥락 + 장식을 걷은 본문)가 있다({@code RetrievalService#withStoredText}).
     *
     * <p>기본 구현은 빈 목록이다 — 호출부는 그때 받은 문서를 그대로 쓴다(되돌리지 못할 뿐 틀리지는 않는다).
     */
    default List<Document> findByIds(String userId, String version, List<String> springDocIds) {
        return List.of();
    }
}
