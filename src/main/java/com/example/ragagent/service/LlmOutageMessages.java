package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * 소진({@link LlmProviderExhaustedException})을 사용자에게 어떤 문장으로 보일지 — 채팅 진입점 둘
 * (SSE {@code StreamingAgentService}, HTMX {@code ChatController})이 같은 규칙을 쓰기 위한 순수 클래스.
 *
 * <p>예외의 메시지를 그대로 내보내지 않는 이유는 그것이 한국어 고정({@code LlmRouter} 가 만든다)이고
 * {@code (task=TEXT)} 같은 진단 꼬리가 붙어 있어서다. 대신 예외가 나르는 <b>사실</b> 둘로 현지화된
 * 문장을 고른다:
 * <ol>
 *   <li>{@link LlmProviderExhaustedException#repeated()} — 같은 프로바이더가 연속 N회 실패했다.
 *       "잠시 후 다시"는 거짓이고 서버(모델) 상태를 보라고 말한다.</li>
 *   <li>{@link LlmProviderExhaustedException#retryAfterSeconds()} — 차단 때문이라 몇 초 뒤엔 된다.
 *       초를 말해 주지 않으면 사용자가 할 수 있는 일은 계속 눌러 보는 것뿐이다(실측: 30초 차단 하나에
 *       재시도 3번이 전부 같은 오류로 죽었다).</li>
 * </ol>
 * 둘 다 아니면 예전의 일반 문구({@code error.llm.exhausted}) 그대로다.
 */
public final class LlmOutageMessages {

    private LlmOutageMessages() {}

    public static String resolve(MessageSource messages, LlmProviderExhaustedException e, Locale locale) {
        int wait = e.retryAfterSeconds();
        if (e.repeated()) {
            String base = messages.getMessage("error.llm.exhausted.repeated",
                    new Object[] {e.consecutiveFailures()},
                    "AI 서버가 연속 " + e.consecutiveFailures() + "회 응답하지 않습니다. 서버(모델) 상태를 확인해 주세요.",
                    locale);
            if (wait < 0) return base;
            return base + " " + messages.getMessage("error.llm.exhausted.retry-suffix",
                    new Object[] {wait}, "(" + wait + "초 후 재시도 가능)", locale);
        }
        if (wait >= 0) {
            return messages.getMessage("error.llm.exhausted.retry", new Object[] {wait},
                    "AI 서버가 일시적으로 응답하지 않습니다. " + wait + "초 후 다시 시도해 주세요.", locale);
        }
        return messages.getMessage("error.llm.exhausted", null,
                "LLM 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", locale);
    }
}
