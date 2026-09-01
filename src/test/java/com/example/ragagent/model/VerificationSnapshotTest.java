package com.example.ragagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증 배지 규칙의 <b>단일 구현</b>을 고정한다 (PLAN §6.24 Step 4-b).
 *
 * <p>렌더러가 셋이라(HTMX 폴백 프래그먼트 · 대화 기록 루프 · 스트리밍 JS) 조건을 각자 풀어 쓰면
 * 반드시 갈라지고, 갈라진 것은 화면에서 보이지 않는다. 서버 렌더러 둘은 이 레코드를 읽으므로
 * 여기를 고정하면 둘이 함께 고정된다.
 */
class VerificationSnapshotTest {

    private static VerificationSnapshot snap(Boolean grounded, boolean generative,
                                             String evalReason, String... invented) {
        return new VerificationSnapshot(grounded, generative, evalReason, null, List.of(invented), null);
    }

    @Test
    @DisplayName("검증 미실행(grounded=null)이면 배지를 띄우지 않는다")
    void notVerified_showsNoBadge() {
        // S 모드, meta/Direct 답변, 검색 결과 없음 — 셋 다 이 경로다.
        VerificationSnapshot v = snap(null, false, null);
        assertThat(v.verdictLabel()).isNull();
        assertThat(v.verdictClass()).isNull();
        assertThat(v.verdictTitle()).isNull();
    }

    @Test
    @DisplayName("표준 모드 통과는 초록 '검증됨'")
    void standardPass_isGreenVerified() {
        VerificationSnapshot v = snap(true, false, null);
        assertThat(v.verdictLabel()).isEqualTo("검증됨");
        assertThat(v.verdictClass()).isEqualTo("bg-success");
        assertThat(v.verdictTitle()).isNull();
    }

    @Test
    @DisplayName("생성 모드 통과는 파랑 '생성' — 통과한 검증의 질문이 다르기 때문이다")
    void generativePass_isBlueGenerated() {
        // 표준의 grounded 는 "문서에 근거하는가", 창의의 apiGrounded 는 "제시한 이름이 실재하는가"를
        // 물었다. 같은 초록 배지를 붙이면 사용자가 뒤엣것을 앞엣것으로 읽는다.
        VerificationSnapshot v = snap(true, true, null);
        assertThat(v.verdictLabel()).isEqualTo("생성");
        assertThat(v.verdictClass()).isEqualTo("bg-primary");
        assertThat(v.verdictTitle()).contains("재료로 생성된");
    }

    @Test
    @DisplayName("미통과는 모드와 무관하게 '미검증'이고 사유가 툴팁·한 줄 양쪽에 실린다")
    void failure_isAmberRegardlessOfMode() {
        for (boolean generative : new boolean[]{false, true}) {
            VerificationSnapshot v = snap(false, generative, "포트 설정 값이 문서에 없음");
            assertThat(v.verdictLabel()).as("generative=%s", generative).isEqualTo("미검증");
            assertThat(v.verdictClass()).isEqualTo("bg-warning text-dark");
            assertThat(v.verdictTitle()).isEqualTo("포트 설정 값이 문서에 없음");
            assertThat(v.showsEvalReasonLine()).isTrue();
        }
    }

    @Test
    @DisplayName("사유가 없는 미통과는 한 줄 표시를 하지 않는다 (모델이 안 줘도 깨지지 않음)")
    void failureWithoutReason_showsNoLine() {
        assertThat(snap(false, true, null).showsEvalReasonLine()).isFalse();
    }

    @Test
    @DisplayName("발명된 이름은 통과한 답변에도 붙는다 — 재시도를 걸지 않는 경고 전용 값이다")
    void inventedSymbols_areShownEvenOnAPassingAnswer() {
        VerificationSnapshot v = snap(true, true, null, "parseDateEx", "--strict-mode");
        assertThat(v.verdictLabel()).isEqualTo("생성");   // 통과했는데도
        assertThat(v.hasInventedSymbols()).isTrue();      // 경고는 함께 뜬다
        assertThat(v.inventedSymbolsText()).isEqualTo("parseDateEx, --strict-mode");
    }

    @Test
    @DisplayName("발명된 이름이 없으면 경고도 없다")
    void noInventedSymbols_noWarning() {
        VerificationSnapshot v = snap(true, true, null);
        assertThat(v.hasInventedSymbols()).isFalse();
        assertThat(v.inventedSymbolsText()).isEmpty();
    }

    @Test
    @DisplayName("null 심볼 목록은 빈 목록으로 정규화된다 (옛 기록·모델 누락 대응)")
    void nullListIsNormalized() {
        VerificationSnapshot v = new VerificationSnapshot(true, false, null, null, null, null);
        assertThat(v.inventedSymbols()).isEmpty();
        assertThat(v.hasInventedSymbols()).isFalse();
    }

    @Test
    @DisplayName("담을 것이 하나도 없는 턴은 isEmpty — 저장하지 않아 컬럼이 NULL로 남는다")
    void emptySnapshotIsNotWorthStoring() {
        assertThat(new VerificationSnapshot(null, false, null, null, List.of(), null).isEmpty()).isTrue();
        // envNote 만 있어도 저장 대상이다 — 검증 통과 여부와 무관하게 사용자에게 줄 안내다.
        assertThat(new VerificationSnapshot(null, false, null, "경로는 환경마다 다릅니다", List.of(), null).isEmpty()).isFalse();
        assertThat(new VerificationSnapshot(true, false, null, null, List.of(), null).isEmpty()).isFalse();
        assertThat(new VerificationSnapshot(null, true, null, null, List.of("x"), null).isEmpty()).isFalse();
    }

    @Test
    @DisplayName("축소 안내만 있어도 저장한다 — 검증을 안 돌린 턴이라도 이 사실은 남아야 한다")
    void budgetNoteAloneIsWorthPersisting() {
        var onlyBudget = new VerificationSnapshot(null, false, null, null, java.util.List.of(),
                "컨텍스트 한도로 검색된 문서 10개 중 6개만 사용했습니다.");

        // isEmpty() 가 true 면 MemoryService.saveVerification() 이 저장을 건너뛰고, 새로고침하면
        // 출처 10개가 아무 단서 없이 나열된다 — 이 기능이 막으려던 바로 그 상태다.
        assertThat(onlyBudget.isEmpty()).isFalse();
        // 판정은 없으므로 배지는 여전히 그리지 않는다.
        assertThat(onlyBudget.verdictLabel()).isNull();
    }

    @Test
    @DisplayName("아무 값도 없으면 여전히 빈 스냅샷이다")
    void stillEmptyWhenNothingIsSet() {
        assertThat(new VerificationSnapshot(null, false, null, null, java.util.List.of(), null).isEmpty())
                .isTrue();
    }
}
