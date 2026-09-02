package com.example.ragagent.exception;

/**
 * Thrown when no provider could accept the prompt because it outgrew the LLM server's context
 * window — not because the providers were down.
 *
 * <p><b>{@link LlmProviderExhaustedException} 을 상속하는 것이 핵심이다.</b> 도달 경로가 같기 때문에
 * (라우터가 모든 후보를 돌고 나서 던진다) 기존에 소진을 잡아 우아하게 물러나던 자리들 —
 * {@code MarkdownCorrectionService} 는 LLM 없이 원문을 그대로 두고, {@code LlmConfig} 의 기동 워밍업은
 * 조용히 넘어간다 — 이 그대로 동작해야 한다. 형제 클래스로 만들었다면 그 자리들이 이 예외를 못 잡아
 * 인덱싱이 우아한 강등 대신 실패했을 것이다. 구분된 문구가 필요한 곳만 이 타입을 <b>먼저</b> 잡는다
 * (자바는 하위 타입 catch 가 앞에 와야 하므로 순서가 곧 규약이다).
 *
 * <p>HTTP 는 부모의 503 이 아니라 <b>500</b> 이다. 503 은 "지금은 안 되니 이따 다시"라는 뜻인데 이
 * 실패는 결정적이라 같은 요청을 다시 보내면 똑같이 실패한다 — 고쳐야 할 것은 시간이 아니라 프롬프트
 * 크기(검색 문서 수·{@code max-tokens})이거나 서버의 컨텍스트 설정이다.
 *
 * <p>사용자에게 나가는 문구는 {@code error.llm.context-overflow} 이며 두 가지를 함께 말한다:
 * 질문을 좁히면 사용자가 스스로 풀 수 있고, 그렇지 않으면 관리자가 {@code search-top-k} 를 낮춰야
 * 한다. 이 구분이 없던 때는 "모든 프로바이더를 사용할 수 없습니다" 가 나가서, 실제로는 프로바이더가
 * 멀쩡하고 프롬프트만 컸던 상황에서 아무도 고칠 곳을 찾지 못했다.
 */
public final class LlmContextOverflowException extends LlmProviderExhaustedException {

    public LlmContextOverflowException(String providerName, Throwable cause) {
        super("RAG-LLM-003",
              "Prompt exceeded the LLM context window on provider [" + providerName
                      + "] and no other provider accepted it",
              cause);
    }

    /** 결정적 실패라 재시도를 권하는 503 이 아니다 — {@link LlmContextOverflowException} 클래스 주석 참고. */
    @Override public int httpStatus() { return 500; }
}
