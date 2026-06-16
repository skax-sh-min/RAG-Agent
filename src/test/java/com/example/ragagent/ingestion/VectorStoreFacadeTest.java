package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.VectorStoreRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R-1 회귀 — 유사도 임계값이 SearchRequest 에 전달되는지 검증.
 * threshold=0 이면 Spring AI accept-all(0.0) 동작을 그대로 유지해야 한다.
 */
class VectorStoreFacadeTest {

    private SearchRequest capturedSearch(double threshold) {
        VectorStoreRegistry registry = mock(VectorStoreRegistry.class);
        VectorStore store = mock(VectorStore.class);
        when(registry.getStore(any(), any())).thenReturn(store);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        AppProperties props = mock(AppProperties.class);
        when(props.searchSimilarityThresholdSafe()).thenReturn(threshold);

        new VectorStoreFacade(registry, props).search("owner", "질문", "latest", 7);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("R-1: threshold>0 → SearchRequest.similarityThreshold 반영")
    void threshold_applied_whenConfigured() {
        SearchRequest req = capturedSearch(0.6);
        assertThat(req.getSimilarityThreshold()).isEqualTo(0.6);
        assertThat(req.getTopK()).isEqualTo(7);
    }

    @Test
    @DisplayName("R-1: threshold=0 → accept-all(0.0) 유지")
    void threshold_acceptAll_whenZero() {
        SearchRequest req = capturedSearch(0.0);
        assertThat(req.getSimilarityThreshold()).isEqualTo(0.0);
    }
}
