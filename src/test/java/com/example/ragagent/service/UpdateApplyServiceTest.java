package com.example.ragagent.service;

import com.example.ragagent.model.AboutInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 업데이트 적용 — 진짜 {@link HttpServer} 가 GitHub(릴리즈 JSON · jar · 체크섬)를 흉내 내므로 받기·크기 검사·해시 계산이 실제 HTTP 를
 * 지난다. 테스트 jar 는 코드로 만든다(매니페스트 {@code Start-Class} + {@code META-INF/build-info.properties}). DB 백업·재시작·인덱싱
 * 상태만 가짜다. 마지막 두 테스트 묶음은 런처와의 약속(체크섬 파일 읽기 · {@code result.properties} 읽기)이다.
 */
class UpdateApplyServiceTest {

    private static final String REPO = "owner/name";
    private static final String JAR_NAME = "rag-agent-0.5.7.jar";
    private static final String SUM_NAME = JAR_NAME + ".sha256";

    @TempDir Path tmp;

    private HttpServer server;
    private int port;

    // 서버가 내려 주는 것 — 테스트가 prepare() 전에 바꾼다
    private byte[] jarBytes;
    private String checksumText;
    private long declaredJarSize = -1;
    private boolean offerChecksumAsset = true;
    private long downloadDelayMillis = 0;
    private byte[] jarResponseOverride;          // 선언 크기와 다른 본문을 내려 줄 때

    private DatabaseBackupService backups;
    private AppRestarter restarter;
    private IndexingProgressService indexing;
    private ObjectProvider<EmbeddingRebuildService> rebuildProvider;
    private UpdateCheckService checks;
    private Path updateDir;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        updateDir = tmp.resolve("updates");
        backups = mock(DatabaseBackupService.class);
        when(backups.run(anyString())).thenReturn(new DatabaseBackupService.RunResult(
                "pre-update", Instant.now(), "memory-20261116-010203.db", 10, 5, null));
        when(backups.backupFile("memory-20261116-010203.db")).thenReturn(tmp.resolve("db-backups").resolve("memory-20261116-010203.db"));
        when(backups.databaseFile()).thenReturn(tmp.resolve("memory.db"));
        restarter = mock(AppRestarter.class);
        indexing = mock(IndexingProgressService.class);
        rebuildProvider = mock(ObjectProvider.class);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // ── 가짜 GitHub ──────────────────────────────────────────────────────────

