package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.CrossEncoderReranker.Ranked;
import com.example.ragagent.service.CrossEncoderReranker.UnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 교차 인코더 리랭커 — 진짜 {@code RestClient} 로 내장 HTTP 서버에 대고 요청 모양, 점수순 정렬, 실패 분류와 건너뛰기를 본다.
 * 서버의 응답 모양({@code results[{index, relevance_score}]})은 2026-11-06 {@code llama-server --reranking} 에서 확인한 것이다.
 */
class CrossEncoderRerankerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** {@code status} 와 {@code response} 를 돌려주는 {@code /v1/rerank} 서버. 요청 본문과 경로를 기록한다. */
    private String start(int status, String response) throws IOException {
        return startOn(0, status, response);
    }

    private String startOn(int port, int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AppProperties props(String baseUrl) {
        var reranker = new AppProperties.RerankerConfig(baseUrl, "bge-reranker-v2-m3", 2, 5, 2);
        return new AppProperties("./data", 2, 800, 100, 100, 10, 0.0, true, 0, false, true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, reranker);
    }

    private static Document doc(String id, String text) {
        return Document.builder().id(id).text(text).metadata(java.util.Map.of("doc_id", id)).build();
    }

    private static String results(String... indexScore) {
        StringBuilder sb = new StringBuilder("{\"model\":\"bge-reranker-v2-m3\",\"object\":\"list\",\"usage\":{\"total_tokens\":1},\"results\":[");
        for (int i = 0; i < indexScore.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(indexScore[i]).append(",\"relevance_score\":").append(indexScore[i + 1]).append('}');
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("점수 내림차순으로 줄 세워 상위 topK 를 점수와 함께 돌려준다 — 서버가 입력 순서로 줘도")
    void ranksByScoreAndCutsToTopK() throws IOException {
        // 서버는 index 순으로 준다(실제 llama-server 가 그랬다) — 정렬은 우리 몫이다
        String url = start(200, results("0", "-6.7", "1", "3.2", "2", "0.4"));
        var reranker = new CrossEncoderReranker(props(url));
        List<Document> candidates = List.of(doc("a", "무관한 문서"), doc("b", "정답 문서"), doc("c", "곁다리 문서"));

        List<Ranked> out = reranker.rerank("정답이 어디 있나", candidates, 2);

        assertThat(out).extracting(r -> r.doc().getId()).containsExactly("b", "c");
        assertThat(out).extracting(Ranked::score).containsExactly(3.2, 0.4);
    }

    @Test
    @DisplayName("요청 본문 — model · query · 입력 순서의 documents · top_n(전체), 경로는 /v1/rerank")
    void requestShape() throws IOException {
        String url = start(200, results("0", "1.0", "1", "0.5"));
        var reranker = new CrossEncoderReranker(props(url));

        reranker.rerank("  질문입니다  ", List.of(doc("a", "첫째"), doc("b", "둘째")), 1);

        assertThat(paths).containsExactly("/v1/rerank");
        JsonNode body = requests.get(0);
        assertThat(body.path("model").asText()).isEqualTo("bge-reranker-v2-m3");
        assertThat(body.path("query").asText()).isEqualTo("질문입니다");
        assertThat(body.path("documents")).extracting(JsonNode::asText).containsExactly("첫째", "둘째");
        assertThat(body.path("top_n").asInt()).as("자르기는 서버의 top_n 해석에 기대지 않고 우리가 한다").isEqualTo(2);
    }

    @Test
    @DisplayName("문서는 마크다운 장식을 걷고 길이를 자른다 — 한 쌍이 서버 배치에 들어가야 한다")
    void documentsAreNormalizedAndTruncated() throws IOException {
        String url = start(200, results("0", "1.0"));
        var reranker = new CrossEncoderReranker(props(url));
        String longText = "가".repeat(CrossEncoderReranker.MAX_DOC_CHARS + 500);

        reranker.rerank("q".repeat(CrossEncoderReranker.MAX_QUERY_CHARS + 100), List.of(doc("a", longText)), 1);

        JsonNode body = requests.get(0);
        assertThat(body.path("documents").get(0).asText()).hasSize(CrossEncoderReranker.MAX_DOC_CHARS);
        assertThat(body.path("query").asText()).hasSize(CrossEncoderReranker.MAX_QUERY_CHARS);
        assertThat(CrossEncoderReranker.textFor(doc("b", "**굵게** 쓴 글"))).doesNotContain("**");
    }

    @Test
    @DisplayName("base-url 끝의 / 와 /v1 은 있어도 없어도 같은 주소로 간다")
    void endpointBaseIsNormalized() {
        assertThat(CrossEncoderReranker.endpointBase("http://h:28095")).isEqualTo("http://h:28095");
        assertThat(CrossEncoderReranker.endpointBase("http://h:28095/")).isEqualTo("http://h:28095");
        assertThat(CrossEncoderReranker.endpointBase("http://h:28095/v1")).isEqualTo("http://h:28095");
        assertThat(CrossEncoderReranker.endpointBase(" http://h:28095/v1/ ")).isEqualTo("http://h:28095");
    }

    @Test
    @DisplayName("후보가 비면 서버를 부르지 않는다")
    void emptyCandidatesDoNotCallTheServer() throws IOException {
        String url = start(200, results("0", "1.0"));
        var reranker = new CrossEncoderReranker(props(url));

        assertThat(reranker.rerank("q", List.of(), 5)).isEmpty();

        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("범위 밖 index · 점수 없는 항목은 버리고 나머지를 쓴다")
    void invalidEntriesAreSkipped() throws IOException {
        String url = start(200, "{\"results\":[{\"index\":9,\"relevance_score\":5.0},{\"index\":0},"
                + "{\"index\":1,\"relevance_score\":2.0}]}");
        var reranker = new CrossEncoderReranker(props(url));

        List<Ranked> out = reranker.rerank("q", List.of(doc("a", "a"), doc("b", "b")), 5);

        assertThat(out).extracting(r -> r.doc().getId()).containsExactly("b");
    }

    @Test
    @DisplayName("읽을 수 없는 응답 · 쓸 항목이 없는 응답 · 4xx/5xx 는 UnavailableException — 건너뛰기 창은 열지 않는다")
    void unusableResponsesAreUnavailableButDoNotBlock() throws IOException {
        for (var bad : List.of(new Object[]{200, "not json"}, new Object[]{200, "{\"results\":[]}"},
                new Object[]{200, "{\"nothing\":1}"}, new Object[]{400, "{\"error\":\"too large\"}"},
                new Object[]{503, "{\"error\":\"busy\"}"})) {
            if (server != null) server.stop(0);
            requests.clear();
            String url = start((Integer) bad[0], (String) bad[1]);
            var reranker = new CrossEncoderReranker(props(url));

            assertThatThrownBy(() -> reranker.rerank("q", List.of(doc("a", "a")), 1))
                    .as("%s %s", bad[0], bad[1]).isInstanceOf(UnavailableException.class);
            assertThatThrownBy(() -> reranker.rerank("q", List.of(doc("a", "a")), 1))
                    .as("두 번째도 서버까지 간다(건너뛰기 없음)").isInstanceOf(UnavailableException.class);
            assertThat(requests).as("%s %s", bad[0], bad[1]).hasSize(2);
        }
    }

    @Test
    @DisplayName("연결 실패는 UnavailableException 이고 30초 동안 서버를 다시 부르지 않는다 — 시간이 지나면 다시 시도한다")
    void connectionFailureBlocksTheServerForAWhile() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        AtomicLong now = new AtomicLong(1_000_000_000L);
        var reranker = new CrossEncoderReranker(props("http://127.0.0.1:" + port), now::get);

        // 아직 아무도 듣지 않는 포트 — 연결 거부
        assertThatThrownBy(() -> reranker.rerank("q", List.of(doc("a", "a")), 1))
                .isInstanceOf(UnavailableException.class).hasMessageContaining("호출 실패");

        // 서버가 같은 포트에서 살아나도, 창 안에서는 부르지 않는다
        startOn(port, 200, results("0", "1.0"));
        assertThatThrownBy(() -> reranker.rerank("q", List.of(doc("a", "a")), 1))
                .isInstanceOf(UnavailableException.class).hasMessageContaining("건너뛴다");
        assertThat(requests).as("건너뛰는 동안 서버로 가지 않는다").isEmpty();

        // 창이 지나면 다시 서버로 간다
        now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(CrossEncoderReranker.BLOCK_SECONDS) + 1);
        assertThat(reranker.rerank("q", List.of(doc("a", "a")), 1)).hasSize(1);
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("동시에 나가는 호출은 max-concurrent 로 제한된다")
    void concurrencyIsLimited() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int now = inFlight.incrementAndGet();
            maxSeen.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            byte[] out = results("0", "1.0").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        var reranker = new CrossEncoderReranker(props("http://127.0.0.1:" + server.getAddress().getPort()));   // max-concurrent 2

        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(pool.submit(() -> reranker.rerank("q", List.of(doc("a", "a")), 1)));
            }
            for (var f : fs) f.get();
        }

        assertThat(maxSeen.get()).isEqualTo(2);
    }
}
