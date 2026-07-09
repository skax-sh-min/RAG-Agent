package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.*;
import com.example.ragagent.web.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies GlobalExceptionHandler: errorCode, HTTP status, X-Trace-Id, HX-Reswap headers.
 * Uses standaloneSetup to avoid full Spring context — no Security/ThreadContextResolver needed.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class StubController {
        @GetMapping("/test/indexing-error")
        void indexingError() { throw new DocumentIndexingException("SHA-256 failed"); }

        @GetMapping("/test/vector-error")
        void vectorError() { throw new VectorStoreException("Chroma down", new RuntimeException("timeout")); }

        @GetMapping("/test/invalid-question")
        void invalidQuestion() { throw new InvalidQuestionException("question is blank"); }

        @GetMapping("/test/unsupported-file")
        void unsupportedFile() { throw new UnsupportedFileTypeException("unsupported extension: .exe"); }

        @GetMapping("/test/llm-exhausted")
        void llmExhausted() { throw new LlmProviderExhaustedException("all providers blocked"); }

        @GetMapping("/test/unexpected")
        void unexpected() { throw new RuntimeException("boom"); }

        @GetMapping(value = "/test/sse-unexpected", produces = "text/event-stream")
        void sseUnexpected() { throw new RuntimeException("sse boom"); }

        @GetMapping("/test/client-abort")
        void clientAbort() {
            throw new RuntimeException("wrapper", new java.io.IOException("Broken pipe"));
        }

        @GetMapping(value = "/test/sse-io-abort", produces = "text/event-stream")
        void sseIoAbort() {
            // Korean-Windows-localized WSAECONNABORTED message — no English substring match.
            throw new RuntimeException("wrapper",
                    new java.io.IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어에 의해 중단되었습니다"));
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        when(props.sseTimeoutMs()).thenReturn(3_600_000L);
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler(props))
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("DocumentIndexingException → 500 + RAG-INDEX-001")
    void documentIndexingException_returns500() throws Exception {
        mvc.perform(get("/test/indexing-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("RAG-INDEX-001"))
                .andExpect(jsonPath("$.detail").value("SHA-256 failed"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("VectorStoreException → 503 + RAG-VEC-001")
    void vectorStoreException_returns503() throws Exception {
        mvc.perform(get("/test/vector-error"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("RAG-VEC-001"));
    }

    @Test
    @DisplayName("InvalidQuestionException → 400 + RAG-VAL-001")
    void invalidQuestionException_returns400() throws Exception {
        mvc.perform(get("/test/invalid-question"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("RAG-VAL-001"));
    }

    @Test
    @DisplayName("UnsupportedFileTypeException → 422 + RAG-UP-001")
    void unsupportedFileTypeException_returns422() throws Exception {
        mvc.perform(get("/test/unsupported-file"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RAG-UP-001"));
    }

    @Test
    @DisplayName("LlmProviderExhaustedException → 503 + RAG-LLM-001")
    void llmProviderExhaustedException_returns503() throws Exception {
        mvc.perform(get("/test/llm-exhausted"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("RAG-LLM-001"));
    }

    @Test
    @DisplayName("HTMX 요청 → HX-Reswap: none 헤더 포함")
    void htmxRequest_getsHxReswapHeader() throws Exception {
        mvc.perform(get("/test/indexing-error").header("HX-Request", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("HX-Reswap", "none"));
    }

    @Test
    @DisplayName("X-Trace-Id 요청 헤더 → 응답 헤더에 동일 값 반영")
    void traceIdFromRequest_echoedInResponse() throws Exception {
        mvc.perform(get("/test/invalid-question").header("X-Trace-Id", "test-trace-42"))
                .andExpect(header().string("X-Trace-Id", "test-trace-42"))
                .andExpect(jsonPath("$.traceId").value("test-trace-42"));
    }

    @Test
    @DisplayName("예상치 못한 예외(일반 요청) → 500 + ProblemDetail")
    void unexpectedException_returns500ProblemDetail() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("RAG-INT-001"));
    }

    @Test
    @DisplayName("SSE 요청에서 예외 발생 시 ProblemDetail 본문 없이 500")
    void sseUnexpected_returns500WithoutProblemDetailBody() throws Exception {
        mvc.perform(get("/test/sse-unexpected").header("Accept", "text/event-stream"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("클라이언트 연결 종료 예외는 204로 정리")
    void clientAbort_returns204() throws Exception {
        mvc.perform(get("/test/client-abort"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("SSE 요청 중 로케일 메시지의 IOException도 클라이언트 연결 종료로 204 처리")
    void sseIoExceptionWithLocalizedMessage_returns204() throws Exception {
        mvc.perform(get("/test/sse-io-abort").header("Accept", "text/event-stream"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("브라우저 페이지 요청(Accept: text/html, HTMX 아님) → HTML 본문 (ProblemDetail은 text/html 컨버터가 없어 재크래시함)")
    void unexpectedException_htmlPageRequest_returnsHtmlNotProblemDetail() throws Exception {
        mvc.perform(get("/test/unexpected").header("Accept", "text/html"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("500")));
    }

    @Test
    @DisplayName("HTMX 요청은 Accept: text/html 이어도 기존처럼 ProblemDetail(JSON) 유지")
    void unexpectedException_htmxRequestWithHtmlAccept_stillReturnsProblemDetail() throws Exception {
        mvc.perform(get("/test/unexpected").header("Accept", "text/html").header("HX-Request", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("RAG-INT-001"));
    }
}
