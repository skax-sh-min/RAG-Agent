package com.example.ragagent.config;

import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderToggle;
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

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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
                "local", baseUrl, "", model, "BOTH", "LOCAL", 1, true, null);
    }

    private AppProperties propsWith(AppProperties.ProviderConfig provider, boolean verifyOnStartup) {
        var llm = new AppProperties.LlmConfig(
                List.of(provider), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 6000, verifyOnStartup);
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

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle());

        assertThat(router.hasLocalProvider()).isTrue();
    }

    @Test
    @DisplayName("G3: model missing from /v1/models — startup fails")
    void modelMissing_failsStartup() throws IOException {
        String baseUrl = startModelsServer(
                "{\"object\":\"list\",\"data\":[{\"id\":\"other-model\"}]}", 200);
        AppProperties props = propsWith(localProvider(baseUrl, "gemma-4-e4b"), true);

        assertThatThrownBy(() -> new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gemma-4-e4b")
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("G3: server unreachable — startup fails")
    void serverUnreachable_failsStartup() {
        AppProperties props = propsWith(localProvider("http://127.0.0.1:1/v1", "gemma-4-e4b"), true);

        assertThatThrownBy(() -> new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("G3: verify-local-models-on-startup=false skips the check entirely")
    void verificationDisabled_skipsCheckEvenOnUnreachableServer() {
        AppProperties props = propsWith(localProvider("http://127.0.0.1:1/v1", "gemma-4-e4b"), false);

        LlmRouter router = new LlmConfig().llmRouter(props, USAGE, new CircuitBreaker(2), new ProviderToggle());

        assertThat(router.hasLocalProvider()).isTrue();
    }
}
