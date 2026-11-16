package com.example.ragagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 감독 스크립트({@code rag-agent-supervisor.sh} / {@code .ps1})가 지켜야 하는 약속을 <b>실제로 스크립트를 돌려</b> 확인한다 — 두 구현이
 * 같은 시나리오를 통과해야 한다. 진짜 java 대신 PATH 에 가짜 {@code java} 를 두고, 가짜는 jar 파일의 내용({@code BEHAVIOR=…})대로
 * 행동한다: 헬스 플래그 파일을 만들고 종료하거나, 죽거나({@code crash} — DB 를 "마이그레이션" 해 놓고), 응답 없이 매달리거나, 한 번 42 로 끝난다.
 * 스크립트의 헬스 확인은 {@code RAG_HEALTH_URL=file:///…플래그} 로 돌린다(curl · WebRequest 모두 file:// 을 읽는다).
 *
 * <p>마커 파일({@code pending.properties})은 앱이 쓰는 모양 그대로(줄 단위 key=value, 네이티브 절대 경로)로 이 테스트가 쓴다.
 */
abstract class AbstractSupervisorScenarios {

    @TempDir Path root;

    Path updates;
    Path dbFile;
    Path backup;
    Path healthFlag;
    Path stubLog;
    Path stubState;
    Path bin;

    /** 이 환경에서 돌릴 수 없으면 {@code Assumptions.abort} — 스킵한다. */
    abstract void assumeRunnable();

    /** {@code binDir} 에 가짜 java 를 설치하고 {@code JAVA_BIN} 으로 쓸 경로를 돌려준다. */
    abstract String installStub(Path binDir) throws IOException;

    /** 감독 스크립트를 실행하는 명령(작업 디렉터리는 {@link #root}). */
    abstract List<String> command();

    /** 헬스 플래그 파일을 가리키는 {@code RAG_HEALTH_URL}. */
    abstract String healthUrl(Path flag);

    @BeforeEach
    void setUpRoot() throws Exception {
        assumeRunnable();
        updates = root.resolve("data").resolve("updates");
        Files.createDirectories(updates);
        dbFile = root.resolve("data").resolve("memory.db");
        backup = root.resolve("data").resolve("db-backups").resolve("memory-20261116-010203.db");
        Files.createDirectories(backup.getParent());
        healthFlag = root.resolve("healthy.flag");
        stubLog = root.resolve("stub.log");
        stubState = root.resolve("stub-state");
        Files.createDirectories(stubState);
        bin = root.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(dbFile, "v1-data");
        Files.writeString(dbFile.resolveSibling("memory.db-wal"), "wal");
        Files.writeString(backup, "v1-data");
        javaBin = installStub(bin);
    }

    private String javaBin;

    // ── 도우미 ───────────────────────────────────────────────────────────────

    static void writeJar(Path path, String version, String behavior) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "VERSION=" + version + "\nBEHAVIOR=" + behavior + "\n");
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** 앱이 쓰는 모양의 pending.properties — 새 jar 는 updates 폴더에 두고 경로는 네이티브 절대 경로다. */
    Path stagePending(String version, String behavior, String shaOverride) throws Exception {
        Path newJar = updates.resolve("rag-agent-" + version + ".jar");
        writeJar(newJar, version, behavior);
        String sha = shaOverride != null ? shaOverride : sha256(newJar);
        Files.writeString(updates.resolve("pending.properties"), String.join("\n",
                "version=" + version,
                "previousVersion=0.5.6",
                "jar=" + newJar.toAbsolutePath(),
                "sha256=" + sha,
                "dbBackup=" + backup.toAbsolutePath(),
                "dbFile=" + dbFile.toAbsolutePath(),
                "requestedAt=2026-11-16T01:02:03Z") + "\n", StandardCharsets.UTF_8);
        return newJar;
    }

    record Run(int exit, String output) {}

    Run run(Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command()).directory(root.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        for (String k : List.of("DATA_DIR", "UPDATE_DIR", "RAG_JAR", "SERVER_PORT", "JAVA_OPTS", "RAG_SUPERVISED")) env.remove(k);
        env.put("JAVA_BIN", javaBin);
        env.put("STUB_LOG", stubLog.toAbsolutePath().toString());
        env.put("STUB_FLAG", healthFlag.toAbsolutePath().toString());
        env.put("STUB_DB", dbFile.toAbsolutePath().toString());
        env.put("STUB_STATE", stubState.toAbsolutePath().toString());
        env.put("RAG_HEALTH_URL", healthUrl(healthFlag));
        env.put("RAG_HEALTH_TIMEOUT", "4");
        env.put("RAG_HEALTH_INTERVAL", "1");
        env.put("RAG_RESTART_DELAY", "0");
        env.put("RAG_CRASH_LIMIT", "3");
        env.putAll(extraEnv);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 프로세스가 끝나면 닫힌다
            }
        });
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(2000);
            throw new AssertionError("감독 스크립트가 120초 안에 끝나지 않았다:\n" + out);
        }
        reader.join(5000);
        return new Run(p.exitValue(), out.toString());
    }

    Map<String, String> props(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1).trim());
        }
        return m;
    }

    List<String> started() throws IOException {
        return Files.exists(stubLog) ? Files.readAllLines(stubLog).stream().map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of();
    }

    String jarText() throws IOException {
        return Files.readString(root.resolve("rag-agent.jar"));
    }

    // ── 시나리오 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("대기 중인 업데이트를 적용한다 — 새 jar 로 교체(이전 jar 는 .prev), 헬스가 나오면 시도(trial)를 끝내고 ok 를 기록")
    void appliesAPendingUpdateAndKeepsItWhenHealthy() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        stagePending("0.5.7", "healthy-exit0", null);

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(jarText()).contains("VERSION=0.5.7");
        assertThat(Files.readString(root.resolve("rag-agent.jar.prev"))).contains("VERSION=0.5.6");
        assertThat(started()).containsExactly("start 0.5.7 healthy-exit0");
        Map<String, String> result = props(updates.resolve("result.properties"));
        assertThat(result).containsEntry("status", "ok").containsEntry("version", "0.5.7");
        assertThat(result.get("at")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(updates.resolve("rag-agent-0.5.7.jar")).doesNotExist();         // 적용한 파일은 치운다
        assertThat(Files.readString(dbFile)).isEqualTo("v1-data");                  // 성공하면 DB 는 건드리지 않는다
        assertThat(dbFile.resolveSibling("memory.db-wal")).exists();
    }

    @Test
    @DisplayName("새 버전이 기동 중에 죽으면 이전 jar 와 업데이트 전 DB 백업으로 되돌리고(-wal 도 지움) 이전 버전을 다시 띄운다")
    void rollsBackJarAndDatabaseWhenTheNewVersionCrashes() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        stagePending("0.5.7", "crash", null);       // crash = 죽기 전에 DB 를 "마이그레이션" 해 놓는다

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(started()).containsExactly("start 0.5.7 crash", "start 0.5.6 healthy-exit0");
        assertThat(jarText()).contains("VERSION=0.5.6");
        assertThat(Files.readString(dbFile)).as("DB 가 백업으로 복원돼야 한다").isEqualTo("v1-data");
        assertThat(dbFile.resolveSibling("memory.db-wal")).as("새 버전의 WAL 이 남으면 복원본 위에 재생된다").doesNotExist();
        Map<String, String> result = props(updates.resolve("result.properties"));
        assertThat(result).containsEntry("status", "rolled-back").containsEntry("version", "0.5.7")
                .containsEntry("reason", "exited-early").containsEntry("dbRestored", "true");
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(r.output()).contains("rolling back");
    }

    @Test
    @DisplayName("새 버전이 제한 시간 안에 헬스에 답하지 않으면(매달림) 죽이고 같은 방식으로 되돌린다")
    void rollsBackWhenTheNewVersionNeverBecomesHealthy() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        stagePending("0.5.7", "hang", null);

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(started()).containsExactly("start 0.5.7 hang", "start 0.5.6 healthy-exit0");
        assertThat(jarText()).contains("VERSION=0.5.6");
        Map<String, String> result = props(updates.resolve("result.properties"));
        assertThat(result).containsEntry("status", "rolled-back").containsEntry("reason", "unhealthy-timeout")
                .containsEntry("dbRestored", "true");
        assertThat(Files.readString(dbFile)).isEqualTo("v1-data");
    }

    @Test
    @DisplayName("체크섬이 맞지 않으면 교체하지 않고(거절 기록) 지금 jar 를 그대로 돌린다")
    void rejectsAChecksumMismatchAndKeepsRunning() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        stagePending("0.5.7", "healthy-exit0", "0".repeat(64));

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 healthy-exit0");
        assertThat(jarText()).contains("VERSION=0.5.6");
        assertThat(root.resolve("rag-agent.jar.prev")).doesNotExist();
        Map<String, String> result = props(updates.resolve("result.properties"));
        assertThat(result).containsEntry("status", "rejected").containsEntry("version", "0.5.7")
                .containsEntry("reason", "checksum-mismatch");
        assertThat(updates.resolve("pending.properties")).doesNotExist();
        assertThat(updates.resolve("trial.properties")).doesNotExist();
        assertThat(Files.readString(dbFile)).isEqualTo("v1-data");
    }

    @Test
    @DisplayName("받아 둔 jar 가 사라졌으면 거절한다")
    void rejectsWhenTheStagedJarIsMissing() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        Path newJar = stagePending("0.5.7", "healthy-exit0", null);
        Files.delete(newJar);

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(props(updates.resolve("result.properties"))).containsEntry("status", "rejected").containsEntry("reason", "missing-jar");
        assertThat(jarText()).contains("VERSION=0.5.6");
    }

    @Test
    @DisplayName("종료 코드 42(업데이트를 위한 종료)는 다시 띄운다 — pending 이 없어도 정상 종료로 보지 않는다")
    void restartsOnExitCode42() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "exit42-once");

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 exit42-once", "start 0.5.6 exit42-once");
        assertThat(updates.resolve("result.properties")).doesNotExist();         // 업데이트가 없었으니 결과도 없다
    }

    @Test
    @DisplayName("정상 종료(0)면 감독도 끝난다")
    void stopsWhenTheApplicationExitsNormally() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "quick-exit0");

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(started()).containsExactly("start 0.5.6 quick-exit0");
        assertThat(r.output()).contains("stopped normally");
    }

    @Test
    @DisplayName("계속 죽으면(제한 횟수) 포기하고 1 로 끝난다 — 무한 재시작하지 않는다")
    void givesUpAfterRepeatedCrashes() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "crash");

        Run r = run(Map.of("RAG_CRASH_LIMIT", "3"));

        assertThat(r.exit()).as(r.output()).isEqualTo(1);
        assertThat(started()).hasSize(3);
        assertThat(r.output()).contains("giving up");
    }

    @Test
    @DisplayName("jar 가 없으면 설명을 남기고 1 로 끝난다")
    void failsClearlyWithoutAJar() throws Exception {
        Run r = run(Map.of());

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.output()).contains("rag-agent.jar").contains("not found");
        assertThat(started()).isEmpty();
    }

    @Test
    @DisplayName("백업 경로가 없는 pending 도 롤백은 jar 만 되돌린다 — DB 는 건드리지 않고 dbRestored=false")
    void rollbackWithoutBackupLeavesTheDatabaseAlone() throws Exception {
        writeJar(root.resolve("rag-agent.jar"), "0.5.6", "healthy-exit0");
        stagePending("0.5.7", "crash", null);
        String pending = Files.readString(updates.resolve("pending.properties"))
                .replaceAll("(?m)^dbBackup=.*$", "dbBackup=");
        Files.writeString(updates.resolve("pending.properties"), pending);

        Run r = run(Map.of());

        assertThat(r.exit()).as(r.output()).isZero();
        assertThat(jarText()).contains("VERSION=0.5.6");
        assertThat(props(updates.resolve("result.properties"))).containsEntry("status", "rolled-back").containsEntry("dbRestored", "false");
        assertThat(Files.readString(dbFile).strip()).as("복원하지 않았으니 새 버전이 바꾼 그대로").isEqualTo("migrated");
    }
}
