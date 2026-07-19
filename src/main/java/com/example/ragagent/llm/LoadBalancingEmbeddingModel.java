package com.example.ragagent.llm;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * §6.21 E1 — distributes embedding calls across multiple embedding endpoints (e.g. N GPU
 * replicas of the same model) by least-in-flight, mirroring {@code LlmRouter.selectWithinTopPriority()}
 * on the LLM side. Each delegate is a distinct {@code OpenAiEmbeddingModel} pointed at a different
 * base-url; all MUST serve the same model and dimension (embeddings from different models aren't
 * comparable and would corrupt the vector index). Wired below {@link TrackingEmbeddingModel} /
 * {@link CachingEmbeddingModel} in the decorator chain ({@code EmbeddingBeanConfig}) so usage
 * tracking and the query cache are unaffected — the balancer only decides which endpoint serves
 * a given delegate {@code call()}.
 *
 * <p>Balancing only helps when calls actually overlap: concurrent query embeds (multiple users)
 * and — during indexing — concurrent sub-batch embeds (§6.21 E2, {@code app.embedding.max-concurrent-batches})
 * or concurrent files ({@code app.indexing.max-concurrent-files}). A single serial caller keeps
 * {@code inFlight} at 0 everywhere, so the round-robin tie-break still spreads its calls evenly.
 *
 * <p>Only {@link #call(EmbeddingRequest)} is the balancing point; {@link #embed(Document)} and
 * {@link #dimensions()} mirror {@link TrackingEmbeddingModel}. {@code dimensions()} delegates to
 * the first endpoint since all endpoints share one model.
 */
public class LoadBalancingEmbeddingModel implements EmbeddingModel {

    private final List<EmbeddingModel> delegates;
    private final AtomicInteger[] inFlight;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public LoadBalancingEmbeddingModel(List<EmbeddingModel> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("LoadBalancingEmbeddingModel needs at least one delegate");
        }
        this.delegates = List.copyOf(delegates);
        this.inFlight = new AtomicInteger[this.delegates.size()];
        for (int i = 0; i < inFlight.length; i++) {
            inFlight[i] = new AtomicInteger();
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        int idx = pickLeastInFlight();
        inFlight[idx].incrementAndGet();
        try {
            return delegates.get(idx).call(request);
        } finally {
            inFlight[idx].decrementAndGet();
        }
    }

    /**
     * Least in-flight, with a rotating start so equal-load endpoints (notably all-idle, the serial
     * case) share evenly instead of always hitting endpoint 0. The min-load read and the pick race
     * benignly under concurrency — an approximate least-connections decision is all a balancer needs.
     */
    private int pickLeastInFlight() {
        int n = delegates.size();
        if (n == 1) return 0;
        int minLoad = Integer.MAX_VALUE;
        for (AtomicInteger c : inFlight) {
            int v = c.get();
            if (v < minLoad) minLoad = v;
        }
        int start = Math.floorMod(roundRobin.getAndIncrement(), n);
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            if (inFlight[i].get() <= minLoad) return i;
        }
        return start; // loads shifted concurrently — any endpoint is acceptable
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return delegates.get(0).dimensions();
    }
}
