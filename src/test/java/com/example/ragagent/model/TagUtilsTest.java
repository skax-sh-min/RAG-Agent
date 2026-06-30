package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Step 5.9 — 태그 정규화/검증/매칭 단위 테스트. */
class TagUtilsTest {

    @Test
    @DisplayName("normalize: 소문자·trim·중복 제거·공백 제거, 순서 보존")
    void normalize_cleans() {
        assertThat(TagUtils.normalize(List.of(" Spring ", "SPRING", "  ", "Boot")))
                .containsExactly("spring", "boot");
        assertThat(TagUtils.normalize(null)).isEmpty();
        assertThat(TagUtils.normalize(List.of())).isEmpty();
    }

    @Test
    @DisplayName("normalize: 태그 10개 초과 → 예외")
    void normalize_tooMany_throws() {
        List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "t" + i).toList();
        assertThatThrownBy(() -> TagUtils.normalize(eleven))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("normalize: 태그 길이 32자 초과 → 예외")
    void normalize_tooLong_throws() {
        String big = "x".repeat(33);
        assertThatThrownBy(() -> TagUtils.normalize(List.of(big)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("parseCsv: 쉼표 분리 + 정규화")
    void parseCsv() {
        assertThat(TagUtils.parseCsv("Spring, boot ,spring"))
                .containsExactly("spring", "boot");
        assertThat(TagUtils.parseCsv(null)).isEmpty();
        assertThat(TagUtils.parseCsv("   ")).isEmpty();
    }

    @Test
    @DisplayName("parseTagList: String csv / JSON 배열 문자열 / Collection / null 모두 방어")
    void parseTagList_defensive() {
        assertThat(TagUtils.parseTagList("a,b,A")).containsExactly("a", "b");
        assertThat(TagUtils.parseTagList("[\"a\", \"b\"]")).containsExactly("a", "b");
        assertThat(TagUtils.parseTagList(List.of("A", "b", "B"))).containsExactly("a", "b");
        assertThat(TagUtils.parseTagList(null)).isEmpty();
        assertThat(TagUtils.parseTagList("")).isEmpty();
    }

    @Test
    @DisplayName("matchesAnd: 선택 태그가 모두 포함될 때만 true")
    void matchesAnd() {
        assertThat(TagUtils.matchesAnd(List.of("a", "b", "c"), List.of("a", "b"))).isTrue();
        assertThat(TagUtils.matchesAnd(List.of("a"), List.of("a", "b"))).isFalse();
        assertThat(TagUtils.matchesAnd(List.of("a"), List.of())).isTrue();      // 빈 선택 = 통과
        assertThat(TagUtils.matchesAnd(List.of(), List.of("a"))).isFalse();     // 빈 청크 + 선택 = 불통
        assertThat(TagUtils.matchesAnd(null, List.of("a"))).isFalse();
    }

    @Test
    @DisplayName("toMetaValue: 정규화 후 쉼표 결합")
    void toMetaValue() {
        assertThat(TagUtils.toMetaValue(List.of("A", "b", "a"))).isEqualTo("a,b");
        assertThat(TagUtils.toMetaValue(null)).isEmpty();
    }
}
