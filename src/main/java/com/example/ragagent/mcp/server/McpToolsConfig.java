package com.example.ragagent.mcp.server;

import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolCatalog;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 로 나가는 Tool (AGENT_MCP_DESIGN §2.4 · §4.2). 스프링 AI 의 MCP 자동 구성은 {@code ToolCallbackProvider} 빈을 <b>전부</b> 공개하므로
 * 공개 범위는 이 빈 하나({@code mcpExposedTools})로 통제한다 — {@code @Tool} 빈을 직접 {@code ToolCallbackProvider} 로 만들면 안 된다.
 *
 * <p>{@code tools/list} 는 정적 목록이라 호출자별로 거르지 못한다(관리자 Tool 도 보인다). 막는 것은 호출이다: 각 콜백이 <b>요청 스레드의
 * {@code SecurityContext}</b> 에서 호출자를 만들어 {@link ToolCatalog#call} 로 보내고, 읽기 키로 관리자 Tool 을 부르면 {@code ToolDeniedException}
 * 이다. MCP SDK 가 넘기는 {@link ToolContext} 는 쓰지 않는다 — 호출자를 클라이언트가 보낸 값으로 정하지 않는다. 인증 정보가 없으면 게스트다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class McpToolsConfig {

    @Bean
    ToolCallbackProvider mcpExposedTools(ToolCatalog catalog) {
        // 목록을 만들 때만 전체를 본다 — 호출 권한은 호출마다 따로 검사한다.
        List<ToolCallback> all = catalog.forCaller(new ToolCaller("mcp-listing", true, "mcp"));
        List<ToolCallback> bound = all.stream().<ToolCallback>map(cb -> new CallerBound(cb.getToolDefinition(), catalog)).toList();
        return ToolCallbackProvider.from(bound);
    }

    /** 호출 시점의 요청 스레드에서 호출자를 만들어 카탈로그로 넘기는 콜백. */
    static final class CallerBound implements ToolCallback {

        private final ToolDefinition definition;
        private final ToolCatalog catalog;

        CallerBound(ToolDefinition definition, ToolCatalog catalog) {
            this.definition = definition;
            this.catalog = catalog;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return catalog.call(definition.name(), toolInput, McpApiKeyFilter.callerFromContext());
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }
}
