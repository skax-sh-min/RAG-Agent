package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.StorageQuotaExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §6.15 — the deployment-wide storage cap.
 *
 * <p>The behaviours worth pinning are the ones a future change could silently undo: that the
 * default (no limit) never even looks at the disk, that the measurement covers the derived
 * artifacts and not just the uploaded originals, and that deleting frees space with no counter
 * involved.
 */
class StorageQuotaServiceTest {

    private static AppProperties props(Path dataDir, Long limitBytes) {
        return new AppProperties(
                dataDir.toString(), 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                limitBytes == null ? null : new AppProperties.UploadConfig(DataSize.ofBytes(limitBytes)));
    }

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    // ── 기본값 = 무제한 ────────────────────────────────────────────────────

    @Test
    @DisplayName("상한 미설정이면 아무리 큰 파일도 통과하고, 사용량을 재려고 디스크를 걷지도 않는다")
    void noLimit_acceptsAnything(@TempDir Path dataDir) {
        // 존재하지 않는 데이터 디렉터리를 가리켜도 통과한다 = 검사 경로가 디스크에 닿지 않았다는 뜻
        StorageQuotaService svc = new StorageQuotaService(props(dataDir.resolve("nope"), null));

        assertThat(svc.hasLimit()).isFalse();
        assertThat(svc.limitBytes()).isZero();
        assertThatCode(() -> svc.checkCanAccept(Long.MAX_VALUE, "huge.pdf")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상한 0 은 '무제한' 이지 '아무것도 못 받음' 이 아니다")
    void zeroLimit_meansUnlimited(@TempDir Path dataDir) {
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 0L));

        assertThat(svc.hasLimit()).isFalse();
        assertThatCode(() -> svc.checkCanAccept(1_000_000, "a.pdf")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("음수 상한은 uploadSafe() 가 0(무제한) 으로 정규화한다 — 설정 실수가 업로드를 막지 않는다")
    void negativeLimit_normalizesToUnlimited(@TempDir Path dataDir) {
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, -5L));

        assertThat(svc.limitBytes()).isZero();
        assertThatCode(() -> svc.checkCanAccept(1_000, "a.pdf")).doesNotThrowAnyException();
    }

    // ── 사용량 측정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("사용량은 원본만이 아니라 인덱싱 산출물(converted·images)까지 합산한다")
    void usedBytes_countsDerivedArtifactsToo(@TempDir Path dataDir) throws IOException {
        write(dataDir.resolve("documents/a.pdf"), 100);
        write(dataDir.resolve("documents/backup/a_2026.pdf"), 50);   // ↺ 재인덱싱이 남긴 백업도 디스크다
        write(dataDir.resolve("converted/a.md"), 200);
        write(dataDir.resolve("images/abc123/slide1.png"), 400);
        write(dataDir.resolve("images/submissions/deadbeef.png"), 30);
        // 업로드가 만들지 않는 것들은 세지 않는다
        write(dataDir.resolve("memory.db"), 10_000);
        write(dataDir.resolve("audit/audit.log"), 10_000);

        assertThat(new StorageQuotaService(props(dataDir, 1L)).usedBytes()).isEqualTo(780);
    }

    @Test
    @DisplayName("측정 대상 디렉터리가 아직 없으면 0 — 갓 설치한 배포도 그냥 동작한다")
    void usedBytes_missingDirsAreZero(@TempDir Path dataDir) {
        assertThat(new StorageQuotaService(props(dataDir, 1L)).usedBytes()).isZero();
    }

