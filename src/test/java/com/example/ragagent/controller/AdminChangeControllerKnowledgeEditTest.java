package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.security.CurrentUser;
import com.example.ragagent.workflow.ChangeApplyService;
import com.example.ragagent.workflow.EditIntent;
import com.example.ragagent.workflow.KnowledgeEditWorkflow;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

/** 자연어 지식 수정(AGENT_MCP_DESIGN §10.6·§10.7) 의 요청 갈래·리포트·추가 후보 화면. */
@WebMvcTest(value = AdminChangeController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class AdminChangeControllerKnowledgeEditTest {

    private static final AppUserDetails ADMIN = new AppUserDetails("root", "root@example.com", null, "관리자", "ADMIN", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean MaintenanceWorkflow workflow;
    @MockitoBean KnowledgeEditWorkflow knowledgeEdit;
    @MockitoBean ChangeApplyService applyService;
    @MockitoBean ChangeJobRepository jobs;
    @MockitoBean ChangeProposalRepository proposals;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AppProperties props;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;

    @BeforeEach
    void setUp() {
        when(currentUser.userId()).thenReturn("root");
        when(knowledgeEdit.start(any(), any(), any(), any())).thenReturn(new KnowledgeEditWorkflow.Started(9, "running"));
        when(workflow.start(any(), any(), any(), any(), anyBoolean(), anyBoolean(), any())).thenReturn(new MaintenanceWorkflow.Started(8, "running"));
    }

    @Test
    @DisplayName("유형을 고르면 그 의도로 자연어 워크플로, 지시문만이면 해석, 바꿀 말·새 말이면 글자 치환")
    void requestRoutes() throws Exception {
        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf())
                        .param("type", "add_note").param("subject", "B").param("content", "호출 전에 초기화한다"))
                .andExpect(header().string("Location", "/admin/change-jobs/9"));
        ArgumentCaptor<EditIntent> intent = ArgumentCaptor.forClass(EditIntent.class);
        verify(knowledgeEdit).start(eq("root"), eq(""), intent.capture(), any());
        assertThat(intent.getValue().type()).isEqualTo("add_note");
        assertThat(intent.getValue().content()).isEqualTo("호출 전에 초기화한다");

        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("instruction", "A 기능은 더 이상 사용되지 않습니다"))
                .andExpect(header().string("Location", "/admin/change-jobs/9"));
        verify(knowledgeEdit).start(eq("root"), eq("A 기능은 더 이상 사용되지 않습니다"), eq(null), any());

        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf())
                        .param("instruction", "x").param("from", "PostgreSQL").param("to", "Oracle").param("caseVariants", "true"))
                .andExpect(header().string("Location", "/admin/change-jobs/8"));
        verify(workflow).start(eq("root"), eq("x"), eq("PostgreSQL"), eq("Oracle"), eq(false), eq(true), any());

        mvc.perform(post("/admin/change-requests").with(user(ADMIN)).with(csrf()).param("type", "replace"))
                .andExpect(header().string("Location", "/admin/change-jobs?error=invalid"));
    }

    @Test
    @DisplayName("자연어 작업 화면 — 유형·대상·요약과 추가 적용 후보·언급만 목록(이미 수정안이 있는 청크는 고를 수 없다)")
    void rendersTheEditReport() throws Exception {
        String report = """
                {"mode":"nl","instruction":"B 주의","type":"add_note","subject":"B","content":"호출 전에 초기화한다","replacement":null,
                 "mentions":3,"truncated":false,"tooShort":false,"candidates":3,"judged":3,"unjudged":0,"proposals":1,"manualCurated":0,
                 "fallbacks":1,
                 "mentionsOnly":[{"chunkId":"m1","docId":"d1","file":"list.pdf","role":"mentions","quote":"| B | 운영 |","score":null}],
                 "extraCandidates":[{"chunkId":"c2","docId":"d1","file":"guide.pdf","role":"describes","quote":"B 는 캐시한다.","score":null},
                                    {"chunkId":"c1","docId":"d1","file":"guide.pdf","role":"describes","quote":"B 는 호출한다.","score":null}],
                 "discarded":[]}""";
        when(jobs.find(5L)).thenReturn(Optional.of(new ChangeJobRepository.Job(5, "maintenance", "B 주의", "root", "done", report, null,
                "t", "t", "{\"mode\":\"nl\",\"type\":\"add_note\",\"subject\":\"B\"}")));
        when(proposals.listByJob(5L)).thenReturn(List.of(new ChangeProposalRepository.Proposal(1, 5, "d1", "guide.pdf", "c1", "edit",
                "B 는 호출한다.", "B 는 호출한다.\n\n> **주의** — 호출 전에 초기화한다", "h", null, null, null, null, "주의 사항 추가: B",
                "pending", null, null, null, null, "t")));

        mvc.perform(get("/admin/change-jobs/5").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("주의 사항 추가")))
                .andExpect(content().string(containsString("후보 3곳 중 3곳을 판정해 수정안 1개를 만들었습니다")))
                .andExpect(content().string(containsString("지시문 문장을 그대로 넣은 곳 1개")))
                .andExpect(content().string(containsString("추가로 적용할 수 있는 청크")))
                .andExpect(content().string(containsString("value=\"c2\"")))
                .andExpect(content().string(containsString("대상을 언급만 하는 청크")))
                .andExpect(content().string(containsString("/admin/change-jobs/5/chunks/propose")))
                .andExpect(content().string(containsString("수정안 있음")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("null → null"))));
    }

    @Test
    @DisplayName("고른 청크로 수정안 만들기 — 관리자·CSRF, 워크플로에 그대로 넘긴다")
    void proposeSelectedChunks() throws Exception {
        when(jobs.find(5L)).thenReturn(Optional.of(new ChangeJobRepository.Job(5, "maintenance", "B", "root", "done", null, null, "t", "t")));
        when(knowledgeEdit.proposeForChunks(eq(5L), any(), any())).thenReturn(2);

        mvc.perform(post("/admin/change-jobs/5/chunks/propose").with(user(ADMIN)).param("chunkIds", "c2", "m1"))
                .andExpect(status().isForbidden());
        verify(knowledgeEdit, never()).proposeForChunks(eq(5L), any(), any());
        mvc.perform(post("/admin/change-jobs/5/chunks/propose").with(user(ADMIN)).with(csrf()).param("chunkIds", "c2", "m1"))
                .andExpect(header().string("Location", "/admin/change-jobs/5"));
        verify(knowledgeEdit).proposeForChunks(eq(5L), eq(List.of("c2", "m1")), any());
    }
}
