package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.DualResult;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — AnswerService 4가지 경로
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - BLOCKING (COST_FIRST)
 *  - STREAMING (GraphListener token 전달)
 *  - DUAL BLOCKING (LOCAL + 외부 둘 다 호출)
 *  - DUAL STREAMING (token sink 양쪽 분기)
 *  - PROGRESSIVE 업그레이드 (sufficient=false 후 PREMIUM 호출)
 */
class AnswerServiceTest {

    private static final int MAX_RETRY = 2;

    private ChatClient chatClient;
    private LlmRouter llmRouter;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        llmRouter = mock(LlmRouter.class);
        AppProperties props = new AppProperties(
                "./data", MAX_RETRY, 8000, 800, 100, 7, 0.0, true, 0, false,
                null, null, null, null, null, null, null, null, null);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new AnswerService(chatClient, llmRouter, props, messageSource);
    }

    private AgentState newState(RoutingMode mode) {
        return AgentState.of("질문", "v1", "t1", "", mode);
    }

    // ── BLOCKING 경로 ────────────────────────────────────────────────────
    // AnswerService 블로킹 경로는 .stream().content().blockLast() 를 사용하므로
    // Flux<String> 으로 stub 해야 한다 (.call().chatResponse() 는 호출되지 않음).
    // 스트리밍 경로는 토큰 수를 추적하지 않으므로 입출력 토큰은 0 으로 누적된다.

    @Test
    @DisplayName("BLOCKING COST_FIRST — 답변+sufficiency 2회 호출, llmCallCount=2")
    void blocking_costFirst_basicFlow() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("## 요약\n핵심 답변"), Flux.just("{\"sufficient\":true}"));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.answer()).isEqualTo("## 요약\n핵심 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-flash");
        assertThat(result.needsRetry()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(0);
        assertThat(result.totalOutputTokens()).isEqualTo(0);
        assertThat(result.llmCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("BLOCKING — sufficiency=false 면 needsRetry=true")
    void blocking_sufficiency_false_setsNeedsRetry() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("불완전 답변"), Flux.just("{\"sufficient\":false}"));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isTrue();
    }

    @Test
    @DisplayName("BLOCKING — sufficiency 파싱 실패 시 sufficient 처리 (fail-safe)")
    void blocking_sufficiency_parse_error_treatsAsSufficient() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("답변"), Flux.just("not-a-json"));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState result = service.execute(newState(RoutingMode.COST_FIRST));

        assertThat(result.needsRetry()).isFalse();
    }

    // ── DUAL BLOCKING 경로 ───────────────────────────────────────────────

    @Test
    @DisplayName("DUAL BLOCKING — local+external 답변 + needsRetry=false")
    @SuppressWarnings("unchecked")
    void dual_blocking_capturesLocalAndExternal() {
        DualResult dual = new DualResult(
                "로컬 답변", "local",
                "외부 답변", "gemini-flash");
        when(llmRouter.executeDual(eq(TaskType.TEXT), any(Function.class))).thenReturn(dual);

        AgentState result = service.execute(newState(RoutingMode.DUAL));

        assertThat(result.answer()).isEqualTo("외부 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-flash");
        assertThat(result.dualLocalAnswer()).isEqualTo("로컬 답변");
        assertThat(result.dualLocalProvider()).isEqualTo("local");
        assertThat(result.needsRetry()).isFalse();
        verify(llmRouter, times(1)).executeDual(eq(TaskType.TEXT), any(Function.class));
    }

    // ── DUAL STREAMING 경로 ──────────────────────────────────────────────

    @Test
    @DisplayName("DUAL STREAMING — listener 가 local/external 두 채널 토큰 모두 수신")
    @SuppressWarnings("unchecked")
    void dual_streaming_emitsBothChannelTokens() {
        List<String> localTokens = new ArrayList<>();
        List<String> externalTokens = new ArrayList<>();
        GraphListener listener = new GraphListener() {
            @Override public void onToken(String tab, String text) {
                if ("local".equals(tab)) localTokens.add(text);
                else externalTokens.add(text);
            }
        };

        // executeDualStream 은 양쪽 sink 에 토큰을 푸시하고 DualProviders 반환하도록 stub.
        when(llmRouter.executeDualStream(eq(TaskType.TEXT), any(), any(), any()))
                .thenAnswer(inv -> {
                    Consumer<String> localSink = inv.getArgument(2);
                    Consumer<String> externalSink = inv.getArgument(3);
                    localSink.accept("L1");
                    localSink.accept("L2");
                    externalSink.accept("X1");
                    externalSink.accept("X2");
                    externalSink.accept("X3");
                    return new LlmRouter.DualProviders("local", "gemini-flash");
                });

        AgentState result = service.executeStreaming(newState(RoutingMode.DUAL), listener);

        assertThat(localTokens).containsExactly("L1", "L2");
        assertThat(externalTokens).containsExactly("X1", "X2", "X3");
        assertThat(result.answer()).isEqualTo("X1X2X3");
        assertThat(result.dualLocalAnswer()).isEqualTo("L1L2");
        assertThat(result.usedProvider()).isEqualTo("gemini-flash");
        assertThat(result.dualLocalProvider()).isEqualTo("local");
        assertThat(result.needsRetry()).isFalse();
    }

    // ── PROGRESSIVE 업그레이드 경로 ───────────────────────────────────────

    @Test
    @DisplayName("PROGRESSIVE — sufficiency=false + retryCount>=maxRetry → QUALITY_FIRST 업그레이드")
    @SuppressWarnings("unchecked")
    void progressive_upgrade_triggersQualityFirst() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("초안 답변"), Flux.just("{\"sufficient\":false}"));
        when(llmRouter.findProviderName(any(), eq(RoutingMode.PROGRESSIVE)))
                .thenReturn("gemini-flash");
        LlmProvider premiumProvider = new LlmProvider(
                "gemini-pro", TaskType.TEXT, ProviderRole.PREMIUM, 4, "key", null, null, true, null, null);
        when(llmRouter.routeProvider(any(), eq(RoutingMode.QUALITY_FIRST)))
                .thenReturn(premiumProvider);
        when(llmRouter.executeWithTracking(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class)))
                .thenReturn("프리미엄 답변");

        // retryCount = maxRetry 도달 상태에서 진입 (그래프가 retry 한도 초과 후 PROGRESSIVE 진입 시 시나리오)
        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .withRetryCountIncremented().withRetryCountIncremented();

        AgentState result = service.execute(initial);

        assertThat(result.answer()).isEqualTo("프리미엄 답변");
        assertThat(result.usedProvider()).isEqualTo("gemini-pro");
        assertThat(result.premiumUpgraded()).isEqualTo("gemini-pro");
        assertThat(result.needsRetry()).isFalse();
        verify(llmRouter, times(1))
                .executeWithTracking(eq(TaskType.TEXT), eq(RoutingMode.QUALITY_FIRST), any(Function.class));
    }

    @Test
    @DisplayName("PROGRESSIVE — sufficient=true 면 업그레이드 안 함")
    void progressive_sufficient_noUpgrade() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("충분한 답변"), Flux.just("{\"sufficient\":true}"));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        AgentState initial = newState(RoutingMode.PROGRESSIVE)
                .withRetryCountIncremented().withRetryCountIncremented();

        AgentState result = service.execute(initial);

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.answer()).isEqualTo("충분한 답변");
    }

    @Test
    @DisplayName("PROGRESSIVE — needsRetry 더라도 retryCount < maxRetry 면 업그레이드 안 함")
    void progressive_underRetryLimit_noUpgrade() {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("답변"), Flux.just("{\"sufficient\":false}"));
        when(llmRouter.findProviderName(any(), any())).thenReturn("gemini-flash");

        // retryCount = 0 (< maxRetry=2) → 업그레이드 조건 미충족, needsRetry=true 만 전파
        AgentState result = service.execute(newState(RoutingMode.PROGRESSIVE));

        assertThat(result.premiumUpgraded()).isNull();
        assertThat(result.needsRetry()).isTrue();
    }
}
