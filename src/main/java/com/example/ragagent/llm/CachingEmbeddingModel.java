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
 * <p>Only {@link #call(EmbeddingRequest)} is overridden; every other {@link EmbeddingModel}
 * method is a default that funnels into {@code this.call(...)}, mirroring
 * {@link TrackingEmbeddingModel}'s approach. Indexing calls (unique chunk text, rarely
 * repeated) pass through the same cache and mostly miss — bounded by maximumSize/TTL, so
 * they don't leak memory, they just don't benefit from caching.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final Cache<String, float[]> cache;
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
        float[][] outputs = new float[texts.size()][];
        List<Integer> missIndexes = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] cached = cache.getIfPresent(cacheKey(texts.get(i)));
            if (cached != null) {
                outputs[i] = cached;
            } else {
                missIndexes.add(i);
                missTexts.add(texts.get(i));
            }
        }

        if (!missTexts.isEmpty()) {
            EmbeddingResponse response = delegate.call(new EmbeddingRequest(missTexts, request.getOptions()));
            List<Embedding> results = response.getResults();
            for (int j = 0; j < missIndexes.size(); j++) {
                float[] output = results.get(j).getOutput();
                outputs[missIndexes.get(j)] = output;
                cache.put(cacheKey(missTexts.get(j)), output);
            }
        }

        List<Embedding> merged = new ArrayList<>(outputs.length);
        for (int i = 0; i < outputs.length; i++) {
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

    private String cacheKey(String text) {
        return cacheKeyPrefix + (text == null ? "" : text.strip());
    }
}
