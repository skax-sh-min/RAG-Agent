package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * §6.21 E1 — distributes embedding calls across multiple embedding endpoints (e.g. N GPU
 * replicas of the same model) by least-in-flight, mirroring {@code LlmRouter.selectWithinTopPriority()}
 * on the LLM side. Each delegate is a distinct {@code OpenAiEmbeddingModel} pointed at a different
 * base-url; all MUST serve the same model and dimension (embeddings from different models aren't
 * comparable and would corrupt the vector index). Wired below {@link TrackingEmbeddingModel} /
 * {@link CachingEmbeddingModel} in the decorator chain ({@code EmbeddingBeanConfig}) so usage
 * tracking and the query cache are unaffected — the balancer only decides which endpoint serves
 * a given delegate {@code call()}.
 *
 * <p>Balancing only helps when calls actually overlap: concurrent query embeds (multiple users)
 * and — during indexing — concurrent sub-batch embeds (§6.21 E2, {@code app.embedding.max-concurrent-batches})
 * or concurrent files ({@code app.indexing.max-concurrent-files}). A single serial caller keeps
 * {@code inFlight} at 0 everywhere, so the round-robin tie-break still spreads its calls evenly.
 *
 * <p><b>장애 전환.</b> 엔드포인트에 <b>닿지 못한</b> 실패(연결 거부·연결 타임아웃·DNS·경로 없음 —
 * {@link LlmRouter#isConnectionFailure})는 그 엔드포인트를 {@link #BLOCK} 동안 후보에서 빼고 <b>같은 호출 안에서</b>
 * 다음 엔드포인트로 다시 보낸다. 이것이 없던 동안 같은 로컬 서버 두 대 중 한 대가 죽으면 채팅 모델은 다른 서버로
 * 넘어가도 RAG 턴은 질문 임베딩에서 실패했다(PITFALLS {@code StreamFailover} 의 남은 한계 ③). 판정을 연결 계열로만
 * 좁힌 이유는 임베딩 서버의 5xx 가 흔히 <b>요청 모양</b>의 문제라서다 — llama.cpp 는 배치보다 긴 입력을 500 으로
 * 거절하는데, 그걸 장애로 읽으면 멀쩡한 두 서버를 차례로 막아 임베딩 전체를 30초 세운다. 읽기 타임아웃·인터럽트도
 * 같은 이유로 넘기지 않는다(LLM 쪽 규칙과 같다).
 *
 * <p>차단된 엔드포인트만 남으면 <b>아직 아무 곳도 시도하지 않았을 때만</b> 그중 가장 먼저 풀리는 곳을 한 번 부른다 —
 * 모든 엔드포인트가 죽은 배포가 호출마다 연결 타임아웃을 N 번 무는 대신 예전처럼 한 번만 물고, 서버가 돌아오면
 * 차단이 풀리기를 기다리지 않고 바로 낫는다.
 *
 * <p>Only {@link #call(EmbeddingRequest)} is the balancing point; {@link #embed(Document)} and
 * {@link #dimensions()} mirror {@link TrackingEmbeddingModel}. {@code dimensions()} asks the
 * endpoints in order (all share one model) and skips the unreachable ones the same way.
 */
public class LoadBalancingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingEmbeddingModel.class);

    /** 닿지 못한 엔드포인트를 후보에서 빼 두는 시간 — 대신 받을 곳이 있으므로 LLM 라우터의 폴백 있는 차단과 같은 30초. */
    static final Duration BLOCK = Duration.ofSeconds(30);

    private final List<EmbeddingModel> delegates;
    private final List<String> labels;
    private final AtomicInteger[] inFlight;
    /** 엔드포인트별 차단 만료 시각({@link #clock} 의 나노초). 0 = 차단 없음. */
    private final AtomicLong[] blockedUntil;
    private final AtomicInteger roundRobin = new AtomicInteger();
    private final LongSupplier clock;

    public LoadBalancingEmbeddingModel(List<EmbeddingModel> delegates) {
        this(delegates, null);
    }

    /** @param labels 로그에 찍을 엔드포인트 이름(보통 base-url) — {@code null} 이면 {@code endpoint#i} */
    public LoadBalancingEmbeddingModel(List<EmbeddingModel> delegates, List<String> labels) {
        this(delegates, labels, System::nanoTime);
    }

    LoadBalancingEmbeddingModel(List<EmbeddingModel> delegates, List<String> labels, LongSupplier clock) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("LoadBalancingEmbeddingModel needs at least one delegate");
        }
        this.delegates = List.copyOf(delegates);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < this.delegates.size(); i++) {
            names.add(labels != null && i < labels.size() && labels.get(i) != null ? labels.get(i) : "endpoint#" + i);
        }
        this.labels = List.copyOf(names);
        this.inFlight = new AtomicInteger[this.delegates.size()];
        this.blockedUntil = new AtomicLong[this.delegates.size()];
        for (int i = 0; i < inFlight.length; i++) {
            inFlight[i] = new AtomicInteger();
            blockedUntil[i] = new AtomicLong();
        }
        this.clock = clock;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return withFailover("embed", m -> m.call(request), true);
    }

    @Override
    public int dimensions() {
        // 순서대로 묻는다 — 모두 같은 모델이라 어느 쪽 답이든 같다. 균형은 의미가 없고 결정적인 편이 낫다.
        return withFailover("dimensions", EmbeddingModel::dimensions, false);
    }

    /**
     * 엔드포인트를 골라 부르고, 닿지 못하면 차단하고 다음 엔드포인트로. 넘길 곳이 없으면 첫 실패를 그대로 던진다.
     *
     * @param balanced {@code true} 면 least-in-flight 로 고르고, {@code false} 면 등록 순서대로
     */
    private <T> T withFailover(String what, Function<EmbeddingModel, T> op, boolean balanced) {
        int n = delegates.size();
        if (n == 1) return op.apply(delegates.get(0)); // 넘길 곳이 없다 — 예전 동작 그대로
        Set<Integer> tried = new HashSet<>();
        RuntimeException firstFailure = null;
        int next = pick(tried, balanced); // 아직 아무것도 시도하지 않았으므로 언제나 하나는 나온다
        while (true) {
            final int idx = next;
            tried.add(idx);
            inFlight[idx].incrementAndGet();
            try {
                T result = op.apply(delegates.get(idx));
                blockedUntil[idx].set(0); // 차단이 풀리기 전에 불려 성공했다면(전부 차단) 바로 되살린다
                return result;
            } catch (RuntimeException e) {
                if (!isUnreachable(e)) throw e;
                blockedUntil[idx].set(clock.getAsLong() + BLOCK.toNanos());
                if (firstFailure == null) firstFailure = e;
                next = pick(tried, balanced); // 남은 곳이 전부 차단이면 -1 — 이번 호출에서는 기다려도 소용없다
                log.warn("[EMBED-FAILOVER] {} endpoint {} unreachable ({}: {}) — blocked {}s, {}",
                        what, labels.get(idx), e.getClass().getSimpleName(), e.getMessage(), BLOCK.toSeconds(),
                        next >= 0 ? "trying " + labels.get(next) : "no endpoint left");
                if (next < 0) throw firstFailure;
            } finally {
                inFlight[idx].decrementAndGet();
            }
        }
    }

    /** 서버에 닿지 못했다 — 이것만 다른 엔드포인트로 넘긴다(클래스 주석). 사용자 중단은 실패가 아니다. */
    private static boolean isUnreachable(Throwable e) {
        return !Thread.currentThread().isInterrupted() && LlmRouter.isConnectionFailure(e);
    }

    /**
     * 아직 시도하지 않았고 차단되지 않은 엔드포인트 중 하나. 없으면 — 이번 호출에서 아직 아무 데도 안 불렀을 때만 —
     * 가장 먼저 풀리는 차단된 엔드포인트. 그것도 아니면 {@code -1}.
     */
    private int pick(Set<Integer> tried, boolean balanced) {
        long now = clock.getAsLong();
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < delegates.size(); i++) {
            if (!tried.contains(i) && !isBlocked(i, now)) open.add(i);
        }
        if (!open.isEmpty()) {
            return balanced ? pickLeastInFlight(open) : open.get(0);
        }
        if (!tried.isEmpty()) return -1;
        int soonest = 0;
        for (int i = 1; i < delegates.size(); i++) {
            if (blockedUntil[i].get() - blockedUntil[soonest].get() < 0) soonest = i;
        }
        return soonest;
    }

    private boolean isBlocked(int idx, long now) {
        long until = blockedUntil[idx].get();
        return until != 0 && until - now > 0;
    }

    /**
     * Least in-flight, with a rotating start so equal-load endpoints (notably all-idle, the serial
     * case) share evenly instead of always hitting endpoint 0. The min-load read and the pick race
     * benignly under concurrency — an approximate least-connections decision is all a balancer needs.
     */
    private int pickLeastInFlight(List<Integer> candidates) {
        int n = candidates.size();
        if (n == 1) return candidates.get(0);
        int minLoad = Integer.MAX_VALUE;
        for (int i : candidates) {
            int v = inFlight[i].get();
            if (v < minLoad) minLoad = v;
        }
        int start = Math.floorMod(roundRobin.getAndIncrement(), n);
        for (int k = 0; k < n; k++) {
            int i = candidates.get((start + k) % n);
            if (inFlight[i].get() <= minLoad) return i;
        }
        return candidates.get(start); // loads shifted concurrently — any endpoint is acceptable
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(getEmbeddingContent(document));
    }
}
