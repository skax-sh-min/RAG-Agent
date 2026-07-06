package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
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
 * rerank()는 LlmRouter.executeWithTracking()(TaskType.TEXT, RoutingMode.COST_FIRST)으로
 * 라우팅한다 — 이전에는 직접 주입된 ChatClient를 써서 /llm-usage에 전혀 잡히지 않았다(§6.14).
 */
class RerankerServiceTest {

    private LlmRouter llmRouter;
    private RerankerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("rerank-system-prompt");
        service = new RerankerService(llmRouter, messageSource);
    }

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("doc_id", id));
    }

    private void stubLlmResponse(String response) {
        when(llmRouter.executeWithTracking(any(), any(), any())).thenReturn(response);
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
    @DisplayName("rerank — LlmRouter.executeWithTracking()을 TaskType.TEXT/COST_FIRST로 호출 (사용량 추적)")
    void rerank_tracksUsageViaLlmRouter() {
        List<Document> candidates = List.of(doc("A"), doc("B"), doc("C"));
        stubLlmResponse("[0, 1, 2]");

        service.rerank("질문", candidates, 2);

        verify(llmRouter).executeWithTracking(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any());
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
