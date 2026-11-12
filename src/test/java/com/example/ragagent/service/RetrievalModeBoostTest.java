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
 * §6.24 Step 4-c — 응답 모드의 검색 부스트(C 는 +3). 이력 부스트와 달리 교환이 없어 프롬프트가 그만큼 커지므로 <b>창을 알 때만,
 * 검증 발췌 예산 안에서만</b> 들어간다. 기본 컷은 어떤 경우에도 줄지 않는다.
 */
class RetrievalModeBoostTest {

    private static final int TOP_K = 10;
    private static final int MAX_TOKENS = 12_000;
    private static final int DOC_CHARS = 1_000;     // 한글이라 문서 하나 ≈ 1,000토큰
    private static final int BIG_WINDOW = 131_072;

    private static List<Document> pool() {
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

    private static ProviderContextWindows window(int tokens) {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("lm", tokens, ProviderContextWindows.Source.CONFIGURED);
        return windows;
    }

    private static AgentState state(ResponseMode mode, boolean excludeHistory) {
        return AgentState.of("질문", "latest", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().responseMode(mode).excludeHistory(excludeHistory).build();
    }

    /** C 의 검증 발췌 예산이 고정 몫 + 문서 {@code docs} 개 + 반 개가 되는 가장 작은 창 — 런타임과 같은 함수로 잰다. */
    private static int windowFitting(int docs) {
        ResponseMode c = ResponseMode.C;
        int evalReservation = AnswerService.evalReservation(ThinkingBudget.none(), c.evalThinkingSite(), "lm", MAX_TOKENS).tokens();
        long answer = AnswerService.answerReservation(ThinkingBudget.none(), c.ragThinkingSite(), "lm", c, false, MAX_TOKENS).tokens();
        long fixed = answer + TokenEstimator.estimate("질문") + 1_500;   // RetrievalService.EVAL_OVERHEAD_TOKENS
        long target = fixed + (long) docs * DOC_CHARS + DOC_CHARS / 2;
        int window = 1_000;
        while (new PromptBudget(window, evalReservation).inputBudget() < target) window++;
        return window;
    }

    @Test
    @DisplayName("창을 모르면 C 도 부스트 0 — 추측 없이 프롬프트를 키우지 않는다")
    void unknownWindowMeansNoModeBoost() {
        AgentState result = service(1_500, new ProviderContextWindows()).execute(state(ResponseMode.C, false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K);
    }

    @Test
    @DisplayName("창이 넉넉하면 C 의 최종 컷은 topK + 3")
    void roomyWindowAddsThreeForC() {
        AgentState result = service(1_500, window(BIG_WINDOW)).execute(state(ResponseMode.C, false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + ResponseMode.C.retrievalBoost());
        assertThat(result.historyBoost()).as("이력 부스트 칸은 이력 제외 몫만 나른다").isZero();
    }

    @Test
    @DisplayName("N·S 는 창이 넉넉해도 부스트 0 — 기본 동작을 바꾸지 않는다")
    void otherModesAreUntouched() {
        for (ResponseMode mode : List.of(ResponseMode.N, ResponseMode.S)) {
            AgentState result = service(1_500, window(BIG_WINDOW)).execute(state(mode, false));

            assertThat(result.retrievedDocs()).as("%s", mode).hasSize(TOP_K);
        }
    }

    @Test
    @DisplayName("부스트분이 검증 발췌 예산에 다 안 들어가면 들어가는 만큼만 — 꼬리부터 덜어낸다")
    void trimsTheBoostToTheEvalBudget() {
        AgentState result = service(1_500, window(windowFitting(TOP_K + 1))).execute(state(ResponseMode.C, false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 1);
    }

    @Test
    @DisplayName("기본 컷조차 예산에 안 들어가면 부스트만 0 — 부스트가 없는 턴보다 문서가 줄지는 않는다 (작은 창)")
    void smallWindowDropsTheBoostButNeverTheBaseCut() {
        AgentState result = service(1_500, window(windowFitting(TOP_K - 3))).execute(state(ResponseMode.C, false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K);
    }

    @Test
    @DisplayName("(기본 컷 + 부스트) × chunk-size 가 검증 발췌 글자 상한(32,000)을 넘지 않는다")
    void excerptCharCapBoundsTheModeBoost() {
        // 32,000 ÷ 2,900 = 11 이라 10 위로 하나만 들어간다
        AgentState result = service(2_900, window(BIG_WINDOW)).execute(state(ResponseMode.C, false));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 1);
    }

    @Test
    @DisplayName("이력 제외 부스트와 더해진다 — 모드 +3 이 앞, 이력 +4 가 뒤이고 AgentState.historyBoost 는 이력 몫(4)만")
    void addsToTheHistoryBoost() {
        AgentState result = service(1_500, window(BIG_WINDOW)).execute(state(ResponseMode.C, true));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 3 + 4);
        assertThat(result.historyBoost()).isEqualTo(4);
    }

    @Test
    @DisplayName("덜어낼 때는 이력 부스트가 먼저 빠진다 — 모드 부스트는 순위가 앞이다")
    void trimsTheHistoryBoostBeforeTheModeBoost() {
        AgentState result = service(1_500, window(windowFitting(TOP_K + 3))).execute(state(ResponseMode.C, true));

        assertThat(result.retrievedDocs()).hasSize(TOP_K + 3);
        assertThat(result.historyBoost()).isZero();
    }
}
