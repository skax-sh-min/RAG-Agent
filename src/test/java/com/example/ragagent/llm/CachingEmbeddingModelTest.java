package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — CachingEmbeddingModel query-embedding cache decorator (§10.3) + in-flight
 * single-flight dedup (§6.12).
 *
 * Covers: repeated text hits the cache and skips the delegate entirely (so a
 * TrackingEmbeddingModel delegate records no usage on a hit), a partial hit only
 * forwards the missing texts to the delegate (in the right order/mapping), embed(Document)
 * benefits from the cache the same as embed(String), dimensions() bypasses the cache, and
 * concurrent calls for the exact same (not-yet-cached) text collapse into a single delegate
 * call instead of each recomputing independently.
 */
class CachingEmbeddingModelTest {

    private final EmbeddingModel delegate = mock(EmbeddingModel.class);

    private static EmbeddingResponse responseFor(float[]... vectors) {
        List<Embedding> embeddings = new java.util.ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            embeddings.add(new Embedding(vectors[i], i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Test
    @DisplayName("동일 텍스트 재조회 → 두 번째 호출은 delegate 미경유, 동일 벡터 반환")
    void repeatedTextHitsCacheAndSkipsDelegate() {
        when(delegate.call(any())).thenReturn(responseFor(new float[]{1f, 2f}));
        var model = new CachingEmbeddingModel(delegate, "nomic", 500, 600);

        EmbeddingResponse first = model.call(new EmbeddingRequest(List.of("hello"), null));
        EmbeddingResponse second = model.call(new EmbeddingRequest(List.of("hello"), null));

        assertThat(first.getResults().get(0).getOutput()).isEqualTo(new float[]{1f, 2f});
        assertThat(second.getResults().get(0).getOutput()).isEqualTo(new float[]{1f, 2f});
        verify(delegate, org.mockito.Mockito.times(1)).call(any());
    }

    @Test
    @DisplayName("캐시 히트 시 usage 미기록 — TrackingEmbeddingModel을 delegate로 감싸도 재조회는 기록 0회")
    void cacheHitRecordsNoUsage() {
        LlmUsageRepository usageRepo = mock(LlmUsageRepository.class);
        EmbeddingModel raw = mock(EmbeddingModel.class);
        when(raw.call(any())).thenReturn(
                new EmbeddingResponse(List.of(new Embedding(new float[]{1f}, 0)),
                        new EmbeddingResponseMetadata("nomic", new DefaultUsage(10, 0))));
        var tracked = new TrackingEmbeddingModel(raw, usageRepo, "nomic", true);
        var cached = new CachingEmbeddingModel(tracked, "nomic", 500, 600);

        cached.call(new EmbeddingRequest(List.of("hello"), null));
        cached.call(new EmbeddingRequest(List.of("hello"), null));

        verify(usageRepo, org.mockito.Mockito.times(1)).record("embed:nomic", 10, 0);
        verify(raw, org.mockito.Mockito.times(1)).call(any());
    }

    @Test
    @DisplayName("부분 히트 — 캐시에 없는 텍스트만 delegate에 전달, 결과는 원래 순서로 병합")
    void partialHitOnlyForwardsMisses() {
        when(delegate.call(any())).thenReturn(responseFor(new float[]{1f}));
        var model = new CachingEmbeddingModel(delegate, "nomic", 500, 600);
        model.call(new EmbeddingRequest(List.of("a"), null)); // warms cache for "a"

        when(delegate.call(any())).thenReturn(responseFor(new float[]{2f}, new float[]{3f}));
        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("a", "b", "c"), null));

        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults().get(0).getOutput()).isEqualTo(new float[]{1f});
        assertThat(response.getResults().get(1).getOutput()).isEqualTo(new float[]{2f});
        assertThat(response.getResults().get(2).getOutput()).isEqualTo(new float[]{3f});

        var captor = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(delegate, org.mockito.Mockito.times(2)).call(captor.capture());
        assertThat(captor.getAllValues().get(1).getInstructions()).containsExactly("b", "c");
    }

    @Test
    @DisplayName("embed(Document) 도 캐시 적용 — 동일 본문 재조회 시 delegate 미경유")
    void embedDocumentBenefitsFromCache() {
        when(delegate.call(any())).thenReturn(responseFor(new float[]{9f}));
        var model = new CachingEmbeddingModel(delegate, "nomic", 500, 600);

        model.embed(new Document("some content"));
        model.embed(new Document("some content"));

        verify(delegate, org.mockito.Mockito.times(1)).call(any());
        verify(delegate, never()).embed(any(Document.class));
    }

