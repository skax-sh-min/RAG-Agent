package com.example.ragagent.tool;

/** 호출자에게 허용되지 않은 Tool 호출. 메시지는 호출자에게 그대로 보여도 되는 문장이다(내부 사정을 싣지 않는다). */
public class ToolDeniedException extends RuntimeException {

    private final String toolName;

    public ToolDeniedException(String toolName) {
        super("이 도구를 사용할 권한이 없습니다: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
