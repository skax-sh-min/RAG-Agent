package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 모든 Tool 메서드가 <b>첫 줄에서</b> 지나는 권한 검사 (AGENT_MCP_DESIGN §2.2 · §3.1).
 *
 * <p>Tool 은 컨트롤러를 거치지 않고 서비스를 직접 부르므로 URL 필터({@code SecurityConfig})가 지키던 것이 사라진다.
 * {@code ToolCatalog.forCaller} 가 권한 없는 Tool 을 목록에서 빼는 것은 편의이고, 막는 것은 이 검사다 — 목록에 없는 이름을
 * 모델이 지어내 부르거나 직접 메서드를 부르는 경우도 여기서 걸린다.
 *
 * <p>허용된 호출과 거부된 호출을 모두 감사 로그에 남긴다({@code tool.call} · {@code tool.denied}).
 */
@Component
public class ToolGuard {

    private final AuditLogger audit;

    public ToolGuard(AuditLogger audit) {
        this.audit = audit;
    }

    /** 허용 여부만 본다 — 감사 기록도 예외도 없다(목록 거르기용). */
    public static boolean allows(ToolCaller caller, ToolAccess access) {
        return switch (access) {
            case PUBLIC -> true;
            case ADMIN -> caller != null && caller.admin();
        };
    }

    /**
     * @throws ToolDeniedException 호출자에게 허용되지 않을 때
     */
    public void require(ToolCaller caller, ToolAccess access, String toolName) {
        ToolCaller who = caller == null ? ToolCaller.guest() : caller;
        if (!allows(who, access)) {
            record("tool.denied", toolName, who, access);
            throw new ToolDeniedException(toolName);
        }
        record("tool.call", toolName, who, access);
    }

    private void record(String action, String toolName, ToolCaller who, ToolAccess access) {
        try {
            audit.log(action, toolName, Map.of("user", who.userId(), "channel", who.channel(), "access", access.name()));
        } catch (RuntimeException e) {
            // 감사 기록 실패가 권한 판단을 바꾸면 안 된다 — 거부는 거부대로, 허용은 허용대로 진행한다.
        }
    }
}
