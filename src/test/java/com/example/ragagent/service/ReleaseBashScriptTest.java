package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/release.sh} 를 실제 bash 로 돌려 릴리즈 흐름 전체(사전 점검 · 빌드 · 검증 · 커밋/태그/다음 SNAPSHOT · 푸시 · 게시)를 확인한다.
 * Windows 에서는 Git Bash 가 있어야 하고 없으면 스킵한다. 공통 바탕은 {@link ReleaseHarness}.
 */
@DisplayName("릴리즈 스크립트(bash)")
class ReleaseBashScriptTest extends ReleaseHarness {

    @Override
    void assumeRunnable() {
        assumeBash();
    }

    // ── --build-only ────────────────────────────────────────────────────────

    @Test
    @DisplayName("--build-only: 빌드·검증하고 산출물(jar + sha256sum 형식 체크섬)을 만들되 pom 은 되돌리고 git 은 건드리지 않는다")
    void buildOnlyBuildsAndVerifiesButTouchesNothing() throws Exception {
        Result r = run(args(opt(Opt.BUILD_ONLY)));

        assertThat(r.exit()).as(r.out()).isZero();
        Path jar = repo.resolve("target/release/rag-agent-0.5.6.jar");
        assertThat(jar).exists();
        assertThat(Files.readString(repo.resolve("target/release/rag-agent-0.5.6.jar.sha256")))
                .isEqualTo(sha256(jar) + "  rag-agent-0.5.6.jar\n");
        assertThat(jarEntry(jar, "META-INF/build-info.properties")).contains("build.version=0.5.6");   // SNAPSHOT 이 아니라 릴리즈 버전으로 빌드했다
        assertThat(Files.readString(mvnLog)).contains("clean package").doesNotContain("-DskipTests");
        assertNothingChanged();
        assertThat(ghCalls()).as("--build-only 는 gh 를 부르지 않는다").isEmpty();
        assertThat(r.out()).contains("built and verified");
    }

    // ── 전체 릴리즈 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("전체: 릴리즈 커밋 → 주석 태그 → 다음 SNAPSHOT 커밋 → 브랜치·태그 푸시 → 초안 릴리즈에 jar+체크섬을 올리고 게시")
    void fullReleaseCommitsTagsBumpsPushesAndPublishes() throws Exception {
        Result r = run(args(opt(Opt.YES)));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("log", "--format=%s", "-n", "3")).isEqualTo("chore: begin 0.5.7-SNAPSHOT\nrelease: v0.5.6\ninitial");
        assertThat(git("show", "v0.5.6:pom.xml")).contains("<version>0.5.6</version>").doesNotContain("SNAPSHOT");
        assertThat(git("show", "HEAD:pom.xml")).contains("<version>0.5.7-SNAPSHOT</version>");
        assertThat(git("show", "HEAD:pom.xml")).contains("<version>4.1.2</version>");               // 부모의 버전은 그대로
        assertThat(git("cat-file", "-t", "v0.5.6")).isEqualTo("tag");                               // 주석 태그
        assertThat(git("status", "--porcelain")).isEmpty();
        assertThat(remoteGit("tag")).isEqualTo("v0.5.6");
        assertThat(remoteGit("log", "--format=%s", "-n", "3", "main")).isEqualTo(git("log", "--format=%s", "-n", "3"));

