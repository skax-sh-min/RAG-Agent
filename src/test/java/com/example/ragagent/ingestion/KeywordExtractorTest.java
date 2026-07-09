package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.IndexingCancelledException;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("검색, 인덱싱, 청크");

        Document result = extractor.enrichKeywords(new Document("테스트 문서 내용입니다."));

        assertThat(result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("검색, 인덱싱, 청크");
    }

    @Test
    @DisplayName("LLM 호출 실패 시 TF-IDF 폴백 키워드로 대체된다")
    void enrichKeywords_llmFailure_fallsBackToTf() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));

        Document result = extractor.enrichKeywords(new Document("keyword extraction fallback test content"));

        Object keywords = result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS);
        assertThat(keywords).isNotNull();
        assertThat(keywords.toString()).isNotBlank();
    }

    @Test
    @DisplayName("enrichKeywords — LlmRouter.executeWithTracking()을 context: 접두사로 호출 (키워드+맥락 통합 호출, §10.1)")
    void enrichKeywords_tracksUsageUnderContextPrefix() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("키워드");

        extractor.enrichKeywords(new Document("테스트 문서 내용입니다."));

        verify(llmRouter).executeWithTracking(
                eq(TaskType.LIGHT_TEXT), eq(RoutingMode.COST_FIRST), eq(BackgroundUsage.CONTEXT_PREFIX), any());
    }

    @Test
    @DisplayName("enrichKeywords — LLM 응답에 키워드/맥락 마커가 모두 있으면 각각 파싱해 저장한다")
    void enrichKeywords_llmSuccessWithMarkers_parsesKeywordsAndContext() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("키워드: 검색, 인덱싱, 청크\n맥락: 이 청크는 설정 방법을 설명합니다.");
        Document chunk = new Document("테스트 문서 내용입니다.",
                java.util.Map.of(MetaKey.FILENAME, "가이드.pdf", MetaKey.HEADING, "설정 방법"));

        Document result = extractor.enrichKeywords(chunk);

        assertThat(result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("검색, 인덱싱, 청크");
        assertThat(result.getMetadata().get(MetaKey.CHUNK_CONTEXT))
                .isEqualTo("가이드.pdf > 설정 방법\n이 청크는 설정 방법을 설명합니다.");
    }

    @Test
    @DisplayName("enrichKeywords — 맥락 마커만 없으면 구조적 맥락으로 폴백한다")
    void enrichKeywords_missingContextMarker_fallsBackToStructuralContext() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("키워드: 검색, 인덱싱, 청크");
        Document chunk = new Document("테스트 문서 내용입니다.",
                java.util.Map.of(MetaKey.FILENAME, "가이드.pdf", MetaKey.HEADING, "설정 방법"));

        Document result = extractor.enrichKeywords(chunk);

        assertThat(result.getMetadata().get(MetaKey.CHUNK_CONTEXT)).isEqualTo("가이드.pdf > 설정 방법");
    }

    @Test
    @DisplayName("enrichKeywords — 마커 없는 레거시 응답도 키워드로 정상 파싱된다(하위호환)")
    void enrichKeywords_legacyResponseWithoutMarkers_parsesAsKeywords() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("검색, 인덱싱, 청크");

        Document result = extractor.enrichKeywords(new Document("테스트 문서 내용입니다."));

        assertThat(result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("검색, 인덱싱, 청크");
    }

    @Test
    @DisplayName("enrichKeywords — LLM 호출 실패 시 CHUNK_CONTEXT는 구조적 맥락만으로 폴백한다")
    void enrichKeywords_llmFailure_fallsBackToStructuralContextOnly() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));
        Document chunk = new Document("keyword extraction fallback test content",
                java.util.Map.of(MetaKey.FILENAME, "가이드.pdf", MetaKey.HEADING, "설정 방법"));

        Document result = extractor.enrichKeywords(chunk);

        assertThat(result.getMetadata().get(MetaKey.CHUNK_CONTEXT)).isEqualTo("가이드.pdf > 설정 방법");
    }

    @Test
    @DisplayName("enrichParallel — 모든 청크가 병렬로 처리되고 개수가 보존된다")
    void enrichParallel_processesAllChunksPreservingCount() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("키워드");
        List<Document> chunks = List.of(
                new Document("첫 번째 청크"), new Document("두 번째 청크"), new Document("세 번째 청크"));

        List<Document> result = extractor.enrichParallel(chunks, new Semaphore(2), "test.txt", e -> {});

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(d ->
                assertThat(d.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("키워드"));
    }

    @Test
    @DisplayName("enrichParallel — 호출 스레드 인터럽트(취소) 시 IndexingCancelledException 던지고 즉시 반환(§6.16.1)")
    void enrichParallel_interrupted_throwsIndexingCancelledException() throws InterruptedException {
        CountDownLatch llmCallStarted = new CountDownLatch(1);
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenAnswer(inv -> {
            llmCallStarted.countDown();
            Thread.sleep(30_000); // simulates a hung LLM call; interrupted well before this elapses
            return "unused";
        });
        List<Document> chunks = List.of(new Document("interrupt-test chunk"));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                extractor.enrichParallel(chunks, new Semaphore(1), "test.txt", e -> {});
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();

        assertThat(llmCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(10_000);

        assertThat(worker.isAlive()).as("worker must terminate, no zombie thread").isFalse();
        assertThat(thrown.get()).isInstanceOf(IndexingCancelledException.class);
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