    private static byte[] jar(String startClass, String buildVersion) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (startClass != null) mf.getMainAttributes().putValue("Start-Class", startClass);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bos, mf)) {
            if (buildVersion != null) {
                out.putNextEntry(new ZipEntry("META-INF/build-info.properties"));
                out.write(("build.name=rag-agent\nbuild.version=" + buildVersion + "\n").getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("BOOT-INF/classes/placeholder.txt"));
            out.write("x".repeat(2000).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] goodJar() throws IOException {
        return jar(UpdateApplyService.START_CLASS, "0.5.7");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sumFile(byte[] bytes) throws Exception {
        return sha256(bytes) + "  " + JAR_NAME + "\n";
    }

    /** 서버를 띄우고 현재 버전({@code current}) 기준의 서비스를 만든 뒤 확인을 한 번 돌려 latest 를 채운다. */
    private UpdateApplyService prepare(String current, boolean supervised) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        String dl = base + "/owner/name/releases/download/v0.5.7/";
        server.createContext("/repos/owner/name/releases/latest", ex -> {
            long jarSize = declaredJarSize >= 0 ? declaredJarSize : jarBytes.length;
            StringBuilder assets = new StringBuilder("{\"name\":\"" + JAR_NAME + "\",\"state\":\"uploaded\",\"size\":" + jarSize
                    + ",\"browser_download_url\":\"" + dl + JAR_NAME + "\"}");
            if (offerChecksumAsset) {
                assets.append(",{\"name\":\"").append(SUM_NAME).append("\",\"state\":\"uploaded\",\"size\":")
                        .append(checksumText.getBytes(StandardCharsets.UTF_8).length)
                        .append(",\"browser_download_url\":\"").append(dl).append(SUM_NAME).append("\"}");
            }
            reply(ex, 200, ("{\"tag_name\":\"v0.5.7\",\"name\":\"Release 0.5.7\",\"body\":\"notes\",\"draft\":false,\"prerelease\":false,"
                    + "\"assets\":[" + assets + "]}").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/owner/name/releases/download/v0.5.7/" + JAR_NAME, ex -> {
            if (downloadDelayMillis > 0) {
                try {
                    Thread.sleep(downloadDelayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            reply(ex, 200, jarResponseOverride != null ? jarResponseOverride : jarBytes);
        });
        server.createContext("/owner/name/releases/download/v0.5.7/" + SUM_NAME,
                ex -> reply(ex, 200, checksumText.getBytes(StandardCharsets.UTF_8)));
        server.start();

        AboutService about = mock(AboutService.class);
        when(about.info()).thenReturn(new AboutInfo("RAG Agent", current, null, "L", "u", "c", "21", "4"));
        checks = new UpdateCheckService(about, true, "0 0 9 * * *", REPO, base, "Asia/Seoul");
        checks.check("manual");
        return new UpdateApplyService(checks, backups, restarter, indexing, rebuildProvider, supervised, updateDir.toString(),
                tmp.resolve("data").toString());
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private UpdateApplyService ready() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        return prepare("0.5.6", true);
    }

    private UpdateApplyService.Job waitDone(UpdateApplyService svc) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            UpdateApplyService.Job j = svc.view().job();
            if (j != null && !j.running() || j != null && j.phase() == UpdateApplyService.Phase.RESTARTING) return j;
            Thread.sleep(20);
        }
        throw new AssertionError("업데이트 작업이 끝나지 않았다: " + svc.view().job());
    }

    // ── 정상 경로 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상: 받기 → 체크섬·구조 확인 → 백업 → pending 작성 → 종료 코드 42 로 재시작 요청")
    void happyPath() throws Exception {
        UpdateApplyService svc = ready();

        assertThat(svc.view().canApply()).isTrue();
        svc.start("0.5.7");
        UpdateApplyService.Job job = waitDone(svc);

        assertThat(job.phase()).isEqualTo(UpdateApplyService.Phase.RESTARTING);
        assertThat(job.version()).isEqualTo("0.5.7");
        assertThat(job.bytesDone()).isEqualTo(jarBytes.length);
        verify(backups).run("pre-update-0.5.7");
        verify(restarter).restart(UpdateApplyService.EXIT_CODE_UPDATE);
        assertThat(UpdateApplyService.EXIT_CODE_UPDATE).isEqualTo(42);

        assertThat(Files.readAllBytes(updateDir.resolve(JAR_NAME))).isEqualTo(jarBytes);
        assertThat(updateDir.resolve(JAR_NAME + ".partial")).doesNotExist();
        String pending = Files.readString(updateDir.resolve("pending.properties"));
        assertThat(pending).contains("version=0.5.7\n").contains("previousVersion=0.5.6\n")
                .contains("sha256=" + sha256(jarBytes) + "\n")
                .contains("jar=" + updateDir.resolve(JAR_NAME).toAbsolutePath() + "\n")
                .contains("dbBackup=" + tmp.resolve("db-backups").resolve("memory-20261116-010203.db") + "\n")
                .contains("dbFile=" + tmp.resolve("memory.db") + "\n")
                .contains("requestedAt=");
        assertThat(pending).doesNotContain("\r");     // 스크립트가 줄 끝의 CR 을 만나지 않게
    }

    @Test
    @DisplayName("pending.properties 는 BOM·이스케이프 없이 key=value 줄이다 — Windows 경로의 역슬래시가 그대로")
    void pendingIsPlainKeyValue() throws Exception {
        UpdateApplyService svc = ready();
        svc.start("0.5.7");
        waitDone(svc);

        byte[] raw = Files.readAllBytes(updateDir.resolve("pending.properties"));
        assertThat(raw[0]).isNotEqualTo((byte) 0xEF);
        for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
            assertThat(line).matches("[A-Za-z][A-Za-z0-9]*=.*");
        }
    }

    @Test
    @DisplayName("앞선 시도가 남긴 rag-agent-*.jar·.partial·.sha256 은 시작할 때 치우고 다른 파일은 건드리지 않는다")
    void leftoversAreCleanedButOtherFilesStay() throws Exception {
        UpdateApplyService svc = ready();
        Files.createDirectories(updateDir);
        Files.writeString(updateDir.resolve("rag-agent-0.5.1.jar"), "old");
        Files.writeString(updateDir.resolve("rag-agent-0.5.7.jar.partial"), "half");
        Files.writeString(updateDir.resolve("result.properties"), "status=ok\n");
        Files.writeString(updateDir.resolve("notes.txt"), "mine");

        svc.start("0.5.7");
        waitDone(svc);

        assertThat(updateDir.resolve("rag-agent-0.5.1.jar")).doesNotExist();
        assertThat(updateDir.resolve("result.properties")).exists();
        assertThat(updateDir.resolve("notes.txt")).exists();
    }

    // ── 검증 실패 — 아무것도 바꾸지 않는다 ───────────────────────────────────

    private void assertFailedBeforeAnyChange(UpdateApplyService svc, String messagePart) throws Exception {
        UpdateApplyService.Job job = waitDone(svc);
        assertThat(job.phase()).isEqualTo(UpdateApplyService.Phase.FAILED);
        assertThat(job.message()).contains(messagePart);
        assertThat(job.finishedAt()).isNotNull();
        verify(restarter, never()).restart(org.mockito.ArgumentMatchers.anyInt());
        assertThat(updateDir.resolve("pending.properties")).doesNotExist();
        assertThat(updateDir.resolve(JAR_NAME)).doesNotExist();
        assertThat(updateDir.resolve(JAR_NAME + ".partial")).doesNotExist();
    }

    @Test
    @DisplayName("체크섬이 다르면 적용하지 않는다 — 백업도 만들지 않는다")
    void checksumMismatch() throws Exception {
        jarBytes = goodJar();
        checksumText = "0".repeat(64) + "  " + JAR_NAME + "\n";
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "체크섬이 맞지 않습니다");
        verify(backups, never()).run(anyString());
    }

    @Test
    @DisplayName("이 앱의 jar 가 아니면(Start-Class 가 다름) 체크섬이 맞아도 거절")
    void wrongStartClass() throws Exception {
        jarBytes = jar("com.other.App", "0.5.7");
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "이 앱의 jar 가 아닙니다");
        verify(backups, never()).run(anyString());
    }

    @Test
    @DisplayName("jar 안의 빌드 버전이 릴리즈 태그와 다르면 거절 — 잘못 올린 릴리즈를 걸러 낸다")
    void tagAndJarVersionDiffer() throws Exception {
        jarBytes = jar(UpdateApplyService.START_CLASS, "0.5.8");
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "다릅니다");
    }

    @Test
    @DisplayName("jar 에 빌드 정보가 없으면 버전을 확인할 수 없어 거절")
    void noBuildInfo() throws Exception {
        jarBytes = jar(UpdateApplyService.START_CLASS, null);
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "빌드 정보");
    }

    @Test
    @DisplayName("jar(zip)가 아닌 파일은 거절한다")
    void notAJar() throws Exception {
        jarBytes = "this is not a zip file".repeat(50).getBytes(StandardCharsets.UTF_8);
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "jar");
    }

    @Test
    @DisplayName("받은 크기가 릴리즈가 알린 크기와 다르면(중간에 끊김) 거절")
    void shorterThanDeclared() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        declaredJarSize = jarBytes.length + 500;
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "다릅니다");
    }

    @Test
    @DisplayName("받은 크기가 알린 크기보다 크면 중간에 끊고 거절")
    void longerThanDeclared() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        jarResponseOverride = new byte[jarBytes.length + 5000];
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertFailedBeforeAnyChange(svc, "큽니다");
    }

    @Test
    @DisplayName("DB 백업이 실패하면 아무것도 바꾸지 않고 멈춘다 — 되돌릴 지점 없이는 올리지 않는다")
    void backupFailureStopsEverything() throws Exception {
        when(backups.run(anyString())).thenReturn(new DatabaseBackupService.RunResult(
                "pre-update", Instant.now(), null, 0, 5, "disk full"));
        UpdateApplyService svc = ready();

        svc.start("0.5.7");

        UpdateApplyService.Job job = waitDone(svc);
        assertThat(job.phase()).isEqualTo(UpdateApplyService.Phase.FAILED);
        assertThat(job.message()).contains("DB 백업에 실패").contains("disk full");
        verify(restarter, never()).restart(org.mockito.ArgumentMatchers.anyInt());
        assertThat(updateDir.resolve("pending.properties")).doesNotExist();
        assertThat(updateDir.resolve(JAR_NAME)).doesNotExist();     // 검증까지 마친 jar 도 남기지 않는다
    }

    @Test
    @DisplayName("재시작 요청이 실패하면 pending 을 거두고 실패로 남는다 — 런처가 나중에 엉뚱하게 적용하지 않게")
    void restartFailureRemovesPending() throws Exception {
        doThrow(new IllegalStateException("cannot exit")).when(restarter).restart(UpdateApplyService.EXIT_CODE_UPDATE);
        UpdateApplyService svc = ready();

        svc.start("0.5.7");

        // RESTARTING 을 지나 FAILED 로 가는 중이다 — 바로 읽으면 아직 RESTARTING 이다
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (svc.view().job().phase() != UpdateApplyService.Phase.FAILED && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(svc.view().job().phase()).isEqualTo(UpdateApplyService.Phase.FAILED);
        assertThat(updateDir.resolve("pending.properties")).doesNotExist();
        assertThat(updateDir.resolve(JAR_NAME)).doesNotExist();
    }

    // ── 시작 조건 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("런처 아래가 아니면 적용할 수 없다 — 사유를 보이고 start 는 거절")
    void notSupervised() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.6", false);

        UpdateApplyService.View view = svc.view();
        assertThat(view.supervised()).isFalse();
        assertThat(view.canApply()).isFalse();
        assertThat(view.reason()).contains("런처");
        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("런처");
        assertThat(svc.view().job()).isNull();
    }

    @Test
    @DisplayName("관리자가 본 버전이 아닌 것은 올리지 않는다 — 그사이 최신이 바뀐 경우")
    void staleVersionRequest() throws Exception {
        UpdateApplyService svc = ready();

        assertThatThrownBy(() -> svc.start("0.5.8")).isInstanceOf(IllegalStateException.class).hasMessageContaining("바뀌었습니다");
        assertThatThrownBy(() -> svc.start(null)).isInstanceOf(IllegalStateException.class);
        verify(backups, never()).run(anyString());
    }

    @Test
    @DisplayName("새 버전이 없으면(같은 버전) 시작할 수 없고 사유도 보이지 않는다")
    void nothingToUpdate() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        UpdateApplyService svc = prepare("0.5.7", true);

        assertThat(svc.view().canApply()).isFalse();
        assertThat(svc.view().reason()).isNull();
        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("새 버전이 없습니다");
    }

    @Test
    @DisplayName("릴리즈에 체크섬 파일이 없으면 알리기는 해도 자동 업데이트는 못 한다")
    void noChecksumAsset() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        offerChecksumAsset = false;
        UpdateApplyService svc = prepare("0.5.6", true);

        assertThat(checks.status().updateAvailable()).isTrue();
        assertThat(svc.view().canApply()).isFalse();
        assertThat(svc.view().reason()).contains("체크섬").contains(SUM_NAME);
        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("체크섬");
    }

    @Test
    @DisplayName("문서 인덱싱이 진행 중이면 시작하지 않는다 — 재시작이 작업을 끊는다")
    void indexingInProgress() throws Exception {
        when(indexing.activeWorkerCount()).thenReturn(1);
        UpdateApplyService svc = ready();

        assertThat(svc.view().canApply()).isFalse();
        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("인덱싱");
    }

    @Test
    @DisplayName("임베딩 재생성이 진행 중이면 시작하지 않는다")
    void embeddingRebuildInProgress() throws Exception {
        EmbeddingRebuildService rebuild = mock(EmbeddingRebuildService.class);
        when(rebuild.status()).thenReturn(new EmbeddingRebuildService.Status(1024, 1024, false, 10, 5, 5, true, null));
        when(rebuildProvider.getIfAvailable()).thenReturn(rebuild);
        UpdateApplyService svc = ready();

        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("임베딩 재생성");
    }

    @Test
    @DisplayName("처리되지 않은 pending 이 남아 있으면 새로 시작하지 않는다")
    void leftoverPending() throws Exception {
        UpdateApplyService svc = ready();
        Files.createDirectories(updateDir);
        Files.writeString(updateDir.resolve("pending.properties"), "version=0.5.7\n");

        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("아직 처리되지");
    }

    @Test
    @DisplayName("이미 진행 중이면 두 번째 시작은 거절한다")
    void secondStartIsRejected() throws Exception {
        jarBytes = goodJar();
        checksumText = sumFile(jarBytes);
        downloadDelayMillis = 700;
        UpdateApplyService svc = prepare("0.5.6", true);

        svc.start("0.5.7");

        assertThatThrownBy(() -> svc.start("0.5.7")).isInstanceOf(IllegalStateException.class).hasMessageContaining("진행 중");
        waitDone(svc);
    }

    // ── 체크섬 파일 읽기 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("체크섬 파일: sha256sum 출력(이중 공백·* 바이너리 표시·경로 접두)과 해시만 있는 한 줄을 읽는다")
    void parseChecksumFormats() {
        String h = "ab".repeat(32);
        assertThat(UpdateApplyService.parseChecksum(h + "  rag-agent-0.5.7.jar\n", "rag-agent-0.5.7.jar")).isEqualTo(h);
        assertThat(UpdateApplyService.parseChecksum(h + " *rag-agent-0.5.7.jar", "rag-agent-0.5.7.jar")).isEqualTo(h);
        assertThat(UpdateApplyService.parseChecksum(h + "  target/rag-agent-0.5.7.jar\r\n", "rag-agent-0.5.7.jar")).isEqualTo(h);
        assertThat(UpdateApplyService.parseChecksum(h + "  C:\\build\\rag-agent-0.5.7.jar", "rag-agent-0.5.7.jar")).isEqualTo(h);
        assertThat(UpdateApplyService.parseChecksum(h.toUpperCase() + "\n", "rag-agent-0.5.7.jar")).isEqualTo(h);   // 해시만 + 대문자
    }

    @Test
    @DisplayName("체크섬 파일에 여러 파일이 있으면 이 jar 의 줄만 쓴다 — 없으면 남의 해시를 쓰지 않고 실패")
    void parseChecksumPicksTheRightLine() {
        String a = "aa".repeat(32);
        String b = "bb".repeat(32);
        String text = a + "  other.jar\n" + b + "  rag-agent-0.5.7.jar\n";

        assertThat(UpdateApplyService.parseChecksum(text, "rag-agent-0.5.7.jar")).isEqualTo(b);
        assertThatThrownBy(() -> UpdateApplyService.parseChecksum(a + "  other.jar\n" + b + "  third.jar\n", "rag-agent-0.5.7.jar"))
                .isInstanceOf(UpdateApplyService.Failure.class).hasMessageContaining("찾지 못했습니다");
        assertThatThrownBy(() -> UpdateApplyService.parseChecksum(a + "\n" + b + "\n", "rag-agent-0.5.7.jar"))
                .isInstanceOf(UpdateApplyService.Failure.class);
        assertThatThrownBy(() -> UpdateApplyService.parseChecksum("not a checksum", "rag-agent-0.5.7.jar"))
                .isInstanceOf(UpdateApplyService.Failure.class);
        assertThatThrownBy(() -> UpdateApplyService.parseChecksum("", "rag-agent-0.5.7.jar"))
                .isInstanceOf(UpdateApplyService.Failure.class);
    }

    // ── 런처가 남긴 결과 읽기 ───────────────────────────────────────

    private UpdateApplyService serviceForResults() throws Exception {
        return ready();
    }

    private void writeResult(String text) throws IOException {
        Files.createDirectories(updateDir);
        Files.writeString(updateDir.resolve("result.properties"), text);
    }

    @Test
    @DisplayName("결과 파일이 없으면 null")
    void noResultFile() throws Exception {
        assertThat(serviceForResults().readResult("0.5.6")).isNull();
    }

    @Test
    @DisplayName("성공(ok)은 지금 실행 중인 버전과 같을 때만 보인다 — 더 새 버전이 뜨면 낡은 결과다")
    void okResultIsShownOnlyForTheRunningVersion() throws Exception {
        UpdateApplyService svc = serviceForResults();
        writeResult("status=ok\nversion=0.5.7\nreason=\ndbRestored=false\nat=2026-11-16T01:02:03Z\n");

        UpdateApplyService.LauncherResult shown = svc.readResult("0.5.7");
        assertThat(shown.status()).isEqualTo("ok");
        assertThat(shown.message()).contains("0.5.7").contains("업데이트했습니다");
        assertThat(shown.at()).isEqualTo(Instant.parse("2026-11-16T01:02:03Z"));
        assertThat(svc.readResult("0.5.8")).isNull();
        assertThat(svc.readResult("0.5.6")).isNull();
        assertThat(svc.readResult(null)).isNull();
    }

    @Test
    @DisplayName("롤백 결과는 다음 시도가 덮어쓸 때까지 보인다 — 사유와 DB 복원 여부를 풀어 쓴다")
    void rolledBackResult() throws Exception {
        UpdateApplyService svc = serviceForResults();
        writeResult("status=rolled-back\nversion=0.5.7\nreason=unhealthy-timeout\ndbRestored=true\nat=2026-11-16T01:02:03Z\n");

        UpdateApplyService.LauncherResult r = svc.readResult("0.5.6");

        assertThat(r.status()).isEqualTo("rolled-back");
        assertThat(r.dbRestored()).isTrue();
        assertThat(r.message()).contains("이전 버전으로 되돌렸습니다").contains("제한 시간").contains("DB 도").contains("복원했습니다");
        writeResult("status=rolled-back\nversion=0.5.7\nreason=exited-early\ndbRestored=false\n");
        assertThat(svc.readResult("0.5.6").message()).contains("종료됐습니다").contains("복원하지 못했습니다");
    }

    @Test
    @DisplayName("거절 결과(체크섬 불일치 등)도 사유를 문장으로 보인다")
    void rejectedResult() throws Exception {
        UpdateApplyService svc = serviceForResults();
        writeResult("status=rejected\nversion=0.5.7\nreason=checksum-mismatch\ndbRestored=false\n");

        assertThat(svc.readResult("0.5.6").message()).contains("걸러져").contains("체크섬이 맞지 않습니다");
        writeResult("status=rejected\nversion=0.5.7\nreason=missing-jar\n");
        assertThat(svc.readResult("0.5.6").message()).contains("받아 둔 jar 가 없습니다");
        writeResult("status=rejected\nversion=0.5.7\nreason=copy-failed\n");
        assertThat(svc.readResult("0.5.6").message()).contains("설치에 실패");
        writeResult("status=rejected\nversion=0.5.7\nreason=invalid-pending\n");
        assertThat(svc.readResult("0.5.6").message()).contains("올바르지 않습니다");
    }

    @Test
    @DisplayName("알 수 없는 사유 코드는 그대로 보이고, 깨진 파일·빈 status 는 null")
    void unknownOrBrokenResult() throws Exception {
        UpdateApplyService svc = serviceForResults();
        writeResult("status=rolled-back\nversion=0.5.7\nreason=something-new\ndbRestored=true\n");
        assertThat(svc.readResult("0.5.6").message()).contains("something-new");

        writeResult("garbage without equals\n");
        assertThat(svc.readResult("0.5.6")).isNull();
        writeResult("status=\nversion=0.5.7\n");
        assertThat(svc.readResult("0.5.6")).isNull();
    }

    @Test
    @DisplayName("view() 는 마지막 결과와 진행 작업을 함께 돌려준다")
    void viewIncludesLastResult() throws Exception {
        UpdateApplyService svc = serviceForResults();
        writeResult("status=rolled-back\nversion=0.5.7\nreason=exited-early\ndbRestored=true\n");

        UpdateApplyService.View view = svc.view();

        assertThat(view.lastResult()).isNotNull();
        assertThat(view.lastResult().status()).isEqualTo("rolled-back");
        assertThat(view.job()).isNull();
        assertThat(view.supervised()).isTrue();
    }
}
