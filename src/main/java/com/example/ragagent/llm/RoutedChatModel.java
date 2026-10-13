package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>호출할 때마다</b> 라우터에 프로바이더를 묻는 {@link ChatModel} — 프레임워크가 모델을 한 번 받아 오래 들고 쓰는 자리
 * ({@code RetrievalService} 의 {@code MultiQueryExpander} 가 자기 {@code ChatClient} 를 만들 때)를 위한 것이다.
 *
 * <p><b>왜 필요한가.</b> 예전에는 기동 시 한 번 고른 프로바이더의 {@code ChatModel} 을 그대로 쥐고 동시성 게이트·사용량 기록
 * 데코레이터({@code ConcurrencyLimitingChatModel}·{@code TrackingChatModel})만 감쌌다. 그러면 그 프로바이더가 평생 고정이다 —
 * 같은 로컬 서버 두 대 중 먼저 등록된 쪽(대개 {@code LOCAL_LLM_URL})이 죽어도 확장은 계속 그쪽으로 가고, 차단기에는 실패가 한 번도
 * 보고되지 않으며, 다른 서버로 넘어가지도 않는다. 확장이 실패하면 원문 검색으로 물러나므로 오류로는 안 보이지만, 죽은 서버에
 * 닿는 시간(연결 타임아웃, 읽기 타임아웃이면 더 길다)만큼 매 질문이 느려진다.
 *
 * <p>이제 호출마다 {@link LlmRouter#executeGatedWithUsage} 를 지난다 — 분류·질의 확장·재순위·검증과 <b>같은 블로킹 경로</b>라서
 * 프로바이더 선택(least-in-flight)·동시성 게이트·사용량 기록·실패 시 차단과 다음 프로바이더로의 전환을 한꺼번에 받고, 그 규칙이
 * 두 벌로 갈라지지 않는다. 두 데코레이터가 하던 일을 라우터가 그대로 한다.
 *
 * <p>라우팅(작업 유형·모드)은 호출 지점({@link ThinkingSite})이 정한다 — 호출부가 리터럴을 두지 않는다
 * ({@code ThinkingSiteConventionTest}). 생각 수준의 사이트 표시는 이 모델 <b>앞</b>의 {@link ThinkingSiteChatModel} 이 하고,
 * 표시를 읽어 걷어내는 것은 고른 프로바이더의 체인({@code ThinkingControlChatModel})이다.
 *
 * <p>블로킹 호출({@link #call})만 지원한다 — 확장기는 {@code .call()} 만 쓴다.
 */
public class RoutedChatModel implements ChatModel {

    private final LlmRouter router;
    private final ThinkingSite site;

    public RoutedChatModel(LlmRouter router, ThinkingSite site) {
        this.router = router;
        this.site = site;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // 라우터는 호출을 불투명한 클로저로 받고 텍스트·토큰만 돌려준다. ChatClient 가 기다리는 것은 ChatResponse 라서, 성공한
        // 프로바이더의 응답을 클로저에서 붙잡는다 — 장애 전환이 있었다면 마지막(= 성공한) 시도의 것이 남는다.
        AtomicReference<ChatResponse> served = new AtomicReference<>();
        router.executeGatedWithUsage(site.taskType(), site.fixedRoutingMode(), model -> {
            ChatResponse response = model.call(prompt);
            served.set(response);
            return response;
        });
        return served.get();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("RoutedChatModel 은 블로킹 호출만 라우팅한다");
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // 프로바이더마다 다르고 호출 때에야 정해진다 — 옵션은 고른 프로바이더의 체인이 기본값과 합친다.
        return ChatOptions.builder().build();
    }
}
