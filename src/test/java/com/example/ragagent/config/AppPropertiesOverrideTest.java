package com.example.ragagent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime settings-override layer wired into the hot-editable {@code xxxSafe()}
 * accessors. Verifies override precedence (override wins over property default), clamping still
 * applies on top of an override, and malformed/unset overrides fall back to the property value.
 *
 * <p>The bound {@link AppProperties.OverrideSource} is process-wide static, so every test unbinds
 * it afterwards to stay isolated from the rest of the suite.
 */
class AppPropertiesOverrideTest {

    /** Base props: similarity=0.0, mq-min-len=5, retry-escalate=true, cand-mult=3, tag-mult=2, rrf-weight=1.0, rrf-k=60. */
    private static AppProperties base() {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null);
    }

    private final Map<String, String> overrides = new HashMap<>();

    private void bind() {
        AppProperties.bindOverrides(overrides::get);
    }

    @AfterEach
    void unbind() {
        AppProperties.unbindOverrides();
    }

    @Test
    @DisplayName("소스 미바인딩 시 프로퍼티 기본값을 그대로 반환 (회귀 0)")
    void noSource_returnsPropertyDefaults() {
        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(0.0);
        assertThat(p.searchRrfKSafe()).isEqualTo(60);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(1.0);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(3);
        assertThat(p.searchTagCandidateMultiplierSafe()).isEqualTo(2);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(5);
        assertThat(p.searchRetryEscalateSafe()).isTrue();
    }

    @Test
    @DisplayName("오버라이드가 있으면 각 hot 접근자가 오버라이드 값을 반환")
    void override_winsOverPropertyDefault() {
        bind();
        overrides.put(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "0.42");
        overrides.put(SettingsKeys.SEARCH_RRF_K, "80");
        overrides.put(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "2.5");
        overrides.put(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER, "5");
        overrides.put(SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER, "4");
        overrides.put(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH, "12");
        overrides.put(SettingsKeys.SEARCH_RETRY_ESCALATE, "false");

        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(0.42);
        assertThat(p.searchRrfKSafe()).isEqualTo(80);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(2.5);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(5);
        assertThat(p.searchTagCandidateMultiplierSafe()).isEqualTo(4);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(12);
        assertThat(p.searchRetryEscalateSafe()).isFalse();
    }

    @Test
    @DisplayName("오버라이드 위에도 클램핑이 그대로 적용된다")
    void override_isStillClamped() {
        bind();
        overrides.put(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "1.5"); // > 1.0 → 1.0
        overrides.put(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER, "0");   // < 1 → 1
        overrides.put(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH, "-3"); // < 0 → 0

        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(1.0);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(1);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(0);
    }

    @Test
    @DisplayName("파싱 불가/공백 오버라이드는 무시하고 프로퍼티 기본값으로 폴백")
    void malformedOrBlankOverride_fallsBackToDefault() {
        bind();
        overrides.put(SettingsKeys.SEARCH_RRF_K, "not-a-number");
        overrides.put(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "   ");
        overrides.put(SettingsKeys.SEARCH_RETRY_ESCALATE, "maybe");

        AppProperties p = base();
        assertThat(p.searchRrfKSafe()).isEqualTo(60);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(1.0);
        assertThat(p.searchRetryEscalateSafe()).isTrue();
    }
}
