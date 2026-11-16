package com.example.ragagent.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 런처의 작은 부품들 — 설정 읽기 · 설치 배치 · 파일 형식 · 패키지 제약. 프로세스를 띄우지 않으므로 빠르다. */
class LauncherUnitsTest {

    @TempDir Path dir;

    // ── .env · 설정 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName(".env: 주석·빈 줄은 건너뛰고, 따옴표 한 쌍은 벗기고, 값 안의 = 와 공백은 그대로 둔다")
    void parsesAnEnvFile() {
        Map<String, String> m = LauncherConfig.parseEnv(String.join("\r\n",
                "# comment", "", "A=1", "B = spaced", "C=\"quoted value\"", "D='single'", "E=x=y z", "F=", "  G=indented",
                "not a line", "=novalue", "H=\"unbalanced", ""));

        assertThat(m).containsEntry("A", "1").containsEntry("C", "quoted value").containsEntry("D", "single")
                .containsEntry("E", "x=y z").containsEntry("F", "").containsEntry("G", "indented")
                .containsEntry("H", "\"unbalanced").doesNotContainKey("not a line");
        assertThat(m).doesNotContainKey("B");        // 키 뒤의 공백은 셸에서도 대입이 아니다
    }

    @Test
    @DisplayName(".env 는 BOM 이 있어도 첫 키를 읽는다")
    void envFileWithBom() {
        assertThat(LauncherConfig.parseEnv("\uFEFFA=1\nB=2\n")).containsEntry("A", "1").containsEntry("B", "2");
    }

    @Test
    @DisplayName("기본값 — 포트 8080 의 헬스 주소 · data/updates · 180초 · 2초 · 5초 · 5회/300초 · 3개 버전")
    void defaults() throws IOException {
        LauncherConfig c = LauncherConfig.load(dir, Map.of(), null);

        assertThat(c.healthUrl).isEqualTo("http://127.0.0.1:8080/api/v1/health");
        assertThat(c.dataDir).isEqualTo(dir.resolve("data").normalize());
        assertThat(c.updateDir).isEqualTo(dir.resolve("data").resolve("updates").normalize());
        assertThat(c.healthTimeoutMs).isEqualTo(180_000);
        assertThat(c.healthIntervalMs).isEqualTo(2_000);
        assertThat(c.restartDelayMs).isEqualTo(5_000);
        assertThat(c.crashLimit).isEqualTo(5);
        assertThat(c.crashWindowMs).isEqualTo(300_000);
        assertThat(c.keepVersions).isEqualTo(3);
        assertThat(c.jvmOpts).isEmpty();
        assertThat(c.javaBin).contains("java");
    }

    @Test
    @DisplayName("환경변수와 .env — 같은 이름이면 .env 가 이기고, DATA_DIR 은 설치 폴더 기준으로 풀린다")
    void envFileWinsOverTheEnvironment() throws IOException {
        Files.writeString(dir.resolve(".env"), "SERVER_PORT=9999\nDATA_DIR=./store\nJAVA_OPTS=-Xmx1g -Dx=y\n");

        LauncherConfig c = LauncherConfig.load(dir, Map.of("SERVER_PORT", "1111", "RAG_CRASH_LIMIT", "7"), null);

        assertThat(c.healthUrl).isEqualTo("http://127.0.0.1:9999/api/v1/health");
        assertThat(c.dataDir).isEqualTo(dir.resolve("store").normalize());
        assertThat(c.updateDir).isEqualTo(dir.resolve("store").resolve("updates").normalize());
        assertThat(c.crashLimit).isEqualTo(7);
        assertThat(c.jvmOpts).containsExactly("-Xmx1g", "-Dx=y");
        assertThat(c.command(dir.resolve("a.jar"))).containsSubsequence("-Xmx1g", "-Dx=y", "-jar", dir.resolve("a.jar").toString());
    }

