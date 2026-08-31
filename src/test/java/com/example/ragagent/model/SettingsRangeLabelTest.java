package com.example.ragagent.model;

import com.example.ragagent.model.SettingsView.SettingItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /settings} 편집 컨트롤의 <b>허용 범위 툴팁</b> 계약.
 *
 * <p>경계값은 {@code Double} 이라 그대로 찍으면 {@code 1.0 ~ 1000.0} 이 된다 — 눈에 거슬리는 만큼
 * 조용하기도 해서(예외도, 로그도 없다) 여기서 고정한다. 문구 자체는 메시지 번들에 있으므로 두
 * 번들 모두에 키가 있는지, 그리고 인자가 실제로 치환되는지도 함께 본다: 키가 빠지면 화면에는
 * {@code ??settings.range.number_ko??} 가 title 속성 안에 숨어 아무도 보지 못한다.
 */
class SettingsRangeLabelTest {

    private static SettingItem numberItem(Double min, Double max, Double step) {
        return new SettingItem("k", "settings.item.rrf-k", "70", "number", true, false, null, min, max, step);
    }

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);   // ENGLISH 조회가 ko 로 폴백되면 영어 번들을 안 읽는다
        return ms;
    }

    @Test
    @DisplayName("정수 경계는 소수점을 떼고, 소수 경계는 그대로 둔다")
    void integralBoundsLoseTheirDecimalPoint() {
        SettingItem intBounds = numberItem(1.0, 1000.0, 1.0);
        assertThat(intBounds.minLabel()).isEqualTo("1");
        assertThat(intBounds.maxLabel()).isEqualTo("1000");
        assertThat(intBounds.stepLabel()).isEqualTo("1");

        SettingItem fractional = numberItem(0.0, 0.3, 0.01);
        assertThat(fractional.minLabel()).isEqualTo("0");
        assertThat(fractional.maxLabel()).isEqualTo("0.3");
        assertThat(fractional.stepLabel()).isEqualTo("0.01");
    }

    @Test
    @DisplayName("경계가 없는 행(bool·읽기 전용)은 빈 문자열 — 툴팁이 'null' 을 보여주면 안 된다")
    void absentBoundsRenderAsEmpty() {
        SettingItem noBounds = new SettingItem("k", "settings.item.retry-escalate", "true",
                "bool", true, false, null, null, null, null);
        assertThat(noBounds.minLabel()).isEmpty();
        assertThat(noBounds.maxLabel()).isEmpty();
        assertThat(noBounds.stepLabel()).isEmpty();
    }

    @Test
    @DisplayName("범위 문구 키가 한/영 번들에 있고 경계값이 실제로 치환된다")
    void rangeMessagesExistInBothBundlesAndInterpolate() {
        SettingItem item = numberItem(1.0, 1000.0, 1.0);
        Object[] args = {item.minLabel(), item.maxLabel(), item.stepLabel()};

        for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
            String number = realMessageSource().getMessage("settings.range.number", args, "MISSING", locale);
            assertThat(number).as("settings.range.number (%s)", locale)
                    .isNotEqualTo("MISSING")
                    .contains("1", "1000")
                    .doesNotContain("1.0", "{0}");

            assertThat(realMessageSource().getMessage("settings.range.bool", null, "MISSING", locale))
                    .as("settings.range.bool (%s)", locale)
                    .isNotEqualTo("MISSING");
        }
    }
}
