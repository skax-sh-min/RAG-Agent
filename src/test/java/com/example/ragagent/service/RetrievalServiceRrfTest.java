package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA — RetrievalService RRF merge
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
    @DisplayName("RRF — 두 번째 쿼리의 rank-0 문서가 첫 번째 쿼리의 rank-1 문서보다 높은 점수 획득")
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

    // ── weighted RRF (§10.2) ────────────────────────────────────────────

    @Test
    @DisplayName("가중 RRF — 벡터축 그룹 정규화로 멀티쿼리 축 개수가 많아도 키워드축과 동등 비중")
    void weightedRrf_groupNormalizesVectorAxes() {
        // 3 vector axes (e.g. MultiQuery original+2), doc "x" ranks 0 in every one of them.
        // keyword axis has a single hit "y" at rank 0. Without group normalization x would
        // score 3x a lone keyword hit at equal per-axis weight; with normalization both
        // axes contribute like a single axis, so x and y tie.
        List<List<Document>> vectorRanked = List.of(
                List.of(doc("x")), List.of(doc("x")), List.of(doc("x")));
        List<Document> keywordRanked = List.of(doc("y"));

        List<Document> result = RetrievalService.mergeRrf(vectorRanked, keywordRanked, 2, 60, 1.0);

        assertThat(ids(result))
                .as("group-normalized vector axes should not out-score a single keyword hit at equal weight")
                .containsExactlyInAnyOrder("x", "y");
    }

    @Test
    @DisplayName("가중 RRF — keywordWeight 상향 시 키워드 축 문서가 우선 순위 획득")
    void weightedRrf_keywordWeightBoostsKeywordAxis() {
        List<List<Document>> vectorRanked = List.of(List.of(doc("x")));
        List<Document> keywordRanked = List.of(doc("y"));

        List<Document> result = RetrievalService.mergeRrf(vectorRanked, keywordRanked, 1, 60, 3.0);

        assertThat(result.get(0).getMetadata().get("filename")).isEqualTo("y");
    }

    @Test
    @DisplayName("가중 RRF — 키워드축 없음(하이브리드 비활성) → 2-arg 오버로드와 동일 순위")
    void weightedRrf_noKeywordAxisMatchesUnweightedOverload() {
        List<List<Document>> vectorRanked = List.of(
                List.of(doc("a"), doc("b")),
                List.of(doc("b"), doc("c")));

        List<Document> weighted = RetrievalService.mergeRrf(vectorRanked, List.of(), 3, 60, 1.0);
        List<Document> unweighted = RetrievalService.mergeRrf(vectorRanked, 3);

        assertThat(ids(weighted)).isEqualTo(ids(unweighted));
    }

    // ── stable docKey ────────────────────────────────────────────────────

    @Test
    @DisplayName("doc_id+chunk_index 있으면 안정 키 사용")
    void docKey_usesDocIdAndChunkIndex() {
        Document d = new Document("content", Map.<String, Object>of("doc_id", "D1", "chunk_index", 3));
        assertThat(RetrievalService.docKey(d)).isEqualTo("D1:3");
    }

    @Test
    @DisplayName("chunk_index 타입(Integer/Double/String) 무관 동일 키로 정규화")
    void docKey_normalizesNumericTypes() {
        Document asInt = new Document("x", Map.<String, Object>of("doc_id", "D1", "chunk_index", 3));
        Document asStr = new Document("y", Map.<String, Object>of("doc_id", "D1", "chunk_index", "3"));
        Document asDbl = new Document("z", Map.<String, Object>of("doc_id", "D1", "chunk_index", 3.0));
        assertThat(RetrievalService.docKey(asInt)).isEqualTo("D1:3");
        assertThat(RetrievalService.docKey(asStr)).isEqualTo("D1:3");
        assertThat(RetrievalService.docKey(asDbl)).isEqualTo("D1:3");
    }

    @Test
    @DisplayName("앞 50자 동일·chunk_index 다른 인접 청크가 충돌 없이 모두 보존")
    void mergeRrf_keepsAdjacentChunksWithSamePreview() {
        String shared = "동일한 앞부분 텍스트입니다 ".repeat(5); // >50자, 프리뷰 동일
        Document c0 = new Document(shared + "A",
                Map.<String, Object>of("doc_id", "D", "page_or_slide", "1", "chunk_index", 0));
        Document c1 = new Document(shared + "B",
                Map.<String, Object>of("doc_id", "D", "page_or_slide", "1", "chunk_index", 1));

        List<Document> result = RetrievalService.mergeRrf(List.of(List.of(c0, c1)), 5);

        assertThat(result)
                .as("legacy preview 키였다면 1개로 충돌, 키로는 2개 모두 보존")
                .hasSize(2);
    }

    // ── curated-Q&A axis (§10.10) ──────────────────────────────────────────

    @Test
    @DisplayName("큐레이션 축 — curatedWeight 상향 시 큐레이션 문서가 우선 순위 획득")
    void weightedRrf_curatedWeightBoostsCuratedAxis() {
        List<List<Document>> vectorRanked = List.of(List.of(doc("x")));
        List<Document> curatedRanked = List.of(doc("curated-1"));

        List<Document> result = RetrievalService.mergeRrf(
                vectorRanked, List.of(), curatedRanked, 1, 60, 1.0, 3.0);

        assertThat(result.get(0).getMetadata().get("filename")).isEqualTo("curated-1");
    }

    @Test
    @DisplayName("큐레이션 축 없음(비활성/무히트) → 5-arg 오버로드와 동일 순위")
    void weightedRrf_noCuratedAxisMatchesFiveArgOverload() {
        List<List<Document>> vectorRanked = List.of(
                List.of(doc("a"), doc("b")),
                List.of(doc("b"), doc("c")));
        List<Document> keywordRanked = List.of(doc("b"));

        List<Document> withEmptyCurated = RetrievalService.mergeRrf(
                vectorRanked, keywordRanked, List.of(), 3, 60, 1.0, 5.0);
        List<Document> fiveArg = RetrievalService.mergeRrf(vectorRanked, keywordRanked, 3, 60, 1.0);

        assertThat(ids(withEmptyCurated)).isEqualTo(ids(fiveArg));
    }

    @Test
    @DisplayName("큐레이션 축 — 벡터/키워드/큐레이션 세 축 모두에 등장하는 문서가 최상위 점수 획득")
    void weightedRrf_docInAllThreeAxesRanksFirst() {
        List<List<Document>> vectorRanked = List.of(List.of(doc("shared"), doc("only-vector")));
        List<Document> keywordRanked = List.of(doc("shared"), doc("only-keyword"));
        List<Document> curatedRanked = List.of(doc("shared"), doc("only-curated"));

        List<Document> result = RetrievalService.mergeRrf(
                vectorRanked, keywordRanked, curatedRanked, 4, 60, 1.0, 1.0);

        assertThat(result.get(0).getMetadata().get("filename")).isEqualTo("shared");
    }

    @Test
    @DisplayName("큐레이션 doc_id 합성 키(curated:{id}:0) — 실제 문서와 충돌하지 않는다")
    void docKey_curatedSyntheticIdDoesNotCollideWithRealDoc() {
        Document curated = new Document("답변 본문",
                Map.<String, Object>of("doc_id", "curated:1", "chunk_index", 0));
        Document real = new Document("문서 본문",
                Map.<String, Object>of("doc_id", "D1", "chunk_index", 0));

        assertThat(RetrievalService.docKey(curated)).isEqualTo("curated:1:0");
        assertThat(RetrievalService.docKey(curated)).isNotEqualTo(RetrievalService.docKey(real));
    }

    // ── 큐레이션 / 지식 제안 축 분리 ──────────────────────────────────────────

    /**
     * §10.11 — 큐레이션은 축 하나다. 예전에는 좋아요 승격과 지식 제안을 {@code CURATED_ORIGIN} 으로
     * 갈라 서로 다른 가중치를 줬는데, 그 구분의 근거("앱이 만든 무검토 출력" 대 "사람이 쓴 텍스트")가
     * 모든 유입에 사람 편집 + 관리자 승인이 걸리면서 사라졌다. 두 출처가 이제 <b>같은 가중치로
     * 경쟁</b>하고, 순위만이 순서를 정한다.
     */
    @Test
    @DisplayName("큐레이션 축은 하나다 — 출처가 달라도 같은 가중치로 순위만 겨룬다")
    void curatedOriginsShareOneAxis() {
        Document fromLike = doc("like");
        Document fromSubmission = doc("submission");

        List<Document> fused = RetrievalService.mergeRrf(
                List.of(), List.of(), List.of(fromLike, fromSubmission), 5, 60, 0.0, 1.5);

        assertThat(ids(fused)).containsExactly("like", "submission");
    }

    // ── 검색 진단 수치 (1단계) ──────────────────────────────────────────────

    private static Document scored(String id, double score) {
        return Document.builder()
                .text("content-" + id)
                .metadata(Map.of("filename", id, "page_or_slide", "1"))
                .score(score)
                .build();
    }

    @Test
    @DisplayName("메트릭 수집이 순위를 바꾸지 않는다 — mergeRrf와 mergeRrfScored의 문서 순서 동일")
    void scoredVariant_rankingIsIdentical() {
        List<List<Document>> vectorRanked = List.of(List.of(doc("a"), doc("b")), List.of(doc("c")));
        List<Document> keyword = List.of(doc("b"), doc("d"));

        List<Document> plain = RetrievalService.mergeRrf(
                vectorRanked, keyword, List.of(), 4, 60, 1.0, 1.2);
        RetrievalService.RrfResult scored = RetrievalService.mergeRrfScored(
                vectorRanked, keyword, List.of(), 4, 60, 1.0, 1.2);

        assertThat(ids(scored.docs())).isEqualTo(ids(plain));
    }

    @Test
    @DisplayName("키워드 축이 먼저 도달한 청크도 벡터 유사도를 잃지 않는다")
    void keywordAxisDoesNotBlankOutVectorSimilarity() {
        // 같은 청크가 키워드 축 1위, 벡터 축 2위. 키워드 축 Document에는 score가 없다(BM25는
        // 거리값을 만들지 않음) — putIfAbsent였다면 유사도가 통째로 사라지던 자리.
        Document keywordSide = doc("shared");           // score 없음
        Document vectorSide  = scored("shared", 0.83);  // score 있음

        RetrievalService.RrfResult r = RetrievalService.mergeRrfScored(
                List.of(List.of(scored("other", 0.9), vectorSide)),
                List.of(keywordSide),
                List.of(), 5, 60, 1.0, 0.0);

        String key = RetrievalService.docKey(vectorSide);
        assertThat(r.metrics().get(key).vectorSimilarity()).isEqualTo(0.83);
        assertThat(r.metrics().get(key).axisRanks()).isEqualTo("vec:2, bm25:1");
    }

    @Test
    @DisplayName("여러 벡터 축(MultiQuery)에 걸친 청크는 최고 유사도·최선 순위를 취한다")
    void multipleVectorAxes_takeBestSimilarityAndRank() {
        RetrievalService.RrfResult r = RetrievalService.mergeRrfScored(
                List.of(List.of(doc("x"), scored("a", 0.55)),   // a: 2위, 0.55
                        List.of(scored("a", 0.71))),            // a: 1위, 0.71
                List.of(), List.of(), 5, 60, 1.0, 0.0);

        var m = r.metrics().get(RetrievalService.docKey(doc("a")));
        assertThat(m.vectorSimilarity()).isEqualTo(0.71);
        assertThat(m.axisRanks()).isEqualTo("vec:1");
    }

    @Test
    @DisplayName("벡터 축에 없던 청크는 유사도가 null — 0.0이 아니다")
    void keywordOnlyChunk_hasNullSimilarity() {
        RetrievalService.RrfResult r = RetrievalService.mergeRrfScored(
                List.of(List.of(scored("a", 0.9))),
                List.of(doc("kw-only")),
                List.of(), 5, 60, 1.0, 0.0);

        var m = r.metrics().get(RetrievalService.docKey(doc("kw-only")));
        assertThat(m.vectorSimilarity()).isNull();
        assertThat(m.axisRanks()).isEqualTo("bm25:1");
    }

    @Test
    @DisplayName("RRF 점수는 축 가중치를 반영하고, 상위 문서가 더 높은 점수를 갖는다")
    void rrfScoresAreOrderedAndWeighted() {
        RetrievalService.RrfResult r = RetrievalService.mergeRrfScored(
                List.of(List.of(doc("a"), doc("b"))),
                List.of(), List.of(), 5, 60, 1.0, 0.0);

        double a = r.metrics().get(RetrievalService.docKey(doc("a"))).rrfScore();
        double b = r.metrics().get(RetrievalService.docKey(doc("b"))).rrfScore();
        assertThat(a).isGreaterThan(b);
        assertThat(a).isEqualTo(1.0 / 61);
    }
}
