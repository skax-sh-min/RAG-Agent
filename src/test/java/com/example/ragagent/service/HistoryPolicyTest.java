package com.example.ragagent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10.13 — {@code [이전 대화]} 에 얼마나 넣고 무엇으로 렌더할지.
 *
 * <p>고정하는 것은 두 가지다. <b>예산</b>은 "문서가 차지할 자리"에서 나오지 "Direct 라서" 나오지
 * 않는다는 것, 그리고 <b>렌더</b>는 두 축(지금 묻는 턴이 Direct 인가 × 이전 턴이 어떤 모드였나)의
 * 조합이라는 것 — 하나로 합치려는 다음 사람이 이 표를 먼저 보게 된다.
 */
class HistoryPolicyTest {

    /** PLAN §10.13 의 측정 예시 — 창 40,960 · N 스트리밍 출력 예약 5,000 · 여유 4,096. */
    private static final int WINDOW = 40_960;
    private static final int N_STREAMING_RESERVATION = 5_000;

    private static final int FALLBACK = 5_000;   // = max-tokens 10,000 / 2

    // ── 예산 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("문서 자리가 0이면 그만큼 이력이 넓어진다 — 5,000자 고정에서 30,000자대로")
    void directTurn_getsTheEmptyDocumentSlot() {
        int direct = HistoryPolicy.budgetChars(WINDOW, N_STREAMING_RESERVATION, 0, 20, FALLBACK);

        // 40,960 − 5,000(출력) − 4,096(여유) − 20(질문) − 1,000(고정비)
        assertThat(direct).isEqualTo(40_960 - 5_000 - 4_096 - 20 - 1_000);
        assertThat(direct).isGreaterThan(FALLBACK * 6);
    }

    @Test
    @DisplayName("규칙은 모드가 아니라 자리다 — 문서 자리를 채우면 같은 식이 그만큼 좁힌다")
    void documentSlotIsWhatShrinksIt() {
        int empty = HistoryPolicy.budgetChars(WINDOW, N_STREAMING_RESERVATION, 0, 20, FALLBACK);
        int withDocs = HistoryPolicy.budgetChars(WINDOW, N_STREAMING_RESERVATION, 15_000, 20, FALLBACK);

        assertThat(empty - withDocs).isEqualTo(15_000);
    }

