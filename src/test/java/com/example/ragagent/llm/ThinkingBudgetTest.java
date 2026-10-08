package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생각을 켠 호출의 출력 예약(PLAN §6.29 ④). 숫자는 PLAN 의 예시 표와 같아야 한다 — 공식이 바뀌면 여기가 깨진다(골든).
 * 미리보기(5단계)가 같은 함수를 부르므로, 이 숫자가 곧 화면의 숫자다.
 */
class ThinkingBudgetTest {

    /**
     * 16k 창 · 전역 max-tokens 10,000 의 프로바이더 상한 — 10,000 그대로다({@code cappedMaxTokens} 는 max-tokens 가 창
     * 이상일 때만 창의 절반으로 누른다). 그래서 16k 창에서 생각 여유를 묶는 것은 창 25%(4,096)다.
     */
    private static final int WINDOW_16K = 16_384;
    private static final int PROVIDER_MAX_16K = ProviderContextWindows.cappedMaxTokens(10_000, WINDOW_16K);

    private static ThinkingBudget.Reservation eval16k(ThinkingLevel level) {
        return ThinkingBudget.compute(2_048, level, level != ThinkingLevel.OFF, false, PROVIDER_MAX_16K, WINDOW_16K);
    }

    @Test
    @DisplayName("수준별 여유 — 끔 0 · 낮게 1,024 · 중간 2,048 · 높게 4,096 (6단계 실측으로 낮게를 512 에서 올렸다)")
    void headroomPerLevel() {
        assertThat(List.of(ThinkingLevel.values())).extracting(ThinkingBudget::headroom)
                .containsExactly(0, 1_024, 2_048, 4_096);
    }

    @Test
    @DisplayName("골든 — 16k 창의 검증(기본 2,048): 끔 2,048 · 낮게 3,072 · 중간 4,096(상한에 닿음) · 높게 4,096(상한에 깎임)")
    void goldenEvalOn16k() {
        assertThat(PROVIDER_MAX_16K).as("전제 — 16k 창의 프로바이더 상한").isEqualTo(10_000);
        assertThat(List.of(ThinkingLevel.values())).extracting(l -> eval16k(l).tokens())
                .containsExactly(2_048, 3_072, 4_096, 4_096);
        assertThat(eval16k(ThinkingLevel.HIGH).ceiling()).as("창의 25%").isEqualTo(4_096);
        assertThat(eval16k(ThinkingLevel.MEDIUM).clipped()).as("상한에 정확히 닿았을 뿐 깎이지 않았다").isFalse();
        assertThat(eval16k(ThinkingLevel.HIGH).clipped()).as("높게는 4,096 을 요구해 2,048 만 받았다 — 깎임").isTrue();
        // PLAN ⑦-나 예시의 입력 예산 줄: 12,698 · 11,674 · 10,650 · 10,650
        assertThat(List.of(ThinkingLevel.values()))
                .extracting(l -> new PromptBudget(WINDOW_16K, eval16k(l).tokens()).inputBudget())
                .containsExactly(12_698, 11_674, 10_650, 10_650);
    }

    @Test
    @DisplayName("골든 — 8k 창의 검증: 상한(창 25% = 2,048)이 기본 예약과 같아 어느 수준도 여유를 못 받는다 — 입력 예산 5,325 그대로")
    void goldenEvalOn8k() {
        int window = 8_192;
        int providerMax = ProviderContextWindows.cappedMaxTokens(10_000, window);
        for (ThinkingLevel level : List.of(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)) {
            ThinkingBudget.Reservation r = ThinkingBudget.compute(2_048, level, true, false, providerMax, window);
            assertThat(r.tokens()).as(level.name()).isEqualTo(2_048);
            assertThat(r.clipped()).as("깎였다는 사실은 남는다 — 미리보기의 '여유 깎임'").isTrue();
            assertThat(new PromptBudget(window, r.tokens()).inputBudget()).isEqualTo(5_325);
        }
    }

    @Test
    @DisplayName("기본 예약은 깎지 않는다 — 16k 창의 스트리밍 답변(N 5,000)은 상한 4,096 위라 여유 0, 예약 5,000 그대로")
    void neverCutsTheBaseReservation() {
        ThinkingBudget.Reservation r = ThinkingBudget.compute(5_000, ThinkingLevel.HIGH, true, false,
                PROVIDER_MAX_16K, WINDOW_16K);

        assertThat(r.tokens()).as("앞선 초안의 min(기본 + h, C) 는 여기서 4,096 으로 깎았다").isEqualTo(5_000);
        assertThat(r.granted()).isZero();
        assertThat(r.describe()).isEqualTo("5,000 (기본 5,000 + 생각 0/4,096, 상한 4,096)");
    }

