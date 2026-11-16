package com.example.ragagent.service;

import com.example.ragagent.config.HttpClientTimeouts;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 관리자가 「업데이트」 를 누르면 새 jar 를 받아 검증하고, 업데이트 전 DB 백업을 만든 뒤, 런처에 교체를 맡기고 앱을 끝낸다.
 * <b>이 클래스는 실행 중인 jar 를 직접 바꾸지 않는다</b> — 실행 중인 JVM 은 자기 jar 를 못 바꾸고(Windows 는 파일이 잠긴다) 바꾸다 죽으면
 * 되돌릴 사람이 없다. 그래서 순서는 다음과 같다.
 * <ol>
 *   <li>체크섬 파일과 jar 를 {@code UPDATE_DIR} 에 받는다(크기는 릴리즈가 알린 값과 정확히 같아야 한다).</li>
 *   <li><b>SHA-256 이 체크섬 파일과 같은지</b> 보고, jar 가 이 앱의 것인지(매니페스트 {@code Start-Class}) 와 jar 안의 빌드 버전이
 *       릴리즈 태그와 같은지({@code META-INF/build-info.properties}) 본다 — 태그와 파일이 어긋난 릴리즈를 걸러 낸다.</li>
 *   <li>{@link DatabaseBackupService} 로 {@code pre-update-<버전>} 백업을 만든다. <b>실패하면 아무것도 바꾸지 않고 멈춘다</b>
 *       (되돌릴 지점 없이는 새 버전을 올리지 않는다 — 마이그레이션은 앞으로만 간다).</li>
 *   <li>{@code pending.properties} 를 쓰고 종료 코드 {@link #EXIT_CODE_UPDATE} 로 끝낸다. 런처가 jar 를 바꾸고 새 버전을 띄우며,
 *       정상 기동(헬스)하지 못하면 이전 jar 와 위의 DB 백업으로 되돌린다.</li>
 * </ol>
 * 런처 아래에서 돌 때만({@code RAG_SUPERVISED}) 적용할 수 있다. 결과는 스크립트가 쓰는 {@code result.properties} 로 앱이 다시 뜬
 * 뒤 {@link View#lastResult()} 에서 읽는다.
 *
 * <p>체크섬은 같은 릴리즈에 올라온 파일이라 <b>손상·중간 끊김·CDN 변조</b>를 막지만 GitHub 계정이 탈취된 경우까지 막지는 못한다(서명은 쓰지
 * 않는다). 그래서 적용은 관리자의 명시적 확인(버전 지정)이 있을 때만 하고 감사 로그에 남긴다.
 */
@Service
public class UpdateApplyService {

    private static final Logger log = LoggerFactory.getLogger(UpdateApplyService.class);

    /** 런처와 맞춘 값 — 이 코드로 끝나면 "업데이트를 위한 종료"이니 pending 을 적용하고 다시 띄운다. */
    public static final int EXIT_CODE_UPDATE = 42;
    static final String START_CLASS = "com.example.ragagent.RagAgentApplication";
    static final long MAX_JAR_BYTES = 1_000_000_000L;
    static final long MIN_FREE_MARGIN_BYTES = 200L * 1024 * 1024;
    static final String PENDING = "pending.properties";
    static final String TRIAL = "trial.properties";
    static final String RESULT = "result.properties";
    private static final Pattern HASH_LINE = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b(?:\\s+\\*?(\\S+))?");

    public enum Phase { IDLE, DOWNLOADING, VERIFYING, BACKING_UP, RESTARTING, FAILED }

    /** 진행 중이거나 마지막으로 끝난 적용 작업. 메모리에만 있다(재기동하면 {@link LauncherResult} 가 이어받는다). */
    public record Job(Phase phase, String version, long bytesDone, long bytesTotal, String message,
                      Instant startedAt, Instant finishedAt) {
        boolean running() {
            return phase == Phase.DOWNLOADING || phase == Phase.VERIFYING || phase == Phase.BACKING_UP || phase == Phase.RESTARTING;
        }
    }

    /** 런처가 남긴 마지막 교체 결과({@code result.properties}) — {@code message} 는 사유 코드를 풀어 쓴 문장이다. */
    public record LauncherResult(String status, String version, String reason, boolean dbRestored, Instant at, String message) {}

    /** {@code /admin} 카드가 그리는 것. {@code reason} 은 새 버전이 있는데 적용할 수 없을 때만 채워진다. */
    public record View(boolean supervised, boolean canApply, String reason, Job job, LauncherResult lastResult) {}

    /** 사용자에게 그대로 보일 사유가 있는 실패. */
    static final class Failure extends RuntimeException {
        Failure(String message) {
            super(message);
        }
    }

    private final UpdateCheckService updates;
    private final DatabaseBackupService backups;
    private final AppRestarter restarter;
    private final IndexingProgressService indexing;
    private final ObjectProvider<EmbeddingRebuildService> rebuild;
    private final boolean supervised;
    private final Path dir;
    private final RestClient client;

    private volatile Job job;

    public UpdateApplyService(UpdateCheckService updates,
                              DatabaseBackupService backups,
                              AppRestarter restarter,
                              IndexingProgressService indexing,
                              ObjectProvider<EmbeddingRebuildService> rebuild,
                              @Value("${app.update.supervised:false}") boolean supervised,
                              @Value("${app.update.dir:}") String updateDir,
                              @Value("${app.data-dir:./data}") String dataDir) {
        this.updates = updates;
        this.backups = backups;
        this.restarter = restarter;
        this.indexing = indexing;
        this.rebuild = rebuild;
        this.supervised = supervised;
        this.dir = (updateDir == null || updateDir.isBlank() ? Path.of(dataDir).resolve("updates") : Path.of(updateDir))
                .toAbsolutePath();
        this.client = HttpClientTimeouts.restClientBuilder(5, 60).build();
    }

    // ── 상태 ──────────────────────────────────────────────────────────────────

    public View view() {
        UpdateCheckService.Status s = updates.status();
        String blocker = blocker(s);
        boolean available = s.updateAvailable();
        return new View(supervised, available && blocker == null, available ? blocker : null, job, readResult(s.currentVersion()));
    }

    /** 지금 적용할 수 없는 이유 — 없으면 null. */
    private String blocker(UpdateCheckService.Status s) {
        if (!s.enabled()) return "새 버전 확인이 꺼져 있습니다.";
        if (!supervised) {
            return "런처(rag-agent-launcher.jar)로 실행 중이 아니라 자동 업데이트를 할 수 없습니다 — 운영자 매뉴얼 §6.2.3 의 절차로 직접 교체하세요.";
        }
        Job j = job;
        if (j != null && j.running()) return "이미 업데이트가 진행 중입니다.";
        if (s.latest() != null && s.latest().checksum() == null) {
            return "릴리즈에 체크섬 파일(" + (s.latest().jar() == null ? "<jar>" : s.latest().jar().name()) + ".sha256)이 없어 자동 업데이트를 할 수 없습니다.";
        }
        if (Files.exists(dir.resolve(PENDING))) return "이전 업데이트 요청이 아직 처리되지 않았습니다(런처가 교체하지 않았습니다).";
        if (indexing.activeWorkerCount() > 0) return "문서 인덱싱이 진행 중입니다 — 끝난 뒤 다시 시도하세요.";
        EmbeddingRebuildService r = rebuild.getIfAvailable();
        if (r != null && r.status().running()) return "임베딩 재생성이 진행 중입니다 — 끝난 뒤 다시 시도하세요.";
        return null;
    }

    // ── 시작 ──────────────────────────────────────────────────────────────────

    /**
     * 업데이트를 시작한다(받기 → 검증 → 백업 → 재시작은 가상 스레드에서 이어진다). {@code version} 은 관리자가 화면에서 본 최신 버전이며
     * 그사이 최신이 바뀌었으면 거절한다 — 확인하지 않은 버전을 올리지 않는다. 조건이 안 맞으면 {@link IllegalStateException}(사유 문장).
     */
    public synchronized Job start(String version) {
        UpdateCheckService.Status s = updates.status();
        if (!s.updateAvailable() || s.latest() == null) throw new IllegalStateException("업데이트할 새 버전이 없습니다.");
        if (version == null || !version.equals(s.latest().version())) {
            throw new IllegalStateException("최신 버전이 바뀌었습니다(요청 " + version + ", 현재 " + s.latest().version() + ") — 화면을 새로 고쳐 다시 확인하세요.");
        }
        String blocker = blocker(s);
        if (blocker != null) throw new IllegalStateException(blocker);

        UpdateCheckService.Release release = s.latest();
        String from = s.currentVersion();
        Job started = new Job(Phase.DOWNLOADING, release.version(), 0, release.jar().size(), "체크섬 파일을 받는 중", Instant.now(), null);
        job = started;
        Thread.ofVirtual().name("update-apply").start(MdcPropagation.wrap(() -> run(release, from)));
        return started;
    }

    private void run(UpdateCheckService.Release release, String fromVersion) {
        Path partial = dir.resolve(release.jar().name() + ".partial");
        Path jarFile = dir.resolve(release.jar().name());
        boolean pendingWritten = false;
        try {
            Files.createDirectories(dir);
            cleanLeftovers();
            checkFreeSpace(release.jar().size());

            progress(Phase.DOWNLOADING, release, 0, "체크섬 파일을 받는 중");
            String expected = parseChecksum(downloadText(release.checksum()), release.jar().name());

            progress(Phase.DOWNLOADING, release, 0, "jar 를 받는 중");
            String actual = downloadJar(release, partial);

            progress(Phase.VERIFYING, release, release.jar().size(), "체크섬과 jar 구조를 확인하는 중");
            if (!actual.equalsIgnoreCase(expected)) {
                throw new Failure("체크섬이 맞지 않습니다 — 받은 파일이 릴리즈의 것과 다릅니다(손상 또는 변조). 적용하지 않았습니다.");
            }
            inspectJar(partial, release.version());
            Files.move(partial, jarFile, StandardCopyOption.REPLACE_EXISTING);

            progress(Phase.BACKING_UP, release, release.jar().size(), "업데이트 전 DB 백업을 만드는 중");
            DatabaseBackupService.RunResult backup = backups.run("pre-update-" + release.version());
            if (!backup.ok()) {
                throw new Failure("업데이트 전 DB 백업에 실패해 중단했습니다(되돌릴 지점 없이는 올리지 않습니다): " + backup.error());
            }

            writePending(release, fromVersion, jarFile, actual, backups.backupFile(backup.file()), backups.databaseFile());
            pendingWritten = true;
            progress(Phase.RESTARTING, release, release.jar().size(), "다시 시작하는 중 — 새 버전이 뜨면 이 화면을 새로 고치세요");
            log.warn("[UPDATE-APPLY] {} → {} 교체를 런처에 맡긴다 (백업 {})", fromVersion, release.version(), backup.file());
            restarter.restart(EXIT_CODE_UPDATE);
        } catch (Failure e) {
            fail(release, e.getMessage(), partial, pendingWritten);
        } catch (Exception e) {
            log.error("[UPDATE-APPLY] 업데이트 중 예기치 않은 오류", e);
            fail(release, "예기치 않은 오류로 중단했습니다 (" + e.getClass().getSimpleName() + ": " + UpdateCheckService.abbreviate(e.getMessage(), 200) + ")",
                    partial, pendingWritten);
        }
    }

    private void progress(Phase phase, UpdateCheckService.Release release, long done, String message) {
        Job j = job;
        job = new Job(phase, release.version(), done, release.jar().size(), message, j == null ? Instant.now() : j.startedAt(), null);
    }

    private void fail(UpdateCheckService.Release release, String message, Path partial, boolean pendingWritten) {
        log.warn("[UPDATE-APPLY] 실패 — {}", message);
        try {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(dir.resolve(release.jar().name()));     // 검증까지 마친 jar 도 — 적용하지 않을 파일이다
            if (pendingWritten) Files.deleteIfExists(dir.resolve(PENDING));
        } catch (IOException e) {
            log.warn("[UPDATE-APPLY] 임시 파일을 지우지 못했다: {}", e.getMessage());
        }
        Job j = job;
        job = new Job(Phase.FAILED, release.version(), j == null ? 0 : j.bytesDone(), release.jar().size(), message,
                j == null ? Instant.now() : j.startedAt(), Instant.now());
    }

    // ── 내려받기 ──────────────────────────────────────────────────────────────

    private void checkFreeSpace(long jarSize) throws IOException {
        long usable = Files.getFileStore(dir).getUsableSpace();
        if (usable < jarSize * 2 + MIN_FREE_MARGIN_BYTES) {
            throw new Failure("업데이트 폴더(" + dir + ")의 빈 공간이 부족합니다 — 필요 " + ((jarSize * 2 + MIN_FREE_MARGIN_BYTES) >> 20) + " MB, 남음 " + (usable >> 20) + " MB.");
        }
    }

    /** 앞선 시도가 남긴 {@code rag-agent-*.jar}·{@code .partial}·{@code .sha256} — 이 작업이 만드는 이름만 지운다. */
    private void cleanLeftovers() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith("rag-agent-") && (n.endsWith(".jar") || n.endsWith(".partial") || n.endsWith(".sha256"))) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private String downloadText(UpdateCheckService.Asset asset) {
        byte[] body = client.get().uri(URI.create(asset.url()))
                .header("User-Agent", "update-check")
                .exchange((req, res) -> {
                    int code = res.getStatusCode().value();
                    if (code != 200) throw new Failure("체크섬 파일을 받지 못했습니다 (HTTP " + code + ")");
                    return res.getBody().readNBytes(UpdateCheckService.MAX_CHECKSUM_BYTES + 1);
                });
        if (body.length > UpdateCheckService.MAX_CHECKSUM_BYTES) throw new Failure("체크섬 파일이 너무 큽니다.");
        return new String(body, StandardCharsets.UTF_8);
    }

    /** jar 를 {@code partial} 에 받으며 SHA-256 을 계산한다. 받은 크기는 릴리즈가 알린 크기와 정확히 같아야 한다. */
    private String downloadJar(UpdateCheckService.Release release, Path partial) {
        UpdateCheckService.Asset asset = release.jar();
        return client.get().uri(URI.create(asset.url()))
                .header("User-Agent", "update-check")
                .header("Accept", "application/octet-stream")
                .exchange((req, res) -> {
                    int code = res.getStatusCode().value();
                    if (code != 200) throw new Failure("jar 를 받지 못했습니다 (HTTP " + code + ")");
                    long limit = Math.min(MAX_JAR_BYTES, asset.size());
                    MessageDigest md = sha256();
                    long total = 0;
                    try (InputStream in = res.getBody();
                         OutputStream out = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                                 StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            total += n;
                            if (total > limit) throw new Failure("받은 크기가 릴리즈가 알린 크기(" + asset.size() + " bytes)보다 큽니다.");
                            md.update(buf, 0, n);
                            out.write(buf, 0, n);
                            Job j = job;
                            if (j != null) job = new Job(j.phase(), j.version(), total, j.bytesTotal(), j.message(), j.startedAt(), null);
                        }
                    }
                    if (total != asset.size()) {
                        throw new Failure("받은 크기(" + total + " bytes)가 릴리즈가 알린 크기(" + asset.size() + " bytes)와 다릅니다 — 중간에 끊겼습니다.");
                    }
                    return HexFormat.of().formatHex(md.digest());
                });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 검증 ──────────────────────────────────────────────────────────────────

    /**
     * 체크섬 파일에서 {@code jarName} 의 해시를 읽는다. {@code sha256sum} 출력({@code <해시>  <파일>} 또는 {@code <해시> *<파일>})을 받고,
     * 해시 한 줄뿐인 파일도 받는다. 여러 파일이 들어 있는데 이 jar 의 줄이 없으면 실패한다(남의 해시를 쓰지 않는다).
     */
    static String parseChecksum(String text, String jarName) {
        List<String[]> found = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher m = HASH_LINE.matcher(line.strip());
            if (m.find()) found.add(new String[]{m.group(1).toLowerCase(Locale.ROOT), m.group(2)});
        }
        for (String[] f : found) {
            if (f[1] != null && baseName(f[1]).equals(jarName)) return f[0];
        }
        if (found.size() == 1 && found.get(0)[1] == null) return found.get(0)[0];
        throw new Failure("체크섬 파일에서 " + jarName + " 의 SHA-256 을 찾지 못했습니다.");
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** jar 가 이 앱의 것이고({@code Start-Class}) 안의 빌드 버전이 릴리즈 태그와 같은지 본다. */
    static void inspectJar(Path jar, String expectedVersion) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) throw new Failure("jar 에 매니페스트가 없습니다 — 이 앱의 jar 가 아닙니다.");
            String startClass;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                startClass = new Manifest(new java.io.ByteArrayInputStream(in.readNBytes(256 * 1024)))
                        .getMainAttributes().getValue("Start-Class");
            }
            if (!START_CLASS.equals(startClass)) throw new Failure("이 앱의 jar 가 아닙니다 (Start-Class=" + startClass + ").");
            ZipEntry info = zip.getEntry("META-INF/build-info.properties");
            if (info == null) throw new Failure("jar 에 빌드 정보(build-info.properties)가 없어 버전을 확인할 수 없습니다.");
            Properties p = new Properties();
            try (InputStream in = zip.getInputStream(info)) {
                p.load(new java.io.ByteArrayInputStream(in.readNBytes(64 * 1024)));
            }
            String inJar = p.getProperty("build.version");
            AppVersion a = AppVersion.parse(inJar).orElse(null);
            AppVersion b = AppVersion.parse(expectedVersion).orElse(null);
            if (a == null || b == null || !a.equals(b)) {
                throw new Failure("릴리즈 태그의 버전(" + expectedVersion + ")과 jar 안의 버전(" + inJar + ")이 다릅니다 — 잘못 올린 릴리즈일 수 있어 적용하지 않았습니다.");
            }
        } catch (ZipException e) {
            throw new Failure("받은 파일이 올바른 jar(zip)가 아닙니다 — " + e.getMessage());
        } catch (IOException e) {
            throw new Failure("받은 jar 를 읽지 못했습니다 — " + e.getMessage());
        }
    }

    // ── 런처와의 약속 ───────────────────────────────────────────────

    /** {@code pending.properties} — 런처가 읽는다. 줄 단위 {@code key=value}(이스케이프 없음 — 스크립트가 셸·PowerShell 로 읽는다). */
    private void writePending(UpdateCheckService.Release release, String fromVersion, Path jarFile, String sha256,
                              Path dbBackup, Path dbFile) throws IOException {
        String body = String.join("\n",
                "version=" + release.version(),
                "previousVersion=" + (fromVersion == null ? "" : fromVersion),
                "jar=" + jarFile.toAbsolutePath(),
                "sha256=" + sha256,
                "dbBackup=" + dbBackup,
                "dbFile=" + dbFile,
                "requestedAt=" + Instant.now()) + "\n";
        Path tmp = dir.resolve(PENDING + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(PENDING), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 런처가 남긴 마지막 결과. 성공({@code ok}) 은 <b>지금 실행 중인 버전과 같을 때만</b> 보인다(더 새 버전이 뜨면 낡은 결과다).
     * 롤백·거절은 다음 시도가 덮어쓸 때까지 보인다.
     */
    LauncherResult readResult(String currentVersion) {
        Path file = dir.resolve(RESULT);
        if (!Files.isRegularFile(file)) return null;
        try {
            Properties p = new Properties();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) p.setProperty(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
            String status = p.getProperty("status", "");
            String version = p.getProperty("version", "");
            if (status.isBlank()) return null;
            if ("ok".equals(status) && !(currentVersion != null && AppVersion.parse(version).map(v -> v.equals(AppVersion.parse(currentVersion).orElse(null))).orElse(false))) {
                return null;
            }
            Instant at = null;
            try {
                at = Instant.parse(p.getProperty("at", ""));
            } catch (RuntimeException ignored) {
                // 표시용
            }
            boolean dbRestored = "true".equalsIgnoreCase(p.getProperty("dbRestored", "false"));
            String reason = p.getProperty("reason", "");
            return new LauncherResult(status, version, reason, dbRestored, at, describe(status, version, reason, dbRestored));
        } catch (IOException | RuntimeException e) {
            log.warn("[UPDATE-APPLY] 결과 파일을 읽지 못했다 {}: {}", file, e.getMessage());
            return null;
        }
    }

    static String describe(String status, String version, String reason, boolean dbRestored) {
        return switch (status) {
            case "ok" -> version + " 로 업데이트했습니다.";
            case "rolled-back" -> version + " 업데이트에 실패해 이전 버전으로 되돌렸습니다 — " + switch (reason) {
                case "unhealthy-timeout" -> "새 버전이 제한 시간 안에 정상 기동하지 못했습니다";
                case "exited-early" -> "새 버전이 기동 중에 종료됐습니다";
                default -> reason.isBlank() ? "원인을 알 수 없습니다" : reason;
            } + (dbRestored ? ". DB 도 업데이트 전 백업으로 복원했습니다." : ". DB 는 복원하지 못했습니다 — 로그를 확인하세요.");
            case "rejected" -> version + " 은(는) 교체 직전 확인에서 걸러져 적용하지 않았습니다 — " + switch (reason) {
                case "checksum-mismatch" -> "체크섬이 맞지 않습니다";
                case "missing-jar" -> "받아 둔 jar 가 없습니다";
                case "copy-failed" -> "jar 설치에 실패했습니다(같은 버전 이름에 다른 jar 가 이미 있을 수 있습니다)";
                case "invalid-pending" -> "업데이트 요청의 내용(버전 이름·jar 위치)이 올바르지 않습니다";
                default -> reason.isBlank() ? "원인을 알 수 없습니다" : reason;
            } + ".";
            default -> status + " " + version;
        };
    }
}
