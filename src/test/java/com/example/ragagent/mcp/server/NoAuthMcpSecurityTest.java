package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.GuestIdentityResolver;
import com.example.ragagent.security.NoAuthAutoLoginFilter;
import com.example.ragagent.security.SecurityConfig;
import com.example.ragagent.security.SqliteUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.mockito.Mockito.when;

/** NoAuth 모드(auth.enabled=false, management-only=false) — 관리자 계정이 있는 상태. */
@WebMvcTest(value = FakeMcpController.class,
        properties = {"app.auth.enabled=false", "app.auth.management-only=false", "spring.ai.mcp.server.enabled=true", "app.mcp.read-api-key=read-key-0123456789ab", "app.mcp.admin-api-key=admin-key-0123456789a"})
@Import({SecurityConfig.class, McpSecurityConfig.class, NoAuthAutoLoginFilter.class, NoAuthMcpSecurityTest.TestConfig.class})
@ResourceLock("global-state")
class NoAuthMcpSecurityTest extends McpSecurityModesBase {

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
    void adminExists() {
        when(userDetailsService.findFirstAdmin()).thenReturn(Optional.of(
                new AppUserDetails("admin-id", "admin@local", "hash", "Admin", "ADMIN", true, false)));
    }
}
