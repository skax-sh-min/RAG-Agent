package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
