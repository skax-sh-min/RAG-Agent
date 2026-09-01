package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ConcurrencyLimitingChatModel (gates framework-internal callers, e.g.
 * MultiQueryExpander's injected ChatModel in RetrievalService, that bypass LlmRouter.executeGated).
 */
class ConcurrencyLimitingChatModelTest {

    private ChatModel delegate;
    private LlmProvider provider;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        delegate = mock(ChatModel.class);
        provider = new LlmProvider("lm", TaskType.TEXT, ProviderRole.LOCAL, 1, "k", null, null, true,
                delegate, null);
        breaker = new CircuitBreaker(2);
    }

    private LlmRouter router(int concurrency) {
        return new LlmRouter(List.of(provider), mock(LlmUsageRepository.class), breaker,
                RoutingMode.COST_FIRST, 180, Map.of("lm", concurrency), concurrency, 1);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("call — 슬롯이 있으면 delegate 결과를 그대로 반환")
    void call_delegatesWhenSlotAvailable() {
        LlmRouter llmRouter = router(1);
        var gated = new ConcurrencyLimitingChatModel(delegate, provider, llmRouter);
        Prompt prompt = new Prompt(new UserMessage("질문"));
        when(delegate.call(prompt)).thenReturn(chatResponse("답변"));

        ChatResponse result = gated.call(prompt);

        assertThat(result.getResult().getOutput().getText()).isEqualTo("답변");
    }

    @Test
    @DisplayName("call — 게이트가 포화 상태면 delegate를 호출하지 않고 LlmBackpressureException")
    void call_backpressureWhenSaturated() {
        LlmRouter llmRouter = router(1);
        var gated = new ConcurrencyLimitingChatModel(delegate, provider, llmRouter);
        LlmRouter.Permit held = llmRouter.acquirePermit(provider); // occupy the only slot

        assertThatThrownBy(() -> gated.call(new Prompt("x")))
                .isInstanceOf(LlmBackpressureException.class);
        verify(delegate, never()).call(any(Prompt.class));

        held.close();
    }

    @Test
    @DisplayName("stream — 게이트 적용 없이 delegate로 그대로 위임")
    void stream_delegatesWithoutGating() {
        LlmRouter llmRouter = router(1);
        var gated = new ConcurrencyLimitingChatModel(delegate, provider, llmRouter);
        Prompt prompt = new Prompt(new UserMessage("질문"));
        Flux<ChatResponse> flux = Flux.just(chatResponse("토큰"));
        when(delegate.stream(prompt)).thenReturn(flux);

        assertThat(gated.stream(prompt)).isSameAs(flux);
    }

    @Test
    @DisplayName("getDefaultOptions — delegate로 그대로 위임")
    void getDefaultOptions_delegates() {
        LlmRouter llmRouter = router(1);
        var gated = new ConcurrencyLimitingChatModel(delegate, provider, llmRouter);
        ChatOptions options = ChatOptions.builder().model("test-model").build();
        when(delegate.getDefaultOptions()).thenReturn(options);

        assertThat(gated.getDefaultOptions()).isSameAs(options);
    }
}
