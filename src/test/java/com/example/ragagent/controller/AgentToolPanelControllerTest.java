package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.repository.AgentToolCallRepository;
import com.example.ragagent.security.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /admin/agent-tools} — 관리자만 읽을 수 있고, 요약을 표로 그리며, 기간은 허용 값으로 눌린다. */
@WebMvcTest(value = AgentToolPanelController.class, properties = {"app.auth.enabled=true", "app.agent.enabled=true"})
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@ResourceLock("global-state")
class AgentToolPanelControllerTest {

    private static final AppUserDetails ADMIN =
            new AppUserDetails("u1", "admin@example.com", null, "Admin", "ADMIN", true, false);
    private static final AppUserDetails MEMBER =
            new AppUserDetails("u2", "member@example.com", null, "Member", "USER", true, false);

    @Autowired MockMvc mvc;

    @MockitoBean AgentToolCallRepository calls;
    @MockitoBean AuditLogger auditLogger;
    @MockitoBean AppProperties props;                         // SecurityConfig 의존
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel;   // WebConfig 의존

    @Test
    @DisplayName("관리자에게 Tool 별 표와 폴백 비율을 그린다")
    void rendersSummary() throws Exception {
        when(calls.summary(14)).thenReturn(new AgentToolCallRepository.Summary(10, 6, 2, 1, 1, 0, 900L, 1500L,
                List.of(new AgentToolCallRepository.ToolRow("queryCatalog", 4, 4, 3, 1, 0, 30L, 80L),
                        new AgentToolCallRepository.ToolRow("analyzeImpact", 2, 2, 1, 0, 1, 50L, 90L))));

        String html = mvc.perform(get("/admin/agent-tools").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("queryCatalog")))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("analyzeImpact").contains("40.0%");   // 폴백 (10-6)/10
        assertThat(html).contains("75.0%");                              // queryCatalog 성공률 3/4
        assertThat(html).doesNotContain("Tool 선택 꺼짐");               // 이 테스트는 app.agent.enabled=true
    }

    @Test
    @DisplayName("기록이 없으면 빈 안내만 — 오류 없이")
    void emptyState() throws Exception {
        when(calls.summary(14)).thenReturn(new AgentToolCallRepository.Summary(0, 0, 0, 0, 0, 0, null, null, List.of()));

        mvc.perform(get("/admin/agent-tools").with(user(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("기록된 Tool 선택이 없다")));
    }

    @Test
    @DisplayName("기간은 허용 값(1·7·14·30·90)으로 눌린다")
    void clampsDays() throws Exception {
        when(calls.summary(90)).thenReturn(new AgentToolCallRepository.Summary(0, 0, 0, 0, 0, 0, null, null, List.of()));

        mvc.perform(get("/admin/agent-tools").param("days", "400").with(user(ADMIN))).andExpect(status().isOk());

        verify(calls).summary(90);
        assertThat(AgentToolPanelController.clampDays(10)).isEqualTo(7);
        assertThat(AgentToolPanelController.clampDays(-5)).isEqualTo(1);
    }

    @Test
    @DisplayName("가입만 한 일반 사용자는 읽을 수 없다")
    void membersAreForbidden() throws Exception {
        mvc.perform(get("/admin/agent-tools").with(user(MEMBER))).andExpect(status().isForbidden());
    }
}
