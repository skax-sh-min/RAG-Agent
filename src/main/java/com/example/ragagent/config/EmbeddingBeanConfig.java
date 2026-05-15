package com.example.ragagent.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(cfg.baseUrl())
                .apiKey(cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : "no-key")
                .restClientBuilder(HttpClientTimeouts.restClientBuilder(
                        cfg.connectTimeoutSeconds(),
                        cfg.readTimeoutSeconds()))
                .build();
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(cfg.model() != null ? cfg.model() : "text-embedding-ada-002")
                        .build()
        );
    }
}
