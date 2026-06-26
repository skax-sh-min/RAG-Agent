package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link SqliteVecVerifier}는 sqlite-vec 모드에서만 생성됨을 검증한다.
 * {@code ApplicationContextRunner}는 {@code ApplicationReadyEvent}를 발행하지 않으므로
 * {@code verify()}는 실행되지 않고 {@code @ConditionalOnProperty} 빈 등록 조건만 확인한다.
 */
class SqliteVecVerifierTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withUserConfiguration(SqliteVecVerifier.class);

    @Test
    @DisplayName("기본(프로퍼티 미설정): 빈 미생성")
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SqliteVecVerifier.class));
    }

    @Test
    @DisplayName("type=chroma: 빈 미생성")
    void absentForChroma() {
        runner.withPropertyValues("app.vectorstore.type=chroma")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SqliteVecVerifier.class));
    }

    @Test
    @DisplayName("type=sqlite-vec: 빈 생성")
    void presentForSqliteVec() {
        runner.withPropertyValues("app.vectorstore.type=sqlite-vec")
                .run(ctx -> assertThat(ctx).hasSingleBean(SqliteVecVerifier.class));
    }
}
