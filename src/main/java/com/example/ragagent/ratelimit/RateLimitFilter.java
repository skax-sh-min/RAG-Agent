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

    String policyFor(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path.contains("/chat")) return "chat";
        if (path.contains("/documents/sync")) return "sync";
        if (path.contains("/documents")) return "upload";
        if (path.contains("/images/")) return "image";
        return "default";
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
