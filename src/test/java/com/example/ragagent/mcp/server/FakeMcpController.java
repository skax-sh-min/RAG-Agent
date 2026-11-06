package com.example.ragagent.mcp.server;

import com.example.ragagent.tool.ToolCaller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 보안 슬라이스 테스트용 가짜 엔드포인트 — 실제 MCP SDK 엔드포인트와 같은 경로에서, <b>요청 스레드</b>의 호출자를 읽어 돌려준다(Tool 이 그
 * 스레드에서 돈다). 최상위 클래스여야 한다: 테스트 클래스의 중첩 클래스는 스프링 부트의 {@code TestTypeExcludeFilter} 가 스캔에서 뺀다.
 */
@RestController
class FakeMcpController {

    @PostMapping({"/api/v1/mcp", "/api/v1/mcp/sub"})
    Map<String, Object> mcp() {
        ToolCaller c = McpApiKeyFilter.callerFromContext();
        return Map.of("user", c.userId(), "admin", c.admin(), "channel", c.channel());
    }

    @GetMapping({"/api/v1/mcp", "/api/v1/mcp/sub"})
    Map<String, Object> mcpGet() {
        return mcp();
    }

    /** 모드 제어용 일반 경로 — 관리자가 없는 no-auth 에서 /setup 으로 302 되는지 대조한다. */
    @GetMapping("/plain-page")
    String plain() {
        return "ok";
    }
}
