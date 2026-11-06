package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.SecurityConfig;
import com.example.ragagent.security.SqliteUserDetailsService;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 전체 인증 모드(세션 + CSRF + 로그인 리다이렉트)에서도 MCP 는 키 하나로 STATELESS 로 동작한다. */
@WebMvcTest(value = FakeMcpController.class,
        properties = {"app.auth.enabled=true", "spring.ai.mcp.server.enabled=true", "app.mcp.read-api-key=read-key-0123456789ab", "app.mcp.admin-api-key=admin-key-0123456789a"})
@Import({SecurityConfig.class, McpSecurityConfig.class, FullAuthMcpSecurityTest.TestConfig.class})
@ResourceLock("global-state")
class FullAuthMcpSecurityTest extends McpSecurityModesBase {

    @TestConfiguration
    static class TestConfig {
        @Bean
        AppProperties props() {
            return propsFor(new AppProperties.AuthConfig(true, false));
        }
    }

    @MockitoBean SqliteUserDetailsService userDetailsService;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean AuditLogger auditLogger;
}
