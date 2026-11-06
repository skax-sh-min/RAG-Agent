package com.example.ragagent.tool.change;

import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.workflow.UnifiedDiffFormatter;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * {@code generateDiff} — 두 텍스트의 차이를 줄 단위로 요약한다 (AGENT_MCP_DESIGN §3.3). 아무것도 읽거나 쓰지 않는 순수 계산이라 저장소에
 * 닿지 않는다. 입력 길이 상한만 둔다 — {@code ChunkDiff} 의 DP 가 상한을 넘으면 비교하지 않고 {@code tooLarge} 로 돌려준다.
 */
@Component
public class DiffTool {

    /** 한 쪽 텍스트의 글자 상한 — 이 Tool 의 인자는 모델이 만든 JSON 이라 문서 전체가 들어오는 일을 막는다. */
    static final int MAX_TEXT_CHARS = 20_000;

    private final ToolGuard guard;

    public DiffTool(ToolGuard guard) {
        this.guard = guard;
    }

    @Tool(name = "generateDiff",
            description = "Compare two texts line by line and return a short unified-style diff showing only the changed lines with a little context.")
    public UnifiedDiffFormatter.Formatted generateDiff(
            @ToolParam(description = "The original text (required)") String before,
            @ToolParam(description = "The changed text (required)") String after,
            ToolContext context) {
        guard.require(ToolCaller.from(context), ToolAccess.PUBLIC, "generateDiff");
        if (before == null || after == null) {
            throw new IllegalArgumentException("before 와 after 가 모두 필요합니다");
        }
        if (before.length() > MAX_TEXT_CHARS || after.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("텍스트가 너무 깁니다(각각 " + MAX_TEXT_CHARS + "자 이하)");
        }
        return UnifiedDiffFormatter.format(ChunkDiff.compare(before, after));
    }
}
