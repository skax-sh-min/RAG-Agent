package com.example.ragagent.ratelimit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.security.GuestIdentityResolver;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

public class RateLimitFilter extends OncePerRequestFilter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(10_000)
            .build();

    private final AppProperties appProperties;
    private final CurrentUser currentUser;
    private final ClientIpResolver clientIpResolver;

    RateLimitFilter(AppProperties appProperties, CurrentUser currentUser, ClientIpResolver clientIpResolver) {
        this.appProperties = appProperties;
        this.currentUser = currentUser;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        AppProperties.RateLimitConfig cfg = appProperties.rateLimitSafe();
        if (!cfg.enabled()) {
            chain.doFilter(req, res);
            return;
        }

        String policy = policyFor(req);
        int limit = limitFor(cfg, policy);
        String key = clientKey(req) + ":" + policy;

        Bucket bucket = buckets.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(limit, Refill.greedy(limit, Duration.ofMinutes(1))))
                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(req, res);
        } else {
            long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(waitSeconds));
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("""
                    {"errorCode":"RAG-RATE-001","message":"Rate limit exceeded. Retry-After: %ds"}""".formatted(waitSeconds));
        }
    }

    /**
     * Which bucket a request draws from.
     *
     * <p><b>The document rules are method-aware on purpose.</b> Matching {@code "/documents"} on the
     * path alone put every read of that area — the {@code /documents} page itself, the
     * {@code /ui/documents/list} refresh the upload flow fires when a batch finishes, exports, tag
     * and display-name edits — into the same bucket as an actual upload, whose limit is a deliberately
     * small {@code upload-per-minute} (10). Loading the page and then uploading ten selected files is
     * eleven tokens, so the last file of a normal multi-file upload was refused with a 429 that the UI
     * could only render as "서버 오류". Writes are what that limit is for; reads belong in {@code default}.
     */
    String policyFor(HttpServletRequest req) {
        String path = req.getRequestURI();
        boolean write = isWrite(req.getMethod());
        if (path.contains("/chat")) return "chat";
        if (write && path.contains("/documents/sync")) return "sync";
        if (write && isFileUpload(path)) return "upload";
        if (path.contains("/images/")) return "image";
        return "default";
    }

    /**
     * The endpoints that accept new bytes from a client.
     *
     * <p>{@code /curated/submissions/images} 는 문서가 아니지만 <b>업로드</b>다 — 이 버킷의 기준은
     * "문서 경로인가"가 아니라 "새 파일을 받는가"이다. 게다가 그쪽은 인증 없이도 부를 수 있는
     * 유일한 바이너리 쓰기 경로라({@code CuratedImageStore} 클래스 주석) 오히려 여기 있어야 한다.
     * 예전에는 {@code default}(분당 120)에 떨어져서, 파일당 5MB × 120 = 분당 600MB 를 게스트가
     * 밀어 넣을 수 있었다 — 저장 상한은 기본이 무제한이다. 분당 10 은 한 제안이 담을 수 있는
     * 이미지 수({@code CuratedImageStore.MAX_IMAGES_PER_SUBMISSION})와 같은 값이라, 정상적인
     * 작성 한 번은 그대로 지나간다.
     *
     * <p>경로 문자열에 {@code /images/}(뒤 슬래시)가 없으므로 아래 이미지 <b>조회</b> 버킷과 겹치지
     * 않는다 — 그쪽은 GET 이라 {@code write} 검사에서도 이미 갈린다.
     */
    private static boolean isFileUpload(String path) {
        return path.endsWith("/ui/documents/upload")
                || path.endsWith("/api/v1/documents")
                || path.endsWith("/curated/submissions/images");
    }

    private static boolean isWrite(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
    }

    int limitFor(AppProperties.RateLimitConfig cfg, String policy) {
        return switch (policy) {
            case "chat"   -> cfg.chatPerMinute();
            case "upload" -> cfg.uploadPerMinute();
            case "sync"   -> cfg.syncPerMinute();
            case "image"  -> cfg.imagePerMinute();
            default       -> cfg.defaultPerMinute();
        };
    }

    /**
     * 이 요청이 어느 버킷을 쓰는가.
     *
     * <p><b>공유 게스트는 사용자로 세지 않는다.</b> no-auth 모드의 기본 전략({@code
     * app.auth.guest-identity=shared})에서는 모든 방문자가 {@link GuestIdentityResolver#SHARED_ID}
     * 하나를 principal 로 받고, 그것은 {@code CurrentUser.isAuthenticated()} 에 <b>참</b>이다 —
     * 그래서 예전에는 배포 전체가 버킷 하나를 나눠 썼고, 방문자 한 명이 분당 한도를 소진하면
     * 나머지 전원이 429 를 받았다(레이트리밋은 남용을 막는 장치인데 그 자체가 서비스 거부의
     * 지렛대가 됐다). 그 id 일 때는 IP 로 떨어뜨려 방문자별로 센다.
     *
     * <p>{@code ip}/{@code cookie}/{@code hybrid} 전략은 이미 방문자별 id 를 주므로 이 분기에
     * 걸리지 않고, 실제 로그인 사용자도 마찬가지다(계정 id 는 랜덤 UUID 라 이 상수와 겹칠 수 없다).
     */
    String clientKey(HttpServletRequest req) {
        if (currentUser.isAuthenticated() && !isSharedGuest(currentUser.userId())) {
            return "user:" + currentUser.userId();
        }
        // PLAN §6.19.3 — X-Forwarded-For is only honored when the operator opts in, otherwise an
        // attacker could vary the header per request and refill their own bucket indefinitely.
        return "ip:" + clientIpResolver.resolve(req);
    }

    /** 전 방문자가 공유하는 게스트 principal 인가 — 그렇다면 "사용자 하나"로 셀 수 없다. */
    private static boolean isSharedGuest(String userId) {
        return GuestIdentityResolver.SHARED_ID.equals(userId);
    }
}
