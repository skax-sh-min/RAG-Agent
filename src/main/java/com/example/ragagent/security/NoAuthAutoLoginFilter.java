package com.example.ragagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Activated only when app.auth.enabled=false.
 * Injects a fixed identity on every request:
 *   /admin/** → first ADMIN user in DB (requires /setup on first run)
 *   everything else → fixed "guest" principal
 */
@Component
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false")
public class NoAuthAutoLoginFilter extends OncePerRequestFilter {

    static final String GUEST_ID = "00000000-0000-0000-0000-000000000001";
    private static final AppUserDetails GUEST_PRINCIPAL = new AppUserDetails(
            GUEST_ID, "guest@local", "", "Guest", "USER", true, false
    );

    private final SqliteUserDetailsService userDetailsService;

    public NoAuthAutoLoginFilter(SqliteUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
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

        var adminOpt = userDetailsService.findFirstAdmin();

        if (adminOpt.isEmpty()) {
            response.sendRedirect(request.getContextPath() + "/setup");
            return;
        }

        AppUserDetails principal = path.startsWith("/admin") ? adminOpt.get() : GUEST_PRINCIPAL;
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
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
