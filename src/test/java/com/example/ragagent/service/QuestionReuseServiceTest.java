package com.example.ragagent.service;

import com.example.ragagent.repository.QuestionReuseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("추천 목록은 질문 텍스트 중복을 제거한다")
    void suggest_deduplicatesQuestions() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo);

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(12L, "u1", "t1", "Spring Boot 설정 방법", "a1", "2026-08-05 10:00:00"),
                        new QuestionReuseRepository.CandidateTurn(11L, "u1", "t2", "spring   boot   설정 방법", "a2", "2026-08-05 09:00:00"),
                        new QuestionReuseRepository.CandidateTurn(10L, "u1", "t3", "다른 질문", "a3", "2026-08-05 08:00:00")
                ));
        when(repo.findSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", QuestionReuseService.Scope.SHARED, "spring", 10);

        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).question()).isEqualTo("Spring Boot 설정 방법");
        assertThat(suggestions.get(1).question()).isEqualTo("다른 질문");
    }

    @Test
    @DisplayName("추천 목록에는 50자를 초과하는 질문이 포함되지 않는다")
    void suggest_excludesQuestionsOver50Chars() {
        QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
        QuestionReuseService service = new QuestionReuseService(repo);

        String longQuestion = "Spring Boot에서 보안 설정을 운영 환경에서 단계별로 점검하는 상세 절차를 알려주세요";

        when(repo.findSuggestionCandidates(anyString(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new QuestionReuseRepository.CandidateTurn(20L, "u1", "t1", longQuestion, "a1", "2026-08-05 10:00:00"),
                        new QuestionReuseRepository.CandidateTurn(19L, "u1", "t2", "로그인 오류 401 원인", "a2", "2026-08-05 09:00:00")
                ));
        when(repo.findSourceRefs(anyLong()))
                .thenReturn(List.of(new QuestionReuseRepository.SourceSnapshot("c1", "d1", "h1")));
        when(repo.currentChunkHashes(java.util.Set.of("c1")))
                .thenReturn(java.util.Map.of("c1", "h1"));

        List<QuestionReuseService.Suggestion> suggestions =
                service.suggest("u1", QuestionReuseService.Scope.SHARED, "로그인", 10);

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).question()).isEqualTo("로그인 오류 401 원인");
    }

        @Test
        @DisplayName("이전 대화 출처 라벨은 챕터/페이지 규칙을 동일하게 따른다")
        void sourceRefsForTurn_formatsLabelWithChapterRule() {
                QuestionReuseRepository repo = mock(QuestionReuseRepository.class);
                QuestionReuseService service = new QuestionReuseService(repo);

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
                QuestionReuseService service = new QuestionReuseService(repo);

                when(repo.findSourcePreviewRows(8L)).thenReturn(List.of(
                                new QuestionReuseRepository.SourcePreviewRow(
                                                "c1", "d1", "manual.docx", "12", null, "docx chunk")
                ));

                var refs = service.sourceRefsForTurn(8L);

                assertThat(refs).hasSize(1);
                assertThat(refs.get(0).label()).isEqualTo("manual.docx");
        }
}
