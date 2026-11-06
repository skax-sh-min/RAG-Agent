package com.example.ragagent.tool;

/**
 * Tool 하나를 부를 수 있는 호출자의 조건 (AGENT_MCP_DESIGN §3.2). 쓰기 Tool 은 없다 — 적용과 재색인은 승인 뒤 워크플로만 한다.
 */
public enum ToolAccess {
    /** 로그인하지 않은 방문자(게스트)를 포함한 누구나. 채팅이 게스트에게 열려 있는 것과 같은 범위다. */
    PUBLIC,
    /** 관리자만. 목록(스키마)에서도 빠진다. */
    ADMIN
}
