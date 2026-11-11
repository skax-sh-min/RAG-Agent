package com.example.ragagent.llm;

import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 임베딩·리랭커 서버 한 대에 대해 <b>요청 본문에 실을 모델명</b>과 <b>그 서버의 모델 상태</b>를 정한다 — 설정한 이름
 * ({@code EMBED_MODEL} · {@code RERANK_MODEL})이 서버 {@code /v1/models} 목록의 id 에 (대소문자 무시) 부분 문자열로 들어
 * 있으면 그 서버 id 를 쓴다. <b>일치는 완전 일치가 아니라 부분 일치</b>다: 정확히 같은 id 가 있으면 그것이 우선이고, 아니면 설정값을
 * 포함하는 id 가 하나든 여럿이든 일치로 본다 — 여럿이면 설정값에 가장 가까운(가장 짧은) id, 같은 길이면 목록의 앞쪽을 고른다.
 * (LLM 의 G3 는 모호하면 기동을 막지만, 여기서는 "모델없음" 으로 요청을 막는 것이 더 큰 사고라 고른다.)
 *
 * <p><b>요청 경로를 절대 막지 않는다.</b> 목록 조회는 <b>백그라운드</b>(가상 스레드, 한 번에 하나)에서 하고, 요청은 그 시점에
 * 알려진 상태를 읽을 뿐이다. 만들어지는 즉시 한 번 시작하고(기동을 늦추지 않는다), 이후는 요청이 들어왔을 때 간격이 지났으면
 * 다시 시작한다. 아직 모르는 동안은 설정한 이름 그대로 쓴다.
 *
 * <p>상태는 넷이다:
 * <ul>
 *   <li>{@link State#UNKNOWN} — 아직 조회가 끝나지 않았다. 정상과 같이 취급한다(보내 본다).</li>
 *   <li>{@link State#OK} — 모델을 찾았다. 그 이름을 계속 쓴다(서버가 다른 모델로 바뀌면 재기동).</li>
 *   <li>{@link State#UNREACHABLE} — 목록을 못 읽었다(닿지 않음·오류). {@link #RETRY_UNREACHABLE} 뒤 다시 본다.</li>
 *   <li>{@link State#MODEL_MISSING} — 서버는 답하는데 설정한 이름을 포함하는 id 가 하나도 없다. 호출자는 이 서버로 요청을 보내지
 *       않는다. 운영자가 서버에 모델을 올리는 일은 드물고 조회가 서버를 두드리는 일이므로 간격을 훨씬 길게 둔다
 *       ({@link #RETRY_MODEL_MISSING}).</li>
 * </ul>
 */
public class ServerModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ServerModelResolver.class);

    public enum State { UNKNOWN, OK, UNREACHABLE, MODEL_MISSING }

    /** 목록을 못 읽은 뒤 다시 보기까지 — 서버가 곧 돌아올 수 있다. */
    static final long RETRY_UNREACHABLE = TimeUnit.SECONDS.toNanos(30);
    /** 모델이 없다고 나온 뒤 다시 보기까지 — 모델을 올리는 일은 드물다. */
    static final long RETRY_MODEL_MISSING = TimeUnit.MINUTES.toNanos(5);

    private final String label;
    private final String configured;
    private final Supplier<List<String>> lister;
    private final LongSupplier clock;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile State state = State.UNKNOWN;
    private volatile String resolved;
    private volatile String detail = "";
    private volatile boolean attempted;
    private volatile long lastAttempt;

    /** @param label 로그에 찍을 이름(보통 주소) — 만들어지는 즉시 백그라운드 조회를 시작한다 */
    public ServerModelResolver(String label, String configured, Supplier<List<String>> lister) {
        this(label, configured, lister, System::nanoTime,
                task -> Thread.ofVirtual().name("model-resolve").start(MdcPropagation.wrap(task)));
        refreshAsync();
    }

    /** 테스트용 — 시계와 실행기를 바꿔 끼운다(동기 실행기면 조회가 그 자리에서 끝난다). 자동 시작은 하지 않는다. */
    ServerModelResolver(String label, String configured, Supplier<List<String>> lister, LongSupplier clock,
                        Executor executor) {
        this.label = label;
        this.configured = configured;
        this.lister = lister;
        this.clock = clock;
        this.executor = executor;
    }

    /** 테스트용(같은 패키지가 아닌 곳에서도) — 조회를 호출 스레드에서 끝내는 해석기. 자동 시작은 하지 않는다. */
    public static ServerModelResolver forTest(String label, String configured, Supplier<List<String>> lister) {
        return new ServerModelResolver(label, configured, lister, System::nanoTime, Runnable::run);
    }

    /** 이 서버의 모델 상태. 조회할 때가 됐으면 백그라운드로 시작만 하고 기다리지 않는다. */
    public State state() {
        refreshAsync();
        return state;
    }

    /** 지금 요청에 실을 모델명 — 풀린 서버 id, 아직이면 설정한 이름. 기다리지 않는다. */
    public String modelName() {
        refreshAsync();
        String r = resolved;
        return r != null ? r : configured;
    }

    /** {@link State#MODEL_MISSING}·{@link State#UNREACHABLE} 의 사유 한 줄 — 오류 문구에 싣는다. */
    public String detail() {
        return detail;
    }

    public String configuredModel() {
        return configured;
    }

    public String label() {
        return label;
    }

    /** 조회가 필요하고 때가 됐으면 백그라운드로 시작한다(이미 돌고 있으면 아무것도 하지 않는다). */
    public void refreshAsync() {
        if (configured == null || configured.isBlank()) return;
        State s = state;
        if (s == State.OK) return;
        if (attempted) {
            long interval = s == State.MODEL_MISSING ? RETRY_MODEL_MISSING : RETRY_UNREACHABLE;
            if (clock.getAsLong() - lastAttempt < interval) return;
        }
        if (!running.compareAndSet(false, true)) return;
        attempted = true;
        lastAttempt = clock.getAsLong();
        try {
            executor.execute(() -> {
                try {
                    refreshNow();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
        }
    }

    /** 여러 부분 일치 중 설정값에 가장 가까운(가장 짧은) id — 같은 길이면 목록의 앞쪽. 없으면 {@code null}. */
    private static String closest(List<String> candidates) {
        String best = null;
        for (String c : candidates) if (best == null || c.length() < best.length()) best = c;
        return best;
    }

    private void refreshNow() {
        try {
            ModelNameResolver.Resolution res = ModelNameResolver.resolve(configured, lister.get());
            String pick = res.found() ? res.resolved() : closest(res.candidates());
            if (pick != null) {
                resolved = pick;
                if (!res.found()) {
                    log.info("[MODEL] '{}' matches several models {} at {} \u2014 using the closest, '{}'", configured, res.candidates(), label, pick);
                }
                detail = "";
                state = State.OK;
                if (!pick.equals(configured) && res.found()) {
                    log.info("[MODEL] '{}' resolved to the server's model id '{}' at {}", configured, resolved, label);
                }
            } else {
                detail = "'" + configured + "' is not part of any model id in the server's list";
                if (state != State.MODEL_MISSING) {
                    log.warn("[MODEL] {} at {} \u2014 requests to this server are not sent until it is found (rechecked every {} min)",
                            detail, label, TimeUnit.NANOSECONDS.toMinutes(RETRY_MODEL_MISSING));
                }
                state = State.MODEL_MISSING;
            }
        } catch (RuntimeException e) {
            detail = "could not read the model list: " + e.getMessage();
            log.debug("[MODEL] could not read the model list of {}: {}", label, e.toString());
            state = State.UNREACHABLE;
        }
    }
}
