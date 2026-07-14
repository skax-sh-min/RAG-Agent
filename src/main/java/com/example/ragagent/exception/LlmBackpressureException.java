package com.example.ragagent.exception;

/**
 * Thrown when a provider's per-server concurrency gate (§6.12 — {@code LlmRouter.acquirePermit()})
 * stays saturated past {@code app.llm.permit-wait-timeout-seconds}. Distinct from
 * {@link LlmProviderExhaustedException}: the provider is healthy, just momentarily at capacity,
 * so callers must not treat this as a provider failure (no circuit-breaker block, no retry to
 * another provider) — it maps to HTTP 429 with a Retry-After hint instead.
 */
public final class LlmBackpressureException extends RagException {

    private final int retryAfterSeconds;

    public LlmBackpressureException(String message, int retryAfterSeconds) {
        super("RAG-LLM-002", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override public int httpStatus() { return 429; }

    @Override public int retryAfterSeconds() { return retryAfterSeconds; }
}
