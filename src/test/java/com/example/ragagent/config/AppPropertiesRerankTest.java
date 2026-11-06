package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리랭크 설정 — 접속 설정의 기본값과, {@code search-top-k} 하나에서 파생하는 후보 수·최종 컷(AGENT_MCP_DESIGN §4.3 표).
 */
@ResourceLock("global-state")   // search-top-k 를 정적 오버라이드 계층을 거쳐 읽는다
class AppPropertiesRerankTest {

    private static AppProperties props(int topK, AppProperties.RerankerConfig reranker) {
        return new AppProperties("./data", 2, 800, 100, 100, topK, 0.0, true, 0, false, true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, reranker);
    }

    @Test
    @DisplayName("후보 수 = top-k × 2, 최종 컷 = max(5, ⌈top-k ÷ 2⌉) 이고 후보 수를 넘지 않는다 — 설계서 §4.3 표")
    void derivedValuesFollowTheTable() {
        // {search-top-k, 후보 수, 최종 컷}
        int[][] table = {{1, 2, 2}, {2, 4, 4}, {3, 6, 5}, {4, 8, 5}, {10, 20, 5}, {11, 22, 6}, {20, 40, 10}, {50, 100, 25}};
        for (int[] row : table) {
            AppProperties p = props(row[0], null);
            assertThat(p.effectiveRerankCandidates()).as("top-k %d 후보 수", row[0]).isEqualTo(row[1]);
            assertThat(p.effectiveRerankTopK()).as("top-k %d 최종 컷", row[0]).isEqualTo(row[2]);
        }
    }

    @Test
    @DisplayName("후보 수는 어떤 top-k 에서도 최종 컷 이상이다")
    void candidatesNeverFallBelowTheCut() {
        for (int k = 1; k <= 50; k++) {
            assertThat(AppProperties.rerankCandidates(k)).as("top-k %d", k).isGreaterThanOrEqualTo(AppProperties.rerankTopK(k));
        }
    }

    @Test
    @DisplayName("top-k 가 비정상(0 이하)이어도 안전한 값이 나온다")
    void nonPositiveTopKIsSafe() {
        assertThat(AppProperties.rerankCandidates(0)).isEqualTo(2);
        assertThat(AppProperties.rerankTopK(0)).isEqualTo(2);
        assertThat(AppProperties.rerankCandidates(-5)).isEqualTo(2);
    }

    @Test
    @DisplayName("파생값은 /settings 로 바꾼 search-top-k 를 따라 바로 바뀐다(핫)")
    void followsTheHotOverride() {
        AppProperties p = props(10, null);
        assertThat(p.effectiveRerankCandidates()).isEqualTo(20);
        AppProperties.bindOverrides(new TestOverrides(Map.of(SettingsKeys.SEARCH_TOP_K, "16")));
        try {
            assertThat(p.effectiveRerankCandidates()).isEqualTo(32);
            assertThat(p.effectiveRerankTopK()).isEqualTo(8);
        } finally {
            AppProperties.bindOverrides(null);
        }
    }

    @Test
    @DisplayName("접속 설정 — 미설정이면 꺼짐, 기본값은 모델 bge-reranker-v2-m3 · 연결 5초 · 읽기 15초 · 동시 2")
    void connectionDefaultsAndEnabledFlag() {
        AppProperties unset = props(10, null);
        assertThat(unset.rerankerEnabled()).isFalse();
        assertThat(unset.rerankerSafe().baseUrl()).isEmpty();
        assertThat(unset.rerankerSafe().model()).isEqualTo("bge-reranker-v2-m3");
        assertThat(unset.rerankerSafe().connectTimeoutSeconds()).isEqualTo(5);
        assertThat(unset.rerankerSafe().timeoutSeconds()).isEqualTo(15);
        assertThat(unset.rerankerSafe().maxConcurrent()).isEqualTo(2);

        assertThat(props(10, new AppProperties.RerankerConfig("   ", null, null, null, null)).rerankerEnabled())
                .as("공백 주소는 꺼짐").isFalse();
        AppProperties on = props(10, new AppProperties.RerankerConfig(" http://h:28095 ", " m ", 0, -1, 0));
        assertThat(on.rerankerEnabled()).isTrue();
        assertThat(on.rerankerSafe().baseUrl()).isEqualTo("http://h:28095");
        assertThat(on.rerankerSafe().model()).isEqualTo("m");
        assertThat(on.rerankerSafe().connectTimeoutSeconds()).as("0 이하는 기본값").isEqualTo(5);
        assertThat(on.rerankerSafe().timeoutSeconds()).isEqualTo(15);
        assertThat(on.rerankerSafe().maxConcurrent()).isEqualTo(2);
    }

    private static AppProperties propsWith(Double minScore, Integer maxAttempts) {
        return new AppProperties("./data", 2, 800, 100, 100, 10, 0.0, true, 0, false, true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, minScore, maxAttempts);
    }

    @Test
    @DisplayName("재검색 문턱 — 미설정이면 0.0, 음수도 그대로(모델 원점수), NaN·무한대는 기본값")
    void rerankMinScoreDefaultsAndKeepsNegatives() {
        assertThat(propsWith(null, null).searchRerankMinScoreSafe()).isEqualTo(0.0);
        assertThat(propsWith(-1.5, null).searchRerankMinScoreSafe()).isEqualTo(-1.5);
        assertThat(propsWith(2.0, null).searchRerankMinScoreSafe()).isEqualTo(2.0);
        assertThat(propsWith(Double.NaN, null).searchRerankMinScoreSafe()).isEqualTo(0.0);
        assertThat(propsWith(Double.POSITIVE_INFINITY, null).searchRerankMinScoreSafe()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("검색 횟수 상한 — 미설정·0 이하는 3, 상한 5 로 눌린다(설정이 커져도 LLM 호출이 곱해지지 않게)")
    void maxAttemptsDefaultsAndClamps() {
        assertThat(propsWith(null, null).searchMaxAttemptsSafe()).isEqualTo(3);
        assertThat(propsWith(null, 0).searchMaxAttemptsSafe()).isEqualTo(3);
        assertThat(propsWith(null, -2).searchMaxAttemptsSafe()).isEqualTo(3);
        assertThat(propsWith(null, 1).searchMaxAttemptsSafe()).as("1 = 재검색 끔").isEqualTo(1);
        assertThat(propsWith(null, 4).searchMaxAttemptsSafe()).isEqualTo(4);
        assertThat(propsWith(null, 99).searchMaxAttemptsSafe()).isEqualTo(AppProperties.MAX_SEARCH_MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("두 값은 /settings 오버라이드를 따라 바로 바뀐다(핫)")
    void searchLoopSettingsFollowTheHotOverride() {
        AppProperties p = propsWith(null, null);
        AppProperties.bindOverrides(new TestOverrides(Map.of(
                SettingsKeys.SEARCH_RERANK_MIN_SCORE, "-2.5", SettingsKeys.SEARCH_MAX_ATTEMPTS, "2")));
        try {
            assertThat(p.searchRerankMinScoreSafe()).isEqualTo(-2.5);
            assertThat(p.searchMaxAttemptsSafe()).isEqualTo(2);
        } finally {
            AppProperties.bindOverrides(null);
        }
    }

    /** 오버라이드 계층 대역 — 키 하나의 문자열 값만 돌려준다. */
    private record TestOverrides(Map<String, String> values) implements AppProperties.OverrideSource {
        @Override
        public String get(String key) {
            return values.get(key);
        }
    }
}
