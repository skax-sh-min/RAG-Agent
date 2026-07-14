package com.example.ragagent.controller;

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
    @DisplayName("GET /settings — 전체 페이지(레이아웃+그룹+프로바이더)가 렌더된다")
    void settingsPageRenders() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        SettingItem fixed = new SettingItem(null, "settings.item.top-k", "7",
                "text", false, false, null, null, null, null);
        SettingsView view = new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", true, false, null)),
                "COST_FIRST", "0.0", "6000", "bge-m3", "1024", "chroma",
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
}
