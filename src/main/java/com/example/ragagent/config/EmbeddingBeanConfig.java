package com.example.ragagent.config;

import com.example.ragagent.llm.CachingEmbeddingModel;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LoadBalancingEmbeddingModel;
import com.example.ragagent.llm.LoggingEmbeddingModel;
import com.example.ragagent.llm.TrackingEmbeddingModel;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EmbeddingBeanConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBeanConfig.class);

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AppProperties props, LlmUsageRepository usageRepo,
                                         EmbeddingConcurrencyTracker concurrencyTracker) {
        AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
        if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "app.embedding.base-url (EMBED_BASE_URL) is required but not configured");
        }
        String model = cfg.model() != null ? cfg.model() : "text-embedding-ada-002";

        // §6.21 E1 — primary base-url + any additional endpoints (must serve the SAME model/dimension,
        // e.g. N GPU replicas). One raw OpenAiEmbeddingModel per URL; when >1, balance least-in-flight.
        List<String> urls = new ArrayList<>();
        urls.add(cfg.baseUrl());
        if (cfg.additionalBaseUrls() != null) {
            for (String u : cfg.additionalBaseUrls()) {
                if (u != null && !u.isBlank()) urls.add(u.trim());
            }
        }

        EmbeddingModel base;
        if (urls.size() == 1) {
            base = buildRawModel(urls.get(0), cfg, model);
        } else {
            List<EmbeddingModel> delegates = urls.stream()
                    .map(u -> buildRawModel(u, cfg, model))
                    .toList();
            base = new LoadBalancingEmbeddingModel(delegates);
            log.info("Embedding load balancing (§6.21 E1) across {} endpoints: {}", urls.size(), urls);
        }

        EmbeddingModel tracked = new TrackingEmbeddingModel(base, usageRepo, model,
                cfg.usageFallbackEnabled(), concurrencyTracker);
        if (!props.searchQueryEmbedCacheEnabledSafe()) {
            return tracked;
        }
        // Cache sits outside tracking so a cache hit records no usage — no provider call happened.
        return new CachingEmbeddingModel(tracked, model,
                props.searchQueryEmbedCacheMaxSizeSafe(), props.searchQueryEmbedCacheTtlSecondsSafe());
    }

    /** Builds one raw {@link OpenAiEmbeddingModel} pointed at {@code rawUrl}, sharing {@code cfg}'s
     *  key/model/timeouts — wrapped in {@link LoggingEmbeddingModel} so DEBUG logs show a curl
     *  reproduction of every embedding call, mirroring {@code LlmConfig}'s {@link
     *  com.example.ragagent.llm.LoggingChatModel} for chat calls. */
    private EmbeddingModel buildRawModel(String rawUrl, AppProperties.EmbeddingConfig cfg, String model) {
        // OpenAiApi.builder() appends /v1 internally — strip it to avoid /v1/v1/embeddings.
        String apiBase = rawUrl.endsWith("/v1/") ? rawUrl.substring(0, rawUrl.length() - 4)
                       : rawUrl.endsWith("/v1")  ? rawUrl.substring(0, rawUrl.length() - 3)
                       : rawUrl;
        String effectiveApiKey = cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : "no-key";
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(apiBase)
                .apiKey(effectiveApiKey)
                .restClientBuilder(HttpClientTimeouts.restClientBuilder(
                        cfg.connectTimeoutSeconds(),
                        cfg.readTimeoutSeconds()))
                .build();
        // Limit retries to 2: DEFAULT_RETRY_TEMPLATE retries up to 10 times with exponential
        // backoff, causing the UI to show "벡터 DB 저장 중..." for several minutes on
        // connection failures (e.g. LM Studio not running).
        RetryTemplate shortRetry = RetryTemplate.builder()
                .maxAttempts(2)
                .exponentialBackoff(500, 2.0, 5_000)
                .build();
        OpenAiEmbeddingModel raw = new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .build(),
                shortRetry
        );
        return new LoggingEmbeddingModel(raw, TrackingEmbeddingModel.PROVIDER_PREFIX + model,
                rawUrl, effectiveApiKey, model);
    }
}
