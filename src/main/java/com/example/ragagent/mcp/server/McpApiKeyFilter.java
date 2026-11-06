package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.tool.ToolCaller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * MCP 엔드포인트의 API 키 인증 (AGENT_MCP_DESIGN §4.2). 앱에는 토큰 인증이 없고 폐쇄망에서 인가 서버를 전제할 수 없어 OAuth2 는 쓰지 않는다.
 *
 * <p>키는 {@code Authorization: Bearer <key>} 또는 {@code X-API-Key: <key>}. <b>관리자 키</b>는 {@code ROLE_MCP_ADMIN},
 * <b>읽기 키</b>는 {@code ROLE_MCP_READ} 를 받는다. 키가 없거나 틀리면 401 이고 체인의 나머지는 실행되지 않는다.
 *
 * <p>비교는 SHA-256 다이제스트끼리 상수 시간({@link MessageDigest#isEqual})으로 한다 — 길이와 일치한 앞부분이 응답 시간으로 새지 않게.
 * 설정된 키가 비어 있는 역할은 어떤 입력과도 일치하지 않는다(빈 헤더가 빈 키와 맞는 일이 없다).
 *
 * <p>인증 결과는 요청 스레드의 {@code SecurityContext} 에 실린다. MCP 서블릿 서버는 Tool 을 요청 스레드에서 실행하므로
 * ({@link McpToolsConfig}) Tool 호출자는 거기서 읽는다. 요청마다 새 컨텍스트이고 세션은 만들지 않는다.
 */
public class McpApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpApiKeyFilter.class);

    public static final String ROLE_READ = "ROLE_MCP_READ";
    public static final String ROLE_ADMIN = "ROLE_MCP_ADMIN";

    private final byte[] readDigest;
    private final byte[] adminDigest;
    private final AuditLogger audit;

    public McpApiKeyFilter(McpProperties props, AuditLogger audit) {
        this.readDigest = props.readEnabled() ? sha256(props.readApiKey()) : null;
        this.adminDigest = props.adminEnabled() ? sha256(props.adminApiKey()) : null;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String presented = presentedKey(req);
        String role = presented == null ? null : roleFor(presented);
        if (role == null) {
            // 키 값은 로그에 남기지 않는다. 시도 사실만 — 키 추측이 몰리는지 보려는 것이다.
            log.warn("[MCP] 인증 실패 path={} 키={}", req.getRequestURI(), presented == null ? "없음" : "틀림");
            try {
                audit.log("mcp.auth.denied", req.getRequestURI(), Map.of("keyPresent", presented != null));
            } catch (RuntimeException ignored) {
                // 감사 기록 실패가 401 을 바꾸지 않는다
            }
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setHeader("WWW-Authenticate", "Bearer realm=\"rag-agent-mcp\"");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"A valid API key is required\"}");
            return;
        }
        boolean admin = ROLE_ADMIN.equals(role);
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority(ROLE_READ), new SimpleGrantedAuthority(ROLE_ADMIN))
                : List.of(new SimpleGrantedAuthority(ROLE_READ));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(admin ? "mcp-admin" : "mcp-read", null, authorities));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 일치하는 역할 — 관리자 키가 우선하고(설정 검증이 두 키가 같을 수 없게 한다), 없으면 {@code null}. */
    String roleFor(String presented) {
        byte[] digest = sha256(presented);
        // 둘 다 항상 비교한다(앞에서 끝내지 않는다) — 어느 역할의 키인지가 응답 시간으로 새지 않게.
        boolean isAdmin = adminDigest != null && MessageDigest.isEqual(digest, adminDigest);
        boolean isRead = readDigest != null && MessageDigest.isEqual(digest, readDigest);
        if (isAdmin) return ROLE_ADMIN;
        return isRead ? ROLE_READ : null;
    }

    private static String presentedKey(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).strip();
            if (!token.isEmpty()) return token;
        }
        String header = req.getHeader("X-API-Key");
        return (header == null || header.isBlank()) ? null : header.strip();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 현재 요청 스레드의 인증에서 Tool 호출자를 만든다. <b>인증이 없거나 MCP 역할이 아니면 게스트</b>다(권한이 모자란 쪽으로 실패).
     */
    public static ToolCaller callerFromContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return ToolCaller.guest();
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
        boolean read = admin || auth.getAuthorities().stream().anyMatch(a -> ROLE_READ.equals(a.getAuthority()));
        if (!read) return ToolCaller.guest();
        return new ToolCaller(String.valueOf(auth.getName()), admin, "mcp");
    }
}
