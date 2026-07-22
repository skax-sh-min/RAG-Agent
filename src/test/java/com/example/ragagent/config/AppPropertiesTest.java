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
                null, null, null, null, null, null, null, null, null, null, null, null,
                sseIdleTimeoutSeconds, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AppProperties withAuth(AppProperties.AuthConfig auth) {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, auth, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AppProperties withMdCorrectionDefaultCodeLanguage(String lang) {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, lang, null, null, null, null, null);
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

    // ── authSafe() — §6.17 B안 ──────────────────────────────────────────────

    @Test
    @DisplayName("authSafe — auth==null 시 기본값(enabled=true, managementOnly=false)")
    void authSafe_nullAuth_defaultsToFullAuth() {
        AppProperties.AuthConfig cfg = withAuth(null).authSafe();

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.managementOnly()).isFalse();
    }

    @Test
    @DisplayName("authSafe — enabled=false, managementOnly=true는 그대로 통과")
    void authSafe_managementOnlyPassesThrough() {
        AppProperties.AuthConfig cfg = withAuth(new AppProperties.AuthConfig(false, true)).authSafe();

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.managementOnly()).isTrue();
    }

    @Test
    @DisplayName("authSafe — enabled=true인데 managementOnly=true(오설정)는 managementOnly=false로 정규화")
    void authSafe_misconfiguredEnabledAndManagementOnly_normalizesManagementOnlyToFalse() {
        AppProperties.AuthConfig cfg = withAuth(new AppProperties.AuthConfig(true, true)).authSafe();

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.managementOnly()).isFalse();
    }

    @Test
    @DisplayName("authSafe — enabled=false, managementOnly=false는 그대로 통과(plain no-auth)")
    void authSafe_plainNoAuthPassesThrough() {
        AppProperties.AuthConfig cfg = withAuth(new AppProperties.AuthConfig(false, false)).authSafe();

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.managementOnly()).isFalse();
    }

    // ── mdCorrectionDefaultCodeLanguageSafe() ────────────────────────────────

    @Test
    @DisplayName("mdCorrectionDefaultCodeLanguageSafe — 미설정(null) 시 java 기본값 사용")
    void mdCorrectionDefaultCodeLanguageSafe_nullDefaultsToJava() {
        assertThat(withMdCorrectionDefaultCodeLanguage(null).mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("java");
    }

    @Test
    @DisplayName("mdCorrectionDefaultCodeLanguageSafe — java/bash/sql 외의 값(오설정)은 java로 대체")
    void mdCorrectionDefaultCodeLanguageSafe_invalidValueDefaultsToJava() {
        assertThat(withMdCorrectionDefaultCodeLanguage("python").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("java");
        assertThat(withMdCorrectionDefaultCodeLanguage("").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("java");
        assertThat(withMdCorrectionDefaultCodeLanguage("   ").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("java");
    }

    @Test
    @DisplayName("mdCorrectionDefaultCodeLanguageSafe — java/bash/sql은 대소문자 무관하게 그대로 통과")
    void mdCorrectionDefaultCodeLanguageSafe_validValuesPassThroughCaseInsensitively() {
        assertThat(withMdCorrectionDefaultCodeLanguage("bash").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("bash");
        assertThat(withMdCorrectionDefaultCodeLanguage("SQL").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("sql");
        assertThat(withMdCorrectionDefaultCodeLanguage(" Java ").mdCorrectionDefaultCodeLanguageSafe()).isEqualTo("java");
    }
}
