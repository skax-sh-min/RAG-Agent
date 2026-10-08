package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.ChunkReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §10.14 신고 접수 엔드포인트의 계약 — 접수 200 / 중복 409 / 검증 실패 400.
 *
 * <p>중복이 400 이 아니라 409 인 것이 이 테스트의 핵심이다: 화면 문구가 "실패"가 아니라 "이미
 * 신고하셨습니다"여야 하고, 그 구분은 상태 코드로만 전달된다.
 */
@WebMvcTest(value = ChunkReportController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class ChunkReportControllerTest {

    private static final AppUserDetails USER =
            new AppUserDetails("u1", "u1@example.com", null, "사용자", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean ChunkReportService chunkReportService;
    @MockitoBean AppProperties props;                 // SecurityConfig / advice 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;  // WebConfig.chatClient 의존

    @BeforeEach
    void setUp() throws Exception {
        when(threadContextResolver.supportsParameter(any())).thenReturn(true);
        when(threadContextResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new ThreadContext("t1", "u1", Locale.KOREAN));
    }

    @Test
    @DisplayName("POST /ui/chunk-reports: 접수되면 200 + id")
    void reports() throws Exception {
        when(chunkReportService.report(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ChunkReportService.ReportResult(true, 7L));

        mvc.perform(post("/ui/chunk-reports").with(user(USER)).with(csrf())
                        .param("chunkId", "c1")
                        .param("threadId", "t1")
                        .param("turnId", "3")
                        .param("reason", "WRONG")
                        .param("comment", "포트가 틀렸습니다"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("created")));

        verify(chunkReportService).report("u1", "c1", "t1", 3L, "WRONG", "포트가 틀렸습니다");
    }

    @Test
    @DisplayName("이미 신고한 청크는 409 — 오류가 아니라 '이미 접수됨'이다")
    void duplicateIsConflict() throws Exception {
        when(chunkReportService.report(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ChunkReportService.ReportResult(false, -1L));

        mvc.perform(post("/ui/chunk-reports").with(user(USER)).with(csrf())
                        .param("chunkId", "c1")
                        .param("threadId", "t1")
                        .param("reason", "WRONG")
                        .param("comment", "틀렸습니다"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("duplicate")));
    }

    @Test
    @DisplayName("검증 실패는 400 + 사용자에게 보일 문구(ProblemDetail.detail)")
    void validationFailureIsBadRequest() throws Exception {
        when(chunkReportService.report(anyString(), anyString(), any(), any(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("어떤 내용이 잘못됐는지 간단히 적어 주세요."));

        mvc.perform(post("/ui/chunk-reports").with(user(USER)).with(csrf())
                        .param("chunkId", "c1")
                        .param("threadId", "t1")
                        .param("reason", "WRONG")
                        .param("comment", " "))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("간단히 적어 주세요")));
    }

    @Test
    @DisplayName("turnId 는 없어도 된다 — 스트리밍 중 턴 id 가 아직 없는 출처에서도 신고할 수 있어야 한다")
    void turnIdIsOptional() throws Exception {
        when(chunkReportService.report(anyString(), anyString(), any(), eq(null), anyString(), any()))
                .thenReturn(new ChunkReportService.ReportResult(true, 1L));

        mvc.perform(post("/ui/chunk-reports").with(user(USER)).with(csrf())
                        .param("chunkId", "c1")
                        .param("threadId", "t1")
                        .param("reason", "OTHER")
                        .param("comment", "이상합니다"))
                .andExpect(status().isOk());
    }
}
