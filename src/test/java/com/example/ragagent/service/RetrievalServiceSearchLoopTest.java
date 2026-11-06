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
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재검색 루프를 {@code RetrievalService} 에 연결한 동작(§6.33) — 첫 RETRIEVAL 에서만 돌고, 쓴 LLM 호출은 턴의 호출 수·토큰에 잡히며,
 * 재검색을 다 하고도 점수가 낮으면 답변 프롬프트의 [경고] 에 남는다.
 */
class RetrievalServiceSearchLoopTest {

    private static final int CUT = 5;

    private RagService ragService;
    private CrossEncoderReranker crossEncoder;
    private SearchQueryRewriter rewriter;
    private AppProperties props;
    private RetrievalService svc;

    @BeforeEach
    void setUp() {
        props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(10);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(true);
        when(props.effectiveRerankCandidates()).thenReturn(20);
        when(props.effectiveRerankTopK()).thenReturn(CUT);
        when(props.searchRerankMinScoreSafe()).thenReturn(0.0);
        when(props.searchMaxAttemptsSafe()).thenReturn(3);

        // 검색어마다 이름이 다른 문서 30개 — 어느 회차의 문서인지 알아볼 수 있다
        ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt())).thenAnswer(inv -> {
            List<String> queries = inv.getArgument(1);
            return List.of(IntStream.range(0, 30).mapToObj(i -> new Document(queries.get(0) + "/doc-" + i, Map.of())).toList());
        });

        LlmRouter llmRouter = mock(LlmRouter.class);
        RouterTestSupport.executeOn(llmRouter, new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null));
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");

        crossEncoder = mock(CrossEncoderReranker.class);
        rewriter = mock(SearchQueryRewriter.class);
        HybridSearchService hybrid = new HybridSearchService(llmRouter, ragService, props, messages);
        SearchLoopService loop = new SearchLoopService(hybrid, Optional.of(crossEncoder), rewriter, props);
        svc = new RetrievalService(hybrid, loop, llmRouter, ragService, props, Optional.empty(), Optional.empty(),
                Optional.of(crossEncoder), new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows(),
                ThinkingBudget.none());
    }

    private static AgentState state(int retrievalRetries) {
        AgentState.Builder b = AgentState.of("질문", "latest", "t1", "", RoutingMode.COST_FIRST).toBuilder();
        for (int i = 0; i < retrievalRetries; i++) b.incrementRetrievalRetry();
        return b.build();
    }

    /** 검색어별 1위 점수 — 그 회차의 첫 문서에 이 점수를, 이어지는 문서는 1씩 낮춘다. */
    private void topScores(Map<String, Double> byQuery) {
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            List<Document> in = inv.getArgument(1);
            int topK = inv.getArgument(2);
            List<Ranked> out = new ArrayList<>();
            for (int i = 0; i < in.size() && out.size() < topK; i++) out.add(new Ranked(in.get(i), byQuery.get(q) - i));
            return out;
        });
    }

    private void rewritesTo(String... queries) {
        org.mockito.stubbing.OngoingStubbing<Optional<SearchQueryRewriter.Next>> chain = null;
        var stub = when(rewriter.nextQuery(anyString(), anyString(), anyString(), anyList(), anyList(), any()));
        for (String q : queries) {
            Optional<SearchQueryRewriter.Next> next = Optional.of(new SearchQueryRewriter.Next(q, 200, 20));
            chain = (chain == null) ? stub.thenReturn(next) : chain.thenReturn(next);
        }
    }

    @Test
    @DisplayName("1위 점수가 문턱 이상이면 한 번만 검색하고 경고도 새 LLM 호출도 없다")
    void goodFirstResultNeedsNoLoop() {
        topScores(Map.of("질문", 3.0));

        AgentState out = svc.execute(state(0));

        verify(ragService, times(1)).searchBatch(anyString(), any(), anyString(), anyInt());
        assertThat(out.retrievedDocs()).hasSize(CUT);
        assertThat(out.retrievalWarnings()).doesNotContain(RetrievalService.LOW_RELEVANCE_WARNING);
        assertThat(out.llmCallCount()).isZero();
    }

    @Test
    @DisplayName("문턱 미만이면 새 검색어로 다시 찾아 더 나은 문서를 쓴다 — 새 검색어 호출은 턴의 호출 수·토큰에 잡힌다")
    void lowScoreTriggersARewriteAndUsesTheBetterRound() {
        topScores(Map.of("질문", -2.0, "다른 표현", 4.0));
        rewritesTo("다른 표현");
        GraphListener listener = mock(GraphListener.class);

        AgentState out = svc.execute(state(0), listener);

        verify(ragService, times(2)).searchBatch(anyString(), any(), anyString(), anyInt());
        verify(listener).onSearchRetry(2, 3);
        assertThat(out.retrievedDocs()).hasSize(CUT);
        assertThat(out.retrievedDocs().get(0).getText()).as("재검색이 찾은 1위").isEqualTo("다른 표현/doc-0");
        assertThat(out.retrievalWarnings()).doesNotContain(RetrievalService.LOW_RELEVANCE_WARNING);
        assertThat(out.llmCallCount()).isEqualTo(1);
        assertThat(out.totalInputTokens()).isEqualTo(200);
        assertThat(out.totalOutputTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("재검색을 다 하고도 문턱 미만이면 [경고] 문장이 남고, 검색은 상한(3회)을 넘지 않는다")
    void stillLowAfterTheLastAttemptLeavesAWarning() {
        topScores(Map.of("질문", -2.0, "a", -3.0, "b", -4.0));
        rewritesTo("a", "b");

        AgentState out = svc.execute(state(0));

        verify(ragService, times(3)).searchBatch(anyString(), any(), anyString(), anyInt());
        assertThat(out.retrievalWarnings()).contains(RetrievalService.LOW_RELEVANCE_WARNING);
        assertThat(out.llmCallCount()).isEqualTo(2);
        assertThat(out.retrievedDocs()).as("가장 점수가 높았던 첫 검색의 문서가 남는다").isNotEmpty();
        assertThat(out.retrievedDocs().get(0).getText()).isEqualTo("질문/doc-0");
    }

    @Test
    @DisplayName("답변 검증 실패 재시도(RETRIEVAL 두 번째 진입)는 점수가 낮아도 재검색하지 않고 경고도 다시 쌓지 않는다")
    void theLoopRunsOnlyOnTheTurnsFirstRetrieval() {
        topScores(Map.of("질문", -2.0));

        AgentState out = svc.execute(state(1));

        verify(ragService, times(1)).searchBatch(anyString(), any(), anyString(), anyInt());
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
        assertThat(out.retrievalWarnings()).doesNotContain(RetrievalService.LOW_RELEVANCE_WARNING);
        // 재시도 증가분(+1)은 그대로 최종 컷 위에 더해진다
        assertThat(out.retrievedDocs()).hasSize(CUT + 1);
    }

    @Test
    @DisplayName("search-max-attempts=1 이면 재검색 없이, 점수가 낮은 채로 경고만 남는다")
    void maxAttemptsOneKeepsTheWarningButNeverRewrites() {
        when(props.searchMaxAttemptsSafe()).thenReturn(1);
        topScores(Map.of("질문", -2.0));

        AgentState out = svc.execute(state(0));

        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
        assertThat(out.retrievalWarnings()).contains(RetrievalService.LOW_RELEVANCE_WARNING);
    }

    @Test
    @DisplayName("리랭커를 쓸 수 없으면 재검색도 경고도 없다 — 점수를 못 얻었으니 낮다고 말할 수 없다(RRF 순서로 물러남)")
    void unavailableRerankerMeansNoLoopAndNoWarning() {
        when(crossEncoder.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new CrossEncoderReranker.UnavailableException("서버 다운"));

        AgentState out = svc.execute(state(0));

        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
        verify(ragService, times(1)).searchBatch(anyString(), any(), anyString(), anyInt());
        assertThat(out.retrievalWarnings()).doesNotContain(RetrievalService.LOW_RELEVANCE_WARNING);
        assertThat(out.retrievedDocs()).hasSize(10);
    }

    @Test
    @DisplayName("문턱은 이번 호출에서 읽은 핫 설정이다 — 올리면 같은 점수가 재검색 대상이 된다")
    void thresholdFollowsTheHotSetting() {
        topScores(Map.of("질문", 0.5, "다른 표현", 4.0));
        rewritesTo("다른 표현");
        svc.execute(state(0));
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());

        when(props.searchRerankMinScoreSafe()).thenReturn(1.0);
        svc.execute(state(0));
        verify(rewriter, times(1)).nextQuery(any(), eq("t1"), eq("질문"), anyList(), anyList(), any());
    }
}
