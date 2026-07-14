package com.example.ragagent.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorates the primary {@link EmbeddingModel} bean with a Caffeine cache keyed on
 * normalized text + model name, so repeated/near-identical search queries skip the embedding
 * round-trip entirely. Sits outside {@link TrackingEmbeddingModel} in the decorator chain
 * (cache → tracking → delegate, see {@code EmbeddingBeanConfig}) so a cache hit is never
 * recorded as usage — no provider call happened, so no tokens were spent.
 *
 * <p>The cache is a plain in-memory instance field tied to one bean instantiation (one boot),
 * so a model change across restarts already invalidates it naturally — no need to fold
 * {@link EmbeddingModel#dimensions()} into the key, which matters because that call hits the
 * live embedding endpoint on first invocation ({@code OpenAiEmbeddingModel} sends a probe
 * request to learn it); doing that eagerly in this constructor would add a new startup-time
 * dependency on the embedding server being reachable.
 *
 * <p><b>In-flight single-flight</b>: a text that's already being fetched by another
 * concurrent {@link #call(EmbeddingRequest)} (e.g. two users asking the same question at
 * nearly the same moment) is <em>not</em> re-sent to the delegate — the second caller joins
 * the first caller's in-flight {@link CompletableFuture} and reuses its result. Only exactly
 * identical (post-normalization) text collapses this way; near-duplicate questions still miss
 * (that's §10.5 semantic-cache territory, currently deferred). Texts that are simultaneously
 * new within the *same* request are still batched into one delegate call, same as before —
 * single-flight only affects cross-call races on the same key.
 *
 * <p>Only {@link #call(EmbeddingRequest)} is overridden; every other {@link EmbeddingModel}
 * method is a default that funnels into {@code this.call(...)}, mirroring
 * {@link TrackingEmbeddingModel}'s approach. Indexing calls (unique chunk text, rarely
 * repeated) pass through the same cache and mostly miss — bounded by maximumSize/TTL, so
 * they don't leak memory, they just don't benefit from caching.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final Cache<String, float[]> cache;
    private final ConcurrentHashMap<String, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    private final String cacheKeyPrefix;

    public CachingEmbeddingModel(EmbeddingModel delegate, String modelName, int maximumSize, int ttlSeconds) {
        this.delegate = delegate;
        this.cacheKeyPrefix = modelName + ":";
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        int n = texts.size();
        float[][] outputs = new float[n][];

        // Texts this call must actually fetch (not cached, and no other in-flight call already
        // owns them) — batched into a single delegate call, same as the pre-single-flight behavior.
        List<Integer> ownedIndexes = new ArrayList<>();
        List<String> ownedTexts = new ArrayList<>();
        List<String> ownedKeys = new ArrayList<>();
        List<CompletableFuture<float[]>> ownedFutures = new ArrayList<>();

        // Texts owned by another concurrent call — this call just joins their future.
        List<Integer> joinedIndexes = new ArrayList<>();
        List<CompletableFuture<float[]>> joinedFutures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String key = cacheKey(texts.get(i));
            float[] cached = cache.getIfPresent(key);
            if (cached != null) {
                outputs[i] = cached;
                continue;
            }
            CompletableFuture<float[]> ownFuture = new CompletableFuture<>();
            CompletableFuture<float[]> existing = inFlight.putIfAbsent(key, ownFuture);
            if (existing != null) {
                joinedIndexes.add(i);
                joinedFutures.add(existing);
            } else {
                ownedIndexes.add(i);
                ownedTexts.add(texts.get(i));
                ownedKeys.add(key);
                ownedFutures.add(ownFuture);
            }
        }

        if (!ownedTexts.isEmpty()) {
            try {
                EmbeddingResponse response = delegate.call(new EmbeddingRequest(ownedTexts, request.getOptions()));
                List<Embedding> results = response.getResults();
                for (int j = 0; j < ownedTexts.size(); j++) {
                    float[] output = results.get(j).getOutput();
                    outputs[ownedIndexes.get(j)] = output;
                    cache.put(ownedKeys.get(j), output);
                    ownedFutures.get(j).complete(output);
                }
            } catch (RuntimeException e) {
                for (CompletableFuture<float[]> f : ownedFutures) f.completeExceptionally(e);
                throw e;
            } finally {
                for (int j = 0; j < ownedKeys.size(); j++) {
                    inFlight.remove(ownedKeys.get(j), ownedFutures.get(j));
                }
            }
        }

        for (int j = 0; j < joinedIndexes.size(); j++) {
            outputs[joinedIndexes.get(j)] = join(joinedFutures.get(j));
        }

        List<Embedding> merged = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            merged.add(new Embedding(outputs[i], i));
        }
        return new EmbeddingResponse(merged);
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private static float[] join(CompletableFuture<float[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    private String cacheKey(String text) {
        return cacheKeyPrefix + (text == null ? "" : text.strip());
    }
}
