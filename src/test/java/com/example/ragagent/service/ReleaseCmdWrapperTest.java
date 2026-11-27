package com.example.ragagent.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/windows_release.cmd} — 로직은 {@code release.sh} 에 있고(그쪽은 {@link ReleaseBashScriptTest} 가 전부 본다) 이 래퍼는 Git for
 * Windows 의 bash 를 찾아 넘겨 주기만 한다. 그래서 여기서는 래퍼가 하는 일만 본다: bash 찾기(WSL 의 bash 는 쓰지 않는다) · 인자(값이 있는 옵션·
 * 공백이 든 경로) 그대로 넘기기 · 표준 입력 · 종료 코드 전달. Windows 에서만 돈다(다른 OS 는 스킵).
 */
// 오래 걸리는 테스트 — 평소 mvn test 에서 빠지고 릴리즈(-Pall-tests)에서 돈다. 따로 돌리려면 mvn test -Plong-running-tests (pom.xml 의 surefire 설명)
@Tag("long-running")
@DisplayName("릴리즈 래퍼(windows_release.cmd)")
class ReleaseCmdWrapperTest extends ReleaseHarness {

    @Override
    void assumeRunnable() {
        Assumptions.assumeTrue(WINDOWS, "Windows 에서만 돈다");
        Assumptions.assumeTrue(Files.exists(Path.of("scripts", "windows_release.cmd")), "래퍼가 없다");
        assumeBash();
    }

    @Override
    List<String> scriptFiles() {
        return List.of("release.sh", "windows_release.cmd");
    }

    @Override
    List<String> command(Path scriptsDir) {
        return List.of("cmd.exe", "/c", scriptsDir.resolve("windows_release.cmd").toString());
    }

    @Test
    @DisplayName("--help — 래퍼가 Git Bash 를 찾아 release.sh 의 사용법을 보여 주고 0 으로 끝난다")
    void helpGoesThroughGitBash() throws Exception {
        Result r = run(args("--help"));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("Usage:").contains("--build-only").contains("windows_release.cmd");
    }

    @Test
    @DisplayName("--build-only — 래퍼를 거쳐도 빌드·검증이 되고 git 은 그대로다")
    void buildOnlyThroughTheWrapper() throws Exception {
        Result r = run(args("--build-only"));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(repo.resolve("target/release/rag-agent-0.5.6.jar")).exists();
        assertThat(repo.resolve("target/release/rag-agent-0.5.6.jar.sha256")).exists();
        assertNothingChanged();
    }

    @Test
    @DisplayName("버전·값이 있는 옵션·스위치가 그대로 넘어간다 — 0.6.0 --next 0.7.0-SNAPSHOT --no-publish (확인은 표준 입력)")
    void argumentsPassThrough() throws Exception {
        List<String> a = args("0.6.0", "--no-publish");
        a.addAll(valueOpt("next", "0.7.0-SNAPSHOT"));

        Result r = run(a, Map.of(), ANSWER_ALL_YES);

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("tag")).isEqualTo("v0.6.0");
        assertThat(git("show", "HEAD:pom.xml")).contains("<version>0.7.0-SNAPSHOT</version>");
    }

    @Test
    @DisplayName("공백이 든 경로 인자가 쪼개지지 않고 도착한다 — 공백 있는 폴더의 --notes-file")
    void argumentsWithSpacesSurvive() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("my notes"));
        Path notes = dir.resolve("release notes.md");
        Files.writeString(notes, "- things\n");
        List<String> a = args("--no-publish");
        a.add("--notes-file");
        a.add(notes.toString());

        Result r = run(a, Map.of(), ANSWER_ALL_YES);

        // 스크립트는 파일이 없으면 "notes file not found" 로 멈춘다 — 통과했다는 것은 경로가 온전히 도착했다는 뜻이다
        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("tag")).isEqualTo("v0.5.6");
    }

    @Test
    @DisplayName("스크립트의 거절이 종료 코드로 전달된다 — 작업 트리가 더러우면 0 이 아니다")
    void refusalExitCodeIsPropagated() throws Exception {
        Files.writeString(repo.resolve("stray.txt"), "oops");

        Result r = run(args("--build-only"));

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("not clean");
    }

    @Test
    @DisplayName("래퍼로도 버전 확인을 묻는다 — 버전이 보이고, n 이면 빌드하지 않는다")
    void wrapperAsksForTheVersion() throws Exception {
        Result r = run(args(), Map.of(), "y\nn\n");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("Make a release now?").contains("Release version 0.5.6").contains("aborted before building");
        assertNothingChanged();
        assertThat(mvnCalls()).noneMatch(l -> l.contains("package"));
    }

    @Test
    @DisplayName("래퍼로도 --yes 는 거절된다 — 확인을 건너뛸 수 없다")
    void wrapperRejectsYes() throws Exception {
        Result r = run(args("--no-publish", "--yes"), Map.of(), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("no longer accepted");
        assertNothingChanged();
    }

    @Test
    @DisplayName("표준 입력이 스크립트의 확인 질문까지 간다 — n 이면 커밋하지 않는다")
    void stdinReachesTheConfirmation() throws Exception {
        Result r = run(args(), Map.of(), "n\n");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("aborted");
        assertNothingChanged();
    }

    @Test
    @DisplayName("RELEASE_BASH 가 없는 파일을 가리키면 이유를 알리고 멈춘다")
    void invalidReleaseBashIsExplained() throws Exception {
        Result r = run(args("--build-only"), Map.of("RELEASE_BASH", tmp.resolve("no-such").resolve("bash.exe").toString()), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("RELEASE_BASH").contains("does not exist");
        assertThat(mvnLog).doesNotExist();
    }

    @Test
    @DisplayName("RELEASE_BASH 로 다른 bash 를 지정할 수 있다")
    void releaseBashOverrideIsUsed() throws Exception {
        Result r = run(args("--help"), Map.of("RELEASE_BASH", findBash()), "");

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(r.out()).contains("Usage:");
    }
}
