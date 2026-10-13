package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.21 E1 — LoadBalancingEmbeddingModel distributes embed calls across endpoints and
 * falls through to a single delegate transparently.
 */
class LoadBalancingEmbeddingModelTest {

    private EmbeddingModel countingDelegate(AtomicInteger counter) {
        EmbeddingModel m = mock(EmbeddingModel.class);
        when(m.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class))).thenAnswer(inv -> {
            counter.incrementAndGet();
            return new EmbeddingResponse(List.of());
        });
        return m;
    }

    @Test
    @DisplayName("생성자는 빈 델리게이트 목록을 거부한다")
    void rejectsEmptyDelegates() {
        assertThatThrownBy(() -> new LoadBalancingEmbeddingModel(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("단일 델리게이트면 모든 호출이 그 델리게이트로 간다")
    void singleDelegatePassthrough() {
        AtomicInteger c = new AtomicInteger();
        var lb = new LoadBalancingEmbeddingModel(List.of(countingDelegate(c)));
        for (int i = 0; i < 5; i++) lb.call(new EmbeddingRequest(List.of("t"), null));
        assertThat(c.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("직렬 호출도 round-robin tie-break으로 여러 엔드포인트에 고르게 분산된다")
    void serialCallsSpreadAcrossEndpoints() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        var lb = new LoadBalancingEmbeddingModel(List.of(countingDelegate(a), countingDelegate(b)));
        for (int i = 0; i < 10; i++) lb.call(new EmbeddingRequest(List.of("t"), null));
        // all-idle each call → rotating start splits evenly (never all to endpoint 0)
        assertThat(a.get()).isEqualTo(5);
        assertThat(b.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("동시 호출은 least-in-flight로 두 엔드포인트에 모두 도달한다")
    void concurrentCallsUseBothEndpoints() throws InterruptedException {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        // Delegates block until released, so calls genuinely overlap (in-flight > 0).
        EmbeddingModel slowA = blockingDelegate(a, release);
        EmbeddingModel slowB = blockingDelegate(b, release);
        var lb = new LoadBalancingEmbeddingModel(List.of(slowA, slowB));

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch started = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                started.countDown();
                lb.call(new EmbeddingRequest(List.of("t"), null));
            });
        }
        started.await(2, TimeUnit.SECONDS);
        Thread.sleep(100); // let calls enter the delegates and register in-flight
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(a.get() + b.get()).isEqualTo(n);
        assertThat(a.get()).isPositive(); // both endpoints actually used under concurrency
        assertThat(b.get()).isPositive();
    }

    private EmbeddingModel blockingDelegate(AtomicInteger counter, CountDownLatch release) {
        EmbeddingModel m = mock(EmbeddingModel.class);
        when(m.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class))).thenAnswer(inv -> {
            counter.incrementAndGet();
            release.await(5, TimeUnit.SECONDS);
            return new EmbeddingResponse(List.of());
        });
        return m;
    }

    @Test
    @DisplayName("dimensions()는 첫 엔드포인트로 위임한다")
    void dimensionsDelegatesToFirst() {
        EmbeddingModel first = mock(EmbeddingModel.class);
        when(first.dimensions()).thenReturn(768);
        EmbeddingModel second = mock(EmbeddingModel.class);
        var lb = new LoadBalancingEmbeddingModel(List.of(first, second));
        assertThat(lb.dimensions()).isEqualTo(768);
    }

    // ── 장애 전환 ──────────────────────────────────────────────────────────

    /** RestClient 가 연결 거부를 올리는 모양 그대로(OpenAiEmbeddingModel 의 재시도 뒤에도 같다). */
    private static RuntimeException connectionRefused() {
        return new ResourceAccessException("I/O error on POST request: Connection refused",
                new ConnectException("Connection refused"));
    }

    private EmbeddingModel failingDelegate(AtomicInteger counter, RuntimeException failure) {
        EmbeddingModel m = mock(EmbeddingModel.class);
        when(m.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class))).thenAnswer(inv -> {
            counter.incrementAndGet();
            throw failure;
        });
        when(m.dimensions()).thenThrow(failure);
        return m;
    }

    /** 테스트가 움직이는 시계(나노초). */
    private static final class FakeClock implements java.util.function.LongSupplier {
        long now = 1_000L;
        @Override public long getAsLong() { return now; }
        void advance(java.time.Duration d) { now += d.toNanos(); }
    }

    @Test
    @DisplayName("닿지 못한 엔드포인트는 같은 호출 안에서 다음 엔드포인트로 넘기고, 차단 동안은 다시 부르지 않는다")
    void unreachableEndpoint_failsOverAndIsSkippedWhileBlocked() {
        AtomicInteger dead = new AtomicInteger();
        AtomicInteger live = new AtomicInteger();
        var lb = new LoadBalancingEmbeddingModel(
                List.of(failingDelegate(dead, connectionRefused()), countingDelegate(live)),
                List.of("http://dead/v1", "http://live/v1"), new FakeClock());

        for (int i = 0; i < 6; i++) lb.call(new EmbeddingRequest(List.of("t"), null));

        assertThat(live.get()).isEqualTo(6);   // 모든 호출이 성공했다
        assertThat(dead.get()).isEqualTo(1);   // 죽은 쪽은 처음 한 번만 — 그다음부터는 차단돼 건너뛴다
    }

    @Test
    @DisplayName("차단이 풀리면 다시 시도하고, 살아났으면 다시 분산된다")
    void blockExpires_endpointIsRetried() {
        FakeClock clock = new FakeClock();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean aDown = new java.util.concurrent.atomic.AtomicBoolean(true);
        EmbeddingModel flaky = mock(EmbeddingModel.class);
        when(flaky.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class))).thenAnswer(inv -> {
            a.incrementAndGet();
            if (aDown.get()) throw connectionRefused();
            return new EmbeddingResponse(List.of());
        });
        var lb = new LoadBalancingEmbeddingModel(List.of(flaky, countingDelegate(b)), null, clock);

        for (int i = 0; i < 4; i++) lb.call(new EmbeddingRequest(List.of("t"), null));
        int attemptsWhileDown = a.get();
        assertThat(attemptsWhileDown).isEqualTo(1);

        aDown.set(false);
        clock.advance(LoadBalancingEmbeddingModel.BLOCK.plusSeconds(1));
        for (int i = 0; i < 4; i++) lb.call(new EmbeddingRequest(List.of("t"), null));

        assertThat(a.get() - attemptsWhileDown).isPositive(); // 풀린 뒤 다시 받는다
    }

    @Test
    @DisplayName("연결 계열이 아닌 실패(요청 모양·5xx 등)는 넘기지도 차단하지도 않고 그대로 던진다")
    void nonConnectionFailure_isRethrownWithoutFailover() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        // llama.cpp 가 배치보다 긴 입력을 거절하는 모양 — 서버는 멀쩡하다.
        RuntimeException tooLarge = new IllegalStateException("500 - input is too large to process");
        var lb = new LoadBalancingEmbeddingModel(
                List.of(failingDelegate(first, tooLarge), failingDelegate(second, tooLarge)), null, new FakeClock());

        assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null))).isSameAs(tooLarge);
        assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null))).isSameAs(tooLarge);
        // 넘기지 않았으므로 호출당 한 엔드포인트만, 차단하지 않았으므로 round-robin 으로 둘 다 한 번씩.
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("전부 닿지 않으면 첫 실패를 던지고, 그다음 호출은 엔드포인트 하나만 시도한다(전부 연결 타임아웃을 물지 않는다)")
    void allUnreachable_throwsAndThenTriesOnlyOnePerCall() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        RuntimeException refused = connectionRefused();
        var lb = new LoadBalancingEmbeddingModel(
                List.of(failingDelegate(a, refused), failingDelegate(b, refused)), null, new FakeClock());

        assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null))).isSameAs(refused);
        assertThat(a.get() + b.get()).isEqualTo(2); // 첫 호출은 둘 다 시도

        assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null))).isSameAs(refused);
        assertThat(a.get() + b.get()).isEqualTo(3); // 둘 다 차단된 뒤에는 가장 먼저 풀리는 하나만
    }

    @Test
    @DisplayName("전부 차단된 상태에서 불린 엔드포인트가 성공하면 그 자리에서 차단이 풀린다")
    void allBlocked_successfulProbeUnblocks() {
        AtomicInteger a = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        EmbeddingModel flakyA = mock(EmbeddingModel.class);
        when(flakyA.call(org.mockito.ArgumentMatchers.any(EmbeddingRequest.class))).thenAnswer(inv -> {
            a.incrementAndGet();
            if (down.get()) throw connectionRefused();
            return new EmbeddingResponse(List.of());
        });
        AtomicInteger b = new AtomicInteger();
        var lb = new LoadBalancingEmbeddingModel(
                List.of(flakyA, failingDelegate(b, connectionRefused())), null, new FakeClock());

        assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null)));
        down.set(false);
        lb.call(new EmbeddingRequest(List.of("t"), null)); // 둘 다 차단 — 먼저 차단된 A 를 한 번 불러 성공
        int before = a.get();
        lb.call(new EmbeddingRequest(List.of("t"), null)); // A 는 이제 열려 있고 B 는 차단 — A 로 간다
        assertThat(a.get()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("사용자 중단(인터럽트) 중의 실패는 장애로 세지 않는다")
    void interruptedCaller_isNotTreatedAsOutage() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        RuntimeException refused = connectionRefused();
        var lb = new LoadBalancingEmbeddingModel(
                List.of(failingDelegate(a, refused), countingDelegate(b)), null, new FakeClock());
        Thread.currentThread().interrupt();
        try {
            // round-robin 시작점이 0 이라 첫 호출은 A — 인터럽트 상태면 넘기지 않고 그대로 던진다
            assertThatThrownBy(() -> lb.call(new EmbeddingRequest(List.of("t"), null))).isSameAs(refused);
        } finally {
            Thread.interrupted();
        }
        assertThat(b.get()).isZero();
    }

    @Test
    @DisplayName("dimensions()도 닿지 않는 엔드포인트를 건너뛴다")
    void dimensions_skipsUnreachableEndpoint() {
        EmbeddingModel dead = failingDelegate(new AtomicInteger(), connectionRefused());
        EmbeddingModel live = mock(EmbeddingModel.class);
        when(live.dimensions()).thenReturn(1024);
        var lb = new LoadBalancingEmbeddingModel(List.of(dead, live), null, new FakeClock());
        assertThat(lb.dimensions()).isEqualTo(1024);
    }
}
