package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /settings} 프로바이더 표의 상태 열(미설정 · 접속불가 · 정상)이 읽는 "서버에 실제로 물어본 결과".
 *
 * <p>앞쪽은 가짜 prober 로 <b>언제 묻고 언제 안 묻는가</b>(재사용 창 · 병렬 · 예외)를, 뒤쪽은 진짜 HTTP 서버로 <b>무엇을 어디에
 * 어떻게 묻는가</b>(주소 만들기 · Bearer · 오류 응답 · 닫힌 포트)를 고정한다.
 */
class ProviderConnectivityTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static AppProperties.ProviderConfig cfg(String name, String baseUrl) {
        return new AppProperties.ProviderConfig(name, baseUrl, "", "gemma-4-e2b", "BOTH", "LOCAL", 1, true, null, null, null);
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static LlmPing.Reach up() {
        return new LlmPing.Reach(true, 12L, true, null);
    }

    // ── 언제 묻는가 ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("한 번도 묻지 않았으면 마지막 확인이 없다 — 화면은 '확인 중'으로 그린다")
    void lastIsEmptyUntilTheFirstRefresh() {
        ProviderConnectivity connectivity = new ProviderConnectivity(new MutableClock(), c -> up());

        assertThat(connectivity.last("local")).isEmpty();
        assertThat(connectivity.last(null)).isEmpty();
    }

    @Test
    @DisplayName("프로바이더마다 결과가 따로 남는다 — 닿는 서버와 안 닿는 서버가 섞여도 서로를 가리지 않는다")
    void recordsEachProviderSeparately() {
        MutableClock clock = new MutableClock();
        ProviderConnectivity connectivity = new ProviderConnectivity(clock, c ->
                c.name().equals("a") ? up() : new LlmPing.Reach(false, 3000L, null, "models: ConnectException: Connection refused"));

        connectivity.refresh(List.of(cfg("a", "http://a/v1"), cfg("b", "http://b/v1")));

        ProviderConnectivity.Result a = connectivity.last("a").orElseThrow();
        assertThat(a.reachable()).isTrue();
        assertThat(a.latencyMs()).isEqualTo(12L);
        assertThat(a.modelListed()).isTrue();
        assertThat(a.checkedAt()).isEqualTo(clock.now);
        ProviderConnectivity.Result b = connectivity.last("b").orElseThrow();
        assertThat(b.reachable()).isFalse();
        assertThat(b.error()).contains("Connection refused");
    }

    @Test
    @DisplayName("재사용 창 안에서는 다시 묻지 않고, 창이 지나면 다시 묻는다 — 새로 고침이 서버를 두드리는 수단이 되지 않는다")
    void reusesAFreshResultAndAsksAgainOnceItIsOld() {
        MutableClock clock = new MutableClock();
        AtomicInteger asked = new AtomicInteger();
        ProviderConnectivity connectivity = new ProviderConnectivity(clock, c -> {
            asked.incrementAndGet();
            return up();
        });
        List<AppProperties.ProviderConfig> targets = List.of(cfg("local", "http://x/v1"));

        connectivity.refresh(targets);
        connectivity.refresh(targets);
        assertThat(asked).as("같은 순간의 두 번째 호출은 첫 결과를 나눠 쓴다").hasValue(1);

        clock.now = clock.now.plus(ProviderConnectivity.REUSE_WINDOW).plusMillis(1);
        connectivity.refresh(targets);
        assertThat(asked).as("창이 지나면 다시 묻는다").hasValue(2);
    }

    @Test
    @DisplayName("창 안이어도 아직 안 물어본 프로바이더는 묻는다 — 재사용은 프로바이더 단위다")
    void reuseIsPerProvider() {
        MutableClock clock = new MutableClock();
        AtomicInteger asked = new AtomicInteger();
        ProviderConnectivity connectivity = new ProviderConnectivity(clock, c -> {
            asked.incrementAndGet();
            return up();
        });

        connectivity.refresh(List.of(cfg("a", "http://a/v1")));
        connectivity.refresh(List.of(cfg("a", "http://a/v1"), cfg("b", "http://b/v1")));

        assertThat(asked).as("a 는 재사용, b 만 새로").hasValue(2);
        assertThat(connectivity.last("b")).isPresent();
    }

