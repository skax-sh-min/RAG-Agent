package com.example.ragagent.ratelimit;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.ClientIpResolver;
import com.example.ragagent.security.CurrentUser;
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
        if (write && isDocumentUpload(path)) return "upload";
        if (path.contains("/images/")) return "image";
        return "default";
    }

    /** The two endpoints that actually accept a new document ({@code DocumentController}). */
    private static boolean isDocumentUpload(String path) {
        return path.endsWith("/ui/documents/upload") || path.endsWith("/api/v1/documents");
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

    String clientKey(HttpServletRequest req) {
        if (currentUser.isAuthenticated()) return "user:" + currentUser.userId();
        // PLAN §6.19.3 — X-Forwarded-For is only honored when the operator opts in, otherwise an
        // attacker could vary the header per request and refill their own bucket indefinitely.
        return "ip:" + clientIpResolver.resolve(req);
    }
}
