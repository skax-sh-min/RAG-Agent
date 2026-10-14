package com.example.ragagent.config;

import com.example.ragagent.SqliteTestDatabase;
import db.migration.V9__Local_timestamps_to_utc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 — 서버 로컬 시각으로 쓰이던 네 테이블의 시각을 UTC 로 옮긴다. 처음부터 UTC 였던 컬럼({@code conversation_turns} 등)은
 * 모양이 같아도 건드리지 않아야 하고, NULL·모양이 다른 값은 그대로 남아야 한다. 앱의 Flyway 설정으로 V8 까지 올린 파일에
 * 행을 넣고 V9 를 돌린다({@code OrphanTurnRefsMigrationTest} 선례). 시간대는 JVM 기본값이라 변환 자체는 시간대를 받는
 * {@code toUtc()} 로 고정하고, Flyway 경로는 같은 기본값으로 계산한 기대값과 맞춰 본다.
 */
class LocalTimestampsToUtcMigrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** V8 까지 적용하고, 네 테이블마다 서버 로컬 시각 행 하나 + UTC 인 턴 하나를 넣는다. */
    private static JdbcTemplate seededAtV8(Path db) {
        SqliteTestDatabase.appFlyway(db, "8").migrate();
        JdbcTemplate jdbc = SqliteTestDatabase.jdbc(db);
        jdbc.update("INSERT INTO thread_meta (thread_id, user_id, created_at, updated_at) "
                + "VALUES ('t1', 'u', '2026-10-14 08:30:00', '2026-10-14 10:55:34')");
        jdbc.update("INSERT INTO curated_qa (source_user_id, source_thread_id, question, answer, created_at, updated_at) "
                + "VALUES ('u', 't1', 'q', 'a', '2026-10-14 12:00:00', '2026-10-14 12:00:05')");
        jdbc.update("INSERT INTO curated_submission (author_user_id, title, body, created_at, updated_at, reviewed_at) "
                + "VALUES ('u', 'title', 'body', '2026-10-14 09:00:00', '2026-10-14 09:10:00', '2026-10-14 09:20:00')");
        jdbc.update("INSERT INTO chunk_report (chunk_id, reporter_user_id, reason_code, comment, created_at) "
                + "VALUES ('c1', 'u', 'WRONG', 'comment', '2026-10-14 18:00:00')");
        // 처음부터 UTC — 모양이 같아도 이름으로 고르지 않은 컬럼이라 그대로여야 한다.
        jdbc.update("INSERT INTO conversation_turns (thread_id, question, answer, user_id, created_at, asked_at) "
                + "VALUES ('t1', 'q', 'a', 'u', '2026-10-14 01:57:00', '2026-10-14 01:56:50')");
        return jdbc;
    }

    private static String at(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    @Test
    @DisplayName("KST 로 쓰인 값은 9시간 당겨지고 날짜도 넘어간다 — UTC 컬럼·NULL 은 그대로")
    void shiftsServerLocalColumnsOnly(@TempDir Path db) throws Exception {
        Path file = db.resolve("memory.db");
        JdbcTemplate jdbc = seededAtV8(file);

        V9__Local_timestamps_to_utc.Result result;
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            result = V9__Local_timestamps_to_utc.toUtc(con, KST);
        }

        assertThat(at(jdbc, "SELECT created_at FROM thread_meta")).isEqualTo("2026-10-13 23:30:00");
        assertThat(at(jdbc, "SELECT updated_at FROM thread_meta")).isEqualTo("2026-10-14 01:55:34");
        assertThat(at(jdbc, "SELECT created_at FROM curated_qa")).isEqualTo("2026-10-14 03:00:00");
        assertThat(at(jdbc, "SELECT updated_at FROM curated_qa")).isEqualTo("2026-10-14 03:00:05");
        assertThat(at(jdbc, "SELECT created_at FROM curated_submission")).isEqualTo("2026-10-14 00:00:00");
        assertThat(at(jdbc, "SELECT updated_at FROM curated_submission")).isEqualTo("2026-10-14 00:10:00");
        assertThat(at(jdbc, "SELECT reviewed_at FROM curated_submission")).isEqualTo("2026-10-14 00:20:00");
        assertThat(at(jdbc, "SELECT author_read_at FROM curated_submission")).isNull();
        assertThat(at(jdbc, "SELECT created_at FROM chunk_report")).isEqualTo("2026-10-14 09:00:00");
        assertThat(at(jdbc, "SELECT reviewed_at FROM chunk_report")).isNull();

        assertThat(at(jdbc, "SELECT created_at FROM conversation_turns")).isEqualTo("2026-10-14 01:57:00");
        assertThat(at(jdbc, "SELECT asked_at FROM conversation_turns")).isEqualTo("2026-10-14 01:56:50");

        assertThat(result.converted()).isEqualTo(8);
        assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("모양이 다른 값은 건드리지 않고 센다")
    void leavesUnexpectedShapesAlone(@TempDir Path db) throws Exception {
        Path file = db.resolve("memory.db");
        JdbcTemplate jdbc = seededAtV8(file);
        jdbc.update("UPDATE chunk_report SET reviewed_at = '2026-10-14T18:30:00.123'");

        V9__Local_timestamps_to_utc.Result result;
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            result = V9__Local_timestamps_to_utc.toUtc(con, KST);
        }

        assertThat(at(jdbc, "SELECT reviewed_at FROM chunk_report")).isEqualTo("2026-10-14T18:30:00.123");
        assertThat(at(jdbc, "SELECT created_at FROM chunk_report")).isEqualTo("2026-10-14 09:00:00");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("UTC 로 돌던 서버(흔한 컨테이너)에서는 아무 값도 바뀌지 않는다")
    void utcServerChangesNothing(@TempDir Path db) throws Exception {
        Path file = db.resolve("memory.db");
        JdbcTemplate jdbc = seededAtV8(file);

        V9__Local_timestamps_to_utc.Result result;
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            result = V9__Local_timestamps_to_utc.toUtc(con, ZoneOffset.UTC);
        }

        assertThat(result.converted()).isZero();
        assertThat(at(jdbc, "SELECT updated_at FROM thread_meta")).isEqualTo("2026-10-14 10:55:34");
    }

    @Test
    @DisplayName("Flyway 로 돌리면 JVM 기본 시간대로 옮긴다 — 저장소가 LocalDateTime.now() 로 쓰던 그 시간대")
    void flywayUsesTheJvmDefaultZone(@TempDir Path db) {
        Path file = db.resolve("memory.db");
        JdbcTemplate jdbc = seededAtV8(file);

        assertThat(SqliteTestDatabase.appFlyway(file, null).migrate().success).isTrue();

        String expected = LocalDateTime.parse("2026-10-14 10:55:34", STAMP).atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).format(STAMP);
        assertThat(at(jdbc, "SELECT updated_at FROM thread_meta")).isEqualTo(expected);
        assertThat(at(jdbc, "SELECT created_at FROM conversation_turns")).isEqualTo("2026-10-14 01:57:00");
    }
}
