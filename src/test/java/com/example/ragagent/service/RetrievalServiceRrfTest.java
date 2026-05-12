package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — RetrievalService RRF merge (B-22 회귀)
 *
 * Old deduplicate() broke early at topK, so results from later query lists
 * were silently discarded even when they ranked higher by RRF score.
 */
class RetrievalServiceRrfTest {

    private static Document doc(String id) {
        return new Document("content-" + id, Map.of("filename", id, "page_or_slide", "1"));
    }

    private static List<String> ids(List<Document> docs) {
        return docs.stream()
                .map(d -> (String) d.getMetadata().get("filename"))
                .toList();
    }

    @Test
    @DisplayName("RRF — 두 번째 쿼리의 rank-0 문서가 첫 번째 쿼리의 rank-1 문서보다 높은 점수 획득 (B-22 핵심)")
    void rrfPromotesSecondQueryHighRankDoc() {
        // query1: [a(r0), b(r1)]   a=1/61≈0.0164, b=1/62≈0.0161
        // query2: [c(r0)]          c=1/61≈0.0164
        // topK=2 → a and c (both 1/61) should be in result; b (1/62) should be excluded
        // Old deduplicate: processes allDocs=[a,b,c] and stops at topK=2 → [a,b] (c never considered)
        List<List<Document>> ranked = List.of(
                List.of(doc("a"), doc("b")),
                List.of(doc("c"))
        );

        List<Document> result = RetrievalService.mergeRrf(ranked, 2);

        assertThat(ids(result))
                .as("RRF should include rank-0 doc from query 2 over rank-1 doc from query 1")
                .containsExactlyInAnyOrder("a", "c")
                .doesNotContain("b");
    }

    @Test
    @DisplayName("RRF — 두 쿼리에 모두 등장하는 문서가 최상위 점수 획득")
    void rrfBoostsDocAppearingInBothQueries() {
        // query1: [a(r0), b(r1)]  query2: [b(r0), c(r1)]
        // b appears in both: score = 1/62 + 1/61 ≈ 0.0325
        // a: 1/61 ≈ 0.0164,  c: 1/62 ≈ 0.0161
        // top2 → [b, a]
        List<List<Document>> ranked = List.of(
                List.of(doc("a"), doc("b")),
                List.of(doc("b"), doc("c"))
        );

        List<Document> result = RetrievalService.mergeRrf(ranked, 2);

        assertThat(ids(result)).hasSize(2);
        assertThat(result.get(0).getMetadata().get("filename"))
                .as("doc appearing in both queries should rank first")
                .isEqualTo("b");
        assertThat(ids(result)).contains("a").doesNotContain("c");
    }

    @Test
    @DisplayName("RRF — 빈 ranked 리스트 → 빈 결과")
    void rrfEmptyRanked() {
        assertThat(RetrievalService.mergeRrf(List.of(), 5)).isEmpty();
    }

    @Test
    @DisplayName("RRF — topK 0 → 빈 결과")
    void rrfTopKZero() {
        List<List<Document>> ranked = List.of(List.of(doc("a")));
        assertThat(RetrievalService.mergeRrf(ranked, 0)).isEmpty();
    }
}
