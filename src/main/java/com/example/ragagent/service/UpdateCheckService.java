package com.example.ragagent.service;

import com.example.ragagent.config.HttpClientTimeouts;
import com.example.ragagent.model.AboutInfo;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 새 릴리즈 확인 — GitHub Releases 의 <b>최신 정식 릴리즈</b>({@code GET /repos/{owner}/{repo}/releases/latest} — draft·prerelease 는
 * API 가 이미 뺀다)를 지금 실행 중인 버전과 견주어 관리자에게 알린다. <b>알리기만 한다</b> — 내려받기·교체는 이 클래스의 일이 아니다.
 *
 * <p><b>새 버전으로 알리는 조건은 "버전이 더 높다"와 "그 릴리즈에 jar 가 첨부돼 있다" 둘 다다.</b> 태그만 먼저 게시하고 파일을 나중에 올리는
 * 동안 관리자가 받을 것이 없는 알림을 받지 않게 하려는 것이다 — jar 가 없으면 {@link Status#releaseWithoutJar} 로 사유만 보이고
 * 파일이 올라온 뒤의 다음 확인에서 알린다. jar 는 {@code rag-agent-*.jar}(소스·javadoc·plain 제외)이고, 업로드가 끝난
 * 자산({@code state=uploaded})이며, 내려받기 주소가 {@code https://github.com/{repo}/releases/download/} 아래일 때만 인정한다.
 *
 * <p><b>기본값은 꺼짐이고, 꺼져 있으면 밖으로 아무 요청도 나가지 않는다</b>(수동 확인 포함). 폐쇄망 배포가 전제인 앱이라 외부 호출은
 * 운영자가 명시적으로 켜야 한다. 켜면 기동 직후 한 번, 그 뒤 {@code app.update.cron}(기본 매일 09:00, {@code app.time-zone} 기준)에
 * 확인한다. 저장소는 공개 전제라 토큰을 받지 않는다(토큰이 필요한 비공개 저장소는 지원하지 않는다 — 인증 헤더가 있는 외부 요청을
 * 만들지 않기 위해서).
 *
 * <p>설정은 기동 시 한 번 읽는다({@code DatabaseBackupService} 와 같은 {@code @Value} 규약 — 재기동 필요). 값이 틀려도 기동은
 * 멈추지 않는다: 부가 기능이라 앱을 못 띄우는 것은 과하므로 ERROR 로그를 남기고 이 기능만 끈 채 {@link Status#configError} 로 화면에 알린다.
 *
 * <p>GitHub 응답은 신뢰 경계 밖이다 — 본문 크기를 제한하고(1MB), 릴리즈 노트는 잘라서(4,000자) 화면이 <b>글자로만</b> 그리게 하며(마크다운·HTML
 * 해석 없음), 링크는 {@code https://}(테스트용 루프백은 {@code http://})일 때만 싣는다. 요청의 {@code User-Agent} 는 일반 클라이언트
 * 이름이고 앱·사용자 식별 정보를 싣지 않는다. 사내 프록시는 JVM 표준 설정({@code -Dhttps.proxyHost}/{@code -Dhttps.proxyPort},
 * {@code JAVA_OPTS})으로 지나간다.
 */
@Service
public class UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final int MAX_BODY_BYTES = 1_048_576;
    static final int MAX_NOTES_CHARS = 4_000;
    /** 수동 확인 연타가 GitHub 의 비인증 한도(시간당 60회)를 태우지 않게 — 이 간격 안의 수동 확인은 직전 결과를 그대로 돌려준다. */
    static final Duration MANUAL_MIN_INTERVAL = Duration.ofSeconds(10);
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern JAR_NAME = Pattern.compile("rag-agent-[A-Za-z0-9._+-]{1,80}\\.jar");

    /** 릴리즈에 첨부된 파일 하나 — {@code url} 은 내려받기 주소로 허용된 것일 때만 채워진다. */
    public record Asset(String name, long size, String url) {}

    /**
     * 최신 릴리즈 하나. {@code version} 은 접두 {@code v} 를 뗀 표기, {@code url} 은 안전하다고 본 링크일 때만(아니면 null).
     * {@code jar} 는 첨부된 앱 jar — 없으면 null(그 릴리즈는 알리지 않는다).
     */
    public record Release(String tag, String version, String name, String notes, String url, Instant publishedAt, Asset jar) {}

    /** 마지막 확인의 결과. {@code error} 가 있으면 실패다. */
    public record CheckResult(String trigger, Instant checkedAt, boolean ok, String error) {}

    /**
     * {@code /admin} 카드가 그리는 것. {@code currentVersion} 이 null 이면 빌드 정보가 없는 개발 실행({@code devBuild})이라 비교하지
     * 않는다({@code updateAvailable=false}). {@code latest} 는 <b>마지막으로 성공한</b> 확인의 릴리즈이며 이후 확인이 실패해도 남는다.
     * {@code updateAvailable} 은 <b>더 높은 버전이고 jar 가 첨부된</b> 릴리즈일 때만 참이다 — 더 높은 버전이지만 jar 가 없으면
     * {@code releaseWithoutJar}.
     */
    public record Status(boolean enabled, String cron, String zone, String repo, String currentVersion, boolean devBuild,
                         boolean checking, CheckResult last, Release latest, boolean updateAvailable,
                         boolean releaseWithoutJar, String configError) {}

    private final AboutService about;
    private final boolean enabled;
    private final String cron;
    private final String zone;
    private final String repo;
    private final String apiBase;
    private final String configError;
    private final RestClient client;

    private volatile boolean checking;
    private volatile CheckResult last;
    private volatile Release latest;

    public UpdateCheckService(AboutService about,
                              @Value("${app.update.enabled:false}") boolean enabled,
                              @Value("${app.update.cron:0 0 9 * * *}") String cron,
                              @Value("${app.update.repo:skax-sh-min/RAG-Agent}") String repo,
                              @Value("${app.update.api-base:https://api.github.com}") String apiBase,
                              @Value("${app.time-zone:Asia/Seoul}") String timeZone) {
        this.about = about;
        this.cron = cron;
        this.zone = ZoneId.of(timeZone).getId();   // 틀린 값이면 기동이 멈춘다 — @Scheduled 의 zone 도 같다
        this.repo = repo == null ? "" : repo.strip();
        String base = apiBase == null ? "" : apiBase.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.apiBase = base;
        String problem = null;
        if (!REPO.matcher(this.repo).matches()) {
            problem = "app.update.repo 는 owner/name 모양이어야 합니다: " + abbreviate(this.repo, 60);
        } else if (!isAllowedBase(base)) {
            problem = "app.update.api-base 는 https:// 주소여야 합니다(테스트용으로만 루프백 http 허용): " + abbreviate(base, 60);
        }
        this.configError = problem;
        this.enabled = enabled && problem == null;
        if (enabled && problem != null) {
            log.error("[UPDATE-CHECK] 설정 오류로 새 버전 확인을 끈다 — {}", problem);
        }
        this.client = HttpClientTimeouts.restClientBuilder(5, 10).build();
    }

    // ── 실행 시점 ──────────────────────────────────────────────────────────────

    /** 기동 직후 한 번 — 기동을 늦추지 않도록 {@link ApplicationReadyEvent} 뒤 가상 스레드에서 돈다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        Thread.ofVirtual().name("update-check-startup").start(MdcPropagation.wrap(() -> runQuietly("startup")));
    }

    /** 정기 확인 — 꺼져 있으면 아무것도 하지 않는다. */
    @Scheduled(cron = "${app.update.cron:0 0 9 * * *}", zone = "${app.time-zone:Asia/Seoul}")
    void scheduled() {
        if (!enabled) return;
        runQuietly("scheduled");
    }

    private void runQuietly(String trigger) {
        try {
            check(trigger);
        } catch (RuntimeException e) {
            log.warn("[UPDATE-CHECK] {} 확인이 중단됐다: {}", trigger, e.toString());
        }
    }

    // ── 확인 ──────────────────────────────────────────────────────────────────

    /**
     * 지금 한 번 확인하고 갱신된 상태를 돌려준다. 확인이 <b>실패해도 예외를 던지지 않고</b> 결과에 담는다(정기 실행이 스케줄러 스레드를
     * 죽이지 않게). 꺼져 있거나 설정이 틀리면 {@link IllegalStateException} — 이때는 요청도 나가지 않는다.
     */
    public synchronized Status check(String trigger) {
        if (configError != null) throw new IllegalStateException(configError);
        if (!enabled) throw new IllegalStateException("새 버전 확인이 꺼져 있습니다 (UPDATE_CHECK_ENABLED=true 로 켜고 재기동).");
        CheckResult prev = last;
        if ("manual".equals(trigger) && prev != null
                && Duration.between(prev.checkedAt(), Instant.now()).compareTo(MANUAL_MIN_INTERVAL) < 0) {
            return status();
        }
        checking = true;
        try {
            Release found = fetchLatest();
            latest = found;
            last = new CheckResult(trigger, Instant.now(), true, null);
            AppVersion cur = currentVersion().orElse(null);
            AppVersion lat = AppVersion.parse(found.version()).orElse(null);
            boolean newer = cur != null && lat != null && lat.isNewerThan(cur);
            log.info("[UPDATE-CHECK] {} — 최신 {} / 현재 {}{}", trigger, found.version(),
                    cur == null ? "(개발 빌드)" : cur.text(),
                    !newer ? "" : found.jar() != null ? " → 새 버전이 있다" : " → 더 높은 버전이지만 jar 가 첨부되지 않아 알리지 않는다");
        } catch (CheckFailure e) {
            last = new CheckResult(trigger, Instant.now(), false, e.getMessage());
            log.warn("[UPDATE-CHECK] {} 실패: {}", trigger, e.getMessage());
        } catch (RuntimeException e) {
            String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + abbreviate(e.getMessage(), 200));
            last = new CheckResult(trigger, Instant.now(), false, "GitHub 에 접속하지 못했습니다 (" + msg + ")");
            log.warn("[UPDATE-CHECK] {} 접속 실패: {}", trigger, e.toString());
        } finally {
            checking = false;
        }
        return status();
    }

    public Status status() {
        Release l = latest;
        Optional<AppVersion> cur = currentVersion();
        boolean newer = l != null && cur.isPresent()
                && AppVersion.parse(l.version()).map(v -> v.isNewerThan(cur.get())).orElse(false);
        boolean available = newer && l.jar() != null;
        return new Status(enabled, cron, zone, repo, cur.map(AppVersion::text).orElse(null), about.info().devBuild(),
                checking, last, l, available, newer && l.jar() == null, configError);
    }

    /** 새 버전이 있는가 — 헤더 배지가 묻는다. */
    public boolean updateAvailable() {
        return status().updateAvailable();
    }

    private Optional<AppVersion> currentVersion() {
        AboutInfo info = about.info();
        return info.devBuild() ? Optional.empty() : AppVersion.parse(info.version());
    }

    // ── GitHub 호출 ────────────────────────────────────────────────────────────

    private record Raw(int status, byte[] body) {}

    private Release fetchLatest() {
        String url = apiBase + "/repos/" + repo + "/releases/latest";
        Raw raw = client.get().uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "update-check")
                .exchange((req, res) -> new Raw(res.getStatusCode().value(), res.getBody().readNBytes(MAX_BODY_BYTES + 1)));
        if (raw.status() == 404) {
            throw new CheckFailure("게시된 릴리즈가 없습니다 (저장소 " + repo + ")");
        }
        if (raw.status() == 403 || raw.status() == 429) {
            throw new CheckFailure("GitHub 가 요청을 거절했습니다 (HTTP " + raw.status() + ") — 요청 한도에 걸렸을 수 있으니 잠시 뒤 다시 확인하세요");
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            throw new CheckFailure("GitHub 응답 오류 (HTTP " + raw.status() + ")");
        }
        if (raw.body().length > MAX_BODY_BYTES) {
            throw new CheckFailure("GitHub 응답이 너무 큽니다");
        }
        return parseRelease(raw.body());
    }

    /** 응답 본문 → 릴리즈. 읽을 수 없는 모양이면 {@link CheckFailure}. 테스트가 직접 부른다. */
    Release parseRelease(byte[] body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new CheckFailure("GitHub 응답을 읽지 못했습니다 (JSON 이 아님)");
        }
        if (root == null || !root.isObject()) throw new CheckFailure("GitHub 응답을 읽지 못했습니다 (예상 밖의 모양)");
        if (root.path("draft").asBoolean(false) || root.path("prerelease").asBoolean(false)) {
            throw new CheckFailure("최신 릴리즈가 정식 릴리즈가 아닙니다 (draft/prerelease)");
        }
        String tag = root.path("tag_name").asText("");
        AppVersion version = AppVersion.parse(tag).orElseThrow(
                () -> new CheckFailure("릴리즈 태그의 버전 형식을 읽지 못했습니다: " + abbreviate(tag, 40)));
        Instant published = null;
        String publishedText = root.path("published_at").asText("");
        if (!publishedText.isBlank()) {
            try {
                published = Instant.parse(publishedText);
            } catch (RuntimeException ignored) {
                // 표시용일 뿐이다 — 읽지 못하면 비워 둔다
            }
        }
        return new Release(abbreviate(tag, 64), version.text(),
                abbreviate(root.path("name").asText(""), 200),
                abbreviate(root.path("body").asText(""), MAX_NOTES_CHARS),
                safeUrl(root.path("html_url").asText("")), published, pickJar(root.path("assets"), version.text()));
    }

    /**
     * 첨부 파일 중 앱 jar 를 고른다 — {@code rag-agent-*.jar}(소스·javadoc·plain 제외), 업로드가 끝난 것, 내려받기 주소가 허용된 것.
     * 여럿이면 {@code rag-agent-{version}.jar} 를 우선한다. 없으면 null.
     */
    Asset pickJar(JsonNode assets, String version) {
        if (assets == null || !assets.isArray()) return null;
        Asset best = null;
        for (JsonNode a : assets) {
            String name = a.path("name").asText("");
            if (!JAR_NAME.matcher(name).matches() || name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")
                    || name.endsWith("-plain.jar")) continue;
            String state = a.path("state").asText("uploaded");
            if (!"uploaded".equals(state)) continue;             // open = 업로드가 끝나지 않았다
            long size = a.path("size").asLong(0);
            String url = safeDownloadUrl(a.path("browser_download_url").asText(""));
            if (size <= 0 || url == null) continue;
            Asset candidate = new Asset(name, size, url);
            if (name.equals("rag-agent-" + version + ".jar")) return candidate;
            if (best == null) best = candidate;
        }
        return best;
    }

    /**
     * 내려받기 주소로 허용하는 것 — {@code https://github.com/{repo}/releases/download/…}. 테스트용으로 api-base 가 루프백이면 같은 루프백
     * 호스트의 같은 경로도 허용한다. 릴리즈 JSON 이 다른 호스트를 가리켜도 따라가지 않는다(실행할 파일을 받는 주소라서).
     */
    String safeDownloadUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 500) return null;
        try {
            URI u = URI.create(url.strip());
            if (u.getUserInfo() != null || u.getHost() == null || u.getQuery() != null || u.getFragment() != null) return null;
            String prefix = "/" + repo + "/releases/download/";
            if (u.getPath() == null || !u.getPath().startsWith(prefix) || u.getPath().contains("..")) return null;
            boolean github = "https".equalsIgnoreCase(u.getScheme()) && "github.com".equalsIgnoreCase(u.getHost());
            URI base = URI.create(apiBase);
            boolean sameLoopback = "http".equalsIgnoreCase(u.getScheme()) && isLoopback(u.getHost())
                    && isLoopback(base.getHost()) && u.getPort() == base.getPort();
            return github || sameLoopback ? url.strip() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 검증 보조 ──────────────────────────────────────────────────────────────

    static boolean isAllowedBase(String base) {
        try {
            URI u = URI.create(base);
            String scheme = u.getScheme();
            String host = u.getHost();
            if (host == null || u.getUserInfo() != null) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            return "http".equalsIgnoreCase(scheme) && isLoopback(host);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }

    /** 화면에 링크로 싣어도 되는 주소만 — 그 밖(javascript:, 사용자 정보가 든 주소 등)은 버린다. */
    static String safeUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 500) return null;
        return isAllowedBase(url.strip()) ? url.strip() : null;
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 확인이 실패한 사유 — 메시지가 그대로 화면에 간다(우리가 쓴 문장이다). */
    static final class CheckFailure extends RuntimeException {
        CheckFailure(String message) {
            super(message);
        }
    }
}
