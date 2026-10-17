package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면으로 나가는 시각은 UTC 순간이다 — 서버는 시간대를 바꾸지 않고, 브라우저({@code layout/base.html} 의
 * {@code localizeTimes()})가 보는 사람의 시간대로 바꾼다. 여기서는 서버가 내보내는 두 값(기계용 {@code datetime}, JS 가
 * 돌지 않을 때의 글자)이 저장된 두 모양 어느 쪽에서든 같은 순간을 가리키는지 본다.
 */
class UtcTimeTest {

    @Test
    @DisplayName("iso — DB 스탬프(존 표기 없는 UTC)와 ISO 순간이 같은 ISO-8601 UTC 값이 된다(초 단위)")
    void isoReadsBothStoredShapes() {
        assertThat(UtcTime.iso("2026-10-13 23:30:05")).isEqualTo("2026-10-13T23:30:05Z");
        assertThat(UtcTime.iso(" 2026-10-13 00:00:00 ")).isEqualTo("2026-10-13T00:00:00Z");
        assertThat(UtcTime.iso("2026-10-13T23:30:05.123456789Z")).isEqualTo("2026-10-13T23:30:05Z");
    }

    @Test
    @DisplayName("iso — 비었거나 읽을 수 없으면 null(화면은 시각을 바꾸지 않는다)")
    void isoGivesUpOnUnreadableValues() {
        assertThat(UtcTime.iso((String) null)).isNull();
        assertThat(UtcTime.iso("")).isNull();
        assertThat(UtcTime.iso("어제")).isNull();
        assertThat(UtcTime.iso("2026-10-13T23:30:05")).as("존 없는 ISO 는 어느 시계인지 모른다").isNull();
    }

    @Test
    @DisplayName("fallback — JS 가 돌지 않으면 'UTC' 를 붙인 UTC 시각, 정밀도별 모양 · 읽을 수 없으면 원래 값")
    void fallbackSaysItIsUtc() {
        assertThat(UtcTime.fallback("2026-10-14 01:55:38", "second")).isEqualTo("2026-10-14 01:55:38 UTC");
        assertThat(UtcTime.fallback("2026-10-14T01:55:38.5Z", "minute")).isEqualTo("2026-10-14 01:55 UTC");
        assertThat(UtcTime.fallback("2026-10-14 01:55:38", "time")).isEqualTo("01:55:38 UTC");
        assertThat(UtcTime.fallback("2026-10-14 01:55:38", null)).as("기본은 초 단위").isEqualTo("2026-10-14 01:55:38 UTC");
        assertThat(UtcTime.fallback("어제", "second")).isEqualTo("어제");
        assertThat(UtcTime.fallback(null, "second")).isEmpty();
    }

    @Test
    @DisplayName("timeElement — 문구 안에 끼울 <time> 요소: 기계용 datetime + 정밀도 + UTC 글자")
    void timeElementCarriesTheInstant() {
        assertThat(UtcTime.timeElement(Instant.parse("2026-10-14T04:52:03.904Z"), "time"))
                .isEqualTo("<time class=\"utc-time\" datetime=\"2026-10-14T04:52:03Z\" data-precision=\"time\">"
                        + "04:52:03 UTC</time>");
        assertThat(UtcTime.timeElement(Instant.parse("2026-10-14T04:52:03Z"), "<script>"))
                .as("모르는 정밀도는 second 로 — 속성에 들어가는 값은 정해진 셋뿐이다")
                .contains("data-precision=\"second\"").doesNotContain("<script>");
        assertThat(UtcTime.timeElement(null, "time")).isEmpty();
    }
}
