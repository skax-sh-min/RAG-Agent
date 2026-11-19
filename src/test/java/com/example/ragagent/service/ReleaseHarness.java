package com.example.ragagent.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 릴리즈 스크립트 테스트의 공통 바탕 — 임시 git 저장소 + 로컬 bare 원격(push 가 진짜로 일어난다)을 만들고, 진짜 mvn 대신 가짜
 * {@code mvn}(pom 버전을 읽어 {@code build-info.properties} 가 든 jar 를 만든다)과 가짜 {@code gh}(호출을 기록)를
 * {@code RELEASE_MVN}/{@code RELEASE_GH} 로 지정한다. 푸시·게시 같은 되돌리기 어려운 일을 하는 스크립트라서 "실패하면 아무것도 바꾸지 않는다"를
 * 특히 꼼꼼히 본다.
 *
 * <p><b>저장소는 클래스당 한 번만 만들고 테스트마다 복사한다</b>(git 프로세스를 테스트마다 열두 번 띄우면 Windows 에서 시나리오당 수 초가
 * 더 든다). bash 는 Git Bash 만 쓴다 — WSL 의 {@code bash.exe} 는 경로 체계가 달라 Windows 경로를 못 읽는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ReleaseHarness {

    /** {@code YES} 는 스크립트 옵션이 아니다 — 스크립트는 언제나 묻는다. 인자 목록에 표지로 넣으면 {@link #run} 이 빼고 모든 질문에 y 로 답한다. */
    enum Opt { BUILD_ONLY, NO_PUBLISH, SKIP_TESTS, ANY_BRANCH, YES, PR }

    /** 확인 질문 셋(릴리즈 여부 · 버전 · 커밋 전)에 모두 y. */
    static final String ANSWER_ALL_YES = "y\ny\ny\n";
    private static final String YES_MARKER = "<answer-all-yes>";

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("windows");
    private static final Path SCRIPTS = Path.of("scripts").toAbsolutePath();

    private Path template;      // 클래스당 한 번: repo/ remote.git/ bin/
    Path tmp;                   // 테스트마다
    Path repo;
    Path remote;
    Path bin;
    Path mvnLog;
    Path ghLog;

    /** 이 환경에서 돌릴 수 없으면 {@code Assumptions.abort} 로 건너뛴다. */
    abstract void assumeRunnable();

    /** 저장소의 {@code scripts/} 아래에서 임시 저장소로 복사할 파일들(전부 커밋돼 작업 트리가 깨끗하다). */
    List<String> scriptFiles() {
        return List.of("release.sh");
    }

    /** 스크립트를 실행하는 명령 — {@code scriptsDir} 는 임시 저장소의 scripts/ 다. */
    List<String> command(Path scriptsDir) {
        return List.of(findBash(), scriptsDir.resolve("release.sh").toString().replace('\\', '/'));
    }

    // ── bash ────────────────────────────────────────────────────────────────

    static String findBash() {
        String fromEnv = System.getenv("RAG_TEST_BASH");
        for (String c : new String[]{fromEnv, "C:/Program Files/Git/bin/bash.exe", "/bin/bash", "/usr/bin/bash"}) {
            if (c != null && Files.isExecutable(Path.of(c))) return c;
        }
        return null;
    }

    static boolean bashHasTools(String bash) {
        try {
            Process p = new ProcessBuilder(bash, "-c",
                    "command -v git >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 && command -v sed >/dev/null 2>&1")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static void assumeBash() {
        String bash = findBash();
        Assumptions.assumeTrue(bash != null, "bash 가 없어 스킵한다");
        Assumptions.assumeTrue(bashHasTools(bash), "git · unzip · sed 가 필요하다");
    }

    // ── 저장소 템플릿 ───────────────────────────────────────────────────────

    @BeforeAll
    void buildTemplate() throws Exception {
        assumeRunnable();
        template = Files.createTempDirectory("release-template");
        Path tRepo = template.resolve("repo");
        Path tRemote = template.resolve("remote.git");
        Path tBin = template.resolve("bin");
        Files.createDirectories(tRepo.resolve("scripts"));
        Files.createDirectories(tBin);
        installFakes(tBin);

        exec(template, "git", "init", "-q", "--bare", "-b", "main", tRemote.toString());
        exec(template, "git", "init", "-q", "-b", "main", tRepo.toString());
        // 설정은 .git/config 에 직접 쓴다 — git config 를 다섯 번 띄우지 않으려고
        Files.writeString(tRepo.resolve(".git").resolve("config"), Files.readString(tRepo.resolve(".git").resolve("config"))
                + "[user]\n\tname = Release Test\n\temail = release@test.invalid\n"
                + "[core]\n\tautocrlf = false\n[commit]\n\tgpgsign = false\n[tag]\n\tgpgsign = false\n");
        Files.writeString(tRepo.resolve(".gitignore"), "target/\n");
        writePom(tRepo, "0.5.6-SNAPSHOT", "\n");
        for (String f : scriptFiles()) {
            Files.copy(SCRIPTS.resolve(f), tRepo.resolve("scripts").resolve(f));
        }
        exec(tRepo, "git", "add", "-A");
        exec(tRepo, "git", "commit", "-q", "-m", "initial");
        exec(tRepo, "git", "remote", "add", "origin", tRemote.toString());
        exec(tRepo, "git", "push", "-q", "-u", "origin", "main");
    }

    @AfterAll
    void deleteTemplate() {
        if (template != null) deleteTree(template);
    }

    @BeforeEach
    void copyTemplate() throws Exception {
        tmp = Files.createTempDirectory("release-test");
        repo = tmp.resolve("repo");
        remote = tmp.resolve("remote.git");
        bin = template.resolve("bin");
        mvnLog = tmp.resolve("mvn.log");
        ghLog = tmp.resolve("gh.log");
        copyTree(template.resolve("repo"), repo);
        copyTree(template.resolve("remote.git"), remote);
        exec(repo, "git", "remote", "set-url", "origin", remote.toString());
    }

    @AfterEach
    void deleteTmp() {
        if (tmp != null) deleteTree(tmp);
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** git 의 객체 파일은 읽기 전용이라 Windows 에서는 쓰기 가능으로 바꿔야 지워진다. */
    static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 임시 폴더라 남아도 해가 없다
                }
            });
        } catch (IOException ignored) {
            // 위와 같다
        }
    }

    // ── 가짜 mvn · gh ───────────────────────────────────────────────────────

    void installFakes(Path binDir) throws IOException {
        Path mvn = binDir.resolve("mvn");
        Files.writeString(mvn, String.join("\n",
                "#!/bin/sh",
                "case \" $* \" in",
                "  *\" -v \"*) echo \"Apache Maven 3.9.9\"; echo \"Java version: ${FAKE_MVN_JAVA:-21.0.13}, vendor: test\"; exit 0 ;;",
                "esac",
                "echo \"$*\" >> \"$MVN_LOG\"",
                "[ -n \"$FAKE_MVN_FAIL\" ] && exit 1",
                "ver=$(sed -b -n '/<artifactId>rag-agent<\\/artifactId>/{n;s|.*<version>\\([^<]*\\)</version>.*|\\1|p;}' pom.xml 2>/dev/null | tr -d '\\r' | head -n 1)",
                "bv=\"$ver\"; [ -n \"$FAKE_MVN_WRONG_VERSION\" ] && bv=\"9.9.9\"",
                "start=\"com.example.ragagent.RagAgentApplication\"; [ -n \"$FAKE_MVN_WRONG_START\" ] && start=\"com.other.App\"",
                "rm -rf target",
                "mkdir -p target/stage/META-INF",
                "printf 'build.version=%s\\nbuild.name=rag-agent\\n' \"$bv\" > target/stage/META-INF/build-info.properties",
                "printf 'Start-Class: %s\\n' \"$start\" > target/manifest.txt",
                "[ -z \"$FAKE_MVN_NO_PROTOCOL\" ] && printf 'Min-Launcher-Protocol: %s\\n' \"${FAKE_MVN_PROTOCOL:-1}\" >> target/manifest.txt",
                "\"$FAKE_JAR\" --create --file \"target/rag-agent-$ver.jar\" --manifest target/manifest.txt -C target/stage . || exit 1",
                "if [ -z \"$FAKE_MVN_NO_LAUNCHER\" ]; then",
                "  mkdir -p target/lstage/com/example/ragagent/launcher",
                "  : > target/lstage/com/example/ragagent/launcher/Launcher.class",
                "  printf 'Main-Class: com.example.ragagent.launcher.Launcher\\n' > target/lmanifest.txt",
                "  \"$FAKE_JAR\" --create --file \"target/rag-agent-$ver-launcher.jar\" --manifest target/lmanifest.txt -C target/lstage . || exit 1",
                "fi",
                "exit 0",
                ""));
        mvn.toFile().setExecutable(true);
        Path gh = binDir.resolve("gh");
        Files.writeString(gh, String.join("\n",
                "#!/bin/sh",
                "echo \"$*\" >> \"$GH_LOG\"",
                "case \"$1 $2\" in",
                "  \"auth status\") [ -n \"$FAKE_GH_UNAUTH\" ] && exit 1; exit 0 ;;",
                "  \"release create\") [ -n \"$FAKE_GH_CREATE_FAIL\" ] && exit 1; exit 0 ;;",
                "esac",
                "exit 0",
                ""));
        gh.toFile().setExecutable(true);
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    static void writePom(Path repoDir, String version, String nl) throws IOException {
        String pom = String.join(nl,
                "<project>",
                "  <parent>",
                "    <groupId>org.example</groupId>",
                "    <artifactId>parent</artifactId>",
                "    <version>4.1.2</version>",
                "  </parent>",
                "  <groupId>com.example</groupId>",
                "  <artifactId>rag-agent</artifactId>",
                "  <version>" + version + "</version>",
                "  <name>rag-agent</name>",
                "</project>", "");
        Files.writeString(repoDir.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
    }

    void writePom(String version, String nl) throws IOException {
        writePom(repo, version, nl);
    }

    static String exec(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("timeout: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) throw new AssertionError(String.join(" ", cmd) + " failed:\n" + out);
        return out;
    }

    String git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return exec(repo, cmd.toArray(new String[0])).strip().replace("\r", "");
    }

    String remoteGit(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "--git-dir", remote.toString()));
        cmd.addAll(List.of(args));
        return exec(tmp, cmd.toArray(new String[0])).strip().replace("\r", "");
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static String jarEntry(Path jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(entry);
            return e == null ? null : new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    String opt(Opt option) {
        return switch (option) {
            case BUILD_ONLY -> "--build-only";
            case NO_PUBLISH -> "--no-publish";
            case SKIP_TESTS -> "--skip-tests";
            case ANY_BRANCH -> "--any-branch";
            case YES -> YES_MARKER;
            case PR -> "--pr";
        };
    }

    /**
     * 원격을 "PR 로만 받는 main" 으로 만든다 — {@code refs/heads/main} 을 갱신하려는 푸시를 pre-receive 훅이 거절한다(GitHub 의 GH006 과 같은
     * 모양). 다른 브랜치와 태그는 받는다. 이 테스트의 원격 복사본에만 걸린다.
     */
    void protectMain() throws IOException {
        Path hook = remote.resolve("hooks").resolve("pre-receive");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, String.join("\n",
                "#!/bin/sh",
                "while read old new ref; do",
                "  if [ \"$ref\" = \"refs/heads/main\" ]; then",
                "    echo \"remote: error: GH006: Protected branch update failed for $ref.\" >&2",
                "    exit 1",
                "  fi",
                "done",
                "exit 0",
                ""), StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true);
    }

    List<String> valueOpt(String name, String value) {
        return List.of("--" + name, value);
    }

    record Result(int exit, String out) {}

    Result run(List<String> args, Map<String, String> extraEnv, String stdin) throws Exception {
        List<String> cmd = new ArrayList<>(command(repo.resolve("scripts")));
        if (args.contains(YES_MARKER)) {
            args = args.stream().filter(a -> !YES_MARKER.equals(a)).toList();
            stdin = ANSWER_ALL_YES + stdin;
        }
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("RELEASE_") || k.startsWith("FAKE_"));
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("RELEASE_MVN", bin.resolve("mvn").toString().replace('\\', '/'));
        env.put("RELEASE_GH", bin.resolve("gh").toString().replace('\\', '/'));
        env.put("MVN_LOG", mvnLog.toString());
        env.put("GH_LOG", ghLog.toString());
        env.put("FAKE_JAR", Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "jar.exe" : "jar").toString().replace('\\', '/'));
        env.putAll(extraEnv);
        Process p = pb.start();
        try (var os = p.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                out.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 프로세스가 끝나면 닫힌다
            }
        });
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(2000);
            throw new AssertionError("릴리즈 스크립트가 180초 안에 끝나지 않았다:\n" + out);
        }
        reader.join(5000);
        return new Result(p.exitValue(), out.toString());
    }

    Result run(List<String> args) throws Exception {
        return run(args, Map.of(), "");
    }

    List<String> args(String... a) {
        return new ArrayList<>(List.of(a));
    }

    /** 아무것도 바뀌지 않았다: 커밋 1개(initial) · 태그 없음 · 작업 트리 깨끗 · 원격에 태그 없음. */
    void assertNothingChanged() throws Exception {
        org.assertj.core.api.Assertions.assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("1");
        org.assertj.core.api.Assertions.assertThat(git("tag")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(git("status", "--porcelain")).as("pom.xml 이 원래대로여야 한다").isEmpty();
        org.assertj.core.api.Assertions.assertThat(remoteGit("tag")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(remoteGit("rev-list", "--count", "main")).isEqualTo("1");
    }

    List<String> mvnCalls() throws IOException {
        return Files.exists(mvnLog) ? Files.readAllLines(mvnLog).stream().map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of();
    }

    List<String> ghCalls() throws IOException {
        return Files.exists(ghLog) ? Files.readAllLines(ghLog).stream().map(String::strip).filter(l -> !l.isEmpty()).toList() : List.of();
    }
}
