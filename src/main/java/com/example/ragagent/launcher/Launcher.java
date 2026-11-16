package com.example.ragagent.launcher;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * RAG Agent 런처 — 앱을 <b>자식 프로세스로</b> 띄우고 지켜보며, 앱이 요청한 업데이트를 교체·검증·롤백한다.
 *
 * <p><b>왜 앱 밖에 이런 프로세스가 필요한가</b>: 실행 중인 JVM 은 자기 jar 를 못 바꾸고, 새 버전이 뜨지 못하면 되돌릴 사람이 없다. 그래서 앱은
 * 새 jar 를 <i>받고 검증하고 DB 를 백업</i>한 뒤 {@code pending.properties} 를 쓰고 종료 코드 {@value #EXIT_UPDATE} 로 끝나며, 이 런처가 이어받는다.
 * <ol>
 *   <li>pending 의 jar 를 SHA-256 으로 <b>다시</b> 확인하고 {@code versions/<버전>/} 에 설치한 뒤 {@code current.txt} 를 새 버전으로 바꾼다
 *       (이전 버전은 파일째 남는다).</li>
 *   <li>새 버전을 띄우고 {@code /api/v1/health} 가 200 으로 답하는지 본다(시험 — trial).</li>
 *   <li>죽거나 제한 시간 안에 답하지 못하면 <b>포인터를 이전 버전으로 되돌리고 업데이트 전 DB 백업을 복원</b>(-wal/-shm 삭제)한 뒤 이전 버전을 다시
 *       띄운다. 마이그레이션은 앞으로만 가서 이전 jar 가 새 버전이 손댄 DB 로는 못 뜰 수 있기 때문이고, 한 번도 정상 응답하지 못한 버전은 사용자
 *       요청을 받은 적이 없어 복원이 잃는 데이터가 없다.</li>
 * </ol>
 * 앱이 비정상 종료하면 다시 띄운다(제한 횟수 안에서).
 *
 * <p>앱과의 약속 — 파일 형식은 {@link Props}, 종료 코드 {@value #EXIT_UPDATE}, 앱에 넘기는 환경변수
 * {@code RAG_SUPERVISED=1} · {@code UPDATE_DIR} · {@code RAG_LAUNCHER_PID}/{@code RAG_LAUNCHER_STARTED}(앱이 런처를 지켜보다 사라지면 스스로 끝난다) ·
 * {@code RAG_LAUNCHER_PROTOCOL}/{@code RAG_LAUNCHER_VERSION}(앱이 새 jar 의 {@code Min-Launcher-Protocol} 과 견주고 화면에 보인다).
 * 이 패키지는 JDK 만 쓴다.
 */
public final class Launcher {

    /** 앱이 "업데이트를 위해 끝난다"는 뜻으로 쓰는 종료 코드 — {@code UpdateApplyService.EXIT_CODE_UPDATE} 와 같은 값이다. */
    public static final int EXIT_UPDATE = 42;

    /**
     * 앱과의 약속(파일 형식·종료 코드·환경변수)의 세대. 앱 jar 의 {@code Min-Launcher-Protocol} 이 이보다 크면 이 런처는 그 버전을 설치하지
     * 않는다 — 옛 런처가 모르는 방식을 요구하는 업데이트를 조용히 깨뜨리지 않으려고 있다. <b>약속이 호환되지 않게 바뀔 때만 올린다.</b>
     */
    public static final int PROTOCOL = 1;

    /** 이 런처의 버전 — 런처 jar 매니페스트의 {@code Implementation-Version}. IDE·테스트처럼 jar 가 아니면 {@code dev}. */
    static String version() {
        String v = Launcher.class.getPackage() == null ? null : Launcher.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    static final String USAGE = String.join("\n",
            "RAG Agent launcher - runs the application and applies the updates it stages.",
            "",
            "Usage: java -jar rag-agent-<version>-launcher.jar [--home DIR] [--jar FILE]",
            "  --home DIR   install directory (versions/, current.txt, .env, data/). Default: the current directory.",
            "  --jar FILE   first install only: the application jar to install as the first version.",
            "               Without it the launcher looks for rag-agent*.jar in the install directory, then in target/.",
            "  -v, --version  print the launcher version and protocol",
            "  -h, --help   this text",
            "",
            "Settings come from the environment and DIR/.env (.env wins; its values are passed on to the application):",
            "  JAVA_BIN JAVA_OPTS DATA_DIR UPDATE_DIR SERVER_PORT RAG_HEALTH_URL",
            "  RAG_HEALTH_TIMEOUT (180 s)  RAG_HEALTH_INTERVAL (2 s)  RAG_RESTART_DELAY (5 s)",
            "  RAG_CRASH_LIMIT (5)  RAG_CRASH_WINDOW (300 s)  RAG_KEEP_VERSIONS (3)",
            "Exit codes: 0 stopped normally, 1 gave up or cannot run, 2 bad usage.");

    private final LauncherConfig cfg;
    private final Layout layout;
    private final PrintStream out;
    private final Path pending;
    private final Path trial;
    private final Path result;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private volatile Process child;
    private volatile boolean stopping;

    Launcher(LauncherConfig cfg, PrintStream out) {
        this.cfg = cfg;
        this.layout = new Layout(cfg.home);
        this.out = out;
        this.pending = cfg.updateDir.resolve("pending.properties");
        this.trial = cfg.updateDir.resolve("trial.properties");
        this.result = cfg.updateDir.resolve("result.properties");
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.getenv(), System.out, true));
    }

    /** 인자를 해석하고 실행한다. {@code installHook} 은 SIGTERM/Ctrl+C 때 자식을 먼저 멈추게 하는 종료 훅 — 테스트는 끈다. */
    static int execute(String[] args, Map<String, String> env, PrintStream out, boolean installHook) {
        Path home = Path.of("").toAbsolutePath();
        Path jar = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return 0;
                }
                case "-v", "--version" -> {
                    out.println("rag-agent launcher " + version() + " (protocol " + PROTOCOL + ")");
                    return 0;
                }
                case "--home" -> {
                    if (++i >= args.length) return usage(out, "--home needs a directory");
                    home = Path.of(args[i]).toAbsolutePath();
                }
                case "--jar" -> {
                    if (++i >= args.length) return usage(out, "--jar needs a file");
                    jar = Path.of(args[i]).toAbsolutePath();
                }
                default -> {
                    return usage(out, "unknown option: " + args[i]);
                }
            }
        }
        try {
            Files.createDirectories(home);
            LauncherConfig cfg = LauncherConfig.load(home, env, jar);
            Files.createDirectories(cfg.updateDir);
            // 같은 설치 폴더에 런처가 둘 뜨면 서로의 교체와 앱을 밟는다 — 잠금 파일로 막는다
            try (FileChannel ch = FileChannel.open(home.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = tryLock(ch)) {
                if (lock == null) {
                    out.println("[launcher] ERROR: another launcher is already running in " + home);
                    return 1;
                }
                Launcher launcher = new Launcher(cfg, out);
                if (installHook) {
                    Runtime.getRuntime().addShutdownHook(new Thread(launcher::requestStop, "launcher-shutdown"));
                }
                return launcher.run();
            }
        } catch (IOException e) {
            out.println("[launcher] ERROR: " + e);
            return 1;
        }
    }

    private static FileLock tryLock(FileChannel ch) throws IOException {
        try {
            return ch.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;     // 같은 JVM 안에서 이미 쥐고 있다
        }
    }

    private static int usage(PrintStream out, String problem) {
        out.println("[launcher] " + problem);
        out.println();
        out.println(USAGE);
        return 2;
    }

    // ── 감독 루프 ─────────────────────────────────────────────────────────────

    /** 앱이 끝날 때까지 감독한다. 정상 종료 0, 포기하거나 실행할 수 없으면 1. */
    int run() {
        try {
            if (!ensureInstalled()) return 1;
            Deque<Long> crashes = new ArrayDeque<>();
            info("supervising " + cfg.home + " (updates: " + cfg.updateDir + ", health: " + cfg.healthUrl + ")");
            while (true) {
                if (Files.exists(pending)) applyPending();
                boolean onTrial = Files.exists(trial);
                Optional<String> current = layout.current();
                if (current.isEmpty() || !Files.isRegularFile(layout.jarOf(current.get()))) {
                    error("current.txt does not point to an installed jar (" + current.orElse("none") + ") - cannot start");
                    return 1;
                }
                start(layout.jarOf(current.get()));
                if (onTrial) {
                    int rc = waitHealthy();
                    if (stopping) {
                        stopChild(5);
                        return 0;           // 시험 도중에 멈춘다 — trial 은 남겨 다음에 이어서 시험한다
                    }
                    if (rc == 0) {
                        finishTrial();
                    } else {
                        if (!rollback(rc == 2 ? "unhealthy-timeout" : "exited-early")) return 1;
                        continue;
                    }
                }
                int code = child.waitFor();
                child = null;
                if (stopping) return 0;
                if (code == 0) {
                    if (Files.exists(pending)) continue;
                    info("application stopped normally");
                    return 0;
                }
                if (code == EXIT_UPDATE) {
                    info("restart requested for an update");
                    continue;
                }
                long now = System.currentTimeMillis();
                while (!crashes.isEmpty() && now - crashes.peekFirst() >= cfg.crashWindowMs) crashes.pollFirst();
                crashes.addLast(now);
                if (crashes.size() >= cfg.crashLimit) {
                    error("the application crashed " + crashes.size() + " times within " + cfg.crashWindowMs / 1000
                            + "s (last exit code " + code + ") - giving up");
                    return 1;
                }
                info("application exited with code " + code + " - restarting in " + cfg.restartDelayMs / 1000 + "s");
                Thread.sleep(cfg.restartDelayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopChild(5);
            return 0;
        } catch (IOException e) {
            error(e.toString());
            stopChild(5);
            return 1;
        }
    }

    // ── 첫 설치 ───────────────────────────────────────────────────────────────

    private boolean ensureInstalled() throws IOException {
        Optional<String> current = layout.current();
        if (current.isPresent()) {
            if (Files.isRegularFile(layout.jarOf(current.get()))) return true;
            error("current.txt says " + current.get() + " but " + layout.jarOf(current.get()) + " is missing");
            return false;
        }
        Path source = cfg.bootstrapJar != null ? cfg.bootstrapJar : findJar();
        if (source == null || !Files.isRegularFile(source)) {
            error("no application jar to install - pass --jar <rag-agent-X.Y.Z.jar> or put it in " + cfg.home);
            return false;
        }
        String version = Jars.buildVersion(source);
        if (!Layout.validVersion(version)) {
            error(source + " has no usable build version in META-INF/build-info.properties (found: " + version + ")");
            return false;
        }
        layout.install(version, source, true);
        layout.setCurrent(version);
        info("installed " + version + " from " + source);
        return true;
    }

    /** 설치 폴더(그다음 target/)에서 가장 최근의 앱 jar. 런처 jar·소스 jar·원본(.original)은 앱이 아니다. */
    private Path findJar() throws IOException {
        for (Path dir : new Path[]{cfg.home, cfg.home.resolve("target")}) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                Optional<Path> best = files.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("rag-agent") && n.endsWith(".jar") && !n.endsWith("-launcher.jar")
                            && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith("-plain.jar");
                }).max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
                if (best.isPresent()) return best.get();
            }
        }
        return null;
    }

    // ── 업데이트 적용 ─────────────────────────────────────────────────────────

    private void applyPending() throws IOException {
        Map<String, String> p = Props.read(pending);
        String version = p.getOrDefault("version", "");
        String shown = Layout.validVersion(version) ? version : "?";
        info("pending update to " + shown);
        if (!Layout.validVersion(version) || p.getOrDefault("jar", "").isEmpty()) {
            reject(shown, "invalid-pending");
            return;
        }
        Optional<String> current = layout.current();
        if (current.isPresent() && current.get().equals(version) && Files.exists(trial)) {
            // 교체를 마치고 pending 만 못 지우고 죽었다 — 다시 적용하면 이전 버전을 새 버전으로 착각한다
            Files.deleteIfExists(pending);
            info("update to " + version + " was already applied");
            return;
        }
        Path staged = Path.of(p.get("jar")).toAbsolutePath().normalize();
        if (!staged.startsWith(cfg.updateDir)) {
            reject(version, "invalid-pending");        // 업데이트 폴더 밖의 파일은 가져오지 않는다
            return;
        }
        if (!Files.isRegularFile(staged)) {
            reject(version, "missing-jar");
            return;
        }
        String want = p.getOrDefault("sha256", "").toLowerCase();
        if (want.isEmpty() || !want.equals(Jars.sha256(staged))) {
            reject(version, "checksum-mismatch");
            return;
        }
        int needed = Jars.minLauncherProtocol(staged);
        if (needed > PROTOCOL) {
            // 앱도 받은 뒤에 같은 검사를 하지만(옛 앱이 새 jar 를 받는 경우는 앱이 모른다), 설치 직전에 한 번 더 — 포인터를 건드리기 전이다
            info("update to " + version + " needs launcher protocol " + needed + " but this launcher speaks " + PROTOCOL);
            reject(version, "launcher-too-old");
            return;
        }
        try {
            layout.install(version, staged, false);
        } catch (IOException e) {
            error("could not install " + version + ": " + e.getMessage());
            reject(version, "copy-failed");
            return;
        }
        Map<String, String> t = new LinkedHashMap<>();
        t.put("version", version);
        t.put("previous", current.orElse(""));
        t.put("dbBackup", p.getOrDefault("dbBackup", ""));
        t.put("dbFile", p.getOrDefault("dbFile", ""));
        t.put("startedAt", Instant.now().toString());
        Props.write(trial, t);
        layout.setCurrent(version);                      // 마지막 한 걸음 — 이 순간부터 새 버전이다
        Files.deleteIfExists(pending);
        info("switched to " + version + " (previous " + current.orElse("none") + " stays in versions/)");
    }

    private void reject(String version, String reason) throws IOException {
        Files.deleteIfExists(pending);
        writeResult("rejected", version, reason, false);
        info("update to " + version + " rejected: " + reason + " (still running the current version)");
    }

    private void finishTrial() throws IOException {
        Map<String, String> t = Props.read(trial);
        String version = t.getOrDefault("version", "");
        Files.deleteIfExists(trial);
        writeResult("ok", version, "", false);
        info("version " + version + " is healthy - update complete");
        Set<String> protect = Set.of(version, t.getOrDefault("previous", ""));
        List<String> removed = layout.prune(cfg.keepVersions, protect);
        if (!removed.isEmpty()) info("removed old versions: " + removed);
    }

    /** @return false 면 되돌릴 수 없어 런처가 멈춘다 */
    private boolean rollback(String reason) throws IOException {
        Map<String, String> t = Props.read(trial);
        String version = t.getOrDefault("version", "?");
        String previous = t.getOrDefault("previous", "");
        info("version " + version + " did not become healthy (" + reason + ") - rolling back");
        stopChild(5);
        if (!Layout.validVersion(previous) || !Files.isRegularFile(layout.jarOf(previous))) {
            error("previous version '" + previous + "' is not installed - cannot roll back");
            writeResult("rolled-back", version, reason, false);
            Files.deleteIfExists(trial);
            return false;
        }
        layout.setCurrent(previous);
        boolean restored = restoreDatabase(t.getOrDefault("dbBackup", ""), t.getOrDefault("dbFile", ""));
        writeResult("rolled-back", version, reason, restored);
        Files.deleteIfExists(trial);
        info("running " + previous + " again");
        return true;
    }

    /** 업데이트 전 백업을 DB 자리에 복원한다. -wal/-shm 을 먼저 지운다 — 남으면 SQLite 가 새 버전의 WAL 을 복원본 위에 재생한다. */
    private boolean restoreDatabase(String backup, String dbFile) {
        if (backup.isEmpty() || dbFile.isEmpty() || !Files.isRegularFile(Path.of(backup))) {
            info("no database backup recorded - database left as it is");
            return false;
        }
        Path db = Path.of(dbFile);
        try {
            Files.deleteIfExists(db);
            Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-wal"));
            Files.deleteIfExists(db.resolveSibling(db.getFileName() + "-shm"));
            Files.copy(Path.of(backup), db, StandardCopyOption.REPLACE_EXISTING);
            info("database restored from " + backup);
            return true;
        } catch (IOException e) {
            error("could not restore the database from " + backup + ": " + e);
            return false;
        }
    }

    private void writeResult(String status, String version, String reason, boolean dbRestored) throws IOException {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("version", version);
        r.put("reason", reason);
        r.put("dbRestored", String.valueOf(dbRestored));
        r.put("at", Instant.now().toString().replaceAll("\\.\\d+Z$", "Z"));
        Props.write(result, r);
    }

    // ── 자식 프로세스 ─────────────────────────────────────────────────────────

    private void start(Path jar) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cfg.command(jar)).directory(cfg.home.toFile()).inheritIO();
        pb.environment().putAll(cfg.overrides);
        pb.environment().put("RAG_SUPERVISED", "1");          // 앱이 "업데이트 버튼을 켜도 된다"고 아는 신호
        pb.environment().put("UPDATE_DIR", cfg.updateDir.toString());
        // 앱이 업데이트를 받은 뒤 "이 런처로 설치할 수 있는 버전인가"를 알려면 런처의 세대를 알아야 한다(.env 에 있어도 이 값이 이긴다)
        pb.environment().put("RAG_LAUNCHER_PROTOCOL", String.valueOf(PROTOCOL));
        pb.environment().put("RAG_LAUNCHER_VERSION", version());
        // 강제 종료된 런처 뒤에 앱이 고아로 남지 않게 - 앱이 이 값으로 런처를 지켜보다 사라지면 스스로 끝난다(LauncherWatchdog)
        ProcessHandle self = ProcessHandle.current();
        pb.environment().put("RAG_LAUNCHER_PID", String.valueOf(self.pid()));
        self.info().startInstant().ifPresent(t -> pb.environment().put("RAG_LAUNCHER_STARTED", t.toString()));
        child = pb.start();
    }

    /** 0 = 정상 응답, 1 = 응답 전에 프로세스가 죽음, 2 = 제한 시간 초과 */
    private int waitHealthy() throws InterruptedException {
        long deadline = System.nanoTime() + cfg.healthTimeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (stopping) return 1;
            if (!child.isAlive()) return 1;
            if (healthy()) return 0;
            Thread.sleep(cfg.healthIntervalMs);
        }
        return 2;
    }

    private boolean healthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.healthUrl)).timeout(Duration.ofSeconds(3)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 자식을 멈춘다 — 먼저 정상 종료를 요청하고 {@code graceSeconds} 뒤에도 살아 있으면 자손까지 강제로 끝낸다. */
    private void stopChild(int graceSeconds) {
        Process p = child;
        if (p == null) return;
        try {
            if (p.isAlive()) {
                p.destroy();
                if (!p.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);   // 래퍼 뒤에 남는 자식이 포트·파일을 쥐지 않게
                    p.destroyForcibly();
                    p.waitFor(10, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        child = null;
    }

    /** SIGTERM / Ctrl+C — 자식에게 정상 종료를 요청하고 끝나기를 기다린다. */
    void requestStop() {
        stopping = true;
        Process p = child;
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void info(String message) {
        out.println("[launcher] " + message);
    }

    private void error(String message) {
        out.println("[launcher] ERROR: " + message);
    }
}
