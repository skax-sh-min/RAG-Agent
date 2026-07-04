package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for KeywordExtractor — extracted from DocumentIndexer (§EDIT.md #4).
 */
class KeywordExtractorTest {

    private LlmRouter llmRouter;
    private KeywordExtractor extractor;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.keywordTimeoutSeconds()).thenReturn(5);
        when(props.indexingSafe()).thenReturn(indexing);

        extractor = new KeywordExtractor(llmRouter, props);
    }

    @Test
    @DisplayName("LLM 호출 성공 시 반환된 키워드가 메타데이터에 저장된다")
    void enrichKeywords_llmSuccess_storesKeywordsInMetadata() {
        when(llmRouter.executeWithTracking(any(), any(), any())).thenReturn("검색, 인덱싱, 청크");

        Document result = extractor.enrichKeywords(new Document("테스트 문서 내용입니다."));

        assertThat(result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("검색, 인덱싱, 청크");
    }

    @Test
    @DisplayName("LLM 호출 실패 시 TF-IDF 폴백 키워드로 대체된다")
    void enrichKeywords_llmFailure_fallsBackToTf() {
        when(llmRouter.executeWithTracking(any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));

        Document result = extractor.enrichKeywords(new Document("keyword extraction fallback test content"));

        Object keywords = result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS);
        assertThat(keywords).isNotNull();
        assertThat(keywords.toString()).isNotBlank();
    }

    @Test
    @DisplayName("enrichParallel — 모든 청크가 병렬로 처리되고 개수가 보존된다")
    void enrichParallel_processesAllChunksPreservingCount() {
        when(llmRouter.executeWithTracking(any(), any(), any())).thenReturn("키워드");
        List<Document> chunks = List.of(
                new Document("첫 번째 청크"), new Document("두 번째 청크"), new Document("세 번째 청크"));

        List<Document> result = extractor.enrichParallel(chunks, new Semaphore(2), "test.txt", e -> {});

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(d ->
                assertThat(d.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("키워드"));
    }

    @Test
    @DisplayName("extractKeywordsTf — 빈도 상위 N개 키워드를 반환한다")
    void extractKeywordsTf_returnsTopFrequentTerms() {
        String text = "apple apple apple banana banana cherry";

        String keywords = KeywordExtractor.extractKeywordsTf(text, 2);

        assertThat(keywords).contains("apple");
    }

    @Test
    @DisplayName("extractKeywordsTf — 빈 텍스트는 빈 문자열을 반환한다")
    void extractKeywordsTf_blankText_returnsEmptyString() {
        assertThat(KeywordExtractor.extractKeywordsTf("", 5)).isEmpty();
        assertThat(KeywordExtractor.extractKeywordsTf(null, 5)).isEmpty();
    }
}
