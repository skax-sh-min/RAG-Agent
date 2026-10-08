package com.example.ragagent.service;

import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.llm.IndexingOutputCap;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ThinkingBudget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생각 수준 미리보기가 <b>런타임과 같은 식</b>을 쓰도록 호출부에서 뽑아낸 순수 함수들(PLAN §6.29 ⑦-바)이, 뽑기 전과 같은 값을
 * 내는가. 뽑는 리팩터링은 동작을 바꾸지 않아야 한다 — 호출부는 이제 이 함수를 지나므로 여기서 틀리면 실제 요청이 틀린다.
 * (호출부를 실제로 돌려 미리보기와 비교하는 것은 {@code ThinkingPreviewParityTest}.)
 */
class PreviewRuntimeFunctionsTest {

    // ── 기본 출력 예약 ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("독립화 256 · 답변 뒤 보강 2,048 · 큐레이션 제안 256 — 전역 max-tokens 가 더 작으면 그쪽, 0 이하면 싣지 않는다(0)")
    void shortCallBaseReservations() {
        assertThat(QuestionCondenser.baseReservation(10_000)).isEqualTo(256);
        assertThat(QuestionCondenser.baseReservation(200)).isEqualTo(200);
        assertThat(QuestionCondenser.baseReservation(0)).isZero();

        assertThat(PostAnswerService.baseReservation(10_000)).isEqualTo(2_048);
        assertThat(PostAnswerService.baseReservation(1_000)).isEqualTo(1_000);
        assertThat(PostAnswerService.baseReservation(-1)).isZero();

        assertThat(CuratedQuestionSuggester.baseReservation(10_000)).isEqualTo(256);
        assertThat(CuratedQuestionSuggester.baseReservation(0)).isZero();
    }

    @Test
    @DisplayName("키워드+맥락 — 청크 수에 비례(전역 max-tokens 의 5% × n)하되 512 아래로 내려가지 않고 전역값을 넘지 않는다")
    void keywordReservationScalesWithTheBatch() {
        assertThat(KeywordExtractor.enrichmentReservation(1, 10_000)).isEqualTo(512);
        assertThat(KeywordExtractor.enrichmentReservation(2, 10_000)).isEqualTo(1_000);
        assertThat(KeywordExtractor.enrichmentReservation(4, 10_000)).isEqualTo(2_000);
        assertThat(KeywordExtractor.enrichmentReservation(100, 10_000)).as("전역값에서 잘린다").isEqualTo(10_000);
        assertThat(KeywordExtractor.enrichmentReservation(0, 10_000)).as("0 청크는 1 로 본다").isEqualTo(512);
        assertThat(KeywordExtractor.enrichmentReservation(2, 0)).as("전역값이 0 이하면 싣지 않는다").isZero();
        assertThat(KeywordExtractor.batchPromptOverheadTokens(2)).as("배치 머리말은 비어 있지 않다").isPositive();
    }

    @Test
    @DisplayName("재작성 예약은 문자열로 재든 토큰으로 재든 같다 — forRewriteTokens 는 forRewrite 의 몸통이다")
    void rewriteReservationFromTokensEqualsFromText() {
        for (int chars : new int[]{0, 100, 4_750, 6_000}) {
            String text = "가".repeat(chars);
            assertThat(IndexingOutputCap.forRewriteTokens(chars, 10_000))
                    .as("%d자".formatted(chars)).isEqualTo(IndexingOutputCap.forRewrite(text, 10_000));
        }
        assertThat(IndexingOutputCap.forRewriteTokens(4_750, 10_000)).as("입력의 1.5배").isEqualTo(7_125);
        assertThat(IndexingOutputCap.forRewriteTokens(4_750, 0)).isZero();
    }

    @Test
    @DisplayName("이미지 설명(비전 교정 패스)의 예약은 max-tokens 의 5%, 최소 512")
    void visionPassReservation() {
        assertThat(MarkdownCorrectionService.visionReservation(10_000)).isEqualTo(512);
        assertThat(MarkdownCorrectionService.visionReservation(32_000)).isEqualTo(1_600);
        assertThat(MarkdownCorrectionService.visionReservation(0)).isZero();
    }

    // ── 조각 크기 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MD 교정 조각 — 창을 모르면 max-tokens 파생값, 알면 그 값과 창에서 나온 값 중 작은 쪽(줄이기만 한다)")
    void sectionCharsIsMaxTokensDerivedAndOnlyShrinks() {
        assertThat(MarkdownCorrectionService.sectionChars(10_000, 0, 0)).as("(10,000 − 500) / 2").isEqualTo(4_750);
        assertThat(MarkdownCorrectionService.sectionChars(10_000, 131_072, 0)).as("창이 넉넉하면 그대로 — 키우지 않는다").isEqualTo(4_750);
        // 8k 창: (8,192 − 지시 1,300 − 여유 819) / 2.5 = 2,429
        assertThat(MarkdownCorrectionService.sectionChars(10_000, 8_192, 0)).isEqualTo(2_429);
        // 같은 창에서 생각 여유 2,048 → (8,192 − 1,300 − 819 − 2,048) / 2.5 = 1,610
        assertThat(MarkdownCorrectionService.sectionChars(10_000, 8_192, 2_048)).isEqualTo(1_610);
        assertThat(MarkdownCorrectionService.sectionChars(10_000, 1_000, 0)).as("창이 지시 프롬프트도 못 담으면 판단 불가 — 파생값").isEqualTo(4_750);
        assertThat(MarkdownCorrectionService.sectionChars(1_000, 0, 0)).as("바닥 500").isEqualTo(500);
    }

