package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.StreamingAgentService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * QA — ChatController HTMX 계약 보호
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - POST /ui/chat 가 정상 응답 시 'fragments/message-assistant :: message' 반환
 *  - 빈 질문 → 'fragments/message-error :: message'
 *  - DUAL 응답 시 'fragments/message-assistant-dual :: message'
 *  - 서비스 예외 → 'fragments/message-error :: message'
 *  - directMode 누락 시 400 방지
 */
@WebMvcTest(value = ChatController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
@ResourceLock("global-state")
class ChatControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean AgentService agentService;
    @MockitoBean StreamingAgentService streamingAgentService;
    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean ConversationSummarizerService summarizerService;
    @MockitoBean AppProperties props;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;

    private ChatResponse sampleResponse() {
        return new ChatResponse(
                "## 요약\n핵심 답변",
                "manual",
                List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet preview", "doc_abc", 3)),
                List.of(),
                120, 80, 2, 0.42,
                null, "gemini-flash", null, null, 1L);
    }

    private ChatResponse dualResponse() {
        return new ChatResponse(
                "외부 답변",
                "manual",
                List.of(),
                List.of(),
                100, 50, 2, 0.5,
                null, "gemini-flash", "로컬 답변", "local", 2L);
    }

    @Test
    @DisplayName("POST /ui/chat — 정상 응답 시 message-assistant fragment 반환")
    void postChat_returnsAssistantFragment() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("routingMode", "COST_FIRST")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 빈 질문 → message-error fragment")
    void postChat_blankQuestion_returnsErrorFragment() throws Exception {
        mvc.perform(post("/ui/chat")
                        .param("question", "")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-error :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — DUAL 응답 → message-assistant-dual fragment")
    void postChat_dualResponse_returnsDualFragment() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(dualResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "DUAL 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("routingMode", "DUAL")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant-dual :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 서비스 예외 → message-error fragment")
    void postChat_serviceException_returnsErrorFragment() throws Exception {
        when(agentService.chat(any(), any())).thenThrow(new RuntimeException("LLM down"));

        mvc.perform(post("/ui/chat")
                        .param("question", "q")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(view().name("fragments/message-error :: message"));
    }

    // ── 회귀: directMode 파라미터 누락 시 400 방지 ────────────────────────

    @Test
    @DisplayName("POST /ui/chat — directMode 누락 시 400 아닌 정상 응답")
    void postChat_missingDirectMode_doesNotReturn400() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat/stream — directMode 누락 시 SSE 정상 시작")
    void streamChat_missingDirectMode_doesNotReturn400() throws Exception {
        when(props.sseTimeoutMs()).thenReturn(300_000L);
        mvc.perform(post("/ui/chat/stream")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("§6.10 — POST /ui/chat/summary/precompute → 202 Accepted (백그라운드 발화 후 즉시 응답)")
    void precomputeSummary_returnsAccepted() throws Exception {
        mvc.perform(post("/ui/chat/summary/precompute")
                        .param("threadId", "t1")
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /ui/chat — 전송된 태그 선택을 스레드에 스냅샷 저장 (사이드바 목록용)")
    void postChat_snapshotsSelectedTagsOntoThread() throws Exception {
        when(agentService.chat(any(), any())).thenReturn(sampleResponse());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("tags", "billing, policy")
                        .with(csrf()))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(threadMetaService)
                .updateTags(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("t1"),
                        org.mockito.ArgumentMatchers.eq(List.of("billing", "policy")));
    }

    @Test
    @DisplayName("POST /ui/chat/stream — 전송된 태그 선택을 스레드에 스냅샷 저장 (사이드바 목록용)")
    void streamChat_snapshotsSelectedTagsOntoThread() throws Exception {
        when(props.sseTimeoutMs()).thenReturn(300_000L);

        mvc.perform(post("/ui/chat/stream")
                        .param("question", "테스트")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("tags", "onboarding")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        org.mockito.Mockito.verify(threadMetaService)
                .updateTags(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("t1"),
                        org.mockito.ArgumentMatchers.eq(List.of("onboarding")));
    }
}
