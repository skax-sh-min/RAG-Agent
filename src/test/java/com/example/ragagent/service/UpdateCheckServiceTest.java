package com.example.ragagent.service;

import com.example.ragagent.model.AboutInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 새 릴리즈 확인 — 진짜 {@link HttpServer} 가 GitHub API 를 흉내 내므로 실제 HTTP 왕복(요청 경로·헤더·상태 코드·크기 제한)을 지난다.
 */
class UpdateCheckServiceTest {

    private static final String REPO = "owner/name";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** {@code /repos/owner/name/releases/latest} 가 주어진 상태·본문으로 답하는 서버의 base 주소. */
    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AboutService aboutWithVersion(String version) {
        AboutService about = mock(AboutService.class);
        when(about.info()).thenReturn(new AboutInfo("RAG Agent", version, null, "L", "u", "c", "21", "4"));
        return about;
    }

    private UpdateCheckService service(String currentVersion, boolean enabled, String repo, String apiBase) {
        return new UpdateCheckService(aboutWithVersion(currentVersion), enabled, "0 0 9 * * *", repo, apiBase, "Asia/Seoul");
    }

    private static String release(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"Release " + tag + "\",\"body\":\"- fix A\\n- fix B\","
                + "\"html_url\":\"https://github.com/owner/name/releases/tag/" + tag + "\","
                + "\"published_at\":\"2026-11-16T01:02:03Z\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[" + asset("rag-agent-" + tag.replaceFirst("^v", "") + ".jar", tag, 1234, "uploaded") + ","
                + asset("rag-agent-" + tag.replaceFirst("^v", "") + ".jar.sha256", tag, 100, "uploaded") + "]}";
    }

    private static String asset(String name, String tag, long size, String state) {
        return "{\"name\":\"" + name + "\",\"state\":\"" + state + "\",\"size\":" + size + ","
                + "\"browser_download_url\":\"https://github.com/owner/name/releases/download/" + tag + "/" + name + "\"}";
    }

    private static String releaseWithAssets(String tag, String assetsJson) {
        return "{\"tag_name\":\"" + tag + "\",\"assets\":[" + assetsJson + "]}";
    }

    private UpdateCheckService.Release parse(String json) {
        return service("0.5.6", true, REPO, "http://127.0.0.1:1").parseRelease(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("새 버전이 있으면 updateAvailable — 릴리즈 정보와 요청 모양(경로·Accept·User-Agent)도 확인")
    void newerReleaseIsAvailable() throws Exception {
        String base = serve(200, release("v0.5.7"));
        UpdateCheckService svc = service("0.5.6", true, REPO, base);

        UpdateCheckService.Status s = svc.check("manual");

        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.last().ok()).isTrue();
        assertThat(s.last().trigger()).isEqualTo("manual");
        assertThat(s.latest().version()).isEqualTo("0.5.7");
        assertThat(s.latest().tag()).isEqualTo("v0.5.7");
        assertThat(s.latest().notes()).contains("fix A").contains("fix B");
        assertThat(s.latest().url()).isEqualTo("https://github.com/owner/name/releases/tag/v0.5.7");
        assertThat(s.latest().publishedAt()).isNotNull();
        assertThat(svc.updateAvailable()).isTrue();
        assertThat(lastPath.get()).isEqualTo("/repos/owner/name/releases/latest");
        assertThat(lastAccept.get()).contains("application/vnd.github+json");
        assertThat(lastUserAgent.get()).isEqualTo("update-check");   // 앱·사용자 식별 정보를 싣지 않는다
    }

    @Test
    @DisplayName("같은 버전이면 새 버전이 아니다")
    void sameVersionIsNotAvailable() throws Exception {
        String base = serve(200, release("v0.5.6"));

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.updateAvailable()).isFalse();
        assertThat(s.latest().version()).isEqualTo("0.5.6");
    }

    @Test
    @DisplayName("SNAPSHOT 빌드는 그 번호의 정식 릴리즈가 나오면 새 버전으로 본다")
    void snapshotSeesItsRelease() throws Exception {
        String base = serve(200, release("v0.5.6"));

        assertThat(service("0.5.6-SNAPSHOT", true, REPO, base).check("manual").updateAvailable()).isTrue();
    }

