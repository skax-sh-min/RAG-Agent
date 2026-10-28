package com.example.ragagent.service;

import com.example.ragagent.SqliteTestDatabase;
import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.LlmConfig;
import com.example.ragagent.config.ProviderOverride;
import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.TokenEstimateCalibration;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.ProviderOverrideRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 화면에서 고친 프로바이더가 기동 때 실제 라우터에 얹히는가 — 그리고 그 값이 틀렸을 때 기동을 막지 않는가.
 *
 * <p>진짜 {@link LlmConfig#llmRouter} 를 가짜 {@code /v1/models} 서버에 대고 돌린다. {@link ProviderCatalog} 가 {@code AppProperties}
 * 의 정적 오버레이를 묶으므로 다른 테스트와 함께 돌지 않게 잠근다.
 */
@ResourceLock("global-state")
class ProviderCatalogStartupTest {

    @TempDir Path tmp;

    private HttpServer server;
    private String baseUrl;
    private ProviderOverrideRepository repo;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-e4b\"},{\"id\":\"qwen3-8b\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        repo = new ProviderOverrideRepository(SqliteTestDatabase.open(tmp.resolve("t.db")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        AppProperties.unbindProviderOverlay();
    }

    private AppProperties props() {
        var local = new AppProperties.ProviderConfig("local", baseUrl, "", "gemma-4-e4b", "BOTH", "LOCAL", 1,
                true, null, null, null);
        var llm = new AppProperties.LlmConfig(
                List.of(local), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 12_000, 1, true);
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false, true, false, 3,
                null, llm, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 기동 순서 그대로 — catalog 가 먼저 묶이고, 설정 클래스가 그것을 받아 라우터를 만든다. */
    private LlmRouter boot(AppProperties props, ProviderCatalog catalog, ProviderContextWindows windows) {
        catalog.init();
        LlmConfig config = new LlmConfig();
        config.setProviderCatalog(catalog);
        return config.llmRouter(props, mock(LlmUsageRepository.class), new CircuitBreaker(2), new ProviderToggle(),
                new BackgroundLlmConcurrencyTracker(), windows, new TokenEstimateCalibration(),
                new ProviderThinkingDialects(), new ThinkingObservations());
    }

    private ProviderCatalog catalog(AppProperties props, ProviderContextWindows windows) {
        return new ProviderCatalog(repo, props, windows, mock(AuditLogger.class));
    }

    @Test
    @DisplayName("고친 모델이 서버에 있으면 그 값으로 기동하고, 모든 소비자가 props.llmSafe() 에서 같은 목록을 본다")
    void aValidEditIsApplied() {
        repo.upsert(new ProviderOverride("local", null, "qwen3-8b", null, null, 3, null, false, false));
        AppProperties props = props();
        ProviderContextWindows windows = new ProviderContextWindows();

        LlmRouter router = boot(props, catalog(props, windows), windows);

        assertThat(router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST).model()).isEqualTo("qwen3-8b");
        assertThat(props.llmSafe().providers()).singleElement().satisfies(cfg -> {
            assertThat(cfg.model()).isEqualTo("qwen3-8b");
            assertThat(cfg.priority()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("고친 모델이 서버에 없으면 기동을 막지 않고 properties 정의로 띄운다 — 화면에는 실패 사유가 남는다")
    void aBrokenEditFallsBackInsteadOfFailingStartup() {
        repo.upsert(new ProviderOverride("local", null, "typo-model", null, null, null, null, false, false));
        AppProperties props = props();
        ProviderContextWindows windows = new ProviderContextWindows();
        ProviderCatalog catalog = catalog(props, windows);

        LlmRouter router = boot(props, catalog, windows);

        assertThat(router.routeProvider(TaskType.TEXT, RoutingMode.COST_FIRST).model()).isEqualTo("gemma-4-e4b");
        assertThat(props.llmSafe().providers()).singleElement()
                .satisfies(cfg -> assertThat(cfg.model()).isEqualTo("gemma-4-e4b"));
        assertThat(catalog.editOf("local").startupFailure()).contains("typo-model");
        assertThat(repo.findAll().get("local").model()).as("저장된 편집은 남는다").isEqualTo("typo-model");
    }

    @Test
    @DisplayName("화면에서 추가한 LOCAL 이 닿지 않으면 이번 기동에는 등록하지 않는다 — 나머지는 그대로 뜬다")
    void anUnreachableAddedProviderIsSkipped() {
        repo.upsert(new ProviderOverride("extra", "http://127.0.0.1:1/v1", "m", "TEXT", "LOCAL", 0, null, true, false));
        AppProperties props = props();
        ProviderContextWindows windows = new ProviderContextWindows();
        ProviderCatalog catalog = catalog(props, windows);

        LlmRouter router = boot(props, catalog, windows);

        assertThat(router.hasLocalProvider()).isTrue();
        assertThat(props.llmSafe().providers()).extracting(AppProperties.ProviderConfig::name).containsExactly("local");
        assertThat(catalog.editOf("extra").startupFailure()).isNotBlank();
    }

    @Test
    @DisplayName("화면에서 넣은 컨텍스트는 기동 때 탐지가 실패해야 쓰인다('수동 입력') — 선언처럼 읽지 않는다")
    void aManualContextIsUsedWhenTheProbeFails() {
        repo.upsert(new ProviderOverride("local", null, null, null, null, null, 32_768, false, false));
        AppProperties props = props();
        ProviderContextWindows windows = new ProviderContextWindows();

        boot(props, catalog(props, windows), windows);   // 이 가짜 서버에는 /props 가 없다 — 탐지 실패

        assertThat(windows.find("local")).hasValueSatisfying(w -> {
            assertThat(w.tokens()).isEqualTo(32_768);
            assertThat(w.source()).isEqualTo(ProviderContextWindows.Source.MANUAL);
        });
    }

    @Test
    @DisplayName("기동 때 탐지가 되면 수동 컨텍스트 대신 탐지값을 쓴다")
    void aSuccessfulProbeWinsOverTheManualContext() {
        server.createContext("/props", exchange -> {
            byte[] body = "{\"default_generation_settings\":{\"n_ctx\":40960}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        repo.upsert(new ProviderOverride("local", null, null, null, null, null, 32_768, false, false));
        AppProperties props = props();
        ProviderContextWindows windows = new ProviderContextWindows();

        boot(props, catalog(props, windows), windows);

        assertThat(windows.find("local")).hasValueSatisfying(w -> {
            assertThat(w.tokens()).isEqualTo(40_960);
            assertThat(w.source()).isEqualTo(ProviderContextWindows.Source.PROBED);
        });
    }
}
