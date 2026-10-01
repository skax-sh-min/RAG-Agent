package com.example.ragagent.config;

import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingOffChatModel;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.repository.LlmUsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * "생각 끄기" 표시가 실제 요청 본문으로 어떻게 나가는가 — {@code LlmConfig} 가 만든 진짜 체인(데코레이터들 +
 * Spring AI {@code OpenAiChatModel} 의 직렬화)을 내장 {@link HttpServer} 에 대고 본다. 데코레이터 단위 테스트로는
 * 알 수 없는 것을 고정한다: Spring AI 가 호출부 옵션의 {@code extraBody} 를 본문 <b>최상위</b>로 내보내는가,
 * 프로바이더별 max-tokens 상한이 옵션을 복사할 때 그 표시를 잃지 않는가, 그리고 표시를 거부하는 서버가
 * 프로바이더 차단으로 번지지 않는가.
 */
class ThinkingOffWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** OpenAI 호환 채팅 엔드포인트 — 본문을 기록하고, {@code rejectSwitch} 면 표시가 실린 요청을 400 으로 거부한다. */
    private String startServer(boolean rejectSwitch) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            boolean reject = rejectSwitch && body.has(ThinkingOffChatModel.TEMPLATE_KWARGS);
            byte[] out = (reject
                    ? "{\"error\":\"Unrecognized request argument supplied: chat_template_kwargs\"}"
                    : "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"한 줄\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reject ? 400 : 200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 컨텍스트 창을 선언해 기동 시 탐지 요청이 이 서버로 가지 않게 한다(검증 테스트는 채팅 본문만 본다). */
    private static AppProperties.ProviderConfig provider(String role, String baseUrl, Integer maxTokens) {
        String apiKey = "LOCAL".equals(role) ? "" : "sk-test";
        return new AppProperties.ProviderConfig(
                "p1", baseUrl, apiKey, "m", "BOTH", role, 1, true, null, 8192, maxTokens);
    }

    private static LlmRouter router(AppProperties.ProviderConfig provider, CircuitBreaker breaker) {
        var llm = new AppProperties.LlmConfig(
                List.of(provider), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, false);
        AppProperties props = new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        return new LlmConfig().llmRouter(props, mock(LlmUsageRepository.class), breaker, new ProviderToggle(),
                new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration());
    }

    /** 생각을 끄고 부르는 호출부(독립화·질문 다듬기)와 같은 모양. */
    private static String callWithThinkingOff(LlmRouter router, int maxTokens) {
        return router.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.COST_FIRST, "test:",
                model -> model.call(new Prompt(List.of(new UserMessage("질문")),
                        ThinkingOffChatModel.requestOff(OpenAiChatOptions.builder().maxTokens(maxTokens)).build())));
    }

    @Test
    @DisplayName("LOCAL 프로바이더 — 본문 최상위에 chat_template_kwargs.enable_thinking=false 가 실린다")
    void localProviderSendsTheSwitchAtTopLevel() throws IOException {
        LlmRouter router = router(provider("LOCAL", startServer(false), null), new CircuitBreaker(2));

        assertThat(callWithThinkingOff(router, 256)).isEqualTo("한 줄");

        assertThat(requests).hasSize(1);
        JsonNode body = requests.get(0);
        assertThat(body.path(ThinkingOffChatModel.TEMPLATE_KWARGS).path("enable_thinking").isBoolean()).isTrue();
        assertThat(body.path(ThinkingOffChatModel.TEMPLATE_KWARGS).path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.has("extra_body")).as("중첩되지 않고 최상위로 펼쳐져야 서버가 읽는다").isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
    }

    @Test
    @DisplayName("원격(NORMAL) 프로바이더 — 표시가 본문에 없다(모르는 필드를 400 으로 거부하는 서버들)")
    void remoteProviderNeverSeesTheSwitch() throws IOException {
        LlmRouter router = router(provider("NORMAL", startServer(false), null), new CircuitBreaker(2));

        callWithThinkingOff(router, 256);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).has(ThinkingOffChatModel.TEMPLATE_KWARGS)).isFalse();
    }

    @Test
    @DisplayName("프로바이더별 max-tokens 상한이 옵션을 복사해 낮춰도 표시는 남는다")
    void providerMaxTokensCapKeepsTheSwitch() throws IOException {
        LlmRouter router = router(provider("LOCAL", startServer(false), 1000), new CircuitBreaker(2));

        callWithThinkingOff(router, 2048);

        JsonNode body = requests.get(0);
        assertThat(body.path("max_tokens").asInt()).as("상한이 걸렸다").isEqualTo(1000);
        assertThat(body.has(ThinkingOffChatModel.TEMPLATE_KWARGS)).as("복사본에도 표시가 있다").isTrue();
    }

    @Test
    @DisplayName("표시를 거부하는 LOCAL 서버 — 표시 없이 다시 보내 성공하고, 차단되지 않으며, 다음부터는 처음부터 싣지 않는다")
    void aServerRejectingTheSwitchIsNeitherBlockedNorAskedAgain() throws IOException {
        CircuitBreaker breaker = new CircuitBreaker(2);
        LlmRouter router = router(provider("LOCAL", startServer(true), null), breaker);

        assertThat(callWithThinkingOff(router, 256)).isEqualTo("한 줄");
        assertThat(callWithThinkingOff(router, 256)).isEqualTo("한 줄");

        assertThat(requests).extracting(body -> body.has(ThinkingOffChatModel.TEMPLATE_KWARGS))
                .containsExactly(true, false, false);
        assertThat(breaker.isBlocked("p1")).isFalse();
        assertThat(breaker.consecutiveFailures("p1")).isZero();
    }
}
