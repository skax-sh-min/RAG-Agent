package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 출처 목록 표시 순서 — 1순위 응답 참여도, 2순위 유사도.
 *
 * <p>chat-stream.js의 {@code compareSourceOrder()}가 스트리밍 경로에서 같은 규칙을 다시 구현하므로
 * (출처가 답변보다 먼저 도착해 참여도가 그 시점에 없다), 규칙을 바꿀 때는 양쪽을 함께 고쳐야 한다.
 */
class SourceRefDisplayOrderTest {

    private static SourceRef ref(String label, Double similarity, Double answerShare) {
        return new SourceRef(label, "preview", label, "d1", null,
                similarity, null, null, answerShare, null, false);
    }

    private static List<String> labels(List<SourceRef> sources) {
        return sources.stream().map(SourceRef::label).toList();
    }

    @Test
    @DisplayName("응답 참여도가 유사도보다 우선한다")
    void answerShareBeatsSimilarity() {
        List<SourceRef> sorted = SourceRef.sortedForDisplay(List.of(
                ref("높은유사도", 0.91, 0.10),
                ref("높은참여도", 0.42, 0.70)));
        assertThat(labels(sorted)).containsExactly("높은참여도", "높은유사도");
    }

    @Test
    @DisplayName("참여도가 같으면 유사도 내림차순")
    void similarityBreaksTie() {
        List<SourceRef> sorted = SourceRef.sortedForDisplay(List.of(
                ref("낮음", 0.30, 0.25),
                ref("높음", 0.80, 0.25),
                ref("중간", 0.55, 0.25)));
        assertThat(labels(sorted)).containsExactly("높음", "중간", "낮음");
    }

    @Test
    @DisplayName("측정되지 않은 값(null)은 뒤로 간다 — 0으로 취급하지 않는다")
    void nullsGoLast() {
        List<SourceRef> sorted = SourceRef.sortedForDisplay(List.of(
                ref("참여도없음-유사도있음", 0.90, null),
                ref("둘다없음", null, null),
                ref("참여도있음", 0.10, 0.05)));
        assertThat(labels(sorted))
                .containsExactly("참여도있음", "참여도없음-유사도있음", "둘다없음");
    }

    @Test
    @DisplayName("완전 동률이면 원래(검색) 순서를 유지한다")
    void stableOnFullTie() {
        List<SourceRef> sorted = SourceRef.sortedForDisplay(List.of(
                ref("첫째", null, null),
                ref("둘째", null, null),
                ref("셋째", null, null)));
        assertThat(labels(sorted)).containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    @DisplayName("null/단일 목록은 그대로 돌려준다")
    void degenerateInputs() {
        assertThat(SourceRef.sortedForDisplay(null)).isNull();
        assertThat(SourceRef.sortedForDisplay(List.of())).isEmpty();
        List<SourceRef> one = List.of(ref("하나", 0.5, 0.5));
        assertThat(SourceRef.sortedForDisplay(one)).isSameAs(one);
    }
}
