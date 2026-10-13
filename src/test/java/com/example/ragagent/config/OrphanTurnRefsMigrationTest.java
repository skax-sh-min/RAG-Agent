package com.example.ragagent.config;

import com.example.ragagent.SqliteTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 — 턴이 사라졌는데 남은 출처·이미지 참조 행을 지운다. 살아 있는 턴의 행은 그대로여야 한다(재사용 검증·출처 미리보기가
 * 읽는다). 앱의 Flyway 설정으로 V6 까지 올린 파일에 행을 넣고 V7 을 돌린다({@code EmbeddingUsageMigrationTest} 선례).
 */
class OrphanTurnRefsMigrationTest {

    private static void turn(JdbcTemplate jdbc, long id) {
        jdbc.update("INSERT INTO conversation_turns (id, thread_id, question, answer, user_id) VALUES (?, 't', 'q', 'a', 'u')", id);
    }

    private static void sourceRef(JdbcTemplate jdbc, long turnId, String chunkId) {
        jdbc.update("INSERT INTO turn_source_ref (turn_id, user_id, thread_id, chunk_id, chunk_hash) VALUES (?, 'u', 't', ?, 'h')",
                turnId, chunkId);
    }

    private static void imageRef(JdbcTemplate jdbc, long turnId, String ref) {
        jdbc.update("INSERT INTO turn_image_ref (turn_id, user_id, thread_id, image_ref) VALUES (?, 'u', 't', ?)", turnId, ref);
    }

    @Test
    @DisplayName("지워진 턴의 출처·이미지 참조는 지우고, 살아 있는 턴의 것은 그대로 둔다")
    void purgesOnlyRowsOfMissingTurns(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        SqliteTestDatabase.appFlyway(db, "6").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        turn(jdbc, 1);
        sourceRef(jdbc, 1, "live-a");
        sourceRef(jdbc, 1, "live-b");
        imageRef(jdbc, 1, "img-live");
        // 턴 2 는 출처 삭제가 들어가기 전에 지워졌다 — 자식 행만 남았다.
        sourceRef(jdbc, 2, "orphan-a");
        imageRef(jdbc, 2, "img-orphan");

        assertThat(SqliteTestDatabase.appFlyway(db, null).migrate().success).isTrue();

        assertThat(jdbc.queryForList("SELECT chunk_id FROM turn_source_ref ORDER BY chunk_id", String.class))
                .containsExactly("live-a", "live-b");
        assertThat(jdbc.queryForList("SELECT image_ref FROM turn_image_ref", String.class))
                .containsExactly("img-live");
    }

    @Test
    @DisplayName("고아가 없는 DB 에서는 아무것도 바꾸지 않는다")
    void nothingToPurge_nothingChanges(@TempDir Path dir) {
        Path db = dir.resolve("memory.db");
        SqliteTestDatabase.appFlyway(db, "6").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        turn(jdbc, 1);
        sourceRef(jdbc, 1, "live-a");

        assertThat(SqliteTestDatabase.appFlyway(db, null).migrate().success).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM turn_source_ref", Integer.class)).isEqualTo(1);
    }
}
