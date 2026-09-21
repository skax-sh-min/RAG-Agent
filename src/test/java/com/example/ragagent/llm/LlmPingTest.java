package com.example.ragagent.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "살아 있는가"의 세 답(닿는가 / 로드됐는가 / 추론이 되는가)이 따로 나오는지 고정한다 — 특히
 * 2026-09-21 의 GPU 소실처럼 앞 둘은 통과하고 셋째만 실패하는 경우.
 */
class LlmPingTest {

    private static final String MODELS = "{\"data\":[{\"id\":\"gemma-4-e2b\",\"object\":\"model\"}]}";
    private static final String LM_STUDIO_LOADED =
            "{\"data\":[{\"id\":\"gemma-4-e2b\",\"state\":\"loaded\",\"max_context_length\":32768}]}";
    private static final String COMPLETION =
            "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"}}]}";

    /** 경로 → (status, content-type, body). 없는 경로는 404 JSON. */
    private record Route(int status, String contentType, String body) {
        static Route json(String body) { return new Route(200, "application/json", body); }
    }

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String start(Map<String, Route> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Route> table = new HashMap<>(routes);
        server.createContext("/", exchange -> {
            Route r = table.get(exchange.getRequestURI().getPath());
            if (r == null) r = new Route(404, "application/json", "{\"error\":\"Unexpected endpoint\"}");
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.contentType());
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static LlmProvider provider(String baseUrl) {
        return new LlmProvider("local", TaskType.BOTH, ProviderRole.LOCAL, 1, "", baseUrl + "/v1",
                "gemma-4-e2b", true, (ChatModel) null, null);
    }

    private static LlmPing.Result ping(String base, boolean deep, int blocked) {
        return LlmPing.probe(provider(base), deep, 2, 2, 5, blocked, true);
    }

    @Test
    @DisplayName("LM Studio 가 정상이면 — 닿고, 로드됐고, deep 이면 1토큰 추론까지 ok")
    void healthyLmStudio() throws IOException {
        String base = start(Map.of(
                "/v1/models", Route.json(MODELS),
                "/api/v0/models", Route.json(LM_STUDIO_LOADED),
                "/v1/chat/completions", Route.json(COMPLETION)));

        LlmPing.Result shallow = ping(base, false, 0);
        assertThat(shallow.ok()).isTrue();
        assertThat(shallow.reachable()).isTrue();
        assertThat(shallow.modelListed()).isTrue();
        assertThat(shallow.modelState()).isEqualTo("loaded");
        assertThat(shallow.inference()).isNull();           // 안 물었다
        assertThat(shallow.latencyMs()).isNotNull();
        assertThat(shallow.baseUrl()).isEqualTo(base + "/v1");
        assertThat(shallow.error()).isNull();

        LlmPing.Result deep = ping(base, true, 0);
        assertThat(deep.ok()).isTrue();
        assertThat(deep.inference().ok()).isTrue();
        assertThat(deep.inference().latencyMs()).isNotNull();
    }

    /**
     * 그날의 로그 그대로 — 서버는 살아 있고 모델은 "로드됨"인데 decode 가 GPU 소실로 500 을 낸다.
     * 얕은 핑은 통과하고, 깊은 핑만 서버가 준 사유와 함께 실패해야 한다.
     */
    @Test
    @DisplayName("엔진만 죽은 서버(GPU 소실) — 얕은 핑은 통과, deep 만 서버의 사유와 함께 실패")
    void deadEngineBehindALiveServer() throws IOException {
        String base = start(Map.of(
                "/v1/models", Route.json(MODELS),
                "/api/v0/models", Route.json(LM_STUDIO_LOADED),
                "/v1/chat/completions", new Route(500, "application/json",
                        "{\"error\":{\"code\":500,\"message\":\"decode() failed: vk::Queue::submit: ErrorDeviceLost\",\"type\":\"server_error\"}}")));

        assertThat(ping(base, false, 0).ok()).isTrue();

        LlmPing.Result deep = ping(base, true, 0);
        assertThat(deep.reachable()).isTrue();
        assertThat(deep.modelState()).isEqualTo("loaded");
        assertThat(deep.ok()).isFalse();
        assertThat(deep.inference().ok()).isFalse();
        assertThat(deep.inference().error()).contains("HTTP 500").contains("ErrorDeviceLost");
    }

    @Test
    @DisplayName("JSON 이 아닌 본문(application/octet-stream)도 추론 실패로 읽는다 — 그날의 두 번째 증상")
    void nonJsonCompletionBody() throws IOException {
        String base = start(Map.of(
                "/v1/models", Route.json(MODELS),
                "/v1/chat/completions", new Route(200, "application/octet-stream", "")));

        LlmPing.Result deep = ping(base, true, 0);
        assertThat(deep.reachable()).isTrue();
        assertThat(deep.inference().ok()).isFalse();
        assertThat(deep.inference().error()).startsWith("chat/completions:");
    }

    @Test
    @DisplayName("서버가 내려가 있으면 — reachable=false, deep 은 묻지 않고 skipped")
    void serverDown() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            freePort = s.getLocalPort();
        }
        LlmPing.Result r = ping("http://127.0.0.1:" + freePort, true, 4);

        assertThat(r.ok()).isFalse();
        assertThat(r.reachable()).isFalse();
        assertThat(r.modelListed()).isNull();
        assertThat(r.modelState()).isEqualTo("unknown");
        assertThat(r.error()).startsWith("models:");
        assertThat(r.inference().ok()).isFalse();
        assertThat(r.inference().error()).contains("skipped");
        assertThat(r.circuitBlockedSeconds()).isEqualTo(4);
    }

    @Test
    @DisplayName("llama.cpp — /api/v0/models 가 없으면 /health 의 status 를 상태로 쓴다")
    void llamaCppHealth() throws IOException {
        String base = start(Map.of(
                "/v1/models", Route.json(MODELS),
                "/health", Route.json("{\"status\":\"ok\"}")));

        LlmPing.Result r = ping(base, false, 0);
        assertThat(r.ok()).isTrue();
        assertThat(r.modelState()).isEqualTo("ok");
    }

    @Test
    @DisplayName("설정된 모델이 목록에 없으면 — 닿아도 ok 가 아니다 (modelListed=false, state=not-listed)")
    void modelNotListed() throws IOException {
        String base = start(Map.of(
                "/v1/models", Route.json("{\"data\":[{\"id\":\"other-model\"}]}"),
                "/api/v0/models", Route.json("{\"data\":[{\"id\":\"other-model\",\"state\":\"loaded\"}]}")));

        LlmPing.Result r = ping(base, false, 0);
        assertThat(r.reachable()).isTrue();
        assertThat(r.modelListed()).isFalse();
        assertThat(r.modelState()).isEqualTo("not-listed");
        assertThat(r.ok()).isFalse();
    }

    @Test
    @DisplayName("상태를 아는 경로가 하나도 없으면 unknown — 추측하지 않는다")
    void unknownStateWhenNoServerSpecificEndpoint() throws IOException {
        String base = start(Map.of("/v1/models", Route.json(MODELS)));

        LlmPing.Result r = ping(base, false, 0);
        assertThat(r.ok()).isTrue();
        assertThat(r.modelState()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("base-url 은 요청한 쪽이 정한다 — 내부 호스트는 관리자 응답에만")
    void baseUrlOnlyWhenRequested() throws IOException {
        String base = start(Map.of("/v1/models", Route.json(MODELS)));

        assertThat(LlmPing.probe(provider(base), false, 2, 2, 5, 0, false).baseUrl()).isNull();
    }

    @Test
    @DisplayName("오류 문구는 상한이 있다 — HTML 오류 페이지를 통째로 싣지 않는다")
    void errorTextIsBounded() throws IOException {
        String base = start(Map.of(
                "/v1/models", new Route(502, "text/html", "<html>" + "x".repeat(2000) + "</html>")));

        LlmPing.Result r = ping(base, false, 0);
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).startsWith("models: HTTP 502");
        assertThat(r.error().length()).isLessThanOrEqualTo("models: ".length() + LlmPing.MAX_ERROR_LEN + 1);
    }
}
