package com.example.ragagent.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each provider's effective context window (tokens), recorded once at startup.
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
 * <p>기동 시 한 번 채우고 그 뒤로는 읽기만 한다 — 서버가 다른 컨텍스트로 모델을 다시 로드하면 이
 * 값은 낡는다. 그래서 {@code /settings} 가 이 값을 <b>탐지된 값</b>으로 표시하고, 어긋나면 운영자가
 * {@code context-size} 로 못 박을 수 있게 한다.
 */
@Component
public class ProviderContextWindows {

    /** 값을 어디서 얻었는지 — {@code /settings} 표시와 로그 문구가 이걸로 갈린다. */
    public enum Source { CONFIGURED, PROBED }

    /** @param tokens 이 프로바이더가 한 번에 다룰 수 있는 총 토큰 수(입력 + 출력). */
    public record ContextWindow(int tokens, Source source) {}

    private final Map<String, ContextWindow> windows = new ConcurrentHashMap<>();

    public void record(String providerName, int tokens, Source source) {
        if (tokens > 0) windows.put(providerName, new ContextWindow(tokens, source));
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

    public Map<String, ContextWindow> snapshot() {
        return Map.copyOf(windows);
    }
}
