package com.example.ragagent.config;

import org.junit.jupiter.api.parallel.ResourceLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime settings-override layer wired into the hot-editable {@code xxxSafe()}
 * accessors. Verifies override precedence (override wins over property default), clamping still
 * applies on top of an override, and malformed/unset overrides fall back to the property value.
 *
 * <p>The bound {@link AppProperties.OverrideSource} is process-wide static, so every test unbinds
 * it afterwards to stay isolated from the rest of the suite.
 */
@ResourceLock("global-state")
class AppPropertiesOverrideTest {

    /** Base props: similarity=0.0, mq-min-len=5, retry-escalate=true, cand-mult=3, tag-mult=2, rrf-weight=1.0, rrf-k=60. */
    private static AppProperties base() {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, null, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Same as {@link #base()} but with a configured {@code IndexingConfig} (base() leaves it null). */
    private static AppProperties withIndexing(AppProperties.IndexingConfig indexing) {
        return new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 5, false,
                true, false, 3, null,
                null, indexing, null, null, null, null, null, null, null, null, null, 2,
                null, 1.0, 60, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private final Map<String, String> overrides = new HashMap<>();

    private void bind() {
        AppProperties.bindOverrides(overrides::get);
    }

    @AfterEach
    void unbind() {
        AppProperties.unbindOverrides();
    }

    @Test
    @DisplayName("소스 미바인딩 시 프로퍼티 기본값을 그대로 반환 (회귀 0)")
    void noSource_returnsPropertyDefaults() {
        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(0.0);
        assertThat(p.searchRrfKSafe()).isEqualTo(60);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(1.0);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(3);
        assertThat(p.searchTagCandidateMultiplierSafe()).isEqualTo(2);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(5);
        assertThat(p.searchRetryEscalateSafe()).isTrue();
    }

    @Test
    @DisplayName("오버라이드가 있으면 각 hot 접근자가 오버라이드 값을 반환")
    void override_winsOverPropertyDefault() {
        bind();
        overrides.put(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "0.42");
        overrides.put(SettingsKeys.SEARCH_RRF_K, "80");
        overrides.put(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "2.5");
        overrides.put(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER, "5");
        overrides.put(SettingsKeys.SEARCH_TAG_CANDIDATE_MULTIPLIER, "4");
        overrides.put(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH, "12");
        overrides.put(SettingsKeys.SEARCH_RETRY_ESCALATE, "false");

        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(0.42);
        assertThat(p.searchRrfKSafe()).isEqualTo(80);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(2.5);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(5);
        assertThat(p.searchTagCandidateMultiplierSafe()).isEqualTo(4);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(12);
        assertThat(p.searchRetryEscalateSafe()).isFalse();
    }

    @Test
    @DisplayName("오버라이드 위에도 클램핑이 그대로 적용된다")
    void override_isStillClamped() {
        bind();
        overrides.put(SettingsKeys.SEARCH_SIMILARITY_THRESHOLD, "1.5"); // > 1.0 → 1.0
        overrides.put(SettingsKeys.SEARCH_CANDIDATE_MULTIPLIER, "0");   // < 1 → 1
        overrides.put(SettingsKeys.SEARCH_MULTIQUERY_MIN_LENGTH, "-3"); // < 0 → 0

        AppProperties p = base();
        assertThat(p.searchSimilarityThresholdSafe()).isEqualTo(1.0);
        assertThat(p.searchCandidateMultiplierSafe()).isEqualTo(1);
        assertThat(p.searchMultiqueryMinLengthSafe()).isEqualTo(0);
    }

    @Test
    @DisplayName("검색 — topK/멀티쿼리 확장/하이브리드도 오버라이드가 적용된다 (다음 검색부터 반영)")
    void override_searchTopKMultiqueryHybrid() {
        bind();
        AppProperties p = base();
        assertThat(p.searchTopKSafe()).isEqualTo(7);
        assertThat(p.searchMultiqueryEnabledSafe()).isTrue();
        assertThat(p.searchHybridEnabledSafe()).isFalse();

        overrides.put(SettingsKeys.SEARCH_TOP_K, "12");
        overrides.put(SettingsKeys.SEARCH_MULTIQUERY_ENABLED, "false");
        overrides.put(SettingsKeys.SEARCH_HYBRID_ENABLED, "true");

        assertThat(p.searchTopKSafe()).isEqualTo(12);
        assertThat(p.searchMultiqueryEnabledSafe()).isFalse();
        assertThat(p.searchHybridEnabledSafe()).isTrue();
    }

    @Test
    @DisplayName("§10.10 — 큐레이션 Q&A 축 on/off·가중치 기본값 및 오버라이드")
    void override_curatedQaEnabledAndWeight() {
        bind();
        AppProperties p = base();
        assertThat(p.searchCuratedQaEnabledSafe()).isTrue();   // default: enabled
        assertThat(p.searchCuratedQaWeightSafe()).isEqualTo(1.5); // default weight

        overrides.put(SettingsKeys.SEARCH_CURATED_QA_ENABLED, "false");
        overrides.put(SettingsKeys.SEARCH_CURATED_QA_WEIGHT, "2.5");

        assertThat(p.searchCuratedQaEnabledSafe()).isFalse();
        assertThat(p.searchCuratedQaWeightSafe()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("인덱싱 — 청크 크기/오버랩/최소 크기 오버라이드 (다음 인덱싱부터 반영)")
    void override_chunkValues() {
        bind();
        AppProperties p = base();
        assertThat(p.chunkSizeSafe()).isEqualTo(800);
        assertThat(p.chunkOverlapSafe()).isEqualTo(100);
        assertThat(p.minChunkSizeSafe()).isEqualTo(100);

        overrides.put(SettingsKeys.CHUNK_SIZE, "1500");
        overrides.put(SettingsKeys.CHUNK_OVERLAP, "250");
        overrides.put(SettingsKeys.MIN_CHUNK_SIZE, "400");

        assertThat(p.chunkSizeSafe()).isEqualTo(1500);
        assertThat(p.chunkOverlapSafe()).isEqualTo(250);
        assertThat(p.minChunkSizeSafe()).isEqualTo(400);
    }

    @Test
    @DisplayName("min-chunk-size 오버라이드가 0이면 오버라이드된 오버랩 값으로 폴백")
    void minChunkSizeOverrideZero_fallsBackToOverriddenOverlap() {
        bind();
        overrides.put(SettingsKeys.MIN_CHUNK_SIZE, "0");
        overrides.put(SettingsKeys.CHUNK_OVERLAP, "250");

        assertThat(base().minChunkSizeSafe()).isEqualTo(250);
    }

    @Test
    @DisplayName("indexingSafe() — indexing 설정이 없어도(널 분기) 동시성 오버라이드가 반영된다")
    void indexingOverride_nullIndexingConfig() {
        bind();
        AppProperties p = base(); // indexing == null → application.properties 기본값과 동일한 폴백
        assertThat(p.indexingSafe().maxConcurrentFiles()).isEqualTo(1);
        assertThat(p.indexingSafe().maxConcurrentLlmCalls()).isEqualTo(3);

        overrides.put(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES, "6");
        overrides.put(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM, "2");

        assertThat(p.indexingSafe().maxConcurrentFiles()).isEqualTo(6);
        assertThat(p.indexingSafe().maxConcurrentLlmCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("indexingSafe() — 설정된 값 위에 오버라이드가 우선하고, timeout/batch는 건드리지 않는다")
    void indexingOverride_overConfiguredValues() {
        bind();
        AppProperties p = withIndexing(new AppProperties.IndexingConfig(3, 4, 30, 4));
        assertThat(p.indexingSafe().maxConcurrentFiles()).isEqualTo(3);
        assertThat(p.indexingSafe().maxConcurrentLlmCalls()).isEqualTo(4);

        overrides.put(SettingsKeys.INDEXING_MAX_CONCURRENT_FILES, "8");
        overrides.put(SettingsKeys.INDEXING_MAX_CONCURRENT_LLM, "1");

        assertThat(p.indexingSafe().maxConcurrentFiles()).isEqualTo(8);
        assertThat(p.indexingSafe().maxConcurrentLlmCalls()).isEqualTo(1);
        // 오버라이드 대상이 아닌 필드는 그대로 유지
        assertThat(p.indexingSafe().keywordTimeoutSeconds()).isEqualTo(30);
        assertThat(p.indexingSafe().keywordBatchSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("LLM — direct-temperature 오버라이드가 llmSafe()에 반영되고 [0.0, 0.2]로 clamp된다 (§6.18)")
    void override_directTemperature() {
        bind();
        assertThat(base().llmSafe().directTemperature()).isEqualTo(0.1); // 기본값
        assertThat(base().llmSafe().temperature()).isEqualTo(0.0);       // 일반 temperature 기본값

        overrides.put(SettingsKeys.LLM_DIRECT_TEMPERATURE, "0.05");
        assertThat(base().llmSafe().directTemperature()).isEqualTo(0.05);

        overrides.put(SettingsKeys.LLM_DIRECT_TEMPERATURE, "0.9"); // > 0.2 → clamp
        assertThat(base().llmSafe().directTemperature()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("파싱 불가/공백 오버라이드는 무시하고 프로퍼티 기본값으로 폴백")
    void malformedOrBlankOverride_fallsBackToDefault() {
        bind();
        overrides.put(SettingsKeys.SEARCH_RRF_K, "not-a-number");
        overrides.put(SettingsKeys.SEARCH_RRF_KEYWORD_WEIGHT, "   ");
        overrides.put(SettingsKeys.SEARCH_RETRY_ESCALATE, "maybe");

        AppProperties p = base();
        assertThat(p.searchRrfKSafe()).isEqualTo(60);
        assertThat(p.searchRrfKeywordWeightSafe()).isEqualTo(1.0);
        assertThat(p.searchRetryEscalateSafe()).isTrue();
    }
}
