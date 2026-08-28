package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.ThreadAdminRepository;
import com.example.ragagent.repository.ThreadAdminRepository.Sort;
import com.example.ragagent.repository.ThreadAdminRepository.Summary;
import com.example.ragagent.repository.ThreadAdminRepository.ThreadRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — §6.25 {@code ThreadAdminService}: the thin layer between the aggregate and the panel.
 *
 * <p>Covers the four things that aren't SQL:
 *  - paging arguments are clamped before they reach the repository (a hand-edited
 *    {@code ?limit=100000} on an admin URL must not turn into that query)
 *  - blank user filter is normalized to null, so the repository sees one "no filter" form
 *  - 방문자 분리 힌트 — no-auth + shared 일 때만 켜진다
 *  - 표시 파생값(제목 접두 제거·소유자 축약·검색 없는 대화 표시)
 */
class ThreadAdminServiceTest {

    private ThreadAdminRepository repository;
    private AppProperties props;
    private ThreadAdminService service;

    private CuratedQaService curatedQaService;
    private MemoryService memoryService;
    private ThreadMetaService threadMetaService;

    @BeforeEach
    void setUp() {
        repository = mock(ThreadAdminRepository.class);
        props = mock(AppProperties.class);
        curatedQaService = mock(CuratedQaService.class);
        memoryService = mock(MemoryService.class);
        threadMetaService = mock(ThreadMetaService.class);
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(true, false));
        when(repository.findAll(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(repository.summary()).thenReturn(new Summary(0, 0, 0, 0, 0));
        when(repository.distinctUserIds()).thenReturn(List.of());
        service = new ThreadAdminService(repository, props, curatedQaService,
                memoryService, threadMetaService);
    }

    private static ThreadRow row(String title, String userId, int turns, int diag) {
        return new ThreadRow("t1", userId, title, "", "2026-01-01", "2026-02-01",
                "2026-01-31", turns, 0, 0, diag, 0, 0);
    }

    @Test
    @DisplayName("limit 은 상한으로, offset 은 0 아래로 못 내려가게 조인다")
    void clampsPagingArguments() {
        service.panel(null, null, -50, 100_000);

        verify(repository).findAll(isNull(), eq(Sort.RECENT),
                eq(0), eq(ThreadAdminService.MAX_LIMIT));
    }

    @Test
    @DisplayName("limit 0/음수는 기본값으로 되돌린다")
    void nonPositiveLimitFallsBackToDefault() {
        service.panel(null, null, 0, 0);

        verify(repository).findAll(isNull(), eq(Sort.RECENT),
                eq(0), eq(ThreadAdminService.DEFAULT_LIMIT));
    }

    @Test
    @DisplayName("빈 사용자 필터는 null 로 정규화된다 — 목록과 count 가 같은 '필터 없음'을 본다")
    void blankUserFilterIsNormalizedToNull() {
        service.panel("   ", "turns", 0, 20);

        verify(repository).findAll(isNull(), eq(Sort.TURNS), eq(0), eq(20));
        verify(repository).count(isNull());
    }

    @Test
    @DisplayName("사용자 필터는 trim 된 뒤 목록과 count 에 똑같이 전달된다")
    void userFilterIsTrimmedAndAppliedToBoth() {
        var view = service.panel(" u1 ", null, 0, 20);

        verify(repository).findAll(eq("u1"), eq(Sort.RECENT), eq(0), eq(20));
        verify(repository).count(eq("u1"));
        assertThat(view.userFilter()).isEqualTo("u1");
    }

    @Test
    @DisplayName("방문자 분리 힌트 — no-auth + shared 일 때만 켜진다")
    void visitorSeparationHintOnlyInNoAuthSharedMode() {
        // 인증 켜짐: guest-identity 는 의미가 없다(리졸버가 컨텍스트에 없다).
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(true, false));
        assertThat(service.panel(null, null, 0, 20).visitorSeparationOff()).isFalse();

        // no-auth + shared: 전 방문자가 한 사용자로 뭉친다 → 힌트 필요.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(
                false, false, AppProperties.GuestIdentity.SHARED));
        assertThat(service.panel(null, null, 0, 20).visitorSeparationOff()).isTrue();

        // no-auth + hybrid: 방문자별로 갈린다 → 힌트 불필요.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(
                false, false, AppProperties.GuestIdentity.HYBRID));
        assertThat(service.panel(null, null, 0, 20).visitorSeparationOff()).isFalse();
    }

    @Test
    @DisplayName("제목은 사이드바와 같은 규칙으로 레거시 [version] 접두를 뗀다")
    void stripsLegacyVersionPrefixLikeTheSidebar() {
        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("[latest] 인덱싱 질문", "u1", 3, 3)));

        assertThat(service.panel(null, null, 0, 20).threads())
                .singleElement()
                .extracting(ThreadAdminService.ThreadView::displayTitle)
                .isEqualTo("인덱싱 질문");
    }

    @Test
    @DisplayName("소유자 id 축약 — 해시 게스트 id 도 셀에 들어가되 원본은 남는다")
    void shortensLongOwnerIds() {
        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("대화", "guest-a1b2c3d4e5f6", 1, 1)));

        var v = service.panel(null, null, 0, 20).threads().get(0);

        assertThat(v.shortUserId()).endsWith("…").hasSizeLessThan("guest-a1b2c3d4e5f6".length() + 1);
        assertThat(v.userId()).isEqualTo("guest-a1b2c3d4e5f6");   // 툴팁용 원본은 그대로
    }

    @Test
    @DisplayName("짧은 사용자 id 는 축약하지 않는다")
    void shortOwnerIdsAreLeftAlone() {
        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("대화", "admin", 1, 1)));

        assertThat(service.panel(null, null, 0, 20).threads().get(0).shortUserId())
                .isEqualTo("admin");
    }

    // ── 원문 열람 / KST 표시 ──────────────────────────────────────────────────

    @Test
    @DisplayName("turnContent — 원문을 그대로 주되 시각은 KST 로, 모드는 파싱된 값으로")
    void turnContentReturnsRawTextWithKstTimeAndParsedMode() {
        when(repository.findTurnContent(7L)).thenReturn(java.util.Optional.of(
                new ThreadAdminRepository.TurnContent(
                        7L, "u1", "t1", "2026-08-27 03:00:00",
                        "질문", "답변 전문", "M")));   // 구 모드 값

        var c = service.turnContent(7L).orElseThrow();

        assertThat(c.question()).isEqualTo("질문");
        assertThat(c.answer()).isEqualTo("답변 전문");     // 원문은 손대지 않는다
        assertThat(c.askedAtKst()).isEqualTo("2026-08-27 12:00:00");
        assertThat(c.responseMode()).isEqualTo("N");       // 구 M/L 은 실제 동작과 같은 N 으로
        assertThat(c.userId()).isEqualTo("u1");            // 감사에 남길 소유자
    }

    @Test
    @DisplayName("turnContent — 없는 턴은 비어 있다 (열람이 아니므로 감사도 없다)")
    void turnContentUnknownTurnIsEmpty() {
        when(repository.findTurnContent(99L)).thenReturn(java.util.Optional.empty());

        assertThat(service.turnContent(99L)).isEmpty();
    }

    @Test
    @DisplayName("드릴다운 턴의 시각도 KST — 진단 패널과 같은 규칙")
    void drilldownTurnTimeIsKst() {
        var row = new ThreadAdminRepository.TurnRow(
                7L, "2026-08-27 15:30:45", "질문", "N", "local", null, false, false, true);

        assertThat(new ThreadAdminService.TurnView(row).askedAtKst())
                .isEqualTo("2026-08-28 00:30:45");   // 날짜 경계를 넘는다
    }

    // ── 삭제 ─────────────────────────────────────────────────────────────────

    private void stubOne(String threadId, String owner, ThreadRow row) {
        when(repository.findOwner(threadId)).thenReturn(java.util.Optional.ofNullable(owner));
        when(repository.findOne(threadId)).thenReturn(java.util.Optional.ofNullable(row));
    }

    @Test
    @DisplayName("delete — 소유자를 thread id 로 서버가 찾고, 큐레이션 회수를 기록 삭제보다 먼저 한다")
    void delete_resolvesOwnerAndRetractsCuratedFirst() {
        stubOne("t1", "u1", row("대화", "u1", 3, 3));
        when(curatedQaService.onThreadDeleted("u1", "t1")).thenReturn(2);

        var result = service.delete("t1").orElseThrow();

        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.turnCount()).isEqualTo(3);
        assertThat(result.curatedRetracted()).isEqualTo(2);

        // 순서가 계약이다 — 큐레이션 행은 turn/thread id 의 '복사본'으로만 연결돼 있어
        // 기록을 먼저 지우면 회수 대상을 찾을 근거가 흐려진다(사용자 삭제 경로와 같은 순서).
        InOrder order = inOrder(curatedQaService, memoryService, threadMetaService);
        order.verify(curatedQaService).onThreadDeleted("u1", "t1");
        order.verify(memoryService).clearHistory("u1", "t1");
        order.verify(threadMetaService).delete("u1", "t1");
    }

    @Test
    @DisplayName("delete — 없는 대화는 아무것도 건드리지 않고 비어 있는 결과를 낸다")
    void delete_unknownThreadTouchesNothing() {
        stubOne("ghost", null, null);

        assertThat(service.delete("ghost")).isEmpty();

        verify(curatedQaService, never()).onThreadDeleted(any(), any());
        verify(memoryService, never()).clearHistory(any(), any());
        verify(threadMetaService, never()).delete(any(), any());
    }

    @Test
    @DisplayName("deletePreview — 확인 문구의 숫자를 클릭 시점에 다시 읽는다")
    void deletePreview_readsCountsFresh() {
        stubOne("t1", "u1", new ThreadRow("t1", "u1", "[latest] 인덱싱 질문", "",
                "2026-01-01", "2026-08-27", "2026-08-26", 15, 11, 3, 4, 1, 0));
        when(curatedQaService.countActiveByThread("u1", "t1")).thenReturn(2);

        var p = service.deletePreview("t1").orElseThrow();

        assertThat(p.displayTitle()).isEqualTo("인덱싱 질문");   // 사이드바와 같은 접두 제거
        assertThat(p.userId()).isEqualTo("u1");
        assertThat(p.turnCount()).isEqualTo(15);
        assertThat(p.reusedOut()).isEqualTo(3);
        assertThat(p.diagCount()).isEqualTo(4);
        assertThat(p.curatedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("deletePreview — 없는 대화는 비어 있다 (대화상자가 아니라 404 로 끝난다)")
    void deletePreview_unknownThreadIsEmpty() {
        stubOne("ghost", null, null);

        assertThat(service.deletePreview("ghost")).isEmpty();
    }

    @Test
    @DisplayName("검색이 거의 돌지 않은 대화를 표시한다 (재사용·Direct 위주 대화 신호)")
    void flagsConversationsThatMostlySkippedRetrieval() {
        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("대화", "u1", 15, 4)));
        assertThat(service.panel(null, null, 0, 20).threads().get(0).mostlyWithoutRetrieval()).isTrue();

        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("대화", "u1", 4, 4)));
        assertThat(service.panel(null, null, 0, 20).threads().get(0).mostlyWithoutRetrieval()).isFalse();

        // 턴이 없는 대화는 "검색을 건너뛴" 것이 아니라 아무 일도 없었던 것이다.
        when(repository.findAll(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("빈 대화", "u1", 0, 0)));
        assertThat(service.panel(null, null, 0, 20).threads().get(0).mostlyWithoutRetrieval()).isFalse();
    }
}
