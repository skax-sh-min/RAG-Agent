package com.example.ragagent.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global in-flight counter for genuine outbound embedding calls — both the query-time
 * cache-miss/"owner" path (via {@link CachingEmbeddingModel}) and index-time calls, which bypass
 * that cache entirely (see {@link CachingEmbeddingModel#unwrapForIndexing}). {@link
 * TrackingEmbeddingModel} is the layer common to both paths, so it increments/decrements this
 * around its one real delegate call.
 *
 * <p>Embedding never touches {@link LlmRouter}'s per-provider chat concurrency gate (a completely
 * separate {@code EmbeddingModel} decorator chain), so without this the header's LLM concurrency
 * indicator would sit at 0 during indexing/search embedding no matter how busy the embedding
 * endpoint actually is. {@code OperationsController} folds {@link #get()} into that indicator.
 */
@Component
public class EmbeddingConcurrencyTracker {

    private final AtomicInteger inFlight = new AtomicInteger(0);

    public void increment() {
        inFlight.incrementAndGet();
    }

    public void decrement() {
        inFlight.decrementAndGet();
    }

    /** Current number of embedding calls genuinely in flight (never negative). */
    public int get() {
        return Math.max(0, inFlight.get());
    }
}
