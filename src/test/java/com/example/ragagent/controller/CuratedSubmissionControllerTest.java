package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.CuratedSubmissionRepository.Submission;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.service.CuratedSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 청크 추가 게시판 (사용자 측) — 페이지 렌더 + 폼 POST 계약.
 *
 * <p>{@code curated-submissions.html} 은 base.html 을 decorate 하므로 이 테스트는 레이아웃까지
 * 함께 렌더한다(상태 뱃지·본문 카운터 등 Thymeleaf 문법 회귀를 잡는 목적).
 */
@WebMvcTest(value = CuratedSubmissionController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class CuratedSubmissionControllerTest {

    private static final String USER = "u1";

    /** base.html reads {@code principal.displayName} — a plain @WithMockUser principal has none. */
    private static final AppUserDetails PRINCIPAL =
            new AppUserDetails(USER, "u1@local", "hash", "사용자", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean CuratedSubmissionService service;
    @MockitoBean com.example.ragagent.service.CuratedImageStore imageStore;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AppProperties props;                          // GlobalModelAdvice + SecurityConfig
    @MockitoBean ThreadContextResolver threadContextResolver;  // WebMvcConfig
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;  // WebConfig.chatClient()

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn(USER);
        when(service.chunkSizeForBody()).thenReturn(800);
        when(service.listMine(anyString(), anyInt(), anyInt())).thenReturn(List.of());
    }

    /** {@code curatedActive}/{@code curatedFailed} 로 전부/전무 상태를 만든다(총 청크 2개 기준). */
    private static Submission submission(String status, String reviewNote,
                                         int curatedActive, int curatedFailed) {
        return new Submission(1L, USER, "제안 제목", "제안 본문", status, "admin", reviewNote,
                7L, "2026-01-01", "2026-01-01", "2026-01-02", null, "인프라", null, null,
                "approved".equals(status) ? 2 : 0, "approved".equals(status) ? 2 : 0, curatedActive, curatedFailed);
    }

    @Test
    @DisplayName("GET — 페이지를 열면 읽음 처리되고 본문 길이 상한이 노출된다")
    void page_marksReadAndExposesLimit() throws Exception {
        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("800")));

        verify(service).markAllReadForAuthor(USER);
        verify(service).listMine(USER, 0, 20);
    }

    @Test
    @DisplayName("GET ?fromTurn — 좋아요한 답변을 서버가 읽어 폼을 채우고, 출처를 숨은 필드로 싣는다")
    void page_prefillsFromChatTurn() throws Exception {
        when(service.prefillFromTurn(USER, "t1", 42L)).thenReturn(java.util.Optional.of(
                new CuratedSubmissionService.TurnPrefill(42L, "t1", "원래 질문", "원래 답변 본문",
                        "인프라", 0, "DN")));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko")
                        .param("fromThread", "t1").param("fromTurn", "42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("원래 답변 본문")))
                .andExpect(content().string(containsString("[DN]")))
                .andExpect(content().string(containsString("name=\"sourceTurnId\"")))
                .andExpect(content().string(containsString("채팅 답변에서 가져왔습니다")));
    }

    @Test
    @DisplayName("GET ?fromTurn — 같은 턴에 살아 있는 제안이 있으면 두 번째 초안을 열지 않는다")
    void page_duplicateProposal_pointsAtExisting() throws Exception {
        when(service.findLiveProposalForTurn(42L))
                .thenReturn(java.util.Optional.of(submission("pending", null, 0, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko")
                        .param("fromThread", "t1").param("fromTurn", "42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이미 지식 제안으로 등록")));

        verify(service, never()).prefillFromTurn(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("POST — 출처 턴을 그대로 서비스에 넘긴다 (소유권 확인은 서비스의 몫)")
    void submit_forwardsSourceTurn() throws Exception {
        mvc.perform(post("/curated/submissions").with(csrf()).with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "본문")
                        .param("sourceThreadId", "t1").param("sourceTurnId", "42"))
                .andExpect(status().is3xxRedirection());

        verify(service).submit(USER, "제목", "본문", List.of(), "t1", 42L);
    }

    @Test
    @DisplayName("GET — 반려 건은 사유 전문과 반려 뱃지를 함께 렌더한다")
    void page_rendersRejectionReason() throws Exception {
        when(service.listMine(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("rejected", "출처가 불분명합니다", 0, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("출처가 불분명합니다")))
                .andExpect(content().string(containsString("반려")));
    }

    @Test
    @DisplayName("GET — 승인 후 회수된 건은 '회수됨'으로 표시된다")
    void page_rendersRevokedStatus() throws Exception {
        when(service.listMine(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("approved", null, 0, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("회수됨")));
    }

    @Test
    @DisplayName("GET — 임베딩 실패 건은 경고 안내가 붙는다")
    void page_rendersEmbedFailureHint() throws Exception {
        when(service.listMine(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("approved", null, 2, 1)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("임베딩에 실패")));
    }

    @Test
    @DisplayName("POST — 등록 성공 시 성공 플래시와 함께 목록으로 리다이렉트")
    void submit_success_redirectsWithFlash() throws Exception {
        when(service.submit(USER, "제목", "본문", java.util.List.of())).thenReturn(3L);

        mvc.perform(post("/curated/submissions").with(csrf()).with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "본문"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/curated/submissions"))
                .andExpect(flash().attributeExists("submitSuccess"));
    }

    @Test
    @DisplayName("POST — 검증 실패 시 오류 메시지와 입력 초안을 되돌려준다 (JSON 오류가 아니라 플래시)")
    void submit_validationFailure_returnsDraft() throws Exception {
        when(service.submit(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("본문이 너무 깁니다 (최대 800자, 입력: 900자)"));

        mvc.perform(post("/curated/submissions").with(csrf()).with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "너무 긴 본문"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/curated/submissions"))
                .andExpect(flash().attribute("submitError",
                        containsString("본문이 너무 깁니다")))
                .andExpect(flash().attribute("draftTitle", "제목"))
                .andExpect(flash().attribute("draftBody", "너무 긴 본문"));
    }

    @Test
    @DisplayName("POST /{id}/withdraw — 작성자 스코프로 위임하고, 실패 시 오류 플래시")
    void withdraw_delegatesAndFlashes() throws Exception {
        when(service.withdraw(1L, USER)).thenReturn(true);
        mvc.perform(post("/curated/submissions/1/withdraw").with(csrf()).with(user(PRINCIPAL)))
                .andExpect(flash().attributeExists("submitSuccess"));

        when(service.withdraw(2L, USER)).thenReturn(false);
        mvc.perform(post("/curated/submissions/2/withdraw").with(csrf()).with(user(PRINCIPAL)))
                .andExpect(flash().attributeExists("submitError"));

        verify(service).withdraw(1L, USER);
        verify(service).withdraw(2L, USER);
    }

    @Test
    @DisplayName("GET /unread-count — 호출자 본인의 미확인 알림 수만 반환 (읽음 처리는 하지 않음)")
    void unreadCount_scopedToCallerWithoutMarkingRead() throws Exception {
        when(service.countUnreadForAuthor(USER)).thenReturn(2);

        mvc.perform(get("/curated/submissions/unread-count").with(user(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":2}"));

        // 배지 폴링이 알림을 지워버리면 사용자가 목록을 보기도 전에 사라진다.
        verify(service, never()).markAllReadForAuthor(anyString());
        verify(service, never()).listMine(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("POST — CSRF 토큰이 없으면 403")
    void submit_withoutCsrf_forbidden() throws Exception {
        mvc.perform(post("/curated/submissions").with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "본문"))
                .andExpect(status().isForbidden());

        verify(service, never()).submit(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("GET — 페이지네이션 offset 이 서비스로 그대로 전달된다")
    void page_passesOffset() throws Exception {
        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko").param("offset", "40"))
                .andExpect(status().isOk());

        verify(service).listMine(USER, 40, 20);
        verify(service, never()).withdraw(anyLong(), anyString());
    }
}
