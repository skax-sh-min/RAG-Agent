package com.example.ragagent.service;

import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.repository.QuestionReuseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuestionReuseServiceTest {

    @Test
    @DisplayName("지시어만 있는 질문은 추천 제외 대상이다")
    void directiveOnlyQuestion_excluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("이거 어떻게 해?"))
                .isTrue();
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("그거 알려줘"))
                .isTrue();
    }

    @Test
    @DisplayName("지시어가 있어도 구체 신호가 있으면 제외하지 않는다")
    void directiveWithConcreteSignal_notExcluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("이거 오류코드 404는 뭐야?"))
                .isFalse();
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("그거 application.properties 설정값 알려줘"))
                .isFalse();
    }

    @Test
    @DisplayName("지시어 없는 일반 질문은 추천 대상이다")
    void normalQuestion_notExcluded() {
        assertThat(QuestionReuseService.isDirectiveOnlyQuestion("Spring Boot에서 sqlite 연결 방법"))
                .isFalse();
    }

    @Test
    @DisplayName("응답 참여도가 없는 청크가 바뀌어도 재사용은 막히지 않는다")
    void validateTurn_ignoresNonContributingChunks() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        // c1만 답변에 지분이 있었고, c2는 검색만 되고 한 글자도 안 쓰였다.
        when(repo.findAllSourceRefs(7L)).thenReturn(List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1", 0.31, "active"),
                new QuestionReuseRepository.SourceSnapshot("c2", "d1", "h2", 0.0, "inactive")));
        // c2는 이미 수정되어 해시가 바뀌었지만 검증 대상이 아니다.
        when(repo.currentChunkHashes(java.util.Set.of("c1"))).thenReturn(java.util.Map.of("c1", "h1"));

        assertThat(service.validateTurn(7L).reusable()).isTrue();
    }

    @Test
    @DisplayName("응답 참여도가 있는 청크가 바뀌면 재사용이 막힌다")
    void validateTurn_blocksWhenContributingChunkChanged() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findAllSourceRefs(8L)).thenReturn(List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1", 0.31, "active"),
                new QuestionReuseRepository.SourceSnapshot("c2", "d1", "h2", 0.0, "active")));
        when(repo.currentChunkHashes(java.util.Set.of("c1"))).thenReturn(java.util.Map.of("c1", "CHANGED"));

        assertThat(service.validateTurn(8L).reusable()).isFalse();
    }

    @Test
    @DisplayName("응답 참여도가 기록되지 않은 구 데이터는 예전처럼 전체 출처를 검증한다")
    void validateTurn_legacyRowsFallBackToAllSources() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findAllSourceRefs(9L)).thenReturn(List.of(
                new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1"),
                new QuestionReuseRepository.SourceSnapshot("c2", "d1", "h2")));
        when(repo.currentChunkHashes(java.util.Set.of("c1", "c2")))
                .thenReturn(java.util.Map.of("c1", "h1", "c2", "CHANGED"));

        assertThat(service.validateTurn(9L).reusable()).isFalse();
    }

    @Test
    @DisplayName("추천 목록은 질문 텍스트 중복을 제거한다")
    void suggest_deduplicatesQuestions() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(12L, "u1", "t1", "Spring Boot 설정 방법", "a1", "2026-08-05 10:00:00"),
                        new QuestionReuseRepository.CandidateTurn(11L, "u1", "t2", "spring   boot   설정 방법", "a2", "2026-08-05 09:00:00"),
                        new QuestionReuseRepository.CandidateTurn(10L, "u1", "t3", "다른 질문", "a3", "2026-08-05 08:00:00")
                ));
        when(repo.findSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.findAllSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", null, QuestionReuseService.Scope.SHARED, "spring", 10);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).question()).isEqualTo("Spring Boot 설정 방법");
        assertThat(suggestions.get(1).question()).isEqualTo("다른 질문");
    }

    @Test
    @DisplayName("추천 목록에는 50자를 초과하는 질문이 포함되지 않는다")
    void suggest_excludesQuestionsOver50Chars() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        String longQuestion = "Spring Boot에서 보안 설정을 운영 환경에서 단계별로 점검하는 상세 절차를 알려주세요";

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(20L, "u1", "t1", longQuestion, "a1", "2026-08-05 10:00:00"),
                        new QuestionReuseRepository.CandidateTurn(19L, "u1", "t2", "로그인 오류 401 원인", "a2", "2026-08-05 09:00:00")
                ));
        when(repo.findSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.findAllSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", null, QuestionReuseService.Scope.SHARED, "로그인", 10);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).question()).isEqualTo("로그인 오류 401 원인");
    }

    @Test
    @DisplayName("shared 추천은 db-reuse turn도 포함한다")
    void suggest_sharedIncludesDbReuseTurns() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(30L, "u1", "t1", "캐시 설정 방법", "원본 답변", "2026-08-06 10:00:00")
                ));
        when(repo.findSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.findAllSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("admin", null, QuestionReuseService.Scope.SHARED, "캐시", 8);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).question()).isEqualTo("캐시 설정 방법");
    }

    @Test
    @DisplayName("재사용 조회는 source turn 답변을 그대로 사용한다")
    void reuseLookup_usesResolvedAnswerFromRepository() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findTurnForReuse(55L, false, "admin"))
                .thenReturn(new QuestionReuseRepository.CandidateTurn(
                        55L, "u1", "t9", "배포 절차", "원본 turn 답변", "2026-08-06 10:00:00"));
        when(repo.findSourceRefs(55L))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.findAllSourceRefs(55L))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        QuestionReuseService.ReuseLookup lookup =
                service.reuseLookup("admin", QuestionReuseService.Scope.SHARED, 55L);

        assertThat(lookup.reusable()).isTrue();
        assertThat(lookup.answer()).isEqualTo("원본 turn 답변");
    }

    /**
     * 재사용 판정은 답변 텍스트만이 아니라 그 답변이 만들어진 모양(응답 모드·Direct 여부·태그
     * 스코프)도 원본에서 그대로 실어야 한다 — 컨트롤러가 새 턴을 저장할 때 복사하는 값이라,
     * 여기서 떨어지면 저장은 조용히 자리표시자로 돌아간다.
     */
    @Test
    @DisplayName("재사용 조회는 원본 답변의 모양(모드·Direct·태그)을 함께 싣는다")
    void reuseLookup_carriesTheSourceTurnsAnswerShape() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findTurnForReuse(56L, false, "admin"))
                .thenReturn(new QuestionReuseRepository.CandidateTurn(
                        56L, "u1", "t9", "VPN 접속 방법", "답변", "2026-08-06 10:00:00",
                        "N", true, "policy,billing"));
        when(repo.findSourceRefs(56L))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.findAllSourceRefs(56L))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        QuestionReuseService.ReuseLookup lookup =
                service.reuseLookup("admin", QuestionReuseService.Scope.SHARED, 56L);

        assertThat(lookup.reusable()).isTrue();
        assertThat(lookup.responseMode()).isEqualTo("N");
        assertThat(lookup.directMode()).isTrue();
        assertThat(lookup.selectedTags()).isEqualTo("policy,billing");

        // 재사용 불가 결과는 모양을 모른다 — 컨트롤러가 그 값을 저장할 일도 없다.
        assertThat(QuestionReuseService.ReuseLookup.notReusable("x", "q").selectedTags()).isEmpty();
    }

        @Test
        @DisplayName("이전 대화 출처 라벨은 챕터/페이지 규칙을 동일하게 따른다")
        void sourceRefsForTurn_formatsLabelWithChapterRule() {
                QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
                QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

                        when(repo.findReusedFromTurnId(7L)).thenReturn(null);
                when(repo.findSourcePreviewRows(7L)).thenReturn(List.of(
                                new QuestionReuseRepository.SourcePreviewRow(
                                                "c1", "d1", "manual.docx", "12", "1.2", "docx chunk"),
                                new QuestionReuseRepository.SourcePreviewRow(
                                                "c2", "d2", "slides.pptx", "3", "0", "pptx chunk")
                ));

                var refs = service.sourceRefsForTurn(7L);

                assertThat(refs).hasSize(2);
                assertThat(refs.get(0).label()).isEqualTo("manual.docx | ch 1.2");
                assertThat(refs.get(1).label()).isEqualTo("slides.pptx | p.3");
        }

        @Test
        @DisplayName("출처 라벨 변환은 chapter가 null이어도 예외 없이 동작한다")
        void sourceRefsForTurn_handlesNullChapterSafely() {
                QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
                QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

                        when(repo.findReusedFromTurnId(8L)).thenReturn(null);
                when(repo.findSourcePreviewRows(8L)).thenReturn(List.of(
                                new QuestionReuseRepository.SourcePreviewRow(
                                                "c1", "d1", "manual.docx", "12", null, "docx chunk")
                ));

                var refs = service.sourceRefsForTurn(8L);

                assertThat(refs).hasSize(1);
                assertThat(refs.get(0).label()).isEqualTo("manual.docx");
        }

        @Test
        @DisplayName("db-reuse turn의 출처 미리보기는 원본 turn 기준으로 조회한다")
        void sourceRefsForTurn_dbReuseUsesOriginalTurn() {
                QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
                QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

                when(repo.findReusedFromTurnId(90L)).thenReturn(12L);
                when(repo.findSourcePreviewRows(12L)).thenReturn(List.of(
                                new QuestionReuseRepository.SourcePreviewRow(
                                                "c1", "d1", "manual.docx", "12", "1.2", "docx chunk")
                ));

                var refs = service.sourceRefsForTurn(90L);

                assertThat(refs).hasSize(1);
                assertThat(refs.get(0).label()).isEqualTo("manual.docx | ch 1.2");
        }

        @Test
        @DisplayName("db-reuse 원본 turn이 삭제되면 안내 문구를 표시한다")
        void sourceRefsForTurn_deletedOriginalShowsFallbackMessage() {
                QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
                QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

                when(repo.findReusedFromTurnId(91L)).thenReturn(13L);
                when(repo.findSourcePreviewRows(13L)).thenReturn(List.of());
                when(repo.existsTurn(13L)).thenReturn(false);

                var refs = service.sourceRefsForTurn(91L);

                assertThat(refs).hasSize(1);
                assertThat(refs.get(0).label()).isEqualTo("참조 원문 삭제됨");
                assertThat(refs.get(0).preview()).contains("원본 대화가 삭제되어 출처 미리보기를 표시할 수 없습니다");
        }

    @Test
    @DisplayName("origin — 현재 대화 / 내 대화 / 다른 사용자로 갈린다")
    void originOf_distinguishesThreadMineOthers() {
        var mine    = new QuestionReuseRepository.CandidateTurn(1L, "u1", "t-other", "q", "a", "2026-09-19");
        var here    = new QuestionReuseRepository.CandidateTurn(2L, "u1", "t-here",  "q", "a", "2026-09-19");
        var someone = new QuestionReuseRepository.CandidateTurn(3L, "u2", "t-x",     "q", "a", "2026-09-19");

        assertThat(QuestionReuseService.originOf(here,    "u1", "t-here")).isEqualTo(QuestionReuseService.Origin.THREAD);
        assertThat(QuestionReuseService.originOf(mine,    "u1", "t-here")).isEqualTo(QuestionReuseService.Origin.MINE);
        assertThat(QuestionReuseService.originOf(someone, "u1", "t-here")).isEqualTo(QuestionReuseService.Origin.OTHERS);
        // 대화 id 없이 부르면(REST) 현재 대화는 없다 — 같은 대화의 턴도 "내 대화"다.
        assertThat(QuestionReuseService.originOf(here,    "u1", null)).isEqualTo(QuestionReuseService.Origin.MINE);
        assertThat(QuestionReuseService.originOf(here,    "u1", " ")).isEqualTo(QuestionReuseService.Origin.MINE);
    }

    @Test
    @DisplayName("origin — 공유 게스트 id 로는 내 것/남의 것을 가를 수 없어 '다른 대화'로 접힌다 (현재 대화는 여전히 가려낸다)")
    void originOf_sharedGuestCannotTellMineFromOthers() {
        String shared = com.example.ragagent.security.GuestIdentityResolver.SHARED_ID;
        var here      = new QuestionReuseRepository.CandidateTurn(1L, shared, "t-here",  "q", "a", "2026-09-19");
        var elsewhere = new QuestionReuseRepository.CandidateTurn(2L, shared, "t-other", "q", "a", "2026-09-19");

        assertThat(QuestionReuseService.originOf(here,      shared, "t-here")).isEqualTo(QuestionReuseService.Origin.THREAD);
        // 같은 id 라고 "내 대화"로 표시하면 남이 물은 질문까지 전부 내 것으로 뜬다.
        assertThat(QuestionReuseService.originOf(elsewhere, shared, "t-here")).isEqualTo(QuestionReuseService.Origin.ELSEWHERE);
    }

    @Test
    @DisplayName("현재 대화의 항목은 재사용 검증을 거치지 않고, 나머지는 여전히 거친다")
    void suggest_currentThreadItemsSkipReuseValidation() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        // 현재 대화 — 출처 행이 하나도 없다(Direct 턴). 재사용이라면 탈락할 자리.
                        new QuestionReuseRepository.CandidateTurn(40L, "u1", "t-here",  "포트 설정 방법", "a1", "2026-09-19 10:00:00"),
                        // 다른 대화 — 마찬가지로 출처가 없으므로 재사용 검증에서 탈락해야 한다.
                        new QuestionReuseRepository.CandidateTurn(39L, "u1", "t-other", "포트 변경 방법", "a2", "2026-09-19 09:00:00")
                ));
        when(repo.findAllSourceRefs(anyLong())).thenReturn(List.of());

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", "t-here", QuestionReuseService.Scope.SHARED, "포트", 10);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).turnId()).isEqualTo(40L);
        assertThat(suggestions.get(0).origin()).isEqualTo(QuestionReuseService.Origin.THREAD);
        // 현재 대화 항목에 대해서는 출처 조회 자체가 없다 — 검증을 건너뛴다는 뜻.
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findAllSourceRefs(40L);
        org.mockito.Mockito.verify(repo).findAllSourceRefs(39L);
    }

    @Test
    @DisplayName("같은 질문이 현재 대화와 다른 대화에 다 있으면 이동 항목(현재 대화)이 남는다")
    void suggest_dedupKeepsTheCurrentThreadItem() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo, mock(DocRegistry.class));

        // 리포지토리는 현재 대화를 먼저 준다(ORDER BY) — 서비스의 중복 제거는 그 순서를 믿는다.
        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(50L, "u1", "t-here",  "캐시 설정 방법", "a1", "2026-09-19 09:00:00"),
                        new QuestionReuseRepository.CandidateTurn(51L, "u2", "t-x",     "캐시  설정 방법", "a2", "2026-09-19 10:00:00")
                ));
        when(repo.findAllSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", "t-here", QuestionReuseService.Scope.SHARED, "캐시", 10);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).turnId()).isEqualTo(50L);
        assertThat(suggestions.get(0).origin()).isEqualTo(QuestionReuseService.Origin.THREAD);
    }
}
