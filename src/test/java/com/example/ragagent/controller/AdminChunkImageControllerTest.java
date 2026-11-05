package com.example.ragagent.controller;

import com.example.ragagent.service.ChunkImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 청크 편집 그림 API의 요청·응답 모양 — 로직은 ChunkImageEditorTest / ChunkImageServiceTest 가 지킨다. */
class AdminChunkImageControllerTest {

    private ChunkImageService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ChunkImageService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminChunkImageController(service)).build();
    }

    @Test
    @DisplayName("업로드: 저장 경로와 썸네일 URL 을 돌려준다")
    void uploadReturnsPathAndUrl() throws Exception {
        when(service.upload(any())).thenReturn("images/chunks/abcd.png");

        mvc.perform(multipart("/admin/chunk-images")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("images/chunks/abcd.png"))
                .andExpect(jsonPath("$.url").value("/api/v1/images/chunks/abcd.png"));
    }

    @Test
    @DisplayName("편집: JSON 요청을 서비스에 그대로 넘기고 바뀐 본문과 마커 목록을 돌려준다")
    void editPassesTheRequestThrough() throws Exception {
        when(service.edit(any())).thenReturn(new ChunkImageService.EditResult("바뀐 본문",
                List.of(new ChunkImageService.MarkerView(0, "images/chunks/abcd.png", "/api/v1/images/chunks/abcd.png",
                        true, false, true, "표: 값")), "경고"));

        mvc.perform(post("/admin/chunk-images/edit").contentType(APPLICATION_JSON)
                        .content("{\"action\":\"insert\",\"text\":\"본문\",\"path\":\"images/chunks/abcd.png\",\"offset\":2,\"describe\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("바뀐 본문"))
                .andExpect(jsonPath("$.warning").value("경고"))
                .andExpect(jsonPath("$.markers[0].description").value("표: 값"));

        verify(service).edit(new ChunkImageService.EditRequest("insert", "본문", null, "images/chunks/abcd.png", 2, true));
    }

    @Test
    @DisplayName("마커 목록: 본문이 없으면 빈 문자열로 본다")
    void markersTreatsMissingTextAsEmpty() throws Exception {
        when(service.markers("")).thenReturn(List.of());

        mvc.perform(post("/admin/chunk-images/markers").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
