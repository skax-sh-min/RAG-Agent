package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RerankerService 단위 테스트.
 *
 * rerank()는 LlmRouter.executeGated()(TaskType.TEXT, RoutingMode.COST_FIRST)으로
 * 라우팅한다 — 이전에는 직접 주입된 ChatClient를 써서 /llm-usage에 전혀 잡히지 않았다(§6.14).
 * executeGated 적용 — 재랭킹은 질의 경로이므로 동시성 게이트 대상.
 */
class RerankerServiceTest {

    private LlmRouter llmRouter;
    private RerankerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("rerank-system-prompt");
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 0.0, 0.7, true, 6000, true));
        service = new RerankerService(llmRouter, messageSource, props);
    }

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("doc_id", id));
    }

    private void stubLlmResponse(String response) {
        when(llmRouter.executeGated(any(), any(), any())).thenReturn(response);
    }

    @Test
    @DisplayName("정상 랭킹 응답 → 인덱스 순서로 재정렬 후 topK 반환")
    void rerank_validResponse_returnsReorderedTopK() {
        List<Document> candidates = List.of(doc("A"), doc("B"), doc("C"));
        stubLlmResponse("[2, 0, 1]");

        List<Document> result = service.rerank("질문", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMetadata().get("doc_id")).isEqualTo("C"); // index 2
        assertThat(result.get(1).getMetadata().get("doc_id")).isEqualTo("A"); // index 0
    }

    @Test
    @DisplayName("LLM 파싱 실패(빈 응답) → 원본 순서 topK 폴백")
    void rerank_emptyResponse_fallsBackToOriginalOrder() {
        List<Document> candidates = List.of(doc("A"), doc("B"), doc("C"));
        stubLlmResponse("");

        List<Document> result = service.rerank("질문", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMetadata().get("doc_id")).isEqualTo("A");
        assertThat(result.get(1).getMetadata().get("doc_id")).isEqualTo("B");
    }

    @Test
    @DisplayName("candidates.size() <= topK → 그대로 반환")
    void rerank_candidatesSmallerThanTopK_returnsAll() {
        List<Document> candidates = List.of(doc("A"), doc("B"));

        List<Document> result = service.rerank("질문", candidates, 5);

        assertThat(result).hasSize(2);
        assertThat(result).isSameAs(candidates);
    }

    @Test
    @DisplayName("빈 candidates → 빈 결과")
    void rerank_emptyCandidates_returnsEmpty() {
        assertThat(service.rerank("질문", List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("rerank — LlmRouter.executeGated()을 TaskType.TEXT/COST_FIRST로 호출 (사용량 추적)")
    void rerank_tracksUsageViaLlmRouter() {
        List<Document> candidates = List.of(doc("A"), doc("B"), doc("C"));
        stubLlmResponse("[0, 1, 2]");

        service.rerank("질문", candidates, 2);

        verify(llmRouter).executeGated(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
    }

    // ── formatDocList 단위 테스트 (§10.7.1) ───────────────────────────────────

    @Test
    @DisplayName("formatDocList — filename+heading 메타 있으면 구조적 컨텍스트 헤더를 프리뷰 앞에 붙인다")
    void formatDocList_includesStructuralContextHeader() {
        Document doc = new Document("본문 텍스트", Map.of(
                MetaKey.FILENAME, "규정집.docx",
                MetaKey.HEADING, "제3장 휴가"));

        String result = RerankerService.formatDocList(List.of(doc));

        assertThat(result).isEqualTo("[0] (규정집.docx > 제3장 휴가) 본문 텍스트\n");
    }

    @Test
    @DisplayName("formatDocList — heading 없이 filename만 있으면 파일명만 헤더로 사용")
    void formatDocList_headerFallsBackToFilenameOnly() {
        Document doc = new Document("본문", Map.of(MetaKey.FILENAME, "규정집.docx"));

        String result = RerankerService.formatDocList(List.of(doc));

        assertThat(result).isEqualTo("[0] (규정집.docx) 본문\n");
    }

    @Test
    @DisplayName("formatDocList — filename/heading 메타 없으면 헤더 없이 프리뷰만 출력")
    void formatDocList_omitsHeaderWhenNoStructuralMetadata() {
        Document doc = new Document("본문 텍스트", Map.of("doc_id", "A"));

        String result = RerankerService.formatDocList(List.of(doc));

        assertThat(result).isEqualTo("[0] 본문 텍스트\n");
    }

    @Test
    @DisplayName("formatDocList — 프리뷰는 500자로 잘린다 (기존 200자에서 확장)")
    void formatDocList_truncatesPreviewTo500Chars() {
        String longText = "가".repeat(600);
        Document doc = new Document(longText, Map.of());

        String result = RerankerService.formatDocList(List.of(doc));

        assertThat(result).isEqualTo("[0] " + "가".repeat(500) + "\n");
    }

    @Test
    @DisplayName("formatDocList — 500자 이하 텍스트는 잘리지 않고 그대로 출력")
    void formatDocList_shortTextNotTruncated() {
        Document doc = new Document("짧은 텍스트", Map.of());

        String result = RerankerService.formatDocList(List.of(doc));

        assertThat(result).isEqualTo("[0] 짧은 텍스트\n");
    }

    // ── parseRanking 단위 테스트 ──────────────────────────────────────────────

    @Test
    @DisplayName("parseRanking — 표준 JSON 배열 파싱")
    void parseRanking_standard() {
        assertThat(RerankerService.parseRanking("[2, 0, 3, 1]", 4))
                .containsExactly(2, 0, 3, 1);
    }

    @Test
    @DisplayName("parseRanking — 범위 초과 인덱스 제거")
    void parseRanking_outOfRangeFiltered() {
        assertThat(RerankerService.parseRanking("[0, 5, 1]", 3))
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("parseRanking — 중복 인덱스 제거")
    void parseRanking_duplicatesRemoved() {
        assertThat(RerankerService.parseRanking("[1, 0, 1, 2]", 3))
                .containsExactly(1, 0, 2);
    }

    @Test
    @DisplayName("parseRanking — 배열 없거나 null/빈 문자열 → 빈 리스트")
    void parseRanking_noBracketsOrBlank_returnsEmpty() {
        assertThat(RerankerService.parseRanking("관련 없는 텍스트", 5)).isEmpty();
        assertThat(RerankerService.parseRanking(null, 5)).isEmpty();
        assertThat(RerankerService.parseRanking("", 5)).isEmpty();
    }
}
