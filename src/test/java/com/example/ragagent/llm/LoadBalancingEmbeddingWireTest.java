package com.example.ragagent.llm;

import com.example.ragagent.config.HttpClientTimeouts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 임베딩 장애 전환을 <b>진짜 클라이언트</b>로 — {@code OpenAiEmbeddingModel}(+ 운영과 같은 재시도·타임아웃 설정)이 실제로 던지는
 * 예외가 {@link LoadBalancingEmbeddingModel} 의 판정({@link LlmRouter#isConnectionFailure})에 걸리는가.
 * {@link LoadBalancingEmbeddingModelTest} 는 목이 던진 예외로 규칙을 고정하고, 이 클래스는 그 예외 모양이 실제와 같은지를 본다
 * (Spring AI 가 연결 실패를 다른 예외로 감싸기 시작하면 전환이 조용히 꺼진다 — {@code StreamingFailoverWireTest} 선례).
 */
class LoadBalancingEmbeddingWireTest {

    private static final String EMBEDDING_JSON = """
            {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.25,0.5,0.75]}],\
            "model":"m","usage":{"prompt_tokens":1,"total_tokens":1}}""";

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** {@code /v1/embeddings} 가 주어진 상태·본문으로 답하는 서버 — 받은 요청 수를 센다. 루트 URL(/v1 없음)을 돌려준다. */
    private String server(int status, String body, AtomicInteger hits) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/embeddings", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    /** {@code EmbeddingBeanConfig.buildRawModel()} 과 같은 조립 — 재시도 2회와 명시적 타임아웃(백오프만 테스트 속도를 위해 짧게). */
    private static EmbeddingModel rawModel(String apiBase) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(apiBase)
                .apiKey("no-key")
                .restClientBuilder(HttpClientTimeouts.restClientBuilder(2, 5))
                .build();
        RetryTemplate shortRetry = RetryTemplate.builder().maxAttempts(2).exponentialBackoff(50, 2.0, 100).build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model("m").build(), shortRetry);
    }

    @Test
    @DisplayName("연결 거부(서버 꺼짐) — 실제 클라이언트의 예외로도 다음 엔드포인트로 넘어가 답을 받는다")
    void connectionRefused_failsOverWithTheRealClient() throws IOException {
        AtomicInteger liveHits = new AtomicInteger();
        String live = server(200, EMBEDDING_JSON, liveHits);
        // 순서상 죽은 쪽이 먼저 불린다(둘 다 한가하면 round-robin 이 0번부터).
        var lb = new LoadBalancingEmbeddingModel(List.of(rawModel("http://127.0.0.1:1"), rawModel(live)),
                List.of("dead", "live"));

        assertThat(lb.embed("질문")).containsExactly(0.25f, 0.5f, 0.75f);
        assertThat(lb.embed("다음 질문")).containsExactly(0.25f, 0.5f, 0.75f);
        assertThat(liveHits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("서버가 5xx 로 거절한 요청(예: 배치보다 긴 입력)은 넘기지 않는다 — 다른 엔드포인트도 같은 이유로 거절한다")
    void serverError_isNotFailedOver() throws IOException {
        AtomicInteger firstHits = new AtomicInteger();
        AtomicInteger secondHits = new AtomicInteger();
        String tooLarge = server(500, "{\"error\":{\"code\":500,\"message\":\"input is too large to process\"}}", firstHits);
        String other = server(200, EMBEDDING_JSON, secondHits);
        var lb = new LoadBalancingEmbeddingModel(List.of(rawModel(tooLarge), rawModel(other)), List.of("a", "b"));

        assertThatThrownBy(() -> lb.embed("아주 긴 청크")).isInstanceOf(RuntimeException.class);
        assertThat(firstHits.get()).as("재시도 설정대로 같은 엔드포인트에만").isPositive();
        assertThat(secondHits.get()).as("다른 엔드포인트로 넘기지 않았다").isZero();
    }
}
