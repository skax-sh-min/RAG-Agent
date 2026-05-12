package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.ChatResponse;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.model.SyncResult;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.service.StreamingAgentService;
import com.example.ragagent.service.ThreadMetaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * QA — WebController HTMX 계약 보호
 *
 * Covers (per refactoring/01-test-safety-net.md):
 *  - POST /ui/chat 가 정상 응답 시 'fragments/message-assistant :: message' 반환
 *  - 빈 질문 → 'fragments/message-error :: message'
 *  - DUAL 응답 시 'fragments/message-assistant-dual :: message'
 *  - 업로드 / 삭제 / 라우팅 모드 변경 동작
 *
 * 본 테스트는 fragment 셀렉터 (외부 계약)가 변경되면 즉시 실패해야 함.
 */
@WebMvcTest(WebController.class)
class WebControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean AgentService agentService;
    @MockitoBean StreamingAgentService streamingAgentService;
    @MockitoBean RagService ragService;
    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean AppProperties props;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean LlmRouter llmRouter;
    @MockitoBean ChatModel chatModel;  // WebConfig.chatClient(ChatModel) 빈 의존성 충족

    private ChatResponse sampleResponse() {
        return new ChatResponse(
                "## 요약\n핵심 답변",
                "manual",
                List.of(new SourceRef("doc.pdf | v1 | p.3", "snippet preview", "doc_abc", 3)),
                List.of(),
                120, 80, 2, 0.42,
                null, "gemini-flash", null, null);
    }

    private ChatResponse dualResponse() {
        return new ChatResponse(
                "외부 답변",
                "manual",
                List.of(),
                List.of(),
                100, 50, 2, 0.5,
                null, "gemini-flash", "로컬 답변", "local");
    }

    @Test
    @DisplayName("POST /ui/chat — 정상 응답 시 message-assistant fragment 반환")
    void postChat_returnsAssistantFragment() throws Exception {
        when(agentService.chat(any())).thenReturn(sampleResponse());
        when(circuitBreaker.getBlockedProviders()).thenReturn(Map.of());

        mvc.perform(post("/ui/chat")
                        .param("question", "테스트 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("routingMode", "COST_FIRST"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 빈 질문 → message-error fragment")
    void postChat_blankQuestion_returnsErrorFragment() throws Exception {
        mvc.perform(post("/ui/chat")
                        .param("question", "")
                        .param("threadId", "t1")
                        .param("version", "latest"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-error :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — DUAL 응답 → message-assistant-dual fragment")
    void postChat_dualResponse_returnsDualFragment() throws Exception {
        when(agentService.chat(any())).thenReturn(dualResponse());
        when(circuitBreaker.getBlockedProviders()).thenReturn(Map.of());

        mvc.perform(post("/ui/chat")
                        .param("question", "DUAL 질문")
                        .param("threadId", "t1")
                        .param("version", "latest")
                        .param("routingMode", "DUAL"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/message-assistant-dual :: message"));
    }

    @Test
    @DisplayName("POST /ui/chat — 서비스 예외 → message-error fragment")
    void postChat_serviceException_returnsErrorFragment() throws Exception {
        when(agentService.chat(any())).thenThrow(new RuntimeException("LLM down"));

        mvc.perform(post("/ui/chat")
                        .param("question", "q")
                        .param("threadId", "t1")
                        .param("version", "latest"))
                .andExpect(view().name("fragments/message-error :: message"));
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 정상 업로드 → 200 + DocumentInfo JSON")
    void uploadDocument_success_returnsJson() throws Exception {
        DocumentInfo info = new DocumentInfo("doc_abc", "test.pdf", "latest", 5,
                "2026-05-12T00:00:00Z", "abc123", List.of());
        when(ragService.indexDocument(any(), anyString(), anyString())).thenReturn(info);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(file)
                        .param("version", "latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doc_id").value("doc_abc"))
                .andExpect(jsonPath("$.filename").value("test.pdf"))
                .andExpect(jsonPath("$.chunks").value(5));
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 빈 파일 → 400")
    void uploadDocument_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/ui/documents/upload").file(empty)
                        .param("version", "latest"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 미지원 확장자 → 422")
    void uploadDocument_unsupportedExt_returns422() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "MZ".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(exe)
                        .param("version", "latest"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("DELETE /ui/threads/{id} — 200 OK")
    void deleteThread_returnsOk() throws Exception {
        mvc.perform(delete("/ui/threads/t1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /ui/threads/{id}/routing-mode — 204 No Content")
    void updateRoutingMode_returnsNoContent() throws Exception {
        mvc.perform(patch("/ui/threads/t1/routing-mode")
                        .param("routingMode", "QUALITY_FIRST"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /ui/documents/{docId} — 200 OK")
    void deleteDocument_returnsOk() throws Exception {
        mvc.perform(delete("/ui/documents/doc_abc")
                        .param("version", "latest"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /ui/documents/sync — 정상 → sync-result fragment")
    void syncDocuments_returnsFragment() throws Exception {
        when(ragService.syncDirectory(anyString()))
                .thenReturn(new SyncResult(List.of("a.pdf"), List.of(), List.of()));

        mvc.perform(post("/ui/documents/sync").param("version", "latest"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/sync-result :: result"));
    }
}