    @Test
    @DisplayName("32k 창의 스트리밍 답변(N 5,000) + 낮게 — 상한 8,192 까지 남은 자리에서 1,024 를 받는다")
    void answerOn32kGetsTheHeadroom() {
        int window = 32_768;
        ThinkingBudget.Reservation r = ThinkingBudget.compute(5_000, ThinkingLevel.LOW, true, false,
                ProviderContextWindows.cappedMaxTokens(10_000, window), window);

        assertThat(r.tokens()).isEqualTo(6_024);
        assertThat(r.describe()).isEqualTo("6,024 (기본 5,000 + 생각 1,024)");
    }

    @Test
    @DisplayName("켬으로 나가지 않으면(끔·생각 제어 안 함·거부됨) 여유가 없다 — 끔이 확정된 호출의 예약을 줄이지도 않는다")
    void noHeadroomUnlessThinkingIsOn() {
        ThinkingBudget.Reservation notSent = ThinkingBudget.compute(256, ThinkingLevel.HIGH, false, false,
                PROVIDER_MAX_16K, WINDOW_16K);
        ThinkingBudget.Reservation off = ThinkingBudget.compute(256, ThinkingLevel.OFF, true, false,
                PROVIDER_MAX_16K, WINDOW_16K);

        assertThat(notSent.tokens()).isEqualTo(256);
        assertThat(off.tokens()).isEqualTo(256);
        assertThat(notSent.describe()).isEqualTo("256");
    }

    @Test
    @DisplayName("상한을 싣지 않는 호출(기본 예약 0 = 프로바이더 기본값)은 손대지 않는다 — PLAN 열린 항목 (d)")
    void providerDefaultIsLeftAlone() {
        ThinkingBudget.Reservation r = ThinkingBudget.compute(0, ThinkingLevel.HIGH, true, false, PROVIDER_MAX_16K, WINDOW_16K);
        assertThat(r.tokens()).isZero();
        assertThat(r.describe()).as("로그에 0 이 아니라 무엇인지를 쓴다").isEqualTo("프로바이더 기본값");
    }

    @Test
    @DisplayName("재작성 — 창 25% 로 깎지 않고(조각이 자리를 만든다) 프로바이더 상한으로만 자른다")
    void rewriteIsBoundedOnlyByTheProvider() {
        ThinkingBudget.Reservation rewrite = ThinkingBudget.compute(6_000, ThinkingLevel.MEDIUM, true, true,
                PROVIDER_MAX_16K, WINDOW_16K);
        ThinkingBudget.Reservation nearCap = ThinkingBudget.compute(9_500, ThinkingLevel.MEDIUM, true, true,
                PROVIDER_MAX_16K, WINDOW_16K);

        assertThat(rewrite.tokens()).as("창 25%(4,096) 를 넘어도 여유를 준다").isEqualTo(8_048);
        assertThat(nearCap.tokens()).as("프로바이더 상한 10,000 에서 멈춘다").isEqualTo(10_000);
        assertThat(nearCap.clipped()).isTrue();
    }

    @Test
    @DisplayName("상한을 하나도 모르면 늘리지 않는다 — 모르는 상한으로 예약을 키우지 않는다")
    void unknownCeilingAddsNothing() {
        ThinkingBudget.Reservation r = ThinkingBudget.compute(2_048, ThinkingLevel.LOW, true, false, 0, 0);

        assertThat(r.tokens()).isEqualTo(2_048);
        assertThat(r.requested()).isEqualTo(1_024);
    }

    @Test
    @DisplayName("불변식 — 어떤 수준·창·기본 예약에서도 예약 ≥ 기본 예약, 여유 ≤ 수준의 여유")
    void reservationNeverShrinksAndNeverOvershoots() {
        for (ThinkingLevel level : ThinkingLevel.values()) {
            for (int window : new int[]{0, 4_096, 8_192, 16_384, 32_768, 131_072}) {
                for (int base : new int[]{1, 256, 2_048, 5_000, 7_000, 10_000}) {
                    for (boolean rewrite : new boolean[]{false, true}) {
                        int providerMax = ProviderContextWindows.cappedMaxTokens(10_000, window > 0 ? window : null);
                        ThinkingBudget.Reservation r = ThinkingBudget.compute(base, level, true, rewrite, providerMax, window);
                        assertThat(r.tokens()).as("%s/%d/%d", level, window, base).isGreaterThanOrEqualTo(base);
                        assertThat(r.granted()).isBetween(0, ThinkingBudget.headroom(level));
                    }
                }
            }
        }
    }

