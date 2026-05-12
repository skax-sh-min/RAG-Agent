package com.example.ragagent.llm;

import org.junit.jupiter.api.Disabled;
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
 *  - B-05 Retry-After negative / zero / malformed value handling
 *  - B-06 isBlocked race when entry expires concurrently with new block()
 *  - Normal block/expiry flow
 */
class CircuitBreakerTest {

    @Test
    @DisplayName("정상 시나리오: block 후 isBlocked=true, default duration 동안 유지")
    void blocksForDefaultDuration() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", null);
        assertThat(cb.isBlocked("p1")).isTrue();
    }

    @Test
    @DisplayName("Retry-After 0/음수: 즉시 만료가 되어선 안 됨 (B-05)")
    void negativeRetryAfterShouldNotBypassBlock() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", "-1");
        // 현재 구현은 이 케이스를 즉시 해제로 처리 → 실패해야 정상(회귀 방지용 테스트).
        assertThat(cb.isBlocked("p1"))
                .as("Retry-After=-1 일 때 즉시 해제되면 안 됨 (B-05)")
                .isTrue();
    }

    @Test
    @DisplayName("Retry-After 가 잘못된 포맷이면 default duration 으로 폴백")
    void malformedRetryAfterFallsBackToDefault() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", "not-a-number");
        assertThat(cb.isBlocked("p1")).isTrue();
    }

    @Test
    @DisplayName("Retry-After HTTP-Date 가 이미 과거이면 default duration 폴백")
    void pastHttpDateFallsBackToDefault() {
        CircuitBreaker cb = new CircuitBreaker(2);
        String past = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().minus(1, ChronoUnit.HOURS).atZone(java.time.ZoneOffset.UTC));
        cb.block("p1", past);
        // B-05: 현재 구현은 Math.max(1, …) 만 적용 → 1초 후 해제됨. default 분 단위로 유지되어야 함.
        assertThat(cb.isBlocked("p1"))
                .as("과거 HTTP-Date 인 경우 default duration 적용되어야 함 (B-05)")
                .isTrue();
    }

    @Test
    @Disabled("B-09: 현재 구현은 live map 반환. 안전망 구축 완료 후 fix 또는 기대값 조정 예정 — refactoring/01-test-safety-net.md")
    @DisplayName("getBlockedProviders 는 스냅샷이어야 함 (B-09)")
    void getBlockedProvidersReturnsSnapshot() {
        CircuitBreaker cb = new CircuitBreaker(2);
        cb.block("p1", null);
        var snapshot = cb.getBlockedProviders();
        cb.block("p2", null);
        // B-09: 현재 구현은 라이브 맵이라 snapshot.containsKey("p2") == true
        assertThat(snapshot.containsKey("p2"))
                .as("반환된 맵은 스냅샷이어야 하며, 이후 block() 영향을 받으면 안 됨 (B-09)")
                .isFalse();
    }

    @Test
    @DisplayName("동시 block 과 isBlocked 가 일관된 상태 유지 (B-06)")
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
                .as("block() 직후 항상 isBlocked=true 가 보장되어야 함 (B-06)")
                .isZero();
    }
}
