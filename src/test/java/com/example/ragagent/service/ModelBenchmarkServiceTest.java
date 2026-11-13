package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingDialect;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 성능 측정이 실제 HTTP 로 무엇을 보내고 무엇을 세는가 — 내장 {@link HttpServer} 에 대고 본다. 고정하는 것: 생각 끔/켬이
 * 실제 전송과 같은 필드로 나가는가, 워밍업은 세지 않는가, 서버의 {@code usage}·{@code timings} 를 읽는가, 임베딩·리랭커 모델명을
 * 서버 목록으로 해석하는가, 워밍업이 실패한 대상은 건너뛰는가.
 */
class ModelBenchmarkServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> embedRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> rerankRequests = new CopyOnWriteArrayList<>();
    private volatile boolean chatFails;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> reply(ex, 200,
                "{\"data\":[{\"id\":\"bge-m3-Q8_0.gguf\"},{\"id\":\"bge-reranker-v2-m3-Q8_0.gguf\"},{\"id\":\"llm.gguf\"}]}"));
        server.createContext("/v1/chat/completions", ex -> {
            JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
            chatRequests.add(req);
            if (chatFails) { reply(ex, 500, "{\"error\":\"engine down\"}"); return; }
            boolean thinking = req.path("chat_template_kwargs").path("enable_thinking").asBoolean(false);
            String body = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"답변입니다\""
                    + (thinking ? ",\"reasoning_content\":\"먼저 생각해 보면 이렇습니다\"" : "") + "}}],"
                    + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":" + (thinking ? 120 : 40) + "},"
                    + "\"timings\":{\"prompt_per_second\":800.5,\"predicted_per_second\":" + (thinking ? 50.0 : 60.0) + "}}";
            reply(ex, 200, body);
        });
        server.createContext("/v1/embeddings", ex -> {
            embedRequests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            reply(ex, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4]}],\"usage\":{\"prompt_tokens\":12}}");
        });
        server.createContext("/v1/rerank", ex -> {
            JsonNode req = JSON.readTree(ex.getRequestBody().readAllBytes());
            rerankRequests.add(req);
            StringBuilder sb = new StringBuilder("{\"results\":[");
            for (int i = 0; i < req.path("documents").size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"index\":").append(i).append(",\"relevance_score\":").append(1.0 - i * 0.1).append('}');
            }
            reply(ex, 200, sb.append("]}").toString());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    private ModelBenchmarkService service(boolean withAux) {
        LlmProvider local = new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 1, null,
                base + "/v1", "llm.gguf", false, null, null);
        LlmProvider remote = new LlmProvider("cloud", TaskType.TEXT, ProviderRole.PREMIUM, 2, "sk-test-key",
                base + "/v1", "gpt-x", false, null, null);
        LlmRouter router = mock(LlmRouter.class);
        when(router.allProviders()).thenReturn(List.of(local, remote));
        when(router.isDisabled(anyString())).thenReturn(false);

        ProviderThinkingDialects dialects = new ProviderThinkingDialects();
        dialects.record("local", ThinkingDialect.AUTO, true);    // LOCAL → chat_template_kwargs.enable_thinking
        dialects.record("cloud", ThinkingDialect.AUTO, false);   // 원격 → 아무것도 싣지 않는다

        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                withAux ? base : null, "", "bge-m3", 4, 5, 30, false, 0, List.of(), 1));
        when(props.rerankerSafe()).thenReturn(new AppProperties.RerankerConfig(
                withAux ? base : "", "bge-reranker-v2-m3", 5, 15, 2, ""));
        when(props.rerankerBaseUrls()).thenReturn(withAux ? List.of(base + "/v1") : List.of());
        return new ModelBenchmarkService(router, props, dialects);
    }

    private static ModelBenchmarkService.Job await(ModelBenchmarkService.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (job.running() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(job.running()).as("측정이 제시간에 끝난다").isFalse();
        return job;
    }

    @Test
    @DisplayName("대상 목록 — LLM(우선순위 순) → 임베딩 → 리랭커, 원격은 기본 선택이 아니고 키는 뷰에 없다")
    void targets() {
        List<ModelBenchmarkService.TargetView> targets = service(true).targets();
        assertThat(targets).extracting(ModelBenchmarkService.TargetView::id)
                .containsExactly("llm:local", "llm:cloud", "embed:0", "rerank:0");
        assertThat(targets).filteredOn(t -> t.id().equals("llm:cloud")).singleElement()
                .satisfies(t -> {
                    assertThat(t.local()).isFalse();
                    assertThat(t.thinking()).isNull();
                    assertThat(t.toString()).doesNotContain("sk-test-key");
                });
        assertThat(targets.get(0).thinking()).isEqualTo("chat_template_kwargs.enable_thinking");
    }

    @Test
    @DisplayName("LLM — 생각 끔/켬을 실제 전송과 같은 필드로 보내고, 워밍업 1회는 세지 않으며, usage·timings 를 읽는다")
    void llmThinkingOffAndOn() throws Exception {
        ModelBenchmarkService svc = service(false);
        var job = await(svc.start(List.of("llm:local"), new ModelBenchmarkService.Options(true, true, 1)));

        int prompts = ModelBenchmarkService.LLM_PROMPTS.size();
        assertThat(chatRequests).hasSize(1 + prompts * 2);   // 워밍업 + 끔 + 켬
        assertThat(chatRequests.subList(1, 1 + prompts))
                .allSatisfy(r -> assertThat(r.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse());
        assertThat(chatRequests.subList(1 + prompts, chatRequests.size()))
                .allSatisfy(r -> {
                    assertThat(r.path("chat_template_kwargs").path("enable_thinking").asBoolean(false)).isTrue();
                    assertThat(r.path("max_tokens").asInt()).isEqualTo(ModelBenchmarkService.MAX_TOKENS_THINKING_ON);
                });
        assertThat(chatRequests).allSatisfy(r -> assertThat(r.path("model").asText()).isEqualTo("llm.gguf"));

        var run = job.runs().get(0);
        assertThat(run.status()).isEqualTo(ModelBenchmarkService.RunStatus.DONE);
        assertThat(run.warmupMs()).isNotNull();
        var off = run.modes().get(0);
        var on = run.modes().get(1);
        assertThat(off.mode()).isEqualTo(ModelBenchmarkService.ThinkingMode.OFF);
        assertThat(off.okCount()).isEqualTo(prompts);
        assertThat(off.tokens().avg()).isEqualTo(40.0);
        assertThat(off.tokensEstimated()).isFalse();
        assertThat(off.decodeTps().avg()).isEqualTo(60.0);
        assertThat(off.prefillTps().avg()).isEqualTo(800.5);
        assertThat(off.reasoningTokens().present()).isFalse();
        assertThat(off.tps().present()).isTrue();
        assertThat(on.tokens().avg()).isEqualTo(120.0);
        assertThat(on.decodeTps().avg()).isEqualTo(50.0);
        assertThat(on.reasoningTokens().present()).as("reasoning_content 가 오면 생각 토큰을 추정한다").isTrue();
        assertThat(job.progressPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("LLM — 생각 제어를 받지 않는 프로바이더는 '서버 기본값' 한 모드만, 필드 없이 보낸다(키는 Bearer 로)")
    void llmWithoutThinkingControl() throws Exception {
        var job = await(service(false).start(List.of("llm:cloud"), new ModelBenchmarkService.Options(true, true, 1)));
        assertThat(job.runs().get(0).modes()).extracting(ModelBenchmarkService.ModeRun::mode)
                .containsExactly(ModelBenchmarkService.ThinkingMode.DEFAULT);
        assertThat(chatRequests).allSatisfy(r -> {
            assertThat(r.has("chat_template_kwargs")).isFalse();
            assertThat(r.has("reasoning_effort")).isFalse();
        });
    }

    @Test
    @DisplayName("임베딩·리랭커 — 모델명을 서버 목록으로 해석하고, 반복 횟수만큼 보내며, 차원·문서 수를 센다")
    void embeddingAndReranker() throws Exception {
        var job = await(service(true).start(List.of("embed:0", "rerank:0"), new ModelBenchmarkService.Options(true, false, 2)));

        assertThat(embedRequests).hasSize(1 + ModelBenchmarkService.EMBEDDING_TEXTS.size() * 2);
        assertThat(embedRequests).allSatisfy(r -> assertThat(r.path("model").asText()).isEqualTo("bge-m3-Q8_0.gguf"));
        var embed = job.runs().get(0);
        assertThat(embed.resolvedModel()).isEqualTo("bge-m3-Q8_0.gguf");
        assertThat(embed.modes().get(0).dimensions()).isEqualTo(4);
        assertThat(embed.modes().get(0).tokens().avg()).isEqualTo(12.0);
        assertThat(embed.modes().get(0).tokensEstimated()).isFalse();

        assertThat(rerankRequests).hasSize(1 + ModelBenchmarkService.RERANK_QUERIES.size() * 2);
        assertThat(rerankRequests).allSatisfy(r -> {
            assertThat(r.path("model").asText()).isEqualTo("bge-reranker-v2-m3-Q8_0.gguf");
            assertThat(r.path("documents").size()).isEqualTo(ModelBenchmarkService.RERANK_DOCUMENTS.size());
        });
        var rerank = job.runs().get(1).modes().get(0);
        assertThat(rerank.okCount()).isEqualTo(ModelBenchmarkService.RERANK_QUERIES.size() * 2L);
        assertThat(rerank.docsPerSecond().present()).isTrue();
        assertThat(rerank.tokensEstimated()).as("서버가 토큰 수를 주지 않으면 추정").isTrue();
    }

    @Test
    @DisplayName("워밍업이 실패하면 그 대상은 건너뛰고 사유를 남긴다 — 같은 실패를 프롬프트 수만큼 보내지 않는다")
    void warmupFailureSkips() throws Exception {
        chatFails = true;
        var job = await(service(false).start(List.of("llm:local"), new ModelBenchmarkService.Options(true, true, 1)));
        var run = job.runs().get(0);
        assertThat(run.status()).isEqualTo(ModelBenchmarkService.RunStatus.SKIPPED);
        assertThat(run.error()).contains("500");
        assertThat(chatRequests).hasSize(1);
        assertThat(job.progressPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("고른 대상이 없으면 거절하고, 하나가 도는 동안 둘째는 시작하지 않는다")
    void startGuards() throws Exception {
        ModelBenchmarkService svc = service(false);
        assertThatThrownBy(() -> svc.start(List.of("llm:nope"), new ModelBenchmarkService.Options(true, false, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        var job = svc.start(List.of("llm:local"), new ModelBenchmarkService.Options(true, true, 1));
        if (job.running()) {
            assertThatThrownBy(() -> svc.start(List.of("llm:local"), new ModelBenchmarkService.Options(true, false, 1)))
                    .isInstanceOf(ModelBenchmarkService.BusyException.class);
        }
        await(job);
        assertThat(svc.running()).isEmpty();
        assertThat(svc.job(job.id())).containsSame(job);
    }

    @Test
    @DisplayName("응답 해석 — usage 가 없으면 토큰을 추정으로 표시하고, choices 가 없으면 실패")
    void parseLlm() throws Exception {
        var estimated = ModelBenchmarkService.parseLlm(
                JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"가나다라마바사\"}}]}"), 1000);
        assertThat(estimated.ok()).isTrue();
        assertThat(estimated.tokensEstimated()).isTrue();
        assertThat(estimated.tokens()).isEqualTo(7L);
        assertThat(estimated.tps()).isEqualTo(7.0);
        assertThat(estimated.decodeTps()).isNull();

        var bad = ModelBenchmarkService.parseLlm(JSON.readTree("{\"error\":\"x\"}"), 10);
        assertThat(bad.ok()).isFalse();
    }

    @Test
    @DisplayName("옵션 — 반복 횟수는 1~5 로 눌리고, 생각 모드를 둘 다 끄면 끔으로 잰다")
    void options() {
        assertThat(new ModelBenchmarkService.Options(true, true, 99).rounds()).isEqualTo(ModelBenchmarkService.MAX_ROUNDS);
        assertThat(new ModelBenchmarkService.Options(true, true, 0).rounds()).isEqualTo(ModelBenchmarkService.MIN_ROUNDS);
        assertThat(new ModelBenchmarkService.Options(false, false, 3).thinkingOff()).isTrue();
    }

    @Test
    @DisplayName("목록 — 임베딩·리랭커는 지금 닿고 모델이 있는 주소만, 나머지는 사유와 함께 빼며 id 는 원래 위치를 지킨다")
    void catalogFiltersUnmeasurableEndpoints() {
        ModelBenchmarkService svc = service(true);
        // 임베딩 주소를 둘로(두 번째는 죽은 주소) — 첫 번째만 닿는다
        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                "http://dead.invalid:1/v1", "", "bge-m3", 4, 5, 30, false, 0, List.of(base), 1));
        when(props.rerankerSafe()).thenReturn(new AppProperties.RerankerConfig(base, "bge-reranker-v2-m3", 5, 15, 2, ""));
        when(props.rerankerBaseUrls()).thenReturn(List.of(base + "/v1"));
        LlmRouter router = mock(LlmRouter.class);
        when(router.allProviders()).thenReturn(List.of());
        svc = new ModelBenchmarkService(router, props, new ProviderThinkingDialects());
        svc.endpointCheck = (url, key, model) -> {
            if (url.contains("dead.invalid")) return new com.example.ragagent.llm.ProviderConnectivity.Result(false, 5L, null, "ConnectException: refused", null);
            if (model.contains("reranker")) return new com.example.ragagent.llm.ProviderConnectivity.Result(true, 5L, false, null, null);
            return new com.example.ragagent.llm.ProviderConnectivity.Result(true, 5L, true, null, null);
        };

        var catalog = svc.catalog();
        assertThat(catalog.targets()).extracting(ModelBenchmarkService.TargetView::id).containsExactly("embed:1");
        assertThat(catalog.excluded()).extracting(x -> x.target().id() + "=" + x.reason())
                .containsExactly("embed:0=unreachable", "rerank:0=model-missing");
        assertThat(catalog.excluded().get(0).detail()).contains("refused");
    }

    @Test
    @DisplayName("리랭커 데이터셋 —문서는 실제 청크에 가깝게 100~300자, 질의에는 50자 이상의 긴 질문이 둘 이상")
    void rerankDatasetShape() {
        assertThat(ModelBenchmarkService.RERANK_DOCUMENTS)
                .allSatisfy(d -> assertThat(d.length()).as(d).isBetween(100, 300));
        assertThat(ModelBenchmarkService.RERANK_QUERIES).filteredOn(q -> q.length() >= 50).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("통계 문구 — 최소 / 평균 / 최대, 값이 없으면 '-'")
    void statText() {
        var stat = ModelBenchmarkService.Stat.of(new ArrayList<>(List.of(10.0, 20.0, 30.0)));
        assertThat(stat.text(1)).isEqualTo("10.0 / 20.0 / 30.0");
        assertThat(stat.avgText(0)).isEqualTo("20");
        assertThat(ModelBenchmarkService.Stat.of(List.of()).text(1)).isEqualTo("-");
        assertThat(ModelBenchmarkService.Stat.of(List.of(1234.0)).text(0)).isEqualTo("1,234 / 1,234 / 1,234");
    }

    @Test
    @DisplayName("요청 본문 — 생각 제어 필드는 본문 최상위, 표준 reasoning_effort 는 그 이름으로")
    void llmBody() {
        Map<String, Object> body = ModelBenchmarkService.llmBody("m", "질문",
                ThinkingDialect.OPENAI_EFFORT.wire(com.example.ragagent.llm.ThinkingLevel.OFF), 100);
        assertThat(body).containsEntry("reasoning_effort", "none").containsEntry("max_tokens", 100)
                .containsEntry("stream", false).doesNotContainKey("chat_template_kwargs");
    }
}
