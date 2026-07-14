package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * §6.12 — applies {@link LlmRouter}'s per-provider concurrency gate to framework-internal
 * callers that build their own {@code ChatClient} around an injected model (e.g. Spring AI's
 * {@code MultiQueryExpander} in {@code RetrievalService}) and therefore never go through
 * {@link LlmRouter#executeGated} — mirrors {@link TrackingChatModel}'s decorator pattern.
 *
 * <p>Only {@link #call(Prompt)} is gated; this decorator is only ever applied to callers known
 * to use the blocking API.
 */
public class ConcurrencyLimitingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LlmProvider provider;
    private final LlmRouter llmRouter;

    public ConcurrencyLimitingChatModel(ChatModel delegate, LlmProvider provider, LlmRouter llmRouter) {
        this.delegate = delegate;
        this.provider = provider;
        this.llmRouter = llmRouter;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try (LlmRouter.Permit permit = llmRouter.acquirePermit(provider)) {
            return delegate.call(prompt);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
