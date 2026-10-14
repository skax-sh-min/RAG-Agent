package com.example.ragagent.service;

import com.example.ragagent.model.MetaKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큐레이션 축의 문턱({@link RetrievalService#gateCurated}) — 풀이 작아서 문턱만 넘으면 그 축의 1위가 되고, RRF 는 순위만 보므로
 * 관련 없는 항목 하나가 거의 모든 답변의 출처에 끼던 것(골든셋 26문항 중 25)을 막는다.
 */
class RetrievalCuratedGateTest {

    private static Document vec(String id, double score) {
        return Document.builder().id(id).text("t").metadata(Map.of()).score(score).build();
    }

    private static Document fts(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of(MetaKey.VERSION, "curated")).build();
    }

    private static final String DEBUG_LOG_ENTRY =
            "디버그 로그 API 사용시 주의사항 — 운영 환경에서 디버그 로그를 켜면 어떻게 성능이 떨어지는지";

    @Test
    @DisplayName("벡터 절반 — 최소 유사도 미만은 버린다(문서 임계값은 저장소가 이미 걸었다)")
    void vectorHalfNeedsTheCuratedFloor() {
        var gated = RetrievalService.gateCurated(List.of(vec("a", 0.55), vec("b", 0.44)), List.of(), "질문", 0.5);

        assertThat(gated.vector()).extracting(Document::getId).containsExactly("a");
    }

    @Test
    @DisplayName("최소 유사도 0 이면 벡터 절반은 그대로다")
    void zeroFloorKeepsTheVectorHalf() {
        var gated = RetrievalService.gateCurated(List.of(vec("a", 0.36)), List.of(), "질문", 0.0);

        assertThat(gated.vector()).hasSize(1);
    }

    @Test
    @DisplayName("키워드 절반 — 흔한 낱말(어떻게) 하나만 맞은 항목은 받지 않는다")
    void keywordHalfRejectsAFunctionWordMatch() {
        var gated = RetrievalService.gateCurated(List.of(),
                List.of(fts("curated-1", DEBUG_LOG_ENTRY)), "SQLite WAL 모드는 어떻게 켜나요?", 0.5);

        assertThat(gated.keyword()).isEmpty();
    }

    @Test
    @DisplayName("키워드 절반 — 질문의 내용어가 둘 이상 들어 있으면 받고 큐레이션 표식을 찍는다")
    void keywordHalfAcceptsTwoContentWords() {
        var gated = RetrievalService.gateCurated(List.of(),
                List.of(fts("curated-1", DEBUG_LOG_ENTRY)), "디버그 로그를 켜면 성능이 떨어지나요?", 0.5);

        assertThat(gated.keyword()).extracting(Document::getId).containsExactly("curated-1");
        assertThat(gated.keyword().get(0).getMetadata()).containsEntry(MetaKey.DOC_TYPE, "curated_qa");
    }

    @Test
    @DisplayName("영문·숫자 낱말은 낱말 단위로만 맞는다 — PU·DU 가 CPU·pmEDU04 안에서 맞지 않는다(한글 어간은 조사 붙은 모양도 맞는다)")
    void asciiWordsMatchWholeWordsOnly() {
        String text = "CPU 부하가 생긴다 — pmEDU04R6002 예제. NOTX로 실행한다. 역할을 나눈다";

        assertThat(RetrievalService.matchedContentWords(text, List.of("pu", "du"))).isZero();
        assertThat(RetrievalService.matchedContentWords(text, List.of("cpu", "notx", "역할"))).isEqualTo(3);
        var gated = RetrievalService.gateCurated(List.of(),
                List.of(fts("curated-1", text)), "PU, FU, DU는 각각 어떤 역할을 담당하는가?", 0.5);
        assertThat(gated.keyword()).as("역할 하나만 진짜로 맞았다").isEmpty();
    }

    @Test
    @DisplayName("내용어가 하나뿐인 질문은 그 하나가 맞으면 받는다 · 내용어가 없으면 키워드로만 찾힌 항목은 받지 않는다")
    void neededMatchesFollowTheQuestion() {
        var one = RetrievalService.gateCurated(List.of(),
                List.of(fts("curated-1", DEBUG_LOG_ENTRY)), "디버그는?", 0.5);
        assertThat(one.keyword()).hasSize(1);

        var none = RetrievalService.gateCurated(List.of(),
                List.of(fts("curated-1", DEBUG_LOG_ENTRY)), "어떻게 해?", 0.5);
        assertThat(none.keyword()).isEmpty();
    }

    @Test
    @DisplayName("벡터에서 떨어진 항목도 키워드 조건을 채우면 키워드 절반으로 들어온다 · 벡터에 남은 항목은 두 번 들어오지 않는다")
    void vectorRejectCanComeBackThroughKeywords() {
        var gated = RetrievalService.gateCurated(
                List.of(vec("curated-1", 0.42), vec("curated-2", 0.6)),
                List.of(fts("curated-1", DEBUG_LOG_ENTRY), fts("curated-2", DEBUG_LOG_ENTRY)),
                "디버그 로그 성능", 0.5);

        assertThat(gated.vector()).extracting(Document::getId).containsExactly("curated-2");
        assertThat(gated.keyword()).extracting(Document::getId).containsExactly("curated-1");
    }
}
