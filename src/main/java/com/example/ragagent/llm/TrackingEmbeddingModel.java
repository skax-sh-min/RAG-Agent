package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decorates the primary {@link EmbeddingModel} bean so every embedding call (search +
 * indexing, both vector store backends) is recorded into {@link LlmUsageRepository} under
 * a reserved {@code "embed:"} prefix — kept separate from chat provider rows without any
 * schema change.
 *
 * <p>Only {@link #call(EmbeddingRequest)} is overridden as the tracking point; {@code embed(String)},
 * {@code embed(List)} and {@code embedForResponse(List)} are inherited default methods on
 * {@link EmbeddingModel} that all funnel into {@code this.call(...)}, so they're tracked for free.
 * {@link #embed(Document)} is abstract on the interface, so it's implemented here to also
 * route through {@code this.call(...)} rather than delegating directly (which would bypass
 * tracking). {@link #dimensions()} delegates straight through since it's not a usage-generating
 * call and the delegate may cache/short-circuit it.
 *
 * <p>Also wraps the delegate call with an {@link EmbeddingConcurrencyTracker} increment/decrement
 * (see that class) so embedding activity — invisible to {@link LlmRouter}'s chat-only concurrency
 * gate — still registers on the header's LLM concurrency indicator.
 */
public class TrackingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(TrackingEmbeddingModel.class);

    /** Reserved provider-name prefix so embedding rows never collide with chat provider names. */
    public static final String PROVIDER_PREFIX = "embed:";

    private final EmbeddingModel delegate;
    private final LlmUsageRepository usageRepo;
    private final String providerName;
    private final boolean approximateFallback;
    private final EmbeddingConcurrencyTracker concurrencyTracker;
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    public TrackingEmbeddingModel(EmbeddingModel delegate, LlmUsageRepository usageRepo,
                                  String modelName, boolean approximateFallback) {
        this(delegate, usageRepo, modelName, approximateFallback, new EmbeddingConcurrencyTracker());
    }

    /**
     * @param concurrencyTracker shared in-flight counter (see {@link EmbeddingConcurrencyTracker})
     *                           folded into the header's LLM concurrency indicator — increments
     *                           around {@link #call} below, the one layer common to both the
     *                           query-time cached path and the uncached index-time path.
     */
    public TrackingEmbeddingModel(EmbeddingModel delegate, LlmUsageRepository usageRepo,
                                  String modelName, boolean approximateFallback,
                                  EmbeddingConcurrencyTracker concurrencyTracker) {
        this.delegate = delegate;
        this.usageRepo = usageRepo;
        this.providerName = PROVIDER_PREFIX + modelName;
        this.approximateFallback = approximateFallback;
        this.concurrencyTracker = concurrencyTracker;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        EmbeddingResponse response;
        concurrencyTracker.increment();
        try {
            response = delegate.call(request);
        } finally {
            concurrencyTracker.decrement();
        }
        try {
            usageRepo.record(providerName, extractInputTokens(response, request), 0);
        } catch (Exception e) {
            // delegate.call() above already succeeded — a usage-table write failure (e.g.
            // SQLITE_FULL) must never fail the actual embedding call (would break indexing/search).
            log.warn("[USAGE] Failed to record usage for provider={}: {}", providerName, e.getMessage());
        }
        return response;
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private long extractInputTokens(EmbeddingResponse response, EmbeddingRequest request) {
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        Integer promptTokens = usage != null ? usage.getPromptTokens() : null;
        if (promptTokens != null && promptTokens > 0) {
            return promptTokens;
        }
        if (!approximateFallback) {
            return 0;
        }
        if (fallbackWarned.compareAndSet(false, true)) {
            log.warn("[embedding usage] provider={} did not report token usage; approximating "
                    + "input tokens with TokenEstimator (CJK ~1/char, else chars/4) for llm_usage "
                    + "tracking (this warning logs once)", providerName);
        }
        return approximateTokens(request.getInstructions());
    }

    /**
     * {@link TokenEstimator} 에 위임한다 — 채팅 스트리밍 폴백과 <b>같은</b> 가정을 쓴다.
     * 예전에는 여기와 {@code LlmRouter} 가 각자 {@code chars/4} 를 복제하고 있었고, 그 값은 영어
     * 기준이라 한국어 문서를 임베딩할 때 입력 토큰이 실제보다 훨씬 적게 기록됐다.
     */
    private static long approximateTokens(List<String> texts) {
        return TokenEstimator.estimate(texts);
    }
}
