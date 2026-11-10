package com.example.ragagent.tool.change;

import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.workflow.ImpactAnalysisWorkflow;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code analyzeImpact} — 변경·삭제 대상의 영향 범위 분석을 <b>시작</b>한다(AGENT_MCP_DESIGN §3.3). 결과를 기다리지 않고 작업 번호만 돌려준다 —
 * 분석은 {@link ImpactAnalysisWorkflow} 가 따로 돌리고 리포트는 작업 링크에서 본다. 코퍼스에는 아무것도 쓰지 않는다.
 *
 * <p>호출자의 사용자 id 는 인자가 아니라 {@link ToolContext} 로 들어온다. 이미 진행 중인 분석이 있으면 새로 만들지 않고 {@code busy} 를 돌려준다 —
 * 예외로 던지면 채팅이 그 턴을 일반 검색으로 내려보내 안내가 사라진다.
 */
@Component
public class ImpactAnalysisTool {

    /** {@code status} 가 이 값이면 이미 진행 중인 분석이 있어 새로 시작하지 않았다({@code jobId} 는 0). */
    public static final String STATUS_BUSY = "busy";

    public record Started(long jobId, String status) {}

    private final ImpactAnalysisWorkflow workflow;
    private final ToolGuard guard;

    public ImpactAnalysisTool(ImpactAnalysisWorkflow workflow, ToolGuard guard) {
        this.workflow = workflow;
        this.guard = guard;
    }

    @Tool(name = "analyzeImpact",
            description = "Start an analysis of which documents are affected when something is changed or deleted. "
                    + "Finds every passage that mentions the subject and judges each one; returns a job id, the report is read later.")
    public Started analyzeImpact(
            @ToolParam(description = "What is being changed or removed, e.g. a class or method name (required)") String subject,
            @ToolParam(required = false, description = "Short note about the planned change") String changeNote,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.PUBLIC, "analyzeImpact");
        try {
            ImpactAnalysisWorkflow.Started s = workflow.start(caller.userId(), subject, changeNote, Locale.KOREAN);
            return new Started(s.jobId(), s.status());
        } catch (IllegalStateException busy) {
            return new Started(0, STATUS_BUSY);
        }
    }
}
