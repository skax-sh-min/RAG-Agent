package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.service.CrossEncoderReranker.Ranked;
import com.example.ragagent.service.CrossEncoderReranker.UnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 교차 인코더 리랭커를 검색에 연결한 동작(Phase 1a) — 후보 풀 크기, 최종 컷, 재시도 증가분, 실패 시 후퇴, LLM 리랭커와의 관계.
 * 기본 설정은 {@code search-top-k} 10 이므로 파생값은 후보 20 · 최종 컷 5 다(AGENT_MCP_DESIGN §4.3).
 */
class RetrievalServiceCrossEncoderTest {

    private static final int TOP_K = 10;
    private static final int RERANK_CANDIDATES = 20;
    private static final int RERANK_CUT = 5;

    private RagService ragService;
    private CrossEncoderReranker crossEncoder;
    private RerankerService llmReranker;

    private static LlmRouter stubLlmRouter() {
        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
        RouterTestSupport.executeOn(llmRouter, expansionProvider);
        return llmRouter;
    }

    private static MessageSource stubMessageSource() {
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");
        return messageSource;
    }

    private static List<Document> docs(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Document("doc-" + i, Map.of())).toList();
    }

    /** 교차 인코더가 있는 서비스 — RAG 는 후보 풀보다 넉넉한 문서 30개를 준다. */
    private RetrievalService service(boolean withLlmReranker) {
        return service(withLlmReranker, false);
    }

    private RetrievalService service(boolean withLlmReranker, boolean multiqueryEnabled) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(TOP_K);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(multiqueryEnabled);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(true);
        when(props.searchRerankEnabled()).thenReturn(withLlmReranker);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.effectiveRerankCandidates()).thenReturn(RERANK_CANDIDATES);
        when(props.effectiveRerankTopK()).thenReturn(RERANK_CUT);

        ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt())).thenReturn(List.of(docs(30)));

        crossEncoder = mock(CrossEncoderReranker.class);
        llmReranker = mock(RerankerService.class);
        return new RetrievalService(stubLlmRouter(), ragService, props, Optional.empty(),
                withLlmReranker ? Optional.of(llmReranker) : Optional.empty(), Optional.of(crossEncoder),
                stubMessageSource(), new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows(),
                ThinkingBudget.none());
    }

    private static AgentState stateWithRetry(int retrievalRetries) {
        AgentState base = AgentState.of("질문", "latest", "t1", "", RoutingMode.COST_FIRST);
        AgentState.Builder b = base.toBuilder().retryCount(retrievalRetries);
        for (int i = 0; i < retrievalRetries; i++) b.incrementRetrievalRetry();
        return b.build();
    }

    /** 리랭커 대역 — 받은 후보를 뒤집어 앞에서 topK 개를 돌려준다(RRF 순서와 확실히 다른 순서). */
    private void reverseOrderReranker() {
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<Document> in = inv.getArgument(1);
            int topK = inv.getArgument(2);
            List<Ranked> out = new ArrayList<>();
            for (int i = in.size() - 1; i >= 0 && out.size() < topK; i--) out.add(new Ranked(in.get(i), i));
            return out;
        });
    }

    @Test
    @DisplayName("후보 풀은 search-top-k × 2(20)로 가져오고, 리랭크 뒤 최종 컷(5)만 답변에 싣는다 — 리랭크 순서대로")
    void fetchesTheDerivedPoolAndKeepsTheDerivedCut() {
        RetrievalService svc = service(false);
        reverseOrderReranker();

        AgentState out = svc.execute(stateWithRetry(0));

        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(RERANK_CANDIDATES));
        verify(crossEncoder).rerank(eq("질문"), anyList(), eq(RERANK_CUT));
        assertThat(out.retrievedDocs()).hasSize(RERANK_CUT);
        assertThat(out.retrievedDocs().get(0).getText()).as("리랭커가 1위로 올린 문서").isEqualTo("doc-19");
    }

    @Test
    @DisplayName("재시도 증가분(+1/회)은 최종 컷 5 위에 그대로 더해진다 — 재시도가 문서를 늘리는 기존 동작을 지킨다")
    void retryGrowthAddsOnTopOfTheDerivedCut() {
        RetrievalService svc = service(false);
        reverseOrderReranker();

        AgentState out = svc.execute(stateWithRetry(1));

        verify(crossEncoder).rerank(eq("질문"), anyList(), eq(RERANK_CUT + 1));
        assertThat(out.retrievedDocs()).hasSize(RERANK_CUT + 1);
        // 후보 풀은 재시도 확장(round(10 × 1.5) = 15)보다 파생 후보 수(20)가 크므로 20이다
        verify(ragService).searchBatch(anyString(), any(), anyString(), eq(RERANK_CANDIDATES));
    }

    @Test
    @DisplayName("리랭커를 쓸 수 없으면 RRF 순서로 물러난다 — 컷은 search-top-k(10), 예외는 밖으로 나가지 않는다")
    void fallsBackToRrfOrderWithTheOriginalCut() {
        RetrievalService svc = service(false);
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenThrow(new UnavailableException("서버 다운"));

        AgentState out = svc.execute(stateWithRetry(0));

        assertThat(out.retrievedDocs()).hasSize(TOP_K);
        assertThat(out.retrievedDocs()).extracting(Document::getText).startsWith("doc-0", "doc-1", "doc-2");
        assertThat(out.sources()).as("출처도 같은 문서로 만들어진다").isNotEmpty();
    }

    @Test
    @DisplayName("교차 인코더가 있으면 LLM 리랭커는 쓰지 않는다 — search-rerank-enabled=true 여도")
    void crossEncoderReplacesTheLlmReranker() {
        RetrievalService svc = service(true);
        reverseOrderReranker();

        svc.execute(stateWithRetry(0));

        verify(llmReranker, never()).rerank(anyString(), anyList(), anyInt());
        verify(crossEncoder).rerank(eq("질문"), anyList(), eq(RERANK_CUT));
    }

    @Test
    @DisplayName("교차 인코더가 동작하면 질의 확장(LLM)은 건너뛴다 — 확장이 켜져 있어도 검색은 원문 하나로 한 번")
    void queryExpansionIsSkippedWhenTheCrossEncoderRuns() {
        RetrievalService svc = service(false, true);
        reverseOrderReranker();

        svc.execute(stateWithRetry(0));

        // 확장 경로는 원문 검색(search)과 변형 배치(searchBatch)로 나뉜다 — 건너뛰면 원문 하나짜리 배치 한 번뿐이다
        verify(ragService, never()).search(anyString(), anyString(), anyString(), anyInt());
        verify(ragService).searchBatch(anyString(), eq(List.of("질문")), anyString(), eq(RERANK_CANDIDATES));
    }
}
