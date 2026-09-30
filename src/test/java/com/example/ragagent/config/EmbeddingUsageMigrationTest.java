package com.example.ragagent.config;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 — 모델별로 쌓인 임베딩 사용량({@code embed:<model>})을 모델명 없는 {@code embed} 하나로 합친다.
 * 모델을 바꾼 배포에는 같은 날짜에 두 모델의 행이 함께 있을 수 있으므로, 합친 뒤에도 날짜별 합계가
 * 그대로여야 한다(잃는 것은 모델별 구분뿐). 앱의 Flyway 설정으로 V4 까지 올린 파일에 옛 행을 넣고 V5 를 돌린다.
 */
class EmbeddingUsageMigrationTest {

    private static final String INSERT =
            "INSERT INTO llm_usage (provider_name, usage_date, input_tokens, output_tokens, call_count) VALUES (?, ?, ?, ?, ?)";

    @Test
    @DisplayName("embed:<model> 행은 날짜별 합계를 유지한 채 embed 로 합쳐지고, 다른 행은 그대로다")
    void mergesPerModelRowsIntoEmbedPerDay(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        SqliteTestDatabase.appFlyway(db, "4").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        // 9/1 에 모델을 바꿨다 — 같은 날짜에 두 모델의 행이 있다.
        jdbc.update(INSERT, "embed:nomic", "2026-09-01", 10, 0, 1);
        jdbc.update(INSERT, "embed:bge-m3", "2026-09-01", 5, 0, 2);
        jdbc.update(INSERT, "embed:bge-m3", "2026-09-02", 7, 0, 3);
        // 이미 embed 행이 있는 날짜 — 정상 경로에서는 생기지 않지만 ON CONFLICT 쪽이 더해야 한다(덮으면 잃는다).
        jdbc.update(INSERT, "embed", "2026-09-02", 1, 0, 1);
        jdbc.update(INSERT, "local", "2026-09-01", 100, 50, 4);
        jdbc.update(INSERT, "title:local", "2026-09-01", 20, 5, 1);

        assertThat(SqliteTestDatabase.appFlyway(db, null).migrate().success).isTrue();

        assertThat(jdbc.queryForList(
                "SELECT provider_name || ' ' || usage_date || ' ' || input_tokens || '/' || output_tokens || '/' || call_count"
                        + " FROM llm_usage ORDER BY provider_name, usage_date", String.class))
                .containsExactly(
                        "embed 2026-09-01 15/0/3",
                        "embed 2026-09-02 8/0/4",
                        "local 2026-09-01 100/50/4",
                        "title:local 2026-09-01 20/5/1");
    }

    @Test
    @DisplayName("임베딩 이력이 없는 DB 에서는 아무것도 바꾸지 않는다")
    void noEmbeddingRows_nothingChanges(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        SqliteTestDatabase.appFlyway(db, "4").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        jdbc.update(INSERT, "local", "2026-09-01", 100, 50, 4);

        assertThat(SqliteTestDatabase.appFlyway(db, null).migrate().success).isTrue();

        assertThat(jdbc.queryForList("SELECT provider_name FROM llm_usage", String.class))
                .isEqualTo(List.of("local"));
    }
}
