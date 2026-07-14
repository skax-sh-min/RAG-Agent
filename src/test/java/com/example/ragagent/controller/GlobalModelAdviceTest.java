package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.AppUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalModelAdvice 계약 보호 테스트 — §6.17 B안의 4가지 모드 조합.
 *
 * Covers: full-auth, plain no-auth, management-only×guest, management-only×admin의
 * authEnabled/managementOnly/isAdmin 조합, 그리고 props==null 기본값.
 */
class GlobalModelAdviceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppProperties propsWith(boolean enabled, boolean managementOnly) {
        AppProperties props = mock(AppProperties.class);
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(enabled, managementOnly));
        return props;
    }

    private void seedLogin(String role) {
        AppUserDetails principal = new AppUserDetails("id", "u@local", "hash", "U", role, true, false);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── full-auth ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full-auth 모드 — authEnabled=true, managementOnly=false, isAdmin=true(무조건)")
    void fullAuthMode() {
        GlobalModelAdvice advice = new GlobalModelAdvice(propsWith(true, false));

        assertThat(advice.authEnabled()).isTrue();
        assertThat(advice.managementOnly()).isFalse();
        assertThat(advice.isAdmin()).isTrue();
    }

    // ── plain no-auth ───────────────────────────────────────────────────────

    @Test
    @DisplayName("plain no-auth 모드 — authEnabled=false, managementOnly=false, isAdmin=true(무조건, 무회귀)")
    void plainNoAuthMode() {
        GlobalModelAdvice advice = new GlobalModelAdvice(propsWith(false, false));

        assertThat(advice.authEnabled()).isFalse();
        assertThat(advice.managementOnly()).isFalse();
        assertThat(advice.isAdmin()).isTrue();
    }

    // ── management-only × guest ─────────────────────────────────────────────

    @Test
    @DisplayName("management-only + 로그인 없음(게스트) — isAdmin=false")
    void managementOnly_noLogin_isNotAdmin() {
        GlobalModelAdvice advice = new GlobalModelAdvice(propsWith(false, true));

        assertThat(advice.authEnabled()).isFalse();
        assertThat(advice.managementOnly()).isTrue();
        assertThat(advice.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("management-only + ROLE_USER 실로그인 — isAdmin=false")
    void managementOnly_nonAdminLogin_isNotAdmin() {
        GlobalModelAdvice advice = new GlobalModelAdvice(propsWith(false, true));
        seedLogin("USER");

        assertThat(advice.isAdmin()).isFalse();
    }

    // ── management-only × admin ─────────────────────────────────────────────

    @Test
    @DisplayName("management-only + ROLE_ADMIN 실로그인 — isAdmin=true")
    void managementOnly_adminLogin_isAdmin() {
        GlobalModelAdvice advice = new GlobalModelAdvice(propsWith(false, true));
        seedLogin("ADMIN");

        assertThat(advice.authEnabled()).isFalse();
        assertThat(advice.managementOnly()).isTrue();
        assertThat(advice.isAdmin()).isTrue();
    }

    // ── props==null (안전 기본값) ─────────────────────────────────────────────

    @Test
    @DisplayName("props==null — authEnabled=true(기본), managementOnly=false, isAdmin=true")
    void nullProps_defaultsToFullAuth() {
        GlobalModelAdvice advice = new GlobalModelAdvice(null);

        assertThat(advice.authEnabled()).isTrue();
        assertThat(advice.managementOnly()).isFalse();
        assertThat(advice.isAdmin()).isTrue();
    }
}
