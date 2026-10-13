package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.repository.LlmUsageRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "{@code LOCAL_LLM_URL} 서버는 응답이 없고 {@code LOCAL_LLM_URL_2} 는 멀쩡하다" — 채팅 화면의 답변 스트리밍이 <b>진짜 라우터와 진짜
 * WebClient</b>를 지날 때의 모양. 내장 {@link HttpServer} 가 살아 있는 서버를, 닫힌 포트가 죽은 서버를 흉내 낸다.
 *
 * <p>목 라우터로는 알 수 없는 것을 고정한다: WebClient 가 연결 거부·HTTP 503·응답 도중 끊김에서 <b>실제로</b> 올리는 예외가
 * {@code LlmRouter.failOver} 의 판정에 맞는 모양인가, 그리고 첫 토큰이 나간 뒤에는 정말로 다시 열지 않는가.
 */
class StreamingFailoverWireTest {

    private static final String ROLE_CHUNK = "{\"choices\":[{\"finish_reason\":null,\"index\":0,\"delta\":"
            + "{\"role\":\"assistant\",\"content\":null}}],\"created\":1,\"id\":\"c1\",\"model\":\"m\","
            + "\"object\":\"chat.completion.chunk\"}";
    private static final String STOP_CHUNK = "{\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,\"delta\":{}}],"
            + "\"created\":1,\"id\":\"c1\",\"model\":\"m\",\"object\":\"chat.completion.chunk\"}";

    private static String content(String text) {
        return "{\"choices\":[{\"finish_reason\":null,\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}],"
                + "\"created\":1,\"id\":\"c1\",\"model\":\"m\",\"object\":\"chat.completion.chunk\"}";
    }

