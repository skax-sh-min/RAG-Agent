package com.example.ragagent.mcp.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 서버의 API 키 (AGENT_MCP_DESIGN §4.2). {@code AppProperties} 에 넣지 않았다 — 그 레코드는 정식 생성자 인자가 40개를 넘고 생성자마다
 * 모든 호출부가 따라 바뀌며, 이 값들은 {@code /settings} 로 편집하는 값이 아니라(비밀이다) 환경변수로만 들어온다.
 *
 * @param readApiKey  읽기 키 — 관리자 전용이 아닌 Tool. 비면 이 역할은 꺼진다
 * @param adminApiKey 관리자 키 — 모든 Tool. 비면 이 역할은 꺼진다. 읽기 키와 같으면 안 된다
 */
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(String readApiKey, String adminApiKey) {

    /** 키의 최소 길이 — 이보다 짧은 키는 기동을 막는다(짧은 키는 추측이 가능하다). */
    public static final int MIN_KEY_LENGTH = 16;

    public McpProperties {
        readApiKey = readApiKey == null ? "" : readApiKey.strip();
        adminApiKey = adminApiKey == null ? "" : adminApiKey.strip();
    }

    public boolean readEnabled() {
        return !readApiKey.isEmpty();
    }

    public boolean adminEnabled() {
        return !adminApiKey.isEmpty();
    }

    /**
     * 설정이 쓸 수 있는가 — 잘못된 설정은 <b>기동을 막는다</b>(조용히 약한 키로 열리는 것보다 낫다).
     *
     * @throws IllegalStateException 키가 너무 짧거나 읽기 키와 관리자 키가 같을 때
     */
    public void validate() {
        if (readEnabled() && readApiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("MCP_READ_API_KEY 가 너무 짧습니다(" + MIN_KEY_LENGTH + "자 이상)");
        }
        if (adminEnabled() && adminApiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("MCP_ADMIN_API_KEY 가 너무 짧습니다(" + MIN_KEY_LENGTH + "자 이상)");
        }
        if (readEnabled() && adminEnabled() && readApiKey.equals(adminApiKey)) {
            throw new IllegalStateException("MCP_READ_API_KEY 와 MCP_ADMIN_API_KEY 는 달라야 합니다");
        }
    }
}