    @Test
    @DisplayName("개발 빌드(버전 없음)는 비교하지 않는다 — 최신 정보는 보이되 updateAvailable 은 거짓")
    void devBuildDoesNotCompare() throws Exception {
        String base = serve(200, release("v9.9.9"));

        UpdateCheckService.Status s = service(null, true, REPO, base).check("manual");

        assertThat(s.devBuild()).isTrue();
        assertThat(s.currentVersion()).isNull();
        assertThat(s.latest().version()).isEqualTo("9.9.9");
        assertThat(s.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("꺼져 있으면 확인하지 않고 요청도 나가지 않는다")
    void disabledSendsNothing() throws Exception {
        String base = serve(200, release("v0.5.7"));
        UpdateCheckService svc = service("0.5.6", false, REPO, base);

        assertThat(svc.status().enabled()).isFalse();
        assertThatThrownBy(() -> svc.check("manual")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("꺼져 있습니다");
        svc.onReady();
        svc.scheduled();

        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("저장소 이름이 owner/name 이 아니면 기능을 끄고 사유를 알린다 — 기동은 막지 않는다")
    void badRepoDisablesTheFeature() throws Exception {
        String base = serve(200, release("v0.5.7"));
        UpdateCheckService svc = service("0.5.6", true, "../etc/passwd", base);

        UpdateCheckService.Status s = svc.status();
        assertThat(s.enabled()).isFalse();
        assertThat(s.configError()).contains("owner/name");
        assertThatThrownBy(() -> svc.check("manual")).isInstanceOf(IllegalStateException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    @DisplayName("api-base 는 https 만 — 평문 http 의 외부 주소와 사용자 정보가 든 주소는 거절")
    void badApiBaseDisablesTheFeature() {
        assertThat(service("0.5.6", true, REPO, "http://api.example.com").status().configError()).contains("https");
        assertThat(service("0.5.6", true, REPO, "https://user:pw@api.example.com").status().configError()).contains("https");
        assertThat(service("0.5.6", true, REPO, "ftp://api.example.com").status().configError()).contains("https");
        assertThat(service("0.5.6", true, REPO, "https://api.github.com/").status().configError()).isNull();
        assertThat(service("0.5.6", true, REPO, "http://127.0.0.1:8080").status().configError()).isNull();   // 테스트용 루프백
    }

    @Test
    @DisplayName("릴리즈가 없으면(404) 실패 결과에 사유를 담고 예외는 던지지 않는다")
    void noReleaseYet() throws Exception {
        String base = serve(404, "{\"message\":\"Not Found\"}");

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.last().ok()).isFalse();
        assertThat(s.last().error()).contains("게시된 릴리즈가 없습니다");
        assertThat(s.latest()).isNull();
        assertThat(s.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("403·429 는 요청 한도일 수 있다고 안내한다")
    void rateLimited() throws Exception {
        String base = serve(403, "{\"message\":\"rate limit exceeded\"}");

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.last().ok()).isFalse();
        assertThat(s.last().error()).contains("HTTP 403").contains("한도");
    }

    @Test
    @DisplayName("서버 오류(500)도 결과에 담는다")
    void serverError() throws Exception {
        String base = serve(500, "oops");

        assertThat(service("0.5.6", true, REPO, base).check("manual").last().error()).contains("HTTP 500");
    }

    @Test
    @DisplayName("접속이 안 되면 실패 결과 — 던지지 않는다")
    void unreachable() throws Exception {
        String base = serve(200, "{}");
        server.stop(0);            // 포트는 닫고 주소만 쓴다
        server = null;

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.last().ok()).isFalse();
        assertThat(s.last().error()).contains("접속하지 못했습니다");
    }

    @Test
    @DisplayName("실패한 뒤에도 마지막으로 성공한 최신 릴리즈는 남는다")
    void latestSurvivesALaterFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean first = calls.incrementAndGet() == 1;
            byte[] bytes = (first ? release("v0.5.7") : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 200 : 500, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        UpdateCheckService svc = service("0.5.6", true, REPO, "http://127.0.0.1:" + server.getAddress().getPort());

        svc.check("scheduled");
        UpdateCheckService.Status s = svc.check("scheduled");

        assertThat(s.last().ok()).isFalse();
        assertThat(s.latest().version()).isEqualTo("0.5.7");
        assertThat(s.updateAvailable()).isTrue();
    }

    @Test
    @DisplayName("수동 확인을 연달아 눌러도 GitHub 에는 한 번만 간다(10초 간격) — 정기 확인은 막지 않는다")
    void manualChecksAreThrottled() throws Exception {
        String base = serve(200, release("v0.5.7"));
        UpdateCheckService svc = service("0.5.6", true, REPO, base);

        svc.check("manual");
        svc.check("manual");
        svc.check("manual");
        assertThat(hits.get()).isEqualTo(1);

        svc.check("scheduled");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("draft·prerelease 이거나 버전 형식을 읽을 수 없는 태그는 새 버전으로 치지 않는다")
    void unusableReleasesAreRejected() throws Exception {
        UpdateCheckService svc = service("0.5.6", true, REPO, "http://127.0.0.1:1");

        assertThatThrownBy(() -> svc.parseRelease("{\"tag_name\":\"v9.0.0\",\"prerelease\":true}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("정식 릴리즈가 아닙니다");
        assertThatThrownBy(() -> svc.parseRelease("{\"tag_name\":\"v9.0.0\",\"draft\":true}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("정식 릴리즈가 아닙니다");
        assertThatThrownBy(() -> svc.parseRelease("{\"tag_name\":\"nightly\"}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("버전 형식");
        assertThatThrownBy(() -> svc.parseRelease("not json".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("읽지 못했습니다");
        assertThatThrownBy(() -> svc.parseRelease("[]".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("읽지 못했습니다");
    }

    @Test
    @DisplayName("릴리즈 링크는 https 일 때만 싣는다 — javascript: 같은 주소는 버린다")
    void unsafeLinkIsDropped() throws Exception {
        UpdateCheckService svc = service("0.5.6", true, REPO, "http://127.0.0.1:1");

        UpdateCheckService.Release bad = svc.parseRelease(
                "{\"tag_name\":\"v0.5.7\",\"html_url\":\"javascript:alert(1)\"}".getBytes(StandardCharsets.UTF_8));
        UpdateCheckService.Release http = svc.parseRelease(
                "{\"tag_name\":\"v0.5.7\",\"html_url\":\"http://evil.example/x\"}".getBytes(StandardCharsets.UTF_8));
        UpdateCheckService.Release none = svc.parseRelease("{\"tag_name\":\"v0.5.7\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(bad.url()).isNull();
        assertThat(http.url()).isNull();
        assertThat(none.url()).isNull();
        assertThat(none.notes()).isEmpty();
        assertThat(none.publishedAt()).isNull();
    }

    @Test
    @DisplayName("jar 가 첨부되지 않은 릴리즈는 알리지 않는다 — 더 높은 버전이어도 updateAvailable 은 거짓, 사유(releaseWithoutJar)만 남는다")
    void releaseWithoutJarIsNotAnnounced() throws Exception {
        String base = serve(200, "{\"tag_name\":\"v0.5.7\",\"name\":\"n\",\"body\":\"b\",\"assets\":[]}");
        UpdateCheckService svc = service("0.5.6", true, REPO, base);

        UpdateCheckService.Status s = svc.check("manual");

        assertThat(s.last().ok()).isTrue();
        assertThat(s.latest().version()).isEqualTo("0.5.7");
        assertThat(s.latest().jar()).isNull();
        assertThat(s.updateAvailable()).isFalse();
        assertThat(s.releaseWithoutJar()).isTrue();
        assertThat(svc.updateAvailable()).isFalse();
    }

    @Test
    @DisplayName("assets 필드가 아예 없어도 jar 없음으로 본다")
    void missingAssetsFieldMeansNoJar() {
        assertThat(parse("{\"tag_name\":\"v0.5.7\"}").jar()).isNull();
    }

    @Test
    @DisplayName("jar 가 있으면 이름·크기·주소를 싣는다")
    void jarIsReported() throws Exception {
        String base = serve(200, release("v0.5.7"));

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.updateAvailable()).isTrue();
        assertThat(s.releaseWithoutJar()).isFalse();
        assertThat(s.latest().jar().name()).isEqualTo("rag-agent-0.5.7.jar");
        assertThat(s.latest().jar().size()).isEqualTo(1234);
        assertThat(s.latest().jar().url()).isEqualTo("https://github.com/owner/name/releases/download/v0.5.7/rag-agent-0.5.7.jar");
    }

    @Test
    @DisplayName("같은 버전이면 jar 가 없어도 releaseWithoutJar 가 아니다 — 알릴 새 버전이 아니므로")
    void sameVersionWithoutJarIsQuiet() throws Exception {
        String base = serve(200, "{\"tag_name\":\"v0.5.6\",\"assets\":[]}");

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.updateAvailable()).isFalse();
        assertThat(s.releaseWithoutJar()).isFalse();
    }

    @Test
    @DisplayName("소스·javadoc·plain jar, .sha256, 다른 이름의 파일은 앱 jar 가 아니다")
    void onlyTheApplicationJarCounts() {
        String assets = String.join(",",
                asset("rag-agent-0.5.7-sources.jar", "v0.5.7", 10, "uploaded"),
                asset("rag-agent-0.5.7-javadoc.jar", "v0.5.7", 10, "uploaded"),
                asset("rag-agent-0.5.7-plain.jar", "v0.5.7", 10, "uploaded"),
                asset("rag-agent-0.5.7.jar.sha256", "v0.5.7", 10, "uploaded"),
                asset("other-0.5.7.jar", "v0.5.7", 10, "uploaded"),
                asset("rag-agent-0.5.7.zip", "v0.5.7", 10, "uploaded"));

        assertThat(parse(releaseWithAssets("v0.5.7", assets)).jar()).isNull();
    }

    @Test
    @DisplayName("업로드가 끝나지 않은 자산(state=open)과 크기가 0인 자산은 인정하지 않는다")
    void incompleteAssetsAreIgnored() {
        assertThat(parse(releaseWithAssets("v0.5.7", asset("rag-agent-0.5.7.jar", "v0.5.7", 100, "open"))).jar()).isNull();
        assertThat(parse(releaseWithAssets("v0.5.7", asset("rag-agent-0.5.7.jar", "v0.5.7", 0, "uploaded"))).jar()).isNull();
    }

    @Test
    @DisplayName("여러 jar 가 있으면 rag-agent-{버전}.jar 를 우선한다")
    void prefersTheExactName() {
        String assets = String.join(",",
                asset("rag-agent-0.5.7-rc1.jar", "v0.5.7", 10, "uploaded"),
                asset("rag-agent-0.5.7.jar", "v0.5.7", 20, "uploaded"));

        assertThat(parse(releaseWithAssets("v0.5.7", assets)).jar().name()).isEqualTo("rag-agent-0.5.7.jar");
        assertThat(parse(releaseWithAssets("v0.5.7", assets)).jar().size()).isEqualTo(20);
    }

    @Test
    @DisplayName("내려받기 주소는 github.com 의 이 저장소 releases/download 아래 https 만 — 다른 호스트·다른 저장소·http·사용자 정보는 버린다")
    void downloadUrlMustBeGithubReleaseDownload() {
        UpdateCheckService svc = service("0.5.6", true, REPO, "https://api.github.com");
        String ok = "https://github.com/owner/name/releases/download/v1/rag-agent-1.0.0.jar";

        assertThat(svc.safeDownloadUrl(ok)).isEqualTo(ok);
        assertThat(svc.safeDownloadUrl("https://evil.example/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
        assertThat(svc.safeDownloadUrl("https://github.com/other/repo/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
        assertThat(svc.safeDownloadUrl("http://github.com/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
        assertThat(svc.safeDownloadUrl("https://user:pw@github.com/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
        assertThat(svc.safeDownloadUrl("https://github.com/owner/name/releases/download/../../x.jar")).isNull();
        assertThat(svc.safeDownloadUrl("https://github.com/owner/name/releases/download/v1/x.jar?token=1")).isNull();
        assertThat(svc.safeDownloadUrl("https://github.com.evil.example/owner/name/releases/download/v1/x.jar")).isNull();
        assertThat(svc.safeDownloadUrl("")).isNull();
        assertThat(svc.safeDownloadUrl(null)).isNull();
        // 루프백 api-base(테스트) 여도 github.com 규칙은 그대로이고, 같은 루프백 호스트·포트의 같은 경로만 더 허용한다
        UpdateCheckService local = service("0.5.6", true, REPO, "http://127.0.0.1:9999");
        assertThat(local.safeDownloadUrl("http://127.0.0.1:9999/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNotNull();
        assertThat(local.safeDownloadUrl("http://127.0.0.1:8888/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
        assertThat(service("0.5.6", true, REPO, "https://api.github.com")
                .safeDownloadUrl("http://127.0.0.1:9999/owner/name/releases/download/v1/rag-agent-1.0.0.jar")).isNull();
    }

    @Test
    @DisplayName("체크섬 파일(<jar>.sha256)은 그 jar 의 것만 인정한다 — 없거나 4KB 를 넘거나 다른 jar 의 것이면 null")
    void checksumAssetIsPickedPerJar() {
        String jar = asset("rag-agent-0.5.7.jar", "v0.5.7", 1000, "uploaded");
        String sum = asset("rag-agent-0.5.7.jar.sha256", "v0.5.7", 100, "uploaded");

        assertThat(parse(releaseWithAssets("v0.5.7", jar + "," + sum)).checksum().name()).isEqualTo("rag-agent-0.5.7.jar.sha256");
        assertThat(parse(releaseWithAssets("v0.5.7", jar)).checksum()).isNull();
        assertThat(parse(releaseWithAssets("v0.5.7", jar + "," + asset("rag-agent-0.5.7.jar.sha256", "v0.5.7", 5000, "uploaded"))).checksum()).isNull();
        assertThat(parse(releaseWithAssets("v0.5.7", jar + "," + asset("rag-agent-0.5.6.jar.sha256", "v0.5.7", 100, "uploaded"))).checksum()).isNull();
        assertThat(parse(releaseWithAssets("v0.5.7", jar + "," + asset("rag-agent-0.5.7.jar.sha256", "v0.5.7", 100, "open"))).checksum()).isNull();
        // jar 가 없으면 체크섬만 있어도 의미가 없다
        assertThat(parse(releaseWithAssets("v0.5.7", sum)).jar()).isNull();
        assertThat(parse(releaseWithAssets("v0.5.7", sum)).checksum()).isNull();
    }

    @Test
    @DisplayName("릴리즈 노트는 4,000자에서 자른다")
    void notesAreTruncated() throws Exception {
        UpdateCheckService svc = service("0.5.6", true, REPO, "http://127.0.0.1:1");
        String body = "{\"tag_name\":\"v0.5.7\",\"body\":\"" + "x".repeat(10_000) + "\"}";

        UpdateCheckService.Release r = svc.parseRelease(body.getBytes(StandardCharsets.UTF_8));

        assertThat(r.notes()).hasSize(UpdateCheckService.MAX_NOTES_CHARS + 1).endsWith("…");
    }

    @Test
    @DisplayName("응답이 1MB 를 넘으면 읽다 말고 거절한다")
    void oversizedResponseIsRejected() throws Exception {
        String base = serve(200, "{\"tag_name\":\"v0.5.7\",\"body\":\"" + "x".repeat(UpdateCheckService.MAX_BODY_BYTES + 10) + "\"}");

        UpdateCheckService.Status s = service("0.5.6", true, REPO, base).check("manual");

        assertThat(s.last().ok()).isFalse();
        assertThat(s.last().error()).contains("너무 큽니다");
    }
}
