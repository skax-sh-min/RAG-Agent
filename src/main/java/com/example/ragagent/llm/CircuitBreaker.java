package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory circuit breaker for LLM providers.
 *
 * Providers are blocked for a duration derived from the Retry-After header (or
 * defaultBlockDuration if absent). State resets automatically on expiry — no
 * external scheduler needed.
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final ConcurrentHashMap<String, Instant> blockedUntil = new ConcurrentHashMap<>();
    private final Duration defaultBlockDuration;

    public CircuitBreaker(
            @Value("${app.llm.circuit-breaker-minutes:2}") int minutes) {
        this.defaultBlockDuration = Duration.ofMinutes(minutes);
    }

    public boolean isBlocked(String providerName) {
        // Atomic check-and-evict: prevents a concurrent block() from being
        // clobbered by a stale isBlocked() observing an already-expired entry.
        Instant until = blockedUntil.computeIfPresent(providerName,
                (k, v) -> Instant.now().isAfter(v) ? null : v);
        return until != null;
    }

    /**
     * Blocks a provider using the Retry-After header value (seconds or HTTP-date),
     * or defaultBlockDuration when the header is absent.
     */
    public void block(String providerName, @Nullable String retryAfterHeader) {
        Duration duration = parseRetryAfter(retryAfterHeader);
        Instant until = Instant.now().plus(duration);
        blockedUntil.put(providerName, until);
        log.warn("Provider [{}] blocked for {}s until {}", providerName, duration.getSeconds(), until);
    }

    /**
     * 이 프로바이더가 풀릴 때까지 남은 초 — 차단돼 있지 않으면 {@code -1}.
     *
     * <p>{@link #isBlocked} 와 달리 <b>얼마나</b>를 답한다. 사용자에게 "잠시 후 다시" 대신 "20초 후
     * 다시"라고 말할 수 있게 하려는 것이고, 1초 미만이 남았어도 0 이 아니라 1 을 돌려준다 — 0 은
     * "지금 된다"로 읽히는데 아직 아니다.
     */
    public int secondsUntilUnblocked(String providerName) {
        Instant until = blockedUntil.computeIfPresent(providerName,
                (k, v) -> Instant.now().isAfter(v) ? null : v);
        if (until == null) return -1;
        long secs = Duration.between(Instant.now(), until).toSeconds();
        return (int) Math.max(1, secs);
    }

    /** Returns currently blocked providers after evicting expired entries. */
    public Map<String, Instant> getBlockedProviders() {
        Instant now = Instant.now();
        blockedUntil.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        return Map.copyOf(blockedUntil);
    }

    private Duration parseRetryAfter(@Nullable String header) {
        if (header == null || header.isBlank()) return defaultBlockDuration;
        Duration parsed;
        try {
            parsed = Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            try {
                Instant retryAt = Instant.from(
                        DateTimeFormatter.RFC_1123_DATE_TIME.parse(header.trim()));
                parsed = Duration.ofSeconds(Instant.now().until(retryAt, ChronoUnit.SECONDS));
            } catch (Exception ex) {
                return defaultBlockDuration;
            }
        }
        // Negative/zero (malformed value or past HTTP-Date) must not bypass the block.
        return (parsed.isNegative() || parsed.isZero()) ? defaultBlockDuration : parsed;
    }
}
