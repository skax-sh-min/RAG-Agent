package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — VectorStoreRegistry.collectionName (B-08)
 *
 * Verifies Chroma constraints:
 *   - matches [a-zA-Z0-9._-]{3,63}
 *   - starts and ends with an alphanumeric
 */
class VectorStoreRegistryTest {

    private final VectorStoreRegistry registry = new VectorStoreRegistry(null, null);

    @Test
    @DisplayName("정상 버전: manual_<version> 형태 유지")
    void normalVersion() {
        assertThat(registry.collectionName("1.0")).isEqualTo("manual_1.0");
    }

    @Test
    @DisplayName("dot 가 그대로 유지됨 (예전 동작과 다른 점)")
    void dotsAreAllowed() {
        assertThat(registry.collectionName("v1.2.3")).matches("[a-zA-Z0-9._-]+");
    }

    @Test
    @DisplayName("허용되지 않는 문자(슬래시, 공백, 한글) → '_'")
    void replacesDisallowedChars() {
        String result = registry.collectionName("한글 / 버전");
        assertThat(result).matches("[a-zA-Z0-9._-]{3,63}");
        assertThat(result).startsWith("manual_");
    }

    @Test
    @DisplayName("63자 초과 → 자르고, 끝자리는 알파벳/숫자여야 함")
    void truncatesAndStripsTrailingNonAlnum() {
        String longVersion = "a".repeat(80) + "___";
        String result = registry.collectionName(longVersion);
        assertThat(result.length()).isLessThanOrEqualTo(63);
        assertThat(Character.isLetterOrDigit(result.charAt(result.length() - 1))).isTrue();
        assertThat(result).matches("[a-zA-Z0-9._-]{3,63}");
    }

    @Test
    @DisplayName("null / blank 버전 → fallback 'latest'")
    void nullVersionFallsBackToLatest() {
        assertThat(registry.collectionName(null)).isEqualTo("manual_latest");
        assertThat(registry.collectionName("")).isEqualTo("manual_latest");
    }

    @Test
    @DisplayName("trailing dot/underscore 제거")
    void trailingDotStripped() {
        assertThat(registry.collectionName("1.0.")).doesNotEndWith(".").doesNotEndWith("_");
    }
}
