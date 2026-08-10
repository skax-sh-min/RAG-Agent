package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
@WebMvcTest(value = DocumentController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
@ResourceLock("global-state")
class DocumentControllerHtmxTest {

    @Autowired MockMvc mvc;

    @MockitoBean RagService ragService;
    @MockitoBean com.example.ragagent.repository.CuratedQaRepository curatedQaRepository;
    @MockitoBean IndexingProgressService indexingProgressService;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean DocumentExportService documentExportService;
        @MockitoBean LlmRouter llmRouter;

    @TempDir Path tempDir;

    @BeforeEach
    void setUpImageDir() {
        when(ragService.userDocumentsDir(any())).thenReturn(tempDir.resolve("documents"));
    }

    private Path writeImage(String docId, String filename, byte[] content) throws Exception {
        Path dir = tempDir.resolve("images").resolve(docId);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, content);
        return file;
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 정상 업로드 → 202 + taskId (비동기 인덱싱)")
    void uploadDocument_success_returnsJson() throws Exception {
        when(indexingProgressService.newTaskId()).thenReturn("task-abc");

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(file)
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-abc"));
    }

    @Test
    @DisplayName("POST /ui/documents/upload — 업로드 파일이 documentsDir에 영구 저장됨 (동기화 삭제 오판 회귀 방지)")
    void uploadDocument_persistsFileToDocumentsDir() throws Exception {
        when(indexingProgressService.newTaskId()).thenReturn("task-persist");

        MockMultipartFile file = new MockMultipartFile(
                "file", "keep.pdf", "application/pdf", "%PDF-1.4 dummy".getBytes());

        mvc.perform(multipart("/ui/documents/upload").file(file)
                        .param("version", "latest")
                        .with(csrf()))
                .andExpect(status().isAccepted());

        // syncDirectory() scans documentsDir and deletes any registered doc missing from disk —
        // if the upload only wrote to a temp file, this file would be absent and the next sync
        // would wipe the document's embeddings.
        assertThat(tempDir.resolve("documents").resolve("keep.pdf")).exists();
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

    // ── 태그 편집 (HTMX view/edit toggle) ───────────────────────

    @Test
    @DisplayName("GET /ui/documents/{docId}/tags/edit — 현재 태그가 채워진 편집 폼 반환")
    void editTagsForm_returnsFormPrefilledWithCurrentTags() throws Exception {
        when(ragService.findDocument(any(), eq("doc1"))).thenReturn(Optional.of(
                new DocumentInfo("doc1", "f.pdf", "latest", 3, "t", "sha", List.of("faq", "guide"), List.of())));

        mvc.perform(get("/ui/documents/doc1/tags/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("faq, guide")));
    }

    @Test
    @DisplayName("GET /ui/documents/{docId}/tags/edit — 존재하지 않는 문서 → 400")
    void editTagsForm_missingDoc_returns400() throws Exception {
        when(ragService.findDocument(any(), eq("missing"))).thenReturn(Optional.empty());

        mvc.perform(get("/ui/documents/missing/tags/edit"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /ui/documents/{docId}/tags — 갱신된 태그로 뷰 프래그먼트 반환 + 감사 로그")
    void updateTags_success_returnsViewFragment() throws Exception {
        when(ragService.updateDocumentTags(any(), eq("doc1"), any())).thenReturn(
                new DocumentInfo("doc1", "f.pdf", "latest", 3, "t", "sha", List.of("x", "y"), List.of()));

        mvc.perform(patch("/ui/documents/doc1/tags")
                        .param("tags", "x, y")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("x")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("y")));
    }

    // ── 회귀: 추출된 SVG는 inline 렌더 금지 ───────────────────────

    @Test
    @DisplayName("GET /api/v1/images/{docId}/{filename} — .svg는 octet-stream + attachment로 강제 다운로드")
    void getImage_svg_forcesDownloadInsteadOfInlineRender() throws Exception {
        writeImage("doc1", "s1_img1.svg",
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes());

        mvc.perform(get("/api/v1/images/doc1/s1_img1.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"s1_img1.svg\""))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    @Test
    @DisplayName("GET /api/v1/images/{docId}/{filename} — .png는 기존대로 image/* inline 반환")
    void getImage_png_staysInline() throws Exception {
        writeImage("doc1", "p1_img1.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        mvc.perform(get("/api/v1/images/doc1/p1_img1.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    @DisplayName("GET /api/v1/tags — 기본(excludeCommon 없음)은 전체 태그 목록 (업로드 태그 제안용)")
    void listTags_defaultsToFullList() throws Exception {
        when(ragService.listTags("v1")).thenReturn(List.of("billing", "common", "policy"));

        mvc.perform(get("/api/v1/tags").param("version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.containsInAnyOrder("billing", "common", "policy")));
    }

    @Test
    @DisplayName("GET /api/v1/tags?excludeCommon=true — 채팅 칩용 공통 태그 제외 목록")
    void listTags_excludeCommon_usesFilteredList() throws Exception {
        when(ragService.listTagsExcludingCommon("v1")).thenReturn(List.of("billing", "policy"));

        mvc.perform(get("/api/v1/tags").param("version", "v1").param("excludeCommon", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.containsInAnyOrder("billing", "policy")));
    }
}
