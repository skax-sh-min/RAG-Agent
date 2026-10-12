package com.example.ragagent.config;

import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.repository.LlmUsageRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * G3: startup-time {@code GET {base-url}/v1/models} verification for LOCAL-role providers
 * ({@code LlmConfig.verifyLocalModel()}). A real embedded {@link HttpServer} stands in for the
 * local LLM server so these tests exercise the actual HTTP round trip, not a mock.
 */
class LlmConfigVerificationTest {

    private static final LlmUsageRepository USAGE = mock(LlmUsageRepository.class);

    private HttpServer server;
    private final List<HttpServer> extraServers = new java.util.ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        extraServers.forEach(s -> s.stop(0));
    }

    /** 두 번째 서버 — {@code /v1/models} 가 주어진 상태·본문·형식으로 답한다. base URL(/v1 포함)을 돌려준다. */
    private String startExtraServer(String body, int statusCode, String contentType) throws IOException {
        HttpServer extra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        extra.createContext("/v1/models", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        extra.start();
        extraServers.add(extra);
        return "http://127.0.0.1:" + extra.getAddress().getPort() + "/v1";
    }

    /** Starts a fake OpenAI-compatible server serving {@code /v1/models}; returns its base URL (with /v1). */
    private String startModelsServer(String responseBody, int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private AppProperties.ProviderConfig localProvider(String baseUrl, String model) {
        return new AppProperties.ProviderConfig(
                "local", baseUrl, "", model, "BOTH", "LOCAL", 1, true, null, null, null);
    }

    private AppProperties propsWith(AppProperties.ProviderConfig provider, boolean verifyOnStartup) {
        return propsWith(List.of(provider), verifyOnStartup);
    }

    private AppProperties propsWith(List<AppProperties.ProviderConfig> providers, boolean verifyOnStartup) {
        var llm = new AppProperties.LlmConfig(
                providers, 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, verifyOnStartup);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("G3: model present in /v1/models — provider registers normally")
    void modelFound_registersProvider() throws IOException {
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"},{\"id\":\"other-model\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4-e4b"), true);

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations());

        assertThat(router.hasLocalProvider()).isTrue();
    }

    @Test
    @DisplayName("G3: configured name is part of exactly one server id — registers, and the provider carries the server's id")
    void partialModelName_resolvesToServerId() throws IOException {
        // LM Studio reports `google/gemma-4-e4b`; the operator wrote just `gemma-4-e4b`.
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"google/gemma-4-e4b\"},{\"id\":\"qwen3-8b\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4-e4b"), true);

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations());

        // The streaming paths put provider.model() straight into the request body, so this is the
        // value that must be the server's — not the shorthand from the env var.
        assertThat(router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST).model())
                .isEqualTo("google/gemma-4-e4b");
    }

    @Test
    @DisplayName("G3: configured name is part of several server ids — startup fails and names them")
    void ambiguousModelName_failsStartup() throws IOException {
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"google/gemma-4-e4b\"},{\"id\":\"google/gemma-4-e2b\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4"), true);

        assertThatThrownBy(() -> new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("google/gemma-4-e4b")
                .hasMessageContaining("google/gemma-4-e2b");
    }

    @Test
    @DisplayName("G3: an exact id keeps working even when another id contains it — no regression for precise names")
    void exactModelName_isNotAmbiguous() throws IOException {
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b-it\"},{\"id\":\"gemma-4-e4b\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4-e4b"), true);

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations());

        assertThat(router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST).model()).isEqualTo("gemma-4-e4b");
    }

    @Test
    @DisplayName("G3: model missing from /v1/models — startup fails")
    void modelMissing_failsStartup() throws IOException {
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"other-model\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4-e4b"), true);

        assertThatThrownBy(() -> new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gemma-4-e4b")
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("G3: server unreachable — startup fails")
    void serverUnreachable_failsStartup() {
        AppProperties props = propsWith(localProvider("http://127.0.0.1:1/v1", "gemma-4-e4b"), true);

        assertThatThrownBy(() -> new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    // ── 복제본(같은 type·priority 의 LOCAL 서버)이 있을 때 ─────────────────────

    private static final String UNREACHABLE = "http://127.0.0.1:1/v1";

    private AppProperties.ProviderConfig provider(String name, String baseUrl, String model, String type, int priority) {
        return new AppProperties.ProviderConfig(name, baseUrl, "", model, type, "LOCAL", priority, true, null, null, null);
    }

    private LlmRouter build(AppProperties props, CircuitBreaker breaker) {
        return new LlmConfig().llmRouter(props, USAGE, breaker, new ProviderToggle(), new BackgroundLlmConcurrencyTracker(),
                new ProviderContextWindows(), new TokenEstimateCalibration(), new ProviderThinkingDialects(),
                new ThinkingObservations());
    }

    @Test
    @DisplayName("G3: 복제본 하나가 닿지 않아도 다른 복제본이 검증을 통과하면 기동하고, 닿지 않은 쪽은 차단된 채 시작한다")
    void unreachableReplica_startsBlockedWhenPeerAnswers() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"google/gemma-4-e4b\"}]}", 200);
        CircuitBreaker breaker = new CircuitBreaker(2);
        AppProperties props = propsWith(List.of(
                provider("local", UNREACHABLE, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", live, "gemma-4-e4b", "BOTH", 1)), true);

        LlmRouter router = build(props, breaker);

        assertThat(breaker.isBlocked("local")).isTrue();
        assertThat(breaker.isBlocked("local-2")).isFalse();
        // 먼저 등록된 쪽이 죽어 있어도 첫 요청부터 살아 있는 쪽으로 간다(해석된 서버 id 와 함께).
        var routed = router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST);
        assertThat(routed.name()).isEqualTo("local-2");
        assertThat(routed.model()).isEqualTo("google/gemma-4-e4b");
    }

    @Test
    @DisplayName("G3: 복제본이 둘 다 닿지 않으면 예전처럼 기동을 멈춘다")
    void allReplicasUnreachable_failsStartup() {
        AppProperties props = propsWith(List.of(
                provider("local", UNREACHABLE, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", "http://127.0.0.1:2/v1", "gemma-4-e4b", "BOTH", 1)), true);

        assertThatThrownBy(() -> build(props, new CircuitBreaker(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("G3: priority 가 다른 LOCAL 서버는 복제본이 아니다 — 소형 모델 서버가 닿지 않으면 기동을 멈춘다")
    void unreachableNonReplica_failsStartupEvenIfAnotherLocalAnswers() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"}]}", 200);
        AppProperties props = propsWith(List.of(
                provider("local-fast", UNREACHABLE, "qwen-0.8b", "MICRO_TEXT", 0),
                provider("local", live, "gemma-4-e4b", "BOTH", 1)), true);

        assertThatThrownBy(() -> build(props, new CircuitBreaker(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-fast")
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("G3: 닿은 서버의 모델이 없으면 복제본 사정과 무관하게 기동을 멈춘다 — 장애가 아니라 설정 오류다")
    void reachableReplicaWithMissingModel_stillFailsStartup() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"other-model\"}]}", 200);
        AppProperties props = propsWith(List.of(
                provider("local", UNREACHABLE, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", live, "gemma-4-e4b", "BOTH", 1)), true);

        assertThatThrownBy(() -> build(props, new CircuitBreaker(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("G3: 복제본이 5xx 로 답하면(모델 로딩 중 등) 장애로 보고 차단한 채 기동한다")
    void replicaAnswering5xx_startsBlocked() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"}]}", 200);
        String loading = startExtraServer("{\"error\":{\"code\":503,\"message\":\"Loading model\"}}", 503, "application/json");
        CircuitBreaker breaker = new CircuitBreaker(2);
        AppProperties props = propsWith(List.of(
                provider("local", loading, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", live, "gemma-4-e4b", "BOTH", 1)), true);

        LlmRouter router = build(props, breaker);

        assertThat(breaker.isBlocked("local")).isTrue();
        assertThat(router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST).name()).isEqualTo("local-2");
    }

    @Test
    @DisplayName("G3: 복제본이 4xx 로 답하면(경로·인증 오류) 설정 오류다 — 다른 복제본이 살아 있어도 기동을 멈춘다")
    void replicaAnswering4xx_failsStartupEvenIfPeerAnswers() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"}]}", 200);
        String wrongPath = startExtraServer("{\"error\":\"not found\"}", 404, "application/json");
        AppProperties props = propsWith(List.of(
                provider("local", wrongPath, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", live, "gemma-4-e4b", "BOTH", 1)), true);

        assertThatThrownBy(() -> build(props, new CircuitBreaker(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[local]")
                .hasMessageContaining("without a model list");
    }

    @Test
    @DisplayName("G3: 모델 목록이 아닌 응답(HTML 등 OpenAI 호환이 아닌 서버)도 설정 오류다 — 기동을 멈춘다")
    void replicaAnsweringNonJson_failsStartupEvenIfPeerAnswers() throws IOException {
        String live = startModelsServer("{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"}]}", 200);
        String otherService = startExtraServer("<html><body>dashboard</body></html>", 200, "text/html");
        AppProperties props = propsWith(List.of(
                provider("local", otherService, "gemma-4-e4b", "BOTH", 1),
                provider("local-2", live, "gemma-4-e4b", "BOTH", 1)), true);

        assertThatThrownBy(() -> build(props, new CircuitBreaker(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a model list");
    }

    @Test
    @DisplayName("G3: verify-local-models-on-startup=false skips the check entirely")
    void verificationDisabled_skipsCheckEvenOnUnreachableServer() {
        AppProperties props = propsWith(localProvider("http://127.0.0.1:1/v1", "gemma-4-e4b"), false);

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle(), new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations());

        assertThat(router.hasLocalProvider()).isTrue();
    }
}
