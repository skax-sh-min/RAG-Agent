package com.example.ragagent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 하네스의 생각 예산 프록시 — 생각을 켠 채팅 요청에만 예산을 싣고, 나머지는 손대지 않고 흘려 보내는가. 외부 서버 없이 돈다.
 */
class ReasoningBudgetProxyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer upstream;
    private ReasoningBudgetProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) proxy.close();
        if (upstream != null) upstream.stop(0);
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("생각을 켠 요청에만 reasoning_budget_tokens 를 얹는다 — 끔·필드 없음·예산 0·JSON 아님은 그대로")
    void rewritesOnlyThinkingRequests() throws IOException {
        byte[] on = body("{\"model\":\"m\",\"chat_template_kwargs\":{\"enable_thinking\":true}}");
        JsonNode rewritten = JSON.readTree(ReasoningBudgetProxy.rewrite(on, 256));
        assertThat(rewritten.path("reasoning_budget_tokens").asInt()).isEqualTo(256);
        assertThat(rewritten.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isTrue();

        byte[] off = body("{\"chat_template_kwargs\":{\"enable_thinking\":false}}");
        assertThat(ReasoningBudgetProxy.rewrite(off, 256)).isSameAs(off);
        byte[] none = body("{\"model\":\"m\"}");
        assertThat(ReasoningBudgetProxy.rewrite(none, 256)).isSameAs(none);
        assertThat(ReasoningBudgetProxy.rewrite(on, 0)).isSameAs(on);
        byte[] notJson = body("not json");
        assertThat(ReasoningBudgetProxy.rewrite(notJson, 256)).isSameAs(notJson);
    }

    @Test
    @DisplayName("요청을 서버로 넘기고 응답(상태·형식·본문)을 그대로 돌려준다 — 예산은 채팅 요청에만, 실린 횟수를 센다")
    void forwardsAndInjects() throws Exception {
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().toString());
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = body("data: {\"x\":1}\n\ndata: [DONE]\n\n");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, reply.length);
            try (var os = exchange.getResponseBody()) {
                os.write(reply);
            }
        });
        upstream.start();
        proxy = ReasoningBudgetProxy.start("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
        proxy.budget(512);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> chat = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"chat_template_kwargs\":{\"enable_thinking\":true}}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(chat.statusCode()).isEqualTo(200);
        assertThat(chat.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        assertThat(chat.body()).contains("[DONE]");
        assertThat(seenPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(JSON.readTree(seenBody.get()).path("reasoning_budget_tokens").asInt()).isEqualTo(512);
        assertThat(proxy.injectedCount()).isEqualTo(1);

        HttpResponse<String> models = client.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl() + "/v1/models"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(models.statusCode()).isEqualTo(200);
        assertThat(seenPath.get()).isEqualTo("/v1/models");
        assertThat(proxy.injectedCount()).as("채팅 요청이 아니면 세지 않는다").isEqualTo(1);
    }
}
