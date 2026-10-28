package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each provider's effective context window (tokens), recorded at startup and refreshed by the
 * {@code /settings} re-probe button (§6.26 A5).
 *
 * <p>값의 출처는 둘이다 — 운영자가 {@code app.llm.providers[N].context-size} 로 선언했거나,
 * {@link ContextWindowProbe} 가 서버에게 물어봤거나. 어느 쪽도 못 구하면 <b>그 프로바이더는 항목이
 * 아예 없다</b>({@link #find} 가 빈 값). "모르면 모른다"를 값으로 표현하는 것이 중요하다 — 추측한
 * 숫자로 입력 예산을 짜면 컨텍스트 초과를 막으려다 오히려 멀쩡한 요청을 잘라내게 된다.
 *
 * <p>{@code LlmProvider} 레코드에 컴포넌트로 넣지 않고 이름 키 사이드 맵으로 둔 이유는
 * {@code ProviderToggle}·{@code CircuitBreaker}·{@code LlmRouter} 의 {@code providerCapacity} 와 같다:
 * 그 레코드는 40곳에서 생성되는데, 런타임에 프로바이더를 <b>식별</b>하는 정보가 아니라 그 프로바이더에
 * <b>관해 관측된</b> 값이라 레코드가 나를 이유가 없다.
 *
 * <p>기동 시 채우고, 그 뒤로 값이 바뀌는 자리는 둘이다 — {@code /settings} 의 재탐지 버튼
 * ({@code SettingsService.reprobeContextWindows()}, §6.26 A5)과 컨텍스트 초과 뒤의 자동 재탐지
 * ({@code ProbingContextWindowRefresher} — 초과는 기록된 창이 틀렸다는 서버의 증언이라 근거가 있다). 주기적으로 다시 묻지
 * 않는 이유는 예산이 스스로 움직이면 같은 질문이 시각에 따라 다른 양의 근거를 받기 때문이다. 서버가 다른 컨텍스트로 모델을
 * 다시 로드하면 이 값은 낡으므로, {@code /settings} 가 이 값을 <b>탐지된 값</b>으로 표시하고 어긋나면
 * 운영자가 {@code context-size} 로 못 박거나 재탐지를 누를 수 있게 한다.
 */
@Component
public class ProviderContextWindows {

    private static final Logger log = LoggerFactory.getLogger(ProviderContextWindows.class);

    /**
     * 이 앱이 지원하는 가장 작은 컨텍스트 창 — {@code app.llm.max-tokens} 하한의 두 배(16,000, "16K").
     *
     * <p>두 배인 이유는 {@link #cappedMaxTokens} 와 같은 경계다 — 출력 예약이 창의 절반을 넘으면 입력 자리가 출력보다
     * 작아진다. 하한이 그 경계 안에 드는 가장 작은 창이 이 값이다. 그보다 작은 창에서는 그 클램프가 {@code max-tokens ≥ 창}일
     * 때만 걸리므로 하한 전체가 그대로 예약된다 — 8k~10k 창이면 옵션을 싣지 않는 질의 확장이 넘치고 답변의 입력 예산도 절반
     * 아래로 준다(PITFALLS 의 2026-10-23 범위 변경 항목). 16,384 가 아니라 하한에 묶은 값인 이유는 하한이 바뀌면 이 경계도
     * 함께 움직여야 해서다 — 16K 로 띄운 서버(16,384)는 통과한다.
     */
    public static final int MIN_SUPPORTED_TOKENS = 2 * AppProperties.MIN_MAX_TOKENS;

    /** 값을 어디서 얻었는지 — {@code /settings} 표시와 로그 문구가 이걸로 갈린다. */
    public enum Source {
        /** {@code app.llm.providers[N].context-size} 선언. */
        CONFIGURED,
        /** 서버에 물어본 값 — 기동·재탐지 버튼·컨텍스트 초과 뒤 자동 재탐지. */
        PROBED,
        /**
         * {@code /settings} 의 프로바이더 편집에서 운영자가 넣은 값({@code ProviderCatalog}) — <b>탐지가 안 될 때의 대체값</b>이다.
         * 탐지로 창을 알 수 있으면 넣을 수 없고, 나중에 탐지(기동·재탐지 버튼·자동 재탐지)가 성공하면 탐지값이 덮는다. 탐지가
         * 실패하면 그대로 남는다.
         */
        MANUAL;

        /** 탐지가 덮으면 안 되는 값인가 — {@code context-size} 선언뿐이다. 수동 입력은 탐지 실패의 대체값이라 탐지가 이긴다. */
        public boolean declared() {
            return this == CONFIGURED;
        }
    }

    /** @param tokens 이 프로바이더가 한 번에 다룰 수 있는 총 토큰 수(입력 + 출력). */
    public record ContextWindow(int tokens, Source source) {}

    private final Map<String, ContextWindow> windows = new ConcurrentHashMap<>();

    public void record(String providerName, int tokens, Source source) {
        if (tokens > 0) windows.put(providerName, new ContextWindow(tokens, source));
    }

    /**
     * 기록을 지운다 = "모름". 쓰는 곳은 하나다 — 운영자가 {@code /settings} 에서 넣은 수동 컨텍스트를 비웠는데 properties 선언도
     * 탐지값도 없을 때({@code ProviderCatalog}). 탐지 실패가 기록을 지우지 않는 것과 반대인 이유는, 이건 관측이 아니라 운영자가
     * 자기 선언을 거둔 것이기 때문이다.
     */
    public void forget(String providerName) {
        if (providerName != null) windows.remove(providerName);
    }

    /**
     * 비어 있으면 "이 프로바이더의 컨텍스트 크기를 모른다"는 뜻이다 — 0 이나 기본값이 아니다.
     *
     * <p>{@code null} 이름도 "모름"으로 받는다. {@code LlmRouter.findProviderName()} 은 후보가 없으면
     * {@code "unknown"} 을 주지만, 그 라우터가 목(mock)일 때는 {@code null} 이 온다 — 조회 하나가
     * {@code ConcurrentHashMap} 의 널 금지에 걸려 예산 계산 전체를 터뜨릴 이유는 없다.
     */
    public Optional<ContextWindow> find(String providerName) {
        return providerName == null ? Optional.empty() : Optional.ofNullable(windows.get(providerName));
    }

    /** 편의 접근 — 모르면 {@code 0}. 예산 계산에 쓸 때는 반드시 0 을 "미지"로 다뤄야 한다. */
    public int tokensOrZero(String providerName) {
        return find(providerName).map(ContextWindow::tokens).orElse(0);
    }

    /**
     * 이 창에서 안전한 출력 예약 — {@code max_tokens >= 창} 이면 입력에 남는 자리가 0 이하라
     * <b>모든 요청이 실패</b>하므로 창의 절반으로 낮춘다(최소 256).
     *
     * <p><b>왜 순수 함수로 여기 있는가.</b> 두 곳이 이 값을 알아야 한다: 기동 시 프로바이더 빈에
     * 이 숫자를 굽는 {@code LlmConfig}, 그리고 창을 <b>다시 탐지한 뒤</b> "지금 구워져 있는 값이
     * 새 창에서도 맞는가"를 판정해야 하는 {@code SettingsService} 다. 후자가 이 계산을 복제하면
     * 두 판정이 갈라져 재기동 안내가 틀린다.
     *
     * @param contextTokens 창을 모르면 {@code null} — 그때는 낮출 근거가 없으므로 그대로 둔다
     */
    public static int cappedMaxTokens(int requested, Integer contextTokens) {
        if (contextTokens == null || contextTokens <= 0 || requested < contextTokens) return requested;
        return Math.max(256, contextTokens / 2);
    }

    /** 창을 알고 그 창이 {@link #MIN_SUPPORTED_TOKENS} 보다 작은가 — 모르면({@code null}·0 이하) {@code false}. */
    public static boolean belowSupportedMinimum(Integer contextTokens) {
        return contextTokens != null && contextTokens > 0 && contextTokens < MIN_SUPPORTED_TOKENS;
    }

    /**
     * {@link #belowSupportedMinimum} 이면 WARN 을 남긴다 — <b>막지는 않는다</b>. 창을 알게 되는 세 자리가 같은 문구를 쓴다:
     * 기동({@code LlmConfig}) · {@code /settings} 재탐지 버튼({@code SettingsService.reprobeContextWindows()}) · 컨텍스트
     * 초과 뒤의 자동 재탐지({@code ProbingContextWindowRefresher}). 한 곳에만 있으면 기동 뒤에 서버를 작은 창으로 다시 띄운
     * 배포가 경고 없이 지나간다.
     *
     * <p>막지 않는 이유: 작게 띄운 쪽이 복제본 하나이거나 실험용 모델일 수 있고, 탐지값이 틀렸으면 운영자가
     * {@code context-size} 선언으로 고칠 수 있다(선언이 탐지를 이긴다).
     *
     * <p>순수 판정 + 로그라 static 이다 — {@link #cappedMaxTokens} 를 여기 둔 것과 같은 이유로, 세 자리가 각자 판정하면
     * 경계가 갈라진다.
     *
     * @param when 어느 자리에서 알았는가 — 로그에서 세 자리를 가르는 말("기동"·"재탐지"·"자동 재탐지")
     * @return 경고했는가
     */
    public static boolean warnIfBelowSupportedMinimum(String providerName, Integer contextTokens, Source source,
                                                      String when) {
        if (!belowSupportedMinimum(contextTokens)) return false;
        log.warn("""
                [LLM CONFIG] provider=[{}] context-size({}, {}) < {} — 지원하는 최소 컨텍스트(16K)보다 작습니다({} 때 확인). \
                max-tokens 하한({})이 창의 절반을 넘어 블로킹 호출의 입력 자리가 출력 예약보다 작아지고, \
                8k~10k 창에서는 옵션을 싣지 않는 질의 확장이 넘칩니다.
                  -> 서버의 컨텍스트를 16K 이상으로 띄우세요(llama.cpp -c 16384 · LM Studio 컨텍스트 길이).""",
                providerName, contextTokens, switch (source) {
                    case CONFIGURED -> "선언";
                    case MANUAL -> "수동 입력";
                    case PROBED -> "탐지";
                },
                MIN_SUPPORTED_TOKENS, when, AppProperties.MIN_MAX_TOKENS);
        return true;
    }

    public Map<String, ContextWindow> snapshot() {
        return Map.copyOf(windows);
    }
}
