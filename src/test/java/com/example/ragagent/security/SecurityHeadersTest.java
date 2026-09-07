package com.example.ragagent.security;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.controller.OperationsController;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 07-auth-ready-seams: 보안 헤더 4종 응답 검증 + CSRF 403 검증.
 */
@WebMvcTest(value = OperationsController.class, properties = "app.auth.enabled=true")
@Import({SecurityConfig.class, com.example.ragagent.context.WebMvcConfig.class})
@WithMockUser
@ResourceLock("global-state")
class SecurityHeadersTest {

    @Autowired MockMvc mvc;

    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean AppProperties props;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean EmbeddingConcurrencyTracker embeddingConcurrencyTracker;
    @MockitoBean BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker;

    @Test
    @DisplayName("GET 응답에 X-Frame-Options 헤더 존재")
    void response_hasXFrameOptions() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("X-Frame-Options"));
    }

    /**
     * {@code /api/**} 이 크로스 오리진에 열려 있으면, 인증 없는 두 모드에서 그 경로는 permitAll +
     * CSRF 예외이므로 방문자가 연 아무 페이지나 그 브라우저를 통해 코퍼스를 읽을 수 있다. 이 앱의
     * API 소비자는 전부 같은 오리진이라 허용할 이유가 없다 — 되살릴 일이 있어도 오리진 목록으로.
     */
    @Test
    @DisplayName("/api/** 는 크로스 오리진 요청에 CORS 허용 헤더를 주지 않는다")
    void api_doesNotAllowCrossOrigin() throws Exception {
        mvc.perform(get("/api/v1/health").header("Origin", "http://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("/api/** 프리플라이트(OPTIONS)도 허용되지 않는다")
    void api_preflightIsNotAllowed() throws Exception {
        mvc.perform(options("/api/v1/documents/doc-1")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("GET 응답에 X-Content-Type-Options 헤더 존재")
    void response_hasXContentTypeOptions() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("X-Content-Type-Options"));
    }

    @Test
    @DisplayName("GET 응답에 Content-Security-Policy 헤더 존재")
    void response_hasCsp() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    @DisplayName("DELETE /ui/threads/{id} — CSRF 토큰 없으면 403")
    void delete_withoutCsrf_returns403() throws Exception {
        mvc.perform(delete("/ui/threads/t1"))
                .andExpect(status().isForbidden());
    }
}
