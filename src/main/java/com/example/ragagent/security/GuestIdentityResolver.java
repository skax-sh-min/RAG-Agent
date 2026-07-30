package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.AppProperties.GuestIdentity;
import com.example.ragagent.repository.AppSecretRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives the per-visitor {@code userId} used in no-auth mode, so separate visitors get separate chat
 * threads instead of all sharing one guest account.
 *
 * <p><b>Why this is the only change needed.</b> Every store is already keyed by {@code userId} —
 * {@code thread_meta}, {@code conversation_turns}, {@code curated_qa.source_user_id} — and every
 * repository method takes it as a parameter (Phase 1 Step 1.4 made that a compile-time requirement).
 * The isolation machinery was simply being fed a constant. Swapping that constant for a derived value
 * here personalizes the chat screen with zero storage or service changes. Document storage stays
 * deliberately shared ({@code DocRegistry.SHARED}) and is unaffected.
 *
 * <p><b>Relation to real multi-user auth.</b> This bean is {@code @ConditionalOnProperty} on
 * {@code app.auth.enabled=false}, the same flag {@link NoAuthAutoLoginFilter} is gated on, so turning
 * real authentication on removes it from the context entirely — there is no path where a derived guest
 * id and a real logged-in id can both be live. Ids carry an {@link #ID_PREFIX} so guest rows stay
 * greppable for a later cleanup or hand-off to real accounts.
 *
 * <p><b>Security note.</b> When the strategy involves an IP, the id is only as trustworthy as
 * {@link ClientIpResolver} — see {@code app.trust-forwarded-for}. With a forgeable
 * {@code X-Forwarded-For} an attacker could derive another visitor's id and read their threads, which
 * is why this class hashes the IP with a persisted server-side key rather than using it directly, and
 * why the operator flag defaults to not trusting the header.
 */
@Component
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false")
public class GuestIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(GuestIdentityResolver.class);

    /** Fixed id every visitor shared before per-visitor identity existed; still the default strategy. */
    public static final String SHARED_ID = "00000000-0000-0000-0000-000000000001";

    /** Marks a synthetic guest id. Deliberately greppable: {@code WHERE user_id LIKE 'guest-%'}. */
    public static final String ID_PREFIX = "guest-";

    static final String COOKIE_NAME = "rag_visitor";
    static final String SECRET_NAME = "guest-identity-hmac";

    private static final int ID_HEX_LEN = 12;
    private static final Pattern VALID_ID = Pattern.compile("^" + ID_PREFIX + "[0-9a-f]{" + ID_HEX_LEN + "}$");
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AppProperties props;
    private final ClientIpResolver clientIpResolver;
    private final AppSecretRepository secretRepository;
    private final SecureRandom random = new SecureRandom();

    /** Loaded lazily on first derivation — a strategy of {@code shared} never touches the DB at all. */
    private volatile byte[] secret;

    public GuestIdentityResolver(AppProperties props,
                                 ClientIpResolver clientIpResolver,
                                 AppSecretRepository secretRepository) {
        this.props = props;
        this.clientIpResolver = clientIpResolver;
        this.secretRepository = secretRepository;
    }

    @PostConstruct
    void logEffectiveStrategy() {
        String raw = props.authSafe().guestIdentity();
        log.info("[GUEST_ID] 방문자 식별 전략: {}{}", raw,
                GuestIdentity.SHARED.equals(raw) ? " (전 방문자가 하나의 게스트를 공유)" : "");
        if (!GuestIdentity.SHARED.equals(raw) && !clientIpResolver.isTrustingForwardedFor()) {
            // Not an error — correct for direct exposure. Only worth a hint, since getting this wrong
            // behind a proxy silently collapses every visitor into the proxy's own address.
            log.info("[GUEST_ID] app.trust-forwarded-for=false — 리버스 프록시(Caddy 등) 뒤에 있다면 "
                    + "true로 켜야 방문자별 IP가 식별됩니다.");
        }
    }

    /**
     * The id this request's visitor should be treated as. May write a {@code Set-Cookie} header, so it
     * must be called before the response is committed (the filter chain position guarantees that).
     */
    public String resolve(HttpServletRequest req, HttpServletResponse res) {
        return switch (props.authSafe().guestIdentity()) {
            case GuestIdentity.IP     -> fromIp(req);
            case GuestIdentity.COOKIE -> fromCookie(req, res, this::randomId);
            case GuestIdentity.HYBRID -> fromCookie(req, res, () -> fromIp(req));
            default                   -> SHARED_ID;
        };
    }

    /** Existing valid cookie wins; otherwise mint via {@code fallback} and persist it as the cookie. */
    private String fromCookie(HttpServletRequest req, HttpServletResponse res, Supplier<String> fallback) {
        String existing = readCookie(req);
        if (existing != null) return existing;

        String id = fallback.get();
        writeCookie(req, res, id);
        return id;
    }

    private String readCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (!COOKIE_NAME.equals(c.getName())) continue;
            String value = c.getValue();
            // Shape-check rather than trust: a tampered/garbage value must not become a userId and
            // start a thread namespace of its own.
            if (value != null && VALID_ID.matcher(value).matches()) return value;
        }
        return null;
    }

    private void writeCookie(HttpServletRequest req, HttpServletResponse res, String id) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, id)
                .httpOnly(true)
                .secure(req.isSecure())
                .path("/")
                .maxAge(COOKIE_MAX_AGE)
                .sameSite("Lax")
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String fromIp(HttpServletRequest req) {
        return hashToId(normalizeIp(clientIpResolver.resolve(req)));
    }

    private String randomId() {
        byte[] bytes = new byte[ID_HEX_LEN];  // more entropy than the hex we keep — fine, we truncate
        random.nextBytes(bytes);
        return ID_PREFIX + HexFormat.of().formatHex(bytes).substring(0, ID_HEX_LEN);
    }

    /**
     * Strips the parts of an address that identify a moment rather than a machine.
     *
     * <p>An IPv6 zone id ({@code %eth0}) is host-local noise. For IPv6 the identity keys off the /64
     * network prefix, because privacy extensions (RFC 4941) rotate the low 64 bits on a timer — using
     * the full address would hand the same visitor a new identity every few hours. Compressed
     * ({@code ::}) forms are left whole rather than expanded here; under {@code hybrid} the cookie
     * absorbs that case anyway, and mis-expanding an address would be worse than not truncating it.
     */
    static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) return "unknown";
        String bare = ip.strip();

        int zone = bare.indexOf('%');
        if (zone >= 0) bare = bare.substring(0, zone);

        if (bare.indexOf(':') < 0) return bare;   // IPv4 (or something unparseable) — use whole
        if (bare.contains("::")) return bare;     // compressed — see javadoc

        String[] groups = bare.split(":");
        if (groups.length < 4) return bare;
        return String.join(":", groups[0], groups[1], groups[2], groups[3]);
    }

    /**
     * HMACs the material with a persisted server-side key. Hashing (rather than using the IP directly)
     * keeps raw addresses out of {@code thread_meta}/audit rows, and the secret makes the id
     * unguessable to someone who merely knows a target's IP.
     */
    private String hashToId(String material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret(), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            return ID_PREFIX + HexFormat.of().formatHex(digest).substring(0, ID_HEX_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("게스트 식별자 HMAC 생성 실패", e);
        }
    }

    private byte[] secret() {
        byte[] local = secret;
        if (local != null) return local;
        synchronized (this) {
            if (secret == null) secret = secretRepository.getOrCreate(SECRET_NAME);
            return secret;
        }
    }
}
