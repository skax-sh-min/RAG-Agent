package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 임베딩·리랭커 서버 한 대에 대해 <b>요청 본문에 실을 모델명</b>을 정한다 — 설정한 이름({@code EMBED_MODEL} ·
 * {@code RERANK_MODEL})이 서버 {@code /v1/models} 목록의 id 에 (대소문자 무시) 부분 문자열로 들어 있으면 그 서버 id 를 쓴다
 * ({@link ModelNameResolver} — LLM 의 G3 와 같은 규칙: 정확 일치 우선, 아니면 포함하는 id 가 하나뿐일 때).
 *
 * <p><b>느리게 푼다</b> — 첫 호출 때 한 번 목록을 묻는다. 기동 때 묻지 않는 이유는 임베딩·리랭커 서버가 앱보다 늦게 떠도
 * 되는 배포가 많아서이고(기동 점검은 경고만 한다), 늦게 뜬 서버도 다음 호출에서 풀리게 하려는 것이다. 풀지 못하면(서버가
 * 안 닿음 · 목록 없음 · 모호함 · 없음) <b>설정한 이름 그대로</b> 쓰고 {@link #RETRY} 뒤에 다시 시도한다 — 그 사이 호출마다
 * 목록 요청을 보태지 않는다. 한 번 풀린 이름은 그대로 쓴다(서버가 다른 모델로 바뀌면 재기동).
 *
 * <p>목록을 묻는 함수와 시계는 주입된다 — 테스트가 HTTP 없이 규칙을 본다.
 */
public class ServerModelResolver {

    private static final Logger log = LoggerFactory.getLogger(ServerModelResolver.class);

    /** 풀지 못한 뒤 다시 묻기까지의 간격. */
    static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final String label;
    private final String configured;
    private final Supplier<List<String>> lister;
    private final LongSupplier clock;

    private volatile String resolved;
    private volatile boolean attempted;
    private volatile long lastAttempt;

    /** @param label 로그에 찍을 이름(보통 주소) */
    public ServerModelResolver(String label, String configured, Supplier<List<String>> lister) {
        this(label, configured, lister, System::nanoTime);
    }

    ServerModelResolver(String label, String configured, Supplier<List<String>> lister, LongSupplier clock) {
        this.label = label;
        this.configured = configured;
        this.lister = lister;
        this.clock = clock;
    }

    /** 지금 요청에 실을 모델명 — 풀린 서버 id, 아니면 설정한 이름. 어떤 실패도 던지지 않는다. */
    public String modelName() {
        String r = resolved;
        if (r != null) return r;
        if (configured == null || configured.isBlank()) return configured;
        synchronized (this) {
            if (resolved != null) return resolved;
            long now = clock.getAsLong();
            if (attempted && now - lastAttempt < RETRY_NANOS) return configured;
            attempted = true;
            lastAttempt = now;
            try {
                ModelNameResolver.Resolution res = ModelNameResolver.resolve(configured, lister.get());
                if (res.found()) {
                    resolved = res.resolved();
                    if (res.renamed()) {
                        log.info("[MODEL] '{}' resolved to the server's model id '{}' at {}", configured, resolved, label);
                    }
                    return resolved;
                }
                log.warn("[MODEL] '{}' {} in the model list of {} \u2014 sending the configured name as is",
                        configured, res.ambiguous() ? "matches several models " + res.candidates() : "is not", label);
            } catch (RuntimeException e) {
                log.debug("[MODEL] could not read the model list of {}: {}", label, e.toString());
            }
            return configured;
        }
    }
}
