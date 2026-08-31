package com.example.ragagent.exception;

/**
 * 라우팅 가능한 프로바이더를 모두 시도했으나 아무도 응답하지 못했다.
 *
 * <p>{@code final} 이 아니라 {@code sealed} 인 이유는 {@link LlmContextOverflowException} 하나 때문이다 —
 * 그쪽은 "프로바이더가 죽어서"가 아니라 "프롬프트가 커서" 같은 지점에 도달하는데, 도달 경로가 같으므로
 * 기존에 소진을 잡아 우아하게 물러나던 자리들이 그대로 잡아야 한다. 그래서 형제가 아니라 하위 타입이다.
 */
public sealed class LlmProviderExhaustedException extends RagException
        permits LlmContextOverflowException {

    public LlmProviderExhaustedException(String message) { super("RAG-LLM-001", message); }

    /** 하위 타입 전용 — 자기 코드와 원인을 실어 보낸다. */
    protected LlmProviderExhaustedException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override public int httpStatus() { return 503; }
}
