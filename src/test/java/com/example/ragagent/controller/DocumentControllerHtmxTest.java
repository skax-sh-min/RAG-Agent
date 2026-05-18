package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA — DocumentController HTMX 계약 보호
 *
 * Covers:
 *  - 정상 업로드 → 202 + taskId
 *  - 빈 파일 → 400
 *  - 미지원 확장자 → 422
 *  - 문서 삭제 → 200
 *  - 동기화 → 202 + taskId
 */
@WebMvcTest(DocumentController.class)
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
class DocumentControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean RagService ragService;
    @MockitoBean IndexingProgressService indexingProgressService;
    @MockitoBean AppProperties props;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AuditLogger auditLogger;

    @Test
    @DisplayName("POST /ui/documents/upload — 정상 업로드 → 202 + taskId (비동기 인덱싱)")
    void uploadDocument_success_returnsJson() throws Exception {
        when(indexingProgressService.newTaskId()).thenReturn("task-abc");
        when(props.dataDir()).thenReturn("/tmp/rag-test");

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(file)
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-abc"));
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 빈 파일 → 400")
    void uploadDocument_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/ui/documents/upload").file(empty)
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 미지원 확장자 → 422")
    void uploadDocument_unsupportedExt_returns422() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "MZ".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(exe)
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("DELETE /ui/documents/{docId} — 200 OK")
    void deleteDocument_returnsOk() throws Exception {
        mvc.perform(delete("/ui/documents/doc_abc")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /ui/documents/sync — 정상 → 202 + taskId (비동기 싱크)")
    void syncDocuments_returnsAccepted() throws Exception {
        when(indexingProgressService.newTaskId()).thenReturn("task-sync-1");

        mvc.perform(post("/ui/documents/sync")
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-sync-1"));
    }
}
