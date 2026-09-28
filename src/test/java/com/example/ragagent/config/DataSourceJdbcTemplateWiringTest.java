package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>어떤 SQLite 파일에 쓰는가</b> — 한정자 없는 {@code JdbcTemplate}(운영 저장소 전부)과
 * {@code @Qualifier("vectorJdbcTemplate")}(벡터/FTS)가 <b>같은 한 파일</b>을 가리키는지 sqlite-vec 모드에서
 * 실제 vec0 를 싣고 확인한다.
 *
 * <p>배경: 예전 분리 스위치({@code app.vectorstore.sqlite-vec.db-path})는 전용 벡터 DataSource 를 하나 더
 * 만들었다. 그런데 {@link DataSourceConfig} 가 {@code vectorJdbcTemplate} 빈을 정의하는 순간 Spring Boot 의
 * {@code JdbcTemplateAutoConfiguration}({@code @ConditionalOnMissingBean(JdbcOperations.class)})이 통째로
 * 물러나 그 빈이 컨텍스트의 유일한 템플릿이 됐고, 운영 테이블까지 전부 벡터 파일에 쌓였다 — {@code memory.db}
 * 는 Flyway 만 닿는 빈 파일로 남았다. 이 테스트는 그 사고를 처음 고정했던 자리다(2026-09-04). 지금은 파일을
 * 하나로 합쳤고, 스위치 값이 있으면 <b>그 파일 하나</b>를 연다 — 그 배포의 데이터가 전부 거기 있기 때문이다.
 * 여기 기대값이 다시 바뀐다면 그것은 곧 "기존 배포의 데이터가 다른 파일로 이사한다"는 경보다.
 *
 * <p>{@code -Dsqlitevec.path=...} 로 게이트한다({@code SqliteVecIntegrationTest} 와 같은 이유 — sqlite-vec
 * 백엔드는 커넥션마다 vec0 확장을 로드하므로 실제 바이너리가 필요하다).
 */
@EnabledIfSystemProperty(named = "sqlitevec.path", matches = ".+")
@ResourceLock("global-state")
class DataSourceJdbcTemplateWiringTest {

    private static final String VEC_PATH = System.getProperty("sqlitevec.path", "");

    private ApplicationContextRunner runner(Path dataDir, String... extraProps) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "app.data-dir=" + dataDir,
                        "app.vectorstore.type=sqlite-vec",
                        "app.vectorstore.sqlite-vec.extension-path=" + VEC_PATH)
                .withPropertyValues(extraProps);
    }

    @Test
    @DisplayName("옛 db-path 설정: DataSource·JdbcTemplate 하나씩, 그 파일에 vec0 까지 — memory.db 는 만들지 않는다")
    void legacyPath_oneDataSourceOnThatFile(@TempDir Path dir) {
        Path legacy = dir.resolve("vector.db");
        runner(dir, "app.vectorstore.sqlite-vec.db-path=" + legacy).run(context -> {
            assertThat(context).hasNotFailed();

            // ① 전용 벡터 DataSource 는 더 이상 없다 — 파일이 하나다.
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
            // ② Boot 의 자동설정 템플릿은 여전히 물러나 있고, 남은 하나가 그 DataSource 를 감싼다.
            assertThat(context.getBeanNamesForType(JdbcTemplate.class)).containsExactly("vectorJdbcTemplate");
            JdbcTemplate unqualified = context.getBean(JdbcTemplate.class);
            assertThat(unqualified.getDataSource()).isSameAs(context.getBean("dataSource", DataSource.class));

            // ③ 그 DataSource 는 옛 스위치의 파일을 연다 — 그 배포의 데이터가 전부 거기 있다.
            assertThat(DataSourceConfig.sqliteFilePath(unqualified.getDataSource()))
                    .isEqualTo(legacy.toAbsolutePath().normalize().toString());
            assertThat(dir.resolve("memory.db")).doesNotExist();

            // ④ vec0 도 그 한 DataSource 에 실린다(예전엔 분리 시 memory.db 쪽에는 싣지 않았다).
            assertThat(unqualified.queryForObject("SELECT vec_version()", String.class)).isNotBlank();
        });
    }

    @Test
    @DisplayName("db-path 없음: 같은 모양 — 파일은 {data-dir}/memory.db")
    void noLegacyPath_oneDataSourceOnMemoryDb(@TempDir Path dir) {
        runner(dir).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
            assertThat(context.getBeanNamesForType(JdbcTemplate.class)).containsExactly("vectorJdbcTemplate");

            JdbcTemplate unqualified = context.getBean(JdbcTemplate.class);
            assertThat(DataSourceConfig.sqliteFilePath(unqualified.getDataSource()))
                    .isEqualTo(dir.toAbsolutePath().normalize().resolve("memory.db").toString());
            assertThat(unqualified.queryForObject("SELECT vec_version()", String.class)).isNotBlank();
        });
    }
}
