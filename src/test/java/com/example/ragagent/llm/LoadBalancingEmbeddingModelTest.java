package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

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
}
