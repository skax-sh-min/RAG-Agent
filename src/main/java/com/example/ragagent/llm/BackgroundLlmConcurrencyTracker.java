package com.example.ragagent.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global in-flight counter for indexing/background chat LLM calls — keyword+context extraction,
 * Markdown correction, TXT structuring, indexing-time Vision descriptions, conversation
 * summarization, thread title generation (anything routed through {@link
 * LlmRouter#executeWithTracking}). {@code executeSingleTracked} increments/decrements this around
 * its one real delegate call, but only for the ungated path — a gated ({@code executeGated}) call
 * already registers via the per-provider {@code Semaphore} that {@link
 * LlmRouter#localTier1Concurrency()} reads, and counting it here too would double it.
 *
 * <p>These calls deliberately never touch that concurrency gate (§6.12 — indexing already throttles
 * itself via {@code app.indexing.max-concurrent-llm-calls} and has no synchronous HTTP caller
 * waiting on a deadline), so without this the header's LLM concurrency indicator sits at 0 during
 * indexing no matter how busy the LOCAL model actually is — the same gap {@link
 * EmbeddingConcurrencyTracker} closes for embedding calls. {@code OperationsController} folds
 * {@link #get()} into that indicator the same way.
 */
@Component
public class BackgroundLlmConcurrencyTracker {

    private final AtomicInteger inFlight = new AtomicInteger(0);

    public void increment() {
        inFlight.incrementAndGet();
    }

    public void decrement() {
        inFlight.decrementAndGet();
    }

    /** Current number of background LLM calls genuinely in flight (never negative). */
    public int get() {
        return Math.max(0, inFlight.get());
    }
}
