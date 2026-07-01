package com.example.ragagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RerankerService 단위 테스트.
 */
class RerankerServiceTest {

    private ChatClient chatClient;
    private RerankerService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("rerank-system-prompt");
        service = new RerankerService(chatClient, messageSource);
    }

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("doc_id", id));
    }

    private void stubLlmResponse(String response) {
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just(response));
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
