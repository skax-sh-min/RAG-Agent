package com.example.ragagent.llm;

import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ContextWindowRefresher} 의 실제 구현 — 컨텍스트 초과를 본 프로바이더에게
 * {@link ContextWindowProbe} 를 한 번 더 돌려 {@link ProviderContextWindows} 를 고친다.
 *
 * <p>규칙이 넷 있고 넷 다 이유가 있다.
 *
 * <p><b>1. 선언된 값({@code CONFIGURED})은 덮지 않는다.</b> {@code context-size} 를 설정에 적은
 * 운영자의 의도를 탐지 결과가 조용히 뒤집으면, 설정 파일과 실제 동작이 달라지고 그 사실이 어디에도
 * 안 보인다. 그 경우 선언값이 틀렸을 가능성을 로그로만 말한다 — 고칠 사람은 설정을 쥔 쪽이다.
 *
 * <p><b>2. 탐지에 실패하면 기존 값을 그대로 둔다.</b> 이 앱의 태도는 "모르면 추측하지 않는다"인데,
 * 여기서 값을 지우는 것은 추측보다 나쁘다: 창을 모르면 {@code AnswerService} 는 사전 축소를 아예
 * 하지 않으므로, 초과를 겪은 직후에 축소를 끄는 셈이 된다.
 *
 * <p><b>3. 프로바이더마다 디바운스한다.</b> 초과는 보통 한 번이 아니라 연달아 온다(재시도·동시 대화).
 * 그때마다 탐지하면 이미 아픈 서버에 HTTP 를 더 얹는다. 첫 한 번만 나가고 나머지는 조용히 무시한다.
 *
 * <p><b>4. 호출자를 막지 않는다.</b> 탐지는 가상 스레드에서 돌고, 그 스레드는 요청의 MDC 를 물려받아
 * 로그가 같은 {@code traceId} 로 묶인다({@code MdcPropagation} — 감싸지 않은 실행기 하나의 줄만
 * {@code [-]} 로 남는 그 규약).
 */
public class ProbingContextWindowRefresher implements ContextWindowRefresher {

    private static final Logger log = LoggerFactory.getLogger(ProbingContextWindowRefresher.class);

    /** 같은 프로바이더를 이 간격 안에서는 다시 탐지하지 않는다. */
    static final Duration DEBOUNCE = Duration.ofMinutes(1);

    private final ProviderContextWindows contextWindows;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final Prober prober;
    private final java.util.function.Consumer<Runnable> executor;
    private final java.time.Clock clock;
    private final Map<String, Instant> lastProbeAt = new ConcurrentHashMap<>();

    /** 탐지 한 번 — 테스트가 HTTP 없이 갈아끼우기 위한 이음매({@code ContextWindowProbe.probe} 의 모양). */
    @FunctionalInterface
    interface Prober {
        Optional<Integer> probe(String apiBase, String model, int connectTimeout, int readTimeout);
    }

    public ProbingContextWindowRefresher(ProviderContextWindows contextWindows,
                                         int connectTimeoutSeconds, int readTimeoutSeconds) {
        this(contextWindows, connectTimeoutSeconds, readTimeoutSeconds,
                ContextWindowProbe::probe,
                task -> Thread.ofVirtual().name("ctx-window-reprobe").start(MdcPropagation.wrap(task)),
                java.time.Clock.systemUTC());
    }

    ProbingContextWindowRefresher(ProviderContextWindows contextWindows,
                                  int connectTimeoutSeconds, int readTimeoutSeconds,
                                  Prober prober, java.util.function.Consumer<Runnable> executor,
                                  java.time.Clock clock) {
        this.contextWindows = contextWindows;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.prober = prober;
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public void refreshAfterOverflow(LlmProvider provider) {
        if (provider == null || provider.baseUrl() == null || provider.baseUrl().isBlank()) return;
        // LOCAL 만 대상 — ContextWindowProbe 는 llama.cpp /props 와 LM Studio /api/v0/models 를 묻는다.
        // 클라우드 엔드포인트에는 그런 경로가 없어 404 만 받고 끝나므로, 남의 서버에 의미 없는 요청을
        // 보내지 않는다. SettingsService.reprobeContextWindows() 의 세 번째 규칙과 같다.
        if (provider.role() != ProviderRole.LOCAL) return;

        Optional<ProviderContextWindows.ContextWindow> known = contextWindows.find(provider.name());
        if (known.isPresent() && known.get().source() == ProviderContextWindows.Source.CONFIGURED) {
            log.warn("[CTX-REPROBE] provider={} 컨텍스트 초과가 났지만 창({}토큰)은 설정에 선언된 값이라 "
                     + "탐지로 덮지 않는다 — app.llm.providers[].context-size 가 서버의 실제 값보다 크지 않은지 "
                     + "확인할 것.", provider.name(), known.get().tokens());
            return;
        }
        if (!shouldProbeNow(provider.name())) return;

        executor.accept(() -> probeAndRecord(provider, known.map(ProviderContextWindows.ContextWindow::tokens)));
    }

    /**
     * 디바운스 판정 — 통과할 때만 시각을 찍으므로 동시 호출 중 하나만 나간다.
     *
     * <p>"이번에 내가 찍었는가"를 <b>플래그로</b> 받는다. 돌아온 값이 {@code now} 인지 보는 쪽이
     * 짧지만, 초과가 같은 밀리초에 연달아 오면(재시도·동시 대화 — 이 디바운스가 존재하는 바로 그
     * 상황) 앞 호출이 찍어 둔 값도 {@code now} 와 같아서 전부 "내가 찍었다"로 읽힌다.
     */
    private boolean shouldProbeNow(String providerName) {
        Instant now = clock.instant();
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        lastProbeAt.compute(providerName, (k, last) -> {
            if (last == null || Duration.between(last, now).compareTo(DEBOUNCE) >= 0) {
                claimed.set(true);
                return now;
            }
            return last;
        });
        return claimed.get();
    }

    private void probeAndRecord(LlmProvider provider, Optional<Integer> before) {
        try {
            Optional<Integer> probed = prober.probe(
                    provider.baseUrl(), provider.model(), connectTimeoutSeconds, readTimeoutSeconds);
            if (probed.isEmpty()) {
                log.warn("[CTX-REPROBE] provider={} 재탐지 실패 — 기록된 창({})을 그대로 둔다. "
                         + "창을 지우면 사전 축소가 꺼져 초과가 더 잦아진다.",
                        provider.name(), before.map(String::valueOf).orElse("모름"));
                return;
            }
            int tokens = probed.get();
            if (before.isPresent() && before.get() == tokens) {
                log.warn("[CTX-REPROBE] provider={} 창은 {}토큰 그대로다 — 낡은 값이 원인이 아니다. "
                         + "app.search-top-k / app.chunk-size 쪽을 볼 것.", provider.name(), tokens);
                return;
            }
            contextWindows.record(provider.name(), tokens, ProviderContextWindows.Source.PROBED);
            log.warn("[CTX-REPROBE] provider={} 컨텍스트 창을 {} → {}토큰으로 고쳤다 "
                     + "(서버가 다른 설정으로 재시작된 것으로 보인다). 다음 요청부터 이 값으로 예산을 짠다.",
                    provider.name(), before.map(String::valueOf).orElse("모름"), tokens);
        } catch (Exception e) {
            // 자가 치유는 실패해도 아무것도 나빠지지 않는다 — 원래 요청은 이미 초과로 끝났다.
            log.warn("[CTX-REPROBE] provider={} 재탐지 중 오류: {}", provider.name(), e.toString());
        }
    }
}
