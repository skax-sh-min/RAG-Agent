package com.example.ragagent.config;

import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.repository.LlmUsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 생각 수준이 실제 요청 본문으로 어떻게 나가는가(PLAN §6.29) — {@code LlmConfig} 가 만든 진짜 체인(데코레이터들 +
 * Spring AI {@code OpenAiChatModel} 의 직렬화·역직렬화)을 내장 {@link HttpServer} 에 대고 본다. 데코레이터 단위
 * 테스트로는 알 수 없는 것을 고정한다: Spring AI 가 {@code extraBody} 를 본문 <b>최상위</b>로 내보내는가, 호출부의 표시
 * 키가 <b>본문에 절대 남지 않는가</b>, 프로바이더별 max-tokens 상한이 옵션을 복사해도 실은 값이 남는가, 거부하는 서버가
 * 프로바이더 차단으로 번지지 않는가, 표준 필드 {@code reasoning_effort} 가 그 이름으로 나가는가, 그리고 서버의
 * {@code reasoning_content} 가 관측까지 닿는가(Spring AI 가 메타데이터에 싣는 키 이름을 여기서 확인한다).
 */
@ResourceLock("global-state")   // 수준을 정적 오버라이드 계층을 거쳐 읽는다 — AppPropertiesOverrideTest 가 같은 키를 바꾼다
class ThinkingControlWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;

    private static final String OK_RESPONSE = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"한 줄\"},"
            + "\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}";

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /**
     * OpenAI 호환 채팅 엔드포인트 — 본문을 기록하고, {@code rejectSwitch} 면 템플릿 인자가 실린 요청을 400 으로
     * 거부한다. 그 외에는 {@code response} 를 돌려준다.
     */
    private String startServer(boolean rejectSwitch, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            boolean reject = rejectSwitch && body.has(KWARGS);
            byte[] out = (reject
                    ? "{\"error\":\"Unrecognized request argument supplied: chat_template_kwargs\"}"
                    : response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reject ? 400 : 200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private String startServer(boolean rejectSwitch) throws IOException {
        return startServer(rejectSwitch, OK_RESPONSE);
    }

    /** 컨텍스트 창을 선언해 기동 시 탐지 요청이 이 서버로 가지 않게 한다(이 테스트는 채팅 본문만 본다). */
    private static AppProperties.ProviderConfig provider(String role, String baseUrl, Integer maxTokens, String dialect) {
        String apiKey = "LOCAL".equals(role) ? "" : "sk-test";
        return new AppProperties.ProviderConfig(
                "p1", baseUrl, apiKey, "m", "BOTH", role, 1, true, null, 8192, maxTokens, dialect);
    }

    private static AppProperties.ProviderConfig provider(String role, String baseUrl, Integer maxTokens) {
        return provider(role, baseUrl, maxTokens, null);
    }

    private static AppProperties props(AppProperties.ProviderConfig provider, Map<String, String> thinking) {
        var llm = new AppProperties.LlmConfig(
                List.of(provider), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, 1, false, thinking);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static LlmRouter router(AppProperties props, CircuitBreaker breaker, ThinkingObservations observations) {
        return new LlmConfig().llmRouter(props, mock(LlmUsageRepository.class), breaker, new ProviderToggle(),
                new BackgroundLlmConcurrencyTracker(), new ProviderContextWindows(), new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), observations);
    }

    private static LlmRouter router(AppProperties.ProviderConfig provider, CircuitBreaker breaker) {
        return router(props(provider, Map.of()), breaker, new ThinkingObservations());
    }

    /** 사이트를 표시하고 부르는 호출부(독립화·질문 다듬기)와 같은 모양. */
    private static String callAt(LlmRouter router, ThinkingSite site, int maxTokens) {
        return router.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.COST_FIRST, "test:",
                model -> model.call(new Prompt(List.of(new UserMessage("질문")),
                        ThinkingControl.mark(OpenAiChatOptions.builder().maxTokens(maxTokens), site).build())));
    }

    @Test
    @DisplayName("LOCAL + 독립화(출하값 끔) — 본문 최상위에 enable_thinking=false 가 실리고, 표시 키는 본문 어디에도 없다")
    void localProviderSendsTheSwitchAtTopLevelAndNeverTheMarker() throws IOException {
        LlmRouter router = router(provider("LOCAL", startServer(false), null), new CircuitBreaker(2));

        assertThat(callAt(router, ThinkingSite.CONDENSE, 256)).isEqualTo("한 줄");

        assertThat(requests).hasSize(1);
        JsonNode body = requests.get(0);
        assertThat(body.path(KWARGS).path("enable_thinking").isBoolean()).isTrue();
        assertThat(body.path(KWARGS).path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.has("extra_body")).as("중첩되지 않고 최상위로 펼쳐져야 서버가 읽는다").isFalse();
        assertThat(body.toString()).as("표시 키는 서버로 나가지 않는다").doesNotContain(ThinkingControl.SITE_MARKER);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
    }

    @Test
    @DisplayName("설정의 수준을 따른다 — app.llm.thinking.condense=low 면 enable_thinking=true")
    void configuredLevelIsSent() throws IOException {
        AppProperties props = props(provider("LOCAL", startServer(false), null), Map.of("condense", "low"));
        LlmRouter router = router(props, new CircuitBreaker(2), new ThinkingObservations());

        callAt(router, ThinkingSite.CONDENSE, 256);

        assertThat(requests.get(0).path(KWARGS).path("enable_thinking").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("원격(NORMAL, auto) — 템플릿 인자도 표시 키도 본문에 없다(모르는 필드를 400 으로 거부하는 서버들)")
    void remoteProviderNeverSeesTheSwitch() throws IOException {
        LlmRouter router = router(provider("NORMAL", startServer(false), null), new CircuitBreaker(2));

        callAt(router, ThinkingSite.CONDENSE, 256);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).has(KWARGS)).isFalse();
        assertThat(requests.get(0).toString()).doesNotContain(ThinkingControl.SITE_MARKER);
    }

    @Test
    @DisplayName("openai-effort 를 지정한 프로바이더 — 표준 필드 reasoning_effort 가 그 이름으로 최상위에 나간다")
    void openAiEffortIsSerializedAsTheStandardField() throws IOException {
        AppProperties props = props(provider("NORMAL", startServer(false), null, "openai-effort"),
                Map.of("post-answer", "medium"));
        LlmRouter router = router(props, new CircuitBreaker(2), new ThinkingObservations());

        callAt(router, ThinkingSite.POST_ANSWER, 256);

        JsonNode body = requests.get(0);
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(body.has(KWARGS)).isFalse();
    }

    @Test
    @DisplayName("표시가 없는 호출은 지금처럼 나간다 — 아직 배선하지 않은 사이트는 아무것도 싣지 않는다")
    void unmarkedCallsSendNothing() throws IOException {
        LlmRouter router = router(provider("LOCAL", startServer(false), null), new CircuitBreaker(2));

        router.executeWithTracking(TaskType.MICRO_TEXT, RoutingMode.COST_FIRST, "test:",
                model -> model.call(new Prompt(List.of(new UserMessage("질문")),
                        OpenAiChatOptions.builder().maxTokens(256).build())));

        assertThat(requests.get(0).has(KWARGS)).isFalse();
    }

    @Test
    @DisplayName("프로바이더별 max-tokens 상한이 옵션을 복사해 낮춰도 실은 값은 남는다")
    void providerMaxTokensCapKeepsTheSwitch() throws IOException {
        LlmRouter router = router(provider("LOCAL", startServer(false), 1000), new CircuitBreaker(2));

        callAt(router, ThinkingSite.CONDENSE, 2048);

        JsonNode body = requests.get(0);
        assertThat(body.path("max_tokens").asInt()).as("상한이 걸렸다").isEqualTo(1000);
        assertThat(body.has(KWARGS)).as("복사본에도 실은 값이 있다").isTrue();
    }

    @Test
    @DisplayName("필드를 거부하는 LOCAL 서버 — 빼고 다시 보내 성공하고, 차단되지 않으며, 다음부터는 처음부터 싣지 않는다")
    void aServerRejectingTheSwitchIsNeitherBlockedNorAskedAgain() throws IOException {
        CircuitBreaker breaker = new CircuitBreaker(2);
        LlmRouter router = router(provider("LOCAL", startServer(true), null), breaker);

        assertThat(callAt(router, ThinkingSite.CONDENSE, 256)).isEqualTo("한 줄");
        assertThat(callAt(router, ThinkingSite.CONDENSE, 256)).isEqualTo("한 줄");

        assertThat(requests).extracting(body -> body.has(KWARGS)).containsExactly(true, false, false);
        assertThat(requests).allSatisfy(body ->
                assertThat(body.toString()).doesNotContain(ThinkingControl.SITE_MARKER));
        assertThat(breaker.isBlocked("p1")).isFalse();
        assertThat(breaker.consecutiveFailures("p1")).isZero();
    }

    @Test
    @DisplayName("블로킹 응답 — Spring AI 가 reasoning_content 를 버려도, 출력 토큰 초과로 생각한 호출로 센다")
    void thinkingIsObservedFromTheOutputTokens() throws IOException {
        // 2026-10-02 llama.cpp(b10236) + gemma-4-E2B 실측 응답의 모양 — reasoning_tokens 는 보고하지 않는다. Spring AI
        // 1.1.8 은 블로킹 응답에서 reasoning_content 를 메타데이터에 싣지 않으므로(같은 날 확인), 관측이 기댈 것은 usage 다.
        String thinkingResponse = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"391\","
                + "\"reasoning_content\":\"Thinking Process: 17*23 = 391\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":227,\"total_tokens\":258}}";
        ThinkingObservations observations = new ThinkingObservations();
        AppProperties props = props(provider("LOCAL", startServer(false, thinkingResponse), null),
                Map.of("condense", "low"));
        LlmRouter router = router(props, new CircuitBreaker(2), observations);

        assertThat(callAt(router, ThinkingSite.CONDENSE, 600)).isEqualTo("391");

        assertThat(observations.samples(ThinkingSite.CONDENSE, "p1", ThinkingLevel.LOW)).singleElement()
                .satisfies(s -> {
                    assertThat(s.thinkingObserved()).as("답 '391' 에 출력 227 — 초과분이 생각이다").isTrue();
                    assertThat(s.outputTokens()).isEqualTo(227);
                    assertThat(s.thinkingTokens()).isEqualTo(227);
                    assertThat(s.thinkingEstimated()).isTrue();
                    assertThat(s.truncated()).isFalse();
                });
    }
}
