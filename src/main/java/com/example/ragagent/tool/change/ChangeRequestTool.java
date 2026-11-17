package com.example.ragagent.tool.change;

import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.workflow.EditIntent;
import com.example.ragagent.workflow.KnowledgeEditWorkflow;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** 자연어 수정 워크플로(6c) — 없으면 지시문만의 요청도 글자 치환 워크플로가 받는다(예전 동작). */
    private final KnowledgeEditWorkflow knowledgeEdit;

    @Autowired
    public ChangeRequestTool(MaintenanceWorkflow workflow, ToolGuard guard, KnowledgeEditWorkflow knowledgeEdit) {
        this.workflow = workflow;
        this.guard = guard;
        this.knowledgeEdit = knowledgeEdit;
    }

    /** 자연어 수정 없이 — 테스트용. */
    public ChangeRequestTool(MaintenanceWorkflow workflow, ToolGuard guard) {
        this(workflow, guard, null);
    }

    @Tool(name = "requestDocumentChange",
            description = "Request a change to document knowledge (administrators only). Only creates proposals for review; "
                    + "nothing is written until an administrator approves. Give from/to for a literal replacement, or just the instruction "
                    + "in natural language (e.g. 'Feature A is no longer used', 'delete the content about B', 'add a caution about B: ...'); "
                    + "type/subject/content skip the interpretation. Returns a job id.")
    public Started requestDocumentChange(
            @ToolParam(description = "What should be changed, e.g. 'replace PostgreSQL with Oracle' or 'Feature A is no longer used' (required)") String instruction,
            @ToolParam(required = false, description = "Exact text to replace, for literal replacement") String from,
            @ToolParam(required = false, description = "Replacement text, for literal replacement") String to,
            @ToolParam(required = false, description = "True to also let the model propose related changes beyond the literal replacement (drivers, ports, SQL syntax); costs one model call per chunk and every such proposal needs review") Boolean semantic,
            @ToolParam(required = false, description = "True to also replace spellings that differ from the text only in letter case (ORACLE, oracle), shaped to the matched case; may change identifiers or string constants, so review the diff") Boolean caseVariants,
            @ToolParam(required = false, description = "deprecate | remove | add_note | revise — set to skip interpreting the instruction") String type,
            @ToolParam(required = false, description = "The feature, function or term the change is about (with type)") String subject,
            @ToolParam(required = false, description = "add_note: the caution to add; revise: the new description (with type)") String content,
            @ToolParam(required = false, description = "deprecate: the alternative to use instead, if any") String replacement,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.ADMIN, "requestDocumentChange");
        try {
            boolean literal = from != null && !from.isBlank() && to != null && !to.isBlank();
            boolean typed = type != null && !type.isBlank() && !EditIntent.REPLACE.equals(type.strip());
            if (knowledgeEdit != null && (typed || !literal)) {
                EditIntent intent = typed ? new EditIntent(type, subject, content, replacement, null, null) : null;
                KnowledgeEditWorkflow.Started s = knowledgeEdit.start(caller.userId(), instruction, intent, Locale.KOREAN);
                return new Started(s.jobId(), s.status());
            }
            MaintenanceWorkflow.Started s = workflow.start(caller.userId(), instruction, from, to, Boolean.TRUE.equals(semantic), Boolean.TRUE.equals(caseVariants), Locale.KOREAN);
            return new Started(s.jobId(), s.status());
        } catch (IllegalStateException busy) {
            return new Started(0, STATUS_BUSY);
        }
    }
}
