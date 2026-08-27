package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.repository.ThreadAdminRepository;
import com.example.ragagent.repository.ThreadAdminRepository.Sort;
import com.example.ragagent.repository.ThreadAdminRepository.Summary;
import com.example.ragagent.repository.ThreadAdminRepository.ThreadRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() {
        repository = mock(ThreadAdminRepository.class);
        props = mock(AppProperties.class);
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(true, false));
        when(repository.findAll(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(repository.summary()).thenReturn(new Summary(0, 0, 0, 0, 0));
        when(repository.distinctUserIds()).thenReturn(List.of());
        service = new ThreadAdminService(repository, props);
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
