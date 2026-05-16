package com.example.ragagent.security;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.controller.OperationsController;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 07-auth-ready-seams: 보안 헤더 4종 응답 검증 + CSRF 403 검증.
 */
@WebMvcTest(OperationsController.class)
@Import({SecurityConfig.class, com.example.ragagent.context.WebMvcConfig.class})
class SecurityHeadersTest {

    @Autowired MockMvc mvc;

    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean AppProperties props;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;

    @Test
    @DisplayName("GET 응답에 X-Frame-Options 헤더 존재")
    void response_hasXFrameOptions() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("X-Frame-Options"));
    }

    @Test
    @DisplayName("GET 응답에 X-Content-Type-Options 헤더 존재")
    void response_hasXContentTypeOptions() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("X-Content-Type-Options"));
    }

    @Test
    @DisplayName("GET 응답에 Content-Security-Policy-Report-Only 헤더 존재")
    void response_hasCsp() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("Content-Security-Policy-Report-Only"));
    }

    @Test
    @DisplayName("DELETE /ui/threads/{id} — CSRF 토큰 없으면 403")
    void delete_withoutCsrf_returns403() throws Exception {
        mvc.perform(delete("/ui/threads/t1"))
                .andExpect(status().isForbidden());
    }
}
