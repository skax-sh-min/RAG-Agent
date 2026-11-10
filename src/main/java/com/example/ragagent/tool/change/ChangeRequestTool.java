package com.example.ragagent.tool.change;

import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code requestDocumentChange} — 문서 변경 요청을 받아 <b>수정안 만들기를 시작</b>한다(AGENT_MCP_DESIGN §3.3). <b>코퍼스에는 아무것도 쓰지 않는다</b> —
 * 수정안은 관리자 검토 대기열에만 쌓이고 적용은 승인 뒤 다른 단계다. {@link ToolAccess#ADMIN} 이라 일반 호출자에게는 스키마째 보이지 않고, 호출해도 거절된다.
 * 현재는 외부 MCP 클라이언트(관리자 키)가 쓴다 — 채팅 호출자는 늘 관리자가 아니라(채팅의 Tool 선택은 공개 Tool 만) 선택지에 오르지 않는다.
 */
@Component
public class ChangeRequestTool {

    /** {@code status} 가 이 값이면 이미 진행 중인 작업이 있어 새로 시작하지 않았다({@code jobId} 는 0). */
    public static final String STATUS_BUSY = "busy";

    public record Started(long jobId, String status) {}

    private final MaintenanceWorkflow workflow;
    private final ToolGuard guard;

    public ChangeRequestTool(MaintenanceWorkflow workflow, ToolGuard guard) {
        this.workflow = workflow;
        this.guard = guard;
    }

    @Tool(name = "requestDocumentChange",
            description = "Request a change to document text (administrators only). Only creates proposals for review; "
                    + "nothing is written until an administrator approves. Returns a job id.")
    public Started requestDocumentChange(
            @ToolParam(description = "What should be changed, e.g. 'replace PostgreSQL with Oracle' (required)") String instruction,
            @ToolParam(required = false, description = "Exact text to replace, for literal replacement") String from,
            @ToolParam(required = false, description = "Replacement text, for literal replacement") String to,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.ADMIN, "requestDocumentChange");
        try {
            MaintenanceWorkflow.Started s = workflow.start(caller.userId(), instruction, from, to, Locale.KOREAN);
            return new Started(s.jobId(), s.status());
        } catch (IllegalStateException busy) {
            return new Started(0, STATUS_BUSY);
        }
    }
}
