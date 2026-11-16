package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest {

    private static AppVersion v(String s) {
        return AppVersion.parse(s).orElseThrow(() -> new AssertionError("파싱 실패: " + s));
    }

    @Test
    @DisplayName("접두 v 와 빌드 메타데이터는 떼고 읽는다")
    void normalizesPrefixAndMetadata() {
        assertThat(v("v0.5.7").text()).isEqualTo("0.5.7");
        assertThat(v("V1.2.3+abc123").text()).isEqualTo("1.2.3");
        assertThat(v(" 0.5.6-SNAPSHOT ").text()).isEqualTo("0.5.6-SNAPSHOT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "nightly", "2026-11-16", "v", "1.2.3.4.5", "1..2", "a.b.c", "1.2.3-", "1.2.3.x",
            "99999999999.1", "7", "v7", "2026-11-16-rc1"})
    @DisplayName("읽을 수 없는 모양은 비워 돌려준다 — 추측해서 '새 버전'으로 알리지 않는다")
    void unparsable(String raw) {
        assertThat(AppVersion.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("null 도 비어 있다")
    void nullIsEmpty() {
        assertThat(AppVersion.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("숫자는 문자열이 아니라 값으로 비교한다 — 0.10.0 은 0.9.0 보다 새롭다")
    void numericComparison() {
        assertThat(v("0.10.0").isNewerThan(v("0.9.0"))).isTrue();
        assertThat(v("1.0.0").isNewerThan(v("0.99.99"))).isTrue();
        assertThat(v("0.5.7").isNewerThan(v("0.5.6"))).isTrue();
        assertThat(v("0.5.6").isNewerThan(v("0.5.7"))).isFalse();
    }

    @Test
    @DisplayName("자리 수가 달라도 모자란 자리는 0 으로 본다 — 0.5 == 0.5.0")
    void paddedWithZeros() {
        assertThat(v("0.5")).isEqualTo(v("0.5.0"));
        assertThat(v("0.5").hashCode()).isEqualTo(v("0.5.0").hashCode());
        assertThat(v("0.5.1").isNewerThan(v("0.5"))).isTrue();
    }

    @Test
    @DisplayName("같은 번호에서 정식이 스냅샷보다 새롭다 — 0.5.6-SNAPSHOT 에서 0.5.6 릴리즈는 업데이트다")
    void releaseBeatsSnapshot() {
        assertThat(v("0.5.6").isNewerThan(v("0.5.6-SNAPSHOT"))).isTrue();
        assertThat(v("0.5.6-SNAPSHOT").isNewerThan(v("0.5.6"))).isFalse();
        assertThat(v("0.5.7-SNAPSHOT").isNewerThan(v("0.5.6"))).isTrue();   // 번호가 앞서면 스냅샷이어도 새롭다
    }

    @Test
    @DisplayName("한정자끼리: SNAPSHOT 이 가장 낮고, 나머지는 사전순(대소문자 무시)")
    void qualifierOrdering() {
        assertThat(v("1.0.0-rc.1").isNewerThan(v("1.0.0-SNAPSHOT"))).isTrue();
        assertThat(v("1.0.0-rc.2").isNewerThan(v("1.0.0-rc.1"))).isTrue();
        assertThat(v("1.0.0-RC.1")).isEqualTo(v("1.0.0-rc.1"));
        assertThat(v("1.0.0").isNewerThan(v("1.0.0-rc.9"))).isTrue();
    }
}
