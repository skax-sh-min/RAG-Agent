package com.example.ragagent.llm;

import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA — TrackingEmbeddingModel usage-recording decorator (§6.6)
 *
 * Covers: real usage recorded under "embed:<model>", chars/4 fallback when the delegate
 * doesn't report usage (EmptyUsage → promptTokens=0, not null), fallback disabled records 0,
 * embed(Document) still routes through call() rather than delegate.embed(Document), and
 * dimensions() bypasses tracking entirely.
 */
class TrackingEmbeddingModelTest {

    private final EmbeddingModel delegate = mock(EmbeddingModel.class);
    private final LlmUsageRepository usageRepo = mock(LlmUsageRepository.class);

    private static EmbeddingResponse responseWithUsage(int promptTokens) {
        var metadata = new EmbeddingResponseMetadata("test-model", new DefaultUsage(promptTokens, 0));
        return new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f}, 0)), metadata);
    }

    private static EmbeddingResponse responseWithEmptyUsage() {
        var metadata = new EmbeddingResponseMetadata("test-model", new EmptyUsage());
        return new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f}, 0)), metadata);
    }

    @Test
    @DisplayName("call() — 응답 usage 그대로 embed:<model> 로 기록, output=0")
    void recordsRealUsageUnderEmbedPrefix() {
        when(delegate.call(any())).thenReturn(responseWithUsage(42));
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true);

        model.call(new EmbeddingRequest(List.of("hello"), null));

        verify(usageRepo).record("embed:nomic", 42, 0);
    }

    @Test
    @DisplayName("usage 미제공(EmptyUsage) + fallback=true → chars/4 근사치 기록")
    void approximatesWhenUsageMissing() {
        when(delegate.call(any())).thenReturn(responseWithEmptyUsage());
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true);

        model.call(new EmbeddingRequest(List.of("12345678"), null)); // 8 chars -> 2 tokens

        verify(usageRepo).record("embed:nomic", 2, 0);
    }

    @Test
    @DisplayName("usage 미제공 + fallback=false → 0 기록")
    void recordsZeroWhenFallbackDisabled() {
        when(delegate.call(any())).thenReturn(responseWithEmptyUsage());
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", false);

        model.call(new EmbeddingRequest(List.of("12345678"), null));

        verify(usageRepo).record("embed:nomic", 0, 0);
    }

    @Test
    @DisplayName("embed(Document) 도 call() 경유로 기록된다 (delegate.embed(Document) 우회 안 함)")
    void embedDocumentRoutesThroughCall() {
        when(delegate.call(any())).thenReturn(responseWithUsage(5));
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true);

        float[] out = model.embed(new Document("some content"));

        assertThat(out).isNotNull();
        verify(usageRepo).record("embed:nomic", 5, 0);
        verify(delegate, never()).embed(any(Document.class));
    }

    @Test
    @DisplayName("call() — 위임 호출이 실행되는 동안만 EmbeddingConcurrencyTracker 가 증가하고, 끝나면 0으로 돌아온다")
    void tracksInFlightAroundDelegateCall() {
        var tracker = new EmbeddingConcurrencyTracker();
        when(delegate.call(any())).thenAnswer(inv -> {
            assertThat(tracker.get()).isEqualTo(1); // 위임 호출 도중엔 반드시 1
            return responseWithUsage(1);
        });
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true, tracker);

        model.call(new EmbeddingRequest(List.of("hi"), null));

        assertThat(tracker.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("call() — 위임 호출이 예외를 던져도 EmbeddingConcurrencyTracker 는 반드시 감소한다")
    void decrementsTrackerEvenOnDelegateFailure() {
        var tracker = new EmbeddingConcurrencyTracker();
        when(delegate.call(any())).thenThrow(new RuntimeException("boom"));
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true, tracker);

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("hi"), null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(tracker.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("dimensions() 는 delegate 로 직접 위임 (call() 미경유, 기록 없음)")
    void dimensionsDelegatesDirectlyWithoutTracking() {
        when(delegate.dimensions()).thenReturn(768);
        var model = new TrackingEmbeddingModel(delegate, usageRepo, "nomic", true);

        assertThat(model.dimensions()).isEqualTo(768);
        verifyNoInteractions(usageRepo);
        verify(delegate, never()).call(any());
    }
}
