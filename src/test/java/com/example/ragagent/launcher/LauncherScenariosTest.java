package com.example.ragagent.launcher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 런처가 지켜야 하는 약속을 <b>진짜 자식 프로세스</b>(가짜 앱 {@link StubApp} 을 담은 jar 를 {@code java -jar} 로)로 확인한다.
 * 스크립트가 없으니 어느 OS 에서나 같은 시나리오가 돈다 — 예전에는 bash 판과 PowerShell 판을 따로 돌렸다.
 * 설정은 설치 폴더의 {@code .env} 로 준다(런처가 읽는 것이자 자식에게 환경변수로 넘어가는 것).
 */
class LauncherScenariosTest {

    @TempDir Path home;

    private Path updates;
    private Path db;
    private Path backup;
    private Path stubLog;
    private Path stubState;
    private final Map<String, String> env = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        updates = home.resolve("data").resolve("updates");
        db = home.resolve("data").resolve("memory.db");
        backup = home.resolve("data").resolve("db-backups").resolve("memory-20261116-010203.db");
        stubLog = home.resolve("stub.log");
        stubState = home.resolve("stub-state");
        Files.createDirectories(updates);
        Files.createDirectories(backup.getParent());
        Files.createDirectories(stubState);
        Files.writeString(db, "v1-data");
        Files.writeString(db.resolveSibling("memory.db-wal"), "wal");
        Files.writeString(backup, "v1-data");
        env.put("SERVER_PORT", String.valueOf(freePort()));
        env.put("STUB_LOG", stubLog.toString());
        env.put("STUB_DB", db.toString());
        env.put("STUB_STATE", stubState.toString());
        env.put("RAG_HEALTH_TIMEOUT", "4");
        env.put("RAG_HEALTH_INTERVAL_MS", "100");
        env.put("RAG_RESTART_DELAY", "0");
        env.put("RAG_CRASH_LIMIT", "3");
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void writeEnvFile() throws IOException {
        StringBuilder sb = new StringBuilder("# test settings\n");
        env.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(home.resolve(".env"), sb.toString());
    }

    private final Layout layout() {
        return new Layout(home);
    }

    /** 이미 설치돼 있고 지금 버전인 jar. */
    private void installCurrent(String version, String behavior) throws IOException {
        Path jar = StubJars.create(home.resolve("src-" + version), version, behavior);
        layout().install(version, jar, true);
        layout().setCurrent(version);
    }

    /** 앱이 남긴 모양의 pending.properties 와, 업데이트 폴더에 받아 둔 새 jar. */
    private Path stagePending(String version, String behavior, String shaOverride) throws Exception {
        Path jar = StubJars.create(updates, version, behavior);
        writePending(version, jar, shaOverride != null ? shaOverride : Jars.sha256(jar), backup.toString());
        return jar;
    }

