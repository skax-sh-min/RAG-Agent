package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 좋아요 → 지식 제안 프리필에서 <b>{@code ## 요약} 이 본문에서 요약 칸으로 옮겨간다</b>는 것,
 * 그리고 등록 시 <b>빈 칸이 자동으로 채워진다</b>는 것.
 *
 * <p>둘 다 같은 목적이다 — 승인되면 요약은 BM25 검색 텍스트 앞에 붙고 키워드는 {@code chunk_fts}
 * 의 전용 컬럼이 된다. 요약이 본문에 그대로 남아 있으면 같은 문장이 그 축에 두 번 들어가고,
 * 키워드가 비어 있으면 이 항목은 어휘 매칭에서 아무 신호도 갖지 못한다.
 */
class CuratedPrefillSummaryTest {

    private static final String ANSWER = """
            ## 요약
            배포는 ArgoCD 가 스테이징에 자동 반영하고 프로덕션은 수동 승인이다.

            ## 상세 설명
            main 에 머지되면 GitHub Actions 가 이미지를 빌드한다.

            ## 참고
            [deploy.md | p.3]""";

    private CuratedSubmissionRepository repository;
    private MemoryService memoryService;
    private KeywordExtractor extractor;
    private CuratedSubmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedSubmissionRepository.class);
        memoryService = mock(MemoryService.class);
        extractor = mock(KeywordExtractor.class);
        CuratedImageStore imageStore = mock(CuratedImageStore.class);

        AppProperties props = mock(AppProperties.class);
        when(props.chunkSizeSafe()).thenReturn(1500);

        service = new CuratedSubmissionService(repository, mock(CuratedQaService.class), imageStore,
                memoryService, props, mock(AuditLogger.class), extractor);
    }

    private void turnReturns(String answer) {
        MemoryRepository.Turn turn = mock(MemoryRepository.Turn.class);
        when(turn.question()).thenReturn("배포는 어떻게 하나요");
        when(turn.answer()).thenReturn(answer);
        when(turn.selectedTags()).thenReturn("인프라");
        when(turn.responseModeLabel()).thenReturn("RN");
        when(memoryService.getTurn(anyString(), anyString(), anyLong())).thenReturn(Optional.of(turn));
    }

    private void extractorReturns(String summary, String keywords) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.CHUNK_CONTEXT, summary);
        meta.put(MetaKey.EXCERPT_KEYWORDS, keywords);
        when(extractor.enrichSingle(any())).thenReturn(new Document("본문", meta));
    }

    // ── 프리필 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("프리필 — '## 요약' 본문은 요약 칸으로 가고 본문에서는 빠진다")
    void prefill_movesTheSummarySectionOutOfTheBody() {
        turnReturns(ANSWER);

        CuratedSubmissionService.TurnPrefill p =
                service.prefillFromTurn("u1", "t1", 42L).orElseThrow();

        assertThat(p.summary())
                .isEqualTo("배포는 ArgoCD 가 스테이징에 자동 반영하고 프로덕션은 수동 승인이다.");
        assertThat(p.body())
                .as("같은 문장이 두 곳에 남으면 승인 후 BM25 입력에 두 번 들어간다")
                .doesNotContain("배포는 ArgoCD 가 스테이징에 자동 반영")
                .contains("## 상세 설명");
    }

    /**
     * 화면이 보여 주는 개수는 <b>실제 등록될 본문</b> 기준이어야 한다 — 제출 단계의
     * {@code validateImageCount(cleanBody)} 가 세는 것도, 승인 시 Vision 을 부르게 될 것도 그쪽이다.
     * 원문을 세면 요약 섹션에 마커가 있을 때 상한을 넘었다고 미리 겁을 준다.
     */
    @Test
    @DisplayName("프리필 — 이미지 개수는 요약을 뗀 본문 기준이다 (원문 기준이 아니다)")
    void prefill_imageCountFollowsTheStrippedBody() {
        turnReturns("""
                ## 요약
                배포 흐름 개요다.

                [이미지: images/submissions/aaaa.png]

                ## 상세 설명
                본문 설명.

                [이미지: images/submissions/bbbb.png]""");

        CuratedSubmissionService.TurnPrefill p =
                service.prefillFromTurn("u1", "t1", 42L).orElseThrow();

        assertThat(p.imageCount())
                .as("요약 안의 마커는 본문에서 함께 빠졌으므로 세면 안 된다")
                .isEqualTo(1);
    }

    /** Direct·meta 답변에는 고정 형식이 없어 요약 헤딩이 아예 없다 — 본문을 건드리면 안 된다. */
    @Test
    @DisplayName("프리필 — 요약 섹션이 없는 답변(Direct/meta)은 본문 그대로, 요약은 빈 문자열")
    void prefill_answerWithoutSummarySection_isLeftAlone() {
        turnReturns("안녕하세요! 무엇을 도와드릴까요?");

        CuratedSubmissionService.TurnPrefill p =
                service.prefillFromTurn("u1", "t1", 42L).orElseThrow();

        assertThat(p.summary()).isEmpty();
        assertThat(p.body()).isEqualTo("안녕하세요! 무엇을 도와드릴까요?");
    }

    // ── 등록 시 자동 채움 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("등록 — 키워드를 비워 두면 본문에서 자동으로 채워 저장한다")
    void submit_fillsEmptyKeywords() {
        extractorReturns("생성된 요약", "배포, ArgoCD");
        when(repository.insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        service.submit("u1", "제목", "본문입니다.", List.of(), null, null, "직접 쓴 요약", "");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keywords = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(anyString(), anyString(), anyString(), any(), any(), any(),
                summary.capture(), keywords.capture());
        assertThat(keywords.getValue()).isEqualTo("배포, ArgoCD");
        assertThat(summary.getValue()).as("사람이 쓴 값은 덮지 않는다").isEqualTo("직접 쓴 요약");
    }

    @Test
    @DisplayName("등록 — 두 칸이 이미 차 있으면 LLM 을 부르지 않는다")
    void submit_bothFilled_makesNoLlmCall() {
        when(repository.insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1L);

        service.submit("u1", "제목", "본문입니다.", List.of(), null, null, "요약", "키워드");

        verify(extractor, never()).enrichSingle(any());
    }

    /**
     * 사용자가 요청한 일은 "제안 등록"이다. 부속값 추출이 실패했다고 등록 자체를 실패시키면
     * 부르지도 않은 기능 때문에 쓴 글을 잃는다.
     */
    @Test
    @DisplayName("등록 — 자동 생성이 실패해도 제안은 그대로 등록된다")
    void submit_survivesAnEnrichmentFailure() {
        when(extractor.enrichSingle(any())).thenThrow(new RuntimeException("LLM down"));
        when(repository.insert(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(7L);

        long id = service.submit("u1", "제목", "본문입니다.", List.of(), null, null, "", "");

        assertThat(id).isEqualTo(7L);
        ArgumentCaptor<String> keywords = ArgumentCaptor.forClass(String.class);
        verify(repository).insert(anyString(), anyString(), anyString(), any(), any(), any(),
                any(), keywords.capture());
        assertThat(keywords.getValue()).as("비워 둔 채로 등록된다 — 나중에 채우면 된다").isNull();
    }
}
