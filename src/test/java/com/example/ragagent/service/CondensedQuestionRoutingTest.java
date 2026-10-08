package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.12 — 독립화된 질문이 <b>검색 축 셋과 분류기에는 닿고 답변 프롬프트에는 닿지 않는</b> 것을
 * 고정한다.
 *
 * <p>축 하나만 고치면 나머지 둘이 여전히 원문을 토크나이즈해 절반은 틀린 채 남는다는 것이 이
 * 항목이 MultiQuery 확장만으로 풀리지 않는 이유였다 — 그래서 세 축을 <b>각각</b> 확인한다.
 */
class CondensedQuestionRoutingTest {

    private static final String ORIGINAL  = "그거 어디야?";
    private static final String CONDENSED = "SSE 타임아웃 설정은 어디에 있어?";

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("filename", id, "page_or_slide", "1"));
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static AgentState condensedState() {
        return AgentState.of(ORIGINAL, "latest", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().searchQuestion(CONDENSED).build();
    }

    // ── AgentState 의 계약 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("재작성이 없으면 검색 질의는 원문 그대로다 — 기본 동작이 바뀌지 않는다")
    void withoutRewriteTheSearchQuestionIsTheOriginal() {
        AgentState plain = AgentState.of(ORIGINAL, "latest", "t1", "", RoutingMode.COST_FIRST);

        assertThat(plain.effectiveSearchQuestion()).isEqualTo(ORIGINAL);
        assertThat(plain.wasCondensed()).isFalse();
        assertThat(plain.toBuilder().searchQuestion("   ").build().effectiveSearchQuestion())
                .as("공백만 있는 재작성은 재작성이 아니다")
                .isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("재작성이 있어도 question() 은 원문 그대로다 — 답변의 어조·초점은 사용자가 쓴 말이 정한다")
    void rewriteNeverReplacesTheOriginalQuestion() {
        AgentState state = condensedState();

        assertThat(state.question()).isEqualTo(ORIGINAL);
        assertThat(state.effectiveSearchQuestion()).isEqualTo(CONDENSED);
        assertThat(state.wasCondensed()).isTrue();
    }

    // ── 검색 축 셋 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("벡터·BM25·큐레이션 세 축이 모두 독립화된 질문으로 검색한다")
    void allThreeAxesSearchWithTheCondensedQuestion() {
        RagService ragService = mock(RagService.class);
        when(ragService.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("vector")));
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(doc("vector"))));
        when(ragService.keywordSearch(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(doc("keyword")));

        RetrievalService svc = retrievalService(ragService, mock(ChatModel.class));
        svc.execute(condensedState());

        verify(ragService).keywordSearch(eq("latest"), eq(CONDENSED), anyInt());
        verify(ragService).search(eq("anonymous"), eq(CONDENSED),
                eq(CuratedQaService.CURATED_VERSION), anyInt());
        verify(ragService).searchBatch(eq("anonymous"), eq(List.of(CONDENSED)), eq("latest"), anyInt());
    }

    @Test
    @DisplayName("확장 게이트는 원문 길이로 잰다 — 독립화된 질의가 길다고 확장까지 함께 돌면 한 턴에 전처리 호출이 둘이 된다")
    void expansionGateStillReadsTheOriginalLength() {
        RagService ragService = mock(RagService.class);
        when(ragService.searchBatch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(List.of(doc("vector"))));

        ChatModel expansionModel = mock(ChatModel.class);
        when(expansionModel.call(any(Prompt.class))).thenReturn(chatResponse("변형1\n변형2"));

        RetrievalService svc = retrievalService(ragService, expansionModel);
        // 원문 7자 < 최소 길이 15자 → 확장은 건너뛰고 독립화된 질의 하나로만 검색한다.
        svc.execute(condensedState());

        verify(expansionModel, never()).call(any(Prompt.class));
        verify(ragService).searchBatch(eq("anonymous"), eq(List.of(CONDENSED)), eq("latest"), anyInt());
    }

    // ── 분류기 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("분류기도 독립화된 질문을 본다 — 분류기에 필요한 것은 이력이 아니라 자립적인 질문이었다")
    @SuppressWarnings("unchecked")
    void classifierSeesTheCondensedQuestion() {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(mock(AppProperties.LlmConfig.class));

        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("분류 프롬프트");

        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(), any(), any()))
                .thenReturn(new LlmRouter.LlmResult("{\"question_type\":\"usage\"}", 10, 3));

        ClassifierService classifier = new ClassifierService(router, messageSource, props);
        classifier.execute(condensedState());

        ArgumentCaptor<Function<ChatModel, ChatResponse>> fn = ArgumentCaptor.forClass(Function.class);
        verify(router).executeGatedWithUsage(any(), any(), fn.capture());

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(chatResponse("{}"));
        fn.getValue().apply(model);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());

        assertThat(prompt.getValue().getContents()).contains(CONDENSED);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static RetrievalService retrievalService(RagService ragService, ChatModel expansionModel) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopKSafe()).thenReturn(5);
        when(props.searchMultiqueryEnabledSafe()).thenReturn(true);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(15);
        when(props.searchHybridEnabledSafe()).thenReturn(true);
        when(props.searchCuratedQaEnabledSafe()).thenReturn(true);
        when(props.searchRetryEscalateSafe()).thenReturn(false);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        when(props.searchTagCandidateMultiplierSafe()).thenReturn(2);

        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true,
                expansionModel, null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);

        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("{query} {number}");

        return new RetrievalService(llmRouter, mock(LlmUsageRepository.class), ragService, props,
                Optional.empty(), Optional.empty(), messageSource,
                new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows());
    }
}
