package com.example.ragagent.ingestion;

import com.example.ragagent.service.CuratedQaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** {@link SimilarityFallback} — 임계값이 너무 많이 걸렀을 때 한 번 낮추는 규칙. */
class SimilarityFallbackTest {

    private static List<Document> docs(double... scores) {
        return java.util.Arrays.stream(scores)
                .mapToObj(s -> Document.builder().id("d" + s).text("t").metadata(Map.of()).score(s).build())
                .toList();
    }

    @Test
    @DisplayName("minWanted — ceil(70% × min(요청 개수, top-k))")
    void minWanted() {
        assertThat(SimilarityFallback.minWanted(10, 10)).isEqualTo(7);
        assertThat(SimilarityFallback.minWanted(30, 10)).as("리랭크용으로 많이 청해도 기준은 top-k").isEqualTo(7);
        assertThat(SimilarityFallback.minWanted(4, 10)).as("요청이 더 작으면 요청 기준").isEqualTo(3);
        assertThat(SimilarityFallback.minWanted(10, 0)).isZero();
    }

    @Test
    @DisplayName("relaxed — 0.05 아래, 0 하한")
    void relaxed() {
        assertThat(SimilarityFallback.relaxed(0.3)).isCloseTo(0.25, within(1e-9));
        assertThat(SimilarityFallback.relaxed(0.03)).isZero();
        assertThat(SimilarityFallback.relaxed(0.6)).as("부동소수 잔여 없이").isEqualTo(0.55);
    }

    @Test
    @DisplayName("충분히 통과하면 그대로 — 임계값을 넘는 앞쪽 topK 개")
    void enoughPassKeepsTheThreshold() {
        var picked = SimilarityFallback.select(docs(0.9, 0.8, 0.7, 0.29), SimilarityFallback::scoreOf, 3, 0.3, 3);

        assertThat(picked.relaxed()).isFalse();
        assertThat(picked.threshold()).isEqualTo(0.3);
        assertThat(picked.rows()).extracting(Document::getScore).containsExactly(0.9, 0.8, 0.7);
    }

    @Test
    @DisplayName("모자라면 한 번만 0.05 낮춰 같은 행을 다시 거른다 — 그 아래로는 더 내려가지 않는다")
    void shortfallLowersOnce() {
        var picked = SimilarityFallback.select(docs(0.9, 0.28, 0.26, 0.24, 0.1), SimilarityFallback::scoreOf, 10, 0.3, 7);

        assertThat(picked.relaxed()).isTrue();
        assertThat(picked.threshold()).isCloseTo(0.25, within(1e-9));
        assertThat(picked.rows()).extracting(Document::getScore).containsExactly(0.9, 0.28, 0.26);
    }

    @Test
    @DisplayName("낮춰도 topK 를 넘기지 않는다")
    void relaxedStillCapsAtTopK() {
        var picked = SimilarityFallback.select(docs(0.9, 0.28, 0.27, 0.26), SimilarityFallback::scoreOf, 2, 0.3, 2);

        assertThat(picked.rows()).extracting(Document::getScore).containsExactly(0.9, 0.28);
    }

    @Test
    @DisplayName("임계값 0(전부 받음)이면 낮출 것이 없다 · minWanted 0 이면 판정하지 않는다")
    void nothingToLower() {
        var zero = SimilarityFallback.select(docs(0.1), SimilarityFallback::scoreOf, 10, 0.0, 7);
        assertThat(zero.relaxed()).isFalse();
        assertThat(zero.rows()).hasSize(1);

        var off = SimilarityFallback.select(docs(0.9, 0.2), SimilarityFallback::scoreOf, 10, 0.3, 0);
        assertThat(off.relaxed()).isFalse();
        assertThat(off.rows()).hasSize(1);
    }

    @Test
    @DisplayName("큐레이션 축에는 걸지 않는다 — 예약 네임스페이스가 CuratedQaService 와 같아야 한다")
    void curatedAxisIsExempt() {
        assertThat(SimilarityFallback.CURATED_VERSION).isEqualTo(CuratedQaService.CURATED_VERSION);
        assertThat(SimilarityFallback.appliesTo(CuratedQaService.CURATED_VERSION)).isFalse();
        assertThat(SimilarityFallback.appliesTo("latest")).isTrue();
    }

    @Test
    @DisplayName("score 가 없는 문서는 유사도 0 으로 본다")
    void missingScoreIsZero() {
        assertThat(SimilarityFallback.scoreOf(Document.builder().id("x").text("t").build())).isZero();
    }
}
