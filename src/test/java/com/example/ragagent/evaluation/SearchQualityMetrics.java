package com.example.ragagent.evaluation;

import java.util.List;
import java.util.regex.Pattern;

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

    /** 공백·마크다운 장식 문자 — 정답 문자열과 청크를 비교하기 전에 양쪽에서 걷어낸다. */
    private static final Pattern NOISE = Pattern.compile("[\\s*`#>_|~\\-]+");

    /**
     * {@code text} 가 정답 문자열({@code markers}) 중 하나를 담고 있는가 — <b>공백과 마크다운 장식을 걷어낸 뒤</b> 비교한다.
     *
     * <p>골든셋의 정답 문자열은 색인된 원문에서 떠 온 것인데, 문서를 다시 교정·인덱싱하면 같은 문장이
     * {@code **IDataSet 타입**으로} 처럼 장식이 붙거나 줄바꿈·공백이 달라진다. 그대로 비교하면 내용은 그대로인데 "정답 없음"이
     * 돼 recall 이 문서 서식 때문에 떨어진다(2026-10-12 재측정 때 26건 중 2건). 표현 자체가 바뀐 경우(애플리케이션 →
     * 어플리케이션)는 여기서 잡지 않는다 — 골든셋에 지금 표현을 정답 후보로 더한다.
     */
    static boolean containsAnyMarker(String text, List<String> markers) {
        if (text == null || markers == null) return false;
        String flat = normalize(text);
        return markers.stream().map(SearchQualityMetrics::normalize).anyMatch(m -> !m.isEmpty() && flat.contains(m));
    }

    static String normalize(String s) {
        return s == null ? "" : NOISE.matcher(s).replaceAll("");
    }
}
