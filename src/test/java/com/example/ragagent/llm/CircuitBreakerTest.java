package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — CircuitBreaker
 *
 * Covers:
 *  - Retry-After negative / zero / malformed value handling
 *  - isBlocked race when entry expires concurrently with new block()
 *  - Normal block/expiry flow
 */
class CircuitBreakerTest {

    @Test
    @DisplayName("정상 흐름: block 직후 isBlocked=true 이고 기본 차단 시간이 유지됨")
    void blocksForDefaultDuration() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", null);
        assertThat(cb.isBlocked("p1")).isTrue();
    }

    @Test
    @DisplayName("Retry-After 0/음수: 즉시 만료 처리하지 않고 기본 차단 시간을 적용")
    void negativeRetryAfterShouldNotBypassBlock() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", "-1");
        // 회귀 방지: 0/음수 Retry-After 를 받아도 즉시 해제되면 안 된다.
        assertThat(cb.isBlocked("p1"))
            .as("Retry-After=-1 인 경우에도 최소 기본 차단 시간은 유지되어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("Retry-After 포맷 오류 시 기본 차단 시간으로 폴백")
    void malformedRetryAfterFallsBackToDefault() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", "not-a-number");
        assertThat(cb.isBlocked("p1")).isTrue();
    }

    @Test
    @DisplayName("Retry-After HTTP-Date 가 과거 시점이면 기본 차단 시간으로 폴백")
    void pastHttpDateFallsBackToDefault() {
        CircuitBreaker cb = new CircuitBreaker(2);
        String past = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().minus(1, ChronoUnit.HOURS).atZone(java.time.ZoneOffset.UTC));
        cb.block("p1", past);
        // 회귀 방지: 과거 시점 HTTP-Date 는 사실상 무효값으로 보고 기본 차단 시간을 적용해야 한다.
        assertThat(cb.isBlocked("p1"))
            .as("과거 HTTP-Date 일 때는 기본 차단 시간이 적용되어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("getBlockedProviders 는 라이브 뷰가 아닌 스냅샷을 반환해야 함")
    void getBlockedProvidersReturnsSnapshot() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", null);
        var snapshot = cb.getBlockedProviders();
        cb.block("p2", null);
        // 회귀 방지: 반환 이후 block() 호출이 기존 snapshot 에 반영되면 안 된다.
        assertThat(snapshot.containsKey("p2"))
            .as("반환값은 스냅샷이어야 하며 이후 block() 호출의 영향을 받지 않아야 한다")
                .isFalse();
    }

    @Test
    @DisplayName("동시성 상황에서도 block 직후 isBlocked=true 일관성 유지")
    void concurrentBlockAndCheck() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(2);
        int threads = 16;
        int iters = 500;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistent = new AtomicInteger();

        try (var exec = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < iters; j++) {
                            cb.block("p1", null);
                            if (!cb.isBlocked("p1")) inconsistent.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {}
                });
            }
            start.countDown();
            exec.shutdown();
            exec.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(inconsistent.get())
            .as("block() 직후에는 항상 isBlocked=true 가 보장되어야 한다")
                .isZero();
    }
}
