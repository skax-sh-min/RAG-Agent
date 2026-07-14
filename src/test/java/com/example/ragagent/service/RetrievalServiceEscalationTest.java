package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재시도 시 topK 에스컬레이션 검증.
 */
class RetrievalServiceEscalationTest {

    private static final int DEFAULT_TOP_K = 5;

    private RagService ragService;

    /** RetrievalService 생성자가 MultiQueryExpander용 모델을 즉시 resolve하므로 항상 스텁 필요. */
    private static LlmRouter stubLlmRouter() {
        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
        return llmRouter;
    }

    private RetrievalService serviceWithEscalate(boolean escalate) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopK()).thenReturn(DEFAULT_TOP_K);
        when(props.searchMultiqueryEnabled()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabled()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(escalate);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);

        ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(new Document("d", Map.of()))));

        return new RetrievalService(stubLlmRouter(), mock(LlmUsageRepository.class), ragService, props,
                Optional.empty(), Optional.empty());
    }

    private static AgentState stateWithRetry(int retryCount) {
        AgentState base = AgentState.of("질문", "latest", "t1", "", RoutingMode.COST_FIRST);
        return base.toBuilder().retryCount(retryCount).build();
    }

    @Test
    @DisplayName("retryCount=0 → defaultTopK(5) 그대로 사용")
    void firstAttempt_usesDefaultTopK() {
        RetrievalService svc = serviceWithEscalate(true);
        svc.execute(stateWithRetry(0));
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(DEFAULT_TOP_K));
    }

    @Test
    @DisplayName("retryCount=1 → topK×2 = 10")
    void firstRetry_doublesTopK() {
        RetrievalService svc = serviceWithEscalate(true);
        svc.execute(stateWithRetry(1));
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(DEFAULT_TOP_K * 2));
    }

    @Test
    @DisplayName("retryCount=2 → topK×3 = 15 (상한)")
    void secondRetry_capsAtTriple() {
        RetrievalService svc = serviceWithEscalate(true);
        svc.execute(stateWithRetry(2));
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(DEFAULT_TOP_K * 3));
    }

    @Test
    @DisplayName("retryCount=3 → 상한(×3)에서 고정")
    void highRetry_staysAtCap() {
        RetrievalService svc = serviceWithEscalate(true);
        svc.execute(stateWithRetry(3));
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(DEFAULT_TOP_K * 3));
    }

    @Test
    @DisplayName("escalate=false → retry 무관 defaultTopK 고정")
    void escalateDisabled_alwaysDefaultTopK() {
        RetrievalService svc = serviceWithEscalate(false);
        svc.execute(stateWithRetry(2));
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(DEFAULT_TOP_K));
    }

    @Test
    @DisplayName("escalate=true, retryCount=2 → 결과 문서 수 defaultTopK(5) 이하")
    void escalate_outputCappedAtDefaultTopK() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopK()).thenReturn(DEFAULT_TOP_K);
        when(props.searchMultiqueryEnabled()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabled()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(true);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);

        RagService rs = mock(RagService.class);
        List<Document> bigList = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new Document("doc-" + i, Map.of()))
                .toList();
        when(rs.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(bigList));

        RetrievalService svc = new RetrievalService(stubLlmRouter(), mock(LlmUsageRepository.class), rs, props,
                Optional.empty(), Optional.empty());

        AgentState result = svc.execute(stateWithRetry(2));
        assertThat(result.retrievedDocs()).hasSizeLessThanOrEqualTo(DEFAULT_TOP_K);
    }
}
