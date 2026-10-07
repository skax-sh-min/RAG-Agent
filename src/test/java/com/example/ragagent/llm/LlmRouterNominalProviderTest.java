package com.example.ragagent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmRouter#findNominalProviderName} — 서킷 브레이커의 차단을 <b>무시했을 때</b> 이 라우팅이 받을 프로바이더.
 * {@code /settings} 생각 수준 카드가 "local-main 차단 중 → 지금은 remote-x" 를 말하려면 지금의 답과 차단이 없었다면의 답이 둘 다
 * 필요하다(PLAN §6.29 ⑦-다). 지금의 답은 {@link LlmRouter#findProviderName} 이고, 운영자가 끈 것·키가 없는 것은 두 답 모두에서 빠진다.
 */
class LlmRouterNominalProviderTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker(2);
    }

    private static LlmProvider p(String name, ProviderRole role, TaskType type, int priority) {
        return new LlmProvider(name, type, role, priority, "k", null, null, true, (ChatModel) null, null);
    }

    @Test
    @DisplayName("차단 중인 1순위가 있어도 차단이 없었다면 그것이 받는다 — 지금의 답은 다음 프로바이더")
    void blockedFirstChoiceIsStillNominal() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var router = new LlmRouter(List.of(local, normal), null, breaker, RoutingMode.COST_FIRST);

        breaker.block("local", null);

        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("openai");
        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).contains("local");
    }

    @Test
    @DisplayName("차단이 없으면 두 답이 같다")
    void sameWhenNothingIsBlocked() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var router = new LlmRouter(List.of(local), null, breaker, RoutingMode.COST_FIRST);

        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.COST_FIRST))
                .contains(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST));
    }

    @Test
    @DisplayName("운영자가 끈 프로바이더는 차단과 달리 두 답에서 모두 빠진다")
    void disabledProviderIsNeverNominal() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var normal = p("openai", ProviderRole.NORMAL, TaskType.TEXT, 2);
        var toggle = new ProviderToggle();
        var router = new LlmRouter(List.of(local, normal), null, breaker, RoutingMode.COST_FIRST, 180,
                Map.of(), 3, 20, toggle);

        toggle.setEnabled("local", false);

        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).contains("openai");
    }

    @Test
    @DisplayName("라우팅 모드의 역할 순서를 따른다 — QUALITY_FIRST 는 PREMIUM 부터, LOCAL_ONLY 는 LOCAL 만")
    void followsTheRoleOrderOfTheMode() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var premium = p("claude", ProviderRole.PREMIUM, TaskType.TEXT, 3);
        var router = new LlmRouter(List.of(local, premium), null, breaker, RoutingMode.COST_FIRST);

        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.QUALITY_FIRST)).contains("claude");
        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.LOCAL_ONLY)).contains("local");
        assertThat(router.findNominalProviderName(TaskType.TEXT, RoutingMode.PROGRESSIVE)).contains("local");
    }

    @Test
    @DisplayName("받을 프로바이더가 하나도 없으면 비어 있다 — 'unknown' 문자열이 아니다")
    void emptyWhenNobodyCanTakeIt() {
        var local = p("local", ProviderRole.LOCAL, TaskType.TEXT, 1);
        var router = new LlmRouter(List.of(local), null, breaker, RoutingMode.LOCAL_ONLY);

        // VISION 은 다른 축이라 TEXT 단독 프로바이더가 흡수하지 않는다(LlmRouterTest 와 같은 전제)
        assertThat(router.findNominalProviderName(TaskType.VISION, RoutingMode.LOCAL_ONLY)).isEqualTo(Optional.empty());
    }
}
