package com.example.ragagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 측정 하네스 전용 — 앱과 llama-server 사이에 끼워, <b>생각을 켠</b> 채팅 요청({@code chat_template_kwargs.enable_thinking=true})
 * 에만 본문 최상위 {@code reasoning_budget_tokens} 를 싣는 HTTP 프록시(PLAN §6.29 열린 항목 (a)).
 *
 * <p>제품에는 이 필드를 싣는 길이 없다 — 요청 단위 생각 예산을 구현할지는 효과를 잰 뒤에 정하기로 했고, 재려고 제품 코드에 dialect 를
 * 먼저 더하면 측정이 결정을 앞질러 버린다. 그래서 앱이 보내는 요청은 그대로 두고 선(線) 위에서만 바꾼다. 하네스가 {@code LOCAL_LLM_URL}
 * 을 이 프록시로 돌리고, 호출마다 {@link #budget(int)} 으로 값을 바꾼다(0 = 싣지 않음). 모델 목록·{@code /props} 같은 다른 요청과
 * 스트리밍 응답(SSE)은 손대지 않고 그대로 흘려 보낸다.
 */
final class ReasoningBudgetProxy implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 프록시가 직접 실을 수 없는 헤더(Host·Content-Length 등은 클라이언트가 다시 만든다) 대신 옮길 것만 고른다. */
    private static final List<String> FORWARDED_HEADERS = List.of("Content-Type", "Authorization", "Accept");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI upstream;
    private volatile int budget;
    private final AtomicInteger injected = new AtomicInteger();

    private ReasoningBudgetProxy(URI upstream) throws IOException {
        this.upstream = upstream;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** @param upstreamRoot 서버의 루트 URL(예: {@code http://localhost:1234}) — 뒤의 {@code /v1} 은 걷어서 넘긴다 */
    static ReasoningBudgetProxy start(String upstreamRoot) throws IOException {
        String root = upstreamRoot.replaceAll("/+$", "").replaceAll("/v1$", "");
        return new ReasoningBudgetProxy(URI.create(root));
    }

    /** 앱에 줄 루트 URL — 뒤에 {@code /v1} 을 붙여 {@code LOCAL_LLM_URL} 로 쓴다. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 다음 요청부터 실을 생각 예산(토큰). 0 이하 = 싣지 않는다. */
    void budget(int tokens) {
        this.budget = Math.max(0, tokens);
    }

    /** 지금까지 예산을 실어 보낸 요청 수 — 하네스가 호출 앞뒤로 비교해 정말 실렸는지 남긴다. */
    int injectedCount() {
        return injected.get();
    }

    /**
     * 생각을 켠 요청이면 {@code reasoning_budget_tokens} 를 얹은 본문, 아니면 받은 본문 그대로(같은 배열).
     * JSON 이 아니거나 읽을 수 없으면 손대지 않는다.
     */
    static byte[] rewrite(byte[] body, int budget) {
        if (budget <= 0 || body == null || body.length == 0) return body;
        try {
            JsonNode root = JSON.readTree(body);
            if (!(root instanceof ObjectNode object)) return body;
            if (!root.path("chat_template_kwargs").path("enable_thinking").asBoolean(false)) return body;
            object.put("reasoning_budget_tokens", budget);
            return JSON.writeValueAsBytes(object);
        } catch (IOException e) {
            return body;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().toString();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && path.contains("/chat/completions")) {
                byte[] rewritten = rewrite(body, budget);
                if (rewritten != body) injected.incrementAndGet();
                body = rewritten;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(upstream.resolve(path))
                    .timeout(Duration.ofMinutes(15))
                    .method(exchange.getRequestMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            for (String header : FORWARDED_HEADERS) {
                String value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) request.header(header, value);
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(502, -1);
                return;
            }
            response.headers().firstValue("Content-Type")
                    .ifPresent(v -> exchange.getResponseHeaders().add("Content-Type", v));
            int status = response.statusCode();
            boolean noBody = status == 204 || status == 304 || "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            // 길이 0 = chunked — 스트리밍 응답(SSE)을 받는 대로 흘려 보내야 앱의 유휴 워치독이 멀쩡한 답변을 끊지 않는다.
            exchange.sendResponseHeaders(status, noBody ? -1 : 0);
            if (noBody) return;
            try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
