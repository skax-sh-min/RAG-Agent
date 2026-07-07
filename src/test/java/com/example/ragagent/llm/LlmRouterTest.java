package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — LlmRouter routing & guard behaviour
 *
 * Focuses on the side-effect-free surface: findFirst() priority by RoutingMode,
 * hasLocalProvider(), findProviderName(), and DUAL preconditions. We do NOT
 * exercise executeWithTracking / executeDual here — those require a non-null
 * usage repository and live ChatModel calls and belong in a Mockito-based test.
 */
class LlmRouterTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker(2);
    }

    private LlmProvider p(String name, ProviderRole role, TaskType type, int priority) {
        return new LlmProvider(name, type, role, priority, "k", null, null, true, (ChatModel) null, null);
    }

    private LlmRouter router(RoutingMode defaultMode, LlmProvider... providers) {
        return new LlmRouter(List.of(providers), null, breaker, defaultMode, 0.6);
    }

    @Test
    @DisplayName("COST_FIRST: LOCAL > NORMAL > PREMIUM 순으로 선택")
    void costFirstPrefersLocal() {
        var local   = p("lm",     ProviderRole.LOCAL,   TaskType.TEXT, 1);
        var normal  = p("openai", ProviderRole.NORMAL,  TaskType.TEXT, 2);
        var premium = p("claude", ProviderRole.PREMIUM, TaskType.TEXT, 3);
        var r = router(RoutingMode.COST_FIRST, local, normal, premium);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
    }

    @Test
    @DisplayName("QUALITY_FIRST: PREMIUM > NORMAL > LOCAL 순으로 선택")
    void qualityFirstPrefersPremium() {
        var local   = p("lm",     ProviderRole.LOCAL,   TaskType.TEXT, 1);
        var normal  = p("openai", ProviderRole.NORMAL,  TaskType.TEXT, 2);
        var premium = p("claude", ProviderRole.PREMIUM, TaskType.TEXT, 3);
        var r = router(RoutingMode.COST_FIRST, local, normal, premium);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.QUALITY_FIRST)).isEqualTo("claude");
    }

    @Test
    @DisplayName("LOCAL_ONLY: LOCAL 만 후보, LOCAL 없으면 'unknown'")
    void localOnlyMode() {
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 1);
        var r = router(RoutingMode.COST_FIRST, normal);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("CircuitBreaker 가 LOCAL 을 차단하면 다음 ROLE(NORMAL) 로 폴백")
    void blockedLocalFallsThroughToNormal() {
        var local  = p("lm",     ProviderRole.LOCAL,  TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        breaker.block("lm", null);
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai");
    }

    @Test
    @DisplayName("hasLocalProvider — LOCAL 등록 + 차단 안 됨 → true")
    void hasLocalProvider() {
        var local  = p("lm",     ProviderRole.LOCAL,  TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        assertThat(r.hasLocalProvider()).isTrue();
        breaker.block("lm", null);
        assertThat(r.hasLocalProvider()).isFalse();
    }

    @Test
    @DisplayName("DUAL 모드: LOCAL 부재 시 executeDual 즉시 LlmProviderExhaustedException")
    void dualRequiresLocal() {
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 1);
        var r = router(RoutingMode.DUAL, normal);

        assertThatThrownBy(() -> r.executeDual(TaskType.TEXT, model -> null))
                .isInstanceOf(LlmProviderExhaustedException.class)
                .hasMessageContaining("LOCAL");
    }

    @Test
    @DisplayName("DUAL 모드: external 부재 시 executeDual 즉시 LlmProviderExhaustedException")
    void dualRequiresExternal() {
        var local = p("lm", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var r = router(RoutingMode.DUAL, local);

        assertThatThrownBy(() -> r.executeDual(TaskType.TEXT, model -> null))
                .isInstanceOf(LlmProviderExhaustedException.class)
                .hasMessageContaining("external");
    }

    @Test
    @DisplayName("executeWithTracking — mmproj 미지원 에러는 CircuitBreaker 를 차단하지 않음 (TEXT 작업은 계속 이용 가능)")
    void visionUnsupportedError_doesNotBlockCircuitBreaker() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(
                "500 - image input is not supported - hint: if this is unexpected, you may need to provide the mmproj"));
        var local = new LlmProvider("lm", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.VISION, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(breaker.isBlocked("lm")).isFalse();
        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("lm");
    }

    @Test
    @DisplayName("executeWithTracking — mmproj 미지원 확인 후에는 VISION/LIGHT_BOTH 요청을 재시도 없이 건너뜀")
    void visionUnsupportedError_skipsFutureImageTasksForThatProvider() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("mmproj file not found"));
        var local = new LlmProvider("lm", TaskType.BOTH, ProviderRole.LOCAL, 1, "k", null, null, true,
                chatModel, null);
        var r = router(RoutingMode.COST_FIRST, local);

        assertThatThrownBy(() -> r.executeWithTracking(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST,
                m -> m.call(new Prompt("x"))))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(r.findProviderName(TaskType.VISION, RoutingMode.COST_FIRST)).isEqualTo("unknown");
        assertThat(r.findProviderName(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("findFirst — apiKey 비어 있으면 후보에서 제외")
    void blankApiKeyExcluded() {
        var local  = new LlmProvider("lm",     TaskType.TEXT, ProviderRole.LOCAL,  1, "",   null, null, true, (ChatModel) null, null);
        var normal = new LlmProvider("openai", TaskType.TEXT, ProviderRole.NORMAL, 2, "sk", null, null, true, (ChatModel) null, null);
        var r = router(RoutingMode.COST_FIRST, local, normal);

        assertThat(r.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai");
    }
}
