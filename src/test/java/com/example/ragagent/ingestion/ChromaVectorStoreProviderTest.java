package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.VectorStoreRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 유사도 임계값 + 배치 멀티쿼리 - 단위 테스트.
 *
 * <p>{@code VectorStoreFacade}의 Chroma I/O 로직이 {@link ChromaVectorStoreProvider}로 분리된 후
 * 해당 동작을 검증한다.
 */
class ChromaVectorStoreProviderTest {

    private ChromaVectorStoreProvider provider(VectorStoreRegistry registry, ChromaApi chromaApi,
                                               EmbeddingModel embeddingModel, double threshold) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchSimilarityThresholdSafe()).thenReturn(threshold);
        return new ChromaVectorStoreProvider(registry, chromaApi, embeddingModel, new ObjectMapper(), props);
    }

    // ── single search threshold ──────────────────────────────────────────

    private SearchRequest capturedSearch(double threshold) {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        VectorStore store = mock(VectorStore.class);
        when(registry.getStore(any(), any())).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        provider(registry, mock(ChromaApi.class), mock(EmbeddingModel.class), threshold)
                .search("owner", "질문", "latest", 7);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("threshold>0 → SearchRequest.similarityThreshold 반영")
    void threshold_applied_whenConfigured() {
        SearchRequest req = capturedSearch(0.6);
        assertThat(req.getSimilarityThreshold()).isEqualTo(0.6);
        assertThat(req.getTopK()).isEqualTo(7);
    }

    @Test
    @DisplayName("threshold=0 → accept-all(0.0) 유지")
    void threshold_acceptAll_whenZero() {
        SearchRequest req = capturedSearch(0.0);
        assertThat(req.getSimilarityThreshold()).isEqualTo(0.0);
    }

    // ── batched multi-query search ───────────────────────────────────────

    private ChromaVectorStoreProvider batchProvider(EmbeddingModel embeddingModel, ChromaApi chromaApi, double threshold) {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        when(registry.collectionName(any(), any())).thenReturn("u_shared_latest");
        when(registry.getStore(any(), any())).thenReturn(mock(VectorStore.class));
        when(chromaApi.getCollection(anyString(), anyString(), anyString()))
                .thenReturn(new ChromaApi.Collection("cid", "u_shared_latest", Map.of()));
        return provider(registry, chromaApi, embeddingModel, threshold);
    }

    private ChromaApi.QueryResponse twoQueryResponse() {
        return new ChromaApi.QueryResponse(
                List.of(List.of("a1", "a2"), List.of("b1")),                       // ids
                null,                                                              // embeddings
                List.of(List.of("textA1", "textA2"), List.of("textB1")),          // documents
                List.of(List.of(Map.of("filename", "f.pdf"), Map.of("filename", "g.pdf")),
                        List.of(Map.of("filename", "h.pdf"))),                     // metadata
                List.of(List.of(0.1, 0.9), List.of(0.2)));                         // distances
    }

    @Test
    @DisplayName("배치 임베딩 1회 + 쿼리별 결과 그룹 보존, score=1-distance")
    void searchBatch_groupsPerQuery_andScores() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class)))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any()))
                .thenReturn(twoQueryResponse());

        List<List<Document>> result = batchProvider(embeddingModel, chromaApi, 0.0)
                .searchBatch("owner", List.of("q1", "q2"), "latest", 7);

        // 임베딩은 단일 배치 호출
        verify(embeddingModel).embed(any(List.class));
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).hasSize(2);
        assertThat(result.get(1)).hasSize(1);
        assertThat(result.get(0).get(0).getScore()).isEqualTo(0.9);   // 1 - 0.1
        assertThat(result.get(0).get(1).getScore()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(result.get(0).get(0).getText()).isEqualTo("textA1");
    }

    @Test
    @DisplayName("threshold 적용 — similarity<threshold 결과 제외")
    void searchBatch_appliesThreshold() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class)))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any()))
                .thenReturn(twoQueryResponse());

        // threshold 0.5 → distance 0.9(sim 0.1) 제외, distance 0.1(sim 0.9) 유지
        List<List<Document>> result = batchProvider(embeddingModel, chromaApi, 0.5)
                .searchBatch("owner", List.of("q1", "q2"), "latest", 7);

        assertThat(result.get(0)).hasSize(1);
        assertThat(result.get(0).get(0).getScore()).isEqualTo(0.9);
        assertThat(result.get(1)).hasSize(1);  // distance 0.2 → sim 0.8 유지
    }

    @Test
    @DisplayName("threshold>0 → n_results를 topK의 2배로 과조회한다 (§10.7.4)")
    void searchBatch_overfetchesWhenThresholdActive() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class)))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any()))
                .thenReturn(twoQueryResponse());

        batchProvider(embeddingModel, chromaApi, 0.5)
                .searchBatch("owner", List.of("q1", "q2"), "latest", 7);

        ArgumentCaptor<ChromaApi.QueryRequest> captor = ArgumentCaptor.forClass(ChromaApi.QueryRequest.class);
        verify(chromaApi).queryCollection(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().nResults()).isEqualTo(14); // ceil(7 * 2.0)
    }

    @Test
    @DisplayName("threshold=0.0(기본) → n_results는 topK 그대로, 과조회 없음 (무해)")
    void searchBatch_noOverfetchWhenThresholdZero() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class)))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any()))
                .thenReturn(twoQueryResponse());

        batchProvider(embeddingModel, chromaApi, 0.0)
                .searchBatch("owner", List.of("q1", "q2"), "latest", 7);

        ArgumentCaptor<ChromaApi.QueryRequest> captor = ArgumentCaptor.forClass(ChromaApi.QueryRequest.class);
        verify(chromaApi).queryCollection(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().nResults()).isEqualTo(7);
    }

    @Test
    @DisplayName("필터 통과 결과가 topK보다 많아도 topK로 잘라낸다 (§10.7.4)")
    void searchBatch_capsPerQueryResultsAtTopK() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(new float[]{0.1f}));
        ChromaApi chromaApi = mock(ChromaApi.class);
        ChromaApi.QueryResponse threeHits = new ChromaApi.QueryResponse(
                List.of(List.of("a1", "a2", "a3")),
                null,
                List.of(List.of("t1", "t2", "t3")),
                List.of(List.of(Map.of("filename", "a.pdf"), Map.of("filename", "b.pdf"), Map.of("filename", "c.pdf"))),
                List.of(List.of(0.1, 0.2, 0.3)));
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any())).thenReturn(threeHits);

        List<List<Document>> result = batchProvider(embeddingModel, chromaApi, 0.0)
                .searchBatch("owner", List.of("q1"), "latest", 2);

        assertThat(result.get(0)).hasSize(2);
    }

    @Test
    @DisplayName("컬렉션 없음 → 쿼리당 빈 리스트, Chroma 쿼리 미호출")
    void searchBatch_noCollection_returnsEmpty() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        when(registry.collectionName(any(), any())).thenReturn("u_shared_latest");
        when(registry.getStore(any(), any())).thenReturn(mock(VectorStore.class));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.getCollection(anyString(), anyString(), anyString())).thenReturn(null);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        List<List<Document>> result = provider(registry, chromaApi, embeddingModel, 0.0)
                .searchBatch("owner", List.of("q1", "q2"), "latest", 7);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEmpty();
        assertThat(result.get(1)).isEmpty();
    }

    // ── add() — manual embed + upsert (§10.1) ────────────────────────────

    private ChromaVectorStoreProvider addProvider(VectorStoreRegistry registry, ChromaApi chromaApi,
                                                   EmbeddingModel embeddingModel) {
        when(registry.getStore(any(), any())).thenReturn(mock(VectorStore.class));
        when(registry.collectionName(any(), any())).thenReturn("u_shared_latest");
        return provider(registry, chromaApi, embeddingModel, 0.0);
    }

    @Test
    @DisplayName("add(onProgress): 서브배치별 실제 진행률을 보고하고 chromaApi.upsertEmbeddings()로 저장한다")
    void add_reportsSubBatchProgressAndUpserts() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(new float[]{0.1f}));
        ChromaApi chromaApi = mock(ChromaApi.class);

        Document doc = Document.builder().id("d1").text("hello")
                .metadata(Map.of("filename", "f.pdf")).build();
        List<int[]> calls = new java.util.ArrayList<>();

        addProvider(registry, chromaApi, embeddingModel)
                .add("owner", "latest", List.of(doc), (done, total) -> calls.add(new int[]{done, total}));

        assertThat(calls.get(0)).containsExactly(0, 1);
        assertThat(calls.get(calls.size() - 1)).containsExactly(1, 1);

        ArgumentCaptor<ChromaApi.AddEmbeddingsRequest> captor = ArgumentCaptor.forClass(ChromaApi.AddEmbeddingsRequest.class);
        verify(chromaApi).upsertEmbeddings(anyString(), anyString(), eq("u_shared_latest"), captor.capture());
        ChromaApi.AddEmbeddingsRequest req = captor.getValue();
        assertThat(req.ids()).containsExactly("d1");
        assertThat(req.documents()).containsExactly("hello");
        assertThat(req.metadata().get(0)).containsEntry("filename", "f.pdf");
    }

    @Test
    @DisplayName("add: 저장 content/metadata는 원문 그대로이고, 임베딩 입력은 맥락+정규화본이며 CHUNK_CONTEXT는 영속에서 제외된다")
    void add_embedsDerivedTextButPersistsOriginal() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(List.class))).thenReturn(List.of(new float[]{0.1f}));
        ChromaApi chromaApi = mock(ChromaApi.class);

        String original = "**중요**한 내용\n------";
        Document doc = Document.builder().id("d1").text(original)
                .metadata(Map.of(MetaKey.CHUNK_CONTEXT, "문서.pdf > 설정"))
                .build();

        addProvider(registry, chromaApi, embeddingModel).add("owner", "latest", List.of(doc));

        ArgumentCaptor<List> embedCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(embedCaptor.capture());
        assertThat(embedCaptor.getValue()).containsExactly("문서.pdf > 설정\n\n중요한 내용");

        ArgumentCaptor<ChromaApi.AddEmbeddingsRequest> captor = ArgumentCaptor.forClass(ChromaApi.AddEmbeddingsRequest.class);
        verify(chromaApi).upsertEmbeddings(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().documents()).containsExactly(original);
        assertThat(captor.getValue().metadata().get(0)).doesNotContainKey(MetaKey.CHUNK_CONTEXT);
    }

    // ── §10.9.4 — indexing bypasses the query-embedding cache ────────────

    @Test
    @DisplayName("add: CachingEmbeddingModel이 주입되면 캐시를 우회해 delegate로 직접 임베딩한다")
    void add_bypassesCachingEmbeddingModelDelegate() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        EmbeddingModel delegate = mock(EmbeddingModel.class);
        when(delegate.embed(any(List.class))).thenReturn(List.of(new float[]{0.5f}));
        var cachingModel = new com.example.ragagent.llm.CachingEmbeddingModel(delegate, "test", 500, 600);
        ChromaApi chromaApi = mock(ChromaApi.class);

        Document doc = Document.builder().id("d1").text("인덱싱되는 청크 본문").metadata(Map.of()).build();
        addProvider(registry, chromaApi, cachingModel).add("owner", "latest", List.of(doc));

        verify(delegate).embed(any(List.class));                                  // reached the raw delegate directly
        verify(delegate, org.mockito.Mockito.never()).call(any());                // the cache's call()-based path never ran
    }

    @Test
    @DisplayName("searchBatch: CachingEmbeddingModel이 주입되면 쿼리 캐시가 그대로 적용된다(인덱싱과 무관)")
    void searchBatch_stillBenefitsFromCache() {
        EmbeddingModel delegate = mock(EmbeddingModel.class);
        when(delegate.call(any())).thenAnswer(inv -> {
            org.springframework.ai.embedding.EmbeddingRequest req = inv.getArgument(0);
            List<org.springframework.ai.embedding.Embedding> out = new java.util.ArrayList<>();
            for (int i = 0; i < req.getInstructions().size(); i++) {
                out.add(new org.springframework.ai.embedding.Embedding(new float[]{0.1f}, i));
            }
            return new org.springframework.ai.embedding.EmbeddingResponse(out);
        });
        var cachingModel = new com.example.ragagent.llm.CachingEmbeddingModel(delegate, "test", 500, 600);
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.queryCollection(anyString(), anyString(), anyString(), any()))
                .thenReturn(twoQueryResponse());

        ChromaVectorStoreProvider p = batchProvider(cachingModel, chromaApi, 0.0);
        p.searchBatch("owner", List.of("q1", "q2"), "latest", 7);
        p.searchBatch("owner", List.of("q1", "q2"), "latest", 7); // same queries — should be a cache hit

        verify(delegate, org.mockito.Mockito.times(1)).call(any()); // only the first call reached the delegate
    }

    // ── updateTags ──────────────────────────────────────────────────────

    @Test
    @DisplayName("updateTags: 기존 임베딩을 그대로 유지하며 tags 메타데이터만 갱신한다")
    void updateTags_mergesTagsAndPreservesEmbedding() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        when(registry.collectionName(any(), any())).thenReturn("u_shared_latest");
        when(registry.getStore(any(), any())).thenReturn(mock(VectorStore.class));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.getCollection(anyString(), anyString(), anyString()))
                .thenReturn(new ChromaApi.Collection("cid", "u_shared_latest", Map.of()));

        ChromaApi.GetEmbeddingResponse existing = new ChromaApi.GetEmbeddingResponse(
                List.of("d1", "d2"),
                List.of(new float[]{0.1f}, new float[]{0.2f}),
                List.of("text1", "text2"),
                List.of(Map.of("filename", "f.pdf"), Map.of("filename", "g.pdf", "tags", "old")));
        when(chromaApi.getEmbeddings(anyString(), anyString(), anyString(), any())).thenReturn(existing);

        provider(registry, chromaApi, mock(EmbeddingModel.class), 0.0)
                .updateTags("owner", "latest", List.of("d1", "d2"), "new,tags");

        ArgumentCaptor<ChromaApi.AddEmbeddingsRequest> captor = ArgumentCaptor.forClass(ChromaApi.AddEmbeddingsRequest.class);
        verify(chromaApi).upsertEmbeddings(anyString(), anyString(), eq("u_shared_latest"), captor.capture());
        ChromaApi.AddEmbeddingsRequest req = captor.getValue();
        assertThat(req.ids()).containsExactly("d1", "d2");
        assertThat(req.embeddings()).isSameAs(existing.embeddings());
        assertThat(req.metadata().get(0)).containsEntry("tags", "new,tags").containsEntry("filename", "f.pdf");
        assertThat(req.metadata().get(1)).containsEntry("tags", "new,tags").containsEntry("filename", "g.pdf");
    }

    @Test
    @DisplayName("updateTags: 빈 tagsCsv → tags 키 제거")
    void updateTags_removesKeyWhenBlank() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        when(registry.collectionName(any(), any())).thenReturn("u_shared_latest");
        when(registry.getStore(any(), any())).thenReturn(mock(VectorStore.class));
        ChromaApi chromaApi = mock(ChromaApi.class);
        when(chromaApi.getCollection(anyString(), anyString(), anyString()))
                .thenReturn(new ChromaApi.Collection("cid", "u_shared_latest", Map.of()));

        ChromaApi.GetEmbeddingResponse existing = new ChromaApi.GetEmbeddingResponse(
                List.of("d1"), List.of(new float[]{0.1f}), List.of("text1"),
                List.of(Map.of("filename", "f.pdf", "tags", "old")));
        when(chromaApi.getEmbeddings(anyString(), anyString(), anyString(), any())).thenReturn(existing);

        provider(registry, chromaApi, mock(EmbeddingModel.class), 0.0)
                .updateTags("owner", "latest", List.of("d1"), "");

        ArgumentCaptor<ChromaApi.AddEmbeddingsRequest> captor = ArgumentCaptor.forClass(ChromaApi.AddEmbeddingsRequest.class);
        verify(chromaApi).upsertEmbeddings(anyString(), anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().metadata().get(0)).doesNotContainKey("tags");
    }

    @Test
    @DisplayName("updateTags(빈 springDocIds): Chroma 호출 없음")
    void updateTags_emptyIds() {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        ChromaApi chromaApi = mock(ChromaApi.class);
        provider(registry, chromaApi, mock(EmbeddingModel.class), 0.0)
                .updateTags("owner", "latest", List.of(), "x");
        verifyNoInteractions(chromaApi);
    }
}
