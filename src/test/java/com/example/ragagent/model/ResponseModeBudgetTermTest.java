package com.example.ragagent.model;

import com.example.ragagent.model.ResponseMode.BudgetTerm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResponseMode#budgetTerm(int)} — 블로킹 답변 예산({@link ResponseMode#maxTokens(int)})을 <b>어느 항이 정했는가</b>.
 *
 * <p>예전에는 "LLM 튜닝" 그룹의 읽기 전용 "응답 예산" 행이 이 말을 했고, 지금은 생각 수준 카드의 답변 사이트 계산 근거가 한다.
 * 화면이 푸는 문구가 실제 예산과 갈라지면 설정 화면이 거짓말을 하므로, 여기서는 항의 판정이 {@code maxTokens} 의 결과와
 * 어긋나지 않는지를 모든 모드·여러 설정값에서 본다.
 */
class ResponseModeBudgetTermTest {

    @Test
    @DisplayName("어느 항이 이겼는가 — 실사용 12,000 에서 S 는 바닥이, N 은 비율이 받친다")
    void whichTermWon() {
        // S: 비율항 1,800 < 바닥 2,000 → 바닥. N/C: 비율항 8,400 > 바닥 5,000 → 비율.
        assertThat(ResponseMode.S.budgetTerm(12_000)).isEqualTo(BudgetTerm.FLOOR);
        assertThat(ResponseMode.N.budgetTerm(12_000)).isEqualTo(BudgetTerm.RATIO);
        assertThat(ResponseMode.C.budgetTerm(12_000)).isEqualTo(BudgetTerm.RATIO);
        // 16,000: S 도 비율이 이긴다 — 전환점은 S 13,334 / N 7,143
        assertThat(ResponseMode.S.budgetTerm(16_000)).isEqualTo(BudgetTerm.RATIO);
        // 설정 상한이 모드의 요구보다 낮으면 상한에서 잘린다(§6.24 클램프) — 그 사실이 보여야 한다
        assertThat(ResponseMode.N.budgetTerm(3_000)).isEqualTo(BudgetTerm.CONFIGURED_CAP);
    }

    @Test
    @DisplayName("항의 판정은 항상 maxTokens() 와 일치한다 — 비율항·바닥·설정 상한이 각각 실제 값과 같다(공식 복제 금지)")
    void termAlwaysAgreesWithTheRealBudget() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (int configured : new int[]{1_000, 3_000, 6_000, 7_143, 12_000, 13_334, 16_000, 32_000}) {
                int effective = mode.maxTokens(configured);
                int ratioTokens = (int) Math.round(configured * mode.tokenRatio());
                String where = "%s @ %d".formatted(mode, configured);
                switch (mode.budgetTerm(configured)) {
                    case CONFIGURED_CAP -> assertThat(effective).as(where).isEqualTo(configured);
                    case RATIO -> assertThat(effective).as(where).isEqualTo(ratioTokens);
                    case FLOOR -> assertThat(effective).as(where).isEqualTo(mode.minChars());
                }
            }
        }
    }
}
