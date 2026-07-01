package com.example.ragagent.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
 */
class NoAuthAutoLoginFilterTest {

    private final SqliteUserDetailsService userDetailsService = mock(SqliteUserDetailsService.class);
    private final NoAuthAutoLoginFilter filter = new NoAuthAutoLoginFilter(userDetailsService);

    private AppUserDetails adminUser() {
        return new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false);
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
}
