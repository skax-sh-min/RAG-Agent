package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.31 이력 제외 — 켠 턴의 최종 컷에 {@code 이력 상한 ÷ chunk-size} 개를 더하되, 검증 쪽은 교환이 없으므로 두 상한(발췌 글자 ·
 * 창을 알면 실제 문서 크기로 잰 발췌 예산) 안에서만. 기본 컷은 어떤 경우에도 줄지 않는다.
 */
class RetrievalHistoryBoostTest {

    private static final int TOP_K = 10;
    private static final int MAX_TOKENS = 12_000;   // 이력 상한 = max(1,000, 12,000 ÷ 2) = 6,000자
    private static final int DOC_CHARS = 1_000;     // 한글이라 문서 하나 ≈ 1,000토큰

    private static List<Document> pool() {
        // 본문이 달라야 한다 — 융합이 같은 본문을 한 문서로 합친다. 머리말은 숫자 넷이라 토큰이 1 남짓 더해질 뿐이다
        return IntStream.range(0, 50)
                .mapToObj(i -> new Document("doc-" + i, "%04d".formatted(i) + "가".repeat(DOC_CHARS - 1), Map.of()))
                .toList();
    }

    private static RetrievalService service(int chunkSize, ProviderContextWindows windows) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(TOP_K);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(false);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(0);
        when(props.searchHybridEnabledSafe()).thenReturn(false);
        when(props.searchRetryEscalateSafe()).thenReturn(true);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.chunkSizeSafe()).thenReturn(chunkSize);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, MAX_TOKENS, 1, true));

        LlmRouter router = mock(LlmRouter.class);
        RouterTestSupport.executeOn(router, new LlmProvider(
                "lm", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null));
        when(router.findProviderName(any(), any())).thenReturn("lm");

        RagService rag = mock(RagService.class);
        when(rag.searchBatch(anyString(), any(), anyString(), anyInt())).thenReturn(List.of(pool()));

        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");
        return new RetrievalService(router, rag, props, Optional.empty(), Optional.empty(), messages,
                new ChatImageAnalysisSkipRegistry(), windows, ThinkingBudget.none());
    }

    private static AgentState state(boolean excludeHistory) {
        return AgentState.of("질문", "latest", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().excludeHistory(excludeHistory).build();
    }

    @Test
    @DisplayName("꺼져 있으면 최종 컷은 topK 그대로, 부스트 0")
    void offLeavesTheCutAlone() {
        AgentState result = service(1_500, new ProviderContextWindows()).execute(state(false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K);
        assertThat(result.historyBoost()).isZero();
    }

    @Test
    @DisplayName("켜면 이력 상한 ÷ chunk-size 만큼 더한다 — 기본값 6,000 ÷ 1,500 = +4(창을 모르면 그대로)")
    void onAddsHistoryCapOverChunkSize() {
        AgentState result = service(1_500, new ProviderContextWindows()).execute(state(true));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 4);
        assertThat(result.historyBoost()).isEqualTo(4);
    }

    @Test
    @DisplayName("(topK + 부스트) × chunk-size 가 검증 발췌의 글자 상한(32,000)을 넘지 않게 줄인다")
    void excerptCharCapBoundsTheBoost() {
        // 6,000 ÷ 2,900 = 2 이지만 32,000 ÷ 2,900 = 11 이라 10 위로 하나만 들어간다
        AgentState result = service(2_900, new ProviderContextWindows()).execute(state(true));

        assertThat(result.historyBoost()).isEqualTo(1);
        assertThat(result.retrievedDocs()).hasSize(TOP_K + 1);
    }

    /** 검증 발췌 예산이 고정 몫 + 문서 {@code docs} 개 + {@code slack} 토큰이 되는 가장 작은 창 — 런타임과 같은 함수로 잰다. */
    private static int windowFitting(int docs, long answerTokens) {
        int evalReservation = AnswerService.evalReservation(ThinkingBudget.none(), ThinkingSite.EVAL, "lm", MAX_TOKENS).tokens();
        long fixed = answerTokens + TokenEstimator.estimate("질문") + 1_500;   // RetrievalService.EVAL_OVERHEAD_TOKENS
        long target = fixed + (long) docs * DOC_CHARS + DOC_CHARS / 2;
        int window = 1_000;
        while (new PromptBudget(window, evalReservation).inputBudget() < target) window++;
        return window;
    }

    private static long firstAttemptAnswerTokens() {
        // 첫 시도에는 직전 답변이 없어 그 모드의 답변 출력 예약으로 추정한다(블로킹 — execute() 는 NOOP 리스너다)
        return AnswerService.answerReservation(ThinkingBudget.none(), ThinkingSite.ANSWER_RAG_N, "lm",
                ResponseMode.N, false, MAX_TOKENS).tokens();
    }

    @Test
    @DisplayName("창을 알면 부스트분이 검증 발췌 예산에 들어가는 만큼만 — 실제 문서 크기로 잰다")
    void knownWindowTrimsTheBoostToTheEvalBudget() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("lm", windowFitting(TOP_K + 2, firstAttemptAnswerTokens()), ProviderContextWindows.Source.CONFIGURED);

        AgentState result = service(1_500, windows).execute(state(true));

        assertThat(result.historyBoost()).isEqualTo(2);
        assertThat(result.retrievedDocs()).hasSize(TOP_K + 2);
    }

    @Test
    @DisplayName("재시도에서는 직전 답변의 실제 길이로 잰다 — 짧은 답변이면 같은 창에서 부스트가 더 들어간다")
    void retryMeasuresWithThePreviousAnswer() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("lm", windowFitting(TOP_K + 2, firstAttemptAnswerTokens()), ProviderContextWindows.Source.CONFIGURED);
        AgentState retry = state(true).toBuilder().answer("짧은 답변").build();

        AgentState result = service(1_500, windows).execute(retry);

        assertThat(result.historyBoost()).isEqualTo(4);
    }

    @Test
    @DisplayName("기본 컷조차 예산에 안 들어가면 부스트만 0 — 켜지 않은 턴보다 문서가 줄지는 않는다")
    void neverCutsBelowTheBaseTopK() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("lm", windowFitting(TOP_K - 3, firstAttemptAnswerTokens()), ProviderContextWindows.Source.CONFIGURED);

        AgentState result = service(1_500, windows).execute(state(true));

        assertThat(result.historyBoost()).isZero();
        assertThat(result.retrievedDocs()).hasSize(TOP_K);
    }

    @Test
    @DisplayName("재검색의 +1 과 더해진다(모드 부스트와 같은 관계) — 창을 모르면 topK + 1 + 4")
    void addsToTheRetryEscalation() {
        AgentState retry = state(true).toBuilder().retryCount(1).incrementRetrievalRetry().build();

        AgentState result = service(1_500, new ProviderContextWindows()).execute(retry);

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 1 + 4);
        assertThat(result.historyBoost()).isEqualTo(4);
    }
}
