package com.example.ragagent.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨텍스트 창 탐지 — 실제 HTTP 로 두 서버의 응답 모양을 흉내 내 검사한다.
 *
 * <p>여기서 가장 중요한 것은 <b>부정 케이스</b>다: llama.cpp 의 {@code meta.n_ctx_train} 과 LM Studio 의
 * 최상위 {@code max_context_length} 는 둘 다 <b>모델 상한</b>이지 지금 로드된 값이 아니다. 그걸 집어
 * 쓰면 예산이 10배 이상 부풀어, 컨텍스트 초과를 막으려던 코드가 정확히 그 초과를 부른다. 그래서
 * "로드된 값이 없으면 상한으로 대신하지 않고 빈 값" 이 이 클래스의 계약이고, 아래 두 테스트가 그것을
 * 고정한다.
 */
class ContextWindowProbeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** 경로별 고정 응답을 내는 최소 서버. 없는 경로는 404 — 서버가 그 엔드포인트를 모르는 상황이다. */
    private String startServer(Map<String, String> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> table = new HashMap<>(routes);
        server.createContext("/", exchange -> {
            String body = table.get(exchange.getRequestURI().getPath());
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Optional<Integer> probe(String base, String model) {
        return ContextWindowProbe.probe(base, model, 2, 2);
    }

    @Test
    @DisplayName("llama.cpp — /props 의 default_generation_settings.n_ctx 를 읽는다 (런타임 값)")
    void readsLlamaCppProps() throws IOException {
        String base = startServer(Map.of(
                "/props", "{\"default_generation_settings\":{\"n_ctx\":8192},\"total_slots\":1}"));

        assertThat(probe(base, "any-model")).contains(8192);
    }

    @Test
    @DisplayName("llama.cpp — /v1/models 의 n_ctx_train(학습 최대치)은 절대 쓰지 않는다")
    void neverUsesTrainedContextAsRuntimeValue() throws IOException {
        // 서버가 -c 8192 로 떠 있어도 이 필드는 131072 다. 이걸 읽으면 예산이 16배 틀린다.
        String base = startServer(Map.of(
                "/v1/models", "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":131072}}]}"));

        assertThat(probe(base, "m")).isEmpty();
    }

    @Test
    @DisplayName("LM Studio — loaded_instances 안의 컨텍스트를 읽는다")
    void readsLmStudioLoadedInstance() throws IOException {
        String base = startServer(Map.of(
                "/api/v0/models", """
                        {"data":[
                          {"id":"other","max_context_length":131072,"loaded_instances":[]},
                          {"id":"m","max_context_length":131072,
                           "loaded_instances":[{"instance_id":"m:1","config":{"context_length":16384}}]}
                        ]}"""));

        assertThat(probe(base, "m")).contains(16384);
    }

    @Test
    @DisplayName("LM Studio — 로드된 인스턴스가 없으면 최상위 max_context_length(모델 상한)로 대신하지 않는다")
    void neverFallsBackToModelMaximum() throws IOException {
        String base = startServer(Map.of(
                "/api/v0/models",
                "{\"data\":[{\"id\":\"m\",\"state\":\"not-loaded\",\"max_context_length\":131072}]}"));

        assertThat(probe(base, "m"))
                .as("모델 상한을 런타임 값으로 착각하면 컨텍스트 초과를 막으려다 되레 부른다")
                .isEmpty();
    }

    @Test
    @DisplayName("두 엔드포인트를 다 모르는 서버여도 예외 없이 빈 값 — 기동을 막지 않는다")
    void unknownServerYieldsEmptyWithoutThrowing() throws IOException {
        String base = startServer(Map.of("/v1/chat/completions", "{}"));

        assertThat(probe(base, "m")).isEmpty();
    }

    @Test
    @DisplayName("서버가 아예 없어도 예외를 던지지 않는다")
    void unreachableServerYieldsEmpty() {
        assertThat(ContextWindowProbe.probe("http://127.0.0.1:1", "m", 1, 1)).isEmpty();
    }

    @Test
    @DisplayName("base-url 에 /v1 이 붙어 있어도 서버 루트로 정규화해 찾는다")
    void stripsV1SuffixFromBaseUrl() throws IOException {
        String base = startServer(Map.of(
                "/props", "{\"default_generation_settings\":{\"n_ctx\":4096}}"));

        // 이 앱의 프로바이더 base-url 은 관례상 .../v1 로 끝나는데 /props 는 그 바깥에 있다.
        assertThat(probe(base + "/v1", "m")).contains(4096);
    }
}
