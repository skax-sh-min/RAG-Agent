package com.example.ragagent.llm;

/**
 * 컨텍스트 초과를 실제로 <b>관측했을 때</b> 그 프로바이더의 창을 다시 재라고 알리는 자리.
 *
 * <p><b>왜 필요한가.</b> {@link ProviderContextWindows} 는 기동 시 한 번 채워진다. 그런데 로컬 LLM 의
 * 창은 앱과 무관하게 바뀐다 — 서버를 다른 {@code -c} 로 재시작하거나, LM Studio 가 JIT 로 다른
 * 설정으로 모델을 올리거나 한다. 기록된 값이 실제보다 <b>크면</b> {@code PromptBudget} 이 매 요청마다
 * 과대한 예산을 잡고, 사전 축소가 걸려야 할 자리에서 안 걸려 서버가 프롬프트를 거절한다. 그리고 그
 * 상태는 스스로 낫지 않는다: 다음 요청도, 그다음도 같은 낡은 숫자로 계산한다.
 *
 * <p>컨텍스트 초과는 <b>"기록된 창이 틀렸다"는 관측 그 자체</b>다. {@code /settings} 에 재탐지
 * 버튼이 있지만 사람이 눌러야 하고, 누를 이유를 알아차리는 것이 바로 이 실패다 — 그래서 그 순간을
 * 자동 재탐지의 트리거로 쓴다.
 *
 * <p>인터페이스로 둔 것은 {@code LlmRouter} 가 HTTP 탐지의 기계를 들지 않게 하기 위해서다.
 * {@link #NOOP} 은 {@code GraphListener.NOOP} 과 같은 규약 — 라우터를 직접 만드는 호출부(테스트,
 * 구 생성자 오버로드)는 아무 일도 하지 않는 구현을 받아 동작이 그대로다.
 */
public interface ContextWindowRefresher {

    /** 아무것도 하지 않는 구현 — 라우터의 구 생성자 오버로드와 테스트가 받는 기본값. */
    ContextWindowRefresher NOOP = provider -> { };

    /**
     * 이 프로바이더가 방금 프롬프트를 컨텍스트 초과로 거절했다.
     *
     * <p><b>실패한 요청을 막아서는 안 된다</b> — 구현은 즉시 돌아와야 하고 탐지는 다른 스레드에서
     * 한다. 던지지도 않는다: 이건 자가 치유이지 이 요청이 기다리는 값이 아니다.
     */
    void refreshAfterOverflow(LlmProvider provider);
}
