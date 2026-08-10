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
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
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
 * 재시도 시 에스컬레이션 검증 — 후보 풀(candidateK, topK×(retry+1) 상한 3배)과 최종 컷
 * (effectiveTopK, topK+retry) 두 가지가 같은 app.search-retry-escalate 플래그로 함께 움직인다.
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

    /** RetrievalService's ctor eagerly builds a PromptTemplate from this — always needs a stub. */
    private static MessageSource stubMessageSource() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");
        return messageSource;
    }

    private RetrievalService serviceWithEscalate(boolean escalate) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(DEFAULT_TOP_K);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(escalate);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);

        ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(new Document("d", Map.of()))));

        return new RetrievalService(stubLlmRouter(), mock(LlmUsageRepository.class), ragService, props,
                Optional.empty(), Optional.empty(), stubMessageSource(), new ChatImageAnalysisSkipRegistry());
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

    // ── 최종 컷(retrievedDocs 개수) 에스컬레이션 ─────────────────────────────
    // 후보 풀만 키우면 "어떤 문서가 최종 자리를 놓고 경쟁하는지"만 바뀌고 답변 노드가 받는 문서 수는
    // 매번 topK 그대로였다. 재시도가 끌어올리려던 근거가 그 컷 바로 뒤에 있으면 재시도가 같은 이유로
    // 다시 실패한다. 그래서 최종 컷도 재시도마다 한 개씩 늘린다(topK + retryCount).

    /** 후보 풀보다 넉넉한 문서를 돌려주는 서비스 — 컷 크기가 결과 수를 결정하게 만든다. */
    private RetrievalService serviceReturningManyDocs(boolean escalate) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(DEFAULT_TOP_K);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(escalate);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);

        RagService rs = mock(RagService.class);
        List<Document> bigList = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> new Document("doc-" + i, Map.of()))
                .toList();
        when(rs.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(bigList));

        return new RetrievalService(stubLlmRouter(), mock(LlmUsageRepository.class), rs, props,
                Optional.empty(), Optional.empty(), stubMessageSource(), new ChatImageAnalysisSkipRegistry());
    }

    @Test
    @DisplayName("retryCount=0 → 결과 문서 수는 defaultTopK(5) 그대로")
    void firstAttempt_outputIsDefaultTopK() {
        AgentState result = serviceReturningManyDocs(true).execute(stateWithRetry(0));
        assertThat(result.retrievedDocs()).hasSize(DEFAULT_TOP_K);
    }

    @Test
    @DisplayName("retryCount=1 → 결과 문서 수 topK+1 = 6")
    void firstRetry_outputAddsOneDoc() {
        AgentState result = serviceReturningManyDocs(true).execute(stateWithRetry(1));
        assertThat(result.retrievedDocs()).hasSize(DEFAULT_TOP_K + 1);
    }

    @Test
    @DisplayName("retryCount=2 → 결과 문서 수 topK+2 = 7 (후보 풀처럼 배수로 커지지 않는다)")
    void secondRetry_outputAddsTwoDocs() {
        AgentState result = serviceReturningManyDocs(true).execute(stateWithRetry(2));
        assertThat(result.retrievedDocs()).hasSize(DEFAULT_TOP_K + 2);
    }

    @Test
    @DisplayName("escalate=false → 재시도해도 결과 문서 수는 defaultTopK 고정 (플래그 하나가 둘 다 끈다)")
    void escalateDisabled_outputStaysAtDefaultTopK() {
        AgentState result = serviceReturningManyDocs(false).execute(stateWithRetry(2));
        assertThat(result.retrievedDocs()).hasSize(DEFAULT_TOP_K);
    }
}
