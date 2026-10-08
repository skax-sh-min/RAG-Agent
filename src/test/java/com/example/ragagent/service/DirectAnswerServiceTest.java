package com.example.ragagent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — DirectAnswerService (meta / directMode, blocking + streaming) — EDIT.md #1
 *
 * execute() now routes through LlmRouter.executeGatedWithUsage() (concurrency-gated; a real,
 * blocking .call() — previously a streaming .stream().content().blockLast() that discarded
 * ChatResponse usage metadata entirely) so both /llm-usage and the per-turn AgentState token
 * totals see real counts for the blocking direct-answer path. executeStreaming() still can't
 * read real ChatResponse usage (token-by-token SSE UX), so it records an approximate (chars/4)
 * usage entry via LlmRouter.recordApproxUsage() and folds the same estimate into AgentState.
 */
@ResourceLock("global-state")
class DirectAnswerServiceTest {

    private LlmRouter llmRouter;
    private MessageSource messageSource;
    private DirectAnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        AppProperties props = mock(AppProperties.class);
        // llmSafe() supplies the Direct temperature (§6.18); a real record so directTemperature() works.
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, true));
        service = new DirectAnswerService(llmRouter, messageSource, props);
    }

    private AgentState newState(boolean directMode) {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST, directMode);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("execute — meta 질문 답변 생성, LlmResult의 실제 토큰 사용량이 그대로 누적된다")
    void execute_generatesAnswer() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("안녕하세요", 80, 15));

        AgentState result = service.execute(newState(false));

        assertThat(result.answer()).isEqualTo("안녕하세요");
        assertThat(result.totalInputTokens()).isEqualTo(80);
        assertThat(result.totalOutputTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("execute — directMode=true 는 모드별 Direct 시스템 프롬프트 키를 쓴다 (§6.24 Step 1-b)")
    void execute_directMode_usesDirectSystemPromptKey() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(true).toBuilder().responseMode(ResponseMode.N).build());
        verify(messageSource).getMessage(eq("prompt.direct.system.n"), any(), any(Locale.class));

        service.execute(newState(true).toBuilder().responseMode(ResponseMode.S).build());
        verify(messageSource).getMessage(eq("prompt.direct.system.s"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — directMode=false(meta) 는 prompt.direct.meta.system 키 사용")
    void execute_metaMode_usesMetaSystemPromptKey() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(false));

        verify(messageSource).getMessage(eq("prompt.direct.meta.system"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — 사용자 프롬프트에 스타일 지시문 층이 더는 없다 (§6.24 Step 0-c)")
    void execute_directMode_noLongerAppendsStyleInstruction() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(true).toBuilder().responseMode(ResponseMode.N).build());

        // 답변의 성격은 이제 전적으로 시스템 프롬프트가 정한다 — 사용자 메시지로 시스템 프롬프트를
        // 덮어쓰던 층 자체가 사라졌다.
        verify(messageSource, never())
                .getMessage(startsWith("prompt.answer.style"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — meta 경로도 스타일 지침이 없다(2-3문장 고정 유지)")
    void execute_metaMode_neverIncludesResponseStyleInstruction() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(false));

        verify(messageSource, never())
                .getMessage(startsWith("prompt.answer.style"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — 질문이 PromptInjectionGuard.wrap()으로 감싸져 전달됨 (EDIT.md #5)")
    @SuppressWarnings("unchecked")
    void execute_wrapsQuestionInUserQuestionDelimiters() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(false));

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("답변"));
        callCaptor.getValue().apply(chatModel);

        String userText = promptCaptor.getValue().getUserMessage().getText();
        assertThat(userText).contains("[USER_QUESTION]").contains("[/USER_QUESTION]");
    }

    /**
     * §6.18 회귀 방지 — 블로킹 Direct 호출의 Prompt가 direct-temperature(여기선 0.1)를 ChatOptions로
     * 실어 보내는지 검증한다. RAG 경로(프로바이더 defaultOptions의 일반 temperature)와 구분되는 핵심.
     */
    @Test
    @DisplayName("execute — Prompt에 direct-temperature가 ChatOptions로 실린다 (RAG와 온도 분리)")
    @SuppressWarnings("unchecked")
    void execute_attachesDirectTemperatureToPrompt() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        service.execute(newState(false));

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("답변"));
        callCaptor.getValue().apply(chatModel);

        org.springframework.ai.chat.prompt.ChatOptions opts = promptCaptor.getValue().getOptions();
        assertThat(opts).isNotNull();
        assertThat(opts.getTemperature()).isEqualTo(0.1); // llmSafe().directTemperature() (setUp의 mock)
    }

    @Test
    @DisplayName("executeStreaming — stream=false 프로바이더는 ChatClient 경유, listener.onToken 으로 전달 + 근사 사용량 기록")
    void executeStreaming_nonStreamingProvider_deliversTokensViaListener() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("스트리밍답변")));
        LlmProvider provider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", false, chatModel, null);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), any())).thenReturn(provider);

        List<String> tokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
        };

        AgentState result = service.executeStreaming(newState(false), listener);

        assertThat(result.answer()).isEqualTo("스트리밍답변");
        assertThat(String.join("", tokens)).isEqualTo("스트리밍답변");
        verify(llmRouter).recordApproxUsage(eq("local"), anyString(), eq("스트리밍답변"));
    }

    @Test
    @DisplayName("executeStreaming — stream=true 프로바이더는 OpenAiApi 네이티브 스트림을 직접 사용(청크별 onToken) + 근사 사용량 기록")
    void executeStreaming_streamingProvider_usesNativeOpenAiStream() {
        OpenAiApi openAiApi = mock(OpenAiApi.class);
        var delta1 = new OpenAiApi.ChatCompletionMessage("안", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        var delta2 = new OpenAiApi.ChatCompletionMessage("녕", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
        var choice1 = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, delta1, null);
        var choice2 = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, delta2, null);
        var chunk1 = new OpenAiApi.ChatCompletionChunk("id", List.of(choice1), null, "model", null, null, null, null);
        var chunk2 = new OpenAiApi.ChatCompletionChunk("id", List.of(choice2), null, "model", null, null, null, null);
        when(openAiApi.chatCompletionStream(any())).thenReturn(Flux.just(chunk1, chunk2));

        LlmProvider provider = new LlmProvider(
                "openai", TaskType.TEXT, ProviderRole.NORMAL, 1, "key", null, "gpt-4o", true, null, openAiApi);
        when(llmRouter.routeProvider(eq(TaskType.TEXT), any())).thenReturn(provider);

        List<String> tokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
        };

        AgentState result = service.executeStreaming(newState(false), listener);

        assertThat(result.answer()).isEqualTo("안녕");
        assertThat(tokens).containsExactly("안", "녕");
        verify(llmRouter).recordApproxUsage(eq("openai"), anyString(), eq("안녕"));
    }

    @Test
    @DisplayName("executeStreaming — stream=true 네이티브 OpenAiApi 우회 경로도 DEBUG 로그(엔드포인트+body)를 남긴다")
    void executeStreaming_streamingProvider_logsRequestAtDebugLevel() {
        Logger logbackLogger = com.example.ragagent.LogbackTestSupport.logger(DirectAnswerService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        Level previousLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.DEBUG);
        try {
            OpenAiApi openAiApi = mock(OpenAiApi.class);
            var delta = new OpenAiApi.ChatCompletionMessage("답변", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT);
            var choice = new OpenAiApi.ChatCompletionChunk.ChunkChoice(null, 0, delta, null);
            var chunk = new OpenAiApi.ChatCompletionChunk("id", List.of(choice), null, "model", null, null, null, null);
            when(openAiApi.chatCompletionStream(any())).thenReturn(Flux.just(chunk));

            LlmProvider provider = new LlmProvider("openai", TaskType.TEXT, ProviderRole.NORMAL, 1,
                    "sk-test-key-123456", "http://localhost:1234/v1", "gpt-4o", true, null, openAiApi);
            when(llmRouter.routeProvider(eq(TaskType.TEXT), any())).thenReturn(provider);

            GraphListener listener = new GraphListener() {
                @Override public void onToken(String text) { }
            };
            service.executeStreaming(newState(false), listener);

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                String msg = event.getFormattedMessage();
                assertThat(msg).contains("openai")
                        .contains("http://localhost:1234/v1/chat/completions")
                        .doesNotContain("curl -s -X POST")
                        .doesNotContain("Authorization")
                        .doesNotContain("sk-test-key-123456");
            });
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(previousLevel);
        }
    }

    // ── §10.13 Direct 경로의 예산 안전망 ────────────────────────────────────

    /**
     * 이 경로는 {@code fitToBudget()} 을 한 번도 부르지 않았다. 이력이 모드와 무관하게 5,000자로
     * 고정이라 드러나지 않았을 뿐이고, §10.13 이 그 상한을 창에서 파생시키기 시작하면 프롬프트가
     * 창을 넘길 수 있다 — 실제로 보내기 직전에 한 번 더 잰다.
     */
    private DirectAnswerService withWindow(int windowTokens) {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, true));
        when(llmRouter.findProviderName(eq(TaskType.TEXT), any())).thenReturn("local");
        var windows = new com.example.ragagent.llm.ProviderContextWindows();
        if (windowTokens > 0) {
            windows.record("local", windowTokens,
                    com.example.ragagent.llm.ProviderContextWindows.Source.PROBED);
        }
        return new DirectAnswerService(llmRouter, messageSource, props, windows);
    }

    private static AgentState stateWithHistory(String history) {
        return AgentState.of("질문", "v1", "t1", history, RoutingMode.COST_FIRST, true)
                .toBuilder().responseMode(ResponseMode.N).build();
    }

    @Test
    @DisplayName("§10.13 — 창에 안 맞는 이력은 보내기 전에 잘리고, 잘랐다는 사실을 사용자에게 말한다")
    void execute_trimsHistoryToTheWindowAndSaysSo() {
        // 8,192 − 4,200(N 블로킹 출력 예약) − 819(여유) − 질문 − 1,000 ≈ 2,170자
        String history = ("Q: 오래된 질문\nA: 오래된 답변\n\n").repeat(200) + "Q: 최근 질문\nA: 최근 답변";
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        AgentState result = withWindow(8_192).execute(stateWithHistory(history));

        assertThat(result.conversationHistory().length()).isLessThan(history.length());
        assertThat(result.conversationHistory()).contains("최근 질문");   // 오래된 쪽부터 버린다
        assertThat(result.budgetNote()).contains("이전 대화 일부를 제외했습니다");
    }

    @Test
    @DisplayName("§10.13 — 창을 모르면 아무것도 하지 않는다 (추측으로 대화 맥락을 버리지 않는다)")
    void execute_unknownWindow_leavesHistoryAlone() {
        String history = ("Q: 질문\nA: 답변\n\n").repeat(500);
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        AgentState result = withWindow(0).execute(stateWithHistory(history));

        assertThat(result.conversationHistory()).isEqualTo(history);
        assertThat(result.budgetNote()).isNull();
    }

    @Test
    @DisplayName("§10.13 — 예산 안에 들어가면 안내도 붙지 않는다")
    void execute_withinBudget_saysNothing() {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult("답변", 0, 0));

        AgentState result = withWindow(40_960).execute(stateWithHistory("Q: 질문\nA: 답변"));

        assertThat(result.conversationHistory()).isEqualTo("Q: 질문\nA: 답변");
        assertThat(result.budgetNote()).isNull();
    }
}
