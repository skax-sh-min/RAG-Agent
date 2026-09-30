package com.example.ragagent.config;

import com.example.ragagent.SqliteTestDatabase;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.flyway.baseline-version} 이 왜 3 인지 — application.properties 의 <b>실제 값</b>으로 고정한다.
 *
 * <p>DB 파일을 하나로 합치면서({@link DataSourceConfig}) Flyway 가 처음으로 실데이터가 있는 파일에 닿게 됐다.
 * 옛 분리 스위치를 켰던 배포의 그 파일은 V4 이전 저장소들의 런타임 DDL({@code CREATE TABLE IF NOT EXISTS} +
 * {@code ALTER})로 만들어져서 V1–V3 가 만드는 것이 전부 있는데 Flyway 이력은 없다. {@code baseline-on-migrate}
 * 는 그런 파일을 {@code baseline-version} 까지 "이미 적용됨"으로 표시하는데, 그 값이 1 이면 V2 의
 * {@code CREATE TABLE users}({@code IF NOT EXISTS} 없음)가 "already exists" 로 기동을 멈춘다.
 *
 * <p>그래서 두 가지를 본다: ① 옛 런타임 DDL({@link SqliteTestDatabase#PRE_V4_RUNTIME_DDL})이 V1–V3 가 만드는
 * 모든 테이블·컬럼·인덱스를 만든다 — 3 으로 baseline 해도 빠지는 것이 없다는 근거 ② 그렇게 만들어진 파일에 앱의
 * Flyway 설정이 데이터를 건드리지 않고 적용된다(V1–V3 는 건너뛰고 V4 만 돈다). 빈 파일은 baseline 과 무관하게
 * 전부 적용된다는 것도 함께 본다. 적용 뒤의 스키마가 새 설치와 같다는 것은 {@code FlywaySchemaConvergenceTest}.
 */
class FlywayBaselineTest {

    /** 테이블 → 컬럼 이름, 그리고 이름 있는 인덱스. Flyway 자신의 이력 테이블과 SQLite 자동 객체는 뺀다. */
    private record Names(Map<String, List<String>> tables) {
        List<String> columnsOf(String table) {
            return tables.get(table).stream().filter(p -> p.startsWith("col ")).map(Names::name).toList();
        }

        List<String> indexes() {
            return tables.values().stream().flatMap(List::stream)
                    .filter(p -> p.startsWith("idx ") && !p.startsWith("idx sqlite_autoindex_"))
                    .map(Names::name).toList();
        }

        private static String name(String part) {
            return part.substring(4, part.indexOf(" | "));
        }
    }

    private static Names namesOf(JdbcTemplate jdbc) {
        return new Names(SqliteTestDatabase.schemaOf(jdbc));
    }

    @Test
    @DisplayName("옛 런타임 DDL 은 V1–V3 가 만드는 테이블·컬럼·인덱스를 전부 만든다 — 3 으로 baseline 해도 빠지는 것이 없다")
    void runtimeDdlCoversEverythingTheBaselinedMigrationsCreate(@TempDir Path dir) {
        Path viaFlyway = dir.resolve("flyway.db");
        SqliteTestDatabase.appFlyway(viaFlyway, "3").migrate();
        Names migrated = namesOf(SqliteTestDatabase.jdbc(viaFlyway));

        JdbcTemplate runtime = SqliteTestDatabase.jdbc(dir.resolve("runtime.db"));
        SqliteTestDatabase.replayPreV4RuntimeDdl(runtime, sql -> false);
        Names built = namesOf(runtime);

        assertThat(migrated.tables()).as("V1–V3 가 무엇이든 만들었어야 비교가 의미 있다").isNotEmpty();
        assertThat(built.tables().keySet()).containsAll(migrated.tables().keySet());
        migrated.tables().keySet().forEach(table ->
                assertThat(built.columnsOf(table)).as("columns of " + table)
                        .containsAll(migrated.columnsOf(table)));
        assertThat(built.indexes()).containsAll(migrated.indexes());
    }

    @Test
    @DisplayName("이력 없이 런타임 DDL 로 만들어진 파일(옛 벡터 DB): 3 으로 baseline 하고 V4 만 돌며, 데이터는 그대로")
    void legacyFileWithoutHistory_isBaselinedAndOnlyV4Runs(@TempDir Path dir) {
        Path db = dir.resolve("vector.db");
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        SqliteTestDatabase.replayPreV4RuntimeDdl(jdbc, sql -> false);
        jdbc.update("INSERT INTO llm_usage (provider_name, usage_date, call_count) VALUES ('p', '2026-09-28', 7)");

        MigrateResult result = SqliteTestDatabase.appFlyway(db, null).migrate();

        assertThat(result.success).isTrue();
        assertThat(jdbc.queryForList(
                "SELECT version, type FROM flyway_schema_history ORDER BY installed_rank"))
                .as("V1–V3 는 이미 있는 것을 다시 만들려다 실패하므로 돌면 안 된다 — baseline 뒤로는 V4 부터")
                .extracting(row -> row.get("version") + ":" + row.get("type"))
                .startsWith("3:BASELINE", "4:JDBC")
                .doesNotContain("1:SQL", "2:SQL", "3:SQL");
        assertThat(jdbc.queryForObject("SELECT call_count FROM llm_usage WHERE provider_name = 'p'", Integer.class))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("빈 파일(새 설치): baseline 없이 모든 마이그레이션이 적용된다")
    void emptyFile_appliesEveryMigration(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        Flyway flyway = SqliteTestDatabase.appFlyway(db, null);

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        assertThat(jdbc.queryForList("SELECT type FROM flyway_schema_history", String.class))
                .doesNotContain("BASELINE");
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class))
                .as("V1 부터 전부")
                .startsWith("1", "2", "3", "4");
    }
}
