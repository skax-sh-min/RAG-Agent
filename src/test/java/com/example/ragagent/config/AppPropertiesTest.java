package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AppProperties.sseTimeoutMs()/sseIdleTimeoutMs().
 * sseTimeoutMs() is an absolute hard ceiling (raised from 300s -> 900s -> 3600s as
 * sseIdleTimeoutMs() took over as the primary guard against a stuck SSE stream —
 * see OPERATOR_MANUAL.md "로컬 LLM 응답 타임아웃").
 */
class AppPropertiesTest {

    private static AppProperties withSseTimeouts(Integer sseTimeoutSeconds, Integer sseIdleTimeoutSeconds) {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3, sseTimeoutSeconds,
                null, null, null, null, null, null, null, null, null, null,
                sseIdleTimeoutSeconds);
    }

    @Test
    @DisplayName("sseTimeoutMs — 미설정(null) 시 3600초(3_600_000ms) 기본값 사용")
    void sseTimeoutMs_nullDefaultsTo3600Seconds() {
        assertThat(withSseTimeouts(null, null).sseTimeoutMs()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("sseTimeoutMs — 0 이하 값도 기본값(3600초)으로 대체")
    void sseTimeoutMs_nonPositiveDefaultsTo3600Seconds() {
        assertThat(withSseTimeouts(0, null).sseTimeoutMs()).isEqualTo(3_600_000L);
        assertThat(withSseTimeouts(-5, null).sseTimeoutMs()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("sseTimeoutMs — 양수 설정 시 그 값을 ms로 변환해 사용")
    void sseTimeoutMs_positiveValueIsRespected() {
        assertThat(withSseTimeouts(60, null).sseTimeoutMs()).isEqualTo(60_000L);
        assertThat(withSseTimeouts(1800, null).sseTimeoutMs()).isEqualTo(1_800_000L);
    }

    @Test
    @DisplayName("sseIdleTimeoutMs — 미설정(null) 시 120초(120_000ms) 기본값 사용")
    void sseIdleTimeoutMs_nullDefaultsTo120Seconds() {
        assertThat(withSseTimeouts(null, null).sseIdleTimeoutMs()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("sseIdleTimeoutMs — 0 이하 값도 기본값(120초)으로 대체")
    void sseIdleTimeoutMs_nonPositiveDefaultsTo120Seconds() {
        assertThat(withSseTimeouts(null, 0).sseIdleTimeoutMs()).isEqualTo(120_000L);
        assertThat(withSseTimeouts(null, -5).sseIdleTimeoutMs()).isEqualTo(120_000L);
    }

    @Test
    @DisplayName("sseIdleTimeoutMs — 양수 설정 시 그 값을 ms로 변환해 사용")
    void sseIdleTimeoutMs_positiveValueIsRespected() {
        assertThat(withSseTimeouts(null, 30).sseIdleTimeoutMs()).isEqualTo(30_000L);
        assertThat(withSseTimeouts(null, 300).sseIdleTimeoutMs()).isEqualTo(300_000L);
    }
}
