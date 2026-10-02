package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ResponseMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채팅 답변 스트리밍이 <b>실제 Spring AI {@code OpenAiApi}(WebClient)</b>를 지날 때의 모양 — 내장 {@link HttpServer} 가
 * llama.cpp 처럼 SSE 로 생각 델타와 답 델타를 흘리거나 HTTP 오류를 돌려준다. 목으로는 알 수 없는 것을 고정한다:
 * <ul>
 *   <li>생각 필드가 실제 본문 <b>최상위</b>로 나가고 표시 키가 없는가 — {@code extraBody} 의 {@code @JsonAnyGetter}</li>
 *   <li>서버의 {@code reasoning_content} 델타가 Spring AI 의 청크 병합을 지나 여기까지 닿는가</li>
 *   <li>WebClient 오류의 메시지에는 본문이 없다 — 그래도 거부·컨텍스트 초과를 알아보는가(2026-10-02 이전에는 둘 다 못
 *       알아봤다: 축소 재시도가 스트리밍 경로에서 한 번도 발동하지 않았다)</li>
 * </ul>
 * 청크 JSON 은 2026-10-02 llama.cpp(b10236) + gemma-4-E2B 의 실제 응답 모양을 줄인 것이다.
 */
class AnswerStreamerWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KWARGS = ThinkingDialect.TEMPLATE_KWARGS_FIELD;
    private static final String OVERFLOW_BODY = "{\"error\":{\"code\":400,\"message\":\"request (40016 tokens) exceeds the"
            + " available context size (32768 tokens), try increasing it\",\"type\":\"exceed_context_size_error\","
            + "\"n_prompt_tokens\":40016,\"n_ctx\":32768}}";
    private static final AnswerStreamer.Trace TRACE = new AnswerStreamer.Trace(
            LoggerFactory.getLogger(AnswerStreamerWireTest.class), "[Wire]", "t1", RoutingMode.COST_FIRST);

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private interface Responder {
        void respond(HttpExchange exchange, JsonNode body, int index) throws IOException;
    }

    /** OpenAI 호환 채팅 엔드포인트 — 본문을 기록하고 {@code responder} 가 답한다. 반환값은 프로바이더의 base-url. */
    private String startServer(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            responder.respond(exchange, body, requests.size() - 1);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static String delta(String field, String value) {
        return "{\"choices\":[{\"finish_reason\":null,\"index\":0,\"delta\":{\"" + field + "\":\"" + value + "\"}}],"
                + "\"created\":1,\"id\":\"c1\",\"model\":\"m\",\"object\":\"chat.completion.chunk\"}";
    }

    private static final String ROLE_CHUNK = "{\"choices\":[{\"finish_reason\":null,\"index\":0,\"delta\":"
            + "{\"role\":\"assistant\",\"content\":null}}],\"created\":1,\"id\":\"c1\",\"model\":\"m\","
            + "\"object\":\"chat.completion.chunk\"}";
    private static final String STOP_CHUNK = "{\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,\"delta\":{}}],"
            + "\"created\":1,\"id\":\"c1\",\"model\":\"m\",\"object\":\"chat.completion.chunk\","
            + "\"timings\":{\"prompt_n\":28,\"predicted_n\":7}}";

    /** 생각 델타 셋 → 답 델타 둘 → 종료 — llama.cpp 의 생각 켬 응답 모양. */
    private static final List<String> THINKING_ANSWER = List.of(ROLE_CHUNK,
            delta("reasoning_content", "Thinking"), delta("reasoning_content", " Process"),
            delta("reasoning_content", ": 2+3"),
            delta("content", "다섯"), delta("content", "입니다"), STOP_CHUNK);

    private static void sse(HttpExchange exchange, List<String> events) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            for (String event : events) os.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** {@code LlmConfig} 와 같은 모양 — base-url 의 {@code /v1} 을 떼고 넘긴다(OpenAiApi 가 붙인다). */
    private static LlmProvider localProvider(String baseUrl) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl.replaceAll("/v1$", "")).apiKey("no-key").build();
        return new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 1, "no-key", baseUrl, "m", true, null, api);
    }

    private static ProviderThinkingDialects localDialect() {
        ProviderThinkingDialects dialects = new ProviderThinkingDialects();
        dialects.record("local", ThinkingDialect.AUTO, true);
        return dialects;
    }

    @Test
    @DisplayName("생각 켬 — 본문 최상위에 enable_thinking=true, 생각 델타는 활동으로만, 답 델타만 토큰, 관측이 남는다")
    void thinkingStreamEndToEnd() throws IOException {
        LlmProvider provider = localProvider(startServer((ex, body, i) -> sse(ex, THINKING_ANSWER)));
        ThinkingObservations observations = new ThinkingObservations();
        List<String> tokens = new ArrayList<>();
        AtomicInteger thinking = new AtomicInteger();

        new AnswerStreamer(localDialect(), observations, site -> ThinkingLevel.LOW).stream(provider,
                ThinkingSite.ANSWER_RAG_N, "시스템", "질문", 0.0, tokens::add, thinking::incrementAndGet, TRACE);

        assertThat(tokens).containsExactly("다섯", "입니다");
        assertThat(thinking.get()).as("Spring AI 의 청크 병합을 지나서도 reasoning_content 가 닿는다").isEqualTo(3);

        JsonNode body = requests.get(0);
        assertThat(body.path(KWARGS).path("enable_thinking").asBoolean()).isTrue();
        assertThat(body.has("extra_body")).as("중첩되지 않고 최상위로 펼쳐져야 서버가 읽는다").isFalse();
        assertThat(body.toString()).doesNotContain(ThinkingControl.SITE_MARKER);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.has("max_tokens")).as("스트리밍 답변은 출력 상한을 보내지 않는다").isFalse();

        assertThat(observations.samples(ThinkingSite.ANSWER_RAG_N, "local", ThinkingLevel.LOW)).singleElement()
                .satisfies(s -> {
                    assertThat(s.thinkingObserved()).isTrue();
                    assertThat(s.thinkingTokens()).isEqualTo(3);
                    assertThat(s.outputTokens()).isEqualTo(5);
                    assertThat(s.truncated()).isFalse();
                });
    }

    @Test
    @DisplayName("필드를 거부하는 서버 — WebClient 오류 본문에서 거부를 알아보고, 빼고 다시 보내 성공한다")
    void aServerRejectingTheSwitchOverWebClient() throws IOException {
        LlmProvider provider = localProvider(startServer((ex, body, i) -> {
            if (body.has(KWARGS)) {
                json(ex, 400, "{\"error\":{\"message\":\"Unrecognized request argument supplied: chat_template_kwargs\","
                        + "\"type\":\"invalid_request_error\"}}");
            } else {
                sse(ex, THINKING_ANSWER);
            }
        }));
        ProviderThinkingDialects dialects = localDialect();
        List<String> tokens = new ArrayList<>();

        new AnswerStreamer(dialects, new ThinkingObservations(), site -> ThinkingLevel.OFF).stream(provider,
                ThinkingSite.ANSWER_DIRECT_N, "s", "u", 0.1, tokens::add, () -> { }, TRACE);

        assertThat(tokens).containsExactly("다섯", "입니다");
        assertThat(requests).extracting(b -> b.has(KWARGS)).containsExactly(true, false);
        assertThat(dialects.rejectedFields("local")).containsExactly(KWARGS);
    }

    @Test
    @DisplayName("컨텍스트 초과 — 스트리밍에서도 SSE 가 아니라 HTTP 400 으로 온다. 올라온 예외를 초과로 알아본다")
    void contextOverflowOverWebClientIsRecognized() throws IOException {
        LlmProvider provider = localProvider(startServer((ex, body, i) -> json(ex, 400, OVERFLOW_BODY)));

        assertThatThrownBy(() -> new AnswerStreamer(localDialect(), new ThinkingObservations(), site -> ThinkingLevel.LOW)
                .stream(provider, ThinkingSite.ANSWER_RAG_N, "s", "u", 0.0, t -> { }, () -> { }, TRACE))
                .isInstanceOf(WebClientResponseException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).as("전제 — 메시지에는 서버의 문장이 없다").doesNotContain("context");
                    assertThat(LlmRouter.isContextOverflow(e)).isTrue();
                });
        assertThat(requests).as("거부가 아니므로 다시 보내지 않는다").hasSize(1);
    }

    // ── AnswerService 의 축소 재시도(§6.26-9)가 이 경로에서 실제로 발동하는가 ─────────────────

    @Test
    @DisplayName("채팅 답변 스트리밍의 축소 재시도 — 실제 WebClient 초과 오류에서 문서를 덜어 다시 보낸다")
    void answerServiceShrinksOnARealStreamingOverflow() throws IOException {
        LlmProvider provider = localProvider(startServer((ex, body, i) -> {
            if (i == 0) json(ex, 400, OVERFLOW_BODY);
            else sse(ex, THINKING_ANSWER);
        }));
        LlmRouter router = mock(LlmRouter.class);
        when(router.routeProvider(any(), any())).thenReturn(provider);
        when(router.findProviderName(any(), any())).thenReturn("local");
        AppProperties props = mock(AppProperties.class);
        when(props.maxRetryCount()).thenReturn(2);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 8000, 1, true));
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        AnswerService service = new AnswerService(router, props, messages, new ProviderContextWindows(),
                new AnswerStreamer(localDialect(), new ThinkingObservations(), site -> ThinkingLevel.LOW),
                com.example.ragagent.llm.ThinkingBudget.none());
        List<Document> docs = new ArrayList<>();
        for (int n = 1; n <= 8; n++) docs.add(new Document("문서" + n + "-" + "가".repeat(200)));
        // S — 검증을 건너뛰는 모드라 답변 경로만 본다
        AgentState state = AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(docs).responseMode(ResponseMode.S).build();
        List<String> tokens = new ArrayList<>();
        AtomicInteger thinking = new AtomicInteger();

        AgentState result = service.executeStreaming(state, new GraphListener() {
            @Override public void onToken(String text) { tokens.add(text); }
            @Override public void onThinking() { thinking.incrementAndGet(); }
        });

        assertThat(requests).as("초과 1 + 덜어낸 재시도 1").hasSize(2);
        String retried = requests.get(1).path("messages").get(1).path("content").asText();
        assertThat(retried).contains("문서7-").doesNotContain("문서8-");
        assertThat(result.answer()).as("S 는 요약 헤딩을 보충한다(SummaryOnlyGuard)").endsWith("다섯입니다");
        assertThat(result.budgetNote()).isEqualTo("컨텍스트 한도로 검색된 문서 8개 중 7개만 사용했습니다.");
        assertThat(tokens).containsExactly("다섯", "입니다");
        assertThat(thinking.get()).as("RAG 답변 경로도 생각 델타를 리스너로 올린다").isEqualTo(3);
    }
}
