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
                eq(TaskType.MICRO_TEXT), eq(RoutingMode.COST_FIRST), eq(BackgroundUsage.CONTEXT_PREFIX), any());
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

    @Test
    @DisplayName("extractKeywordsTf — '이미지'/img/png 등 미디어 필러 단어는 키워드에서 제외된다")
    void extractKeywordsTf_excludesMediaFillerWords() {
        String text = "이미지 이미지 이미지 png png png img img img 매출 매출 매출 실적 실적 실적";

        String keywords = KeywordExtractor.extractKeywordsTf(text, 5);

        assertThat(keywords).doesNotContain("이미지", "png", "img");
        assertThat(keywords).contains("매출", "실적");
    }

    // ── 키워드 추출 노이즈 필터 (이미지/도형/다이어그램 마커, 해시값) ──────────────

    @Test
    @DisplayName("stripKeywordNoise — [이미지: 경로] 마커(해시 경로 포함)를 제거한다")
    void stripKeywordNoise_removesImageMarker() {
        String text = "본문 시작 [이미지: images/3f2a9c81b7e4d2f1/d1_img1.png] 본문 끝";

        String stripped = KeywordExtractor.stripKeywordNoise(text);

        assertThat(stripped).doesNotContain("이미지", "3f2a9c81b7e4d2f1", "img1", "png");
        assertThat(stripped).contains("본문 시작").contains("본문 끝");
    }

    @Test
    @DisplayName("stripKeywordNoise — 도형 그룹/다이어그램 열고닫는 태그를 제거하되 내부 텍스트는 보존한다")
    void stripKeywordNoise_removesShapeGroupAndDiagramTags() {
        String text = "[도형 그룹]\n영업팀 조직도\n[/도형 그룹]\n\n[다이어그램 2]\n프로세스 단계\n[/다이어그램 2]";

        String stripped = KeywordExtractor.stripKeywordNoise(text);

        assertThat(stripped).doesNotContain("도형 그룹", "다이어그램");
        assertThat(stripped).contains("영업팀 조직도").contains("프로세스 단계");
    }

    @Test
    @DisplayName("stripKeywordNoise — [차트: 제목] 라벨은 지우되 제목 텍스트는 남긴다")
    void stripKeywordNoise_chartLabel_keepsTitleDropsLabel() {
        String text = "[차트: 분기별 매출 추이]";

        String stripped = KeywordExtractor.stripKeywordNoise(text);

        assertThat(stripped).isEqualTo("분기별 매출 추이");
    }

    @Test
    @DisplayName("filterNoiseKeywords — 미디어 필러 단어와 해시형 토큰을 목록에서 제거한다")
    void filterNoiseKeywords_dropsMediaWordsAndHashTokens() {
        String keywords = "매출, 이미지, 3f2a9c81, img, 실적";

        String filtered = KeywordExtractor.filterNoiseKeywords(keywords);

        assertThat(filtered).isEqualTo("매출, 실적");
    }

    @Test
    @DisplayName("enrichKeywords — LLM이 이미지/해시 노이즈를 포함해 응답해도 최종 키워드에서 제외된다")
    void enrichKeywords_llmResponseWithNoise_filtersItOut() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("키워드: 매출, 이미지, 3f2a9c81, png\n맥락: 분기 실적 설명.");

        Document result = extractor.enrichKeywords(new Document("[이미지: images/3f2a9c81/d1_img1.png] 매출 실적"));

        assertThat(result.getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("매출");
    }

    // ── §10.8.2 — batch keyword extraction ──────────────────────────────

    @Test
    @DisplayName("splitBatchSections — 번호별 [결과 N] 마커로 구간을 정확히 분리한다")
    void splitBatchSections_parsesNumberedMarkers() {
        String response = """
                [결과 1]
                키워드: 가, 나, 다
                맥락: 첫 번째 설명.
                [결과 2]
                키워드: 라, 마, 바
                맥락: 두 번째 설명.""";

        java.util.Map<Integer, String> sections = KeywordExtractor.splitBatchSections(response);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(1)).contains("가, 나, 다").contains("첫 번째 설명");
        assertThat(sections.get(2)).contains("라, 마, 바").contains("두 번째 설명");
    }

    @Test
    @DisplayName("splitBatchSections — 마커가 없으면 빈 맵을 반환한다")
    void splitBatchSections_noMarkers_returnsEmpty() {
        assertThat(KeywordExtractor.splitBatchSections("마커 없는 응답입니다")).isEmpty();
        assertThat(KeywordExtractor.splitBatchSections(null)).isEmpty();
    }

    @Test
    @DisplayName("enrichKeywordsBatch — 정상 응답이면 청크별로 올바른 키워드/맥락이 매핑된다")
    void enrichKeywordsBatch_success_mapsPerChunkResults() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("""
                [결과 1]
                키워드: 가, 나, 다
                맥락: 첫 번째 청크 설명.
                [결과 2]
                키워드: 라, 마, 바
                맥락: 두 번째 청크 설명.""");
        List<Document> batch = List.of(new Document("첫 번째 청크 내용"), new Document("두 번째 청크 내용"));

        List<Document> result = extractor.enrichKeywordsBatch(batch);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("가, 나, 다");
        assertThat(result.get(0).getMetadata().get(MetaKey.CHUNK_CONTEXT)).isEqualTo("첫 번째 청크 설명.");
        assertThat(result.get(1).getMetadata().get(MetaKey.EXCERPT_KEYWORDS)).isEqualTo("라, 마, 바");
        assertThat(result.get(1).getMetadata().get(MetaKey.CHUNK_CONTEXT)).isEqualTo("두 번째 청크 설명.");
    }

    @Test
    @DisplayName("enrichKeywordsBatch — 응답에 결과 마커가 부족하면(파싱 실패) 배치 전체가 개별 TF 폴백된다")
    void enrichKeywordsBatch_incompleteResponse_fallsBackToTfForEveryChunk() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("[결과 1]\n키워드: 가, 나, 다\n맥락: 설명."); // only 1 of 2 expected sections
        List<Document> batch = List.of(
                new Document("apple apple apple banana"), new Document("cherry cherry cherry date"));

        List<Document> result = extractor.enrichKeywordsBatch(batch);

        assertThat(result).hasSize(2);
        // TF fallback derives keywords from each chunk's own text, not from the (unused) LLM reply.
        assertThat(result.get(0).getMetadata().get(MetaKey.EXCERPT_KEYWORDS).toString()).contains("apple");
        assertThat(result.get(1).getMetadata().get(MetaKey.EXCERPT_KEYWORDS).toString()).contains("cherry");
    }

    @Test
    @DisplayName("enrichKeywordsBatch — LLM 호출 실패 시 배치 전체가 개별 TF 폴백된다(청크별 재시도 없음)")
    void enrichKeywordsBatch_llmFailure_fallsBackToTfWithoutPerChunkRetry() {
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no LLM in test"));
        List<Document> batch = List.of(
                new Document("apple apple apple banana"), new Document("cherry cherry cherry date"));

        List<Document> result = extractor.enrichKeywordsBatch(batch);

        assertThat(result).hasSize(2);
        verify(llmRouter, org.mockito.Mockito.times(1)).executeWithTracking(any(), any(), any(), any());
        assertThat(result.get(0).getMetadata().get(MetaKey.EXCERPT_KEYWORDS).toString()).contains("apple");
    }

    @Test
    @DisplayName("enrichParallel — batchSize>1이면 LLM 왕복 횟수가 ceil(청크수/N)로 감소한다")
    void enrichParallel_batchSizeAboveOne_reducesRoundTrips() {
        LlmRouter router = mock(LlmRouter.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.IndexingConfig indexing = mock(AppProperties.IndexingConfig.class);
        when(indexing.keywordTimeoutSeconds()).thenReturn(5);
        when(indexing.keywordBatchSize()).thenReturn(2);
        when(props.indexingSafe()).thenReturn(indexing);
        KeywordExtractor batchExtractor = new KeywordExtractor(router, props);
        when(router.executeWithTracking(any(), any(), any(), any())).thenReturn("""
                [결과 1]
                키워드: 가
                맥락: 설명1
                [결과 2]
                키워드: 나
                맥락: 설명2""");
        // 5 chunks, batchSize=2 → batches of 2,2,1 → ceil(5/2)=3 round-trips.
        List<Document> chunks = List.of(
                new Document("청크1"), new Document("청크2"), new Document("청크3"),
                new Document("청크4"), new Document("청크5"));

        List<Document> result = batchExtractor.enrichParallel(chunks, new Semaphore(4), "test.txt", e -> {});

        assertThat(result).hasSize(5);
        verify(router, org.mockito.Mockito.times(3)).executeWithTracking(any(), any(), any(), any());
    }
}