    @Test
    @DisplayName("서버 여러 대는 병렬로 묻는다 — 죽은 서버의 연결 타임아웃이 산 서버의 칸을 늦추지 않는다")
    void asksInParallel() {
        CountDownLatch bothInside = new CountDownLatch(2);
        ProviderConnectivity connectivity = new ProviderConnectivity(new MutableClock(), c -> {
            bothInside.countDown();
            try {
                // 직렬로 돌면 첫 호출이 둘째를 영영 기다린다 — 시간 안에 못 모이면 실패로 기록된다
                if (!bothInside.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("not parallel");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return up();
        });

        connectivity.refresh(List.of(cfg("a", "http://a/v1"), cfg("b", "http://b/v1")));

        assertThat(connectivity.last("a").orElseThrow().reachable()).isTrue();
        assertThat(connectivity.last("b").orElseThrow().reachable()).isTrue();
    }

    @Test
    @DisplayName("prober 가 예외를 던져도 refresh 는 던지지 않는다 — 실패 자체가 답(접속불가)이다")
    void aThrowingProberIsRecordedAsUnreachable() {
        ProviderConnectivity connectivity = new ProviderConnectivity(new MutableClock(), c -> {
            throw new IllegalStateException("boom");
        });

        connectivity.refresh(List.of(cfg("local", "http://x/v1")));

        ProviderConnectivity.Result result = connectivity.last("local").orElseThrow();
        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("boom");
    }

    @Test
    @DisplayName("대상이 없으면 아무것도 묻지 않는다")
    void nothingToAsk() {
        AtomicInteger asked = new AtomicInteger();
        ProviderConnectivity connectivity = new ProviderConnectivity(new MutableClock(), c -> {
            asked.incrementAndGet();
            return up();
        });

        connectivity.refresh(List.of());
        connectivity.refresh(null);

        assertThat(asked).hasValue(0);
    }

    // ── 무엇을 어디에 어떻게 묻는가 (진짜 HTTP) ───────────────────────────────────────────────────

    private record Seen(String path, String authorization) {}

    /** {@code /v1/models} 에 {@code status}·{@code body} 로 답하는 서버. 받은 요청은 {@code seen} 에 남긴다. */
    private String start(int status, String body, AtomicReference<Seen> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization")));
            boolean models = "/v1/models".equals(exchange.getRequestURI().getPath());
            byte[] bytes = (models ? body : "{\"error\":\"nope\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(models ? status : 404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ProviderConnectivity.Result ask(String baseUrl) {
        ProviderConnectivity connectivity = new ProviderConnectivity();
        connectivity.refresh(List.of(cfg("local", baseUrl)));
        return connectivity.last("local").orElseThrow();
    }

    @Test
    @DisplayName("모델 목록 API 가 2xx 로 답하면 정상 — 설정한 모델이 목록에 있는지도 함께 읽는다")
    void reachableWhenTheModelListAnswers() throws IOException {
        AtomicReference<Seen> seen = new AtomicReference<>();
        String base = start(200, "{\"data\":[{\"id\":\"gemma-4-e2b\"}]}", seen);

        ProviderConnectivity.Result result = ask(base + "/v1");

        assertThat(result.reachable()).isTrue();
        assertThat(result.modelListed()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(seen.get().path()).isEqualTo("/v1/models");
    }

    @Test
    @DisplayName("설정한 모델이 목록에 없어도 접속은 접속이다 — 상태 3단계를 바꾸지 않고 modelListed=false 로만 남는다")
    void aMissingModelDoesNotMakeTheServerUnreachable() throws IOException {
        String base = start(200, "{\"data\":[{\"id\":\"some-other-model\"}]}", new AtomicReference<>());

        ProviderConnectivity.Result result = ask(base + "/v1");

        assertThat(result.reachable()).isTrue();
        assertThat(result.modelListed()).isFalse();
    }

    @Test
    @DisplayName("설정에 /v1 이 없는 주소도 앱이 실제로 요청하는 /v1/models 로 묻는다 — 상태 열에서만 404 가 되지 않는다")
    void addsTheV1SegmentTheAppAlwaysAdds() throws IOException {
        AtomicReference<Seen> seen = new AtomicReference<>();
        String base = start(200, "{\"data\":[]}", seen);

        assertThat(ask(base).reachable()).isTrue();
        assertThat(seen.get().path()).isEqualTo("/v1/models");

        assertThat(ask(base + "/").reachable()).as("뒤쪽 슬래시").isTrue();
        assertThat(seen.get().path()).isEqualTo("/v1/models");
    }

    @Test
    @DisplayName("키가 비어 있으면 앱이 프로바이더를 만들 때와 같은 자리표시자를, 있으면 그 키를 Bearer 로 싣는다")
    void sendsTheSameBearerTheAppUses() throws IOException {
        AtomicReference<Seen> seen = new AtomicReference<>();
        String base = start(200, "{\"data\":[]}", seen);

        ask(base + "/v1");
        assertThat(seen.get().authorization()).isEqualTo("Bearer no-key");

        ProviderConnectivity connectivity = new ProviderConnectivity();
        connectivity.refresh(List.of(new AppProperties.ProviderConfig(
                "remote", base + "/v1", "sk-secret", "gpt", "BOTH", "NORMAL", 1, true, null, null, null)));
        assertThat(seen.get().authorization()).isEqualTo("Bearer sk-secret");
        assertThat(connectivity.last("remote").orElseThrow().error()).as("키는 결과에 실리지 않는다").isNull();
    }

    @Test
    @DisplayName("HTTP 오류는 접속불가다 — 사유에 상태 코드가 실린다(키 오류 · 없는 엔드포인트)")
    void anHttpErrorIsUnreachableWithTheStatus() throws IOException {
        String base = start(401, "{\"error\":\"invalid key\"}", new AtomicReference<>());

        ProviderConnectivity.Result result = ask(base + "/v1");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("HTTP 401");
    }

    @Test
    @DisplayName("닫힌 포트는 접속불가다 — 던지지 않고 사유가 남는다")
    void aClosedPortIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        ProviderConnectivity.Result result = ask("http://127.0.0.1:" + port + "/v1");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.latencyMs()).isNotNull();
        assertThat(Duration.ofMillis(result.latencyMs())).as("연결 거부는 타임아웃을 기다리지 않는다")
                .isLessThan(Duration.ofSeconds(ProviderConnectivity.CONNECT_TIMEOUT_SECONDS + 1));
    }
}
