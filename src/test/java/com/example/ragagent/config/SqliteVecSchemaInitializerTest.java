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
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("기존 vec_embeddings 차원 검사 — 임베딩 모델 교체 뒤 테이블을 비우지 않은 기동")
    class ExistingDimension {
        @Test
        @DisplayName("embeddingTableDdl 이 만든 문장에서 차원을 읽는다 (sqlite_master 에 남는 모양, 대소문자·공백 무관)")
        void parses() {
            assertThat(SqliteVecSchemaInitializer.parseExistingDimension(
                    SqliteVecSchemaInitializer.embeddingTableDdl(768))).isEqualTo(768);
            assertThat(SqliteVecSchemaInitializer.parseExistingDimension(
                    "CREATE VIRTUAL TABLE vec_embeddings USING vec0(embedding  float [ 1024 ] distance_metric=cosine)"))
                    .isEqualTo(1024);
        }

        @Test
        @DisplayName("문장이 없거나 모양을 모르면 null — 판정하지 않는다")
        void unknownShape() {
            assertThat(SqliteVecSchemaInitializer.parseExistingDimension(null)).isNull();
            assertThat(SqliteVecSchemaInitializer.parseExistingDimension("")).isNull();
            assertThat(SqliteVecSchemaInitializer.parseExistingDimension("CREATE TABLE t(a INT)")).isNull();
        }

        @Test
        @DisplayName("같은 차원 · 테이블 없음 · 읽을 수 없음 → 메시지 없음(null)")
        void noMessage() {
            String ddl = SqliteVecSchemaInitializer.embeddingTableDdl(768);
            assertThat(SqliteVecSchemaInitializer.dimensionMismatchMessage(ddl, 768)).isNull();
            assertThat(SqliteVecSchemaInitializer.dimensionMismatchMessage(null, 768)).isNull();
            assertThat(SqliteVecSchemaInitializer.dimensionMismatchMessage("CREATE TABLE t(a INT)", 768)).isNull();
        }

        @Test
        @DisplayName("다른 차원 → 두 숫자와 해야 할 일(재생성 카드 · §7.3 · 설정만 틀렸을 때)을 담은 문장 — 던지지 않는다")
        void mismatchMessage() {
            String ddl = SqliteVecSchemaInitializer.embeddingTableDdl(768);
            assertThat(SqliteVecSchemaInitializer.dimensionMismatchMessage(ddl, 1024))
                    .contains("768차원")
                    .contains("1024")
                    .contains("임베딩 재생성")
                    .contains("§7.3")
                    .contains("EMBED_DIMENSIONS");
        }
    }

    @Nested
    @DisplayName("init — DDL 실행")
    class Init {
        private AppProperties propsWithDim(Integer dim) {
            AppProperties props = mock(AppProperties.class);
            when(props.embeddingSafe())
                    .thenReturn(new AppProperties.EmbeddingConfig(null, null, null, dim, 10, 120, true, 0, List.of(), 1));
            return props;
        }

        @Test
        @DisplayName("정상 차원: WAL/busy_timeout PRAGMA 2개 + vec0 + chunk 테이블 + 인덱스 2개 = 6 실행")
        void runsAllDdl() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            new SqliteVecSchemaInitializer(jdbc, propsWithDim(1536)).init();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc, times(6)).execute(sql.capture());
            List<String> stmts = sql.getAllValues();

            // pragmas run first (vector DB connection parity), then the DDL block
            assertThat(stmts.get(0)).isEqualTo("PRAGMA journal_mode=WAL");
            assertThat(stmts.get(1)).isEqualTo("PRAGMA busy_timeout=5000");
            assertThat(stmts.get(2)).contains("vec_embeddings").contains("FLOAT[1536]");
            assertThat(stmts.get(3)).contains("CREATE TABLE IF NOT EXISTS vec_document_chunks");
            assertThat(stmts.get(4)).contains("idx_vec_chunks_version");
            assertThat(stmts.get(5)).contains("idx_vec_chunks_docid");
            assertThat(stmts.subList(2, 6)).allMatch(s -> s.contains("IF NOT EXISTS"));
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

        @Test
        @DisplayName("기존 vec_embeddings 가 다른 차원: 기동을 막지 않는다 — 재생성 카드가 고칠 수 있도록 DDL 은 평소대로 6개 실행")
        void mismatchDoesNotBlockStartup() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of(SqliteVecSchemaInitializer.embeddingTableDdl(768)));
            new SqliteVecSchemaInitializer(jdbc, propsWithDim(1024)).init();   // 예외 없음
            verify(jdbc, times(6)).execute(anyString());
        }

        @Test
        @DisplayName("기존 vec_embeddings 가 같은 차원: 평소처럼 DDL 6개 실행")
        void sameExistingDimensionProceeds() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(String.class)))
                    .thenReturn(List.of(SqliteVecSchemaInitializer.embeddingTableDdl(1024)));
            new SqliteVecSchemaInitializer(jdbc, propsWithDim(1024)).init();
            verify(jdbc, times(6)).execute(anyString());
        }
    }

    @Nested
    @DisplayName("조건부 빈 등록 (@ConditionalOnProperty)")
    class Conditional {
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean("vectorJdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
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
