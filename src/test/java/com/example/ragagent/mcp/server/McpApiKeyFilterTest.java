package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** API 키 필터와 키 설정 검증 — 모드와 무관한 순수 단위. 모드별 보안 계약은 {@code McpSecurityModesBase} 가 본다. */
class McpApiKeyFilterTest {

    private static final String READ = "read-key-0123456789ab";
    private static final String ADMIN = "admin-key-0123456789a";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static McpApiKeyFilter filter(String read, String admin) {
        return new McpApiKeyFilter(new McpProperties(read, admin), mock(AuditLogger.class));
    }

    @Test
    @DisplayName("관리자 키 · 읽기 키 · 그 밖을 가른다")
    void rolesByKey() {
        McpApiKeyFilter f = filter(READ, ADMIN);
        assertThat(f.roleFor(ADMIN)).isEqualTo(McpApiKeyFilter.ROLE_ADMIN);
        assertThat(f.roleFor(READ)).isEqualTo(McpApiKeyFilter.ROLE_READ);
        assertThat(f.roleFor("other-key-0123456789")).isNull();
        assertThat(f.roleFor(READ + "x")).isNull();
        assertThat(f.roleFor("")).isNull();
    }

    @Test
    @DisplayName("비어 있는 키는 그 역할을 끈다 — 빈 입력이 빈 설정과 맞는 일이 없다")
    void emptyConfiguredKeyDisablesTheRole() {
        McpApiKeyFilter readOnly = filter(READ, "");
        assertThat(readOnly.roleFor("")).isNull();
        assertThat(readOnly.roleFor(ADMIN)).isNull();
        assertThat(readOnly.roleFor(READ)).isEqualTo(McpApiKeyFilter.ROLE_READ);

        McpApiKeyFilter none = filter("", "");
        assertThat(none.roleFor("")).isNull();
        assertThat(none.roleFor(READ)).isNull();
    }

    @Test
    @DisplayName("키가 없으면 체인을 실행하지 않고 401 — 감사 기록이 실패해도 401")
    void rejectsWithoutRunningTheChain() throws Exception {
        AuditLogger audit = mock(AuditLogger.class);
        doThrow(new IllegalStateException("로그 쓰기 실패")).when(audit).log(any(), any(), any());
        McpApiKeyFilter f = new McpApiKeyFilter(new McpProperties(READ, ADMIN), audit);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        f.doFilter(new MockHttpServletRequest("POST", "/api/v1/mcp"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).startsWith("Bearer");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("통과한 요청의 인증은 체인이 끝나면 지워진다 — 다음 요청으로 새지 않는다")
    void contextIsClearedAfterTheChain() throws Exception {
        McpApiKeyFilter f = filter(READ, ADMIN);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/mcp");
        req.addHeader("Authorization", "Bearer " + ADMIN);
        FilterChain chain = (rq, rs) -> assertThat(McpApiKeyFilter.callerFromContext().admin()).isTrue();

        f.doFilter(req, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("키 설정 검증 — 짧은 키와 같은 키 두 개는 기동을 막고, 둘 다 비면 통과(전부 401 로 닫힌다)")
    void validation() {
        assertThatThrownBy(() -> new McpProperties("short", "").validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_READ_API_KEY");
        assertThatThrownBy(() -> new McpProperties("", "short").validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_ADMIN_API_KEY");
        assertThatThrownBy(() -> new McpProperties(READ, READ).validate()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("달라야");
        new McpProperties("", "").validate();
        new McpProperties(READ, ADMIN).validate();
        new McpProperties(null, "  ").validate();
        assertThat(new McpProperties(" " + READ + " ", null).readApiKey()).isEqualTo(READ);
        assertThat(new McpProperties(null, null).readEnabled()).isFalse();
    }
}
