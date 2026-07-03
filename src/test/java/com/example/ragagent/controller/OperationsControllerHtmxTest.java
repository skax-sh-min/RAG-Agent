package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.MemoryRepository;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA — OperationsController HTMX 계약 보호
 *
 * Covers:
 *  - DELETE /ui/threads/{id} → 200 OK
 *  - PATCH /ui/threads/{id}/routing-mode → 204 No Content
 */
@WebMvcTest(value = OperationsController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
class OperationsControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean AppProperties props;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("DELETE /ui/threads/{id} — 200 OK")
    void deleteThread_returnsOk() throws Exception {
        mvc.perform(delete("/ui/threads/t1")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /ui/threads/{id}/routing-mode — 204 No Content")
    void updateRoutingMode_returnsNoContent() throws Exception {
        mvc.perform(patch("/ui/threads/t1/routing-mode")
                        .param("routingMode", "QUALITY_FIRST")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/feedback — LIKE → 204 No Content")
    void updateTurnFeedback_like_returnsNoContent() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow(null)));

        mvc.perform(patch("/ui/threads/t1/turns/42/feedback")
                        .param("feedback", "LIKE")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/feedback — NONE (해제) → 204 No Content")
    void updateTurnFeedback_clear_returnsNoContent() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        mvc.perform(patch("/ui/threads/t1/turns/42/feedback")
                        .param("feedback", "none") // 대소문자 무관
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/feedback — 존재하지 않는 turn → 404")
    void updateTurnFeedback_notFound_returns404() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong())).thenReturn(Optional.empty());

        mvc.perform(patch("/ui/threads/t1/turns/999/feedback")
                        .param("feedback", "DISLIKE")
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/feedback — 잘못된 값 → 400")
    void updateTurnFeedback_invalidValue_returns400() throws Exception {
        mvc.perform(patch("/ui/threads/t1/turns/42/feedback")
                        .param("feedback", "MAYBE")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
