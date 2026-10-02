package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 생각을 켠 호출의 <b>출력 예약</b> — PLAN §6.29 ④.
 *
 * <p><b>왜 필요한가.</b> 생각 토큰도 {@code max_tokens} 를 먹는다. 출력 상한이 작은 호출(검증 2,048 · 독립화 256 ·
 * 인덱싱의 좁힌 상한)은 생각을 켜는 순간 그 상한을 생각에 다 쓰고 빈 응답으로 끝난다(2026-10-01 실측: 독립화가 매번
 * 그랬다). 그래서 생각이 <b>실제로 켬으로 나가는</b> 호출에만 수준만큼 여유를 더한다.
 *
 * <p><b>"넉넉히"가 아니라 숫자로 정하는 이유</b>: {@code max_tokens} 는 상한이 아니라 예약이다 — OpenAI 호환 서버는
 * {@code 프롬프트 + max_tokens ≤ n_ctx} 를 검사하므로 예약을 늘린 만큼 입력 자리(검색 문서·이력·검증 발췌)가 준다.
 * 그래서 여유는 상한 {@code C = min(프로바이더 max-tokens, 창 × 25%)} 까지 남은 만큼만 준다 — 창 25% 는 여유 10% 와
 * 합쳐 입력 예산이 창의 65% 밑으로 내려가지 않게 하는 바닥이다.
 *
 * <pre>{@code
 *   h  = headroom(수준)              켬으로 나갈 때만. LOW 512 · MEDIUM 1,024 · HIGH 2,048
 *   C  = min(프로바이더 max-tokens, 창 × 25%)   창을 모르면 프로바이더 max-tokens 만
 *   h' = clamp(C − 기본 예약, 0, h)    상한까지 남은 만큼만
 *   예약 = 기본 예약 + h'
 * }</pre>
 *
 * <p><b>기본 예약은 깎지 않는다.</b> 기본 예약이 이미 C 를 넘는 호출(채팅 답변 5,000·7,000)은 h' = 0 이고, 생각은
 * 기존 예약 안에서 답변과 자리를 나눠 쓴다. 앞선 초안의 {@code min(기본 + h, C)} 는 그런 호출의 예약까지 깎았다(16k
 * 창에서 생각을 켜는 순간 답변 예약 5,000 → 4,096). 반대로 끔이 확정된 호출의 예약을 줄이지도 않는다 — 스위치를
 * 무시하는 서버에서는 여전히 생각한다.
 *
 * <p><b>재작성 사이트는 규칙이 다르다</b>({@link ThinkingSite#rewritesInput()}). 출력이 입력의 1.5배라 기본 예약이
 * 거의 언제나 C 를 넘으므로, 여유를 C 로 깎지 않고 <b>조각 크기를 줄여 자리를 만든다</b>
 * ({@link PromptBudget#rewriteInputChars} 의 {@code headroom} — {@link #rewriteHeadroom}). 그 호출의 예약은
 * {@code 기본 + h} 이고 프로바이더 max-tokens 로만 잘린다.
 *
 * <p><b>한 함수를 모두가 지난다.</b> {@link #compute} 를 블로킹 요청({@code ThinkingControlChatModel} — 실제로 받는
 * 프로바이더가 정해진 뒤 요청의 {@code max_tokens} 에 더한다), 입력 예산을 미리 재는 자리(이 빈의
 * {@link #reservation} — 받을 프로바이더를 라우터에게 먼저 묻는다), 그리고 5단계의 {@code /settings} 미리보기가 함께
 * 부른다. 하나라도 옛 상수로 예산을 재면 그 계산이 실제 요청과 갈라지므로, 예산의 출력 예약은 {@link Reservation#tokens()}
 * 로만 넘긴다({@code ThinkingSiteConventionTest}).
 */
@Component
public class ThinkingBudget {

    /** 출력 예약이 창에서 차지할 수 있는 최대 몫(%) — 여유 10% 와 합쳐 입력 예산을 창의 65% 위에 둔다. */
    public static final int CEILING_PERCENT = 25;

    /**
     * 수준별 생각 여유(토큰) — 켬으로 나갈 때만 더한다. 끔은 0. 처음 값은 설계값이고 6단계 실측(관측의 생각 토큰
     * p95)으로 조정한다.
     */
    public static int headroom(ThinkingLevel level) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> 512;
            case MEDIUM -> 1_024;
            case HIGH -> 2_048;
        };
    }

    /**
     * 한 호출의 출력 예약.
     *
     * @param base      그 호출의 기본 예약 — 생각과 무관하게 정해진 값. 0 이하 = 출력 상한을 싣지 않는 호출(프로바이더
     *                  기본값)
     * @param requested 수준이 요구한 생각 여유(켬으로 나가지 않으면 0)
     * @param granted   실제로 더한 여유 — 상한에 걸리면 {@code requested} 보다 작다
     * @param ceiling   여유를 자른 상한(토큰). 0 = 상한을 계산하지 않았다(여유를 요구하지 않았거나 알 수 없다)
     */
    public record Reservation(int base, int requested, int granted, int ceiling) {

        /** 요청에 싣고 예산에서 빼는 값. */
        public int tokens() {
            return base + granted;
        }

        /** 상한에 걸려 요구한 여유를 다 못 받았다 — 숨기지 않는다(로그·미리보기의 "여유 깎임"). */
        public boolean clipped() {
            return granted < requested;
        }

        /**
         * 로그용 — {@code 2,560 (기본 2,048 + 생각 512)}, 깎였으면 {@code 5,000 (기본 5,000 + 생각 0/512, 상한 4,096)}.
         * 여유를 요구하지 않았으면 숫자 하나, 상한을 싣지 않는 호출이면 {@code 프로바이더 기본값}.
         */
        public String describe() {
            if (base <= 0) return "프로바이더 기본값";
            if (requested <= 0) return "%,d".formatted(tokens());
            if (!clipped()) return "%,d (기본 %,d + 생각 %,d)".formatted(tokens(), base, granted);
            return "%,d (기본 %,d + 생각 %,d/%,d, 상한 %,d)".formatted(tokens(), base, granted, requested, ceiling);
        }

        static Reservation unchanged(int base) {
            return new Reservation(base, 0, 0, 0);
        }
    }

    /**
     * 순수 계산 — 모두가 이 함수를 부른다(클래스 주석).
     *
     * @param base              기본 예약. 0 이하면 손대지 않는다 — 상한을 싣지 않는 호출의 예약은 프로바이더 기본값이고
     *                          그건 이미 C 를 넘는다(PLAN §6.29 열린 항목 (d))
     * @param thinkingOn        이 프로바이더에서 생각이 켬으로 나가는가({@link ThinkingWire.Sent#ON}). 끔·보내지 않음이면
     *                          여유가 없다
     * @param rewrite           재작성 사이트 — 창 25% 상한을 두지 않는다(조각 크기가 자리를 만든다)
     * @param providerMaxTokens 그 프로바이더의 지금 유효한 출력 상한({@code MaxTokensCappingChatModel} 이 거는 값). 0 이하
     *                          = 모름
     * @param contextWindow     그 프로바이더의 창. 0 이하 = 모름
     */
    public static Reservation compute(int base, ThinkingLevel level, boolean thinkingOn, boolean rewrite,
                                      int providerMaxTokens, int contextWindow) {
        int requested = thinkingOn ? headroom(level) : 0;
        if (base <= 0 || requested <= 0) return Reservation.unchanged(base);
        long byProvider = providerMaxTokens > 0 ? providerMaxTokens : Long.MAX_VALUE;
        long byWindow = !rewrite && contextWindow > 0 ? (long) contextWindow * CEILING_PERCENT / 100 : Long.MAX_VALUE;
        long ceiling = Math.min(byProvider, byWindow);
        // 둘 다 모르면 상한을 모른다 — 모르는 상한으로 예약을 늘리지 않는다(창을 모르면 자르지 않는 것과 같은 원칙).
        if (ceiling == Long.MAX_VALUE) return new Reservation(base, requested, 0, 0);
        int granted = (int) Math.max(0, Math.min(requested, ceiling - base));
        return new Reservation(base, requested, granted, (int) ceiling);
    }

    /** 생각 제어를 모르는 자리(테스트용 하위호환 생성자)의 예산 — 언제나 기본 예약 그대로다. */
    public static ThinkingBudget none() {
        return new ThinkingBudget(null, null, null, null);
    }

    private final AppProperties props;
    private final ProviderThinkingDialects dialects;
    private final ProviderContextWindows contextWindows;
    private final Function<ThinkingSite, ThinkingLevel> levels;

    @Autowired
    public ThinkingBudget(AppProperties props, ProviderThinkingDialects dialects, ProviderContextWindows contextWindows) {
        // 수준은 값이 아니라 함수로 — 핫 편집 대상이다(ThinkingControlChatModel 과 같은 이유).
        this(props, dialects, contextWindows, site -> props.llmSafe().thinkingLevel(site));
    }

    /** 수준을 함수로 받는다 — 테스트가 설정 계층(정적 오버라이드) 없이 수준을 정할 수 있게. */
    ThinkingBudget(AppProperties props, ProviderThinkingDialects dialects, ProviderContextWindows contextWindows,
                   Function<ThinkingSite, ThinkingLevel> levels) {
        this.props = props;
        this.dialects = dialects;
        this.contextWindows = contextWindows;
        this.levels = levels;
    }

    /**
     * 입력 예산을 미리 재는 자리의 출력 예약 — {@code providerName} 은 그 호출을 받을 것으로 라우터가 답한 프로바이더다.
     * 실제 호출 사이에 답이 달라질 수 있지만 대체되는 것은 보통 다른 역할(원격 — 생각 제어 안 함)이라 "덜 줄였어야 했는데
     * 더 줄였다" 쪽이다({@code AnswerService.budgetFor} 와 같은 근사).
     */
    public Reservation reservation(ThinkingSite site, String providerName, int base) {
        if (props == null) return Reservation.unchanged(base);
        ThinkingLevel level = levels.apply(site);
        int window = contextWindows.tokensOrZero(providerName);
        return compute(base, level, thinkingOn(providerName, level), site.rewritesInput(),
                providerMaxTokens(providerName, window), window);
    }

    /**
     * 재작성 사이트가 조각 크기에서 미리 비워 둘 생각 여유 — 켬으로 나가지 않으면 0. 그 호출의 요청에는
     * {@code ThinkingControlChatModel} 이 같은 {@link #headroom} 을 더한다.
     */
    public int rewriteHeadroom(ThinkingSite site, String providerName) {
        if (props == null) return 0;
        ThinkingLevel level = levels.apply(site);
        return thinkingOn(providerName, level) ? headroom(level) : 0;
    }

    private boolean thinkingOn(String providerName, ThinkingLevel level) {
        return dialects.wireFor(providerName, level).sent() == ThinkingWire.Sent.ON;
    }

    /**
     * 그 프로바이더의 지금 유효한 출력 상한 — {@code LlmConfig.liveMaxTokens} 와 같은 식(자기 {@code max-tokens}, 없으면
     * 전역값, 창을 알면 창의 절반으로 누른다). 모르는 프로바이더면 전역값.
     */
    private int providerMaxTokens(String providerName, int window) {
        int global = props.llmSafe().maxTokens();
        int requested = props.llmSafe().providers() == null ? global : props.llmSafe().providers().stream()
                .filter(p -> p.name() != null && p.name().equals(providerName))
                .findFirst()
                .map(p -> p.requestedMaxTokens(global))
                .orElse(global);
        return ProviderContextWindows.cappedMaxTokens(requested, window > 0 ? window : null);
    }
}
