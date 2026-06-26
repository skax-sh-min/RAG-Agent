package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SqliteVecSchemaInitializer} 단위 + 조건부 빈 테스트.
 * vec0 가상 테이블의 실제 생성은 네이티브 확장이 필요해 통합 테스트로 별도 검증한다.
 */
class SqliteVecSchemaInitializerTest {

    @Nested
    @DisplayName("resolveDimension — 차원 검증")
    class ResolveDimension {
        @Test
        @DisplayName("정상 차원은 그대로 반환")
        void valid() {
            assertThat(SqliteVecSchemaInitializer.resolveDimension(1536)).isEqualTo(1536);
        }

        @Test
        @DisplayName("null / 0 / 음수 → 명확한 오류로 기동 실패")
        void invalid() {
            for (Integer bad : new Integer[]{null, 0, -1}) {
                assertThatThrownBy(() -> SqliteVecSchemaInitializer.resolveDimension(bad))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.embedding.dimensions");
            }
        }
    }

    @Nested
    @DisplayName("embeddingTableDdl — vec0 DDL")
    class EmbeddingDdl {
        @Test
        @DisplayName("차원이 FLOAT[]에 박히고 vec0 + partition key + cosine + IF NOT EXISTS 포함")
        void ddlShape() {
            String ddl = SqliteVecSchemaInitializer.embeddingTableDdl(768);
            assertThat(ddl).contains("FLOAT[768]")
                    .contains("USING vec0(")
                    .contains("CREATE VIRTUAL TABLE IF NOT EXISTS vec_embeddings")
                    .contains("spring_doc_id TEXT PRIMARY KEY")
                    .contains("version TEXT partition key")          // KNN version 필터용
                    .contains("distance_metric=cosine");             // Chroma와 동일 유사도 의미
        }
    }

    @Nested
    @DisplayName("init — DDL 실행")
    class Init {
        private AppProperties propsWithDim(Integer dim) {
            AppProperties props = mock(AppProperties.class);
            when(props.embeddingSafe())
                    .thenReturn(new AppProperties.EmbeddingConfig(null, null, null, dim, 10, 120));
            return props;
        }

        @Test
        @DisplayName("정상 차원: vec0 + chunk 테이블 + 인덱스 2개 = 4 DDL 실행 (멱등 IF NOT EXISTS)")
        void runsAllDdl() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            new SqliteVecSchemaInitializer(jdbc, propsWithDim(1536)).init();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc, times(4)).execute(sql.capture());
            List<String> ddls = sql.getAllValues();

            assertThat(ddls.get(0)).contains("vec_embeddings").contains("FLOAT[1536]");
            assertThat(ddls.get(1)).contains("CREATE TABLE IF NOT EXISTS vec_document_chunks");
            assertThat(ddls.get(2)).contains("idx_vec_chunks_version");
            assertThat(ddls.get(3)).contains("idx_vec_chunks_docid");
            assertThat(ddls).allMatch(s -> s.contains("IF NOT EXISTS"));
        }

        @Test
        @DisplayName("차원 미설정: DDL 한 줄도 실행하지 않고 기동 실패")
        void failsFastWithoutDimension() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            assertThatThrownBy(() -> new SqliteVecSchemaInitializer(jdbc, propsWithDim(null)).init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.embedding.dimensions");
            verify(jdbc, never()).execute(anyString());
        }
    }

    @Nested
    @DisplayName("조건부 빈 등록 (@ConditionalOnProperty)")
    class Conditional {
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(AppProperties.class, () -> mock(AppProperties.class))
                .withUserConfiguration(SqliteVecSchemaInitializer.class);

        @Test
        @DisplayName("기본/chroma: 빈 미생성")
        void absentByDefault() {
            runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SqliteVecSchemaInitializer.class));
            runner.withPropertyValues("app.vectorstore.type=chroma")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(SqliteVecSchemaInitializer.class));
        }

        @Test
        @DisplayName("sqlite-vec: 빈 생성")
        void presentForSqliteVec() {
            runner.withPropertyValues("app.vectorstore.type=sqlite-vec")
                    .run(ctx -> assertThat(ctx).hasSingleBean(SqliteVecSchemaInitializer.class));
        }
    }
}
