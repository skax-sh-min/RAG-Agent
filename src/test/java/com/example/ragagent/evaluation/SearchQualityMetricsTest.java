package com.example.ragagent.evaluation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** {@link SearchQualityMetrics} 순수 계산 단위테스트 — 라이브 백엔드 없이 항상 실행된다. */
class SearchQualityMetricsTest {

    @Test
    @DisplayName("recallAtK: relevant가 top-k 안에 있으면 1.0")
    void recallAtK_hitWithinK() {
        List<Boolean> ranked = List.of(false, false, true, false);
        assertThat(SearchQualityMetrics.recallAtK(ranked, 3)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recallAtK: relevant가 top-k 밖이면 0.0")
    void recallAtK_hitOutsideK() {
        List<Boolean> ranked = List.of(false, false, true, false);
        assertThat(SearchQualityMetrics.recallAtK(ranked, 2)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recallAtK: relevant가 하나도 없으면 0.0")
    void recallAtK_noHit() {
        List<Boolean> ranked = List.of(false, false, false);
        assertThat(SearchQualityMetrics.recallAtK(ranked, 10)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("ndcgAtK: rank=1(최상위 히트)이면 1.0")
    void ndcgAtK_rankOne() {
        List<Boolean> ranked = List.of(true, false, false);
        assertThat(SearchQualityMetrics.ndcgAtK(ranked, 10)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ndcgAtK: rank가 늦을수록 점수가 로그적으로 감소")
    void ndcgAtK_decaysWithRank() {
        List<Boolean> rank2 = List.of(false, true, false);
        List<Boolean> rank3 = List.of(false, false, true);
        double s2 = SearchQualityMetrics.ndcgAtK(rank2, 10);
        double s3 = SearchQualityMetrics.ndcgAtK(rank3, 10);
        assertThat(s2).isEqualTo(1.0 / (Math.log(3) / Math.log(2)), within(1e-9));
        assertThat(s2).isGreaterThan(s3);
        assertThat(s3).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("ndcgAtK: top-k 밖의 히트는 무시되어 0.0")
    void ndcgAtK_hitOutsideK() {
        List<Boolean> ranked = List.of(false, false, false, true);
        assertThat(SearchQualityMetrics.ndcgAtK(ranked, 2)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("firstRelevantRank: 1-based 순위를 반환, 없으면 -1")
    void firstRelevantRank_basic() {
        assertThat(SearchQualityMetrics.firstRelevantRank(List.of(false, true, true), 10)).isEqualTo(2);
        assertThat(SearchQualityMetrics.firstRelevantRank(List.of(false, false), 10)).isEqualTo(-1);
    }
}