    private final List<HttpServer> servers = new ArrayList<>();
    private final AtomicInteger aliveRequests = new AtomicInteger();
    private final CircuitBreaker breaker = new CircuitBreaker(2);

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 이 서버의 base-url(@code http://127.0.0.1:port}). */
    private String start(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            handler.handle(exchange);
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 살아 있는 서버 — 요청 수를 세고 "다섯" "입니다" 를 스트리밍한다. */
    private String aliveServer() throws IOException {
        return start(exchange -> {
            aliveRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String event : List.of(ROLE_CHUNK, content("다섯"), content("입니다"), STOP_CHUNK)) {
                    os.write(("data: " + event + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    /** 아무도 듣지 않는 포트 — 연결 거부(프로세스가 내려간 서버). */
    private static String closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    private static LlmProvider provider(String name, String baseUrl) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey("no-key").build();
        return new LlmProvider(name, TaskType.TEXT, ProviderRole.LOCAL, 1, "no-key", baseUrl + "/v1", "m", true, null, api);
    }

    /** {@code local}(먼저 등록 — 한가하면 먼저 고른다)과 {@code local-2}. */
    private LlmRouter router(String localUrl, String local2Url) {
        return new LlmRouter(List.of(provider("local", localUrl), provider("local-2", local2Url)),
                mock(LlmUsageRepository.class), breaker, RoutingMode.LOCAL_ONLY, 180, Map.of(), 3, 2, new ProviderToggle());
    }

    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        when(props.maxRetryCount()).thenReturn(2);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "LOCAL_ONLY", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 8000, 1, true));
        return props;
    }

    private static MessageSource messages() {
        MessageSource messages = mock(MessageSource.class);
        when(messages.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        return messages;
    }

    private static AnswerStreamer streamer() {
        return new AnswerStreamer(new ProviderThinkingDialects(), new ThinkingObservations(), site -> ThinkingLevel.OFF);
    }

    private static AnswerService answerService(LlmRouter router) {
        return new AnswerService(router, props(), messages(), new ProviderContextWindows(), streamer(), ThinkingBudget.none());
    }

    private static DirectAnswerService directService(LlmRouter router) {
        return new DirectAnswerService(router, messages(), props(), null, streamer(), ThinkingBudget.none());
    }

    /** S — 검증을 건너뛰는 모드라 답변 경로만 본다. */
    private static AgentState ragState() {
        return AgentState.of("질문", "v1", "t1", "", RoutingMode.LOCAL_ONLY).toBuilder()
                .retrievedDocs(List.of(new Document("문서 본문입니다."))).responseMode(ResponseMode.S).build();
    }

    private static AgentState directState() {
        return AgentState.of("질문", "v1", "t1", "", "", RoutingMode.LOCAL_ONLY, true, Locale.KOREAN);
    }

    private static final class Collected implements GraphListener {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        @Override public void onToken(String text) { tokens.add(text); }
    }

    // ── 연결 거부(프로세스가 내려간 서버) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("RAG 답변 스트리밍 — local 이 연결을 거부하면 같은 요청이 local-2 로 넘어가 답이 나온다(예전에는 오류)")
    void ragAnswerFailsOverFromARefusedServer() throws IOException {
        LlmRouter router = router(closedPort(), aliveServer());
        Collected listener = new Collected();

        AgentState result = answerService(router).executeStreaming(ragState(), listener);

        assertThat(listener.tokens).containsExactly("다섯", "입니다");
        assertThat(result.usedProvider()).as("실제로 응답한 프로바이더").isEqualTo("local-2");
        assertThat(aliveRequests.get()).isEqualTo(1);
        assertThat(breaker.isBlocked("local")).as("죽은 서버는 막아 둔다 — 다음 질문이 같은 곳을 또 먼저 부르지 않는다").isTrue();
    }

    @Test
    @DisplayName("Direct 답변 스트리밍도 같다 — 분류 같은 앞단 블로킹 호출이 없어 예전에는 아무도 대신 막아 주지 못했다")
    void directAnswerFailsOverFromARefusedServer() throws IOException {
        LlmRouter router = router(closedPort(), aliveServer());
        Collected listener = new Collected();

        AgentState result = directService(router).executeStreaming(directState(), listener);

        assertThat(listener.tokens).containsExactly("다섯", "입니다");
        assertThat(result.answer()).isEqualTo("다섯입니다");
        assertThat(aliveRequests.get()).isEqualTo(1);
        assertThat(breaker.isBlocked("local")).isTrue();
    }

    @Test
    @DisplayName("둘째 질문은 막힌 서버를 건드리지 않고 곧장 local-2 로 간다")
    void theSecondQuestionGoesStraightToTheLiveServer() throws IOException {
        LlmRouter router = router(closedPort(), aliveServer());
        AnswerService service = answerService(router);
        service.executeStreaming(ragState(), new Collected());

        Collected second = new Collected();
        AgentState result = service.executeStreaming(ragState(), second);

        assertThat(second.tokens).containsExactly("다섯", "입니다");
        assertThat(result.usedProvider()).isEqualTo("local-2");
        assertThat(aliveRequests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("둘 다 죽었으면 날것의 연결 오류가 아니라 소진 안내(남은 시간 포함)가 올라간다")
    void bothDownEndsInTheOutageMessage() throws IOException {
        LlmRouter router = router(closedPort(), closedPort());

        assertThatThrownBy(() -> answerService(router).executeStreaming(ragState(), new Collected()))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(breaker.isBlocked("local")).isTrue();
        assertThat(breaker.isBlocked("local-2")).isTrue();
    }

    // ── HTTP 503 (모델 로딩 중) ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("local 이 503 + Retry-After 를 주면 그 시간만큼 막고 local-2 로 넘어간다 — WebClient 의 실제 예외로 확인")
    void anOverloadedServerIsBlockedForTheTimeItAsksFor() throws IOException {
        String loading = start(exchange -> {
            byte[] body = "{\"error\":{\"message\":\"Loading model\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Retry-After", "9");
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        LlmRouter router = router(loading, aliveServer());
        Collected listener = new Collected();

        AgentState result = answerService(router).executeStreaming(ragState(), listener);

        assertThat(listener.tokens).containsExactly("다섯", "입니다");
        assertThat(result.usedProvider()).isEqualTo("local-2");
        Instant until = breaker.getBlockedProviders().get("local");
        assertThat(until).isNotNull();
        assertThat(Duration.between(Instant.now(), until).toSeconds()).isBetween(6L, 9L);
    }

    // ── 첫 토큰 이후 ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 토큰이 나간 뒤 연결이 끊기면 다른 서버로 이어 붙이지 않는다 — 오류를 그대로 올리고 차단하지도 않는다")
    void aStreamCutAfterTheFirstTokenIsNotRetried() throws IOException {
        // Content-Length 를 크게 선언하고 토큰 하나만 쓴 채 닫는다 — 클라이언트에는 "응답 도중 연결 끊김"이다.
        String cutOff = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 100_000);
            OutputStream os = exchange.getResponseBody();
            os.write(("data: " + content("안") + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                Thread.sleep(150);   // 클라이언트가 첫 토큰을 읽을 시간
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        LlmRouter router = router(cutOff, aliveServer());
        Collected listener = new Collected();

        assertThatThrownBy(() -> answerService(router).executeStreaming(ragState(), listener));

        assertThat(listener.tokens).as("첫 토큰은 이미 화면에 나갔다").containsExactly("안");
        assertThat(aliveRequests.get()).as("local-2 로 다시 열지 않는다 — 화면에 앞부분이 두 번 찍힌다").isZero();
        assertThat(breaker.isBlocked("local")).as("토큰을 내던 서버를 죽었다고 단정하지 않는다").isFalse();
    }
}
