package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.model.SettingsView.ProviderRow;
import com.example.ragagent.model.SettingsView.SettingGroup;
import com.example.ragagent.model.SettingsView.SettingItem;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the {@code fragments/settings-item :: item} template through MockMvc so the
 * novel Thymeleaf bits (i18n message-key preprocessing {@code #{__${item.label}__}}, the number
 * editor, and the HTMX Save/Reset controls) are exercised, not just the controller contract. Uses
 * the fragment endpoint (POST /admin/settings/update), which does not decorate {@code base.html},
 * so it stays free of the layout's principal/requestURI needs.
 */
@WebMvcTest(value = SettingsController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
@ResourceLock("global-state")
class SettingsControllerRenderTest {

    @Autowired MockMvc mvc;

    @MockitoBean SettingsService settingsService;
    @MockitoBean AppProperties props;               // GlobalModelAdvice + SecurityConfig
    @MockitoBean ThreadContextResolver threadContextResolver; // WebMvcConfig
    @MockitoBean org.springframework.ai.chat.model.ChatModel chatModel; // WebConfig.chatClient()

    @Test
    @DisplayName("POST /admin/settings/update — 프래그먼트가 i18n·입력·컨트롤을 포함해 렌더된다")
    void updateFragmentRenders() throws Exception {
        SettingItem item = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "70",
                "number", true, true, null, 1.0, 1000.0, 1.0);
        when(settingsService.editableItem(SettingsKeys.SEARCH_RRF_K)).thenReturn(item);

        mvc.perform(post("/admin/settings/update")
                        .param("key", SettingsKeys.SEARCH_RRF_K)
                        .param("value", "70")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app.search-rrf-k")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"value\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/reset")));

        verify(settingsService).update(SettingsKeys.SEARCH_RRF_K, "70");
    }

    @Test
    @DisplayName("점(.)이 든 키도 hx-include가 행 전체를 가리켜야 한다 — id 셀렉터면 key/value가 전송되지 않음")
    void dottedKeyRowIncludesWholeRow() throws Exception {
        // llm.direct-temperature / indexing.max-concurrent-* 처럼 키에 점이 있으면
        // '#setting-llm.direct-temperature' 는 "id=setting-llm 이면서 class=direct-temperature"로 파싱돼
        // 아무것도 매치되지 않는다 → hx-include가 비어 key·value 둘 다 누락 → 400/500.
        SettingItem item = new SettingItem(SettingsKeys.LLM_DIRECT_TEMPERATURE, "settings.item.direct-temperature",
                "0.1", "number", true, true, null, 0.0, 1.0, 0.01);
        when(settingsService.editableItem(SettingsKeys.LLM_DIRECT_TEMPERATURE)).thenReturn(item);

        String html = mvc.perform(post("/admin/settings/update")
                        .param("key", SettingsKeys.LLM_DIRECT_TEMPERATURE)
                        .param("value", "0.1")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("hx-include=\"closest .setting-row\"");
        assertThat(html).doesNotContain("hx-include=\"#setting-");   // id 셀렉터로 되돌아가면 실패
        assertThat(html).contains("name=\"key\"").contains("name=\"value\"");
    }

    @Test
    @DisplayName("숫자 편집 칸은 허용 범위를 hover 툴팁으로 안내한다 — 경계는 1.0 이 아니라 1 로")
    void numberEditorCarriesARangeTooltip() throws Exception {
        SettingItem item = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "70",
                "number", true, true, null, 1.0, 1000.0, 1.0);
        when(settingsService.editableItem(SettingsKeys.SEARCH_RRF_K)).thenReturn(item);

        // 200 자체가 SpEL 접근 가능 여부를 증명한다 — item.minLabel 이 없으면 Thymeleaf 가 던진다.
        String html = mvc.perform(post("/admin/settings/update")
                        .param("key", SettingsKeys.SEARCH_RRF_K)
                        .param("value", "70")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-bs-toggle=\"tooltip\"");
        // Bootstrap 기본 트리거는 'hover focus' — 값을 고치려고 클릭하면 툴팁이 떠서 남는다.
        assertThat(html).contains("data-bs-trigger=\"hover\"");
        // 문구는 로케일마다 다르지만(ko '~' / en '-') 경계값은 같다. 다듬기가 빠지면 1.0/1000.0 이 된다.
        assertThat(html).containsPattern("1 [~-] 1000");
        assertThat(html).doesNotContain("1.0 ", "??settings.range");
    }

    @Test
    @DisplayName("bool 편집 칸도 같은 자리에 안내를 단다 — 범위가 '두 값 중 하나'일 뿐이다")
    void boolEditorCarriesARangeTooltip() throws Exception {
        SettingItem item = new SettingItem(SettingsKeys.SEARCH_RETRY_ESCALATE, "settings.item.retry-escalate",
                "true", "bool", true, false, null, null, null, null);
        when(settingsService.editableItem(SettingsKeys.SEARCH_RETRY_ESCALATE)).thenReturn(item);

        String html = mvc.perform(post("/admin/settings/update")
                        .param("key", SettingsKeys.SEARCH_RETRY_ESCALATE)
                        .param("value", "true")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<select", "data-bs-toggle=\"tooltip\"", "data-bs-trigger=\"hover\"");
        assertThat(html).doesNotContain("??settings.range");
    }

    @Test
    @DisplayName("POST /admin/settings/provider/toggle — 프로바이더 테이블 프래그먼트가 활성 상태로 렌더된다")
    void toggleProviderFragmentRenders() throws Exception {
        // service reports the provider now disabled → the fragment should show the "enable" control
        when(settingsService.setProviderEnabled("local", false)).thenReturn(
                List.of(new ProviderRow("local", "LOCAL", 1, "qwen", "http://localhost:1234/v1", true, false, null, false)));

        AppUserDetails admin = new AppUserDetails(
                "id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        mvc.perform(post("/admin/settings/provider/toggle")
                        .param("name", "local")
                        .param("enabled", "false")
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"llm-providers\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/settings/provider/toggle")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("local")));

        verify(settingsService).setProviderEnabled("local", false);
    }

    @Test
    @DisplayName("GET /settings — 전체 페이지(레이아웃+그룹+프로바이더)가 렌더된다")
    void settingsPageRenders() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        SettingItem fixed = new SettingItem(null, "settings.item.top-k", "7",
                "text", false, false, null, null, null, null);
        SettingsView view = new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true)),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot)),
                        new SettingGroup("search_fixed", "settings.group.search_fixed", List.of(fixed))));
        when(settingsService.buildView()).thenReturn(view);

        // A real AppUserDetails principal (base.html reads principal.displayName).
        AppUserDetails principal = new AppUserDetails(
                "id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        mvc.perform(get("/settings").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("COST_FIRST")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("app.search-rrf-k")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("local")));
    }

    @Test
    @DisplayName("GET /settings — LOCAL_ONLY + 관리자여도 'LLM 라우팅' 안내 배너/휘발성 안내 문구는 더 이상 렌더되지 않는다")
    void settingsPage_doesNotRenderRemovedLlmRoutingHints() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        SettingsView view = new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true)),
                "LOCAL_ONLY", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot))));
        when(settingsService.buildView()).thenReturn(view);

        AppUserDetails principal = new AppUserDetails(
                "id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        mvc.perform(get("/settings").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LOCAL_ONLY")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NORMAL/PREMIUM"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("re-enables every provider"))));
    }
}
