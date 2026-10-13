package com.example.ragagent.service;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SQLite DB 파일({@code memory.db})의 정기 백업 — 대화 기록·계정·설정·큐레이션·문서 레지스트리·검색 색인({@code chunk_fts})과
 * sqlite-vec 벡터가 전부 이 한 파일에 있다(CLAUDE.md "SQLite 파일은 하나다").
 *
 * <p><b>방법은 {@code VACUUM INTO} 를 앱 커넥션 풀 밖의 별도 연결에서 돌리는 것이다.</b> {@code VACUUM INTO} 는 원본에 대해
 * 읽기 트랜잭션 하나만 쥐고 정리된 사본을 새 파일로 쓴다 — WAL 모드라 그동안 앱의 쓰기는 막히지 않는다(57MB DB 를 0.5초에 떴고,
 * 그 사이 다른 연결의 쓰기 383건이 최악 13ms 로 지나갔다 — 2026-10-13 실측). 풀(크기 1)의 연결로 돌리면 백업 동안 모든 요청이
 * 그 연결을 기다리고, 파일을 그냥 복사하면 아직 체크포인트되지 않은 {@code -wal} 의 페이지를 놓친다. 사본은 vec0·FTS5 의 그림자
 * 테이블까지 일반 테이블로 담으므로 확장을 싣지 않은 연결로도 뜬다.
 *
 * <p>순서: {@code .partial} 이름으로 쓰고 → 그 파일을 열어 {@code PRAGMA quick_check} 가 {@code ok} 인지 보고 → 이름을 바꾼다.
 * 그래서 {@code memory-*.db} 라는 이름의 파일은 언제나 검사를 통과한 완성본이다. 그 뒤 가장 최근 {@code keep} 개만 남긴다.
 *
 * <p><b>담지 않는 것</b>: Chroma 백엔드의 벡터(Chroma 서버에 있다) · 문서 원본·변환본·이미지({@code documents/} · {@code converted/} ·
 * {@code images/} — 파일이다). 복구 절차와 함께 OPERATOR_MANUAL 에 적혀 있다.
 *
 * <p>설정은 기동 시 한 번 읽는다({@code app.backup.*} — 재기동 필요, {@code CircuitBreaker} 의 {@code @Value} 선례).
 */
