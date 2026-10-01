package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "생각 끄기" 표시를 받을 수 있는 프로바이더에만 싣는 데코레이터({@link ThinkingOffChatModel}). 실제 요청 본문에
 * 실리는지는 Spring AI 의 직렬화까지 거치는 {@code ThinkingOffWireTest}(config 패키지)가 본다 — 여기서는 판정만.
 */
class ThinkingOffChatModelTest {

    private static ChatResponse ok() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("한 줄"))));
    }

    private static Prompt askingOff() {
        return new Prompt(List.of(new UserMessage("질문")),
                ThinkingOffChatModel.requestOff(OpenAiChatOptions.builder().maxTokens(256)).build());
    }

    private static Map<String, Object> extraBodyOf(Prompt prompt) {
        return ((OpenAiChatOptions) prompt.getOptions()).getExtraBody();
    }

    @Test
    @DisplayName("LOCAL 프로바이더에는 표시를 그대로 싣는다")
    void localServerGetsTheSwitch() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Prompt prompt = askingOff();

        new ThinkingOffChatModel(delegate, "local", true).call(prompt);

        verify(delegate).call(prompt);
        assertThat(extraBodyOf(prompt)).containsEntry(ThinkingOffChatModel.TEMPLATE_KWARGS,
                Map.of("enable_thinking", false));
    }

    @Test
    @DisplayName("원격 프로바이더에는 표시만 걷어내고 보낸다 — 다른 extraBody 항목과 원본 Prompt 는 그대로")
    void remoteProviderGetsItStripped() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Map<String, Object> body = new HashMap<>();
        body.put(ThinkingOffChatModel.TEMPLATE_KWARGS, Map.of("enable_thinking", false));
        body.put("top_k", 40);
        Prompt prompt = new Prompt(List.of(new UserMessage("질문")),
                OpenAiChatOptions.builder().maxTokens(256).extraBody(body).build());

        new ThinkingOffChatModel(delegate, "openai-mini", false).call(prompt);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).call(sent.capture());
        assertThat(extraBodyOf(sent.getValue()))
                .doesNotContainKey(ThinkingOffChatModel.TEMPLATE_KWARGS)
                .containsEntry("top_k", 40);
        assertThat(((OpenAiChatOptions) sent.getValue().getOptions()).getMaxTokens()).isEqualTo(256);
        assertThat(extraBodyOf(prompt)).as("원본은 건드리지 않는다").containsKey(ThinkingOffChatModel.TEMPLATE_KWARGS);
    }

    @Test
    @DisplayName("표시 하나뿐이었으면 extraBody 자체를 비운다")
    void strippingTheOnlyEntryLeavesNoExtraBody() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());

        new ThinkingOffChatModel(delegate, "gemini-flash-lite", false).call(askingOff());

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).call(sent.capture());
        assertThat(extraBodyOf(sent.getValue())).isNull();
    }

    @Test
    @DisplayName("표시가 없는 요청은 손대지 않는다")
    void promptsWithoutTheSwitchPassThrough() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(ok());
        Prompt prompt = new Prompt(List.of(new UserMessage("질문")), OpenAiChatOptions.builder().maxTokens(10).build());

        new ThinkingOffChatModel(delegate, "openai-mini", false).call(prompt);

        verify(delegate).call(prompt);
    }

    @Test
    @DisplayName("LOCAL 서버가 필드를 거부하면 표시 없이 한 번 다시 보내고, 그 뒤로는 처음부터 싣지 않는다 — 실패가 라우터로 가지 않는다")
    void aServerThatRejectsTheFieldIsRememberedAndRetriedWithout() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenAnswer(inv -> {
            Prompt p = inv.getArgument(0);
            if (ThinkingOffChatModel.asksThinkingOff(p)) {
                throw new NonTransientAiException(
                        "HTTP 400 - {\"error\":\"Unrecognized key(s) in object: 'chat_template_kwargs'\"}");
            }
            return ok();
        });
        ThinkingOffChatModel model = new ThinkingOffChatModel(delegate, "local", true);

        assertThat(model.call(askingOff())).isNotNull();
        assertThat(model.call(askingOff())).isNotNull();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(3)).call(sent.capture());   // 거부 1 + 재시도 1 + 두 번째 호출은 처음부터 없이 1
        assertThat(sent.getAllValues()).extracting(ThinkingOffChatModel::asksThinkingOff)
                .containsExactly(true, false, false);
    }

    @Test
    @DisplayName("필드와 무관한 실패는 그대로 던진다 — 재시도도, 기억도 하지 않는다")
    void unrelatedFailuresPropagate() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("HTTP 400 - the request exceeds the available context size"))
                .thenReturn(ok());
        ThinkingOffChatModel model = new ThinkingOffChatModel(delegate, "local", true);

        assertThatThrownBy(() -> model.call(askingOff())).hasMessageContaining("context size");
        model.call(askingOff());

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, times(2)).call(sent.capture());
        assertThat(sent.getAllValues()).extracting(ThinkingOffChatModel::asksThinkingOff)
                .as("거부로 기억되지 않았다").containsExactly(true, true);
    }

    @Test
    @DisplayName("스트리밍도 원격에는 걷어내고 보낸다")
    void streamingFollowsTheSameRule() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(ok()));

        new ThinkingOffChatModel(delegate, "openai-mini", false).stream(askingOff()).blockLast();

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).stream(sent.capture());
        assertThat(ThinkingOffChatModel.asksThinkingOff(sent.getValue())).isFalse();
    }
}
