package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.model.VectorStoreAdminView;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.AdminService.CollectionsResult;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.CuratedSubmissionService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.IndexingProgressService;
import com.example.ragagent.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * QA — {@code GET /admin}이 두 백엔드(chroma·sqlite-vec)에서 모델 속성을 채우고
 * {@code admin.html}을 회귀 없이 렌더하는지 검증한다. 서비스 단위(AdminServiceTest)가
 * 못 잡는 컨트롤러 배선 + Thymeleaf 백엔드 조건부 렌더 회귀를 보호한다.
 */
@WebMvcTest(value = AdminController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class AdminControllerWebMvcTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean AdminService adminService;
    @MockitoBean RagService ragService;
    @MockitoBean IndexingProgressService progressService;
    @MockitoBean CuratedQaService curatedQaService;
    @MockitoBean CuratedSubmissionService submissionService;
    @MockitoBean RetrievalMetricsService retrievalMetricsService;
    @MockitoBean com.example.ragagent.service.ThreadAdminService threadAdminService;
    @MockitoBean com.example.ragagent.audit.AuditLogger auditLogger;
    @MockitoBean CurrentUser currentUser;             // 승인/거부 시 reviewer id
    @MockitoBean AppProperties props;                 // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;  // WebConfig.chatClient 의존

    @BeforeEach
    void setup() throws Exception {
        // 컨트롤러의 ThreadContext 파라미터를 해석하도록 mock resolver 배선
        when(threadContextResolver.supportsParameter(any())).thenReturn(true);
        when(threadContextResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new ThreadContext("t1", "u1", Locale.KOREAN));
        when(adminService.listCollections()).thenReturn(new CollectionsResult(List.of(), true));
        when(ragService.listDocuments(anyString())).thenReturn(List.of());
        when(curatedQaService.listActive(anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("GET /admin (chroma): 200 + 모델 속성 + 컬렉션 라벨 렌더")
    void adminPage_chroma() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null,
                        "/data/memory.db", null));

        mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"))
                .andExpect(model().attributeExists("vectorStore", "collections", "chromaAvailable", "documents"))
                .andExpect(content().string(containsString("ChromaDB 컬렉션")))
                .andExpect(content().string(containsString("컬렉션 수")))
                // §6.25 — 대화 목록 카드는 페이지에 있되 펼치기 전에는 조회하지 않는다
                // (아래 doesNotEagerlyLoad… 테스트가 그 지연 로딩을 지킨다)
                .andExpect(content().string(containsString("thread-admin-card")))
                .andExpect(content().string(containsString("대화 목록")));
    }

    @Test
    @DisplayName("GET /admin — 문서 레지스트리에 5/10/20/50 페이지 크기 선택과 이동 컨트롤이 렌더된다")
    void adminPage_registryHasPageSizeControls() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null,
                        "/data/memory.db", null));
        when(ragService.listDocuments(anyString())).thenReturn(List.of(
                new com.example.ragagent.model.DocumentInfo(
                        "doc1", "a.md", "latest", 3, "2026-08-31T10:00:00Z", "sha1",
                        List.of(), List.of()),
                new com.example.ragagent.model.DocumentInfo(
                        "doc2", "b.md", "latest", 5, "2026-08-31T09:00:00Z", "sha2",
                        List.of(), List.of())));

        String html = mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 페이징 스크립트가 행을 찾는 앵커 — 이 id 가 빠지면 예외 없이 페이징만 조용히 사라진다.
        org.assertj.core.api.Assertions.assertThat(html).contains("id=\"registry-tbody\"");
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"registry-page-size\"", "5개씩", "10개씩", "20개씩", "50개씩");
        org.assertj.core.api.Assertions.assertThat(html)
                .contains("id=\"registry-prev\"", "id=\"registry-next\"", "id=\"registry-range\"");
        // 기본 선택은 마크업의 selected 가 아니라 initRegistryPaging() 이 정한다(저장된 값 복원).
        org.assertj.core.api.Assertions.assertThat(html).contains("initRegistryPaging()");
    }

    @Test
    @DisplayName("GET /admin — 문서가 없으면 페이지 크기 컨트롤도 렌더하지 않는다")
    void adminPage_registryControlsAbsentWhenNoDocuments() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null,
                        "/data/memory.db", null));
        // setUp() 의 listDocuments = 빈 목록 그대로

        String html = mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("인덱싱된 문서가 없습니다")
                .doesNotContain("id=\"registry-page-size\"");
    }

    /** 다른 지연 로딩 패널과 같은 계약 — /admin 로드만으로 전 사용자 대화를 집계하지 않는다. */
    @Test
    @DisplayName("GET /admin — 대화 목록은 펼치기 전까지 조회하지 않는다")
    void adminPage_doesNotEagerlyLoadThreads() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null,
                        "/data/memory.db", null));

        mvc.perform(get("/admin").with(user(ADMIN))).andExpect(status().isOk());

        verifyNoInteractions(threadAdminService);
    }

    @Test
    @DisplayName("GET /admin (sqlite-vec): 200 + sqlite 전용 지표(vec_version/버전) 렌더")
    void adminPage_sqliteVec() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("sqlite-vec", true, 5, 42,
                        null, "v0.1.9", 768,
                        "/data/memory.db", "/data/vector.db"));

        mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"))
                .andExpect(model().attributeExists("vectorStore"))
                .andExpect(content().string(containsString("버전 (sqlite-vec)")))
                .andExpect(content().string(containsString("vec_version")));
    }

    // ── 청크 목록 조회 — 페이지당 건수 ────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/chunks — limit 파라미터 생략 시 기본값 20건")
    void chunks_defaultLimitIsTwenty() throws Exception {
        when(adminService.getChunks(anyString(), any(), eq(0), eq(20))).thenReturn(List.of());

        mvc.perform(get("/admin/chunks").with(user(ADMIN)).param("collection", "manual_latest"))
                .andExpect(status().isOk());

        verify(adminService).getChunks("manual_latest", null, 0, 20);
    }

    // ── §10.10 step ④ — 큐레이션 Q&A 관리 ────────────────────────────────────

    @Test
    @DisplayName("GET /admin — 큐레이션 패널은 접혀 있고, listActive()는 호출되지 않음 (지연 로딩)")
    void adminPage_doesNotEagerlyLoadCuratedEntries() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null, "/data/memory.db", null));

        mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("curatedEntries"))
                .andExpect(content().string(containsString("펼치면 조회")));

        verifyNoInteractions(curatedQaService);
    }

    @Test
    @DisplayName("GET /admin/curated — curatedEntries 모델 속성과 항목 렌더 (패널 펼침 시 호출되는 지연 로딩 프래그먼트)")
    void curatedPanel_rendersCuratedEntries() throws Exception {
        when(curatedQaService.listActive(anyInt(), anyInt())).thenReturn(List.of(
                new com.example.ragagent.repository.CuratedQaRepository.CuratedQa(
                        1L, 42L, "u1", "t1", "질문입니다", "답변입니다", "active", "latest",
                        "2026-01-01T00:00:00", "2026-01-01T00:00:00", "ok", "like", null, null, 1)));

        mvc.perform(get("/admin/curated").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("curatedEntries"))
                .andExpect(content().string(containsString("질문입니다")));
    }

    // ── 청크 추가 게시판 — 검토 패널 ──────────────────────────────────────────

    @Test
    @DisplayName("GET /admin/submissions — 기본 pending 필터로 조회하고 프래그먼트가 렌더된다")
    void submissionPanel_rendersPendingByDefault() throws Exception {
        when(submissionService.listForAdmin(anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new com.example.ragagent.repository.CuratedSubmissionRepository.Submission(
                        1L, "u1", "제안 제목", "제안 본문", "pending", null, null, null,
                        "2026-01-01", "2026-01-01", null, null, "인프라", null, null, 0, 0, 0)));

        mvc.perform(get("/admin/submissions").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("submissions"))
                .andExpect(content().string(containsString("제안 제목")))
                .andExpect(content().string(containsString("검토 대기")));

        verify(submissionService).listForAdmin("pending", 0, 20);
    }

    @Test
    @DisplayName("GET /admin/submissions?status=all — 'all'은 필터 없음(null)으로 서비스에 전달된다")
    void submissionPanel_allMapsToNullFilter() throws Exception {
        when(submissionService.listForAdmin(any(), anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/admin/submissions").param("status", "all").with(user(ADMIN)))
                .andExpect(status().isOk());

        verify(submissionService).listForAdmin(null, 0, 20);
    }

    @Test
    @DisplayName("POST /admin/submissions/{id}/approve — 승인되면 200 + curatedId")
    void approveSubmission_returnsCuratedId() throws Exception {
        when(submissionService.approve(anyLong(), any(), any(), any(), any())).thenReturn(Optional.of(55L));

        mvc.perform(post("/admin/submissions/1/approve").with(csrf()).with(user(ADMIN))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"고친 제목\",\"body\":\"고친 본문\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("55")));

        verify(submissionService).approve(eq(1L), any(), eq("고친 제목"), eq("고친 본문"), any());
    }

    @Test
    @DisplayName("POST /admin/submissions/{id}/approve — 이미 처리된 제안이면 409")
    void approveSubmission_notPending_returns409() throws Exception {
        when(submissionService.approve(anyLong(), any(), any(), any(), any())).thenReturn(Optional.empty());

        mvc.perform(post("/admin/submissions/1/approve").with(csrf()).with(user(ADMIN))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /admin/submissions/{id}/reject — 사유를 서비스로 전달한다")
    void rejectSubmission_passesReason() throws Exception {
        when(submissionService.reject(anyLong(), any(), anyString())).thenReturn(true);

        mvc.perform(post("/admin/submissions/1/reject").with(csrf()).with(user(ADMIN))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"출처 불명\"}"))
                .andExpect(status().isOk());

        verify(submissionService).reject(eq(1L), any(), eq("출처 불명"));
    }

    @Test
    @DisplayName("GET /admin/submissions/pending-count — 대기 건수를 JSON으로 반환")
    void pendingCount_returnsJson() throws Exception {
        when(submissionService.countPending()).thenReturn(3);

        mvc.perform(get("/admin/submissions/pending-count").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":3}"));
    }

    @Test
    @DisplayName("GET /admin/curated — offset/limit 파라미터 생략 시 기본값 0/20으로 서비스 호출")
    void curatedPanel_defaultsOffsetZeroLimitTwenty() throws Exception {
        when(curatedQaService.listActive(anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/admin/curated").with(user(ADMIN)))
                .andExpect(status().isOk());

        verify(curatedQaService).listActive(0, 20);
    }

    @Test
    @DisplayName("GET /admin/curated/{id}/detail — 존재하면 200 + question/answer")
    void curatedDetail_found_returnsOk() throws Exception {
        when(curatedQaService.findById(1L)).thenReturn(Optional.of(
                new com.example.ragagent.repository.CuratedQaRepository.CuratedQa(
                        1L, 42L, "u1", "t1", "질문", "답변", "active", "latest",
                        "2026-01-01T00:00:00", "2026-01-01T00:00:00", "ok", "like", null, null, 1)));

        mvc.perform(get("/admin/curated/1/detail").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"id\":1,\"question\":\"질문\",\"answer\":\"답변\"}"));
    }

    @Test
    @DisplayName("GET /admin/curated/{id}/detail — 없으면 404")
    void curatedDetail_missing_returns404() throws Exception {
        when(curatedQaService.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/admin/curated/99/detail").with(user(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /admin/curated/{id} — 갱신 성공 시 200")
    void updateCurated_success_returnsOk() throws Exception {
        when(curatedQaService.updateAnswer(anyLong(), anyString())).thenReturn(true);

        mvc.perform(post("/admin/curated/1").with(user(ADMIN)).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"관리자가 수정한 답변\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/curated/{id} — 존재하지 않으면 404")
    void updateCurated_missing_returns404() throws Exception {
        when(curatedQaService.updateAnswer(anyLong(), anyString())).thenReturn(false);

        mvc.perform(post("/admin/curated/99").with(user(ADMIN)).with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /admin/curated/{id} — 강제 삭제 성공 시 200, 좋아요 주체와 무관")
    void deleteCurated_success_returnsOk() throws Exception {
        when(curatedQaService.forceRemove(1L)).thenReturn(true);

        mvc.perform(delete("/admin/curated/1").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /admin/curated/{id} — 없으면 404")
    void deleteCurated_missing_returns404() throws Exception {
        when(curatedQaService.forceRemove(99L)).thenReturn(false);

        mvc.perform(delete("/admin/curated/99").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ── 청크 재인덱싱 (재임베딩 + FTS 재색인) ──────────────────────────────────

    @Test
    @DisplayName("POST /admin/chunks/{id}/reindex — 본문 없이 호출해도 regenerateKeywords=false로 처리(200)")
    void reindexChunk_noBody_defaultsToKeepKeywords() throws Exception {
        when(adminService.reindexChunk(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(true);

        mvc.perform(post("/admin/chunks/c1/reindex").with(user(ADMIN)).with(csrf())
                        .param("collection", "manual_latest"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/chunks/{id}/reindex — regenerateKeywords=true가 서비스로 그대로 전달됨")
    void reindexChunk_regenerateKeywordsTrue_passedThrough() throws Exception {
        when(adminService.reindexChunk(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(true);

        mvc.perform(post("/admin/chunks/c1/reindex").with(user(ADMIN)).with(csrf())
                        .param("collection", "manual_latest")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"regenerateKeywords\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/chunks/{id}/reindex — 청크가 없거나 재색인 실패 시 404")
    void reindexChunk_failure_returns404() throws Exception {
        when(adminService.reindexChunk(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);

        mvc.perform(post("/admin/chunks/missing/reindex").with(user(ADMIN)).with(csrf())
                        .param("collection", "manual_latest"))
                .andExpect(status().isNotFound());
    }

    // ── 문서 재인덱싱 — 코드 펜스 사전 점검 ────────────────────────────────────

    @Test
    @DisplayName("POST /admin/documents/{docId}/reindex — 펜스 문제가 있으면 409 + 라인 번호 목록, 작업은 시작하지 않는다")
    void reindexFromMd_fenceProblems_returns409WithLines() throws Exception {
        when(ragService.checkReindexFenceHealth("doc1")).thenReturn(List.of(
                new com.example.ragagent.service.MarkdownCorrectionService.FenceProblem(
                        12, "tagged_closer", "닫는 펜스에 언어 태그가 붙어 있습니다: '```java'"),
                new com.example.ragagent.service.MarkdownCorrectionService.FenceProblem(
                        40, "unclosed", "'```sql' 로 열린 코드 블록이 문서 끝까지 닫히지 않았습니다")));

        mvc.perform(post("/admin/documents/doc1/reindex").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("preflight_warnings")))
                .andExpect(content().string(containsString("\"line\":12")))
                .andExpect(content().string(containsString("\"line\":40")));

        verifyNoInteractions(progressService);   // 아무 작업도 시작되지 않았다
    }

    @Test
    @DisplayName("POST /admin/documents/{docId}/reindex?force=true — 점검을 건너뛰고 바로 시작한다(202)")
    void reindexFromMd_force_skipsCheckAndStarts() throws Exception {
        when(progressService.newTaskId()).thenReturn("task-1");

        mvc.perform(post("/admin/documents/doc1/reindex").with(user(ADMIN)).with(csrf())
                        .param("force", "true"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("task-1")));

        verify(ragService, org.mockito.Mockito.never()).checkReindexFenceHealth(anyString());
    }

    @Test
    @DisplayName("POST /admin/documents/{docId}/reindex — 펜스 문제가 없으면 기존대로 202 + taskId")
    void reindexFromMd_noProblems_startsNormally() throws Exception {
        when(ragService.checkReindexFenceHealth("doc1")).thenReturn(List.of());
        when(progressService.newTaskId()).thenReturn("task-2");

        mvc.perform(post("/admin/documents/doc1/reindex").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("task-2")));
    }

    // ── 문서 재인덱싱 — 편집된 청크 사전 경고 (A안) ─────────────────────────────

    /** 재인덱싱은 MD 파일로 청크를 다시 만들므로 /admin에서 손으로 고친 청크는 사라진다.
     *  펜스가 멀쩡해도 이 사실만으로 사전 확인을 띄워야 한다. */
    @Test
    @DisplayName("POST /admin/documents/{docId}/reindex — 편집된 청크가 있으면 펜스가 멀쩡해도 409, 작업은 시작하지 않는다")
    void reindexFromMd_editedChunks_returns409() throws Exception {
        when(ragService.checkReindexFenceHealth("doc1")).thenReturn(List.of());
        when(ragService.findDocument(anyString(), eq("doc1"))).thenReturn(Optional.of(
                new com.example.ragagent.model.DocumentInfo(
                        "doc1", "a.md", "latest", 3, "2026-01-01T00:00:00Z", "sha", List.of(), List.of())));
        when(adminService.collectionFor("latest")).thenReturn("manual_latest");
        when(adminService.countEditedChunks("manual_latest", "doc1")).thenReturn(2L);

        mvc.perform(post("/admin/documents/doc1/reindex").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("preflight_warnings")))
                .andExpect(content().string(containsString("\"editedChunks\":2")));

        verifyNoInteractions(progressService);
    }

    /** 레지스트리에 없는 문서는 재인덱싱 호출 자체가 실패로 보고할 일이지,
     *  사전 점검이 헷갈리는 경고로 바꿀 일이 아니다. */
    @Test
    @DisplayName("POST /admin/documents/{docId}/reindex — 레지스트리에 없는 문서는 편집 경고 없이 진행한다(202)")
    void reindexFromMd_unknownDocument_noEditedWarning() throws Exception {
        when(ragService.checkReindexFenceHealth("ghost")).thenReturn(List.of());
        when(ragService.findDocument(anyString(), eq("ghost"))).thenReturn(Optional.empty());
        when(progressService.newTaskId()).thenReturn("task-3");

        mvc.perform(post("/admin/documents/ghost/reindex").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(content().string(containsString("task-3")));
    }

    // ── §6.25 대화 목록 패널 ────────────────────────────────────────────────────

    private static com.example.ragagent.service.ThreadAdminService.ThreadView threadView(
            String threadId, String title, String userId, int turns, int diag,
            int reusedIn, int reusedOut) {
        var row = new com.example.ragagent.repository.ThreadAdminRepository.ThreadRow(
                threadId, userId, title, "", "2026-01-01", "2026-08-27 14:22:00",
                "2026-08-27 05:22:00", turns, reusedIn, reusedOut, diag, 1, 0);
        return new com.example.ragagent.service.ThreadAdminService.ThreadView(row, title);
    }

    private void stubPanel(List<com.example.ragagent.service.ThreadAdminService.ThreadView> rows,
                           boolean visitorSeparationOff) {
        when(threadAdminService.panel(any(), any(), anyInt(), anyInt())).thenReturn(
                new com.example.ragagent.service.ThreadAdminService.PanelView(
                        rows,
                        new com.example.ragagent.repository.ThreadAdminRepository.Summary(
                                rows.size(), 1, 9, 2, 0),
                        List.of("u1"), null,
                        com.example.ragagent.repository.ThreadAdminRepository.Sort.RECENT,
                        0, 20, rows.size(), visitorSeparationOff));
    }

    @Test
    @DisplayName("GET /admin/threads — 두 재사용 카운터를 서로 다른 열로 렌더한다")
    void threadPanel_rendersBothReuseCounters() throws Exception {
        stubPanel(List.of(threadView("t1", "인덱싱 파이프라인 질문",
                "guest-a1b2c3d4e5f6", 15, 4, 11, 3)), false);

        mvc.perform(get("/admin/threads").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("인덱싱 파이프라인 질문")))
                .andExpect(content().string(containsString("재사용함")))
                .andExpect(content().string(containsString("재사용됨")))
                // 소유자는 축약해 보이되 원본이 title 속성으로 남는다
                .andExpect(content().string(containsString("guest-a1b2c3d4e5f6")));
    }

    /** shared 게스트에서 목록이 사용자 한 명으로 뭉치는 것을 "한 명이 썼다"로 읽으면 안 된다. */
    @Test
    @DisplayName("GET /admin/threads — 방문자 분리가 꺼져 있으면 이유를 표시한다")
    void threadPanel_warnsWhenVisitorSeparationIsOff() throws Exception {
        stubPanel(List.of(threadView("t1", "대화", "guest", 3, 3, 0, 0)), true);

        mvc.perform(get("/admin/threads").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app.auth.guest-identity=shared")));
    }

    @Test
    @DisplayName("GET /admin/threads — 필터/정렬/페이징이 서비스로 그대로 전달된다")
    void threadPanel_passesFiltersThrough() throws Exception {
        stubPanel(List.of(), false);

        mvc.perform(get("/admin/threads")
                        .param("userId", "u1").param("sort", "REUSED")
                        .param("offset", "40").param("limit", "50")
                        .with(user(ADMIN)))
                .andExpect(status().isOk());

        verify(threadAdminService).panel(eq("u1"), eq("REUSED"), eq(40), eq(50));
    }

    /**
     * 드릴다운은 질문·모드·경로까지만 보여주고 답변 전문은 담지 않는다 — {@code TurnRow}에
     * answer 필드가 아예 없다는 구조적 사실(§6.25 결정 3)을 렌더 결과에서도 고정한다.
     */
    @Test
    @DisplayName("GET /admin/threads/{id}/turns — 질문·경로 배지를 렌더한다")
    void threadTurns_showsQuestionsAndPathBadges() throws Exception {
        var row = new com.example.ragagent.repository.ThreadAdminRepository.TurnRow(
                7L, "2026-08-27 05:22:00", "청크 분할 전략이 뭐야", "N", "local",
                "LIKE", true, false, false);
        when(threadAdminService.turns("t1"))
                .thenReturn(List.of(new com.example.ragagent.service.ThreadAdminService.TurnView(row)));

        mvc.perform(get("/admin/threads/t1/turns").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("청크 분할 전략이 뭐야")))
                .andExpect(content().string(containsString("재사용")));
    }

    @Test
    @DisplayName("GET /admin/threads/{id}/turns — 턴이 없으면 빈 상태를 렌더한다(오류 아님)")
    void threadTurns_emptyRendersPlaceholder() throws Exception {
        when(threadAdminService.turns("ghost")).thenReturn(List.of());

        mvc.perform(get("/admin/threads/ghost/turns").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("턴이 없습니다")));
    }

    // ── §6.25 진단 패널 확장 ─────────────────────────────────────────────────────

    private static com.example.ragagent.service.RetrievalMetricsService.TurnMetrics metricTurn(
            long turnId, String mode, String userId, String threadId, String threadTitle) {
        var src = new com.example.ragagent.model.SourceRef(
                "doc.md | 1.2", "미리보기", "c1", "d1", 3, 0.72, 0.18, "vec:2", 0.31, null, false);
        return new com.example.ragagent.service.RetrievalMetricsService.TurnMetrics(
                turnId, "2026-08-27 05:22:00", "청크 분할 전략", mode, "local",
                List.of(src), 0.72, 1, userId, threadId, threadTitle);
    }

    private void stubMetrics(com.example.ragagent.service.RetrievalMetricsService.TurnMetrics... turns) {
        when(retrievalMetricsService.recent(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(turns));
        when(retrievalMetricsService.count(any(), any())).thenReturn(turns.length);
        when(retrievalMetricsService.userIds()).thenReturn(List.of("u1"));
    }

    @Test
    @DisplayName("GET /admin/retrieval-metrics — 사용자·대화 열과 공용 출처 표를 렌더한다")
    void metricsPanel_rendersOwnerThreadAndSharedSourceTable() throws Exception {
        stubMetrics(metricTurn(7L, "N", "guest-a1b2c3d4e5f6", "t1", "[latest] 인덱싱 질문"));

        mvc.perform(get("/admin/retrieval-metrics").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("사용자")))
                .andExpect(content().string(containsString("guest-a1b2c3d4e5f6")))
                .andExpect(content().string(containsString("인덱싱 질문")))
                // 공용 프래그먼트가 실제로 끼워졌는지 — 열 이름은 그쪽에만 있다
                .andExpect(content().string(containsString("검색기여")))
                .andExpect(content().string(containsString("축별 순위")));
    }

    /**
     * 한 대화는 소유자가 한 명이므로 두 필터를 동시에 들면 화면 어디에도 원인이 없는 빈 목록에
     * 도달할 수 있다. 서버가 threadId 를 우선하고 사용자 필터를 떨어뜨리는 것을 고정한다.
     */
    @Test
    @DisplayName("GET /admin/retrieval-metrics — threadId 가 오면 userId 는 무시된다(배타)")
    void metricsPanel_threadFilterWinsOverUserFilter() throws Exception {
        stubMetrics();
        when(threadAdminService.deletePreview("t1")).thenReturn(Optional.empty());

        mvc.perform(get("/admin/retrieval-metrics")
                        .param("userId", "u1").param("threadId", "t1")
                        .with(user(ADMIN)))
                .andExpect(status().isOk());

        verify(retrievalMetricsService).recent(isNull(), eq("t1"), eq(0), eq(20));
        verify(retrievalMetricsService).count(isNull(), eq("t1"));
    }

    @Test
    @DisplayName("GET /admin/retrieval-metrics — 사용자만 오면 그대로 전달된다")
    void metricsPanel_userFilterPassesThrough() throws Exception {
        stubMetrics();

        mvc.perform(get("/admin/retrieval-metrics").param("userId", "u1").with(user(ADMIN)))
                .andExpect(status().isOk());

        verify(retrievalMetricsService).recent(eq("u1"), isNull(), eq(0), eq(20));
        verify(retrievalMetricsService).count(eq("u1"), isNull());
    }

    @Test
    @DisplayName("GET .../turns/{turnId}/sources — 대화 패널이 쓰는 공용 출처 표를 낸다")
    void turnSources_rendersSharedFragment() throws Exception {
        when(retrievalMetricsService.sourcesForTurn(7L)).thenReturn(List.of(
                new com.example.ragagent.model.SourceRef(
                        "doc.md | 1.2", "미리보기", "c1", "d1", 3, 0.72, 0.18, "vec:2", 0.31, null, false)));

        mvc.perform(get("/admin/retrieval-metrics/turns/7/sources").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("검색기여")))
                .andExpect(content().string(containsString("doc.md | 1.2")));
    }

    @Test
    @DisplayName("GET .../turns/{turnId}/sources — 출처가 없으면 빈 상태를 렌더한다")
    void turnSources_emptyRendersPlaceholder() throws Exception {
        when(retrievalMetricsService.sourcesForTurn(9L)).thenReturn(List.of());

        mvc.perform(get("/admin/retrieval-metrics/turns/9/sources").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("출처 정보가 없습니다")));
    }

    // ── §6.25 답변 원문 열람 (결정 3) ────────────────────────────────────────────

    @Test
    @DisplayName("GET .../turns/{id}/content — 원문을 내주고 열람을 감사 로그에 남긴다")
    void turnContent_returnsTextAndAuditsTheRead() throws Exception {
        when(threadAdminService.turnContent(7L)).thenReturn(Optional.of(
                new com.example.ragagent.service.ThreadAdminService.TurnContentView(
                        7L, "guest-a1b2c3d4e5f6", "t1", "2026-08-27 12:00:00",
                        "청크 분할 전략이 뭐야", "답변 전문입니다", "N")));

        mvc.perform(get("/admin/threads/turns/7/content").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("답변 전문입니다")))
                // 표시 시각은 KST 로 변환된 값이어야 한다 (§6.25 결정 2)
                .andExpect(content().string(containsString("2026-08-27 12:00:00")));

        verify(auditLogger).log(eq("admin.thread.read"), eq("t1"), argThat(d ->
                Long.valueOf(7L).equals(d.get("turnId"))
                        && "guest-a1b2c3d4e5f6".equals(d.get("owner"))));
    }

    /** 없는 턴은 열람이 아니다 — 404 이고 감사 로그에 "읽었다"가 남으면 안 된다. */
    @Test
    @DisplayName("GET .../turns/{id}/content — 없는 턴은 404이고 열람 기록을 남기지 않는다")
    void turnContent_unknownTurnIs404AndNotAudited() throws Exception {
        when(threadAdminService.turnContent(99L)).thenReturn(Optional.empty());

        mvc.perform(get("/admin/threads/turns/99/content").with(user(ADMIN)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(auditLogger);
    }

    /**
     * 드릴다운 목록에는 답변이 실려서는 안 된다 — {@code TurnRow} 에 필드가 없다는 구조적 사실을
     * 렌더 결과로도 고정한다. 원문은 위 감사되는 엔드포인트로만 나간다(결정 3).
     */
    @Test
    @DisplayName("드릴다운 목록은 답변을 싣지 않고, 원문 버튼만 제공한다")
    void threadTurns_listCarriesNoAnswerOnlyTheButton() throws Exception {
        var row = new com.example.ragagent.repository.ThreadAdminRepository.TurnRow(
                7L, "2026-08-27 03:00:00", "질문 미리보기", "N", "local", null, false, false, true);
        when(threadAdminService.turns("t1"))
                .thenReturn(List.of(new com.example.ragagent.service.ThreadAdminService.TurnView(row)));

        mvc.perform(get("/admin/threads/t1/turns").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("질문 미리보기")))
                .andExpect(content().string(containsString("원문")))
                // 시각은 KST 로 (03:00 UTC → 12:00 KST)
                .andExpect(content().string(containsString("2026-08-27 12:00:00")));
    }

    // ── §6.25 대화 삭제 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET .../delete-preview — 확인 대화상자가 쓰는 다섯 숫자를 모두 돌려준다")
    void deletePreview_returnsEveryNumberTheDialogShows() throws Exception {
        when(threadAdminService.deletePreview("t1")).thenReturn(Optional.of(
                new com.example.ragagent.service.ThreadAdminService.DeletePreview(
                        "t1", "인덱싱 질문", "guest-a1b2c3d4e5f6", 15, 3, 4, 2)));

        mvc.perform(get("/admin/threads/t1/delete-preview").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"turnCount\":15")))
                .andExpect(content().string(containsString("\"reusedOut\":3")))
                .andExpect(content().string(containsString("\"diagCount\":4")))
                .andExpect(content().string(containsString("\"curatedCount\":2")))
                .andExpect(content().string(containsString("guest-a1b2c3d4e5f6")));
    }

    @Test
    @DisplayName("GET .../delete-preview — 없는 대화는 404 (대화상자를 띄우지 않는다)")
    void deletePreview_unknownThreadIs404() throws Exception {
        when(threadAdminService.deletePreview("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/admin/threads/ghost/delete-preview").with(user(ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /admin/threads/{id} — 삭제하고 소유자·턴 수·회수 건수를 감사 로그에 남긴다")
    void deleteThread_deletesAndAudits() throws Exception {
        when(threadAdminService.delete("t1")).thenReturn(Optional.of(
                new com.example.ragagent.service.ThreadAdminService.DeleteResult("t1", "u1", 15, 2)));

        mvc.perform(delete("/admin/threads/t1").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"curatedRetracted\":2")));

        verify(auditLogger).log(eq("admin.thread.delete"), eq("t1"), argThat(d ->
                "u1".equals(d.get("owner"))
                        && Integer.valueOf(15).equals(d.get("turnCount"))
                        && Integer.valueOf(2).equals(d.get("curatedRetracted"))));
    }

    /** 이미 없는 대화를 지웠다고 보고하면 운영자가 "지워졌다"고 믿는다 — 감사 로그도 남기지 않는다. */
    @Test
    @DisplayName("DELETE /admin/threads/{id} — 없는 대화는 404이고 감사 로그를 남기지 않는다")
    void deleteThread_unknownThreadIs404AndNotAudited() throws Exception {
        when(threadAdminService.delete("ghost")).thenReturn(Optional.empty());

        mvc.perform(delete("/admin/threads/ghost").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(auditLogger);
    }
}
