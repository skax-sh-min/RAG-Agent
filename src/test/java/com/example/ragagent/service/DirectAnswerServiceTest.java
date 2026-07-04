package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — DirectAnswerService (meta / directMode, blocking + streaming) — EDIT.md #1
 *
 * ChatModel.stream(Prompt) has no delegating default (throws UnsupportedOperationException
 * unless overridden), so both execute() and the stream=false branch of executeStreaming()
 * — which both go through ChatClient's reactive .stream() surface even though execute() is
 * nominally "blocking" — require stubbing ChatModel.stream() directly, not just call().
 */
class DirectAnswerServiceTest {

    private LlmRouter llmRouter;
    private MessageSource messageSource;
    private DirectAnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new DirectAnswerService(llmRouter, messageSource);
    }

    private AgentState newState(boolean directMode) {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST, directMode);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("execute — meta 질문 답변 생성, 토큰 (0,0) 누적 (스트리밍 경로라 사용량 미측정)")
    void execute_generatesAnswer() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("안녕하세요")));
        when(llmRouter.route(eq(TaskType.TEXT), any())).thenReturn(chatModel);

        AgentState result = service.execute(newState(false));

        assertThat(result.answer()).isEqualTo("안녕하세요");
        assertThat(result.totalInputTokens()).isZero();
        assertThat(result.totalOutputTokens()).isZero();
    }

    @Test
    @DisplayName("execute — directMode=true 는 prompt.direct.system 키 사용")
    void execute_directMode_usesDirectSystemPromptKey() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("답변")));
        when(llmRouter.route(eq(TaskType.TEXT), any())).thenReturn(chatModel);

        service.execute(newState(true));

        verify(messageSource).getMessage(eq("prompt.direct.system"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — directMode=false(meta) 는 prompt.direct.meta.system 키 사용")
    void execute_metaMode_usesMetaSystemPromptKey() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("답변")));
        when(llmRouter.route(eq(TaskType.TEXT), any())).thenReturn(chatModel);

        service.execute(newState(false));

        verify(messageSource).getMessage(eq("prompt.direct.meta.system"), any(), any(Locale.class));
    }

    @Test
    @DisplayName("execute — 질문이 PromptInjectionGuard.wrap()으로 감싸져 전달됨 (EDIT.md #5)")
    void execute_wrapsQuestionInUserQuestionDelimiters() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("답변")));
        when(llmRouter.route(eq(TaskType.TEXT), any())).thenReturn(chatModel);
        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);

        service.execute(newState(false));

        verify(chatModel).stream(captor.capture());
        String userText = captor.getValue().getUserMessage().getText();
        assertThat(userText).contains("[USER_QUESTION]").contains("[/USER_QUESTION]");
    }

    @Test
    @DisplayName("execute — DUAL 라우팅 모드는 COST_FIRST 로 폴백(DirectAnswer 는 DUAL 미지원)")
    void execute_dualMode_fallsBackToCostFirst() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chatResponse("답변")));
        when(llmRouter.route(TaskType.TEXT, RoutingMode.COST_FIRST)).thenReturn(chatModel);

        AgentState dualState = AgentState.of("질문", "v1", "t1", "", RoutingMode.DUAL, false);
        AgentState result = service.execute(dualState);

        assertThat(result.answer()).isEqualTo("답변");
        verify(llmRouter).route(TaskType.TEXT, RoutingMode.COST_FIRST);
    }

    @Test
    @DisplayName("executeStreaming — stream=false 프로바이더는 ChatClient 경유, listener.onToken 으로 전달")
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
    }

    @Test
    @DisplayName("executeStreaming — stream=true 프로바이더는 OpenAiApi 네이티브 스트림을 직접 사용(청크별 onToken)")
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
    }
}
