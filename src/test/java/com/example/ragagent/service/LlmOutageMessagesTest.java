package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소진 문구의 세 갈래 — 연속 실패 / 차단 잔여 초 / 일반 — 를 실제 메시지 번들(한·영)로 고정한다.
 * 예외의 한국어 메시지({@code (task=TEXT)} 꼬리 포함)는 어느 갈래에서도 그대로 나가지 않는다.
 */
class LlmOutageMessagesTest {

    private static final ResourceBundleMessageSource MESSAGES = new ResourceBundleMessageSource();
    static {
        MESSAGES.setBasename("messages");
        MESSAGES.setDefaultEncoding("UTF-8");
        // 앱과 같은 설정(spring.messages.fallback-to-system-locale=false) — 없으면 EN 이 한국어 OS 의
        // 기본 로케일 번들(messages_ko)로 떨어져 영문 단언이 한국어를 받는다.
        MESSAGES.setFallbackToSystemLocale(false);
    }

    @Test
    @DisplayName("연속 실패 — '서버(모델) 상태를 확인' + 차단 잔여 초")
    void repeatedFailureTellsToCheckTheServer() {
        var e = new LlmProviderExhaustedException("AI 서버가 연속 3회 응답하지 않습니다. (task=TEXT)", 4, 3);

        String ko = LlmOutageMessages.resolve(MESSAGES, e, Locale.KOREAN);
        assertThat(ko).isEqualTo("AI 서버가 연속 3회 응답하지 않습니다. 서버(모델) 상태를 확인해 주세요. (4초 후 재시도 가능)");
        assertThat(ko).doesNotContain("task=");

        String en = LlmOutageMessages.resolve(MESSAGES, e, Locale.ENGLISH);
        assertThat(en).isEqualTo("The AI server has failed 3 times in a row. Please check the server (model) status. (retry possible in 4s)");
    }

    @Test
    @DisplayName("연속 실패인데 차단 잔여가 없으면 초를 말하지 않는다")
    void repeatedFailureWithoutBlockOmitsTheSeconds() {
        var e = new LlmProviderExhaustedException("x", -1, 5);
        assertThat(LlmOutageMessages.resolve(MESSAGES, e, Locale.KOREAN))
                .isEqualTo("AI 서버가 연속 5회 응답하지 않습니다. 서버(모델) 상태를 확인해 주세요.");
    }

    @Test
    @DisplayName("차단 때문이면 몇 초 뒤에 되는지 — 실측: 30초 차단 하나에 재시도 3번이 전부 같은 오류로 죽었다")
    void blockedTellsHowLongToWait() {
        var e = new LlmProviderExhaustedException("AI 서버가 일시적으로 … (task=TEXT)", 20, 1);
        assertThat(LlmOutageMessages.resolve(MESSAGES, e, Locale.KOREAN))
                .isEqualTo("AI 서버가 일시적으로 응답하지 않습니다. 20초 후 다시 시도해 주세요.");
        assertThat(LlmOutageMessages.resolve(MESSAGES, e, Locale.ENGLISH))
                .isEqualTo("The AI server is temporarily not responding. Please try again in 20s.");
    }

    @Test
    @DisplayName("둘 다 아니면 예전의 일반 문구 그대로")
    void plainExhaustionKeepsTheGenericText() {
        var e = new LlmProviderExhaustedException("AI 서버에 연결하지 못했습니다. (task=TEXT)");
        assertThat(LlmOutageMessages.resolve(MESSAGES, e, Locale.KOREAN))
                .isEqualTo(MESSAGES.getMessage("error.llm.exhausted", null, Locale.KOREAN))
                .doesNotContain("task=");
    }
}