@Service
public class DatabaseBackupService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);

    static final String PREFIX = "memory-";
    static final String SUFFIX = ".db";
    static final String PARTIAL = ".partial";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern BACKUP_NAME = Pattern.compile("memory-\\d{8}-\\d{6}\\.db");

    /** 백업 파일 하나 — 이름이 곧 만든 시각이다. */
    public record BackupFile(String name, long bytes, Instant modifiedAt) {}

    /** 마지막 실행의 결과. {@code error} 가 있으면 실패다(그때 {@code file} 은 null). */
    public record RunResult(String trigger, Instant finishedAt, String file, long bytes, long millis, String error) {
        public boolean ok() { return error == null; }
    }

    /** {@code /admin} 카드가 그리는 것 — 설정 · 마지막 실행 · 남아 있는 백업. */
    public record Status(boolean enabled, String cron, int keep, String dir, boolean running,
                         RunResult last, List<BackupFile> backups) {}

    private final DataSource dataSource;
    private final boolean enabled;
    private final String cron;
    private final int keep;
    private final Path dir;
    private volatile boolean running;
    private volatile RunResult last;

    public DatabaseBackupService(DataSource dataSource,
                                 @Value("${app.data-dir:./data}") String dataDir,
                                 @Value("${app.backup.enabled:true}") boolean enabled,
                                 @Value("${app.backup.cron:0 30 3 * * *}") String cron,
                                 @Value("${app.backup.keep:7}") int keep,
                                 @Value("${app.backup.dir:}") String backupDir) {
        this.dataSource = dataSource;
        this.enabled = enabled;
        this.cron = cron;
        this.keep = Math.max(1, keep);
        this.dir = (backupDir == null || backupDir.isBlank())
                ? Path.of(dataDir).resolve("db-backups")
                : Path.of(backupDir);
    }

    /** 정기 실행 — {@code app.backup.enabled=false} 면 아무것도 하지 않는다(수동 실행은 그래도 된다). */
    @Scheduled(cron = "${app.backup.cron:0 30 3 * * *}")
    void scheduled() {
        if (!enabled) return;
        run("scheduled");
    }

    /**
     * 지금 한 번 백업한다. 정기 실행과 같은 잠금을 쓰므로 둘이 겹치지 않는다 — 이미 돌고 있으면 기다렸다가 새로 뜬다.
     * 실패해도 예외를 던지지 않고 결과에 담는다(정기 실행이 스케줄러 스레드를 죽이지 않게).
     */
    public synchronized RunResult run(String trigger) {
        running = true;
        long t0 = System.nanoTime();
        Path partial = null;
        try {
            String url = sourceUrl();
            Files.createDirectories(dir);
            deleteLeftoverPartials();
            String name = PREFIX + LocalDateTime.now().format(STAMP) + SUFFIX;
            while (Files.exists(dir.resolve(name))) {   // 같은 초에 두 번(버튼 연타) — 다음 초 이름을 쓴다
                Thread.sleep(200);
                name = PREFIX + LocalDateTime.now().format(STAMP) + SUFFIX;
            }
            Path target = dir.resolve(name);
            partial = dir.resolve(name + PARTIAL);
            try (Connection c = DriverManager.getConnection(url);
                 PreparedStatement ps = c.prepareStatement("VACUUM INTO ?")) {
                ps.setString(1, partial.toAbsolutePath().toString());
                ps.execute();
            }
            String check = quickCheck(partial);
            if (!"ok".equalsIgnoreCase(check)) {
                throw new IllegalStateException("백업본 무결성 검사 실패: " + check);
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            partial = null;
            long bytes = Files.size(target);
            int pruned = prune();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[DB-BACKUP] {} 완료 — {} ({} bytes, {} ms, 오래된 백업 {}개 정리)", trigger, target, bytes, ms, pruned);
            return last = new RunResult(trigger, Instant.now(), name, bytes, ms, null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.error("[DB-BACKUP] {} 실패 — {} (경로 {})", trigger, e.getMessage(), dir, e);
            deleteQuietly(partial);
            return last = new RunResult(trigger, Instant.now(), null, 0, ms, e.getMessage());
        } finally {
            running = false;
        }
    }

    public Status status() {
        return new Status(enabled, cron, keep, dir.toAbsolutePath().toString(), running, last, listBackups());
    }

    /** 이름이 {@code memory-yyyyMMdd-HHmmss.db} 인 완성본만, 최신부터. */
    public List<BackupFile> listBackups() {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<BackupFile> out = new ArrayList<>();
            for (Path p : files.filter(p -> BACKUP_NAME.matcher(p.getFileName().toString()).matches()).toList()) {
                try {
                    out.add(new BackupFile(p.getFileName().toString(), Files.size(p),
                            Files.getLastModifiedTime(p).toInstant()));
                } catch (IOException ignored) {
                    // 그사이 정리된 파일 — 목록에서 빠질 뿐이다
                }
            }
            out.sort(Comparator.comparing(BackupFile::name).reversed());
            return out;
        } catch (IOException e) {
            log.warn("[DB-BACKUP] 백업 목록을 읽지 못했다 {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** 가장 최근 {@link #keep} 개만 남긴다. 이름이 시각 순이라 이름으로 정렬한다(파일 시각은 복사로 바뀔 수 있다). */
    int prune() {
        List<BackupFile> all = listBackups();
        int removed = 0;
        for (BackupFile f : all.subList(Math.min(keep, all.size()), all.size())) {
            try {
                Files.deleteIfExists(dir.resolve(f.name()));
                removed++;
            } catch (IOException e) {
                log.warn("[DB-BACKUP] 오래된 백업을 지우지 못했다 {}: {}", f.name(), e.getMessage());
            }
        }
        return removed;
    }

    /**
     * 원본에 여는 연결의 URL — 앱이 쓰는 것과 같은 URL(같은 파일, 같은 세션 PRAGMA)이되 <b>풀 밖의 새 연결</b>로 연다.
     * vec0 확장은 싣지 않는다: {@code VACUUM INTO} 는 가상 테이블을 스키마 행으로, 그 데이터는 그림자 테이블로 옮기므로
     * 모듈이 필요 없다.
     */
    private String sourceUrl() {
        if (dataSource instanceof HikariDataSource hikari && hikari.getJdbcUrl() != null) {
            return hikari.getJdbcUrl();
        }
        throw new IllegalStateException("SQLite DataSource 의 JDBC URL 을 알 수 없다: " + dataSource.getClass().getName());
    }

    private static String quickCheck(Path file) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA quick_check")) {
            return rs.next() ? rs.getString(1) : "결과 없음";
        }
    }

    /** 앞선 실행이 중간에 죽어 남긴 {@code .partial} — 완성본이 아니므로 지운다. */
    private void deleteLeftoverPartials() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX + PARTIAL)).forEach(DatabaseBackupService::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[DB-BACKUP] 임시 파일을 지우지 못했다 {}: {}", p, e.getMessage());
        }
    }
}
