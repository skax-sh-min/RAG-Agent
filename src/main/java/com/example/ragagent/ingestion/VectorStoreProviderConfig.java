package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.VectorStoreRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code app.vectorstore.type}에 따라 {@link VectorStoreProvider} 구현체를 정확히 하나 선택한다.
 *
 * <p>{@code chroma} (기본값, {@code matchIfMissing=true}) → {@link ChromaVectorStoreProvider}
 * ({@link VectorStoreRegistry}, {@link ChromaApi} 사용); {@code sqlite-vec} →
 * {@link SqliteVecVectorStoreProvider} (공유 {@link JdbcTemplate} 사용).
 * 선택되지 않은 백엔드의 빈은 생성되지 않으므로 sqlite-vec 모드는 ChromaDB 없이 기동된다.
 */
@Configuration
public class VectorStoreProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreProviderConfig.class);

    public VectorStoreProviderConfig(AppProperties props) {
        log.info("[VECTORSTORE] active backend: {}", props.vectorStoreSafe().type());
    }

    @Bean
    @ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "sqlite-vec")
    VectorStoreProvider sqliteVecVectorStoreProvider(JdbcTemplate jdbc, EmbeddingModel embeddingModel,
                                                     ObjectMapper objectMapper, AppProperties props) {
        return new SqliteVecVectorStoreProvider(jdbc, embeddingModel, objectMapper, props);
    }

    @Bean
    @ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "chroma", matchIfMissing = true)
    VectorStoreProvider chromaVectorStoreProvider(VectorStoreRegistry registry, ChromaApi chromaApi,
                                                  EmbeddingModel embeddingModel, ObjectMapper objectMapper,
                                                  AppProperties props) {
        return new ChromaVectorStoreProvider(registry, chromaApi, embeddingModel, objectMapper, props);
    }
}
