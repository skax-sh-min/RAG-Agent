package com.example.ragagent.config;

import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 키리스 LOCAL 프로바이더(G1) + 폐쇄망 라우팅(G5) 단위 테스트.
 *
 * <p>G1: {@code LlmConfig.llmRouter()}는 api-key가 빈 클라우드 프로바이더를 드롭하되,
 * LOCAL role 프로바이더는 유지하고 플레이스홀더로 대체해 라우팅 가능 상태를 보장한다
 * ({@code LlmRouter.findFirst}는 {@code LlmProvider.hasValidApiKey()}로 필터링).
 * G5: 모든 외부 키가 비어 있으면 어떤 라우팅 모드에서도 외부 프로바이더가 선택되지 않는다
 * (라우팅 계층의 "외부 호출 없음" 보장).
 */
class LlmConfigTest {

    private static final LlmUsageRepository USAGE = mock(LlmUsageRepository.class);

    private AppProperties propsWith(AppProperties.ProviderConfig... providers) {
        return propsWith("COST_FIRST", providers);
    }

    private AppProperties propsWith(String routingMode, AppProperties.ProviderConfig... providers) {
        // verifyLocalModelsOnStartup=false — these tests use fake/unreachable local URLs and must
        // not make real HTTP calls (see LlmConfigVerificationTest for the model-verification behavior).
        var llm = new AppProperties.LlmConfig(List.of(providers), 2, 10, 180, routingMode, 0.6, 3, 20, 0.0, 0.1, 6000, false);
        return new AppProperties(
            "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private AppProperties.ProviderConfig provider(String name, String role, String type, String apiKey) {
        return new AppProperties.ProviderConfig(
                name, "http://localhost:1234/v1", apiKey, "test-model", type, role, 0, true, null);
    }

    @Test
    @DisplayName("G1: api-key 없는 LOCAL 프로바이더도 등록되고 라우팅된다")
    void localProviderRegisteredWithoutApiKey() {
        AppProperties props = propsWith(provider("local", "LOCAL", "BOTH", ""));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker());

        assertThat(router.hasLocalProvider()).isTrue();
        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local");
    }

    @Test
    @DisplayName("G1: api-key 없는 NORMAL(클라우드) 프로바이더는 여전히 드롭된다")
    void cloudProviderStillDroppedWithoutApiKey() {
        AppProperties props = propsWith(provider("gemini", "NORMAL", "TEXT", ""));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker());

        assertThat(router.hasLocalProvider()).isFalse();
        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("G1: 비공백 api-key는 그대로 유지된다 (회귀 방지)")
    void explicitApiKeyPreserved() {
        AppProperties props = propsWith(provider("local", "LOCAL", "BOTH", "real-key"));

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker());

        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.COST_FIRST)).isEqualTo("local");
    }

    @Test
    @DisplayName("G5: 폐쇄망 — 외부 키 전부 비우면 어떤 라우팅 모드에서도 외부 provider가 선택되지 않는다")
    void airGappedNeverRoutesToExternal() {
        AppProperties props = propsWith("LOCAL_ONLY",
                provider("local",  "LOCAL",   "BOTH", ""),   // 키리스 로컬 (G1) → 등록됨
                provider("gemini", "NORMAL",  "TEXT", ""),   // 빈 클라우드 키 → 드롭
                provider("openai", "PREMIUM", "TEXT", ""));  // 빈 클라우드 키 → 드롭

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker());

        assertThat(router.hasLocalProvider()).isTrue();
        // 외부 provider는 애초에 등록되지 않으므로 어떤 라우팅 모드에서도 선택될 수 없다.
        for (RoutingMode mode : RoutingMode.values()) {
            assertThat(router.findProviderName(TaskType.TEXT, mode))
                    .as("routing mode %s must never select an external provider", mode)
                    .isIn("local", "unknown");
        }
        assertThat(router.findProviderName(TaskType.TEXT, RoutingMode.LOCAL_ONLY)).isEqualTo("local");
    }
}
