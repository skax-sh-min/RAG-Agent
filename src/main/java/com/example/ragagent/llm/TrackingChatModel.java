package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Decorates a {@link ChatModel} so calls made by framework-internal callers that build their
 * own {@code ChatClient} around an injected model (e.g. Spring AI's {@code MultiQueryExpander})
 * are still recorded into {@link LlmUsageRepository} under {@code providerName} — mirrors
 * {@link TrackingEmbeddingModel}'s decorator pattern for embeddings.
 *
 * <p>Only {@link #call(Prompt)} is overridden as the tracking point; {@link #stream(Prompt)}
 * delegates untouched (no {@link ChatResponse} usage metadata available from a token stream).
 */
public class TrackingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final String providerName;
    private final LlmUsageRepository usageRepo;

    public TrackingChatModel(ChatModel delegate, String providerName, LlmUsageRepository usageRepo) {
        this.delegate = delegate;
        this.providerName = providerName;
        this.usageRepo = usageRepo;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        var usage = response.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        usageRepo.record(providerName, in, out);
        return response;
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