    // ── 빈 — 예산을 미리 재는 자리가 부른다 ──────────────────────────────────

    private static ThinkingBudget bean(ThinkingDialect dialect, boolean local, Integer providerMaxTokens,
                                       ThinkingLevel level, int window) {
        AppProperties.ProviderConfig cfg = new AppProperties.ProviderConfig(
                "p", "http://x/v1", "", "m", "BOTH", local ? "LOCAL" : "NORMAL", 1, true, null, null, providerMaxTokens);
        AppProperties.LlmConfig llm = new AppProperties.LlmConfig(
                List.of(cfg), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 10_000, 1, false, Map.of());
        AppProperties props = org.mockito.Mockito.mock(AppProperties.class);
        org.mockito.Mockito.when(props.llmSafe()).thenReturn(llm);
        ProviderThinkingDialects dialects = new ProviderThinkingDialects();
        dialects.record("p", dialect, local);
        ProviderContextWindows windows = new ProviderContextWindows();
        if (window > 0) windows.record("p", window, ProviderContextWindows.Source.PROBED);
        return new ThinkingBudget(props, dialects, windows, site -> level);
    }

    @Test
    @DisplayName("빈 — 프로바이더의 dialect·창·max-tokens 로 같은 함수를 부른다(LOCAL auto + 낮게 = 3,072)")
    void beanUsesTheProvidersFacts() {
        assertThat(bean(ThinkingDialect.AUTO, true, null, ThinkingLevel.LOW, WINDOW_16K)
                .reservation(ThinkingSite.EVAL, "p", 2_048).tokens()).isEqualTo(3_072);
        assertThat(bean(ThinkingDialect.AUTO, false, null, ThinkingLevel.LOW, WINDOW_16K)
                .reservation(ThinkingSite.EVAL, "p", 2_048).tokens()).as("원격(auto) — 생각 제어 안 함").isEqualTo(2_048);
        assertThat(bean(ThinkingDialect.AUTO, true, 2_300, ThinkingLevel.LOW, WINDOW_16K)
                .reservation(ThinkingSite.EVAL, "p", 2_048).tokens()).as("프로바이더 자기 max-tokens 가 상한").isEqualTo(2_300);
        assertThat(bean(ThinkingDialect.AUTO, true, null, ThinkingLevel.LOW, 0)
                .reservation(ThinkingSite.EVAL, "p", 2_048).tokens()).as("창 모름 — 프로바이더 max-tokens 만").isEqualTo(3_072);
    }

    @Test
    @DisplayName("빈 — 재작성 사이트의 조각 여유는 켬으로 나갈 때만 수준의 여유 그대로")
    void rewriteHeadroom() {
        assertThat(bean(ThinkingDialect.AUTO, true, null, ThinkingLevel.MEDIUM, WINDOW_16K)
                .rewriteHeadroom(ThinkingSite.MD_CORRECT, "p")).isEqualTo(2_048);
        assertThat(bean(ThinkingDialect.AUTO, true, null, ThinkingLevel.OFF, WINDOW_16K)
                .rewriteHeadroom(ThinkingSite.MD_CORRECT, "p")).isZero();
        assertThat(bean(ThinkingDialect.NONE, true, null, ThinkingLevel.HIGH, WINDOW_16K)
                .rewriteHeadroom(ThinkingSite.TXT_TO_MD, "p")).isZero();
    }

    @Test
    @DisplayName("none() — 생각 제어를 모르는 하위호환 자리는 언제나 기본 예약 그대로")
    void noneIsIdentity() {
        assertThat(ThinkingBudget.none().reservation(ThinkingSite.EVAL, "p", 2_048).tokens()).isEqualTo(2_048);
        assertThat(ThinkingBudget.none().rewriteHeadroom(ThinkingSite.MD_CORRECT, "p")).isZero();
    }

    @Test
    @DisplayName("재작성 사이트는 MD 교정과 TXT→MD 둘뿐이다")
    void rewriteSites() {
        assertThat(List.of(ThinkingSite.values())).filteredOn(ThinkingSite::rewritesInput)
                .containsExactlyInAnyOrder(ThinkingSite.MD_CORRECT, ThinkingSite.TXT_TO_MD);
    }
}
