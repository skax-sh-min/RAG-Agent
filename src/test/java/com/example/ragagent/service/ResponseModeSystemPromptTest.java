package com.example.ragagent.service;

import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모드별 시스템 프롬프트의 내용 규칙을 실제 번들에서 고정한다 (PLAN §6.24 Step 0-c/1-a/1-b).
 *
 * <p>{@code AnswerServiceTest}/{@code DirectAnswerServiceTest}는 {@code MessageSource}를 목킹하므로
 * 실제 프롬프트 문구를 한 글자도 읽지 않는다. 그런데 이 단계의 설계는 <b>전부 프롬프트 안에만</b>
 * 있다 — 어느 코드도 "S는 5섹션을 언급하지 않는다"거나 "N에는 글자 수가 없다"를 강제하지 않으므로,
 * 누가 편집해 되돌려도 빌드는 조용히 통과한다. {@link AnswerEvalPromptTest}와 같은 이유의 가드다.
 */
class ResponseModeSystemPromptTest {

    /** 5섹션 형식의 헤더 이름 — S 프롬프트가 <b>언급조차 하면 안 되는</b> 문자열. */
    private static final String[] SECTION_HEADERS = {"상세 설명", "예시/코드", "설정/주의사항", "참고"};

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        // 개발기 시스템 로케일이 ko 라 ENGLISH 조회가 messages_ko 로 폴백되면 영어 번들을
        // 한 줄도 안 읽고 통과한다 (AnswerEvalPromptTest 와 동일한 이유).
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    private static String prompt(String key, Locale locale) {
        return realMessageSource().getMessage(key, null, locale);
    }

    @Test
    @DisplayName("모드가 가리키는 시스템 프롬프트 키가 한/영 번들에 모두 존재한다")
    void everyModePromptKeyResolvesInBothBundles() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
                assertThat(prompt(mode.answerSystemPromptKey(), locale))
                        .as("%s / %s", mode.answerSystemPromptKey(), locale).isNotBlank();
                if (mode.allowsDirect()) {
                    assertThat(prompt(mode.directSystemPromptKey(), locale))
                            .as("%s / %s", mode.directSystemPromptKey(), locale).isNotBlank();
                }
            }
        }
    }

    @Test
    @DisplayName("S 프롬프트는 1,000자 상한을 명시하고 5섹션 헤더는 언급조차 하지 않는다")
    void summaryPromptCapsLengthAndNeverNamesTheSectionFormat() {
        for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
            String s = prompt(ResponseMode.S.answerSystemPromptKey(), locale);
            assertThat(s).as("S/%s 는 1,000자 상한을 말해야 한다", locale).contains("1,000");
            assertThat(s).as("S/%s 는 요약 헤더를 지시해야 한다", locale).contains("## 요약");
            // 금지하려고 나열하는 것만으로도 성능이 낮은 로컬 모델은 그 목록을 따라간다 —
            // 예전 style.s 가 정확히 그렇게 실패했다.
            assertThat(s).as("S/%s 가 5섹션 헤더를 언급하면 안 된다", locale)
                    .doesNotContain(SECTION_HEADERS);
        }
    }

    @Test
    @DisplayName("N 프롬프트는 글자 수를 말하지 않고 '구체적이고 자세하게'를 지시한다")
    void standardPromptNamesNoNumberAndAsksForDetail() {
        String ko = prompt(ResponseMode.N.answerSystemPromptKey(), Locale.KOREAN);
        String en = prompt(ResponseMode.N.answerSystemPromptKey(), Locale.ENGLISH);

        assertThat(ko).contains("구체적이고 자세하게");
        assertThat(en.toLowerCase()).contains("concrete and thorough");
        // 긴 출력에 건 숫자 목표는 모델이 스스로 멈추는 지점보다 뒤에 있어 아무 일도 하지 않는다
        // (구 M "약 5,000자" → 실제 3,047자). 지키지 못할 숫자를 남기면 같은 혼란이 반복된다.
        assertThat(ko).as("N 프롬프트에 글자 수 목표가 되살아났다").doesNotContain("자 이내", "1,000", "5,000");
        assertThat(en.toLowerCase()).as("N prompt must not name a character budget")
                .doesNotContain("characters", "1,000", "5,000");
        // 5섹션 형식은 N 쪽에 그대로 남아 있어야 한다.
        assertThat(ko).contains(SECTION_HEADERS);
    }

    @Test
    @DisplayName("Direct 프롬프트도 같은 분량 규칙을 따른다 (S=1,000자 상한 / N=숫자 없음)")
    void directPromptsFollowTheSameLengthPolicy() {
        String directS = prompt(ResponseMode.S.directSystemPromptKey(), Locale.KOREAN);
        String directN = prompt(ResponseMode.N.directSystemPromptKey(), Locale.KOREAN);

        assertThat(directS).contains("1,000", "## 요약");
        assertThat(directN).contains("구체적이고 자세하게").doesNotContain("자 이내", "1,000");
    }

    @Test
    @DisplayName("스타일 지시문 층은 완전히 사라졌다 (§6.24 Step 0-c)")
    void styleInstructionLayerIsGone() {
        for (ResponseMode mode : ResponseMode.values()) {
            for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
                assertThat(realMessageSource().getMessage(
                        "prompt.answer.style." + mode.name().toLowerCase(), null, "MISSING", locale))
                        .as("prompt.answer.style.%s (%s) 가 되살아났다 — 사용자 메시지로 시스템 "
                            + "프롬프트를 덮어쓰는 층은 다시 만들지 않는다", mode, locale)
                        .isEqualTo("MISSING");
            }
        }
    }
}
