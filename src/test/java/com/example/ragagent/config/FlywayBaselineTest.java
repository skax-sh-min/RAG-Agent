package com.example.ragagent.config;

import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.SqliteMemoryRepository;
import com.example.ragagent.repository.ThreadMetaRepository;
import com.example.ragagent.security.SqliteUserDetailsService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code spring.flyway.baseline-version} 이 왜 3 인지 — application.properties 의 <b>실제 값</b>으로 고정한다.
 *
 * <p>DB 파일을 하나로 합치면서({@link DataSourceConfig}) Flyway 가 처음으로 실데이터가 있는 파일에 닿게 됐다.
 * 옛 분리 스위치를 켰던 배포의 그 파일은 저장소들의 런타임 DDL({@code CREATE TABLE IF NOT EXISTS} +
 * {@code ALTER})로 만들어져서 V1–V3 가 만드는 것이 전부 있는데 Flyway 이력은 없다. {@code baseline-on-migrate}
 * 는 그런 파일을 {@code baseline-version} 까지 "이미 적용됨"으로 표시하는데, 그 값이 1 이면 V2 의
 * {@code CREATE TABLE users}({@code IF NOT EXISTS} 없음)가 "already exists" 로 기동을 멈춘다.
 *
 * <p>그래서 두 가지를 본다: ① 런타임 DDL 이 V1–V3 가 만드는 모든 테이블·컬럼·인덱스를 만든다 — 3 으로
 * baseline 해도 빠지는 것이 없다는 근거 ② 그렇게 만들어진 파일에 앱의 Flyway 설정이 데이터를 건드리지 않고
 * 적용된다. 빈 파일은 baseline 과 무관하게 전부 적용된다는 것도 함께 본다.
 */
class FlywayBaselineTest {

    /** application.properties 의 spring.flyway.* 를 그대로 쓴다 — 값을 여기에 복사하면 조용히 어긋난다. */
    private static Flyway appFlyway(Path db) throws IOException {
        Properties app = new Properties();
        try (InputStream in = FlywayBaselineTest.class.getResourceAsStream("/application.properties")) {
            assertThat(in).as("main application.properties on the test classpath").isNotNull();
            app.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return Flyway.configure()
                .dataSource("jdbc:sqlite:" + db, null, null)
                .locations(app.getProperty("spring.flyway.locations"))
                .baselineOnMigrate(Boolean.parseBoolean(app.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(app.getProperty("spring.flyway.baseline-version"))
                .load();
    }

    private static JdbcTemplate jdbc(Path db) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + db));
    }

    /**
     * V1–V3 가 만드는 객체의 <b>런타임 소유자</b>들의 DDL 을 그대로 돌린다 — Flyway 없이 만들어진 옛 벡터 DB
     * 파일이 생겨난 방식이다. DDL 을 이 테스트에 복사하지 않는 이유는 소유자가 바뀔 때 조용히 어긋나기 때문.
     */
    private static void runRuntimeDdl(JdbcTemplate jdbc) {
        AppProperties props = mock(AppProperties.class);
        when(props.memorySafe()).thenReturn(new AppProperties.MemoryConfig(50));
        ReflectionTestUtils.invokeMethod(new SqliteMemoryRepository(jdbc, props), "init");
        ReflectionTestUtils.invokeMethod(new ThreadMetaRepository(jdbc), "init");
        ReflectionTestUtils.invokeMethod(new LlmUsageRepository(jdbc), "init");
        ReflectionTestUtils.invokeMethod(new SqliteUserDetailsService(jdbc), "initAuthSchema");
    }

    /** 테이블 → 컬럼, 그리고 이름 있는 인덱스. Flyway 자신의 이력 테이블과 SQLite 자동 객체는 뺀다. */
    private record Schema(Map<String, Set<String>> columns, Set<String> indexes) {}

    private static Schema schemaOf(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'", String.class);
        Map<String, Set<String>> columns = new java.util.HashMap<>();
        for (String t : tables) {
            columns.put(t, new HashSet<>(jdbc.query("PRAGMA table_info(\"" + t + "\")",
                    (rs, n) -> rs.getString("name"))));
        }
        Set<String> indexes = new HashSet<>(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'index' "
                        + "AND name NOT LIKE 'sqlite_autoindex_%' AND tbl_name <> 'flyway_schema_history'", String.class));
        return new Schema(columns, indexes);
    }

    @Test
    @DisplayName("런타임 DDL 은 V1–V3 가 만드는 테이블·컬럼·인덱스를 전부 만든다 — 3 으로 baseline 해도 빠지는 것이 없다")
    void runtimeDdlCoversEverythingTheBaselinedMigrationsCreate(@TempDir Path dir) throws IOException {
        Path viaFlyway = dir.resolve("flyway.db");
        appFlyway(viaFlyway).migrate();
        Schema migrated = schemaOf(jdbc(viaFlyway));

        JdbcTemplate runtime = jdbc(dir.resolve("runtime.db"));
        runRuntimeDdl(runtime);
        Schema built = schemaOf(runtime);

        assertThat(migrated.columns()).as("V1–V3 가 무엇이든 만들었어야 비교가 의미 있다").isNotEmpty();
        assertThat(built.columns().keySet()).containsAll(migrated.columns().keySet());
        migrated.columns().forEach((table, cols) ->
                assertThat(built.columns().get(table)).as("columns of " + table).containsAll(cols));
        assertThat(built.indexes()).containsAll(migrated.indexes());
    }

    @Test
    @DisplayName("이력 없이 런타임 DDL 로 만들어진 파일(옛 벡터 DB): baseline 만 기록하고 데이터는 그대로")
    void legacyFileWithoutHistory_isBaselinedWithoutTouchingData(@TempDir Path dir) throws IOException {
        Path db = dir.resolve("vector.db");
        JdbcTemplate jdbc = jdbc(db);
        runRuntimeDdl(jdbc);
        jdbc.update("INSERT INTO llm_usage (provider_name, usage_date, call_count) VALUES ('p', '2026-09-28', 7)");

        MigrateResult result = appFlyway(db).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).as("V1–V3 는 이미 있는 것을 다시 만들려다 실패하므로 돌면 안 된다").isZero();
        assertThat(jdbc.queryForList("SELECT version, type FROM flyway_schema_history"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("version")).isEqualTo("3");
                    assertThat(row.get("type")).isEqualTo("BASELINE");
                });
        assertThat(jdbc.queryForObject("SELECT call_count FROM llm_usage WHERE provider_name = 'p'", Integer.class))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("빈 파일(새 설치): baseline 없이 모든 마이그레이션이 적용된다")
    void emptyFile_appliesEveryMigration(@TempDir Path dir) throws IOException {
        Path db = dir.resolve("memory.db");
        Flyway flyway = appFlyway(db);

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isPositive();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc(db).queryForList("SELECT type FROM flyway_schema_history", String.class))
                .doesNotContain("BASELINE");
    }
}
