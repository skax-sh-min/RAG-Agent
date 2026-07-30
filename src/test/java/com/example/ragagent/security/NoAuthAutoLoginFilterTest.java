package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.AppSecretRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * NoAuthAutoLoginFilter 계약 보호 테스트.
 *
 * Covers:
 *  - /api/v1/health, /actuator/** 는 passThrough — findFirstAdmin() 미호출
 *  - admin 없을 때 /setup 리다이렉트
 *  - admin 있을 때 일반 경로 → GUEST principal 주입
 *  - §6.17 B안(management-only): 게이트 경로는 실제 로그인 없이 통과만 시키고(Spring이 처리),
 *    실제 로그인이 이미 있으면 어떤 경로든 절대 덮어쓰지 않는다.
 */
class NoAuthAutoLoginFilterTest {

    private final SqliteUserDetailsService userDetailsService = mock(SqliteUserDetailsService.class);
    private final AppProperties props = mock(AppProperties.class);
    // Real resolver, not a mock: every case here runs the default 'shared' strategy, which returns the
    // fixed id without ever touching the secret store — so this also pins "shared changes nothing".
    private final GuestIdentityResolver guestIdentityResolver = new GuestIdentityResolver(
            props, new ClientIpResolver(false), mock(AppSecretRepository.class));
    private final NoAuthAutoLoginFilter filter =
            new NoAuthAutoLoginFilter(userDetailsService, props, guestIdentityResolver);

    @BeforeEach
    void setUp() {
        // Plain no-auth by default — matches every pre-existing test in this file. Management-only
        // cases re-stub this locally.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, false));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppUserDetails adminUser() {
        return new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false);
    }

    private void seedRealLogin(AppUserDetails principal) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── 회귀: passThrough 경로 ─────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/health — passThrough, DB 조회 없음")
    void healthPath_passesThrough_noDbCall() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/health");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("GET /actuator/health — passThrough, DB 조회 없음")
    void actuatorHealthPath_passesThrough_noDbCall() throws Exception {
        var req = new MockHttpServletRequest("GET", "/actuator/health");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("GET /actuator/prometheus — passThrough, DB 조회 없음")
    void actuatorSubpath_passesThrough_noDbCall() throws Exception {
        var req = new MockHttpServletRequest("GET", "/actuator/prometheus");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(userDetailsService);
    }

    // ── admin 미존재 시 /setup 리다이렉트 ────────────────────────────────────

    @Test
    @DisplayName("GET /chat — admin 없으면 /setup 리다이렉트, chain 미호출")
    void normalPath_noAdmin_redirectsToSetup() throws Exception {
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest("GET", "/chat");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getRedirectedUrl()).isEqualTo("/setup");
        verify(chain, never()).doFilter(any(), any());
    }

    // ── admin 존재 시 정상 필터 통과 ──────────────────────────────────────────

    @Test
    @DisplayName("GET /chat — admin 있으면 GUEST principal 주입 후 chain 호출")
    void normalPath_adminExists_passesChain() throws Exception {
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("GET", "/chat");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getRedirectedUrl()).isNull();
    }

    // ── §6.17 B안 — management-only 모드 ────────────────────────────────────

    @Test
    @DisplayName("management-only + GET /admin(게이트 경로) + 로그인 없음 → chain만 호출, SecurityContext 미설정")
    void managementOnly_gatedPath_noLogin_passesThroughWithoutInjection() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("GET", "/admin");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        // Bootstrap check must still run — a fresh deployment's first visit straight to /admin
        // needs to redirect to /setup, not dead-end at /login with no way to ever create an admin.
        verify(userDetailsService).findFirstAdmin();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("management-only + DELETE /ui/documents/x(게이트 경로) + 로그인 없음 → chain만 호출")
    void managementOnly_gatedDocumentWriteRoute_noLogin_passesThroughWithoutInjection() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("DELETE", "/ui/documents/x");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("management-only + GET /ui/documents/list(비게이트, 유사 경로) + 로그인 없음 → GUEST 주입(메서드로 구분됨)")
    void managementOnly_similarPathDifferentMethod_notGated_getsGuestInjection() throws Exception {
        // /ui/documents/* (DELETE) 패턴이 경로만 보면 /ui/documents/list 도 매치하지만, 실제 게이트
        // 라우트는 DELETE 전용이므로 이 GET 요청은 게이트되지 않아야 한다.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("GET", "/ui/documents/list");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AppUserDetails.class);
        assertThat(((AppUserDetails) auth.getPrincipal()).getUsername()).isEqualTo("guest@local");
    }

    @Test
    @DisplayName("management-only + 게이트 경로 + 실제 로그인 이미 있음 → findFirstAdmin() 호출 안 함, chain만 호출")
    void managementOnly_gatedPath_realLoginAlreadyActive_skipsAdminLookup() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        seedRealLogin(adminUser());
        var req = new MockHttpServletRequest("GET", "/admin");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(userDetailsService);
    }

    @Test
    @DisplayName("management-only + 비게이트 경로(/documents) + 실제 로그인 이미 있음 → GUEST로 덮어쓰지 않는다 (회귀)")
    void managementOnly_nonGatedPath_realLoginAlreadyActive_isNotOverwritten() throws Exception {
        // 이 테스트가 없으면: admin이 /login으로 실제 로그인한 뒤 /documents(비게이트)로 이동할 때
        // 필터가 즉시 GUEST_PRINCIPAL로 덮어써버려, isAdmin이 항상 false가 되고 관리자 본인도
        // 자기 업로드 버튼을 못 보는 회귀가 생긴다.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        AppUserDetails admin = adminUser();
        seedRealLogin(admin);
        var req = new MockHttpServletRequest("GET", "/documents");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        verifyNoInteractions(userDetailsService);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(admin);
    }

    @Test
    @DisplayName("management-only + 비게이트 경로 + 로그인 없음 → 기존과 동일하게 GUEST principal 주입")
    void managementOnly_nonGatedPath_noLogin_getsGuestInjection() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("GET", "/documents");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((AppUserDetails) auth.getPrincipal()).getUsername()).isEqualTo("guest@local");
    }

    @Test
    @DisplayName("management-only + GET /admin + 로그인 없음 → plain 모드와 달리 admin 자동주입되지 않는다")
    void managementOnly_adminPath_noLogin_doesNotAutoInjectAdmin() throws Exception {
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(adminUser()));
        var req = new MockHttpServletRequest("GET", "/admin/chunks");
        var res = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        // /admin/** is gated — no principal injected at all (unlike plain no-auth mode, where
        // path.startsWith("/admin") auto-injects the admin principal).
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
