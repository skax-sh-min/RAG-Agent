package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Activated only when app.auth.enabled=false.
 * Injects a fixed identity on every request:
 *   /admin/** → first ADMIN user in DB (requires /setup on first run)
 *   everything else → fixed "guest" principal
 *
 * §6.17 B안 (app.auth.management-only=true) changes this: {@code /admin/**} and the document-
 * management write UI ({@link #GATED_UI_DOCUMENT_ROUTES}) are excluded from auto-injection —
 * see {@link #doFilterInternal} for the exact ordering, which matters.
 */
@Component
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false")
public class NoAuthAutoLoginFilter extends OncePerRequestFilter {

    static final String GUEST_ID = "00000000-0000-0000-0000-000000000001";
    private static final AppUserDetails GUEST_PRINCIPAL = new AppUserDetails(
            GUEST_ID, "guest@local", "", "Guest", "USER", true, false
    );

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** A single (method, Ant path pattern) pair gated behind a real login in management-only mode. */
    private record GatedRoute(String method, String pattern) {
    }

    // Mirrors SecurityConfig's management-only authorizeHttpRequests() matchers exactly — kept in
    // sync deliberately (see hasRole("ADMIN") vs authenticated() comment there for why a drift
    // between these two lists fails safe rather than silently granting access).
    private static final List<GatedRoute> GATED_UI_DOCUMENT_ROUTES = List.of(
            new GatedRoute("POST", "/ui/documents/upload"),
            new GatedRoute("POST", "/ui/documents/progress/*/cancel"),
            new GatedRoute("DELETE", "/ui/documents/*"),
            new GatedRoute("PATCH", "/ui/documents/*/tags"),
            new GatedRoute("GET", "/ui/documents/*/tags/edit")
    );

    private final SqliteUserDetailsService userDetailsService;
    private final AppProperties props;

    public NoAuthAutoLoginFilter(SqliteUserDetailsService userDetailsService, AppProperties props) {
        this.userDetailsService = userDetailsService;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (isPassThrough(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean managementOnly = props.authSafe().managementOnly();

        // A real login (management-only mode's /login flow) must never be stomped back to guest —
        // on ANY path, not just gated ones. Without this check, an admin who just logged in and
        // then navigates to a non-gated page (e.g. /documents) would be silently downgraded to
        // GUEST_PRINCIPAL before the controller runs, breaking role-based UI (isAdmin) and audit
        // attribution for no benefit (document storage itself is shared/userId-agnostic).
        if (managementOnly && hasRealLogin()) {
            chain.doFilter(request, response);
            return;
        }

        var adminOpt = userDetailsService.findFirstAdmin();

        if (adminOpt.isEmpty()) {
            response.sendRedirect(request.getContextPath() + "/setup");
            return;
        }

        // Admin exists but this request has no real login — for a gated path, leave Spring's own
        // AnonymousAuthenticationToken in place (don't inject anything) so authorizeHttpRequests()'s
        // hasRole("ADMIN") check denies it and ExceptionTranslationFilter redirects to /login.
        if (managementOnly && isGatedManagementPath(request)) {
            chain.doFilter(request, response);
            return;
        }

        AppUserDetails principal = (!managementOnly && path.startsWith("/admin")) ? adminOpt.get() : GUEST_PRINCIPAL;
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    /** True when a real (non-anonymous) authenticated principal is already in the security context —
     *  i.e. this browser actually logged in via /login, as opposed to nothing having run yet. */
    private boolean hasRealLogin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** management-only mode's protected surface: all of /admin/** plus the document-write UI routes. */
    private boolean isGatedManagementPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/admin") || path.startsWith("/admin/")) return true;

        String method = request.getMethod();
        return GATED_UI_DOCUMENT_ROUTES.stream()
                .anyMatch(route -> route.method().equalsIgnoreCase(method)
                        && PATH_MATCHER.match(route.pattern(), path));
    }

    private boolean isPassThrough(String path) {
        return path.equals("/setup")
                || path.equals("/error")
                || path.equals("/api/v1/health")
                || path.startsWith("/actuator/")
                || path.startsWith("/webjars/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.equals("/favicon.ico")
                // PWA assets — must stay in sync with SecurityConfig's permitAll list so they're
                // reachable before an admin exists (otherwise install banner/offline fallback
                // redirect to /setup on first run).
                || path.equals("/manifest.webmanifest")
                || path.equals("/sw.js")
                || path.equals("/offline.html")
                || path.startsWith("/icons/");
    }
}
