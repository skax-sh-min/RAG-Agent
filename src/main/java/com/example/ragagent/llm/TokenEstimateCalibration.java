package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link TokenEstimator} 의 추정을 <b>서버가 실제로 센 토큰 수</b>와 대조해, 그 가정이 이 배포에서
 * 맞는지 관측한다.
 *
 * <p><b>왜 필요한가.</b> 컨텍스트 입력 예산({@code PromptBudget})과 인덱싱 출력 상한
 * ({@code IndexingOutputCap})이 모두 이 추정 위에 서 있는데, 추정의 핵심 가정 — <b>한글 1글자 ≈
 * 1토큰</b> — 은 토크나이저마다 크게 다르다. 어휘가 큰 최신 모델은 한글을 글자당 0.5~0.7 토큰으로
 * 처리하기도 하고, 그러면 <b>예산이 실제보다 30~50% 빡빡하게</b> 잡혀 멀쩡한 근거 문서를 버린다.
 * 반대로 1보다 크면 예산이 헐거워져 막으려던 컨텍스트 초과가 그대로 난다. 어느 쪽이든 숫자를 보기
 * 전에는 알 수 없고, 이 클래스가 그 숫자를 만든다.
 *
 * <p><b>블로킹 호출에서만 관측된다.</b> 실제 토큰 수는 {@code ChatResponse} 의 usage 메타데이터로만
 * 오는데, 스트리밍은 토큰 델타만 주고 usage 를 주지 않는다({@code TrackingChatModel} 이 같은 이유로
 * {@code stream()} 을 그냥 위임한다). 다행히 계수는 경로가 아니라 <b>텍스트와 토크나이저</b>의
 * 성질이라, 블로킹 표본으로 잰 값이 스트리밍 프롬프트에도 그대로 적용된다.
 *
 * <p><b>관측만 하고 아무것도 바꾸지 않는다.</b> 측정값으로 예산을 자동 보정하지 않는 이유는, 그러면
 * 같은 질문이 표본 수에 따라 다른 양의 근거를 받게 되어 재현이 안 되기 때문이다. 계수가 1 에서
 * 크게 벗어나면 로그가 알려주고, 조정은 사람이 한다.
 */
@Component
public class TokenEstimateCalibration {

    private static final Logger log = LoggerFactory.getLogger(TokenEstimateCalibration.class);

    /** 이만큼 표본이 쌓일 때마다 누적 계수를 INFO 로 한 번 알린다. */
    private static final long LOG_EVERY = 50;

    /** 이 범위를 벗어나면 예산이 실질적으로 어긋난 것이라 경고로 올린다. */
    private static final double SANE_LOW = 0.75;
    private static final double SANE_HIGH = 1.35;

    private final AtomicLong samples = new AtomicLong();
    private final AtomicLong estimatedSum = new AtomicLong();
    private final AtomicLong actualSum = new AtomicLong();
    private final AtomicLong cjkChars = new AtomicLong();
    private final AtomicLong totalChars = new AtomicLong();

    /**
     * @param promptText   실제로 전송된 프롬프트 전문(시스템 + 사용자 메시지)
     * @param actualTokens 서버가 보고한 prompt 토큰 수. 0 이하면 무시한다(usage 미보고 서버)
     */
    public void record(String promptText, int actualTokens) {
        if (promptText == null || promptText.isEmpty() || actualTokens <= 0) return;
        long estimated = TokenEstimator.estimate(promptText);
        if (estimated <= 0) return;

        estimatedSum.addAndGet(estimated);
        actualSum.addAndGet(actualTokens);
        countScript(promptText);
        long n = samples.incrementAndGet();

        log.debug("[TOKEN_CAL] 추정 {} / 실제 {} (비 {})", estimated, actualTokens,
                String.format("%.2f", (double) actualTokens / estimated));
        if (n % LOG_EVERY == 0) reportCumulative(n);
    }

    /**
     * 지금까지의 <b>실제/추정</b> 비율. 1.0 이면 {@link TokenEstimator} 가 맞고, 0.6 이면 추정이
     * 실제보다 40% 크다(= 예산이 그만큼 빡빡하다). 표본이 없으면 비어 있다.
     */
    public Double ratio() {
        long est = estimatedSum.get();
        return est <= 0 ? null : (double) actualSum.get() / est;
    }

    public long sampleCount() {
        return samples.get();
    }

    /** 관측된 텍스트 중 CJK 글자의 비율 — 계수를 읽을 때 필요한 맥락이다(순수 영어면 계수가 달라진다). */
    public Double cjkFraction() {
        long total = totalChars.get();
        return total <= 0 ? null : (double) cjkChars.get() / total;
    }

    private void reportCumulative(long n) {
        Double r = ratio();
        if (r == null) return;
        String line = "[TOKEN_CAL] 표본 {}건 — 추정 {} / 실제 {} → 계수 {} (CJK 글자 비율 {}). "
                + "TokenEstimator 는 한글을 글자당 1토큰으로 셉니다.";
        Object[] args = {n, estimatedSum.get(), actualSum.get(),
                String.format("%.2f", r), String.format("%.0f%%", cjkFraction() * 100)};
        if (r < SANE_LOW || r > SANE_HIGH) {
            log.warn(line + " 계수가 1.0 에서 크게 벗어나 컨텍스트 입력 예산이 그만큼 어긋납니다 — "
                    + "1 보다 작으면 근거 문서를 필요 이상으로 버리고, 크면 컨텍스트 초과를 못 막습니다.", args);
        } else {
            log.info(line, args);
        }
    }

    private void countScript(String text) {
        long cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (TokenEstimator.isCjkLike(cp)) cjk++;
            i += Character.charCount(cp);
        }
        cjkChars.addAndGet(cjk);
        totalChars.addAndGet(text.codePointCount(0, text.length()));
    }

    /**
     * 이 계측을 붙인 {@link ChatModel} 을 돌려준다. 데코레이터로 두는 이유는 <b>프롬프트와 usage 를
     * 동시에 보는 자리가 여기뿐</b>이기 때문이다 — {@code LlmRouter} 는 호출을 불투명한 클로저로
     * 받아 프롬프트 안을 못 보고, 호출부는 서버가 센 토큰 수를 못 본다.
     */
    public ChatModel wrap(ChatModel delegate) {
        return new CalibratingChatModel(delegate);
    }

    private final class CalibratingChatModel implements ChatModel {

        private final ChatModel delegate;

        private CalibratingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            try {
                var usage = response.getMetadata().getUsage();
                if (usage != null && usage.getPromptTokens() != null) {
                    record(promptText(prompt), usage.getPromptTokens());
                }
            } catch (Exception e) {
                // 순수 계측이다 — 여기서 나는 어떤 문제도 이미 성공한 호출을 실패로 만들면 안 된다.
                log.debug("[TOKEN_CAL] 표본 기록 실패: {}", e.toString());
            }
            return response;
        }

        /** 스트리밍은 usage 를 주지 않아 대조할 실제 값이 없다 — 그대로 위임한다. */
        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return delegate.stream(prompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }

        private String promptText(Prompt prompt) {
            StringBuilder sb = new StringBuilder();
            for (Message m : prompt.getInstructions()) {
                if (m.getText() != null) sb.append(m.getText()).append('\n');
            }
            return sb.toString();
        }
    }
}
