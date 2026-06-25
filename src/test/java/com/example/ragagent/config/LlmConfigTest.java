package com.example.ragagent.config;

import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA — Phase 6 G1: LOCAL providers (local endpoints like llama-server) need no api-key.
 *
 * {@code LlmConfig.llmRouter()} drops cloud providers whose api-key is blank, but must KEEP
 * LOCAL-role providers and substitute a non-blank placeholder so they remain routable
 * (LlmRouter.findFirst filters by LlmProvider.hasValidApiKey()).
 */
class LlmConfigTest {

    private static final LlmUsageRepository USAGE = mock(LlmUsageRepository.class);

    private AppProperties propsWith(AppProperties.ProviderConfig... providers) {
        var llm = new AppProperties.LlmConfig(List.of(providers), 2, 10, 180, "COST_FIRST", 0.6);
        return new AppProperties(
                "./data", 2, 8000, 800, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null);
    }

    private AppProperties.ProviderConfig provider(String name, String role, String type, String apiKey) {
        return new AppProperties.ProviderConfig(
                name, "http://localhost:1234/v1", apiKey, "test-model", type, role, 0, true);
    }

    @Test
    @DisplayName("G1: api-key 없는 LOCAL 프로바이더도 등록되고 라우팅된다")
    void localProviderRegisteredWithoutApiKey() {
        AppProperties props = propsWith(provider("local", "LOCAL", "BOTH", ""));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2));

        assertThat(router.hasLocalProvider()).isTrue();
        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local");
    }

    @Test
    @DisplayName("G1: api-key 없는 NORMAL(클라우드) 프로바이더는 여전히 드롭된다")
    void cloudProviderStillDroppedWithoutApiKey() {
        AppProperties props = propsWith(provider("gemini", "NORMAL", "TEXT", ""));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2));

        assertThat(router.hasLocalProvider()).isFalse();
        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("G1: 비공백 api-key는 그대로 유지된다 (회귀 방지)")
    void explicitApiKeyPreserved() {
        AppProperties props = propsWith(provider("local", "LOCAL", "BOTH", "real-key"));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2));

        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local");
    }
}
