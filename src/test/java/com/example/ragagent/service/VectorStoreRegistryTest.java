package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — VectorStoreRegistry.collectionName (B-08)
 *
 * Verifies Chroma constraints:
 *   - format: u_{userId8}_{version}
 *   - matches [a-zA-Z0-9._-]{3,63}
 *   - starts and ends with an alphanumeric
 */
class VectorStoreRegistryTest {

    private final VectorStoreRegistry registry = new VectorStoreRegistry(null, null);

    @Test
    @DisplayName("정상 버전: u_{userId8}_{version} 형태")
    void normalVersion() {
        assertThat(registry.collectionName("user1", "1.0")).isEqualTo("u_user1_1.0");
    }

    @Test
    @DisplayName("dot 가 그대로 유지됨")
    void dotsAreAllowed() {
        assertThat(registry.collectionName("user1", "v1.2.3")).matches("[a-zA-Z0-9._-]+");
    }

    @Test
    @DisplayName("허용되지 않는 문자(슬래시, 공백, 한글) → Chroma 규칙 통과")
    void replacesDisallowedChars() {
        String result = registry.collectionName("user1", "한글 / 버전");
        // version is all non-alphanumeric → stripped → result is just the userId prefix
        assertThat(result).matches("[a-zA-Z0-9._-]{3,63}");
        assertThat(result).startsWith("u_user1");
        assertThat(Character.isLetterOrDigit(result.charAt(result.length() - 1))).isTrue();
    }

    @Test
    @DisplayName("63자 초과 → 자르고, 끝자리는 알파벳/숫자여야 함")
    void truncatesAndStripsTrailingNonAlnum() {
        String longVersion = "a".repeat(80) + "___";
        String result = registry.collectionName("user1", longVersion);
        assertThat(result.length()).isLessThanOrEqualTo(63);
        assertThat(Character.isLetterOrDigit(result.charAt(result.length() - 1))).isTrue();
        assertThat(result).matches("[a-zA-Z0-9._-]{3,63}");
    }

    @Test
    @DisplayName("null / blank 버전 → fallback 'latest'")
    void nullVersionFallsBackToLatest() {
        assertThat(registry.collectionName("user1", null)).isEqualTo("u_user1_latest");
        assertThat(registry.collectionName("user1", "")).isEqualTo("u_user1_latest");
    }

    @Test
    @DisplayName("trailing dot/underscore 제거")
    void trailingDotStripped() {
        assertThat(registry.collectionName("user1", "1.0.")).doesNotEndWith(".").doesNotEndWith("_");
    }

    @Test
    @DisplayName("UUID userId → 8자리 알파뉴메릭만 추출")
    void uuidUserIdPrefix() {
        // "abc12345-6789-..." → remove hyphens → "abc123456789..." → take 8 → "abc12345"
        assertThat(registry.collectionName("abc12345-6789-def0-1234", "latest"))
                .isEqualTo("u_abc12345_latest");
    }

    @Test
    @DisplayName("anonymous userId → u_anonymou_latest")
    void anonymousUserId() {
        assertThat(registry.collectionName("anonymous", "latest"))
                .isEqualTo("u_anonymou_latest");
    }
}
