package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — AnswerService 경로
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - BLOCKING (COST_FIRST)
 *  - STREAMING (GraphListener token 전달)
 *  - PROGRESSIVE 업그레이드 (sufficient=false 후 PREMIUM 호출)
 *
 * executeBlocking()/evaluate() now route through LlmRouter.executeGatedWithUsage() (TaskType.TEXT,
 * state.routingMode()) instead of a directly-injected ChatClient bound to a single boot-time-fixed
 * model — this was previously invisible to /llm-usage and ignored the request's routing mode
 * entirely (§6.14 in PLAN.md), and its real per-call token usage now accumulates into AgentState's
 * per-turn totals too (previously discarded — every call site fed accumulateTokens(0, 0)).
 * Streaming paths still can't read real ChatResponse usage, so they record an approximate
 * (chars/4) usage entry via LlmRouter.recordApproxUsage() and fold the same estimate into
 * AgentState's totals.
 */
class AnswerServiceTest {

    private static final int MAX_RETRY = 2;

    private LlmRouter llmRouter;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = new AppProperties(
                "./data", MAX_RETRY, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new AnswerService(llmRouter, props, messageSource);
    }

    private AgentState newState(RoutingMode mode) {
        return AgentState.of("질문", "v1", "t1", "", mode);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // ── BLOCKING 경로 ────────────────────────────────────────────────────
    // 답변(executeBlocking)과 sufficiency(evaluate) 둘 다 llmRouter.executeGatedWithUsage()을
    // 같은 (TaskType.TEXT, routingMode)로 순서대로 호출하므로 thenReturn(a, b)로 스텁한다.

    @Test
    @DisplayName("BLOCKING COST_FIRST — 답변+sufficiency 2회 호출, llmCallCount=2, 실제 토큰 사용량 누적")
    void blocking_costFirst_basicFlow() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("## 요약\n핵심 답변", 100, 40),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 30, 10));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.answer()).isEqualTo("## 요약\n핵심 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-flash");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(130);
        assertThat(result.totalOutputTokens()).isEqualTo(50);
        assertThat(result.llmCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("BLOCKING — sufficiency=false 면 needsRetry=true")
    void blocking_sufficiency_false_setsNeedsRetry() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("불완전 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
    }

    @Test
    @DisplayName("BLOCKING — sufficiency 파싱 실패 시 sufficient 처리 (fail-safe)")
    void blocking_sufficiency_parse_error_treatsAsSufficient() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("not-a-json", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isFalse();
    }

    @Test
    @DisplayName("BLOCKING — 질문이 답변/평가 프롬프트 모두에서 PromptInjectionGuard.wrap()으로 감싸짐 (EDIT.md #5)")
    @SuppressWarnings("unchecked")
    void blocking_wrapsQuestionInUserQuestionDelimiters() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        service.execute(newState(RoutingMode.COST_FIRST));

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        assertThat(promptCaptor.getAllValues()).hasSize(2).allSatisfy(prompt ->
                assertThat(prompt.getContents()).contains("[USER_QUESTION]").contains("[/USER_QUESTION]"));
    }

    @Test
    @DisplayName("BLOCKING — 답변 프롬프트의 [검색된 문서]는 정규화된 텍스트를 쓰고 맥락 헤더는 넣지 않는다(§10.1)")
    @SuppressWarnings("unchecked")
    void blocking_answerPrompt_usesNormalizedRetrievedDocTextWithoutContextHeader() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        Document doc = new Document("**중요**한 내용\n------", Map.of(
                MetaKey.FILENAME, "가이드.pdf", MetaKey.PAGE_OR_SLIDE, "3",
                MetaKey.CHUNK_CONTEXT, "가이드.pdf > 설정"));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(List.of(doc)).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String answerPrompt = promptCaptor.getAllValues().get(0).getContents();
        assertThat(answerPrompt).contains("중요한 내용");
        assertThat(answerPrompt).doesNotContain("**중요**", "------", "가이드.pdf > 설정");
    }

    @Test
    @DisplayName("BLOCKING — §10.10 큐레이션 Q&A 문서는 '[curated_qa | p.1]' 대신 고정 라벨을 쓴다")
    @SuppressWarnings("unchecked")
    void blocking_answerPrompt_labelsCuratedQaDocDistinctly() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        Document curated = new Document("과거에 좋아요 받은 답변", Map.of(
                MetaKey.DOC_TYPE, "curated_qa", MetaKey.FILENAME, "curated_qa", MetaKey.PAGE_OR_SLIDE, 1));
        AgentState state = newState(RoutingMode.COST_FIRST).toBuilder().retrievedDocs(List.of(curated)).build();

        service.execute(state);

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("dummy"));
        callCaptor.getAllValues().forEach(fn -> fn.apply(chatModel));

        String answerPrompt = promptCaptor.getAllValues().get(0).getContents();
        assertThat(answerPrompt).contains("[큐레이션 Q&A]", "과거에 좋아요 받은 답변");
        assertThat(answerPrompt).doesNotContain("curated_qa | p.1");
    }

    // ── STREAMING 경로 ───────────────────────────────────────────────────
    // ChatModel.stream()에는 call()로 위임하는 default 구현이 없어(UnsupportedOperationException)
    // ChatModel을 직접 mock 해야 한다 (DirectAnswerServiceTest와 동일 패턴).
    // sufficiency(evaluate)는 블로킹 호출이므로 executeGatedWithUsage으로 스텁한다.

    @Test
    @DisplayName("STREAMING COST_FIRST — 답변+sufficiency 2회 호출, llmCallCount=2 (executeBlocking과 동일하게 집계돼야 함), 스트리밍 답변은 근사 토큰이 누적됨")
    void streaming_costFirst_accumulatesLlmCallCount() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("스트리밍 답변")));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST))).thenReturn(provider);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));

        List<String> tokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
        };

        AgentState result = service.executeStreaming(newState(RoutingMode.COST_FIRST), listener);

        assertThat(result.answer()).isEqualTo("스트리밍 답변");
        assertThat(result.usedProvider()).isEqualTo("local");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.llmCallCount()).isEqualTo(2);
        // 스트리밍 답변 자체는 real ChatResponse usage가 없어 chars/4 근사치가 누적된다 (evaluate() 몫은 0).
        assertThat(result.totalOutputTokens()).isEqualTo((int) LlmRouter.approxTokens("스트리밍 답변"));
        verify(llmRouter).recordApproxUsage(eq("local"), anyString(), eq("스트리밍 답변"));
    }

    // ── PROGRESSIVE 업그레이드 경로 ───────────────────────────────────────

    @Test
    @DisplayName("PROGRESSIVE — sufficiency=false + retryCount>=maxRetry → QUALITY_FIRST 업그레이드")
    @SuppressWarnings("unchecked")
    void progressive_upgrade_triggersQualityFirst() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("초안 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), eq(RoutingMode.PROGRESSIVE)))
                .thenReturn("gemini-flash");
        LlmProvider premiumProvider = new LlmProvider(
                "gemini-pro", TaskType.TEXT, ProviderRole.PREMIUM, 4, "key", null, null, true, null, null);
        when(llmRouter.routeProvider(any(), eq(RoutingMode.QUALITY_FIRST)))
                .thenReturn(premiumProvider);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class)))
                .thenReturn(new LlmRouter.LlmResult("프리미엄 답변", 200, 90));

        // retryCount = maxRetry 도달 상태에서 진입 (그래프가 retry 한도 초과 후 PROGRESSIVE 진입 시 시나리오)
        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .toBuilder().incrementRetry().incrementRetry().build();

        AgentState result = service.execute(initial);

        assertThat(result.answer()).isEqualTo("프리미엄 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-pro");
        assertThat(result.premiumUpgraded()).isEqualTo("gemini-pro");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(200);
        assertThat(result.totalOutputTokens()).isEqualTo(90);
        verify(llmRouter, times(1))
                .executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class));
    }

    @Test
    @DisplayName("PROGRESSIVE — sufficient=true 면 업그레이드 안 함")
    void progressive_sufficient_noUpgrade() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("충분한 답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":true}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .toBuilder().incrementRetry().incrementRetry().build();

        AgentState result = service.execute(initial);

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.answer()).isEqualTo("충분한 답변");
    }

    @Test
    @DisplayName("PROGRESSIVE — needsRetry 더라도 retryCount < maxRetry 면 업그레이드 안 함")
    void progressive_underRetryLimit_noUpgrade() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.PROGRESSIVE), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0),
                            new LlmRouter.LlmResult("{\"sufficient\":false}", 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        // retryCount = 0 (< maxRetry=2) → 업그레이드 조건 미충족, needsRetry=true 만 전파
        AgentState result = service.execute(newState(RoutingMode.PROGRESSIVE));

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.needsRetry()).isTrue();
    }
}
