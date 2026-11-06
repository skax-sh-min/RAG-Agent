package com.example.ragagent.mcp.server;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolCatalog;
import com.example.ragagent.tool.ToolDeniedException;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MCP 서버 배선 — 꺼져 있으면 아무것도 없고(Phase 2 완료 기준 ①), 켜면 Tool 은 <b>요청 스레드의 인증</b>으로 호출자를 정하며(②의 짝),
 * 스타터를 넣어도 앱의 Jackson 설정이 살아 있다(②).
 */
@ResourceLock("global-state")
class McpServerWiringTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static ToolCatalog realCatalog(DocumentExportService export) {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        return new ToolCatalog(new KnowledgeSearchTool(null, Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, export, guard),
                new CatalogQueryTool(mock(CatalogQueryService.class), guard), new DiffTool(guard));
    }

    @Configuration
    static class CatalogConfig {
        @Bean
        ToolCatalog toolCatalog() {
            return realCatalog(mock(DocumentExportService.class));
        }
    }

    // ── 꺼짐 / 켬 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MCP_SERVER_ENABLED 가 꺼져 있으면(기본) 보안 체인도 Tool 공개 빈도 만들어지지 않는다")
    void nothingIsRegisteredWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpSecurityConfig.class, McpToolsConfig.class, CatalogConfig.class)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean("mcpFilterChain");
                    assertThat(ctx).doesNotHaveBean("mcpExposedTools");
                    assertThat(ctx).doesNotHaveBean(McpProperties.class);
                });
        new ApplicationContextRunner()
                .withPropertyValues("spring.ai.mcp.server.enabled=false")
                .withUserConfiguration(McpSecurityConfig.class, McpToolsConfig.class, CatalogConfig.class)
                .run(ctx -> assertThat(ctx).doesNotHaveBean("mcpExposedTools"));
    }

    @Test
    @DisplayName("켜면 공개 Tool 은 카탈로그의 여섯 개 전부이고 그 빈 하나로만 나간다")
    void enabledExposesTheCatalogThroughOneProvider() {
        new ApplicationContextRunner()
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .withUserConfiguration(McpToolsConfig.class, CatalogConfig.class)
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(ToolCallbackProvider.class)).containsOnlyKeys("mcpExposedTools");
                    List<String> names = java.util.Arrays.stream(ctx.getBean(ToolCallbackProvider.class).getToolCallbacks())
                            .map(cb -> cb.getToolDefinition().name()).toList();
                    assertThat(names).containsExactlyInAnyOrder("searchKnowledge", "findRelatedDocuments", "queryCatalog",
                            "getChunkContext", "generateDiff", "getDocument");
                });
    }

    // ── 호출자는 요청 스레드의 인증에서 ──────────────────────────────────────

    private static void authenticateAs(String name, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, AuthorityUtils.createAuthorityList(roles)));
    }

    @Test
    @DisplayName("읽기 키 호출자는 관리자 Tool 을 부를 수 없다 — 서비스는 건드리지 않는다")
    void readCallerCannotCallAdminTools() {
        DocumentExportService export = mock(DocumentExportService.class);
        ToolCallback getDocument = new McpToolsConfig.CallerBound(
                realCatalog(export).forCaller(new ToolCaller("x", true, "mcp")).stream()
                        .filter(cb -> cb.getToolDefinition().name().equals("getDocument")).findFirst().orElseThrow().getToolDefinition(),
                realCatalog(export));

        authenticateAs("mcp-read", McpApiKeyFilter.ROLE_READ);
        assertThatThrownBy(() -> getDocument.call("{\"docId\":\"d1\"}")).isInstanceOf(ToolDeniedException.class);
        verifyNoInteractions(export);
    }

    @Test
    @DisplayName("인증이 없거나 MCP 역할이 아니면 게스트다 · MCP 클라이언트가 보낸 ToolContext 의 호출자는 무시한다")
    void callerComesOnlyFromTheSecurityContext() {
        DocumentExportService export = mock(DocumentExportService.class);
        ToolCatalog catalog = realCatalog(export);
        ToolCallback diff = new McpToolsConfig.CallerBound(
                catalog.forCaller(ToolCaller.guest()).stream()
                        .filter(cb -> cb.getToolDefinition().name().equals("generateDiff")).findFirst().orElseThrow().getToolDefinition(),
                catalog);
        ToolCallback getDocument = new McpToolsConfig.CallerBound(
                catalog.forCaller(new ToolCaller("x", true, "mcp")).stream()
                        .filter(cb -> cb.getToolDefinition().name().equals("getDocument")).findFirst().orElseThrow().getToolDefinition(),
                catalog);

        assertThat(McpApiKeyFilter.callerFromContext()).isEqualTo(ToolCaller.guest());   // 인증 없음

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(McpApiKeyFilter.callerFromContext()).isEqualTo(ToolCaller.guest());   // 익명

        authenticateAs("someone", "ROLE_ADMIN");                                           // 앱의 관리자 역할은 MCP 역할이 아니다
        assertThat(McpApiKeyFilter.callerFromContext()).isEqualTo(ToolCaller.guest());

        // 읽기 호출자가 ToolContext 에 관리자 호출자를 실어 보내도 소용없다
        authenticateAs("mcp-read", McpApiKeyFilter.ROLE_READ);
        assertThatThrownBy(() -> getDocument.call("{\"docId\":\"d1\"}", new ToolCaller("evil", true, "mcp").toContext()))
                .isInstanceOf(ToolDeniedException.class);
        assertThat(diff.call("{\"before\":\"a\",\"after\":\"b\"}")).contains("\"addedLines\":1");
        verifyNoInteractions(export);
    }

    @Test
    @DisplayName("관리자 키 호출자는 관리자 Tool 을 부른다")
    void adminCallerCanCallAdminTools() {
        DocumentExportService export = mock(DocumentExportService.class);
        org.mockito.Mockito.when(export.reassembledText("d1", "latest")).thenReturn("본문");
        ToolCatalog catalog = realCatalog(export);
        ToolCallback getDocument = new McpToolsConfig.CallerBound(
                catalog.forCaller(new ToolCaller("x", true, "mcp")).stream()
                        .filter(cb -> cb.getToolDefinition().name().equals("getDocument")).findFirst().orElseThrow().getToolDefinition(),
                catalog);

        authenticateAs("mcp-admin", McpApiKeyFilter.ROLE_READ, McpApiKeyFilter.ROLE_ADMIN);
        assertThat(getDocument.call("{\"docId\":\"d1\"}")).contains("본문");
    }

    // ── Jackson ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("MCP 스타터의 JsonMapper 는 앱의 JsonMapper 를 밀어내지 않는다 — spring.jackson.* 가 살아 있다")
    void appJsonMapperKeepsItsSettingsWhenMcpIsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration.class))
                .withPropertyValues("spring.ai.mcp.server.enabled=true",
                        "spring.jackson.deserialization.fail-on-null-for-primitives=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // 타입으로 주입받는 매퍼 = 앱의 것. Jackson 3 의 기본(켬)이 아니라 설정한 값(끔)이어야 한다.
                    JsonMapper injected = ctx.getBean(JsonMapper.class);
                    assertThat(injected.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES))
                            .as("앱 JsonMapper 의 fail-on-null-for-primitives").isFalse();
                });
    }
}
