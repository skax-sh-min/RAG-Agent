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

    /**
     * 다시 시도할 수 있게 되기까지 남은 초. 차단 때문이 아니면 {@code -1}(= 해당 없음).
     *
     * <p><b>왜 필요한가.</b> 이 예외의 메시지는 SSE {@code error} 이벤트로 <b>그대로</b> 채팅 버블에
     * 찍힌다({@code chat-stream.js} 의 {@code onError}). 남은 시간을 말해 주지 않으면 사용자가 할 수
     * 있는 일은 계속 눌러 보는 것뿐이고, 실제로 그렇게 됐다 — 실측 로그에서 30초 차단 하나에 재시도
     * 3번이 전부 같은 오류로 죽었다. 언제부터 되는지 알면 기다릴 수 있다.
     */
    private final int retryAfterSeconds;

    public LlmProviderExhaustedException(String message) { this(message, -1); }

    public LlmProviderExhaustedException(String message, int retryAfterSeconds) {
        super("RAG-LLM-001", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 하위 타입 전용 — 자기 코드와 원인을 실어 보낸다. */
    protected LlmProviderExhaustedException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.retryAfterSeconds = -1;   // 프롬프트 크기 문제라 기다린다고 풀리지 않는다
    }

    @Override public int httpStatus() { return 503; }

    @Override public int retryAfterSeconds() { return retryAfterSeconds; }
}