    private void writePending(String version, Path jar, String sha, String dbBackup) throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("version", version);
        p.put("previousVersion", "0.5.6");
        p.put("jar", jar.toAbsolutePath().toString());
        p.put("sha256", sha);
        p.put("dbBackup", dbBackup);
        p.put("dbFile", db.toAbsolutePath().toString());
        p.put("requestedAt", "2026-11-16T01:02:03Z");
        Props.write(updates.resolve("pending.properties"), p);
    }

    private record Run(int exit, String out) {}

    private Run run(String... extraArgs) throws Exception {
        writeEnvFile();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String[] args = new String[2 + extraArgs.length];
        args[0] = "--home";
        args[1] = home.toString();
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        int code = Launcher.execute(args, System.getenv(), new PrintStream(buf, true, StandardCharsets.UTF_8), false);
        return new Run(code, buf.toString(StandardCharsets.UTF_8));
    }

    private List<String> started() throws IOException {
        if (!Files.exists(stubLog)) return List.of();
        return Files.readAllLines(stubLog).stream().filter(l -> l.startsWith("start ")).toList();
    }

    private Map<String, String> result() throws IOException {
        return Props.read(updates.resolve("result.properties"));
    }

    private String currentVersion() throws IOException {
        return layout().current().orElse("");
    }

    // ── 업데이트 적용 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("대기 중인 업데이트를 적용한다 — versions/ 에 설치하고 포인터를 바꾸며, 헬스가 나오면 시험을 끝내고 ok 를 기록한다")
    void appliesAPendingUpdateAndKeepsItWhenHealthy() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        Path staged = stagePending("0.5.7", "healthy-exit0", null);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(currentVersion()).isEqualTo("0.5.7");
        assertThat(layout().jarOf("0.5.7")).exists();
        assertThat(layout().jarOf("0.5.6")).as("이전 버전은 파일째 남는다").exists();
        assertThat(started()).containsExactly("start 0.5.7 healthy-exit0");
        assertThat(result()).containsEntry("status", "ok").containsEntry("version", "0.5.7");
        assertThat(result().get("at")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(staged).as("받아 둔 파일은 설치하면서 옮겨졌다").doesNotExist();
        assertThat(Files.readString(db)).isEqualTo("v1-data");
        assertThat(db.resolveSibling("memory.db-wal")).exists();
    }

    @Test
    @DisplayName("새 버전이 기동 중에 죽으면 포인터를 되돌리고 업데이트 전 DB 백업을 복원(-wal 도 지움)한 뒤 이전 버전을 다시 띄운다")
    void rollsBackPointerAndDatabaseWhenTheNewVersionCrashes() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        stagePending("0.5.7", "crash", null);          // crash = 죽기 전에 DB 를 "마이그레이션" 해 놓는다

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(started()).containsExactly("start 0.5.7 crash", "start 0.5.6 healthy-exit0");
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(Files.readString(db)).as("DB 가 백업으로 복원돼야 한다").isEqualTo("v1-data");
        assertThat(db.resolveSibling("memory.db-wal")).as("새 버전의 WAL 이 남으면 복원본 위에 재생된다").doesNotExist();
        assertThat(result()).containsEntry("status", "rolled-back").containsEntry("version", "0.5.7")
                .containsEntry("reason", "exited-early").containsEntry("dbRestored", "true");
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(layout().jarOf("0.5.7")).as("실패한 버전의 파일은 남는다(포인터만 되돌렸다)").exists();
        assertThat(r.out()).contains("rolling back");
    }

    @Test
    @DisplayName("새 버전이 제한 시간 안에 헬스에 답하지 않으면(매달림) 죽이고 같은 방식으로 되돌린다")
    void rollsBackWhenTheNewVersionNeverBecomesHealthy() throws Exception {
        env.put("RAG_HEALTH_TIMEOUT", "2");
        installCurrent("0.5.6", "healthy-exit0");
        stagePending("0.5.7", "hang", null);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(started()).containsExactly("start 0.5.7 hang", "start 0.5.6 healthy-exit0");
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(result()).containsEntry("status", "rolled-back").containsEntry("reason", "unhealthy-timeout")
                .containsEntry("dbRestored", "true");
    }

    @Test
    @DisplayName("시험을 통과한 뒤의 크래시는 롤백이 아니다 — 새 버전을 유지하고 일반 크래시처럼 다시 띄우다 포기한다")
    void aCrashAfterTheTrialIsNotARollback() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        stagePending("0.5.7", "healthy-then-crash", null);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isEqualTo(1);               // 크래시 한도(3)에서 포기
        assertThat(currentVersion()).isEqualTo("0.5.7");
        assertThat(result()).containsEntry("status", "ok").containsEntry("version", "0.5.7");
        assertThat(started()).hasSize(3).allMatch(l -> l.equals("start 0.5.7 healthy-then-crash"));
        assertThat(Files.readString(db)).as("사용자 데이터가 쌓였을 수 있어 DB 는 건드리지 않는다").isEqualTo("v1-data");
    }

    @Test
    @DisplayName("백업 경로가 없는 pending 의 롤백은 포인터만 되돌린다 — DB 는 건드리지 않고 dbRestored=false")
    void rollbackWithoutBackupLeavesTheDatabaseAlone() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        Path jar = StubJars.create(updates, "0.5.7", "crash");
        writePending("0.5.7", jar, Jars.sha256(jar), "");

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(result()).containsEntry("status", "rolled-back").containsEntry("dbRestored", "false");
        assertThat(Files.readString(db)).as("복원하지 않았으니 새 버전이 바꾼 그대로").isEqualTo("migrated");
    }

    // ── 거절 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("체크섬이 맞지 않으면 설치하지 않고(거절 기록) 지금 버전을 그대로 돌린다")
    void rejectsAChecksumMismatchAndKeepsRunning() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        stagePending("0.5.7", "healthy-exit0", "0".repeat(64));

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 healthy-exit0");
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(layout().jarOf("0.5.7")).doesNotExist();
        assertThat(result()).containsEntry("status", "rejected").containsEntry("version", "0.5.7")
                .containsEntry("reason", "checksum-mismatch");
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(Files.readString(db)).isEqualTo("v1-data");
    }

    @Test
    @DisplayName("받아 둔 jar 가 사라졌으면 거절한다")
    void rejectsWhenTheStagedJarIsMissing() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        Path staged = stagePending("0.5.7", "healthy-exit0", null);
        Files.delete(staged);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(result()).containsEntry("status", "rejected").containsEntry("reason", "missing-jar");
        assertThat(currentVersion()).isEqualTo("0.5.6");
    }

    @Test
    @DisplayName("pending 의 버전이 폴더 이름으로 쓸 수 없는 값(../evil)이면 거절한다 — 경로 탈출을 막는다")
    void rejectsAnInvalidVersion() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        Path jar = StubJars.create(updates, "0.5.7", "healthy-exit0");
        writePending("../evil", jar, Jars.sha256(jar), backup.toString());

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(result()).containsEntry("status", "rejected").containsEntry("reason", "invalid-pending");
        assertThat(home.resolve("evil")).doesNotExist();
        assertThat(currentVersion()).isEqualTo("0.5.6");
    }

    @Test
    @DisplayName("업데이트 폴더 밖의 파일을 가리키는 pending 은 거절한다 — 아무 파일이나 설치하지 않는다")
    void rejectsAJarOutsideTheUpdateDirectory() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        Path outside = StubJars.create(home.resolve("elsewhere"), "0.5.7", "healthy-exit0");
        writePending("0.5.7", outside, Jars.sha256(outside), backup.toString());

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(result()).containsEntry("status", "rejected").containsEntry("reason", "invalid-pending");
        assertThat(layout().jarOf("0.5.7")).doesNotExist();
        assertThat(outside).as("원본은 건드리지 않는다").exists();
    }

    @Test
    @DisplayName("같은 버전 이름에 다른 jar 가 이미 설치돼 있으면 덮어쓰지 않고 거절한다")
    void rejectsADifferentJarForAnInstalledVersion() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        installCurrent("0.5.7", "quick-exit0");          // 0.5.7 이 이미 다른 내용으로 설치돼 있고
        layout().setCurrent("0.5.6");
        stagePending("0.5.7", "healthy-exit0", null);     // 같은 이름에 다른 jar 가 들어온다

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(result()).containsEntry("status", "rejected").containsEntry("reason", "copy-failed");
        assertThat(Files.readAllBytes(layout().jarOf("0.5.7"))).as("기존 파일은 그대로").isEqualTo(
                Files.readAllBytes(home.resolve("src-0.5.7").resolve("rag-agent-0.5.7.jar")));
        assertThat(currentVersion()).isEqualTo("0.5.6");
    }

    // ── 중간에 죽었다 다시 뜬 경우 ───────────────────────────────────────────

    @Test
    @DisplayName("포인터를 바꾼 직후 런처가 죽어 pending 이 남았어도 이전 버전을 새 버전으로 착각하지 않는다 — pending 만 버리고 시험을 이어 간다")
    void aPendingFileLeftAfterTheSwitchIsDropped() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        installCurrent("0.5.7", "healthy-exit0");         // 교체는 끝났고(포인터=0.5.7)
        Map<String, String> t = new LinkedHashMap<>();
        t.put("version", "0.5.7");
        t.put("previous", "0.5.6");
        t.put("dbBackup", backup.toString());
        t.put("dbFile", db.toString());
        Props.write(updates.resolve("trial.properties"), t);      // 시험 중이었는데
        Path jar = StubJars.create(updates, "0.5.7", "healthy-exit0");
        writePending("0.5.7", jar, Jars.sha256(jar), backup.toString());   // pending 을 지우기 전에 죽었다

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("already applied");
        assertThat(currentVersion()).isEqualTo("0.5.7");
        assertThat(result()).containsEntry("status", "ok").containsEntry("version", "0.5.7");
        assertThat(started()).containsExactly("start 0.5.7 healthy-exit0");
    }

    @Test
    @DisplayName("시험 도중에 런처가 멈췄다 다시 뜨면 남은 trial 을 이어서 시험한다 — 되돌릴 때를 놓치지 않는다")
    void anInterruptedTrialIsResumed() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        installCurrent("0.5.7", "crash");
        Map<String, String> t = new LinkedHashMap<>();
        t.put("version", "0.5.7");
        t.put("previous", "0.5.6");
        t.put("dbBackup", backup.toString());
        t.put("dbFile", db.toString());
        Props.write(updates.resolve("trial.properties"), t);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(result()).containsEntry("status", "rolled-back").containsEntry("version", "0.5.7");
        assertThat(started()).containsExactly("start 0.5.7 crash", "start 0.5.6 healthy-exit0");
    }

    // ── 감독 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("종료 코드 42(업데이트를 위한 종료)는 다시 띄운다 — pending 이 없어도 정상 종료로 보지 않는다")
    void restartsOnExitCode42() throws Exception {
        installCurrent("0.5.6", "exit42-once");

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 exit42-once", "start 0.5.6 exit42-once");
        assertThat(updates.resolve("result.properties")).doesNotExist();
    }

    @Test
    @DisplayName("정상 종료(0)면 런처도 끝난다")
    void stopsWhenTheApplicationExitsNormally() throws Exception {
        installCurrent("0.5.6", "quick-exit0");

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 quick-exit0");
        assertThat(r.out()).contains("stopped normally");
    }

    @Test
    @DisplayName("계속 죽으면(제한 횟수) 포기하고 1 로 끝난다 — 무한 재시작하지 않는다")
    void givesUpAfterRepeatedCrashes() throws Exception {
        installCurrent("0.5.6", "crash");

        Run r = run();

        assertThat(r.exit()).as(r.out()).isEqualTo(1);
        assertThat(started()).hasSize(3);
        assertThat(r.out()).contains("giving up");
    }

    @Test
    @DisplayName("같은 설치 폴더에 런처가 이미 떠 있으면 두 번째는 시작하지 않는다 — 서로의 교체와 앱을 밟지 않게")
    void refusesASecondLauncherInTheSameHome() throws Exception {
        installCurrent("0.5.6", "healthy-exit0");
        try (FileChannel ch = FileChannel.open(home.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            assertThat(lock.isValid()).isTrue();

            Run r = run();

            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.out()).contains("another launcher is already running");
            assertThat(started()).isEmpty();
        }
    }

    @Test
    @DisplayName("앱에 .env 값(따옴표 벗김)·JAVA_OPTS 의 -D·RAG_SUPERVISED=1·UPDATE_DIR 이 넘어간다")
    void passesTheEnvironmentToTheApplication() throws Exception {
        env.put("JAVA_OPTS", "-Dstub.flag=on");
        env.put("STUB_X", "\"a b=c\"");
        installCurrent("0.5.6", "quick-exit0");

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        String line = Files.readAllLines(stubLog).stream().filter(l -> l.startsWith("env ")).findFirst().orElse("");
        assertThat(line).contains("supervised=1").contains("flag=on").contains("x=a b=c")
                .contains("updateDir=" + updates.toAbsolutePath().normalize())
                .contains("launcherPid=" + ProcessHandle.current().pid());      // 앱이 런처를 지켜보는 데 쓰는 값
    }

    // ── 첫 설치 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("설치된 버전이 없으면 설치 폴더의 rag-agent*.jar 를 첫 버전으로 설치하고(원본은 그대로) 실행한다 — 버전은 jar 안의 build.version")
    void bootstrapsFromAJarInTheHome() throws Exception {
        // 가장 최근 파일이 이긴다 — 단, 런처 jar 는 앱이 아니므로 가장 최근이어도 고르지 않는다
        Path old = StubJars.create(home, "0.5.5-SNAPSHOT", "crash");
        Path app = StubJars.create(home, "0.5.6", "quick-exit0");
        Path decoy = home.resolve("rag-agent-0.5.9-launcher.jar");
        Files.copy(StubJars.create(home.resolve("tmp-decoy"), "0.5.9", "crash"), decoy);
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(app, java.nio.file.attribute.FileTime.fromMillis(5_000));
        Files.setLastModifiedTime(decoy, java.nio.file.attribute.FileTime.fromMillis(9_000));

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(currentVersion()).isEqualTo("0.5.6");
        assertThat(layout().jarOf("0.5.6")).exists();
        assertThat(home.resolve("rag-agent-0.5.6.jar")).as("원본은 지우지 않는다").exists();
        assertThat(started()).containsExactly("start 0.5.6 quick-exit0");
        assertThat(r.out()).contains("installed 0.5.6");
    }

    @Test
    @DisplayName("--jar 로 첫 설치할 jar 를 지정할 수 있다")
    void bootstrapsFromTheJarArgument() throws Exception {
        Path jar = StubJars.create(home.resolve("elsewhere"), "0.6.0", "quick-exit0");

        Run r = run("--jar", jar.toString());

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(currentVersion()).isEqualTo("0.6.0");
        assertThat(started()).containsExactly("start 0.6.0 quick-exit0");
    }

    @Test
    @DisplayName("설치할 jar 가 없으면 무엇을 하라고 알리고 1 로 끝난다")
    void failsClearlyWithoutAnyJar() throws Exception {
        Run r = run();

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).contains("no application jar to install").contains("--jar");
        assertThat(started()).isEmpty();
    }

    @Test
    @DisplayName("jar 안에 build.version 이 없으면 첫 설치를 거절한다 — 폴더 이름을 정할 수 없다")
    void refusesAJarWithoutAVersion() throws Exception {
        Path jar = home.resolve("rag-agent-x.jar");
        Files.write(jar, new byte[]{'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});   // 빈 zip

        Run r = run("--jar", jar.toString());

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).contains("no usable build version");
    }

    @Test
    @DisplayName("포인터가 가리키는 jar 가 없으면 이유를 알리고 1 로 끝난다 — 설치가 망가진 경우")
    void failsWhenThePointedJarIsMissing() throws Exception {
        installCurrent("0.5.6", "quick-exit0");
        Files.delete(layout().jarOf("0.5.6"));

        Run r = run();

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.out()).contains("current.txt says 0.5.6").contains("is missing");
    }

    // ── 오래된 버전 정리 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("업데이트가 성공하면 오래된 버전을 정리하되 지금 버전·이전 버전과 최근 N 개는 남긴다")
    void prunesOldVersionsButKeepsTheCurrentAndPrevious() throws Exception {
        env.put("RAG_KEEP_VERSIONS", "2");
        for (String v : List.of("0.5.1", "0.5.2", "0.5.3", "0.5.4")) {
            installCurrent(v, "quick-exit0");
            Files.setLastModifiedTime(layout().versionsDir().resolve(v), java.nio.file.attribute.FileTime.fromMillis(1_000L * Integer.parseInt(v.substring(4))));
        }
        installCurrent("0.5.6", "healthy-exit0");
        Files.setLastModifiedTime(layout().versionsDir().resolve("0.5.6"), java.nio.file.attribute.FileTime.fromMillis(10_000));
        stagePending("0.5.7", "healthy-exit0", null);

        Run r = run();

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(layout().installed()).containsExactlyInAnyOrder("0.5.7", "0.5.6");
        assertThat(currentVersion()).isEqualTo("0.5.7");
        assertThat(r.out()).contains("removed old versions");
    }

    // ── 인자 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("--help 는 사용법을 보이고 0, 모르는 옵션이나 값이 빠진 옵션은 사용법과 함께 2")
    void commandLine() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8);

        assertThat(Launcher.execute(new String[]{"--help"}, Map.of(), ps, false)).isZero();
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("Usage:").contains("--home").contains("RAG_HEALTH_TIMEOUT");

        buf.reset();
        assertThat(Launcher.execute(new String[]{"--bogus"}, Map.of(), ps, false)).isEqualTo(2);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("unknown option: --bogus").contains("Usage:");

        buf.reset();
        assertThat(Launcher.execute(new String[]{"--home"}, Map.of(), ps, false)).isEqualTo(2);
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("--home needs a directory");
    }
}
