package com.example.ragagent.service;

import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /settings} 의 "응답 예산" 파생 행 계약 (PLAN §6.24 Step 0-e).
 *
 * <p>행 자체는 {@link ResponseMode#values()} 를 돌아 저절로 늘어나지만 <b>라벨은 메시지 키</b>라
 * 번들에 한 줄이 필요하다. 그 누락은 컴파일도 테스트도 통과하고 화면에 {@code ??key??} 로만
 * 드러나므로(=아무도 안 본다) 여기서 고정한다.
 */
class SettingsResponseModeBudgetTest {

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);   // ENGLISH 조회가 ko 로 폴백되면 영어 번들을 안 읽는다
        return ms;
    }

    @Test
    @DisplayName("모든 응답 모드의 예산 라벨 키가 한/영 번들에 존재한다")
    void everyModeHasABudgetLabelInBothBundles() {
        for (ResponseMode mode : ResponseMode.values()) {
            String key = "settings.item.mode-budget." + mode.name().toLowerCase();
            for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
                assertThat(realMessageSource().getMessage(key, null, "MISSING", locale))
                        .as("%s (%s) — 모드를 추가하면 이 라벨도 함께 추가해야 한다", key, locale)
                        .isNotEqualTo("MISSING");
            }
        }
    }

    @Test
    @DisplayName("표시값은 어느 항이 이겼는지를 함께 알려준다 — 바닥 / 비율 / 상한")
    void budgetLabelsWhichTermWon() {
        // 실사용 12,000: 비율항(1,800 / 4,800)이 모두 minChars 아래라 바닥이 이긴다.
        assertThat(SettingsService.formatModeBudgetForTest(ResponseMode.S, 12_000)).isEqualTo("2,000 (바닥)");
        assertThat(SettingsService.formatModeBudgetForTest(ResponseMode.N, 12_000)).isEqualTo("5,000 (바닥)");
        // 16,000: 비율항(2,400 / 6,400)이 minChars 를 넘어 비율이 이긴다 — 전환점 S 13,334 / N 12,501.
        assertThat(SettingsService.formatModeBudgetForTest(ResponseMode.S, 16_000)).isEqualTo("2,400 (비율)");
        assertThat(SettingsService.formatModeBudgetForTest(ResponseMode.N, 16_000)).isEqualTo("6,400 (비율)");
        // 설정 상한이 모드 바닥보다 낮으면 상한에서 잘린다(§6.24 클램프) — 그 사실이 화면에 보여야 한다.
        assertThat(SettingsService.formatModeBudgetForTest(ResponseMode.N, 3_000)).isEqualTo("3,000 (상한)");
    }

    @Test
    @DisplayName("표시값은 항상 ResponseMode.maxTokens() 와 일치한다(공식 복제 금지)")
    void displayedValueAlwaysMatchesTheRealBudget() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (int configured : new int[]{1_000, 6_000, 12_000, 16_000, 32_000}) {
                String shown = SettingsService.formatModeBudgetForTest(mode, configured);
                String expected = "%,d".formatted(mode.maxTokens(configured));
                assertThat(shown)
                        .as("%s @ %d — 화면 값이 실제 예산과 갈라지면 설정 화면이 거짓말을 한다", mode, configured)
                        .startsWith(expected + " (");
            }
        }
    }
}