    @Test
    @DisplayName("읽을 수 없는 숫자는 기본값으로 — 설정 오타로 런처가 못 뜨지 않는다")
    void invalidNumbersFallBackToTheDefaults() throws IOException {
        LauncherConfig c = LauncherConfig.load(dir, Map.of("RAG_HEALTH_TIMEOUT", "soon", "RAG_CRASH_LIMIT", "-3", "RAG_KEEP_VERSIONS", "0"), null);

        assertThat(c.healthTimeoutMs).isEqualTo(180_000);
        assertThat(c.crashLimit).isEqualTo(5);
        assertThat(c.keepVersions).isEqualTo(1);       // 0 개를 남기는 것은 허용하지 않는다
    }

    // ── 설치 배치 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("버전 이름은 폴더 이름으로 안전한 모양만 — ../ · 경로 구분자 · 이상한 문자는 거절")
    void versionNames() {
        for (String ok : List.of("0.5.7", "1.0", "0.5.6-SNAPSHOT", "1.2.3.4", "0.5.7-rc.1")) assertThat(Layout.validVersion(ok)).as(ok).isTrue();
        for (String bad : List.of("", "../evil", "0.5/7", "0.5.7\\x", "v0.5.7", "7", "0.5.7;rm", " 0.5.7", "0.5.7\n", "a.b.c")) {
            assertThat(Layout.validVersion(bad)).as(bad).isFalse();
        }
        assertThat(Layout.validVersion(null)).isFalse();
        assertThat(Layout.validVersion("1." + "1".repeat(80))).isFalse();
    }

