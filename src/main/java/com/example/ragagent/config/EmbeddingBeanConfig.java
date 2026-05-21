package com.example.ragagent.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class EmbeddingBeanConfig {

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AppProperties props) {
        AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
        if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "app.embedding.base-url (EMBED_BASE_URL) is required but not configured");
        }
        // OpenAiApi.builder() appends /v1 internally — strip it to avoid /v1/v1/embeddings.
        String rawUrl = cfg.baseUrl();
        String apiBase = rawUrl.endsWith("/v1/") ? rawUrl.substring(0, rawUrl.length() - 4)
                       : rawUrl.endsWith("/v1")  ? rawUrl.substring(0, rawUrl.length() - 3)
                       : rawUrl;
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(apiBase)
                .apiKey(cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : "no-key")
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
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(cfg.model() != null ? cfg.model() : "text-embedding-ada-002")
                        .build(),
                shortRetry
        );
    }
}
