package com.example.ragagent.service;

import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.MemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — /admin 검색 진단 수치 패널의 읽기 계층 (3단계)
 *
 * <p>The panel is a diagnostic: a malformed or older blob must degrade to a missing row, never to
 * a broken page. These tests pin that, plus the two summary numbers the panel is scanned by.
 */
class RetrievalMetricsServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RetrievalMetricsService serviceReturning(MemoryRepository.MetricsRow... rows) {
        MemoryService memory = mock(MemoryService.class);
        when(memory.findRecentRetrievalMetrics(anyInt(), anyInt())).thenReturn(List.of(rows));
        return new RetrievalMetricsService(memory, MAPPER);
    }

    private static String json(SourceRef... refs) {
        try {
            return MAPPER.writeValueAsString(List.of(refs));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MemoryRepository.MetricsRow row(long id, String metricsJson) {
        return new MemoryRepository.MetricsRow(id, "2026-08-16 10:00:00", "질문", "M", "local", metricsJson);
    }

    @Test
    @DisplayName("JSON 왕복 — SourceRef의 4개 수치가 그대로 복원된다")
    void roundTripsAllMetrics() {
        String stored = json(new SourceRef("doc.pdf | 1.2", "미리보기", "c1", "d1", 3,
                0.72, 0.18, "vec:2, bm25:5", 0.31));

        var turns = serviceReturning(row(1L, stored)).recent(0, 20);

        assertThat(turns).hasSize(1);
        SourceRef s = turns.get(0).sources().get(0);
        assertThat(s.similarity()).isEqualTo(0.72);
        assertThat(s.retrievalShare()).isEqualTo(0.18);
        assertThat(s.axisRanks()).isEqualTo("vec:2, bm25:5");
        assertThat(s.answerShare()).isEqualTo(0.31);
    }

    @Test
    @DisplayName("요약 수치 — 최고 유사도와 '답변에 실제로 쓰인 출처 수'")
    void summarizesMaxSimilarityAndUsedCount() {
        String stored = json(
                new SourceRef("a", "p", "c1", "d1", 1, 0.55, 0.4, "vec:1", 0.7),
                new SourceRef("b", "p", "c2", "d2", 2, 0.81, 0.4, "vec:2", null),   // 검색됐지만 미사용
                new SourceRef("c", "p", "c3", "d3", 3, null, 0.2, "bm25:1", 0.3));  // 벡터 축엔 없던 청크

        var t = serviceReturning(row(1L, stored)).recent(0, 20).get(0);

        assertThat(t.maxSimilarity()).isEqualTo(0.81);
        assertThat(t.usedSourceCount()).isEqualTo(2);      // answerShare 가 있는 것만
        assertThat(t.sources()).hasSize(3);
    }

    @Test
    @DisplayName("유사도가 하나도 없는 턴은 최고 유사도가 null — 0.0으로 뭉개지 않는다")
    void allNullSimilarityStaysNull() {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, null, 0.5, "bm25:1", 1.0));

        assertThat(serviceReturning(row(1L, stored)).recent(0, 20).get(0).maxSimilarity()).isNull();
    }

    @Test
    @DisplayName("깨진 blob 은 그 행만 건너뛴다 — 패널 전체가 죽지 않는다")
    void malformedBlobSkipsOnlyThatRow() {
        String good = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5));

        var turns = serviceReturning(
                row(1L, "{ 이건 JSON 이 아니다"),
                row(2L, good),
                row(3L, "[]")).recent(0, 20);

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).turnId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("모르는 필드가 있는 blob 도 읽힌다 — 필드가 추가/제거돼도 과거 기록이 안 깨진다")
    void unknownFieldsDoNotBreakParsing() {
        String futureShape = "[{\"label\":\"a\",\"chunk_id\":\"c1\",\"similarity\":0.6,"
                           + "\"some_future_field\":123}]";

        var turns = serviceReturning(row(1L, futureShape)).recent(0, 20);

        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).sources().get(0).similarity()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("질문은 미리보기 길이로 잘린다 — 진단 뷰지 대화 열람 뷰가 아니다")
    void longQuestionTruncated() {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5));
        var row = new MemoryRepository.MetricsRow(
                1L, "2026-08-16", "질".repeat(300), "M", "local", stored);

        String shown = serviceReturning(row).recent(0, 20).get(0).question();

        assertThat(shown).hasSizeLessThan(300).endsWith("…");
    }
}
