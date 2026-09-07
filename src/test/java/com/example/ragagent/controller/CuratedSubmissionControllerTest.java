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

import static org.assertj.core.api.Assertions.assertThat;
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
        when(service.listMine(anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    /** {@code curatedActive}/{@code curatedFailed} 로 전부/전무 상태를 만든다(총 청크 2개 기준). */
    private static Submission submission(String status, String reviewNote,
                                         int curatedActive, int curatedFailed) {
        return new Submission(1L, USER, "제안 제목", "제안 본문", status, "admin", reviewNote,
                7L, "2026-01-01", "2026-01-01", "2026-01-02", null, "인프라", null, null, null, null,
                "approved".equals(status) ? 2 : 0, "approved".equals(status) ? 2 : 0, curatedActive, curatedFailed);
    }

    @Test
    @DisplayName("GET — 작성/미리보기 2컬럼 뼈대가 서버 마크업에 있다 (넓은 화면 분기의 전제)")
    void page_carriesTwoColumnScaffolding() throws Exception {
        String html = mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 넓은 화면에서 두 패널을 나란히 놓는 결정은 스크립트가 하지만, 그 대상이 되는 뼈대는
        // 서버 마크업에 있어야 한다. 이 셋 중 하나라도 사라지면 스크립트는 조용히 아무것도
        // 하지 않고 화면은 좁은 화면 모양 그대로 남는다 — 오류가 나지 않아 눈치채기 어렵다.
        assertThat(html)
                .as("2컬럼으로 묶일 컨테이너")
                .contains("id=\"submission-body-panes\"");
        assertThat(html)
                .as("넓은 화면에서 숨겨야 할 탭 그룹")
                .contains("id=\"body-view-tabs\"");
        assertThat(html)
                .as("나란히 놓일 미리보기 패널")
                .contains("id=\"submission-preview\"");
    }

    /**
     * 요약·키워드 칸은 화면을 채우려고 있는 것이 아니다 — 승인되면 각각
     * {@code MetaKey.CHUNK_CONTEXT}/{@code EXCERPT_KEYWORDS} 로 실려 BM25 축이 읽는다.
     * 마크업이 사라지면 저자가 값을 넣을 방법이 없어지고, 그 축은 조용히 예전처럼 빈 채로 돈다.
     */
    @Test
    @DisplayName("GET — 요약·키워드 입력란과 자동 생성 버튼이 폼에 있다")
    void page_carriesEnrichmentFields() throws Exception {
        String html = mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"submission-summary\"");
        assertThat(html).contains("id=\"submission-keywords\"");
        assertThat(html).contains("id=\"submission-enrich-btn\"");
        assertThat(html)
                .as("두 칸의 name 이 없으면 폼 POST 가 값을 싣지 않는다")
                .contains("name=\"summary\"").contains("name=\"keywords\"");
    }

    @Test
    @DisplayName("POST — 요약·키워드를 서비스로 그대로 넘긴다")
    void submit_forwardsEnrichmentFields() throws Exception {
        mvc.perform(post("/curated/submissions").with(csrf()).with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "본문")
                        .param("summary", "한 줄 요약").param("keywords", "배포, 인프라"))
                .andExpect(status().is3xxRedirection());

        verify(service).submit(USER, "제목", "본문", List.of(), null, null, "한 줄 요약", "배포, 인프라");
    }

    /** 검증 실패에서 초안을 되돌릴 때 이 둘도 함께여야 한다 — 아니면 자동 생성을 다시 눌러야 한다. */
    @Test
    @DisplayName("POST — 검증 실패 시 요약·키워드 초안도 함께 되돌려준다")
    void submit_returnsEnrichmentDraftOnFailure() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("본문을 입력해 주세요."))
                .when(service).submit(anyString(), anyString(), anyString(), any(), any(), any(), any(), any());

        mvc.perform(post("/curated/submissions").with(csrf()).with(user(PRINCIPAL))
                        .param("title", "제목").param("body", "본문")
                        .param("summary", "요약 초안").param("keywords", "키워드 초안"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("draftSummary", "요약 초안"))
                .andExpect(flash().attribute("draftKeywords", "키워드 초안"));
    }

    @Test
    @DisplayName("POST /enrich — 본문과 현재 값을 넘기고 결과를 그대로 돌려준다")
    void enrich_returnsGeneratedFields() throws Exception {
        when(service.enrich("본문", "", "")).thenReturn(
                new CuratedSubmissionService.Enrichment("생성된 요약", "생성된 키워드", true));

        mvc.perform(post("/curated/submissions/enrich").with(csrf()).with(user(PRINCIPAL))
                        .contentType("application/json")
                        .content("{\"body\":\"본문\",\"summary\":\"\",\"keywords\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("생성된 요약")))
                .andExpect(content().string(containsString("생성된 키워드")));

        verify(service).enrich("본문", "", "");
    }

    @Test
    @DisplayName("POST /enrich — 본문이 없으면 400 + 메시지 (오프캔버스가 그대로 렌더한다)")
    void enrich_blankBody_returns400() throws Exception {
        when(service.enrich(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("본문을 먼저 입력해 주세요."));

        mvc.perform(post("/curated/submissions/enrich").with(csrf()).with(user(PRINCIPAL))
                        .contentType("application/json").content("{\"body\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("본문을 먼저 입력해 주세요.")));
    }

    @Test
    @DisplayName("GET — 페이지를 열면 읽음 처리되고 본문 길이 상한이 노출된다")
    void page_marksReadAndExposesLimit() throws Exception {
        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("800")));

        verify(service).markAllReadForAuthor(USER);
        verify(service).listMine(USER, null, 0, 20);
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

        verify(service).submit(USER, "제목", "본문", List.of(), "t1", 42L, null, null);
    }

    @Test
    @DisplayName("GET — 등록 완료 건에도 수정·철회 버튼이 붙는다 (§10.11 이전에는 검토 대기만 가능했다)")
    void page_offersEditAndWithdrawOnApproved() throws Exception {
        when(service.listMine(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("approved", null, 2, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("openSubmissionEdit")))
                .andExpect(content().string(containsString("/withdraw")))
                // 등록본이 살아 있는 제안의 철회는 "검색에서도 빠진다"고 먼저 말한다.
                .andExpect(content().string(containsString("검색에서도 즉시 제외")));
    }

    @Test
    @DisplayName("GET ?status — 상태 필터를 서비스로 넘긴다 (all 은 필터 없음)")
    void page_appliesStatusFilter() throws Exception {
        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("status", "approved"))
                .andExpect(status().isOk());
        verify(service).listMine(USER, "approved", 0, 20);

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("status", "all"))
                .andExpect(status().isOk());
        verify(service).listMine(USER, null, 0, 20);
    }

    @Test
    @DisplayName("GET /{id}/detail — 자기 제안만 열린다")
    void detail_scopedToAuthor() throws Exception {
        when(service.findById(1L)).thenReturn(java.util.Optional.of(submission("pending", null, 0, 0)));
        mvc.perform(get("/curated/submissions/1/detail").with(user(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("제안 본문")));

        when(currentUser.userId()).thenReturn("someone-else");
        mvc.perform(get("/curated/submissions/1/detail").with(user(PRINCIPAL)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{id} — 저장은 200, 이미 처리된 제안은 409, 검증 실패는 400 + 메시지")
    void update_reportsOutcomeInline() throws Exception {
        when(service.updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);
        mvc.perform(post("/curated/submissions/1").with(csrf()).with(user(PRINCIPAL))
                        .contentType("application/json")
                        .content("{\"title\":\"제목\",\"body\":\"본문\",\"tags\":\"인프라\"}"))
                .andExpect(status().isOk());
        verify(service).updateByAuthor(1L, USER, "제목", "본문", List.of("인프라"), null, null);

        when(service.updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(false);
        mvc.perform(post("/curated/submissions/1").with(csrf()).with(user(PRINCIPAL))
                        .contentType("application/json").content("{\"title\":\"제목\",\"body\":\"본문\"}"))
                .andExpect(status().isConflict());

        when(service.updateByAuthor(anyLong(), anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("제목이 너무 깁니다"));
        mvc.perform(post("/curated/submissions/1").with(csrf()).with(user(PRINCIPAL))
                        .contentType("application/json").content("{\"title\":\"제목\",\"body\":\"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("제목이 너무 깁니다")));
    }

    @Test
    @DisplayName("GET — 반려 건은 사유 전문과 반려 뱃지를 함께 렌더한다")
    void page_rendersRejectionReason() throws Exception {
        when(service.listMine(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("rejected", "출처가 불분명합니다", 0, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("출처가 불분명합니다")))
                .andExpect(content().string(containsString("반려")));
    }

    @Test
    @DisplayName("GET — 승인 후 회수된 건은 '회수됨'으로 표시된다")
    void page_rendersRevokedStatus() throws Exception {
        when(service.listMine(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(submission("approved", null, 0, 0)));

        mvc.perform(get("/curated/submissions").with(user(PRINCIPAL)).param("lang", "ko"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("회수됨")));
    }

    @Test
    @DisplayName("GET — 임베딩 실패 건은 경고 안내가 붙는다")
    void page_rendersEmbedFailureHint() throws Exception {
        when(service.listMine(anyString(), any(), anyInt(), anyInt()))
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
        when(service.submit(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
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
        verify(service, never()).listMine(anyString(), any(), anyInt(), anyInt());
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

        verify(service).listMine(USER, null, 40, 20);
        verify(service, never()).withdraw(anyLong(), anyString());
    }
}
