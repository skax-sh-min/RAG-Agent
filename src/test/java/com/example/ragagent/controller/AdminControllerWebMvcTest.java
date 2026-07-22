package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.model.VectorStoreAdminView;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.AdminService.CollectionsResult;
import com.example.ragagent.service.CuratedQaService;
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
class AdminControllerWebMvcTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean AdminService adminService;
    @MockitoBean RagService ragService;
    @MockitoBean IndexingProgressService progressService;
    @MockitoBean CuratedQaService curatedQaService;
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
                .andExpect(content().string(containsString("컬렉션 수")));
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

    // ── §10.10 step ④ — 큐레이션 Q&A 관리 ────────────────────────────────────

    @Test
    @DisplayName("GET /admin — curatedEntries 모델 속성과 항목 렌더")
    void adminPage_rendersCuratedEntries() throws Exception {
        when(adminService.vectorStoreView()).thenReturn(
                new VectorStoreAdminView("chroma", true, -1, 0, 0, null, null, "/data/memory.db", null));
        when(curatedQaService.listActive(anyInt())).thenReturn(List.of(
                new com.example.ragagent.repository.CuratedQaRepository.CuratedQa(
                        1L, 42L, "u1", "t1", "질문입니다", "답변입니다", "active", "latest",
                        "2026-01-01T00:00:00", "2026-01-01T00:00:00")));

        mvc.perform(get("/admin").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("curatedEntries"))
                .andExpect(content().string(containsString("질문입니다")));
    }

    @Test
    @DisplayName("GET /admin/curated/{id}/detail — 존재하면 200 + question/answer")
    void curatedDetail_found_returnsOk() throws Exception {
        when(curatedQaService.findById(1L)).thenReturn(Optional.of(
                new com.example.ragagent.repository.CuratedQaRepository.CuratedQa(
                        1L, 42L, "u1", "t1", "질문", "답변", "active", "latest",
                        "2026-01-01T00:00:00", "2026-01-01T00:00:00")));

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
}