    @Test
    @DisplayName("TXT→MD 블록 — 창을 모르면 6,000자, 알면 줄이기만 한다(바닥 500)")
    void blockCharsOnlyShrinks() {
        assertThat(TextToMarkdownService.blockChars(0, 0)).isEqualTo(6_000);
        assertThat(TextToMarkdownService.blockChars(131_072, 2_048)).isEqualTo(6_000);
        // 8k 창: (8,192 − 지시 600 − 여유 819 − 0) / 2.5 = 2,709
        assertThat(TextToMarkdownService.blockChars(8_192, 0)).isEqualTo(2_709);
        assertThat(TextToMarkdownService.blockChars(8_192, 2_048)).isLessThan(TextToMarkdownService.blockChars(8_192, 0));
        assertThat(TextToMarkdownService.blockChars(700, 0)).as("지시도 못 담는 창 — 판단 불가").isEqualTo(6_000);
    }

    // ── 답변·검증의 고정비와 발췌 판정 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("답변 프롬프트의 고정비 = 지시 + 질문 + 경고 + 섹션 머리말(200)")
    void answerFixedCostAddsTheSectionOverhead() {
        assertThat(AnswerService.answerFixedCost(1_000, 200, 0)).isEqualTo(1_400);
        assertThat(AnswerService.answerFixedCost(0, 0, 50)).isEqualTo(250);
    }

    @Test
    @DisplayName("검증 프롬프트의 고정비와 발췌 예산 — 입력 예산에서 고정비를 빼고, 창을 모르면 0(= 토큰 예산 없음)")
    void evalBudgets() {
        assertThat(AnswerService.evalFixedCost(1_100, 3_000, 200, 250)).isEqualTo(4_550);

        ThinkingBudget.Reservation reservation = new ThinkingBudget.Reservation(2_048, 512, 512, 4_096);   // 2,560
        long expected = new PromptBudget(16_384, 2_560).inputBudget() - 4_550;
        assertThat(AnswerService.evalExcerptBudget(16_384, reservation, 4_550)).isEqualTo(expected).isEqualTo(7_636);
        assertThat(AnswerService.evalExcerptBudget(0, reservation, 4_550)).isZero();
        assertThat(AnswerService.evalExcerptBudget(16_384, reservation, 1_000_000)).as("음수로 내려가지 않는다").isZero();
    }

    @Test
    @DisplayName("발췌 판정 — 첫 문서는 예산을 넘어도 늘 싣고, 이후는 토큰 예산·글자 상한 중 먼저 걸리는 쪽에서 멈춘다")
    void excerptFitsRules() {
        assertThat(AnswerService.excerptFits(0, 0, 50_000, 0, 50_000, 10)).as("첫 문서는 예산을 넘어도").isTrue();
        assertThat(AnswerService.excerptFits(1, 1_500, 1_500, 1_500, 1_500, 3_000)).isTrue();
        assertThat(AnswerService.excerptFits(2, 3_000, 1_500, 3_000, 1_500, 3_000)).as("토큰 예산 3,000 초과").isFalse();
        assertThat(AnswerService.excerptFits(2, 3_000, 1_500, 3_000, 1_500, 0)).as("예산 0 = 토큰 예산 없음(창 모름)").isTrue();
        assertThat(AnswerService.excerptFits(21, 31_500, 1_500, 0, 0, 0)).as("글자 상한 32,000 초과").isFalse();
    }

    @Test
    @DisplayName("검증 응답 스키마는 두 검증기가 각자 실어 보내는 그 문자열이다 — 비어 있지 않고 서로 다르다")
    void evalSchemas() {
        assertThat(AnswerService.evalSchema(false)).contains("sufficient", "grounded", "usedDocs");
        assertThat(AnswerService.evalSchema(true)).contains("sufficient", "apiGrounded", "inventedSymbols");
        assertThat(AnswerService.evalSchema(true)).isNotEqualTo(AnswerService.evalSchema(false));
    }

    @Test
    @DisplayName("대화 이력 상한 — max-tokens 의 절반, 바닥 1,000자")
    void conversationChars() {
        assertThat(MemoryService.conversationChars(10_000)).isEqualTo(5_000);
        assertThat(MemoryService.conversationChars(1_000)).isEqualTo(1_000);
        assertThat(MemoryService.conversationChars(500)).isEqualTo(1_000);
    }
}