        List<String> calls = ghCalls();
        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).isEqualTo("auth status");
        assertThat(calls.get(1)).startsWith("release create v0.5.6 ").contains("rag-agent-0.5.6.jar ").contains("rag-agent-0.5.6.jar.sha256 ")
                .endsWith("--title 0.5.6 --generate-notes --verify-tag --draft");
        assertThat(calls.get(2)).isEqualTo("release edit v0.5.6 --draft=false");                    // 올린 뒤에 게시
        assertThat(r.out()).contains("published v0.5.6");
    }

    @Test
    @DisplayName("--no-publish: 커밋·태그·다음 SNAPSHOT 까지 로컬에서 하고, 푸시와 gh 는 하지 않고 명령만 알려 준다")
    void noPublishStaysLocal() throws Exception {
        Result r = run(args(opt(Opt.NO_PUBLISH), opt(Opt.YES)));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("tag")).isEqualTo("v0.5.6");
        assertThat(git("log", "--format=%s", "-n", "2")).isEqualTo("chore: begin 0.5.7-SNAPSHOT\nrelease: v0.5.6");
        assertThat(remoteGit("tag")).isEmpty();
        assertThat(remoteGit("rev-list", "--count", "main")).isEqualTo("1");
        assertThat(ghCalls()).isEmpty();
        assertThat(r.out()).contains("git push origin main").contains("release create v0.5.6");
        assertThat(repo.resolve("target/release/rag-agent-0.5.6.jar")).exists();
    }

    @Test
    @DisplayName("버전을 지정하면 그 버전으로, --next 를 주면 그 SNAPSHOT 으로 이어 간다")
    void explicitVersionAndNext() throws Exception {
        List<String> a = args("0.6.0", opt(Opt.YES));
        a.addAll(valueOpt("next", "0.7.0-SNAPSHOT"));

        Result r = run(a);

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("tag")).isEqualTo("v0.6.0");
        assertThat(git("show", "v0.6.0:pom.xml")).contains("<version>0.6.0</version>");
        assertThat(git("show", "HEAD:pom.xml")).contains("<version>0.7.0-SNAPSHOT</version>");
        assertThat(repo.resolve("target/release/rag-agent-0.6.0.jar")).exists();
        assertThat(ghCalls().get(1)).contains("release create v0.6.0 ").contains("--title 0.6.0");
    }

    @Test
    @DisplayName("기본 다음 버전은 patch + 1 이다 (0.5.9 → 0.5.10-SNAPSHOT — 문자열 정렬이 아니라 숫자)")
    void defaultNextVersionIsPatchPlusOne() throws Exception {
        Result r = run(args("0.5.9", opt(Opt.NO_PUBLISH), opt(Opt.YES)));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(git("show", "HEAD:pom.xml")).contains("<version>0.5.10-SNAPSHOT</version>");
    }

    @Test
    @DisplayName("--skip-tests 는 Maven 에 -DskipTests 를 넘긴다")
    void skipTestsPassesDskipTests() throws Exception {
        Result r = run(args(opt(Opt.BUILD_ONLY), opt(Opt.SKIP_TESTS)));

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(Files.readString(mvnLog)).contains("clean package").contains("-DskipTests");
    }

    @Test
    @DisplayName("--notes-file 을 주면 gh 에 --notes-file 로 넘기고 --generate-notes 는 쓰지 않는다")
    void notesFileIsPassedToGh() throws Exception {
        Path notes = tmp.resolve("notes.md");
        Files.writeString(notes, "- changed things\n");
        List<String> a = args(opt(Opt.YES));
        a.addAll(valueOpt("notes-file", notes.toString()));

        Result r = run(a);

        assertThat(r.exit()).as(r.out()).isZero();
        String create = ghCalls().get(1);
        assertThat(create).contains("--notes-file ").contains("notes.md").doesNotContain("--generate-notes");
    }

    @Test
    @DisplayName("pom 의 줄바꿈(CRLF)을 그대로 보존한다 — 버전 줄만 바뀌고 diff 가 한 줄이다")
    void pomLineEndingsArePreserved() throws Exception {
        writePom("0.5.6-SNAPSHOT", "\r\n");
        exec(repo, "git", "add", "pom.xml");
        exec(repo, "git", "commit", "-q", "-m", "pom crlf");
        exec(repo, "git", "push", "-q", "origin", "main");

        Result r = run(args(opt(Opt.NO_PUBLISH), opt(Opt.YES)));

        assertThat(r.exit()).as(r.out()).isZero();
        String released = Files.readString(repo.resolve("pom.xml"));      // 다음 SNAPSHOT 이 든 현재 pom
        assertThat(released).contains("<version>0.5.7-SNAPSHOT</version>");
        assertThat(released.chars().filter(c -> c == '\n').count()).isEqualTo(released.chars().filter(c -> c == '\r').count());
        assertThat(git("diff", "--numstat", "HEAD~2", "HEAD", "--", "pom.xml")).startsWith("1\t1\t");
    }

    // ── 사전 점검: 실패해도 아무것도 바뀌지 않는다 ──────────────────────────

    @Test
    @DisplayName("작업 트리가 깨끗하지 않으면(추적 파일 수정·새 파일) 거절한다 — 새 파일은 jar 에 섞여 들어간다")
    void dirtyTreeIsRefused() throws Exception {
        Files.writeString(repo.resolve("stray.txt"), "oops");

        Result r = run(args(opt(Opt.BUILD_ONLY)));

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("not clean").contains("stray.txt");
        assertThat(repo.resolve("target/release")).doesNotExist();
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("1");
    }

    @Test
    @DisplayName("이미 있는 태그면 거절한다")
    void existingTagIsRefused() throws Exception {
        exec(repo, "git", "tag", "v0.5.6");

        Result r = run(args(opt(Opt.BUILD_ONLY)));

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("v0.5.6 already exists");
        assertThat(repo.resolve("target/release")).doesNotExist();
        assertThat(mvnLog).doesNotExist();
    }

    @Test
    @DisplayName("main 이 아닌 브랜치는 거절하고, --any-branch 를 주면 허용한다")
    void otherBranchNeedsAnyBranch() throws Exception {
        exec(repo, "git", "checkout", "-q", "-b", "feature");

        Result refused = run(args(opt(Opt.BUILD_ONLY)));
        assertThat(refused.exit()).isNotZero();
        assertThat(refused.out()).contains("'feature'").contains("main");
        assertThat(mvnLog).doesNotExist();

        Result allowed = run(args(opt(Opt.BUILD_ONLY), opt(Opt.ANY_BRANCH)));
        assertThat(allowed.exit()).as(allowed.out()).isZero();
    }

    @Test
    @DisplayName("원격보다 뒤처졌으면 거절한다 — 남의 변경 위에 릴리즈를 만들지 않는다")
    void behindOriginIsRefused() throws Exception {
        Path other = tmp.resolve("other");
        exec(tmp, "git", "clone", "-q", remote.toString(), other.toString());
        exec(other, "git", "config", "user.name", "Other");
        exec(other, "git", "config", "user.email", "other@test.invalid");
        Files.writeString(other.resolve("new.txt"), "x");
        exec(other, "git", "add", "-A");
        exec(other, "git", "commit", "-q", "-m", "someone else");
        exec(other, "git", "push", "-q", "origin", "main");

        Result r = run(args(opt(Opt.BUILD_ONLY)));

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("behind origin/main");
        assertThat(mvnLog).doesNotExist();
    }

    @Test
    @DisplayName("pom 이 SNAPSHOT 이 아니면 버전을 직접 줘야 한다 — 직접 주면 진행한다")
    void nonSnapshotPomNeedsExplicitVersion() throws Exception {
        writePom("0.5.6", "\n");
        exec(repo, "git", "add", "pom.xml");
        exec(repo, "git", "commit", "-q", "-m", "pom release version");
        exec(repo, "git", "push", "-q", "origin", "main");

        Result refused = run(args(opt(Opt.BUILD_ONLY)));
        assertThat(refused.exit()).isNotZero();
        assertThat(refused.out()).contains("not X.Y.Z-SNAPSHOT");

        Result ok = run(args("0.5.7", opt(Opt.BUILD_ONLY)));
        assertThat(ok.exit()).as(ok.out()).isZero();
        assertThat(repo.resolve("target/release/rag-agent-0.5.7.jar")).exists();
    }

    @Test
    @DisplayName("릴리즈 버전은 X.Y.Z 만 — 1.2 · v1.2.3 은 거절")
    void invalidVersionIsRefused() throws Exception {
        for (String bad : List.of("1.2", "v1.2.3")) {
            Result r = run(args(bad, opt(Opt.BUILD_ONLY)));
            assertThat(r.exit()).as(bad + "\n" + r.out()).isNotZero();
            assertThat(r.out()).as(bad).contains("release version must look like");
        }
        assertThat(mvnLog).doesNotExist();
    }

    @Test
    @DisplayName("Java 21 미만이면 빌드 전에 거절한다")
    void oldJavaIsRefused() throws Exception {
        Result r = run(args(opt(Opt.BUILD_ONLY)), Map.of("FAKE_MVN_JAVA", "17.0.9"), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("Java 21+");
        assertThat(repo.resolve("target")).doesNotExist();
    }

    @Test
    @DisplayName("게시하려는데 gh 가 없으면 아무것도 바꾸기 전에 거절한다 — --no-publish 안내를 준다")
    void missingGhIsRefusedBeforeAnyChange() throws Exception {
        Result r = run(args(opt(Opt.YES)), Map.of("RELEASE_GH", tmp.resolve("no-such-gh").toString().replace('\\', '/')), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("GitHub CLI").containsIgnoringCase("publish");
        assertThat(mvnLog).doesNotExist();
        assertNothingChanged();
    }

    @Test
    @DisplayName("gh 에 로그인하지 않았으면 거절한다")
    void unauthenticatedGhIsRefused() throws Exception {
        Result r = run(args(opt(Opt.YES)), Map.of("FAKE_GH_UNAUTH", "1"), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("not logged in");
        assertThat(mvnLog).doesNotExist();
        assertNothingChanged();
    }

    // ── 빌드·검증 실패: pom 을 되돌린다 ─────────────────────────────────────

    @Test
    @DisplayName("빌드가 실패하면 pom 을 되돌리고 커밋·태그를 만들지 않는다")
    void buildFailureRestoresThePom() throws Exception {
        Result r = run(args(opt(Opt.YES)), Map.of("FAKE_MVN_FAIL", "1"), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("build failed");
        assertNothingChanged();
        assertThat(Files.readString(repo.resolve("pom.xml"))).contains("0.5.6-SNAPSHOT");
        assertThat(ghCalls()).doesNotContain("release edit v0.5.6 --draft=false");
    }

    @Test
    @DisplayName("jar 안의 build.version 이 릴리즈 버전과 다르면 릴리즈하지 않는다 (앱도 이런 jar 의 업데이트를 거절한다)")
    void wrongJarVersionIsNotReleased() throws Exception {
        Result r = run(args(opt(Opt.YES)), Map.of("FAKE_MVN_WRONG_VERSION", "1"), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("build.version='9.9.9'");
        assertNothingChanged();
    }

    // ── 확인·게시 단계 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("확인에 n 이라고 답하면 커밋하지 않는다 — 산출물은 남고 pom 은 원래대로")
    void decliningTheConfirmationChangesNothing() throws Exception {
        Result r = run(args(), Map.of(), "n\n");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("aborted");
        assertNothingChanged();
        assertThat(repo.resolve("target/release/rag-agent-0.5.6.jar")).exists();
        assertThat(ghCalls()).doesNotContain("release edit v0.5.6 --draft=false");
    }

    @Test
    @DisplayName("y 라고 답하면 진행한다")
    void answeringYesProceeds() throws Exception {
        Result r = run(args(), Map.of(), "y\n");

        assertThat(r.exit()).as(r.out()).isZero();
        assertThat(remoteGit("tag")).isEqualTo("v0.5.6");
    }

    @Test
    @DisplayName("초안 릴리즈 생성이 실패하면 — 태그는 이미 푸시됐고, 게시(edit)는 하지 않고, 다시 시도할 명령을 알려 준다")
    void ghFailureAfterPushLeavesAHelpfulMessage() throws Exception {
        Result r = run(args(opt(Opt.YES)), Map.of("FAKE_GH_CREATE_FAIL", "1"), "");

        assertThat(r.exit()).isNotZero();
        assertThat(r.out()).contains("retry").contains("release create v0.5.6");
        assertThat(remoteGit("tag")).isEqualTo("v0.5.6");
        assertThat(ghCalls()).noneMatch(l -> l.startsWith("release edit"));
        assertThat(repo.resolve("target/release/rag-agent-0.5.6.jar.sha256")).exists();
    }
}
