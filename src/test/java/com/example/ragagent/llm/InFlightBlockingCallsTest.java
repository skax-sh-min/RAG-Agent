package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.29 열린 항목 (e) — SSE 유휴 워치독이 읽는 "이 턴에서 응답을 기다리는 블로킹 호출이 있는가".
 * 키는 MDC 의 {@code traceId} 이고, 테스트마다 다른 id 를 써서 병렬 실행에서도 서로 간섭하지 않는다.
 */
@ResourceLock("global-state")   // MDC 는 Logback 의 스레드 로컬이라, 다른 클래스의 스프링 컨텍스트 기동(Logback 재초기화)이 값을 지울 수 있다
class InFlightBlockingCallsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("진행 중에만 참이고, 닫으면 거짓이며, 두 번 닫아도 한 번만 내린다")
    void countsOnlyWhileOpen() {
        MDC.put(InFlightBlockingCalls.TRACE_ID_KEY, "t-open");
        assertThat(InFlightBlockingCalls.active("t-open")).isFalse();

        InFlightBlockingCalls.Token a = InFlightBlockingCalls.enter();
        InFlightBlockingCalls.Token b = InFlightBlockingCalls.enter();
        assertThat(InFlightBlockingCalls.active("t-open")).isTrue();

        a.close();
        a.close();   // 두 번째는 무시 — b 의 몫을 내리면 안 된다
        assertThat(InFlightBlockingCalls.active("t-open")).as("b 가 아직 진행 중").isTrue();
        b.close();
        assertThat(InFlightBlockingCalls.active("t-open")).isFalse();
    }

    @Test
    @DisplayName("MDC 에 traceId 가 없으면 아무것도 세지 않는다 — 어느 턴의 호출인지 모르면 남의 워치독을 늦추지 않는다")
    void withoutATraceIdNothingIsCounted() {
        try (InFlightBlockingCalls.Token ignored = InFlightBlockingCalls.enter()) {
            assertThat(InFlightBlockingCalls.active(null)).isFalse();
            assertThat(InFlightBlockingCalls.active("")).isFalse();
        }
    }

    @Test
    @DisplayName("턴마다 따로다 — 다른 id 의 호출은 보이지 않는다")
    void turnsAreIndependent() {
        MDC.put(InFlightBlockingCalls.TRACE_ID_KEY, "t-a");
        try (InFlightBlockingCalls.Token ignored = InFlightBlockingCalls.enter()) {
            assertThat(InFlightBlockingCalls.active("t-a")).isTrue();
            assertThat(InFlightBlockingCalls.active("t-b")).isFalse();
        }
    }

    // ── 라우터의 블로킹 진입점이 실제로 알린다 ───────────────────────────────────

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("답"))));
    }

    private static LlmRouter router(ChatModel model) {
        LlmProvider p = new LlmProvider("local", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true, model, null);
        return new LlmRouter(List.of(p), mock(LlmUsageRepository.class), new CircuitBreaker(2), RoutingMode.COST_FIRST,
                180, Map.of(), 3, 5, new ProviderToggle());
    }

    @Test
    @DisplayName("executeGated · executeGatedWithUsage · executeWithTracking — 응답을 기다리는 동안 참이고 끝나면 거짓이다")
    void routerBlockingEntryPointsReportInFlight() {
        MDC.put(InFlightBlockingCalls.TRACE_ID_KEY, "t-router");
        AtomicInteger activeDuringCall = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            if (InFlightBlockingCalls.active("t-router")) activeDuringCall.incrementAndGet();
            return response();
        });
        LlmRouter r = router(model);

        r.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST, m -> m.call(new Prompt("q")));
        r.executeGatedWithUsage(TaskType.TEXT, RoutingMode.COST_FIRST, m -> m.call(new Prompt("q")));
        r.executeWithTracking(TaskType.TEXT, RoutingMode.COST_FIRST, "bg:", m -> m.call(new Prompt("q")));

        assertThat(activeDuringCall.get()).as("세 진입점 모두 호출 중에는 참").isEqualTo(3);
        assertThat(InFlightBlockingCalls.active("t-router")).as("끝나면 내린다").isFalse();
    }

    @Test
    @DisplayName("호출이 실패해도 내린다 — 멈춘 호출이 읽기 타임아웃으로 끊기면 유휴 시계가 다시 흐른다")
    void aFailedCallStillLeaves() {
        MDC.put(InFlightBlockingCalls.TRACE_ID_KEY, "t-fail");
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("read timed out"));

        assertThatThrownBy(() -> router(model).executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("q")))).isInstanceOf(RuntimeException.class);

        assertThat(InFlightBlockingCalls.active("t-fail")).isFalse();
    }
}
