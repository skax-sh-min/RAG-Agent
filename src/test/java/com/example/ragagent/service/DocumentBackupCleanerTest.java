package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.15 — {@code documents/backup/} 보존 정책.
 *
 * <p>이 클래스가 존재하는 이유가 곧 검증 대상이다: 저장 상한이 이 폴더를 세지 않기로 했으므로,
 * 폴더를 실제로 묶어 주는 것이 여기 말고 없다. 그래서 "지워야 할 것을 지우는가"만큼 "지우면 안 되는
 * 것을 남기는가"(운영자 파일, 각 문서의 최신 1개)도 함께 고정한다.
 */
class DocumentBackupCleanerTest {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private static AppProperties props(Path dataDir, Integer retentionDays, Long backupMaxBytes) {
        return new AppProperties(
                dataDir.toString(), 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                new AppProperties.UploadConfig(DataSize.ofBytes(0), retentionDays,
                        backupMaxBytes == null ? null : DataSize.ofBytes(backupMaxBytes)));
    }

    private static Path backupDir(Path dataDir) {
        return dataDir.resolve("documents").resolve("backup");
    }

    /** {@code RagService.archiveSourceFile} 이 만드는 것과 같은 이름의 백업 파일. */
    private static Path archive(Path dataDir, String originalName, Instant archivedAt, int bytes) throws IOException {
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext  = dot > 0 ? originalName.substring(dot) : "";
        Path p = backupDir(dataDir).resolve(base + "_" + STAMP.format(archivedAt) + ext);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        return p;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    // ── 규칙 1 — 문서당 최신 1개 ────────────────────────────────────────────

    @Test
    @DisplayName("같은 원본 파일명의 백업은 최신 1개만 남는다 — 업로드/삭제 반복을 실제로 묶는 규칙")
    void keepsOnlyNewestPerDocument(@TempDir Path dataDir) throws IOException {
        Path oldest = archive(dataDir, "보고서.pdf", daysAgo(3), 10);
        Path middle = archive(dataDir, "보고서.pdf", daysAgo(2), 10);
        Path newest = archive(dataDir, "보고서.pdf", daysAgo(1), 10);
        Path other  = archive(dataDir, "다른문서.pdf", daysAgo(3), 10);

        assertThat(new DocumentBackupCleaner(props(dataDir, 30, 0L)).sweep()).isEqualTo(2);

        assertThat(newest).exists();
        assertThat(other).exists();     // 다른 문서는 자기 최신본을 유지한다
        assertThat(oldest).doesNotExist();
        assertThat(middle).doesNotExist();
    }

    @Test
    @DisplayName("파일명에 이미 타임스탬프가 들어 있어도 아카이브 스탬프만 떼어 같은 문서로 묶는다")
    void groupsCorrectlyWhenOriginalNameContainsATimestamp(@TempDir Path dataDir) throws IOException {
        Path older = archive(dataDir, "log_20240101_120000.txt", daysAgo(2), 10);
        Path newer = archive(dataDir, "log_20240101_120000.txt", daysAgo(1), 10);

        new DocumentBackupCleaner(props(dataDir, 30, 0L)).sweep();

        assertThat(newer).exists();
        assertThat(older).doesNotExist();
    }

    // ── 규칙 2 — 보관 일수 ─────────────────────────────────────────────────

    @Test
    @DisplayName("보관 일수를 넘긴 백업은 그 문서의 최신본이라도 삭제된다")
    void dropsExpiredEvenWhenNewestForItsDocument(@TempDir Path dataDir) throws IOException {
        Path stale  = archive(dataDir, "옛문서.pdf", daysAgo(31), 10);
        Path fresh  = archive(dataDir, "최근문서.pdf", daysAgo(29), 10);

        assertThat(new DocumentBackupCleaner(props(dataDir, 30, 0L)).sweep()).isEqualTo(1);

        assertThat(fresh).exists();
        assertThat(stale).doesNotExist();
    }

    @Test
    @DisplayName("보관 일수 0 은 기간 규칙만 끈다 — 최신 1개 규칙은 계속 적용된다")
    void zeroRetentionDisablesOnlyTheAgeRule(@TempDir Path dataDir) throws IOException {
        Path ancient = archive(dataDir, "문서.pdf", daysAgo(400), 10);
        Path newer   = archive(dataDir, "문서.pdf", daysAgo(399), 10);

        new DocumentBackupCleaner(props(dataDir, 0, 0L)).sweep();

        assertThat(newer).exists();          // 400일이 지나도 기간 규칙이 꺼져 있으면 남는다
        assertThat(ancient).doesNotExist();  // 그래도 중복은 정리된다
    }

    // ── 규칙 3 — 총 용량 ───────────────────────────────────────────────────

    @Test
    @DisplayName("용량 상한을 넘으면 오래된 것부터 지워 상한 아래로 내린다")
    void dropsOldestFirstUntilUnderSizeCap(@TempDir Path dataDir) throws IOException {
        Path a = archive(dataDir, "a.pdf", daysAgo(5), 400);
        Path b = archive(dataDir, "b.pdf", daysAgo(4), 400);
        Path c = archive(dataDir, "c.pdf", daysAgo(3), 400);   // 합계 1200

        new DocumentBackupCleaner(props(dataDir, 0, 900L)).sweep();

        assertThat(a).doesNotExist();   // 가장 오래된 것부터
        assertThat(b).exists();
        assertThat(c).exists();         // 남은 800 ≤ 900
    }

    @Test
    @DisplayName("용량 상한 0 은 용량 규칙만 끈다")
    void zeroSizeCapDisablesOnlyTheSizeRule(@TempDir Path dataDir) throws IOException {
        Path a = archive(dataDir, "a.pdf", daysAgo(5), 5_000);
        Path b = archive(dataDir, "b.pdf", daysAgo(4), 5_000);

        assertThat(new DocumentBackupCleaner(props(dataDir, 0, 0L)).sweep()).isZero();

        assertThat(a).exists();
        assertThat(b).exists();
    }

    // ── 지우면 안 되는 것 ──────────────────────────────────────────────────

    @Test
    @DisplayName("이 앱이 만들지 않은 파일은 어떤 규칙으로도 지우지 않는다 (용량 계산에는 포함)")
    void neverDeletesFilesThisAppDidNotWrite(@TempDir Path dataDir) throws IOException {
        Files.createDirectories(backupDir(dataDir));
        Path foreign = backupDir(dataDir).resolve("운영자가_직접_둔_파일.zip");
        Files.write(foreign, new byte[5_000]);
        Path ours = archive(dataDir, "a.pdf", daysAgo(400), 100);

        new DocumentBackupCleaner(props(dataDir, 30, 1_000L)).sweep();

        assertThat(foreign).exists();      // 상한을 넘겨도 남는다 — 우리가 만든 파일이 아니다
        assertThat(ours).doesNotExist();   // 기간 초과라 삭제
    }

    @Test
    @DisplayName("백업 디렉터리가 없으면 아무 일도 하지 않는다 (갓 설치한 배포)")
    void noBackupDirectoryIsANoOp(@TempDir Path dataDir) {
        assertThat(new DocumentBackupCleaner(props(dataDir, 30, null)).sweep()).isZero();
    }

    // ── 기본값 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미설정이면 30일 / 2GB 가 적용된다 — 백업은 아무도 보지 않으므로 '무제한'이 기본일 수 없다")
    void unsetMeansTheDefaultsNotUnlimited(@TempDir Path dataDir) throws IOException {
        AppProperties unset = props(dataDir, null, null);

        assertThat(unset.uploadSafe().backupRetentionDaysOrZero()).isEqualTo(30);
        assertThat(unset.uploadSafe().backupMaxBytes()).isEqualTo(2L * 1024 * 1024 * 1024);

        Path stale = archive(dataDir, "문서.pdf", daysAgo(31), 10);
        new DocumentBackupCleaner(unset).sweep();
        assertThat(stale).doesNotExist();
    }
}
