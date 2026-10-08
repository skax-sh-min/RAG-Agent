package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 채팅 답변 스트리밍의 장애 전환 — "같은 로컬 서버 두 대 중 {@code local}({@code LOCAL_LLM_URL})이 죽었는데 {@code local-2} 는 멀쩡하다".
 *
 * <p>스트리밍은 {@code OpenAiApi} 를 직접 불러 라우터의 {@code executeGated} 순회를 우회하므로, 예전에는 {@code routeProvider} 가 고른
 * 죽은 서버로 가서 그대로 오류가 났다(같은 priority 의 둘이 한가하면 먼저 등록된 {@code local} 이 이긴다). 여기서 고정하는 것:
 * 첫 토큰 전의 서버 장애는 차단하고 다음 서버로 넘어가고, 그 밖의 모든 것(첫 토큰 이후·읽기 타임아웃·사용자 중단·컨텍스트
 * 초과·4xx)은 건드리지 않는다 — 스트리밍은 채팅의 유일한 전송 경로라 오탐이 가장 비싸다.
 */
class StreamFailoverTest {

    private CircuitBreaker breaker;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker(2);
        calls.clear();
    }

    private static LlmProvider provider(String name) {
        return new LlmProvider(name, TaskType.TEXT, ProviderRole.LOCAL, 1, "k", "http://" + name + "/v1", "m", true, null, null);
    }

    /** 허용 대기 1초 — 퍼밋 포화 테스트가 오래 걸리지 않게. */
    private LlmRouter router(String... names) {
        List<LlmProvider> providers = new ArrayList<>();
        for (String name : names) providers.add(provider(name));
        return new LlmRouter(providers, mock(LlmUsageRepository.class), breaker, RoutingMode.LOCAL_ONLY, 180,
                Map.of(), 3, 1, new ProviderToggle());
    }

    /** {@code dead} 로 열린 스트림만 {@code failure} 로 실패하고, 나머지는 자기 이름을 돌려준다. */
    private Function<LlmProvider, String> stream(String dead, RuntimeException failure) {
        return p -> {
            calls.add(p.name());
            if (p.name().equals(dead)) throw failure;
            return "served-by-" + p.name();
        };
    }

    private StreamFailover.Served<String> run(LlmRouter router, boolean outputStarted, Function<LlmProvider, String> stream) {
        return StreamFailover.run(router, TaskType.TEXT, RoutingMode.LOCAL_ONLY, () -> outputStarted, stream);
    }

    /** 실제 스트리밍(WebClient)이 연결 거부에서 올리는 모양. */
    private static RuntimeException refused() {
        return new WebClientRequestException(new ConnectException("Connection refused: getsockopt"),
                HttpMethod.POST, URI.create("http://local/v1/chat/completions"), new HttpHeaders());
    }

    private static WebClientResponseException http(int status, String body, HttpHeaders headers) {
        return WebClientResponseException.create(status, "status " + status, headers,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private long blockedSecondsFromNow(String name) {
        Instant until = breaker.getBlockedProviders().get(name);
        assertThat(until).as("%s 가 차단돼 있어야 한다", name).isNotNull();
        return Duration.between(Instant.now(), until).toSeconds();
    }

    // ── 전환한다 ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 토큰 전에 연결이 거부되면 그 서버를 차단하고 다음 서버로 스트림을 다시 연다 — 퍼밋은 놓는다")
    void aRefusedConnectionMovesToTheNextServer() {
        LlmRouter r = router("local", "local-2");

        var served = run(r, false, stream("local", refused()));

        assertThat(served.provider().name()).as("응답한 쪽을 돌려준다 — 호출부가 usedProvider·사용량을 그 이름으로 남긴다").isEqualTo("local-2");
        assertThat(served.value()).isEqualTo("served-by-local-2");
        assertThat(calls).containsExactly("local", "local-2");
        assertThat(breaker.isBlocked("local")).isTrue();
        assertThat(breaker.isBlocked("local-2")).isFalse();
        // 실패한 쪽의 퍼밋이 새지 않았다 — 셋 다 바로 잡힌다(샜다면 세 번째에서 1초 대기 뒤 429)
        assertTimeoutPreemptively(Duration.ofMillis(900), () -> {
            try (var a = r.acquirePermit(provider("local")); var b = r.acquirePermit(provider("local"));
                 var c = r.acquirePermit(provider("local"))) {
                assertThat(a).isNotNull();
            }
        });
    }

    @Test
    @DisplayName("다음 요청은 처음부터 살아 있는 서버로 간다 — 죽은 서버를 매번 먼저 부르던 것이 끝난다")
    void theNextRequestSkipsTheBlockedServer() {
        LlmRouter r = router("local", "local-2");
        run(r, false, stream("local", refused()));
        calls.clear();

        var served = run(r, false, stream("local", refused()));

        assertThat(calls).containsExactly("local-2");
        assertThat(served.provider().name()).isEqualTo("local-2");
    }

    @Test
    @DisplayName("연결 타임아웃도 서버가 떠 있지 않다는 뜻이다 — 차단하고 넘어간다")
    void aConnectTimeoutMovesOn() {
        LlmRouter r = router("local", "local-2");

        var served = run(r, false, stream("local",
                new RuntimeException("I/O error", new SocketTimeoutException("Connect timed out"))));

        assertThat(served.provider().name()).isEqualTo("local-2");
        assertThat(breaker.isBlocked("local")).isTrue();
    }

    @Test
    @DisplayName("HTTP 500 은 서버 장애다 — 폴백이 있으면 예전대로 30초 차단하고 넘어간다")
    void http500MovesOnAndBlocksLong() {
        LlmRouter r = router("local", "local-2");

        var served = run(r, false, stream("local", http(500, "{\"error\":\"boom\"}", new HttpHeaders())));

        assertThat(served.provider().name()).isEqualTo("local-2");
        assertThat(blockedSecondsFromNow("local")).as("폴백이 있으니 짧게 줄일 이유가 없다").isGreaterThan(10);
    }

    @Test
    @DisplayName("HTTP 503 + Retry-After — 서버가 말한 시간만큼 차단하고 넘어간다")
    void http503HonoursRetryAfter() {
        LlmRouter r = router("local", "local-2");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "7");

        var served = run(r, false, stream("local", http(503, "{\"error\":\"loading model\"}", headers)));

        assertThat(served.provider().name()).isEqualTo("local-2");
        assertThat(blockedSecondsFromNow("local")).isBetween(4L, 8L);
    }

    @Test
    @DisplayName("둘 다 죽었으면 날것의 연결 오류가 아니라 남은 시간이 담긴 소진 안내로 끝난다 — 둘 다 차단된다")
    void bothDownEndsInTheOutageMessage() {
        LlmRouter r = router("local", "local-2");

        assertThatThrownBy(() -> run(r, false, p -> {
            calls.add(p.name());
            throw refused();
        })).isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(calls).containsExactly("local", "local-2");
        assertThat(breaker.isBlocked("local")).isTrue();
        assertThat(breaker.isBlocked("local-2")).isTrue();
    }

    @Test
    @DisplayName("프로바이더가 하나뿐일 때의 연결 거부 — 소진 안내로 끝나고 짧게(몇 초) 차단한다(재시작이 곧 회복돼야 한다)")
    void aLoneProviderIsBlockedBriefly() {
        LlmRouter r = router("local");

        assertThatThrownBy(() -> run(r, false, stream("local", refused())))
                .isInstanceOf(LlmProviderExhaustedException.class);

        assertThat(blockedSecondsFromNow("local")).isLessThan(10);
        assertThat(breaker.consecutiveFailures("local")).as("스트리밍 연결 실패도 연속 실패로 센다 — 반복되면 '서버를 확인하라'").isEqualTo(1);
    }

    // ── 건드리지 않는다 ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 토큰이 나간 뒤에는 어떤 실패도 전환하지 않는다 — 다른 서버로 이어 붙이면 화면에 앞부분이 두 번 찍힌다")
    void afterTheFirstTokenNothingIsRetried() {
        LlmRouter r = router("local", "local-2");
        RuntimeException failure = refused();

        assertThatThrownBy(() -> run(r, true, stream("local", failure))).isSameAs(failure);

        assertThat(calls).containsExactly("local");
        assertThat(breaker.isBlocked("local")).isFalse();
    }

    @Test
    @DisplayName("읽기 타임아웃은 서버 탓이 아니다 — 차단도 전환도 없이 그대로 올린다")
    void aReadTimeoutIsLeftAlone() {
        LlmRouter r = router("local", "local-2");
        RuntimeException failure = new RuntimeException("I/O error", new SocketTimeoutException("Read timed out"));

        assertThatThrownBy(() -> run(r, false, stream("local", failure))).isSameAs(failure);

        assertThat(calls).containsExactly("local");
        assertThat(breaker.isBlocked("local")).isFalse();
    }

    @Test
    @DisplayName("사용자 중단은 서버 장애가 아니다 — 토큰 스트림이 인터럽트로 끊긴 모양은 차단하지 않는다")
    void aUserAbortIsNotAFailure() {
        LlmRouter r = router("local", "local-2");
        RuntimeException abort = new UncheckedIOException(new InterruptedIOException("토큰 스트림이 중단됐다 (중지/타임아웃)"));

        assertThatThrownBy(() -> run(r, false, stream("local", abort))).isSameAs(abort);

        assertThat(breaker.isBlocked("local")).isFalse();
        assertThat(calls).containsExactly("local");
    }

    @Test
    @DisplayName("스레드가 인터럽트된 상태의 연결 오류는 중단이다 — 중지를 누른 사용자의 대화를 막지 않는다")
    void anInterruptedThreadIsAnAbortEvenIfTheErrorLooksLikeAnOutage() {
        LlmRouter r = router("local", "local-2");
        try {
            assertThatThrownBy(() -> run(r, false, p -> {
                calls.add(p.name());
                Thread.currentThread().interrupt();   // 유휴 워치독·중지 버튼이 워커를 인터럽트했다
                throw refused();
            })).isInstanceOf(WebClientRequestException.class);

            assertThat(breaker.isBlocked("local")).isFalse();
            assertThat(calls).containsExactly("local");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("컨텍스트 초과는 요청 크기 문제다 — 호출부의 축소 재시도 몫이라 차단도 전환도 하지 않는다(5xx 로 와도)")
    void contextOverflowIsLeftToTheShrinkRetry() {
        LlmRouter r = router("local", "local-2");
        String body = "{\"error\":{\"message\":\"request (40016 tokens) exceeds the available context size (32768 tokens)\"}}";

        for (int status : new int[]{400, 500}) {
            calls.clear();
            RuntimeException failure = http(status, body, new HttpHeaders());

            assertThatThrownBy(() -> run(r, false, stream("local", failure))).isSameAs(failure);

            assertThat(calls).as("status %d", status).containsExactly("local");
            assertThat(breaker.isBlocked("local")).as("status %d", status).isFalse();
        }
    }

    @Test
    @DisplayName("4xx 는 요청 문제이고 알 수 없는 예외는 정체를 모른다 — 서버를 막지 않는다")
    void aRequestProblemOrAnUnknownErrorIsLeftAlone() {
        LlmRouter r = router("local", "local-2");

        for (RuntimeException failure : List.of(http(400, "{\"error\":\"bad request\"}", new HttpHeaders()),
                http(404, "{}", new HttpHeaders()), new IllegalStateException("boom"))) {
            calls.clear();

            assertThatThrownBy(() -> run(r, false, stream("local", failure))).isSameAs(failure);

            assertThat(calls).containsExactly("local");
            assertThat(breaker.isBlocked("local")).isFalse();
        }
    }

    @Test
    @DisplayName("용량 압박(429 + 퍼밋 대기 초과)은 서버 장애가 아니다 — 그대로 올리고 차단하지 않는다")
    void backpressureIsPropagatedUntouched() {
        LlmRouter r = router("local");
        LlmProvider only = provider("local");
        try (var a = r.acquirePermit(only); var b = r.acquirePermit(only); var c = r.acquirePermit(only)) {
            assertThat(a).isNotNull();

            assertThatThrownBy(() -> run(r, false, stream("never", refused())))
                    .isInstanceOf(LlmBackpressureException.class);

            assertThat(calls).as("스트림을 열지도 못했다").isEmpty();
            assertThat(breaker.isBlocked("local")).isFalse();
        }
    }

    @Test
    @DisplayName("차단이 걸리지 않아 같은 프로바이더가 또 나오면 거기서 멈춘다 — 무한히 돌지 않는다")
    void neverLoopsOnTheSameProvider() {
        LlmRouter mockRouter = mock(LlmRouter.class);
        LlmProvider only = provider("local");
        when(mockRouter.routeProvider(any(), any())).thenReturn(only);
        when(mockRouter.failOver(any(), any(), any(), any())).thenReturn(true);   // 차단했다고 하지만 같은 곳이 또 나온다
        RuntimeException failure = refused();

        assertThatThrownBy(() -> run(mockRouter, false, stream("local", failure))).isSameAs(failure);

        assertThat(calls).containsExactly("local");
    }
}
