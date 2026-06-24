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
 * Phase 5 Step 5.5 — selects exactly one {@link VectorStoreProvider} from {@code app.vectorstore.type}.
 *
 * <p>{@code chroma} (default, {@code matchIfMissing}) wires {@link ChromaVectorStoreProvider} from the
 * Chroma-only beans ({@link VectorStoreRegistry}, {@link ChromaApi}); {@code sqlite-vec} wires
 * {@link SqliteVecVectorStoreProvider} from the shared {@link JdbcTemplate}. The unused backend's beans
 * are not created (they carry the matching {@code @ConditionalOnProperty}), so sqlite-vec mode starts
 * without ChromaDB.
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
