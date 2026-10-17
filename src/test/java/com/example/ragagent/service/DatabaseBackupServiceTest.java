package com.example.ragagent.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DatabaseBackupService} — 진짜 SQLite 파일(앱과 같은 모양: Hikari 풀 1개 · WAL)에 대해 백업을 뜨고, 사본이 열리고
 * 내용이 같은지, 이름 규칙·보관 개수·실패 처리를 본다.
 */
class DatabaseBackupServiceTest {

    @TempDir Path tmp;
    private HikariDataSource ds;
    private Path backupDir;

    @BeforeEach
    void setUp() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tmp.resolve("memory.db") + "?journal_mode=WAL&busy_timeout=5000");
        cfg.setMaximumPoolSize(1);
        ds = new HikariDataSource(cfg);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE conversation_turns (id INTEGER PRIMARY KEY, question TEXT)");
        jdbc.update("INSERT INTO conversation_turns(question) VALUES ('첫 질문'), ('둘째 질문')");
        backupDir = tmp.resolve("db-backups");
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    private DatabaseBackupService service(boolean enabled, int keep) {
        return new DatabaseBackupService(ds, tmp.toString(), enabled, "0 30 3 * * *", keep, backupDir.toString(),
                "Asia/Seoul");
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    @DisplayName("run — 검사를 통과한 사본을 memory-yyyyMMdd-HHmmss.db 로 남기고, 그 사본은 원본과 같은 내용으로 열린다")
    void run_writesAVerifiedCopy() throws Exception {
        DatabaseBackupService svc = service(true, 7);

        DatabaseBackupService.RunResult r = svc.run("manual");

        assertThat(r.ok()).as(r.error()).isTrue();
        assertThat(r.file()).matches("memory-\\d{8}-\\d{6}\\.db");
        assertThat(r.bytes()).isPositive();
        assertThat(names(backupDir)).containsExactly(r.file());          // .partial 이 남지 않는다
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backupDir.resolve(r.file()));
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM conversation_turns")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        DatabaseBackupService.Status st = svc.status();
        assertThat(st.last()).isEqualTo(r);
        assertThat(st.backups()).extracting(DatabaseBackupService.BackupFile::name).containsExactly(r.file());
        assertThat(st.running()).isFalse();
    }

    @Test
    @DisplayName("run — 앱의 풀 연결을 쓰지 않는다: 풀(1개)의 연결을 쥐고 있어도 백업이 끝난다")
    void run_doesNotNeedThePoolConnection() throws Exception {
        DatabaseBackupService svc = service(true, 7);
        try (Connection held = ds.getConnection()) {                     // 풀의 유일한 연결을 붙잡는다
            held.createStatement().execute("SELECT 1");
            assertThat(svc.run("manual").ok()).isTrue();
        }
    }

    @Test
    @DisplayName("run — 가장 최근 keep 개만 남기고, 앞선 실행이 남긴 .partial 은 지운다 · 이름이 패턴에 맞지 않는 파일은 건드리지 않는다")
    void run_prunesToKeepAndClearsPartials() throws Exception {
        Files.createDirectories(backupDir);
        for (String n : List.of("memory-20260101-030000.db", "memory-20260102-030000.db", "memory-20260103-030000.db",
                "memory-20260104-030000.db.partial", "notes.txt")) {
            Files.writeString(backupDir.resolve(n), "x");
        }
        DatabaseBackupService svc = service(true, 2);

        DatabaseBackupService.RunResult r = svc.run("scheduled");

        assertThat(r.ok()).isTrue();
        assertThat(names(backupDir)).containsExactlyInAnyOrder(r.file(), "memory-20260103-030000.db", "notes.txt");
    }

    @Test
    @DisplayName("run — 실패는 예외가 아니라 결과로 남는다(정기 실행이 스케줄러를 죽이지 않게) · 원본 URL 을 모르면 실패")
    void run_failureIsRecordedNotThrown() {
        DriverManagerDataSource notHikari = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("x.db"));
        DatabaseBackupService svc = new DatabaseBackupService(notHikari, tmp.toString(), true, "0 30 3 * * *", 7,
                backupDir.toString(), "Asia/Seoul");

        DatabaseBackupService.RunResult r = svc.run("manual");

        assertThat(r.ok()).isFalse();
        assertThat(r.file()).isNull();
        assertThat(svc.status().last()).isEqualTo(r);
    }

    @Test
    @DisplayName("scheduled — 꺼져 있으면 아무것도 하지 않는다(수동 실행은 된다) · 기본 위치는 {DATA_DIR}/db-backups")
    void disabled_skipsScheduledRunsOnly() throws Exception {
        DatabaseBackupService off = service(false, 7);
        off.scheduled();
        assertThat(Files.exists(backupDir)).isFalse();
        assertThat(off.run("manual").ok()).isTrue();

        DatabaseBackupService defaults = new DatabaseBackupService(ds, tmp.toString(), true, "0 30 3 * * *", 7, "",
                "Asia/Seoul");
        assertThat(defaults.status().dir()).isEqualTo(tmp.resolve("db-backups").toAbsolutePath().toString());
    }

    /**
     * 파일 이름의 시각은 app.time-zone 의 시계다 — JVM 기본 시간대가 아니다. 어느 JVM 의 기본값과도 겹치지 않을 시간대
     * (UTC+14)를 줘서, 기본 시간대로 찍으면 반드시 틀리게 만든다.
     */
    @Test
    @DisplayName("run — 이름의 시각은 app.time-zone 기준이고, 카드가 그 시간대를 함께 보인다")
    void run_namesTheCopyOnTheConfiguredClock() throws Exception {
        ZoneId zone = ZoneId.of("Pacific/Kiritimati");
        DatabaseBackupService svc = new DatabaseBackupService(ds, tmp.toString(), true, "0 30 3 * * *", 7,
                backupDir.toString(), zone.getId());

        DatabaseBackupService.RunResult r = svc.run("manual");

        assertThat(r.ok()).as(r.error()).isTrue();
        LocalDateTime named = LocalDateTime.parse(
                r.file().substring("memory-".length(), r.file().length() - ".db".length()),
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        assertThat(Duration.between(named, LocalDateTime.now(zone)).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(svc.status().zone()).isEqualTo("Pacific/Kiritimati");
    }

    @Test
    @DisplayName("생성 — 시간대가 틀리면 기동 단계에서 멈춘다(조용히 다른 시계로 돌지 않는다)")
    void invalidZoneFailsFast() {
        assertThatThrownBy(() -> new DatabaseBackupService(ds, tmp.toString(), true, "0 30 3 * * *", 7, "", "Asia/Seol"))
                .isInstanceOf(DateTimeException.class);
    }
}
