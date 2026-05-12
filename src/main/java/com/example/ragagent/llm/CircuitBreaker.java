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
