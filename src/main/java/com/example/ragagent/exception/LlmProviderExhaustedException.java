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

    /**
     * 이 소진을 낸 프로바이더가 <b>연속으로</b> 실패한 횟수(성공이 한 번이라도 있으면 0 부터).
     *
     * <p>{@link #retryAfterSeconds} 만으로는 부족한 경우가 있다. 유일한 로컬 프로바이더는 실패해도 5초만
     * 차단되므로 문구는 늘 "4초 후 다시"인데, 2026-09-21 의 GPU 소실처럼 서버 프로세스는 살았는데
     * 엔진이 죽은 경우에는 5초가 지나도 같은 실패가 반복된다 — 그때 "잠시 후 다시"는 거짓이고,
     * 사용자에게 필요한 말은 "기다리지 말고 서버를 보라"다. 한 번의 실패로는 삐끗한 것과 죽은 것을
     * 가를 수 없어 횟수를 센다({@link #REPEATED_FAILURE_THRESHOLD}).
     */
    private final int consecutiveFailures;

    /** 이 횟수부터 "잠시 후 다시" 대신 "서버 상태를 확인하라"고 말한다. */
    public static final int REPEATED_FAILURE_THRESHOLD = 3;

    public LlmProviderExhaustedException(String message) { this(message, -1); }

    public LlmProviderExhaustedException(String message, int retryAfterSeconds) {
        this(message, retryAfterSeconds, 0);
    }

    public LlmProviderExhaustedException(String message, int retryAfterSeconds, int consecutiveFailures) {
        super("RAG-LLM-001", message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
    }

    /** 하위 타입 전용 — 자기 코드와 원인을 실어 보낸다. */
    protected LlmProviderExhaustedException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
        this.retryAfterSeconds = -1;   // 프롬프트 크기 문제라 기다린다고 풀리지 않는다
        this.consecutiveFailures = 0;
    }

    @Override public int httpStatus() { return 503; }

    @Override public int retryAfterSeconds() { return retryAfterSeconds; }

    public int consecutiveFailures() { return consecutiveFailures; }

    /** 연속 실패가 문턱을 넘었는가 — 문구를 "기다려라"에서 "서버를 보라"로 바꾸는 기준. */
    public boolean repeated() { return consecutiveFailures >= REPEATED_FAILURE_THRESHOLD; }
}