    // ── 거부/허용 경계 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("사용량 + 들어올 파일이 상한 이하면 통과, 넘으면 거부 (경계 포함)")
    void checkCanAccept_boundary(@TempDir Path dataDir) throws IOException {
        write(dataDir.resolve("documents/a.pdf"), 600);
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 1000L));

        assertThatCode(() -> svc.checkCanAccept(400, "b.pdf")).doesNotThrowAnyException();  // 정확히 1000
        assertThatThrownBy(() -> svc.checkCanAccept(401, "b.pdf"))
                .isInstanceOf(StorageQuotaExceededException.class);
    }

    @Test
    @DisplayName("거부 예외는 413/RAG-UP-002 와 함께 사용량·상한·파일명을 들고 온다 — 왜 막혔는지가 메시지에 있다")
    void checkCanAccept_rejectionCarriesNumbers(@TempDir Path dataDir) throws IOException {
        write(dataDir.resolve("documents/a.pdf"), 900);
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 1000L));

        assertThatThrownBy(() -> svc.checkCanAccept(200, "보고서.pdf"))
                .isInstanceOfSatisfying(StorageQuotaExceededException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(413);
                    assertThat(e.errorCode()).isEqualTo("RAG-UP-002");
                    assertThat(e.retryAfterSeconds()).isEqualTo(-1);   // 기다린다고 자리가 생기지 않는다
                    assertThat(e.usedBytes()).isEqualTo(900);
                    assertThat(e.limitBytes()).isEqualTo(1000);
                    assertThat(e.incomingBytes()).isEqualTo(200);
                    assertThat(e.getMessage()).contains("보고서.pdf");
                });
    }

    @Test
    @DisplayName("파일을 지우면 다음 검사에서 곧바로 자리가 난다 — 카운터가 아니라 디스크를 재기 때문")
    void deletionFreesSpaceImmediately(@TempDir Path dataDir) throws IOException {
        Path big = dataDir.resolve("documents/big.pdf");
        write(big, 900);
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 1000L));

        assertThatThrownBy(() -> svc.checkCanAccept(200, "b.pdf"))
                .isInstanceOf(StorageQuotaExceededException.class);

        Files.delete(big);

        assertThatCode(() -> svc.checkCanAccept(200, "b.pdf")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 상한을 넘긴 상태(소프트 캡의 초과분)에서는 0바이트 요청도 거부된다")
    void alreadyOverLimit_rejectsEvenTinyUploads(@TempDir Path dataDir) throws IOException {
        write(dataDir.resolve("images/abc/huge.png"), 5_000);   // 인덱싱이 만들어 낸 초과분
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 1000L));

        assertThatThrownBy(() -> svc.checkCanAccept(1, "tiny.md"))
                .isInstanceOf(StorageQuotaExceededException.class);
    }

    // ── 표시용 캐시 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("표시용 사용량은 TTL 동안 재사용되고, 검사 경로는 그 값을 쓰지 않는다")
    void displayReadingIsCached_butTheCheckIsNot(@TempDir Path dataDir) throws IOException {
        write(dataDir.resolve("documents/a.pdf"), 100);
        StorageQuotaService svc = new StorageQuotaService(props(dataDir, 1000L));

        assertThat(svc.usedBytesCached()).isEqualTo(100);

        write(dataDir.resolve("documents/b.pdf"), 850);

        // 화면 값은 아직 옛것 (게스트가 /settings 를 두드려도 매번 디스크를 걷지 않는다)
        assertThat(svc.usedBytesCached()).isEqualTo(100);
        // 검사는 매번 다시 잰다 — 950 + 100 > 1000
        assertThatThrownBy(() -> svc.checkCanAccept(100, "c.pdf"))
                .isInstanceOf(StorageQuotaExceededException.class);
        assertThat(svc.usedBytes()).isEqualTo(950);
    }

    // ── 표시 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("formatBytes — 단위를 올려가며 사람이 읽는 형태로 (/settings 행과 거부 메시지가 같은 함수를 쓴다)")
    void formatBytes_isHumanReadable() {
        assertThat(StorageQuotaService.formatBytes(0)).isEqualTo("0 B");
        assertThat(StorageQuotaService.formatBytes(512)).isEqualTo("512 B");
        assertThat(StorageQuotaService.formatBytes(1536)).isEqualTo("1.5 KB");
        assertThat(StorageQuotaService.formatBytes(20L * 1024 * 1024 * 1024)).isEqualTo("20.0 GB");
        assertThat(StorageQuotaService.formatBytes(2L * 1024 * 1024 * 1024 * 1024)).isEqualTo("2.0 TB");
    }
}
