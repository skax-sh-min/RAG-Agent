package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.workflow.ChangeApplyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 수정안 검토 화면(6e) — 관리자만, 3분할 화면이 그려지고, 강조가 서버에서 오며, 동시 편집·일괄 적용 거절은 409 다. */
@WebMvcTest(value = AdminChangeReviewController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class AdminChangeReviewControllerTest {

    private static final AppUserDetails ADMIN = new AppUserDetails("root", "root@example.com", null, "관리자", "ADMIN", true, false);
    private static final AppUserDetails USER = new AppUserDetails("u1", "u1@example.com", null, "사용자", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean ChangeApplyService applyService;
    @MockitoBean ChangeJobRepository jobs;
    @MockitoBean ChangeProposalRepository proposals;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AppProperties props;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;

    private static final String BEFORE = "#### 7.10.6 온라인에서 배치 호출\n| callBatchNow | 즉시 호출 |";
    private static final String AFTER = "#### 7.10.6 온라인에서 배치 호출\n> **사용 중단** — callBatchNow <script>x</script>\n| callBatchNow | 즉시 호출 |";

    private static ChangeJobRepository.Job job(String type) {
        return new ChangeJobRepository.Job(5, ChangeJobRepository.KIND_MAINTENANCE, "callBatchNow 사용 중단", "root", "done", "{}", null,
                "2026-11-20 01:00:00", "2026-11-20 01:00:00", "{\"mode\":\"nl\",\"type\":\"" + type + "\"}");
    }

    private static ChangeProposalRepository.Proposal proposal(long id, String status) {
        return new ChangeProposalRepository.Proposal(id, 5, "d1", "guide.pdf", "c" + id, "edit", BEFORE, AFTER, "h",
                null, "맥락", null, null, "안내", status, null, null, null, null, "t",
                null, "describes", "callBatchNow(", null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn("root");
        when(jobs.find(5L)).thenReturn(Optional.of(job("deprecate")));
        when(proposals.listByJob(5L)).thenReturn(List.of(proposal(1, "pending"), proposal(2, "applied")));
        when(proposals.find(1L)).thenReturn(Optional.of(proposal(1, "pending")));
        when(applyService.batchTargets(eq(5L), any())).thenReturn(List.of(proposal(1, "pending")));
    }

    @Test
    @DisplayName("일반 사용자는 검토 화면에 들어올 수 없고 확인도 실행되지 않는다")
    void nonAdminIsRefused() throws Exception {
        mvc.perform(get("/admin/change-jobs/5/review").with(user(USER))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/change-jobs/5/review/1/confirm").param("text", AFTER).with(user(USER)).with(csrf()))
                .andExpect(status().isForbidden());
        verify(applyService, never()).confirmOne(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("화면 — 첫 대기 항목이 열리고, 수정 후 칸에 강조 층과 편집기가, 본문은 이스케이프되어 그려진다")
    void pageRendersThreePanes() throws Exception {
        mvc.perform(get("/admin/change-jobs/5/review").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("review-grid")))
                .andExpect(content().string(containsString("id=\"rv-backdrop\"")))
                .andExpect(content().string(containsString("id=\"rv-text\"")))
                .andExpect(content().string(containsString("rv-l rv-add")))
                .andExpect(content().string(containsString("&lt;script&gt;x&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>x</script>"))))
                .andExpect(content().string(containsString("/admin/change-jobs/5/review/batch")))
                .andExpect(content().string(containsString(TextHash.sha256(AFTER))));
    }

    @Test
    @DisplayName("원문을 바꾸는 작업에서는 일괄 적용 버튼 대신 하나씩 확인하라는 안내")
    void noBatchButtonForRevise() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(job("revise")));
        mvc.perform(get("/admin/change-jobs/5/review").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/admin/change-jobs/5/review/batch"))));
    }

    @Test
    @DisplayName("강조 갱신 — 저장하지 않고 수정 전 칸 + 강조 층(oob)을 돌려준다")
    void diffReturnsBothPanes() throws Exception {
        mvc.perform(post("/admin/change-jobs/5/review/1/diff").param("text", BEFORE + "\n추가 줄").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hx-swap-oob")))
                .andExpect(content().string(containsString("rv-l rv-add")));
        verify(applyService, never()).saveDraft(anyLong(), anyLong(), any(), any(), any());
        verify(applyService, never()).confirmOne(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("확인 — 서비스에 넘기고 다음 항목을 돌려주며 목록을 다시 읽게 한다; 검사에 걸리면 같은 항목에 사유")
    void confirmAndValidation() throws Exception {
        when(applyService.confirmOne(5L, 1L, AFTER, "hh", "root")).thenReturn(null);
        mvc.perform(post("/admin/change-jobs/5/review/1/confirm").param("text", AFTER).param("expectedHash", "hh")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "review-list-changed"));
        verify(applyService).confirmOne(5L, 1L, AFTER, "hh", "root");

        when(applyService.confirmOne(eq(5L), eq(1L), eq("깨짐"), anyString(), anyString())).thenReturn("그림 마커가 사라졌습니다");
        mvc.perform(post("/admin/change-jobs/5/review/1/confirm").param("text", "깨짐").param("expectedHash", "hh")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("그림 마커가 사라졌습니다")))
                .andExpect(content().string(containsString(">깨짐</textarea>")));
    }

    @Test
    @DisplayName("동시 편집과 일괄 적용 거절은 409 + 사유")
    void conflictsAre409() throws Exception {
        when(applyService.confirmOne(anyLong(), anyLong(), any(), any(), any())).thenThrow(new ChangeApplyService.ProposalConflictException());
        mvc.perform(post("/admin/change-jobs/5/review/1/confirm").param("text", AFTER).with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict());

        when(applyService.approveBatch(anyLong(), any(), any())).thenThrow(new ChangeApplyService.BatchNotAllowedException());
        mvc.perform(post("/admin/change-jobs/5/review/batch").with(user(ADMIN)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("하나씩")));
    }

    @Test
    @DisplayName("임시 저장 — 성공 204, 불변식 위반 422 + 사유")
    void draft() throws Exception {
        when(applyService.saveDraft(5L, 1L, "ok", "hh", "root")).thenReturn(null);
        mvc.perform(post("/admin/change-jobs/5/review/1/draft").param("text", "ok").param("expectedHash", "hh")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isNoContent());
        when(applyService.saveDraft(5L, 1L, "bad", "hh", "root")).thenReturn("펜스");
        mvc.perform(post("/admin/change-jobs/5/review/1/draft").param("text", "bad").param("expectedHash", "hh")
                        .with(user(ADMIN)).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("펜스")));
    }

    @Test
    @DisplayName("대기 항목이 없으면 요약을 보인다")
    void summaryWhenNothingPending() throws Exception {
        when(proposals.listByJob(5L)).thenReturn(List.of(proposal(2, "applied")));
        mvc.perform(get("/admin/change-jobs/5/review").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("review-done")));
    }
}
