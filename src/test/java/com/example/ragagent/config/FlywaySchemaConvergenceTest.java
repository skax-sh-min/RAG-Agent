package com.example.ragagent.config;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4({@code db.migration.V4__Consolidate_runtime_schema})는 <b>어느 옛 상태에서 출발해도</b> 새 설치와 같은 스키마로
 * 간다 — 그래서 V5 이후는 평범한 SQL 마이그레이션으로 쓸 수 있다.
 *
 * <p>기준은 빈 파일에 앱의 Flyway 설정을 그대로 적용한 결과다. 출발 상태는 V4 이전에 실제로 있을 수 있던 모양들이고,
 * 전부 옛 저장소들이 실행하던 DDL 의 기록({@link SqliteTestDatabase#PRE_V4_RUNTIME_DDL})으로 만든다 — V4 의 정의를
 * 여기서 다시 쓰면 V4 가 틀렸을 때 테스트도 같이 틀린다. 비교는 {@link SqliteTestDatabase#schemaOf} 로 타입·NOT NULL·
 * 기본값·PK 와 인덱스(UNIQUE·키·부분 조건)까지 본다.
 *
 * <p>수렴만으로는 "V4 를 고쳤다"를 잡지 못한다 — V4 에 컬럼을 더하면 옛 DB 에도 같이 더해져 여전히 수렴한다. 그리고
 * Java 마이그레이션에는 체크섬이 없어 Flyway 도 그 수정을 모른다(이미 V4 를 적용한 DB 에는 조용히 반영되지 않는다).
 * 그래서 V1–V4 의 결과를 옛 DDL 기록과 <b>정확히</b> 대조하는 테스트를 따로 둔다.
 */
class FlywaySchemaConvergenceTest {

    @Test
    @DisplayName("V1–V4 의 결과는 옛 런타임 DDL 의 결과와 정확히 같다 — V4 는 옮겼을 뿐 스키마를 바꾸지 않는다")
    void v1ToV4_reproduceExactlyThePreV4RuntimeSchema(@TempDir Path dir) {
        Path migrated = dir.resolve("v4.db");
        SqliteTestDatabase.appFlyway(migrated, "4").migrate();
        JdbcTemplate runtime = SqliteTestDatabase.jdbc(dir.resolve("runtime.db"));
        SqliteTestDatabase.replayPreV4RuntimeDdl(runtime, sql -> false);

        assertThat(SqliteTestDatabase.describe(SqliteTestDatabase.schemaOf(SqliteTestDatabase.jdbc(migrated))))
                .as("V4 는 적용된 마이그레이션이다 — 스키마를 바꾸려면 V4 를 고치지 말고 V5 이후 마이그레이션을 더할 것"
                        + " (Java 마이그레이션은 체크섬이 없어 Flyway validate 가 이 수정을 잡지 못한다)")
                .isEqualTo(SqliteTestDatabase.describe(SqliteTestDatabase.schemaOf(runtime)));
    }

    private static Map<String, List<String>> freshInstall(Path dir) {
        Path db = dir.resolve("fresh.db");
        SqliteTestDatabase.appFlyway(db, null).migrate();
        return SqliteTestDatabase.schemaOf(SqliteTestDatabase.jdbc(db));
    }

    private static void assertConverged(Path dir, JdbcTemplate jdbc) {
        Map<String, List<String>> expected = freshInstall(dir);
        Map<String, List<String>> actual = SqliteTestDatabase.schemaOf(jdbc);
        assertThat(SqliteTestDatabase.describe(actual))
                .as("V4 적용 뒤의 스키마는 새 설치와 같아야 한다")
                .isEqualTo(SqliteTestDatabase.describe(expected));
    }

    @Test
    @DisplayName("옛 새 설치(V1–V3 이력 + 런타임 DDL 이 붙인 테이블·컬럼) → V4 뒤 새 설치와 같다")
    void historyPlusRuntimeDdl_converges(@TempDir Path dir) {
        Path db = dir.resolve("old-install.db");
        SqliteTestDatabase.appFlyway(db, "3").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        SqliteTestDatabase.replayPreV4RuntimeDdl(jdbc, sql -> false);

        SqliteTestDatabase.appFlyway(db, null).migrate();

        assertConverged(dir, jdbc);
    }

    @Test
    @DisplayName("이력 없이 런타임 DDL 로만 만들어진 파일(옛 벡터 DB) → baseline 3 + V4 뒤 새 설치와 같다")
    void runtimeDdlOnly_converges(@TempDir Path dir) {
        Path db = dir.resolve("old-vector.db");
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        SqliteTestDatabase.replayPreV4RuntimeDdl(jdbc, sql -> false);

        SqliteTestDatabase.appFlyway(db, null).migrate();

        assertConverged(dir, jdbc);
    }

    @Test
    @DisplayName("옛 버전 앱의 DB(최근 컬럼·테이블이 빠짐) → V4 가 빠진 것만 채워 새 설치와 같다")
    void olderAppMissingRecentSchema_converges(@TempDir Path dir) {
        // 최근에 생긴 것들: 검증 스냅샷·출처 위치 스냅샷·큐레이션 요약/키워드·문서 태그 컬럼, 청크 신고 테이블.
        Predicate<String> recent = sql -> sql.contains("chunk_report")
                || (sql.startsWith("ALTER") && (sql.contains(" verification ")
                        || sql.contains(" page_or_slide ") || sql.contains(" chapter_no ")
                        || sql.contains(" summary ") || sql.contains(" keywords ")
                        || sql.contains("doc_registry ADD COLUMN tags")));
        Path db = dir.resolve("old-app.db");
        SqliteTestDatabase.appFlyway(db, "3").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        SqliteTestDatabase.replayPreV4RuntimeDdl(jdbc, recent);
        // 런타임 DDL 의 CREATE 는 마지막 모양이라 summary/keywords 를 이미 담고 있다 — 옛 버전의 모양으로 떨어낸다.
        jdbc.execute("ALTER TABLE curated_qa DROP COLUMN summary");
        jdbc.execute("ALTER TABLE curated_qa DROP COLUMN keywords");
        jdbc.execute("ALTER TABLE curated_submission DROP COLUMN summary");
        jdbc.execute("ALTER TABLE curated_submission DROP COLUMN keywords");
        assertThat(jdbc.queryForList("PRAGMA table_info(conversation_turns)"))
                .as("출발 상태가 정말 옛 모양인지")
                .extracting(c -> c.get("name")).doesNotContain("verification");

        SqliteTestDatabase.appFlyway(db, null).migrate();

        assertConverged(dir, jdbc);
    }

    @Test
    @DisplayName("좋아요만 있던 시절의 curated_qa(source_turn_id NOT NULL, origin 없음) → 재생성되어 새 설치와 같고 행은 남는다")
    void preOriginCuratedQa_isRebuiltAndConverges(@TempDir Path dir) {
        Path db = dir.resolve("pre-origin.db");
        SqliteTestDatabase.appFlyway(db, "3").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        jdbc.execute("""
                CREATE TABLE curated_qa (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_turn_id      INTEGER NOT NULL,
                    source_user_id      TEXT NOT NULL,
                    source_thread_id    TEXT NOT NULL,
                    question            TEXT NOT NULL,
                    answer              TEXT NOT NULL,
                    status              TEXT NOT NULL DEFAULT 'active',
                    source_doc_version  TEXT,
                    created_at          TEXT NOT NULL,
                    updated_at          TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE UNIQUE INDEX idx_curated_qa_turn ON curated_qa(source_turn_id)");
        jdbc.update("INSERT INTO curated_qa (source_turn_id, source_user_id, source_thread_id, question, answer, "
                + "created_at, updated_at) VALUES (42, 'u1', 't1', '질문', '답변', '2026-01-01', '2026-01-01')");
        // curated_qa 를 만지는 문장만 뺀다(\b 라 curated_submission 의 curated_qa_id 컬럼은 걸리지 않는다).
        SqliteTestDatabase.replayPreV4RuntimeDdl(jdbc, sql -> sql.matches("(?s).*\\bcurated_qa\\b.*"));

        SqliteTestDatabase.appFlyway(db, null).migrate();

        assertConverged(dir, jdbc);
        assertThat(jdbc.queryForMap("SELECT source_turn_id, origin, chunk_count FROM curated_qa"))
                .containsEntry("source_turn_id", 42)
                .containsEntry("origin", "like")
                .containsEntry("chunk_count", 1);
    }
}
