package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.ChromaConfig;
import com.example.ragagent.service.VectorStoreRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code app.vectorstore.type}에 따라 {@link VectorStoreProvider}가 정확히 하나 선택되는지,
 * sqlite-vec 모드에서 Chroma 전용 {@link org.springframework.ai.chroma.vectorstore.ChromaApi}
 * 빈이 생성되지 않는지 검증한다.
 */
class VectorStoreProviderConfigTest {

    private static AppProperties providerProps() {
        AppProperties p = mock(AppProperties.class);
        when(p.vectorStoreSafe()).thenReturn(new AppProperties.VectorStoreConfig("chroma")); // 로그용
        when(p.searchSimilarityThresholdSafe()).thenReturn(0.0);
        return p;
    }

    @Nested
    @DisplayName("provider 택일 — 모드별 정확히 1개")
    class ProviderSelection {
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean("vectorJdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(VectorStoreRegistry.class, () -> mock(VectorStoreRegistry.class))
                .withBean(ChromaApi.class, () -> mock(ChromaApi.class))
                .withBean(AppProperties.class, VectorStoreProviderConfigTest::providerProps)
                .withUserConfiguration(VectorStoreProviderConfig.class);

        @Test
        @DisplayName("기본(미설정): chroma provider 1개")
        void defaultsToChroma() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(VectorStoreProvider.class);
                assertThat(ctx.getBean(VectorStoreProvider.class)).isInstanceOf(ChromaVectorStoreProvider.class);
            });
        }

        @Test
        @DisplayName("type=chroma: chroma provider 1개")
        void explicitChroma() {
            runner.withPropertyValues("app.vectorstore.type=chroma").run(ctx -> {
                assertThat(ctx).hasSingleBean(VectorStoreProvider.class);
                assertThat(ctx.getBean(VectorStoreProvider.class)).isInstanceOf(ChromaVectorStoreProvider.class);
            });
        }

        @Test
        @DisplayName("type=sqlite-vec: sqlite provider 1개")
        void sqliteVec() {
            runner.withPropertyValues("app.vectorstore.type=sqlite-vec").run(ctx -> {
                assertThat(ctx).hasSingleBean(VectorStoreProvider.class);
                assertThat(ctx.getBean(VectorStoreProvider.class)).isInstanceOf(SqliteVecVectorStoreProvider.class);
            });
        }
    }

    @Nested
    @DisplayName("Chroma 전용 빈 가드 — ChromaApi")
    class ChromaBeanGuard {
        private static AppProperties chromaProps() {
            AppProperties p = mock(AppProperties.class);
            when(p.chromaSafe()).thenReturn(new AppProperties.ChromaHttpConfig(5, 60));
            return p;
        }

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AppProperties.class, ChromaBeanGuard::chromaProps)
                .withUserConfiguration(ChromaConfig.class);

        @Test
        @DisplayName("기본: ChromaApi 빈 생성")
        void chromaApiPresentByDefault() {
            runner.run(ctx -> assertThat(ctx).hasSingleBean(ChromaApi.class));
        }

        @Test
        @DisplayName("type=sqlite-vec: ChromaApi 빈 미생성 (ChromaDB 없이 기동)")
        void chromaApiAbsentForSqliteVec() {
            runner.withPropertyValues("app.vectorstore.type=sqlite-vec")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(ChromaApi.class));
        }
    }
}
