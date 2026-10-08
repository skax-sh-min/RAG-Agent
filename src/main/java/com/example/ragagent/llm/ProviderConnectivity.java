package com.example.ragagent.llm;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.web.MdcPropagation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 프로바이더마다 "지금 실제로 닿는가" — {@code /settings} 프로바이더 표의 상태 열(미설정 · 접속불가 · 정상)이 읽는 마지막 확인.
 *
 * <p><b>설정이 있다는 것과 서버가 떠 있다는 것은 다르다.</b> 예전 상태 열은 설정(주소·키가 있는가)과 서킷 브레이커(최근에
 * 실패했는가)만 봤다 — 서버가 꺼진 채로 아무도 질문하지 않았으면 브레이커가 한 번도 실패를 겪지 않아 "정상" 이었다.
 * 그래서 화면을 열 때 서버에 직접 묻는다. 묻는 방법은 모델 목록 API({@code GET {base}/v1/models})이고, "닿는다"의 정의는
 * {@link LlmPing#reach} 와 같은 함수를 부르므로 핑과 설정 화면이 서로 다른 말을 하지 않는다.
 *
 * <p><b>라우터를 거치지 않는다</b>({@link LlmPing} 과 같은 이유) — 서킷 브레이커·동시성 게이트·사용량 집계를 타지 않는 날것의
 * HTTP 라 차단 중에도 묻고, 결과가 브레이커를 바꾸지 않는다. 진단이 상태를 만지면 "화면을 열었더니 풀렸다/막혔다" 가 된다.
 *
 * <p><b>화면을 열 때마다 서버를 두드리지 않는다.</b> 게스트에게 열린 페이지에서 불리는 확인이라, {@link #REUSE_WINDOW} 안에
 * 끝난 결과는 다시 묻지 않고 동시에 열린 화면들은 {@link #refresh} 의 잠금 하나로 한 번의 확인을 나눠 쓴다.
 * 프로바이더 여러 개는 병렬로 묻는다 — 죽은 서버의 연결 타임아웃이 산 서버의 칸을 늦추지 않게.
 *
 * <p>메모리에만 둔다(재시작하면 비고 첫 화면이 다시 묻는다). 결과에는 호스트 이름이 섞인 오류 문구가 실릴 수 있는데, 프로바이더
 * 표가 이미 접속 주소를 같은 사람에게 보여 주므로 새로 드러나는 것은 없다.
 */
@Component
public class ProviderConnectivity {

    /**
     * 한 프로바이더의 마지막 확인.
     *
     * @param modelListed 설정한 모델이 서버의 목록에 있는가 — {@code null} = 목록을 읽지 못했다. 상태 열의 3단계를 바꾸지 않고
     *                    "정상" 칸의 설명에만 쓴다(목록 API 가 없는 게이트웨이에서 오탐하지 않으려고)
     */
    public record Result(boolean reachable, Long latencyMs, Boolean modelListed, String error, Instant checkedAt) {}

    /** 화면을 연 사람이 기다리는 확인이라 핑과 같게 짧다. */
    static final int CONNECT_TIMEOUT_SECONDS = 3;
    static final int READ_TIMEOUT_SECONDS = 5;

    /** 이 안에 끝난 확인은 다시 하지 않는다. */
    static final Duration REUSE_WINDOW = Duration.ofSeconds(3);

    private final Map<String, Result> results = new ConcurrentHashMap<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Clock clock;
    private final Function<AppProperties.ProviderConfig, LlmPing.Reach> prober;

    public ProviderConnectivity() {
        this(Clock.systemUTC(), ProviderConnectivity::probe);
    }

    /** 테스트용 — 시계와 실제로 묻는 함수를 바꿔 끼운다. */
    ProviderConnectivity(Clock clock, Function<AppProperties.ProviderConfig, LlmPing.Reach> prober) {
        this.clock = clock;
        this.prober = prober;
    }

    /**
     * 마지막 확인. 한 번도 안 물었으면 비어 있다. <b>나이를 따지지 않는다</b> — 토글·재탐지 뒤에 돌아오는 표 조각이 지난 확인을
     * 그대로 보여 주어야 클릭 한 번에 상태 칸이 "확인 중" 으로 되돌아가지 않는다.
     */
    public Optional<Result> last(String providerName) {
        return providerName == null ? Optional.empty() : Optional.ofNullable(results.get(providerName));
    }

    /**
     * 대상을 병렬로 확인해 결과를 기록한다. {@link #REUSE_WINDOW} 안에 끝난 결과가 있는 프로바이더는 건너뛴다.
     * 동시에 불린 호출은 잠금에서 기다렸다가 앞 호출이 쓴 결과를 그대로 쓴다(= 한 번의 확인을 나눠 쓴다).
     *
     * @param targets 등록된(설정이 갖춰진) 프로바이더만 — 미설정 프로바이더는 묻지 않는다
     */
    public void refresh(List<AppProperties.ProviderConfig> targets) {
        if (targets == null || targets.isEmpty()) return;
        refreshLock.lock();
        try {
            Instant now = clock.instant();
            List<AppProperties.ProviderConfig> due = targets.stream()
                    .filter(t -> !isFresh(t.name(), now))
                    .toList();
            if (due.isEmpty()) return;
            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                var traced = MdcPropagation.propagating(exec);
                CompletableFuture<?>[] futures = due.stream()
                        .map(cfg -> CompletableFuture.runAsync(() -> results.put(cfg.name(), check(cfg)), traced))
                        .toArray(CompletableFuture<?>[]::new);
                CompletableFuture.allOf(futures).join();
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isFresh(String name, Instant now) {
        Result last = results.get(name);
        return last != null && Duration.between(last.checkedAt(), now).compareTo(REUSE_WINDOW) < 0;
    }

    /** 어떤 실패도 던지지 않는다 — 실패 자체가 답이다. */
    private Result check(AppProperties.ProviderConfig cfg) {
        try {
            LlmPing.Reach reach = prober.apply(cfg);
            return new Result(reach.reachable(), reach.latencyMs(), reach.modelListed(), reach.error(), clock.instant());
        } catch (RuntimeException e) {
            return new Result(false, null, null, LlmPing.describe(e), clock.instant());
        }
    }

    /**
     * 앱이 실제로 요청을 보내는 주소와 키로 묻는다. {@code OpenAiApi} 는 base-url 에서 {@code /v1} 을 걷어낸 루트에 다시 {@code /v1}
     * 을 붙여 요청하므로({@code ProviderConfig#apiBase}) 여기서도 같은 규칙으로 만든다 — 설정에 {@code /v1} 이 없는 주소가 상태
     * 열에서만 404 로 "접속불가" 가 되는 일이 없게. 키는 {@code LlmConfig} 가 프로바이더를 만들 때 쓰는 값과 같다(LOCAL 은 비어 있으면
     * 자리표시자).
     */
    private static LlmPing.Reach probe(AppProperties.ProviderConfig cfg) {
        String apiKey = (cfg.apiKey() != null && !cfg.apiKey().isBlank()) ? cfg.apiKey() : "no-key";
        String modelsBase = cfg.apiBase().replaceAll("/+$", "") + "/v1";
        return LlmPing.reach(modelsBase, apiKey, cfg.model(), CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS);
    }
}
