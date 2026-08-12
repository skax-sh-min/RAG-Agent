package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — BackgroundLlmConcurrencyTracker: plain increment/decrement counter folded into the header's
 * LLM concurrency indicator (see OperationsController.getLlmConcurrency()).
 */
class BackgroundLlmConcurrencyTrackerTest {

    @Test
    @DisplayName("초기값은 0이다")
    void startsAtZero() {
        assertThat(new BackgroundLlmConcurrencyTracker().get()).isEqualTo(0);
    }

    @Test
    @DisplayName("increment/decrement 가 정확히 반영된다")
    void incrementsAndDecrements() {
        var tracker = new BackgroundLlmConcurrencyTracker();

        tracker.increment();
        tracker.increment();
        assertThat(tracker.get()).isEqualTo(2);

        tracker.decrement();
        assertThat(tracker.get()).isEqualTo(1);

        tracker.decrement();
        assertThat(tracker.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("decrement 가 increment 보다 많아도 음수가 아니라 0으로 바닥을 잡는다")
    void neverGoesNegative() {
        var tracker = new BackgroundLlmConcurrencyTracker();

        tracker.decrement();
        tracker.decrement();

        assertThat(tracker.get()).isEqualTo(0);
    }
}
