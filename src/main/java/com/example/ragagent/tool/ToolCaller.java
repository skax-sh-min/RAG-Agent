package com.example.ragagent.tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/**
 * Tool 을 부르는 쪽 — <b>요청 스레드에서 만들어 명시적으로 넘긴다</b>(AGENT_MCP_DESIGN §2.2). SSE 워커(가상 스레드)에는
 * {@code SecurityContext} 가 없으므로 Tool 안에서 현재 사용자를 읽으면 안 된다.
 *
 * <p>LLM 이 보는 Tool 인자에는 {@code userId}·{@code version} 이 없다. 호출자는 인자가 아니라 {@link ToolContext} 로 들어온다 —
 * 모델이 만든 JSON 으로는 호출자를 바꿀 수 없다.
 *
 * @param userId  호출자 id(감사 기록용). 비어 있으면 {@code anonymous}
 * @param admin   관리자인가 — {@link ToolAccess#ADMIN} Tool 의 조건
 * @param channel 어디서 불렀는가({@code chat} · {@code mcp}) — 감사 기록용
 */
public record ToolCaller(String userId, boolean admin, String channel) {

    /** {@link ToolContext} 안에서 호출자를 담는 키. */
    public static final String CONTEXT_KEY = "toolCaller";

    public ToolCaller {
        if (userId == null || userId.isBlank()) userId = "anonymous";
        if (channel == null || channel.isBlank()) channel = "chat";
    }

    /** 권한이 가장 낮은 호출자 — 컨텍스트가 없거나 읽을 수 없을 때 쓴다. */
    public static ToolCaller guest() {
        return new ToolCaller("anonymous", false, "chat");
    }

    public ToolContext toContext() {
        return new ToolContext(Map.of(CONTEXT_KEY, this));
    }

    /**
     * 컨텍스트에서 호출자를 꺼낸다. <b>없거나 타입이 다르면 게스트</b>다 — 권한이 모자란 쪽으로 실패한다.
     */
    public static ToolCaller from(ToolContext context) {
        if (context == null || context.getContext() == null) return guest();
        return context.getContext().get(CONTEXT_KEY) instanceof ToolCaller c ? c : guest();
    }
}
