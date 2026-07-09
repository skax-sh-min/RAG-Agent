package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.model.VectorStoreAdminView;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.AdminService.CollectionsResult;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
