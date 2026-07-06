package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — TrackingChatModel (§6.14 잔여 — MultiQueryExpander 등 framework-internal 호출용 ChatModel 데코레이터).
 *
 * call()만 기록 지점으로 오버라이드하고 stream()/getDefaultOptions()는 delegate로 그대로 위임한다
 * (TrackingEmbeddingModel과 동일한 패턴).
 */
class TrackingChatModelTest {

    private ChatModel delegate;
    private LlmUsageRepository usageRepo;
    private TrackingChatModel tracked;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatModel.class);
        usageRepo = mock(LlmUsageRepository.class);
        tracked = new TrackingChatModel(delegate, "local", usageRepo);
    }

    private static ChatResponse chatResponse(String text, Integer inputTokens, Integer outputTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(inputTokens, outputTokens))
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    @Test
    @DisplayName("call — delegate 결과를 그대로 반환하고 실제 usage를 provider 이름으로 기록")
    void call_delegatesAndRecordsRealUsage() {
        Prompt prompt = new Prompt(new UserMessage("질문"));
        when(delegate.call(prompt)).thenReturn(chatResponse("답변", 120, 45));

        ChatResponse result = tracked.call(prompt);

        assertThat(result.getResult().getOutput().getText()).isEqualTo("답변");
        verify(usageRepo).record("local", 120, 45);
    }

    @Test
    @DisplayName("call — usage 메타데이터가 없으면 (0,0)으로 기록 (예외 없이)")
    void call_missingUsage_recordsZero() {
        Prompt prompt = new Prompt(new UserMessage("질문"));
        ChatResponse noUsageResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("답변"))));
        when(delegate.call(prompt)).thenReturn(noUsageResponse);

        tracked.call(prompt);

        verify(usageRepo).record("local", 0, 0);
    }

    @Test
    @DisplayName("stream — delegate로 그대로 위임(추적 안 함)")
    void stream_delegatesWithoutTracking() {
        Prompt prompt = new Prompt(new UserMessage("질문"));
        Flux<ChatResponse> flux = Flux.just(chatResponse("토큰", null, null));
        when(delegate.stream(prompt)).thenReturn(flux);

        Flux<ChatResponse> result = tracked.stream(prompt);

        assertThat(result).isSameAs(flux);
        verify(usageRepo, org.mockito.Mockito.never()).record(any(), any(Long.class), any(Long.class));
    }

    @Test
    @DisplayName("getDefaultOptions — delegate로 그대로 위임")
    void getDefaultOptions_delegates() {
        ChatOptions options = ChatOptions.builder().model("test-model").build();
        when(delegate.getDefaultOptions()).thenReturn(options);

        assertThat(tracked.getDefaultOptions()).isSameAs(options);
    }
}
