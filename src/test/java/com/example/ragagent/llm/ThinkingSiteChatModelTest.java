package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 옵션을 직접 넘길 수 없는 프레임워크 호출부를 위한 표시 래퍼({@link ThinkingSiteChatModel})와, Spring AI
 * {@code ChatClient} 를 거쳐도 표시가 모델까지 닿는지(PLAN §6.29 2단계). 표시가 중간에 사라지면 오류 없이 그 호출만
 * 생각 수준이 먹지 않으므로, 실제 {@code MultiQueryExpander}·{@code ChatClient} 로 확인한다.
 */
class ThinkingSiteChatModelTest {

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Prompt sentTo(ChatModel delegate) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate, atLeastOnce()).call(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("OpenAiChatOptions 프롬프트 — 사본에 표시를 더하고 다른 값·extraBody 항목은 남긴다. 원본은 그대로")
    void marksACopyOfOpenAiOptions() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response("x"));
        Map<String, Object> body = new HashMap<>();
        body.put("top_k", 40);
        Prompt prompt = new Prompt(List.of(new UserMessage("q")),
                OpenAiChatOptions.builder().temperature(0.2).extraBody(body).build());

        new ThinkingSiteChatModel(delegate, ThinkingSite.QUERY_EXPANSION).call(prompt);

        Prompt sent = sentTo(delegate);
        assertThat(ThinkingControl.siteOf(sent)).isEqualTo(ThinkingSite.QUERY_EXPANSION);
        assertThat(((OpenAiChatOptions) sent.getOptions()).getExtraBody()).containsEntry("top_k", 40);
        assertThat(sent.getOptions().getTemperature()).isEqualTo(0.2);
        assertThat(ThinkingControl.siteOf(prompt)).as("원본은 건드리지 않는다").isNull();
    }

    @Test
    @DisplayName("옵션이 없거나 일반 ChatOptions 여도 표시한다 — 일반 옵션의 값은 옮겨 담는다")
    void marksMissingOrGenericOptions() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response("x"));
        ThinkingSiteChatModel model = new ThinkingSiteChatModel(delegate, ThinkingSite.QUERY_EXPANSION);

        model.call(new Prompt(List.of(new UserMessage("q"))));
        assertThat(ThinkingControl.siteOf(sentTo(delegate))).isEqualTo(ThinkingSite.QUERY_EXPANSION);

        ChatModel delegate2 = mock(ChatModel.class);
        when(delegate2.call(any(Prompt.class))).thenReturn(response("x"));
        new ThinkingSiteChatModel(delegate2, ThinkingSite.QUERY_EXPANSION)
                .call(new Prompt(List.of(new UserMessage("q")), ChatOptions.builder().temperature(0.3).build()));
        Prompt sent = sentTo(delegate2);
        assertThat(ThinkingControl.siteOf(sent)).isEqualTo(ThinkingSite.QUERY_EXPANSION);
        assertThat(sent.getOptions().getTemperature()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("이미 표시된 프롬프트는 그대로 — 호출부가 정한 사이트가 이긴다")
    void callerMarkWins() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenReturn(response("x"));
        Prompt prompt = new Prompt(List.of(new UserMessage("q")),
                ThinkingControl.mark(OpenAiChatOptions.builder(), ThinkingSite.CLASSIFY).build());

        new ThinkingSiteChatModel(delegate, ThinkingSite.QUERY_EXPANSION).call(prompt);

        assertThat(ThinkingControl.siteOf(sentTo(delegate))).isEqualTo(ThinkingSite.CLASSIFY);
    }

    @Test
    @DisplayName("실제 MultiQueryExpander 를 거쳐도 모델이 받는 프롬프트에 표시가 있다(이 앱의 체인처럼 일반 기본 옵션일 때)")
    void multiQueryExpanderPromptsAreMarked() {
        ChatModel delegate = mock(ChatModel.class);
        // 이 앱의 프로바이더 체인은 일반 ChatOptions 를 기본 옵션으로 돌려준다(LoggingChatModel) — 그 모양 그대로.
        when(delegate.getDefaultOptions()).thenReturn(ChatOptions.builder().build());
        when(delegate.call(any(Prompt.class))).thenReturn(response("변형 질문 1\n변형 질문 2"));
        MultiQueryExpander expander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(new ThinkingSiteChatModel(delegate, ThinkingSite.QUERY_EXPANSION)))
                .numberOfQueries(2)
                .build();

        List<Query> queries = expander.expand(new Query("SSE 타임아웃 설정은 어디서 바꾸나요?"));

        assertThat(queries).isNotEmpty();
        assertThat(ThinkingControl.siteOf(sentTo(delegate))).isEqualTo(ThinkingSite.QUERY_EXPANSION);
    }

    @Test
    @DisplayName("ChatClient 의 .options(표시된 옵션) 은 stream() 까지 표시를 나른다 — stream=false 프로바이더의 답변 경로")
    void chatClientCarriesTheMarkIntoStream() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.just(response("답")));

        ChatClient.builder(delegate).build().prompt()
                .options(ThinkingControl.mark(OpenAiChatOptions.builder().temperature(0.1), ThinkingSite.ANSWER_DIRECT_N)
                        .build())
                .system("시스템").user("질문")
                .stream().content().blockLast();

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(delegate).stream(captor.capture());
        assertThat(ThinkingControl.siteOf(captor.getValue())).isEqualTo(ThinkingSite.ANSWER_DIRECT_N);
    }
}
