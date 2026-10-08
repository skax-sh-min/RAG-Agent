package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * § 컨텍스트 초과 시 창 자동 재탐지 — {@link ProbingContextWindowRefresher}.
 *
 * <p>고정하려는 것은 넷이다: 선언값은 덮지 않는다 · 탐지 실패 시 기존 값을 지우지 않는다 ·
 * 프로바이더마다 디바운스한다 · 호출자를 막지 않는다.
 */
class ProbingContextWindowRefresherTest {

    /** 테스트가 시간을 앞으로 미는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-28T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static LlmProvider provider(String name) {
        return new LlmProvider(name, TaskType.TEXT, ProviderRole.LOCAL, 1,
                "", "http://127.0.0.1:1234/v1", "gemma-3", true, null, null);
    }

    /** 탐지 호출을 기록하고 정해진 답을 주는 가짜. */
    private static final class FakeProber implements ProbingContextWindowRefresher.Prober {
        final List<String> calls = new ArrayList<>();
        Optional<Integer> answer = Optional.of(8192);
        @Override public Optional<Integer> probe(String apiBase, String model, int c, int r) {
            calls.add(apiBase + "|" + model);
            return answer;
        }
    }

    /** 즉시 실행 — 가상 스레드 대신 호출 스레드에서 돌려 단언을 결정적으로 만든다. */
    private static final java.util.function.Consumer<Runnable> INLINE = Runnable::run;

    @Test
    @DisplayName("탐지한 창이 기록된 값과 다르면 PROBED 로 고쳐 쓴다")
    void probedWindowReplacesAStaleOne() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 32768, ProviderContextWindows.Source.PROBED);
        FakeProber prober = new FakeProber();
        prober.answer = Optional.of(8192);

        new ProbingContextWindowRefresher(windows, 2, 5, prober, INLINE, new MovableClock())
                .refreshAfterOverflow(provider("local"));

        assertThat(windows.tokensOrZero("local")).isEqualTo(8192);
        assertThat(windows.find("local")).get()
                .extracting(ProviderContextWindows.ContextWindow::source)
                .isEqualTo(ProviderContextWindows.Source.PROBED);
        assertThat(prober.calls).containsExactly("http://127.0.0.1:1234/v1|gemma-3");
    }

    @Test
    @DisplayName("설정에 선언된(CONFIGURED) 창은 덮지 않는다 — 탐지조차 하지 않는다")
    void configuredWindowIsNeverOverwritten() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 32768, ProviderContextWindows.Source.CONFIGURED);
        FakeProber prober = new FakeProber();

        new ProbingContextWindowRefresher(windows, 2, 5, prober, INLINE, new MovableClock())
                .refreshAfterOverflow(provider("local"));

        assertThat(prober.calls).as("운영자의 선언을 탐지가 조용히 뒤집으면 설정과 실제가 갈라진다").isEmpty();
        assertThat(windows.tokensOrZero("local")).isEqualTo(32768);
    }

    @Test
    @DisplayName("탐지에 실패하면 기존 값을 그대로 둔다 — 지우면 사전 축소가 꺼져 초과가 더 잦아진다")
    void failedProbeKeepsThePreviousWindow() {
        ProviderContextWindows windows = new ProviderContextWindows();
        windows.record("local", 32768, ProviderContextWindows.Source.PROBED);
        FakeProber prober = new FakeProber();
        prober.answer = Optional.empty();

        new ProbingContextWindowRefresher(windows, 2, 5, prober, INLINE, new MovableClock())
                .refreshAfterOverflow(provider("local"));

        assertThat(windows.tokensOrZero("local")).isEqualTo(32768);
    }

    @Test
    @DisplayName("연달아 온 초과는 한 번만 탐지한다 — 이미 아픈 서버에 HTTP 를 더 얹지 않는다")
    void repeatedOverflowsAreDebounced() {
        ProviderContextWindows windows = new ProviderContextWindows();
        FakeProber prober = new FakeProber();
        MovableClock clock = new MovableClock();
        var refresher = new ProbingContextWindowRefresher(windows, 2, 5, prober, INLINE, clock);

        refresher.refreshAfterOverflow(provider("local"));
        refresher.refreshAfterOverflow(provider("local"));
        refresher.refreshAfterOverflow(provider("local"));
        assertThat(prober.calls).hasSize(1);

        // 다른 프로바이더는 자기 디바운스를 갖는다.
        refresher.refreshAfterOverflow(provider("local-2"));
        assertThat(prober.calls).hasSize(2);

        // 창이 지나면 다시 탐지한다 — 서버가 그사이 또 바뀌었을 수 있다.
        clock.advance(ProbingContextWindowRefresher.DEBOUNCE.plusSeconds(1));
        refresher.refreshAfterOverflow(provider("local"));
        assertThat(prober.calls).hasSize(3);
    }

    @Test
    @DisplayName("탐지가 던져도 호출자에게 전파되지 않는다 — 자가 치유는 실패해도 아무것도 나빠지지 않는다")
    void probeFailureNeverPropagates() {
        ProviderContextWindows windows = new ProviderContextWindows();
        ProbingContextWindowRefresher.Prober boom = (a, m, c, r) -> {
            throw new IllegalStateException("probe exploded");
        };

        var refresher = new ProbingContextWindowRefresher(windows, 2, 5, boom, INLINE, new MovableClock());

        refresher.refreshAfterOverflow(provider("local"));   // 던지지 않아야 한다
        assertThat(windows.find("local")).isEmpty();
    }

    @Test
    @DisplayName("baseUrl 이 없으면 아무 일도 하지 않는다")
    void missingBaseUrlIsIgnored() {
        FakeProber prober = new FakeProber();
        var refresher = new ProbingContextWindowRefresher(
                new ProviderContextWindows(), 2, 5, prober, INLINE, new MovableClock());

        refresher.refreshAfterOverflow(new LlmProvider("local", TaskType.TEXT, ProviderRole.LOCAL, 1,
                "", "  ", "gemma-3", true, null, null));
        refresher.refreshAfterOverflow(null);

        assertThat(prober.calls).isEmpty();
    }

    @Test
    @DisplayName("LOCAL 이 아닌 프로바이더는 묻지 않는다 — 클라우드에는 /props·/api/v0/models 가 없다")
    void nonLocalProvidersAreNotProbed() {
        FakeProber prober = new FakeProber();
        var refresher = new ProbingContextWindowRefresher(
                new ProviderContextWindows(), 2, 5, prober, INLINE, new MovableClock());

        refresher.refreshAfterOverflow(new LlmProvider("cloud", TaskType.TEXT, ProviderRole.PREMIUM, 2,
                "k", "https://api.example.com/v1", "big-model", true, null, null));

        assertThat(prober.calls).isEmpty();
    }

    @Test
    @DisplayName("NOOP 은 아무것도 하지 않는다 — 라우터의 구 생성자와 테스트가 받는 기본값")
    void noopDoesNothing() {
        AtomicInteger unused = new AtomicInteger();
        ContextWindowRefresher.NOOP.refreshAfterOverflow(provider("local"));
        assertThat(unused).hasValue(0);
    }
}
