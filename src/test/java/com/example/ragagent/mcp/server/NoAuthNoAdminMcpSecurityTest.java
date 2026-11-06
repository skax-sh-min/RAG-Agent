package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.GuestIdentityResolver;
import com.example.ragagent.security.NoAuthAutoLoginFilter;
import com.example.ragagent.security.SecurityConfig;
import com.example.ragagent.security.SqliteUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 평문 no-auth 모드 + <b>관리자가 아직 없는 상태</b> — 설계서가 짚은 위험 지점이다. {@code NoAuthAutoLoginFilter} 는 서블릿 필터로도 돌아
 * 관리자가 없으면 모든 경로를 {@code /setup} 으로 302 시킨다. MCP 경로가 통과 목록에 없으면 키가 맞아도 302, 키가 없어도 302 다.
 * 같은 상태에서 일반 경로가 실제로 302 가 되는지를 대조군으로 함께 본다 — 필터가 돌지 않아서 통과하는 것이 아님을 보이기 위해.
 */
@WebMvcTest(value = FakeMcpController.class,
        properties = {"app.auth.enabled=false", "app.auth.management-only=false",
                "spring.ai.mcp.server.enabled=true", "app.mcp.read-api-key=read-key-0123456789ab",
                "app.mcp.admin-api-key=admin-key-0123456789a"})
@Import({SecurityConfig.class, McpSecurityConfig.class, NoAuthAutoLoginFilter.class, NoAuthNoAdminMcpSecurityTest.TestConfig.class})
@ResourceLock("global-state")
class NoAuthNoAdminMcpSecurityTest extends McpSecurityModesBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties props() {
            return propsFor(new AppProperties.AuthConfig(false, false));
        }

        @Bean
        GuestIdentityResolver guestIdentityResolver(AppProperties props) {
            return guestResolver(props);
        }
    }

    @MockitoBean SqliteUserDetailsService userDetailsService;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean AuditLogger auditLogger;

    @BeforeEach
    void noAdminYet() {
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("대조군: 관리자가 없으면 일반 경로는 /setup 으로 302 가 된다 — 필터가 실제로 돌고 있다")
    void controlGroupRedirectsToSetup() throws Exception {
        mvc.perform(get("/plain-page")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/setup"));
    }

    @Test
    @DisplayName("같은 상태에서 MCP 는 /setup 으로 가지 않는다 — 키 없음 401, 읽기 키 200, 관리자 키는 관리자")
    void mcpIsNotRedirectedToSetup() throws Exception {
        mvc.perform(post("/api/v1/mcp")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer " + READ_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(false));
        mvc.perform(post("/api/v1/mcp").header("Authorization", "Bearer " + ADMIN_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));
    }
}
