package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link BackgroundUsage}'s prefix/label helpers used to merge category cards. */
class BackgroundUsageTest {

    @Test
    @DisplayName("isBackground — 알려진 prefix로 시작하는 이름만 true")
    void isBackground_matchesKnownPrefixesOnly() {
        assertThat(BackgroundUsage.isBackground("title:local")).isTrue();
        assertThat(BackgroundUsage.isBackground("summary:local")).isTrue();
        assertThat(BackgroundUsage.isBackground("local")).isFalse();
        assertThat(BackgroundUsage.isBackground("embed:nomic")).isFalse();
    }

    @Test
    @DisplayName("prefixes — 모든 알려진 prefix를 콜론 포함으로 반환")
    void prefixes_includesAllKnownPrefixesWithColon() {
        assertThat(BackgroundUsage.prefixes()).contains(
                "summary:", "keyword:", "mdcorrect:", "txt2md:", "title:", "image:", "context:");
    }

    @Test
    @DisplayName("label — 끝의 콜론만 제거한다")
    void label_stripsTrailingColonOnly() {
        assertThat(BackgroundUsage.label("title:")).isEqualTo("title");
        assertThat(BackgroundUsage.label("context:")).isEqualTo("context");
    }

    @Test
    @DisplayName("label — 콜론이 없는 입력은 그대로 반환")
    void label_noColonReturnsUnchanged() {
        assertThat(BackgroundUsage.label("title")).isEqualTo("title");
    }
}
