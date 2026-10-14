package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /settings} 생각 수준 카드의 계산 시각은 다른 관리자 화면처럼 KST 다. JVM 기본 시간대로 찍던 동안 TZ 설정 없는
 * 컨테이너(UTC)에서는 9시간 이른 시각이 나왔다.
 */
class ThinkingPreviewClockTest {

    @Test
    @DisplayName("계산 시각은 KST 의 시:분:초 — JVM 기본 시간대와 무관하다")
    void computedAtIsKst() {
        ThinkingPreview preview = new ThinkingPreview(Instant.parse("2026-10-14T04:52:03Z"), null, List.of());

        assertThat(preview.computedAtText()).isEqualTo("13:52:03");
    }
}
