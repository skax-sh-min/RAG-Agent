package com.example.ragagent.ingestion;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 유사도 임계값({@code app.search-similarity-threshold})이 벡터 검색 결과를 너무 많이 걸러 냈을 때 <b>한 번만</b>
 * 임계값을 낮춰 청크를 더 확보하는 규칙 — 두 벡터 백엔드가 같은 판정을 쓰도록 여기 한 곳에 둔다(순수 클래스).
 *
 * <p><b>언제.</b> 임계값을 통과한 청크가 {@code app.search-top-k} 의 {@value #MIN_RATIO_PERCENT}% 보다 적을 때.
 * 기준이 요청한 개수(후보 배수가 곱해진 {@code candidateK})가 아니라 {@code top-k} 인 이유는, 이 규칙이 막으려는
 * 것이 "답변이 받을 문서가 모자라는 것"이기 때문이다 — 리랭크용으로 30개를 청한 질의가 20개를 받았다고 임계값을
 * 내리면 리랭커가 버릴 후보만 늘어난다. 요청이 {@code top-k} 보다 작으면 요청한 개수를 기준으로 한다.
 *
 * <p><b>얼마나.</b> {@value #STEP} 한 번, 0 아래로는 내려가지 않는다. 임계값이 0 이면(전부 받는다) 낮출 것이 없다.
 *
 * <p><b>추가 조회가 없다.</b> 두 백엔드는 임계값이 켜져 있으면 이미 {@code topK × 2} 를 가져와 유사도 내림차순으로
 * 거른다(§10.7.4 과다 조회). 낮춘 임계값으로 얻을 결과 — "낮춘 임계값을 넘는 가장 가까운 topK 개" — 는 순위의
 * 앞쪽 topK 개 안에 있으므로 이미 가져온 행을 다시 거르는 것과 정확히 같다.
 *
 * <p><b>큐레이션 축에는 걸지 않는다</b>({@link #appliesTo}) — 큐레이션 풀은 작아서 모자라는 것이 정상 상태이고,
 * 거기서 임계값을 낮추면 질문과 먼 Q&A 가 큐레이션 가중치를 받고 융합에 들어간다.
 */
public final class SimilarityFallback {

    /** 통과한 청크가 {@code top-k} 의 이 비율보다 적으면 낮춘다. */
    static final double MIN_RATIO = 0.7;
    static final int MIN_RATIO_PERCENT = 70;

    /** 한 번에 낮추는 폭. */
    public static final double STEP = 0.05;

    /** 큐레이션 축의 예약 네임스페이스 — {@code CuratedQaService.CURATED_VERSION} 과 같다(ingestion 이 service 를 보지 않게). */
    static final String CURATED_VERSION = "curated";

    private SimilarityFallback() {}

    /** 이 버전의 검색에 규칙을 거는가 — 문서 검색만(큐레이션 축 제외). */
    public static boolean appliesTo(String version) {
        return !CURATED_VERSION.equals(version);
    }

    /** 이보다 적게 통과하면 낮춘다 — {@code ceil(70% × min(요청 개수, top-k))}. */
    public static int minWanted(int requestedTopK, int configuredTopK) {
        int base = Math.min(Math.max(requestedTopK, 0), Math.max(configuredTopK, 0));
        return (int) Math.ceil(MIN_RATIO * base);
    }

    /** 낮춘 임계값 — {@value #STEP} 아래, 0 하한. 소수 셋째 자리로 반올림한다(0.6 − 0.05 = 0.5499999… 가 로그에 찍히지 않게). */
    public static double relaxed(double threshold) {
        return Math.max(0.0, Math.round((threshold - STEP) * 1000) / 1000.0);
    }

    /** 검색 결과 문서의 유사도 — 두 백엔드는 {@code 1 − distance} 를 score 로 싣는다. 없으면 0 으로 본다. */
    public static double scoreOf(Document doc) {
        Double score = doc.getScore();
        return score == null ? 0.0 : score;
    }

    /** 고른 결과와, 실제로 쓴 임계값(낮췄으면 낮춘 값). */
    public record Selection<T>(List<T> rows, double threshold, boolean relaxed) {}

    /**
     * 유사도 내림차순으로 정렬된 {@code rows} 에서 {@code threshold} 이상인 앞쪽 {@code topK} 개를 고른다. 그 수가
     * {@code minWanted} 보다 적고 임계값이 켜져 있으면 {@link #relaxed} 로 한 번 더 고른다.
     */
    public static <T> Selection<T> select(List<T> rowsDesc, ToDoubleFunction<T> similarity,
                                          int topK, double threshold, int minWanted) {
        List<T> kept = above(rowsDesc, similarity, topK, threshold);
        if (threshold <= 0.0 || kept.size() >= minWanted) {
            return new Selection<>(kept, threshold, false);
        }
        double lower = relaxed(threshold);
        return new Selection<>(above(rowsDesc, similarity, topK, lower), lower, true);
    }

    private static <T> List<T> above(List<T> rowsDesc, ToDoubleFunction<T> similarity, int topK, double threshold) {
        List<T> out = new ArrayList<>();
        for (T row : rowsDesc) {
            if (out.size() >= topK) break;
            if (similarity.applyAsDouble(row) < threshold) continue;
            out.add(row);
        }
        return out;
    }
}
