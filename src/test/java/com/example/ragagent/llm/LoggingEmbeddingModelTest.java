package com.example.ragagent.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — LoggingEmbeddingModel, the embedding counterpart of LoggingChatModel.
 *
 * Embedding requests previously had no DEBUG curl log at all (unlike chat requests), so this
 * covers: delegation is transparent (response/exception pass through unchanged, dimensions()
 * bypasses call() entirely), embed(Document) still routes through call() rather than
 * delegate.embed(Document) (so it gets logged too), and the DEBUG log actually names the
 * provider/model/endpoint when isDebugEnabled() — the whole point of this class.
 */
class LoggingEmbeddingModelTest {

    private final EmbeddingModel delegate = mock(EmbeddingModel.class);
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        logbackLogger = (Logger) org.slf4j.LoggerFactory.getLogger(LoggingEmbeddingModel.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
        logbackLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogCapture() {
        logbackLogger.detachAppender(logAppender);
    }

    private static EmbeddingResponse response() {
        return new EmbeddingResponse(List.of(new Embedding(new float[]{0.1f}, 0)));
    }

    @Test
    @DisplayName("call() — delegate에 그대로 위임하고 응답을 변형 없이 반환")
    void callDelegatesAndReturnsResponseUnchanged() {
        EmbeddingResponse expected = response();
        when(delegate.call(any())).thenReturn(expected);
        var model = new LoggingEmbeddingModel(delegate, "embed:nomic",
                "http://localhost:1234/v1", "sk-test-key-123456", "nomic");
        EmbeddingRequest request = new EmbeddingRequest(List.of("hello"), null);

        EmbeddingResponse actual = model.call(request);

        assertThat(actual).isSameAs(expected);
        verify(delegate).call(request);
    }

    @Test
    @DisplayName("DEBUG 활성 시 provider/endpoint/model/텍스트 개수가 로그에 남는다")
    void logsCurlReproductionAtDebugLevel() {
        when(delegate.call(any())).thenReturn(response());
        var model = new LoggingEmbeddingModel(delegate, "embed:nomic",
                "http://localhost:1234/v1", "sk-test-key-123456", "nomic-embed-text");

        model.call(new EmbeddingRequest(List.of("first chunk", "second chunk"), null));

        assertThat(logAppender.list).anySatisfy(event -> {
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("embed:nomic")
                    .contains("texts=2")
                    .contains("http://localhost:1234/v1/embeddings")
                    .contains("nomic-embed-text")
                    .contains("sk-t****") // masked, not the raw key
                    .doesNotContain("sk-test-key-123456");
        });
    }

    @Test
    @DisplayName("DEBUG 비활성이면 로그를 남기지 않는다")
    void logsNothingWhenDebugDisabled() {
        logbackLogger.setLevel(Level.INFO);
        when(delegate.call(any())).thenReturn(response());
        var model = new LoggingEmbeddingModel(delegate, "embed:nomic",
                "http://localhost:1234/v1", "key", "nomic");

        model.call(new EmbeddingRequest(List.of("x"), null));

        assertThat(logAppender.list).isEmpty();
    }

    @Test
    @DisplayName("embed(Document) 도 call() 경유로 로깅된다 (delegate.embed(Document) 우회 안 함)")
    void embedDocumentRoutesThroughCall() {
        when(delegate.call(any())).thenReturn(response());
        var model = new LoggingEmbeddingModel(delegate, "embed:nomic",
                "http://localhost:1234/v1", "key", "nomic");

        float[] out = model.embed(new Document("some content"));

        assertThat(out).isNotNull();
        verify(delegate).call(any());
        verify(delegate, never()).embed(any(Document.class));
    }

    @Test
    @DisplayName("dimensions() 는 delegate 로 직접 위임 (call() 미경유, 로그 없음)")
    void dimensionsDelegatesDirectlyWithoutLogging() {
        when(delegate.dimensions()).thenReturn(768);
        var model = new LoggingEmbeddingModel(delegate, "embed:nomic",
                "http://localhost:1234/v1", "key", "nomic");

        assertThat(model.dimensions()).isEqualTo(768);
        verify(delegate, never()).call(any());
        assertThat(logAppender.list).isEmpty();
    }
}
