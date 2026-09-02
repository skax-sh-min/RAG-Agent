package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.ProviderToggle;
import com.example.ragagent.repository.SettingsOverrideRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.26 A5 — 운영자가 누르는 컨텍스트 창 재탐지.
 *
 * <p>기동 시 한 번 탐지한 값은 낡는다: 서버를 다른 {@code -c} 로 다시 띄우거나, LM Studio 가 모델을
 * 기동 뒤에 JIT 로 올리면 앱은 낡은 — 또는 없는 — 숫자로 계속 예산을 짠다. <b>피해가 방향에 따라
 * 비대칭</b>이라 이 버튼이 필요하다: 창이 줄어든 쪽은 컨텍스트 초과로 요란하게 드러나지만, 창이
 * 커진 쪽은 멀쩡한 근거를 조용히 계속 버린다.
 *
 * <p>여기서 고정하는 것은 값 갱신 자체보다 <b>갱신하지 않기로 한 경우들</b>이다 — 선언된 창, 실패한
 * 탐지, LOCAL 이 아닌 프로바이더. 셋 다 "버튼이 상황을 악화시키지 않는다"는 약속이다.
 */
@ResourceLock("global-state")
class SettingsContextWindowReprobeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** llama.cpp 의 {@code /props} 만 흉내내는 최소 서버 — 탐지가 실제로 HTTP 를 타는지까지 본다. */
    private String startServer(int nCtx) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean props = "/props".equals(exchange.getRequestURI().getPath());
            byte[] body = (props
                    ? "{\"default_generation_settings\":{\"n_ctx\":" + nCtx + "}}"
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(props ? 200 : 404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Test
    @DisplayName("서버가 새 창을 말하면 갱신된다 — 재기동 없이 다음 호출의 입력 예산이 바뀐다")
    void updatesTheWindowFromTheLiveServer() throws IOException {
        String base = startServer(40_960);
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 20_480, ProviderContextWindows.Source.PROBED); // 기동 시점의 관측
        SettingsService svc = service(windows, provider("local", base, null, null));

        SettingsService.ReprobeResult result = svc.reprobeContextWindows();

        assertThat(windows.tokensOrZero("local")).isEqualTo(40_960);
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(SettingsService.ReprobeOutcome.UPDATED));
        assertThat(result.rows().get(0).before()).contains("20,480");
        assertThat(result.rows().get(0).after()).contains("40,960");
    }

    @Test
    @DisplayName("아직 모르던 프로바이더도 채워진다 — JIT 로딩이라 기동 시엔 로드된 인스턴스가 없었다")
    void fillsInAWindowThatWasUnknownAtStartup() throws IOException {
        String base = startServer(8_192);
        ProviderContextWindows windows = new ProviderContextWindows();   // 기록 없음 = "모름"
        SettingsService svc = service(windows, provider("local", base, null, null));

        svc.reprobeContextWindows();

        assertThat(windows.tokensOrZero("local")).isEqualTo(8_192);
    }

    @Test
    @DisplayName("탐지가 실패해도 이전 값을 지우지 않는다 — 알던 값을 '모름'으로 되돌리면 예산이 통째로 꺼진다")
    void aFailedProbeKeepsThePreviousValue() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 8_192, ProviderContextWindows.Source.PROBED);
        // 연결 거부되는 포트 — 서버가 잠깐 죽은 상황
        SettingsService svc = service(windows, provider("local", "http://127.0.0.1:1/v1", null, null));

        SettingsService.ReprobeResult result = svc.reprobeContextWindows();

        assertThat(windows.tokensOrZero("local")).isEqualTo(8_192);
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(SettingsService.ReprobeOutcome.FAILED));
    }

    @Test
    @DisplayName("context-size 로 선언된 프로바이더는 묻지 않는다 — 관측이 의도를 덮어쓰면 안 된다")
    void aDeclaredWindowIsNeverOverwritten() throws IOException {
        String base = startServer(40_960);   // 서버는 다른 값을 말하지만
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 16_384, ProviderContextWindows.Source.CONFIGURED);
        SettingsService svc = service(windows, provider("local", base, 16_384, null));

        SettingsService.ReprobeResult result = svc.reprobeContextWindows();

        assertThat(windows.tokensOrZero("local")).isEqualTo(16_384);
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r.outcome())
                        .isEqualTo(SettingsService.ReprobeOutcome.SKIPPED_DECLARED));
    }

    @Test
    @DisplayName("LOCAL 이 아닌 프로바이더는 아예 대상이 아니다 — 그 엔드포인트가 없다")
    void cloudProvidersAreNotProbed() {
        ProviderContextWindows windows = new ProviderContextWindows();
        AppProperties.ProviderConfig cloud = new AppProperties.ProviderConfig(
                "gemini", "http://127.0.0.1:1/v1", "key", "m", "BOTH", "NORMAL", 1,
                true, null, null, null);

        assertThat(service(windows, cloud).reprobeContextWindows().rows()).isEmpty();
    }

    @Test
    @DisplayName("창이 바뀌어 출력 예약이 달라져야 하면 재기동을 알린다 — 그 값은 빈에 구워져 있다")
    void warnsWhenTheBakedOutputCapNoLongerMatches() throws IOException {
        // 창 4,000 에서 기동 → max-tokens 6,000 이 창의 절반(2,000)으로 깎여 구워졌다.
        // 이제 창이 40,960 이라 6,000 이 그대로 들어가지만, 그 숫자는 재기동해야 돌아온다.
        String base = startServer(40_960);
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 4_000, ProviderContextWindows.Source.PROBED);

        SettingsService.ReprobeResult result =
                service(windows, provider("local", base, null, null)).reprobeContextWindows();

        assertThat(result.restartNeeded()).isTrue();
        assertThat(result.restartDetail()).contains("local", "2,000", "6,000");
    }

    @Test
    @DisplayName("창이 그대로면 재기동 안내도 없다")
    void noRestartNoticeWhenNothingChanged() throws IOException {
        String base = startServer(8_192);
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 8_192, ProviderContextWindows.Source.PROBED);

        SettingsService.ReprobeResult result =
                service(windows, provider("local", base, null, null)).reprobeContextWindows();

        assertThat(result.restartNeeded()).isFalse();
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(SettingsService.ReprobeOutcome.UNCHANGED));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AppProperties.ProviderConfig provider(String name, String baseUrl,
                                                         Integer contextSize, Integer maxTokens) {
        return new AppProperties.ProviderConfig(name, baseUrl, "key", "m", "BOTH", "LOCAL", 1,
                true, null, contextSize, maxTokens);
    }

    private SettingsService service(ProviderContextWindows windows,
                                    AppProperties.ProviderConfig... providers) {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(providers), 2, 2, 2, "COST_FIRST", 3, 20,
                0.0, 0.1, 0.0, 0.7, true, 6000, 1, false));
        SettingsOverrideRepository repo = mock(SettingsOverrideRepository.class);
        when(repo.findAll()).thenReturn(Map.of());
        return new SettingsService(repo, props, mock(AuditLogger.class), mock(CircuitBreaker.class),
                new ProviderToggle(), windows, mock(StorageQuotaService.class));
    }
}
