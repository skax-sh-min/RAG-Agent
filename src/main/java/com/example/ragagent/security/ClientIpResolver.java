package com.example.ragagent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single place that decides what "this request's client IP" is, for every decision that keys off
 * it (per-IP rate limiting, no-auth guest identity). PLAN §6.19.3.
 *
 * <p>{@code X-Forwarded-For} is attacker-controlled unless a reverse proxy overwrites it, so it is
 * honored only when {@code app.trust-forwarded-for=true} — an explicit opt-in the operator sets when
 * the app really does sit behind Caddy/nginx. Direct-exposure (air-gapped, no proxy) deployments keep
 * the default {@code false} and read {@link HttpServletRequest#getRemoteAddr()} only, so forging the
 * header can neither bypass a per-IP rate limit nor — once
 * {@code app.auth.guest-identity} derives identity from the IP — impersonate another visitor.
 *
 * <p>Conversely, behind a proxy the flag must be ON: without it every request reports the proxy's own
 * address, collapsing all visitors into one identity and one shared rate-limit bucket.
 */
@Component
public class ClientIpResolver {

    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public boolean isTrustingForwardedFor() {
        return trustForwardedFor;
    }

    /**
     * Best-effort client IP. Never null or blank — falls back to {@code "unknown"} when the container
     * reports no remote address, so callers can use the value as a map/hash key unconditionally.
     */
    public String resolve(HttpServletRequest req) {
        if (trustForwardedFor) {
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // Left-most entry is the original client; the rest are proxy hops.
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) return first;
            }
        }
        String remote = req.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
