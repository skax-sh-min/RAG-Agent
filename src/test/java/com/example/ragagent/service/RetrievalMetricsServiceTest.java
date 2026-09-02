package com.example.ragagent.service;

import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.MemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
        // 4-인자 형태를 스텁한다 — §6.25 이후 서비스는 필터 두 개를 항상 넘긴다(없으면 null).
        when(memory.findRecentRetrievalMetrics(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(rows));
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
                0.72, 0.18, "vec:2, bm25:5", 0.31, null, false));

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
                new SourceRef("a", "p", "c1", "d1", 1, 0.55, 0.4, "vec:1", 0.7, null, false),
                new SourceRef("b", "p", "c2", "d2", 2, 0.81, 0.4, "vec:2", null, null, false),   // 검색됐지만 미사용
                new SourceRef("c", "p", "c3", "d3", 3, null, 0.2, "bm25:1", 0.3, null, false));  // 벡터 축엔 없던 청크

        var t = serviceReturning(row(1L, stored)).recent(0, 20).get(0);

        assertThat(t.maxSimilarity()).isEqualTo(0.81);
        assertThat(t.usedSourceCount()).isEqualTo(2);      // answerShare 가 있는 것만
        assertThat(t.sources()).hasSize(3);
    }

    @Test
    @DisplayName("유사도가 하나도 없는 턴은 최고 유사도가 null — 0.0으로 뭉개지 않는다")
    void allNullSimilarityStaysNull() {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, null, 0.5, "bm25:1", 1.0, null, false));

        assertThat(serviceReturning(row(1L, stored)).recent(0, 20).get(0).maxSimilarity()).isNull();
    }

    @Test
    @DisplayName("깨진 blob 은 그 행만 건너뛴다 — 패널 전체가 죽지 않는다")
    void malformedBlobSkipsOnlyThatRow() {
        String good = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5, null, false));

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

    // ── 복원 경로(/chat/{threadId}) 병합 ──────────────────────────────────────

    @Test
    @DisplayName("복원 시 저장된 수치를 chunkId로 병합하되, 라벨·미리보기는 현재 값을 유지한다")
    void enrichMergesMetricsButKeepsLiveLabels() {
        // 저장 당시의 라벨은 낡았을 수 있다(문서 표시 이름 변경, 청크 재인덱싱 등).
        String stored = json(new SourceRef("옛 라벨", "옛 미리보기", "c1", "d1", 1,
                0.72, 0.18, "vec:2", 0.31, null, false));
        MemoryService memory = mock(MemoryService.class);
        when(memory.findRetrievalMetricsByTurnIds(List.of(7L))).thenReturn(Map.of(7L, stored));
        var service = new RetrievalMetricsService(memory, MAPPER);

        List<SourceRef> live = List.of(new SourceRef("현재 라벨", "현재 미리보기", "c1", "d1", 1));
        SourceRef merged = service.enrich(Map.of(7L, live)).get(7L).get(0);

        assertThat(merged.label()).isEqualTo("현재 라벨");        // 현재 값이 권위
        assertThat(merged.preview()).isEqualTo("현재 미리보기");
        assertThat(merged.similarity()).isEqualTo(0.72);          // 수치만 병합
        assertThat(merged.answerShare()).isEqualTo(0.31);
    }

    @Test
    @DisplayName("저장된 수치가 없는 출처·턴은 그대로 통과한다 — 목록에서 사라지지 않는다")
    void enrichLeavesUnmatchedSourcesIntact() {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5, null, false));
        MemoryService memory = mock(MemoryService.class);
        when(memory.findRetrievalMetricsByTurnIds(anyList())).thenReturn(Map.of(7L, stored));
        var service = new RetrievalMetricsService(memory, MAPPER);

        var result = service.enrich(Map.of(
                7L, List.of(new SourceRef("a", "p", "c1", "d1", 1),
                            new SourceRef("b", "p", "c-없음", "d2", 2)),   // 저장 기록에 없는 청크
                8L, List.of(new SourceRef("c", "p", "c3", "d3", 3))));      // 수치가 없는 턴(DB 재사용 등)

        assertThat(result.get(7L)).hasSize(2);
        assertThat(result.get(7L).get(0).similarity()).isEqualTo(0.5);
        assertThat(result.get(7L).get(1).similarity()).isNull();
        assertThat(result.get(8L)).hasSize(1);
        assertThat(result.get(8L).get(0).similarity()).isNull();
    }

    @Test
    @DisplayName("질문은 미리보기 길이로 잘린다 — 진단 뷰지 대화 열람 뷰가 아니다")
    void longQuestionTruncated() {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5, null, false));
        var row = new MemoryRepository.MetricsRow(
                1L, "2026-08-16", "질".repeat(300), "M", "local", stored);

        String shown = serviceReturning(row).recent(0, 20).get(0).question();

        assertThat(shown).hasSizeLessThan(300).endsWith("…");
    }

    // ── §6.25 — 사용자/대화 표기와 C 턴 예외 ──────────────────────────────────

    private static MemoryRepository.MetricsRow rowWithThread(
            String mode, String userId, String threadId, String threadTitle) {
        String stored = json(new SourceRef("a", "p", "c1", "d1", 1, 0.5, 0.5, "vec:1", 0.5, null, false));
        return new MemoryRepository.MetricsRow(1L, "2026-08-16", "질문", mode, "local",
                stored, userId, threadId, threadTitle);
    }

    /**
     * 창의(C) 턴은 평가기가 usedDocs 를 묻지 않아 응답 참여도가 구조적으로 0이다 —
     * 0/N 으로 그리면 "검색이 아무것도 못 찾았다"로 읽혀 영원히 쫓게 될 버그가 된다(§6.24 이슈 b).
     */
    @Test
    @DisplayName("C 턴은 응답 참여도가 '해당 없음' — 0/N 으로 그리지 않는다")
    void creativeTurnsHaveNoAttribution() {
        assertThat(serviceReturning(rowWithThread("C", "u1", "t1", "제목"))
                .recent(0, 20).get(0).attributionApplies()).isFalse();
    }

    @Test
    @DisplayName("N·S 턴과 구 M/L·null 모드는 참여도가 유효하다")
    void nonCreativeTurnsKeepAttribution() {
        for (String mode : new String[]{"N", "S", "M", "L", null, "알수없음"}) {
            assertThat(serviceReturning(rowWithThread(mode, "u1", "t1", "제목"))
                    .recent(0, 20).get(0).attributionApplies())
                    .as("mode=%s", mode)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("대화 라벨 — 제목의 레거시 [version] 접두를 떼고, 제목이 없으면 thread id 로 떨어진다")
    void threadLabelStripsPrefixAndFallsBackToId() {
        assertThat(serviceReturning(rowWithThread("N", "u1", "t1", "[latest] 인덱싱 질문"))
                .recent(0, 20).get(0).threadLabel()).isEqualTo("인덱싱 질문");

        // thread_meta 행이 사라진 턴 — 진단은 유효하므로 목록에 남고, 라벨만 id 로 대신한다.
        assertThat(serviceReturning(rowWithThread("N", "u1", "사라진-대화", null))
                .recent(0, 20).get(0).threadLabel()).isEqualTo("사라진-대화");
    }

    @Test
    @DisplayName("소유자 id 축약 — 대화 목록 패널과 같은 규칙")
    void shortensOwnerIdLikeTheConversationPanel() {
        var t = serviceReturning(rowWithThread("N", "guest-a1b2c3d4e5f6", "t1", "제목"))
                .recent(0, 20).get(0);

        assertThat(t.shortUserId()).endsWith("…");
        assertThat(t.userId()).isEqualTo("guest-a1b2c3d4e5f6");   // 툴팁용 원본
    }

    @Test
    @DisplayName("필터는 저장소로 그대로 전달되고, count 도 같은 필터를 받는다")
    void filtersReachTheRepositoryAndTheCount() {
        MemoryService memory = mock(MemoryService.class);
        when(memory.findRecentRetrievalMetrics(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        var service = new RetrievalMetricsService(memory, MAPPER);

        service.recent("u1", "t1", 0, 20);
        service.count("u1", "t1");

        org.mockito.Mockito.verify(memory).findRecentRetrievalMetrics("u1", "t1", 0, 20);
        org.mockito.Mockito.verify(memory).countRetrievalMetrics("u1", "t1");
    }
}