    @Test
    @DisplayName("창을 모르면 아무것도 하지 않는다 — 추측한 숫자로 이력을 늘리지도 줄이지도 않는다")
    void unknownWindow_keepsTheFixedValue() {
        assertThat(HistoryPolicy.budgetChars(0, N_STREAMING_RESERVATION, 0, 20, FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(HistoryPolicy.budgetChars(-1, N_STREAMING_RESERVATION, 0, 20, FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("좁은 창에서는 오늘의 고정값보다 작게 나오고, 그것이 옳다 (확대와 축소가 같은 식이다)")
    void narrowWindow_shrinksBelowTheFixedValue() {
        // 8,192 − 5,000(출력) − 819(여유) − 20 − 1,000 = 1,353
        int narrow = HistoryPolicy.budgetChars(8_192, N_STREAMING_RESERVATION, 0, 20, FALLBACK);

        assertThat(narrow).isPositive().isLessThan(FALLBACK);
        // Direct 경로에는 예산 가드가 없었으므로, 이 배포는 고정 5,000자로 이미 창을 넘기고 있었다.
    }

    @Test
    @DisplayName("예약과 여유가 창을 다 먹으면 0 — 음수로 내려가지 않는다")
    void impossibleConfiguration_floorsAtZero() {
        assertThat(HistoryPolicy.budgetChars(4_096, 5_000, 0, 20, FALLBACK)).isZero();
    }

    // ── 렌더: 지금 묻는 턴이 RAG (지금까지의 동작 그대로) ──────────────────────

    private static final String RAG_ANSWER = """
            ## 요약
            핵심 한 줄.

            ## 상세 설명
            자세한 설명입니다.

            ## 참고
            - [파일.docx | p.1]""";

    @Test
    @DisplayName("RAG 로 물으면 이전 RAG 턴은 '## 요약'만 — 검색을 다시 하므로 전문은 중복이다")
    void askingRag_ragTurn_keepsOnlyTheSummary() {
        String rendered = HistoryPolicy.renderAnswer(RAG_ANSWER, "N", false, false);

        assertThat(rendered).isEqualTo("핵심 한 줄.");
    }

    @Test
    @DisplayName("RAG 로 물으면 요약 없는 답변은 1,200자로 잘린다 (지금까지의 캡 그대로)")
    void askingRag_answerWithoutSummary_isCapped() {
        String long_ = "가".repeat(3_000);

        String rendered = HistoryPolicy.renderAnswer(long_, "N", false, false);

        assertThat(rendered).hasSize(HistoryPolicy.RECENT_ANSWER_CAP + 1).endsWith("…");
    }

    @Test
    @DisplayName("RAG 로 물어도 이전 턴이 Direct 였으면 전문이 남는다 — 그 턴에는 복제할 문서가 없었다")
    void askingRag_previousDirectTurn_keepsTheBody() {
        String rendered = HistoryPolicy.renderAnswer(RAG_ANSWER, "N", false, true);

        assertThat(rendered).contains("상세 설명", "자세한 설명입니다.")
                .doesNotContain("## 요약", "핵심 한 줄");
    }

    @Test
    @DisplayName("RAG 로 물을 때 이전 Direct 턴의 긴 답변은 1,200자 캡에 걸리지 않는다")
    void askingRag_previousDirectTurn_isNotCapped() {
        String dn = "가".repeat(3_000);

        assertThat(HistoryPolicy.renderAnswer(dn, "N", false, true)).isEqualTo(dn).doesNotEndWith("…");
    }

    @Test
    @DisplayName("조건은 'DN 이면'이 아니라 'Direct 였으면' — DS 는 요약이 곧 답변 전부라 결과가 같다")
    void askingRag_previousDirectSummaryOnlyTurn_isUnchanged() {
        String ds = "## 요약\n짧게 정리한 내용.";

        assertThat(HistoryPolicy.renderAnswer(ds, "S", false, true)).isEqualTo("짧게 정리한 내용.");
    }

    // ── 렌더: 지금 묻는 턴이 Direct ─────────────────────────────────────────

    @Test
    @DisplayName("Direct 로 물으면 이전 S 턴은 '## 요약' — 그게 답변 전부라 뺄 것이 없다")
    void askingDirect_summaryOnlyTurn_keepsTheSummary() {
        String s = "## 요약\n짧게 정리한 내용.";

        assertThat(HistoryPolicy.renderAnswer(s, "S", true, false)).isEqualTo("짧게 정리한 내용.");
    }

    @Test
    @DisplayName("Direct 로 물으면 이전 N 턴은 요약·참고를 뺀 본문 — 요약은 본문의 재진술이고 출처는 값이 없다")
    void askingDirect_normalTurn_keepsTheBody() {
        String rendered = HistoryPolicy.renderAnswer(RAG_ANSWER, "N", true, false);

        assertThat(rendered).contains("상세 설명", "자세한 설명입니다.")
                .doesNotContain("## 요약", "핵심 한 줄", "## 참고", "파일.docx");
    }

    @Test
    @DisplayName("Direct 로 물으면 DN 답변은 전문이 그대로 남는다 — 순효과는 캡 제거뿐이다")
    void askingDirect_directAnswer_isNotCapped() {
        String dn = "가".repeat(3_000);

        String rendered = HistoryPolicy.renderAnswer(dn, "N", true, true);

        assertThat(rendered).isEqualTo(dn).doesNotEndWith("…");
    }

    @Test
    @DisplayName("같은 이전 턴이 다음에 무엇을 묻느냐에 따라 다르게 렌더된다 — 자의적이지 않고 필요가 다르다")
    void sameTurnRendersDifferentlyDependingOnWhatIsAskedNext() {
        String askingRag = HistoryPolicy.renderAnswer(RAG_ANSWER, "N", false, false);
        String askingDirect = HistoryPolicy.renderAnswer(RAG_ANSWER, "N", true, false);

        assertThat(askingRag).isNotEqualTo(askingDirect);
        assertThat(askingDirect.length()).isGreaterThan(askingRag.length());
    }

    @Test
    @DisplayName("C 의 '## 검증되지 않은 부분'은 남는다 — 다음 턴이 무엇이 미확인인지 아는 것은 쓸모가 있다")
    void askingDirect_creativeTurn_keepsTheUnverifiedSection() {
        String c = """
                ## 요약
                한 줄.

                ## 상세 설명
                본문.

                ## 검증되지 않은 부분
                이 API 이름은 확인되지 않았습니다.""";

        assertThat(HistoryPolicy.renderAnswer(c, "C", true, false))
                .contains("검증되지 않은 부분", "이 API 이름은 확인되지 않았습니다")
                .doesNotContain("## 요약");
    }

    @Test
    @DisplayName("레거시·미지 모드 값은 N 으로 읽힌다 — 값을 못 읽었다고 본문을 버리지 않는다")
    void legacyModeValues_fallBackToNormal() {
        for (String legacy : new String[]{null, "", "  ", "M", "L", "몰라"}) {
            assertThat(HistoryPolicy.renderAnswer(RAG_ANSWER, legacy, true, false))
                    .as("%s", legacy).contains("자세한 설명입니다.");
        }
    }

    @Test
    @DisplayName("뺄 것이 남지 않는 답변은 전문으로 되돌린다 — 빈 이력을 만들지 않는다")
    void strippingEverything_fallsBackToTheWholeAnswer() {
        String summaryAndRefsOnly = "## 요약\n한 줄.\n\n## 참고\n- [파일 | p.1]";

        assertThat(HistoryPolicy.renderAnswer(summaryAndRefsOnly, "N", true, false)).isNotBlank();
    }

    // ── 절단 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("예산을 넘으면 가장 오래된 턴부터 버린다 — 턴 경계에서")
    void trimToBudget_dropsOldestTurnsAtTheBoundary() {
        String history = "Q: 첫 질문\nA: 첫 답변\n\nQ: 둘째 질문\nA: 둘째 답변";

        String trimmed = HistoryPolicy.trimToBudget(history, 12);

        assertThat(trimmed).isEqualTo("Q: 둘째 질문\nA: 둘째 답변");
    }

    @Test
    @DisplayName("예산 안이면 손대지 않는다")
    void trimToBudget_withinBudget_isUntouched() {
        String history = "Q: 질문\nA: 답변";

        assertThat(HistoryPolicy.trimToBudget(history, 10_000)).isEqualTo(history);
    }

    @Test
    @DisplayName("예산이 0 이하면 빈 문자열 — 반 토막 이력보다 없는 편이 낫다")
    void trimToBudget_noBudget_returnsEmpty() {
        assertThat(HistoryPolicy.trimToBudget("Q: 질문\nA: 답변", 0)).isEmpty();
    }

    // ── 프롬프트에 싣는 턴 수 상한 ──────────────────────────────────────────

    @Test
    @DisplayName("싣는 턴은 가져오는 창의 절반 — 기본 10턴이면 5턴")
    void promptTurnCap_isHalfTheFetchWindow() {
        assertThat(HistoryPolicy.promptTurnCap(10)).isEqualTo(5);
        assertThat(HistoryPolicy.promptTurnCap(20)).isEqualTo(10);
        assertThat(HistoryPolicy.promptTurnCap(7)).isEqualTo(3);   // 내림 — 창보다 커지지 않는다
    }

    @Test
    @DisplayName("아무리 좁혀도 최소 1턴 — 직전 대화까지 잃으면 후속 질문이 성립하지 않는다")
    void promptTurnCap_neverDropsBelowOne() {
        assertThat(HistoryPolicy.promptTurnCap(1)).isEqualTo(1);
        assertThat(HistoryPolicy.promptTurnCap(0)).isEqualTo(1);
        assertThat(HistoryPolicy.promptTurnCap(-5)).isEqualTo(1);
    }
}