    @Test
    @DisplayName("dimensions() 는 delegate 로 직접 위임, 캐시 미개입")
    void dimensionsDelegatesDirectly() {
        when(delegate.dimensions()).thenReturn(768);
        var model = new CachingEmbeddingModel(delegate, "nomic", 500, 600);

        assertThat(model.dimensions()).isEqualTo(768);
        verify(delegate, never()).call(any());
    }

    // ── §6.12 — in-flight single-flight ───────────────────────────────────────

    @Test
    @DisplayName("§6.12 — 동시에 도착한 동일 텍스트 요청은 delegate를 한 번만 호출하고 결과를 공유한다")
    void concurrentIdenticalTextSingleFlights() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        EmbeddingModel racyDelegate = mock(EmbeddingModel.class);
        when(racyDelegate.call(any())).thenAnswer(inv -> {
            callCount.incrementAndGet();
            delegateEntered.countDown();
            assertThat(releaseDelegate.await(5, TimeUnit.SECONDS)).isTrue();
            return responseFor(new float[]{7f});
        });
        var model = new CachingEmbeddingModel(racyDelegate, "nomic", 500, 600);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<EmbeddingResponse> f1 = exec.submit(() ->
                    model.call(new EmbeddingRequest(List.of("same question"), null)));
            assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<EmbeddingResponse> f2 = exec.submit(() ->
                    model.call(new EmbeddingRequest(List.of("same question"), null)));
            // Give f2's thread a moment to reach the in-flight check and register as a joiner
            // before releasing f1 — see the failure-propagation test below for why this matters.
            Thread.sleep(200);
            releaseDelegate.countDown();

            assertThat(f1.get(5, TimeUnit.SECONDS).getResults().get(0).getOutput()).isEqualTo(new float[]{7f});
            assertThat(f2.get(5, TimeUnit.SECONDS).getResults().get(0).getOutput()).isEqualTo(new float[]{7f});
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("§6.12 — owner 호출 실패 시 join 중이던 호출에도 예외가 전파되고, in-flight 항목은 정리되어 다음 호출은 재시도한다")
    void concurrentFailurePropagatesToJoinerAndClearsInFlight() throws Exception {
        CountDownLatch delegateEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        AtomicInteger callCount = new AtomicInteger();
        EmbeddingModel racyDelegate = mock(EmbeddingModel.class);
        when(racyDelegate.call(any())).thenAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                delegateEntered.countDown();
                assertThat(releaseDelegate.await(5, TimeUnit.SECONDS)).isTrue();
                throw new RuntimeException("boom");
            }
            return responseFor(new float[]{9f});
        });
        var model = new CachingEmbeddingModel(racyDelegate, "nomic", 500, 600);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<EmbeddingResponse> f1 = exec.submit(() ->
                    model.call(new EmbeddingRequest(List.of("boom text"), null)));
            assertThat(delegateEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<EmbeddingResponse> f2 = exec.submit(() ->
                    model.call(new EmbeddingRequest(List.of("boom text"), null)));
            // Give f2's thread a moment to reach the in-flight check and register as a joiner
            // before f1 is released — otherwise f1 may complete/fail and clear the in-flight
            // entry before f2 gets there, making f2 race to become a (second) owner instead.
            Thread.sleep(200);
            releaseDelegate.countDown();

            assertThatThrownBy(() -> f1.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("boom");
            assertThatThrownBy(() -> f2.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("boom");

            EmbeddingResponse retry = model.call(new EmbeddingRequest(List.of("boom text"), null));
            assertThat(retry.getResults().get(0).getOutput()).isEqualTo(new float[]{9f});
            assertThat(callCount.get()).isEqualTo(2);
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @DisplayName("§6.12 — 같은 요청 안에 동일 텍스트가 중복돼도 데드락 없이 delegate에는 한 번만 전달된다")
    void duplicateTextWithinSameRequestDoesNotDeadlock() {
        when(delegate.call(any())).thenReturn(responseFor(new float[]{5f}));
        var model = new CachingEmbeddingModel(delegate, "nomic", 500, 600);

        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("dup", "dup"), null));

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).getOutput()).isEqualTo(new float[]{5f});
        assertThat(response.getResults().get(1).getOutput()).isEqualTo(new float[]{5f});
        var captor = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(delegate, org.mockito.Mockito.times(1)).call(captor.capture());
        assertThat(captor.getValue().getInstructions()).containsExactly("dup");
    }
}
