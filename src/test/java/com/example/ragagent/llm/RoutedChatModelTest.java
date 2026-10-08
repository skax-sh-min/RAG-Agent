package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 질의 확장({@code MultiQueryExpander})이 쥐는 모델 — 기동 때 고른 프로바이더에 못 박혀 있던 것을 <b>호출마다</b> 라우터가 고르게
 * 바꿨다. 같은 로컬 서버 두 대 중 먼저 등록된 쪽이 죽어도 확장은 계속 거기로 가서 질문마다 타임아웃만큼 느려졌고(차단도 전환도
 * 없었다), 그 이유는 프로바이더를 한 번 고른 {@code ChatModel} 을 오래 쥐었기 때문이다.
 */
class RoutedChatModelTest {

    private LlmUsageRepository usage;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        usage = mock(LlmUsageRepository.class);
        breaker = new CircuitBreaker(2);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatModel answering(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(text));
        return model;
    }

    private static ChatModel failing(String message) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException(message));
        return model;
    }

    private static LlmProvider local(String name, TaskType type, int priority, ChatModel model) {
        return new LlmProvider(name, type, ProviderRole.LOCAL, priority, "k", null, null, true, model, null);
    }

    private LlmRouter router(LlmProvider... providers) {
        return new LlmRouter(List.of(providers), usage, breaker, RoutingMode.COST_FIRST, 180, Map.of(), 3, 5,
                new ProviderToggle());
    }

    private RoutedChatModel expansionModel(LlmRouter router) {
        return new RoutedChatModel(router, ThinkingSite.QUERY_EXPANSION);
    }

    @Test
    @DisplayName("호출한 쪽이 돌려받는 것은 서비스한 프로바이더의 ChatResponse 이고, 사용량은 그 프로바이더 이름으로 남는다")
    void returnsTheServingProvidersResponseAndRecordsUsageForIt() {
        ChatModel first = answering("변형1\n변형2");
        LlmRouter r = router(local("local", TaskType.BOTH, 1, first), local("local-2", TaskType.BOTH, 1, answering("other")));

        ChatResponse out = expansionModel(r).call(new Prompt("질문"));

        assertThat(out.getResult().getOutput().getText()).isEqualTo("변형1\n변형2");
        verify(usage).record("local", 0, 0);   // 사용량 기록은 라우터가 한다
    }

    @Test
    @DisplayName("죽은 프로바이더는 같은 호출 안에서 건너뛰고, 차단돼서 다음 호출은 처음부터 살아 있는 쪽으로 간다")
    void aDeadProviderIsBypassedNowAndRememberedForTheNextCall() {
        ChatModel dead = failing("Connection refused");
        ChatModel alive = answering("from-local-2");
        LlmRouter r = router(local("local", TaskType.BOTH, 1, dead), local("local-2", TaskType.BOTH, 1, alive));
        RoutedChatModel model = expansionModel(r);

        assertThat(model.call(new Prompt("1")).getResult().getOutput().getText()).isEqualTo("from-local-2");
        assertThat(model.call(new Prompt("2")).getResult().getOutput().getText()).isEqualTo("from-local-2");

        verify(dead, times(1)).call(any(Prompt.class));   // 두 번째 호출은 죽은 서버에 닿지도 않았다
        verify(alive, times(2)).call(any(Prompt.class));
        assertThat(breaker.isBlocked("local")).isTrue();
        verify(usage, times(2)).record("local-2", 0, 0);
    }

    @Test
    @DisplayName("라우팅은 호출 지점이 정한다 — 소형(MICRO_TEXT)이 있으면 그쪽이 먼저, 차단되면 같은 서버의 큰 모델로 내려간다")
    void routesByTheSite() {
        ChatModel small = answering("from-small");
        ChatModel big = answering("from-big");
        LlmRouter r = router(local("local-fast", TaskType.MICRO_TEXT, 0, small), local("local", TaskType.BOTH, 1, big));
        RoutedChatModel model = expansionModel(r);

        assertThat(model.call(new Prompt("q")).getResult().getOutput().getText()).isEqualTo("from-small");

        breaker.block("local-fast", "30");
        assertThat(model.call(new Prompt("q")).getResult().getOutput().getText()).isEqualTo("from-big");
    }

    @Test
    @DisplayName("전부 실패하면 소진 예외가 올라간다 — RetrievalService 가 잡아 원문 검색으로 물러난다")
    void exhaustionPropagates() {
        LlmRouter r = router(local("local", TaskType.BOTH, 1, failing("Connection refused")),
                local("local-2", TaskType.BOTH, 1, failing("Connection refused")));

        assertThatThrownBy(() -> expansionModel(r).call(new Prompt("q"))).isInstanceOf(LlmProviderExhaustedException.class);
    }

    @Test
    @DisplayName("읽기 타임아웃은 예전처럼 그대로 올라간다 — 느린 서버를 막지 않는다")
    void readTimeoutPropagatesWithoutBlocking() {
        ChatModel slow = mock(ChatModel.class);
        when(slow.call(any(Prompt.class))).thenThrow(
                new RuntimeException("I/O error", new java.net.SocketTimeoutException("Read timed out")));
        ChatModel other = answering("never");
        LlmRouter r = router(local("local", TaskType.BOTH, 1, slow), local("local-2", TaskType.BOTH, 1, other));

        assertThatThrownBy(() -> expansionModel(r).call(new Prompt("q"))).hasRootCauseMessage("Read timed out");

        assertThat(breaker.isBlocked("local")).isFalse();
        verify(other, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("스트리밍은 지원하지 않는다 — 확장기는 .call() 만 쓴다")
    void streamingIsNotSupported() {
        LlmRouter r = router(local("local", TaskType.BOTH, 1, answering("x")));

        assertThatThrownBy(() -> expansionModel(r).stream(new Prompt("q")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
