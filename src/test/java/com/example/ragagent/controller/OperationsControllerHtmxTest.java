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
     * {@code onTurnDeleted} 로 막고 있는 것과 같은 고아 문제). 세 호출의 <b>순서까지</b> 고정한다 —
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
    @DisplayName("§10.11 — 좋아요는 검색 지식을 만들지 않는다 (무검토 유입 경로가 사라졌다)")
    void updateTurnFeedback_like_createsNoCuratedEntry() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow(null)));

        mvc.perform(patch("/ui/threads/t1/turns/42/feedback")
                        .param("feedback", "LIKE")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        // 이 엔드포인트가 curated_qa 를 만들던 것이 §10.11 이 막으려는 바로 그 구멍이다 —
        // 문서를 하나도 안 본 Direct 답변이 좋아요 한 번에 전체 검색 지식이 됐다.
        // 등록은 이제 관리자 승인에서만 일어나므로 여기서는 어떤 호출도 있어서는 안 된다.
        org.mockito.Mockito.verifyNoInteractions(curatedQaService);
    }

    @Test
    @DisplayName("§10.11 — 좋아요를 해제해도 등록된 지식은 그대로 남는다 (철회는 제안 페이지의 일)")
    void updateTurnFeedback_unlike_doesNotRetract() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow("LIKE")));

        mvc.perform(patch("/ui/threads/t1/turns/42/feedback")
                        .param("feedback", "NONE")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verifyNoInteractions(curatedQaService);
    }

    @Test
    @DisplayName("DELETE .../turns/{id} — 좋아요 여부와 무관하게 등록된 지식을 회수한다")
    void deleteTurn_retractsRegardlessOfFeedback() throws Exception {
        when(memoryService.getFeedback(any(), any(), anyLong()))
                .thenReturn(Optional.of(new MemoryRepository.FeedbackRow(null)));
        when(memoryService.deleteTurn(any(), any(), anyLong())).thenReturn(true);

        mvc.perform(delete("/ui/threads/t1/turns/42").with(csrf()))
                .andExpect(status().isNoContent());

        // 예전에는 feedback='LIKE' 일 때만 회수했다. §10.11 이 엔트리의 존재를 피드백 값에서
        // 떼어냈으므로, LIKE 를 확인하고 들어가면 나중에 마음이 바뀐 저자의 엔트리를 전부 놓친다.
        verify(curatedQaService).onTurnDeleted(any(), eq("t1"), eq(42L));
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