    @Test
    @DisplayName("install: 새로 두면 복사/이동하고, 같은 내용이 이미 있으면 멱등이며, 다른 내용이면 덮어쓰지 않고 실패한다")
    void install() throws IOException {
        Layout layout = new Layout(dir);
        Path a = Files.writeString(dir.resolve("a.jar"), "AAA");
        Path b = Files.writeString(dir.resolve("b.jar"), "BBB");

        layout.install("0.5.7", a, true);                       // 복사 — 원본 유지
        assertThat(layout.jarOf("0.5.7")).hasContent("AAA");
        assertThat(a).exists();

        layout.install("0.5.7", a, false);                      // 같은 내용 — 멱등, 옮기는 모드면 원본은 치운다
        assertThat(a).doesNotExist();
        assertThat(layout.jarOf("0.5.7")).hasContent("AAA");

        assertThatThrownBy(() -> layout.install("0.5.7", b, false)).isInstanceOf(IOException.class).hasMessageContaining("different jar");
        assertThat(layout.jarOf("0.5.7")).hasContent("AAA");
        assertThat(b).as("실패하면 원본도 건드리지 않는다").exists();
        assertThat(layout.jarOf("0.5.7").resolveSibling("rag-agent-0.5.7.jar.part")).doesNotExist();
        assertThatThrownBy(() -> layout.install("../x", b, true)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("포인터: 쓰고 읽고, 없거나 이상한 값이면 비어 있다 — 임시 파일은 남지 않는다")
    void pointer() throws IOException {
        Layout layout = new Layout(dir);
        assertThat(layout.current()).isEmpty();

        layout.setCurrent("0.5.7");
        assertThat(layout.current()).contains("0.5.7");
        assertThat(Files.readString(layout.pointer())).isEqualTo("0.5.7\n");
        layout.setCurrent("0.5.8");
        assertThat(layout.current()).contains("0.5.8");
        assertThat(dir.resolve("current.txt.tmp")).doesNotExist();

        Files.writeString(layout.pointer(), "../../etc\n");
        assertThat(layout.current()).isEmpty();
        assertThatThrownBy(() -> layout.setCurrent("../x")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("prune: 최근 N 개와 보호 대상은 남기고 나머지만 지운다 — 보호 대상이 오래됐어도 지우지 않는다")
    void prune() throws IOException {
        Layout layout = new Layout(dir);
        String[] versions = {"0.5.1", "0.5.2", "0.5.3", "0.5.4", "0.5.5"};
        for (int i = 0; i < versions.length; i++) {
            Path jar = Files.writeString(dir.resolve("j" + i + ".jar"), "content-" + i);
            layout.install(versions[i], jar, true);
            Files.setLastModifiedTime(layout.versionsDir().resolve(versions[i]), FileTime.fromMillis(1_000L * (i + 1)));
        }
        assertThat(layout.installed()).containsExactly("0.5.5", "0.5.4", "0.5.3", "0.5.2", "0.5.1");   // 최근 설치 순

        List<String> removed = layout.prune(2, Set.of("0.5.5", "0.5.1"));

        assertThat(removed).containsExactlyInAnyOrder("0.5.3", "0.5.2");
        assertThat(layout.installed()).containsExactlyInAnyOrder("0.5.5", "0.5.4", "0.5.1");
        assertThat(layout.prune(10, Set.of())).as("N 이 남은 수보다 크면 아무것도 지우지 않는다").isEmpty();
    }

    // ── 파일 형식 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("key=value 파일: 이스케이프 없이 그대로(Windows 경로의 역슬래시·콜론), BOM·주석·빈 줄 무시, LF, 임시 파일 안 남김")
    void props() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("jar", "C:\\Users\\me\\data\\updates\\rag-agent-0.5.7.jar");
        m.put("empty", "");
        Path f = dir.resolve("p.properties");

        Props.write(f, m);

        byte[] raw = Files.readAllBytes(f);
        assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo("jar=C:\\Users\\me\\data\\updates\\rag-agent-0.5.7.jar\nempty=\n");
        assertThat(raw[0]).isNotEqualTo((byte) 0xEF);
        assertThat(Props.read(f)).isEqualTo(m);
        assertThat(dir.resolve("p.properties.tmp")).doesNotExist();

        Files.writeString(f, "\uFEFF# c\r\n\r\nstatus=ok\r\nbroken line\r\nversion=0.5.7 \r\n");
        assertThat(Props.read(f)).containsOnly(Map.entry("status", "ok"), Map.entry("version", "0.5.7"));
        assertThat(Props.read(dir.resolve("missing"))).isEmpty();
    }

    @Test
    @DisplayName("앱이 쓰는 pending.properties 모양을 그대로 읽는다 — UpdateApplyService 와 같은 규칙")
    void readsWhatTheAppWrites() throws IOException {
        Path f = dir.resolve("pending.properties");
        Files.writeString(f, "version=0.5.7\nprevious" + "Version=0.5.6\njar=C:\\x\\rag-agent-0.5.7.jar\nsha256=" + "ab".repeat(32)
                + "\ndbBackup=C:\\b\\memory-1.db\ndbFile=C:\\d\\memory.db\nrequestedAt=2026-11-16T01:02:03Z\n");

        Map<String, String> p = Props.read(f);

        assertThat(p).containsEntry("version", "0.5.7").containsEntry("jar", "C:\\x\\rag-agent-0.5.7.jar")
                .containsEntry("dbFile", "C:\\d\\memory.db");
        assertThat(p).hasSize(7);
    }

    // ── 패키지 제약 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("런처 패키지는 JDK 만 쓴다 — 런처 jar 는 Spring 도 로깅 라이브러리도 싣지 않는 작은 jar 이고 거의 안 바뀌어야 한다")
    void theLauncherPackageUsesOnlyTheJdk() throws IOException {
        Path src = Path.of("src", "main", "java", "com", "example", "ragagent", "launcher");
        assertThat(src).isDirectory();
        Pattern imp = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
        try (Stream<Path> files = Files.list(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = imp.matcher(Files.readString(f));
                while (m.find()) {
                    String pkg = m.group(1);
                    assertThat(pkg.startsWith("java.") || pkg.startsWith("javax.")).as(f.getFileName() + " imports " + pkg).isTrue();
                }
            }
        }
    }
}
