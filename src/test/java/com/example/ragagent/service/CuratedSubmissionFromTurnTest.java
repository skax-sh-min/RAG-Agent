package com.example.ragagent.service;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §10.11 — 좋아요가 지식 제안을 경유하도록 만든 서버 기반: 출처 턴의 승계와 프리필.
 *
 * <p>검색 코퍼스로 들어가는 문을 하나로 모으는 설계라, 여기서 지키는 것은 두 가지다.
 * <b>출처는 서버가 확인한다</b>(클라이언트가 "채팅 답변에서 왔다"고 주장할 수 없다) —
 * 그 표시가 관리자의 판단 재료이기 때문이고, <b>프리필 본문은 서버가 턴에서 읽는다</b> —
 * 3,000자 답변은 URL 로 나를 수 없기도 하지만 위조 경로를 아예 만들지 않기 위해서다.
 */
class CuratedSubmissionFromTurnTest {

    private static final String AUTHOR = "u1";
    private static final String THREAD = "t1";
    private static final long   TURN   = 42L;

    private CuratedSubmissionRepository repository;
    private MemoryService memoryService;
    private CuratedSubmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CuratedSubmissionRepository.class);
        memoryService = mock(MemoryService.class);
        AppProperties props = mock(AppProperties.class);
        when(props.chunkSizeSafe()).thenReturn(1_500);
        service = new CuratedSubmissionService(repository, mock(CuratedQaService.class),
                mock(CuratedImageStore.class), memoryService, props, mock(AuditLogger.class));
    }

    private static MemoryRepository.Turn turn(String question, String answer, boolean directMode) {
        return new MemoryRepository.Turn(TURN, question, answer, null, null, 0, 0, 0,
                "local", 1, "LIKE", "N", "인프라", directMode);
    }

    @Test
    @DisplayName("submit — 출처 턴이 본인 것이면 제안에 turn/thread 를 함께 기록한다")
    void submit_withOwnTurn_recordsOrigin() {
        when(memoryService.getTurn(AUTHOR, THREAD, TURN))
                .thenReturn(Optional.of(turn("질문", "답변", false)));

        service.submit(AUTHOR, "제목", "본문", List.of("인프라"), THREAD, TURN);

        verify(repository).insert(AUTHOR, "제목", "본문", "인프라", TURN, THREAD);
    }

    @Test
    @DisplayName("submit — 남의(또는 없는) 턴을 주장하면 출처 없이 손으로 쓴 제안으로 저장된다")
    void submit_withForeignTurn_storesAsHandWritten() {
        when(memoryService.getTurn(AUTHOR, THREAD, TURN)).thenReturn(Optional.empty());

        service.submit(AUTHOR, "제목", "본문", List.of(), THREAD, TURN);

        verify(repository).insert(eq(AUTHOR), eq("제목"), eq("본문"), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("submit — 좋아요 출신은 pending 상한을 적용받지 않는다 (버튼 한 번에는 오류를 띄울 자리가 없다)")
    void submit_fromTurn_skipsPendingCap() {
        when(memoryService.getTurn(AUTHOR, THREAD, TURN))
                .thenReturn(Optional.of(turn("질문", "답변", false)));
        when(repository.countPendingByAuthor(AUTHOR))
                .thenReturn(CuratedSubmissionService.MAX_PENDING_PER_USER + 5);

        service.submit(AUTHOR, "제목", "본문", List.of(), THREAD, TURN);

        verify(repository).insert(AUTHOR, "제목", "본문", "", TURN, THREAD);
        verify(repository, never()).countPendingByAuthor(anyString());
    }

    @Test
    @DisplayName("submit — 손으로 쓴 제안은 pending 상한을 그대로 적용받는다")
    void submit_handWritten_stillHitsPendingCap() {
        when(repository.countPendingByAuthor(AUTHOR))
                .thenReturn(CuratedSubmissionService.MAX_PENDING_PER_USER);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> service.submit(AUTHOR, "제목", "본문", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("검토 대기");
    }

    @Test
    @DisplayName("프리필 — 제목은 질문(200자로 자름), 본문은 답변 전문, 태그는 질문 당시 스코프")
    void prefill_readsFromTurnServerSide() {
        String longQuestion = "질".repeat(500);
        when(memoryService.getTurn(AUTHOR, THREAD, TURN))
                .thenReturn(Optional.of(turn(longQuestion, "답변 전문", true)));

        var prefill = service.prefillFromTurn(AUTHOR, THREAD, TURN).orElseThrow();

        assertThat(prefill.title()).hasSize(CuratedSubmissionService.MAX_TITLE_LEN);
        assertThat(prefill.body()).isEqualTo("답변 전문");
        assertThat(prefill.tags()).isEqualTo("인프라");
        assertThat(prefill.turnId()).isEqualTo(TURN);
        assertThat(prefill.threadId()).isEqualTo(THREAD);
        // Direct 턴임을 저자와 관리자가 같은 표기로 본다 — 문서를 안 본 답변이라는 사실이 판단 재료다.
        assertThat(prefill.modeLabel()).isEqualTo("DN");
    }

    @Test
    @DisplayName("프리필 — 본문 이미지 개수를 미리 센다 (상한 초과는 제출이 아니라 여기서 보여야 한다)")
    void prefill_countsImageMarkers() {
        String answer = "앞\n\n[이미지: images/doc1/a.png]\n\n뒤\n\n[이미지: images/doc1/b.png]\n";
        when(memoryService.getTurn(AUTHOR, THREAD, TURN))
                .thenReturn(Optional.of(turn("질문", answer, false)));

        assertThat(service.prefillFromTurn(AUTHOR, THREAD, TURN).orElseThrow().imageCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("프리필 — 없는(또는 남의) 턴이면 비어 있고, 페이지는 빈 폼으로 뜬다")
    void prefill_unknownTurn_isEmpty() {
        when(memoryService.getTurn(AUTHOR, THREAD, TURN)).thenReturn(Optional.empty());
        assertThat(service.prefillFromTurn(AUTHOR, THREAD, TURN)).isEmpty();
    }
}
