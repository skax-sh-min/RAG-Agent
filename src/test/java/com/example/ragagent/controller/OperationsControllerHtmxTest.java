package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.InOrder;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.MemoryRepository;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
@ResourceLock("global-state")
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
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean EmbeddingConcurrencyTracker embeddingConcurrencyTracker;
    @MockitoBean BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker;

    @Test
    @DisplayName("DELETE /ui/threads/{id} — 200 OK")
    void deleteThread_returnsOk() throws Exception {
        mvc.perform(delete("/ui/threads/t1")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    /**
     * §6.25 — 대화 삭제가 그 대화의 좋아요 큐레이션을 회수하지 않으면, 대화가 사라진 뒤에도
     * curated_qa 행과 벡터가 남아 계속 검색에 기여한다(turn 단위 {@code deleteTurn} 이 이미
     * {@code onUnlike} 로 막고 있는 것과 같은 고아 문제). 세 호출의 <b>순서까지</b> 고정한다 —
     * 회수가 기록 삭제보다 먼저다.
     */
    @Test
    @DisplayName("DELETE /ui/threads/{id} — 기록 삭제 전에 큐레이션을 회수한다")
    void deleteThread_retractsCuratedEntriesBeforeClearingHistory() throws Exception {
        mvc.perform(delete("/ui/threads/t1").with(csrf()))
                .andExpect(status().isOk());

        InOrder order = inOrder(curatedQaService, memoryService, threadMetaService);
        order.verify(curatedQaService).onThreadDeleted(any(), eq("t1"));
        order.verify(memoryService).clearHistory(any(), eq("t1"));
        order.verify(threadMetaService).delete(any(), eq("t1"));
    }

    @Test
    @DisplayName("DELETE /ui/threads/{id} — 회수 건수를 감사 로그에 남긴다")
    void deleteThread_recordsRetractedCountInAudit() throws Exception {
        when(curatedQaService.onThreadDeleted(any(), eq("t1"))).thenReturn(3);

        mvc.perform(delete("/ui/threads/t1").with(csrf()))
                .andExpect(status().isOk());

        verify(auditLogger).log(eq("thread.delete"), eq("t1"),
                argThat(details -> Integer.valueOf(3).equals(details.get("curatedRetracted"))));
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

    // ── §10.10 step ④ — 본인 큐레이션 답변 인라인 편집 ────────────────────────

    @Test
    @DisplayName("GET .../turns/{id}/curated — 소유 turn + 활성 큐레이션 엔트리 → 200 + answer")
    void getCuratedAnswer_ownedAndActive_returnsAnswer() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        when(curatedQaService.findActiveByTurn(42L)).thenReturn(Optional.of(
                new com.example.ragagent.repository.CuratedQaRepository.CuratedQa(
                        1L, 42L, "user", "t1", "질문", "답변", "active", "latest", "2026-01-01", "2026-01-01", "ok", "like", null, null, 1)));

        mvc.perform(get("/ui/threads/t1/turns/42/curated"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"answer\":\"답변\"}"));
    }

    @Test
    @DisplayName("GET .../turns/{id}/curated — 소유하지 않은 turn → 404")
    void getCuratedAnswer_notOwned_returns404() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/ui/threads/t1/turns/42/curated"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/curated — 소유 turn + 갱신 성공 → 204 No Content")
    void updateCuratedAnswer_success_returnsNoContent() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));
        when(curatedQaService.updateAnswerForTurn(any(), any(), eq(42L), any()))
                .thenReturn(true);

        mvc.perform(patch("/ui/threads/t1/turns/42/curated")
                        .param("answer", "수정된 답변")
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH .../turns/{id}/curated — 소유하지 않은 turn → 404 (curatedQaService 호출 자체가 없음)")
    void updateCuratedAnswer_notOwned_returns404() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong())).thenReturn(Optional.empty());

        mvc.perform(patch("/ui/threads/t1/turns/42/curated")
                        .param("answer", "수정된 답변")
                        .with(csrf()))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(curatedQaService, org.mockito.Mockito.never())
                .updateAnswerForTurn(any(), any(), anyLong(), any());
    }
}
