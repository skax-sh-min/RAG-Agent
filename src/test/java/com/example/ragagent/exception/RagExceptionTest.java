package com.example.ragagent.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies errorCode and httpStatus for each RagException subclass.
 */
class RagExceptionTest {

    @Test @DisplayName("DocumentIndexingException — 에러코드 RAG-INDEX-001, HTTP 500")
    void documentIndexing() {
        DocumentIndexingException ex = new DocumentIndexingException("msg");
        assertThat(ex.errorCode()).isEqualTo("RAG-INDEX-001");
        assertThat(ex.httpStatus()).isEqualTo(500);
        assertThat(ex.getMessage()).isEqualTo("msg");
    }

    @Test @DisplayName("VectorStoreException — 에러코드 RAG-VEC-001, HTTP 503")
    void vectorStore() {
        VectorStoreException ex = new VectorStoreException("vec error", new RuntimeException());
        assertThat(ex.errorCode()).isEqualTo("RAG-VEC-001");
        assertThat(ex.httpStatus()).isEqualTo(503);
    }

    @Test @DisplayName("InvalidQuestionException — 에러코드 RAG-VAL-001, HTTP 400")
    void invalidQuestion() {
        InvalidQuestionException ex = new InvalidQuestionException("blank");
        assertThat(ex.errorCode()).isEqualTo("RAG-VAL-001");
        assertThat(ex.httpStatus()).isEqualTo(400);
    }

    @Test @DisplayName("UnsupportedFileTypeException — 에러코드 RAG-UP-001, HTTP 422")
    void unsupportedFileType() {
        UnsupportedFileTypeException ex = new UnsupportedFileTypeException("bad ext");
        assertThat(ex.errorCode()).isEqualTo("RAG-UP-001");
        assertThat(ex.httpStatus()).isEqualTo(422);
    }

    @Test @DisplayName("LlmProviderExhaustedException — 에러코드 RAG-LLM-001, HTTP 503")
    void llmProviderExhausted() {
        LlmProviderExhaustedException ex = new LlmProviderExhaustedException("no providers");
        assertThat(ex.errorCode()).isEqualTo("RAG-LLM-001");
        assertThat(ex.httpStatus()).isEqualTo(503);
    }

    @Test @DisplayName("DocumentIndexingException(cause) — cause 전파")
    void causeIsPropagated() {
        RuntimeException cause = new RuntimeException("root cause");
        DocumentIndexingException ex = new DocumentIndexingException("wrapper", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
