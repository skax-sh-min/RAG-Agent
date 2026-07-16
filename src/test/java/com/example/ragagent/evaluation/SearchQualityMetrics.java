package com.example.ragagent.evaluation;

import java.util.List;

/**
 * §10.7.5 검색 품질 평가 하네스 — recall@k / nDCG@k 계산.
 *
 * 정답이 이진(relevant/not)이고 질의당 "정답 영역"이 사실상 하나라는 전제(§10.7.5 골든셋 설계)에서:
 * recall@k = 상위 k개 안에 relevant 문서가 하나라도 있으면 1, 없으면 0.
 * nDCG@k = 최상위 relevant 히트의 DCG(1/log2(rank+1))를 이상적 배치(rank=1, IDCG=1)로 정규화한 값 —
 * 실제로는 DCG@k와 동일(IDCG=1이므로), 히트가 없으면 0.
 */
final class SearchQualityMetrics {

    private SearchQualityMetrics() {}

    /** 순위 순서대로 나열된 관련성 목록에서 상위 k개 안에 relevant가 있는지. */
    static double recallAtK(List<Boolean> relevanceInRankOrder, int k) {
        return firstRelevantRank(relevanceInRankOrder, k) > 0 ? 1.0 : 0.0;
    }

    /** 상위 k개로 제한한 이진 관련성 nDCG. 히트 없으면 0. */
    static double ndcgAtK(List<Boolean> relevanceInRankOrder, int k) {
        int rank = firstRelevantRank(relevanceInRankOrder, k);
        return rank > 0 ? 1.0 / (Math.log(rank + 1) / Math.log(2)) : 0.0;
    }

    /** 상위 k개 안에서 첫 relevant 히트의 1-based 순위. 없으면 -1. */
    static int firstRelevantRank(List<Boolean> relevanceInRankOrder, int k) {
        int limit = Math.min(k, relevanceInRankOrder.size());
        for (int i = 0; i < limit; i++) {
            if (Boolean.TRUE.equals(relevanceInRankOrder.get(i))) return i + 1;
        }
        return -1;
    }
}
