package com.example.ragagent.controller;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.SettingsView;
import com.example.ragagent.model.SettingsView.ProviderRow;
import com.example.ragagent.model.SettingsView.SettingGroup;
import com.example.ragagent.model.SettingsView.SettingItem;
import com.example.ragagent.security.AppUserDetails;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.ThinkingPreviewHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
// §6.19.2 — 이 클래스가 렌더를 확인하는 엔드포인트는 /admin/** 아래이고, 전체 인증 모드에서
// 그 경로는 ROLE_ADMIN 이다. 기본 @WithMockUser(ROLE_USER)로는 프래그먼트에 닿기 전에 403 이다.
@WithMockUser(roles = "ADMIN")
@ResourceLock("global-state")
class SettingsControllerRenderTest {

    @Autowired MockMvc mvc;

    @MockitoBean SettingsService settingsService;
    @MockitoBean com.example.ragagent.service.ThinkingPreviewService thinkingPreview;   // §6.29 — 생각 수준 카드
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
                List.of(new ProviderRow("local", "LOCAL", 1, "qwen", "http://localhost:1234/v1", true, false, null, false, "-")));

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
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true, "-")),
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
    @DisplayName("GET /settings — 화면 표시(채팅 글자 크기) 카드는 관리자가 아니어도 맨 위에 렌더된다")
    void settingsPage_displayCardRendersForNonAdmins() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        when(settingsService.buildView()).thenReturn(new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true, "-")),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot)))));
        // 관리자 안내가 실제로 뜨는 유일한 경우 — management-only 모드의 비관리자(GlobalModelAdvice.isAdmin 은 다른
        // 모드에서 늘 참이다). 요청마다 읽는 값이라 컨텍스트가 뜬 뒤에 스텁해도 된다.
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        AppUserDetails user = new AppUserDetails("id-2", "user@local", "", "User", "USER", true, false);

        String html = mvc.perform(get("/settings").with(user(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 네 단계 선택지 + 미리보기 말풍선 + 카드 스크립트(content 조각 안에 있어야 레이아웃에 실린다).
        assertThat(html).contains("id=\"display-settings\"",
                "id=\"chat-font-xs\"", "id=\"chat-font-sm\"", "id=\"chat-font-md\"", "id=\"chat-font-lg\"",
                "id=\"chat-font-preview\" class=\"chat-font-scope\"",
                "document.getElementById('display-settings')");
        // 관리자 안내("관리자만 값을 변경할 수 있습니다")는 서버 설정에만 해당한다 — 화면 표시 카드보다 앞에 오면
        // 누구나 바꿀 수 있는 글자 크기까지 막힌 것처럼 읽힌다.
        assertThat(html).contains("alert alert-secondary");
        assertThat(html.indexOf("id=\"display-settings\"")).isLessThan(html.indexOf("alert alert-secondary"));
    }

    @Test
    @DisplayName("GET /settings — LOCAL_ONLY + 관리자여도 'LLM 라우팅' 안내 배너/휘발성 안내 문구는 더 이상 렌더되지 않는다")
    void settingsPage_doesNotRenderRemovedLlmRoutingHints() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        SettingsView view = new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true, "-")),
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

    // ── §6.29 ⑦ 생각(추론) 수준 카드 ─────────────────────────────────────────────────────────────

    /** 로케일은 계산 시점(서비스의 문구)과 렌더 시점(템플릿의 #{...}) 둘 다 같은 것으로 맞춘다. */
    private static com.example.ragagent.model.ThinkingPreview previewIn(Locale locale) {
        Locale previous = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
        try {
            return ThinkingPreviewHarness.builder().build().service.preview();
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(previous);
        }
    }

    private static int count(String haystack, String regex) {
        Matcher m = Pattern.compile(regex).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    @DisplayName("GET /admin/settings/thinking — 카드가 모든 호출 지점을 행으로, 편집 컨트롤과 네 수준의 칸을 미리 그려 렌더된다(한국어)")
    void thinkingCardRendersEveryRowInKorean() throws Exception {
        // 다른 목을 부르는 계산은 스텁 바깥에서 — when(...) 이 끝나기 전에 다른 목을 건드리면 Mockito 가 스텁을 놓친다
        com.example.ragagent.model.ThinkingPreview preview = previewIn(Locale.KOREAN);
        when(thinkingPreview.preview()).thenReturn(preview);

        String html = mvc.perform(get("/admin/settings/thinking").locale(Locale.KOREAN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"thinking-card\"", "hx-get=\"/admin/settings/thinking\"",
                "thinking-preview-stale from:body", "생각(추론) 수준 — 호출 지점별", "관리자 전용");
        int sites = ThinkingSite.values().length;
        assertThat(count(html, "class=\"thinking-row ")).as("사이트마다 한 행").isEqualTo(sites);
        assertThat(count(html, "name=\"key\" value=\"llm\\.thinking\\.")).as("행마다 설정 키가 hidden 으로 간다").isEqualTo(sites);
        for (ThinkingSite site : ThinkingSite.values()) {
            assertThat(html).contains("app.llm.thinking." + site.id(), "id=\"thinking-" + site.id() + "\"");
        }
        // 네 수준이 모두 미리 그려져 있고(드롭다운은 보이기만 한다), 저장된 수준 하나만 숨겨지지 않는다
        assertThat(count(html, "class=\"thinking-pane\" data-pane=\"off\"")).isEqualTo(sites);
        assertThat(count(html, "class=\"thinking-pane\" data-pane=\"(off|low|medium|high)\" hidden"))
                .as("행마다 네 칸 중 셋은 숨겨져 있다").isEqualTo(sites * 3);
        // settings-item 과 같은 컨트롤 모양: 행 전체를 hx-include 로(id 셀렉터가 아니다 — 키에 점이 있다)
        assertThat(html).contains("hx-include=\"closest .thinking-row\"").doesNotContain("hx-include=\"#thinking-");
        assertThat(html).contains("/admin/settings/update").contains("저장 전");
        // 번들에서 빠진 키는 오류 없이 ??key?? 로만 보인다
        assertThat(html).doesNotContain("??");
        // 숫자가 서버에서 계산돼 있다 — 16k 창의 검증 예약 3,072(낮게)와 입력 예산 11,674
        assertThat(html).contains("3,072").contains("11,674");
    }

    @Test
    @DisplayName("GET /admin/settings/thinking — 영어 로케일에서도 키가 빠지지 않고 영어 문구로 나온다")
    void thinkingCardRendersInEnglish() throws Exception {
        com.example.ragagent.model.ThinkingPreview preview = previewIn(Locale.ENGLISH);
        when(thinkingPreview.preview()).thenReturn(preview);

        String html = mvc.perform(get("/admin/settings/thinking").cookie(new jakarta.servlet.http.Cookie("lang", "en")))   // 앱의 로케일은 쿠키로 정한다(WebConfig)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Thinking (reasoning) level per call site", "Admin only", "Output reservation",
                "Input budget", "Chat answers");
        assertThat(html).doesNotContain("??").doesNotContain("생각(추론)");
    }

    @Test
    @DisplayName("POST /admin/settings/update (생각 수준 키) — 그 사이트의 행이 새로 계산돼 돌아오고, 오버라이드면 [기본값] 이 있다")
    void thinkingRowFragmentAfterUpdate() throws Exception {
        ThinkingPreviewHarness h = ThinkingPreviewHarness.builder()
                .file(ThinkingPreviewHarness.levels(ThinkingSite.EVAL, ThinkingLevel.MEDIUM)).build();
        org.mockito.Mockito.when(h.settings.isOverridden("llm.thinking.eval")).thenReturn(true);
        com.example.ragagent.model.ThinkingPreview.Row row = h.row(ThinkingSite.EVAL);
        when(thinkingPreview.row(ThinkingSite.EVAL)).thenReturn(row);

        String html = mvc.perform(post("/admin/settings/update")
                        .param("key", "llm.thinking.eval").param("value", "medium")
                        .locale(Locale.KOREAN).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(settingsService).update("llm.thinking.eval", "medium");
        assertThat(count(html, "class=\"thinking-row ")).as("카드가 아니라 한 행").isEqualTo(1);
        assertThat(html).contains("id=\"thinking-eval\"", "data-saved=\"medium\"", "오버라이드됨",
                "/admin/settings/reset");
        // 라벨 아래에 늘 보이는 기본값 — 누르기 전에 무엇으로 돌아가는지 안다
        assertThat(html).contains("기본값: 중간");
        assertThat(html).contains("app.llm.thinking.eval");
        assertThat(html).doesNotContain("??");
    }

    @Test
    @DisplayName("GET /settings — 관리자에게만 카드 자리표시자를 그린다(내용은 /admin/settings/thinking 이 채운다)")
    void thinkingPlaceholderIsAdminOnly() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        when(settingsService.buildView()).thenReturn(new SettingsView(
                List.of(new ProviderRow("local", "LOCAL", 0, "qwen", "http://localhost:1234/v1", true, false, null, true, "-")),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot)))));
        AppUserDetails admin = new AppUserDetails("id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        String forAdmin = mvc.perform(get("/settings").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(forAdmin).contains("id=\"thinking-card\"", "hx-get=\"/admin/settings/thinking\"",
                "hx-trigger=\"load, thinking-preview-stale from:body\"");
        // 자리표시자는 접힌 카드와 같은 한 줄(chevron + 제목)이다 — <details> 는 내용이 도착한 뒤의 카드에만 있다.
        assertThat(forAdmin).contains("thinking-chevron").doesNotContain("<details class=\"thinking-card-details\"");
        // 펴 둔 상태를 이어 주는 스크립트는 카드와 함께 관리자에게만 간다
        assertThat(forAdmin).contains("thinking-card-details");

        // 관리 전용 인증의 비관리자 — 읽기까지 막는다. 화면에 자리표시자조차 없다(서버의 /admin 게이트가 진짜 방어선이다).
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        AppUserDetails guest = new AppUserDetails("id-2", "user@local", "", "User", "USER", true, false);
        String forGuest = mvc.perform(get("/settings").with(user(guest)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(forGuest).doesNotContain("thinking-card").doesNotContain("/admin/settings/thinking");
    }

    @Test
    @DisplayName("창 재탐지 응답은 생각 수준 카드를 다시 불러오라는 이벤트(HX-Trigger)를 싣는다 — 창이 바뀌면 예산 숫자가 전부 바뀐다")
    void reprobeTellsTheThinkingCardToReload() throws Exception {
        when(settingsService.reprobeContextWindows()).thenReturn(
                new SettingsService.ReprobeResult(List.of(), false, ""));
        when(settingsService.providerRows()).thenReturn(List.of());

        mvc.perform(post("/admin/settings/context-window/reprobe").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("HX-Trigger", "thinking-preview-stale"));
    }

    // ── 프로바이더 표: 생각 제어 · 상태 · 열 순서 ─────────────────────────────────────────────────

    private static ProviderRow providerRow(String name, SettingsView.ProviderThinking thinking) {
        return new ProviderRow(name, "LOCAL", 1, "qwen", "http://localhost:1234/v1", true, false, null, true,
                "16,384 (탐지됨)", thinking);
    }

    private static ProviderRow providerRow(String name, boolean configured, boolean blocked,
                                           SettingsView.ProviderConnection connection) {
        return new ProviderRow(name, "LOCAL", 1, "qwen", "http://localhost:1234/v1", configured, blocked,
                blocked ? "2026-10-08T00:00:30Z" : null, true, "-", null, connection);
    }

    private String providersFragment(Locale locale) throws Exception {
        return mvc.perform(post("/admin/settings/provider/toggle")
                        .param("name", "local").param("enabled", "true").locale(locale).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("프로바이더 표 — 관리자에게만 '생각 제어' 열이 있고, 값은 사용자 설정 적용 · 서버 설정 사용 · Unknown 셋뿐이다")
    void providerTableShowsTheThinkingControlColumnToAdmins() throws Exception {
        var auto = com.example.ragagent.llm.ThinkingDialect.AUTO;
        var kwargs = com.example.ragagent.llm.ThinkingDialect.TEMPLATE_KWARGS;
        var none = com.example.ragagent.llm.ThinkingDialect.NONE;
        SettingsView.ProviderThinking sends = new SettingsView.ProviderThinking(auto, kwargs,
                com.example.ragagent.llm.ThinkingDialect.Support.ON_OFF, "chat_template_kwargs.enable_thinking", java.util.Set.of());
        SettingsView.ProviderThinking rejected = new SettingsView.ProviderThinking(auto, kwargs,
                com.example.ragagent.llm.ThinkingDialect.Support.ON_OFF, "chat_template_kwargs.enable_thinking",
                java.util.Set.of("chat_template_kwargs"));
        SettingsView.ProviderThinking nothing = new SettingsView.ProviderThinking(auto, none,
                com.example.ragagent.llm.ThinkingDialect.Support.NONE, null, java.util.Set.of());
        when(settingsService.setProviderEnabled("local", true)).thenReturn(List.of(
                providerRow("sends", sends), providerRow("rejected", rejected), providerRow("nothing", nothing),
                providerRow("unregistered", null)));

        String html = providersFragment(Locale.KOREAN);

        assertThat(html).contains("생각 제어");
        assertThat(count(html, ">사용자 설정 적용<")).as("필드를 싣는 서버").isEqualTo(1);
        assertThat(count(html, ">서버 설정 사용<")).as("거부해서 뺀 서버 + 아무것도 싣지 않는 서버").isEqualTo(2);
        assertThat(count(html, ">Unknown<")).as("등록되지 않아 알 수 없는 서버").isEqualTo(1);
        // 예전에 칸을 채우던 필드 이름·방식은 말풍선(title)으로 내려갔다
        assertThat(html).contains("chat_template_kwargs.enable_thinking", "자동(AUTO → template-kwargs)", "켬/끔");
        assertThat(count(html, "거부됨 · 재시작 시 초기화")).as("거부 표시는 거부한 서버에만").isEqualTo(1);
        assertThat(html).contains("providers[N].thinking-dialect");   // 서버 설정 사용 — 싣게 하는 방법
        assertThat(html).doesNotContain("??");
    }

    @Test
    @DisplayName("프로바이더 표 — 열 순서는 상태 → 활성화 → 생각 제어(관리자)이고, 비관리자에게는 생각 제어가 없다")
    void providerTableColumnOrder() throws Exception {
        when(settingsService.setProviderEnabled("local", true)).thenReturn(List.of(providerRow("local", null)));

        String admin = providersFragment(Locale.KOREAN);

        int status = admin.indexOf(">상태</th>");
        int enabled = admin.indexOf(">활성화</th>");
        int thinking = admin.indexOf(">생각 제어</th>");
        assertThat(status).isPositive();
        assertThat(enabled).as("활성화가 생각 제어보다 앞이다").isGreaterThan(status);
        assertThat(thinking).isGreaterThan(enabled);
        // 활성화 칸의 오른쪽 여백(pe-3)은 마지막 열이 아니게 되면 필요 없다 — 마지막이 된 생각 제어가 받는다
        assertThat(admin).contains("<th class=\"pe-3\">생각 제어</th>").doesNotContain("<th class=\"pe-3\">활성화</th>");

        // 비관리자(관리 전용 인증)에게는 생각 제어 열이 없고, 활성화가 마지막 열이라 여백을 받는다
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        when(settingsService.buildView()).thenReturn(new SettingsView(
                List.of(providerRow("local", null)),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot)))));
        when(props.authSafe()).thenReturn(new AppProperties.AuthConfig(false, true));
        AppUserDetails user = new AppUserDetails("id-2", "user@local", "", "User", "USER", true, false);
        String guest = mvc.perform(get("/settings").locale(Locale.KOREAN).with(user(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(guest).doesNotContain(">생각 제어<").contains("<th class=\"pe-3\">활성화</th>");
    }

    @Test
    @DisplayName("프로바이더 표 — 상태 칸은 미설정 · 확인 중 · 접속불가 · 정상이고, 사유는 말풍선에 있으며, 차단 중은 덧붙는 표시다")
    void providerStatusCellShowsTheStates() throws Exception {
        when(settingsService.setProviderEnabled("local", true)).thenReturn(List.of(
                providerRow("unset", false, false, SettingsView.ProviderConnection.notConfigured()),
                providerRow("checking", true, false, SettingsView.ProviderConnection.pending()),
                providerRow("down", true, true,
                        SettingsView.ProviderConnection.unreachable("models: ConnectException: Connection refused")),
                providerRow("up", true, false, SettingsView.ProviderConnection.reachable(12L, true)),
                providerRow("nomodel", true, false, SettingsView.ProviderConnection.reachable(30L, false))));

        String html = providersFragment(Locale.KOREAN);

        assertThat(count(html, ">미설정<")).isEqualTo(1);
        assertThat(count(html, ">접속불가<")).isEqualTo(1);
        assertThat(count(html, ">정상<")).as("정상 둘 — 모델 목록에 설정한 모델이 없어도 접속은 접속이다").isEqualTo(2);
        assertThat(count(html, ">확인 중<")).isEqualTo(1);
        assertThat(count(html, "class=\"spinner-border ")).as("스피너는 확인 중 칸에만").isEqualTo(1);
        assertThat(count(html, ">차단 중<")).as("접속 여부와 별개로 라우터가 건너뛰는 중이면 덧붙는다").isEqualTo(1);
        assertThat(html).contains("Connection refused", "12ms 만에 응답", "설정한 모델(qwen)이 없습니다");
        for (int i = 0; i < 5; i++) assertThat(html).contains("id=\"prov-status-" + i + "\"");
        assertThat(html).as("표 자체는 out-of-band 교체 속성을 갖지 않는다").doesNotContain("hx-swap-oob");
        assertThat(html).doesNotContain("??");
    }

    @Test
    @DisplayName("프로바이더 표 — 확인 중인 칸이 있을 때만 접속 확인 요청이 붙는다(swap 없이 — 응답이 칸을 직접 바꿔 끼운다)")
    void statusLoaderIsOnlyThereWhileSomethingIsChecking() throws Exception {
        when(settingsService.setProviderEnabled("local", true)).thenReturn(List.of(
                providerRow("checking", true, false, SettingsView.ProviderConnection.pending())));
        String checking = providersFragment(Locale.KOREAN);
        assertThat(checking).contains("hx-get=\"/settings/llm-status\"", "hx-trigger=\"load\"", "hx-swap=\"none\"");

        when(settingsService.setProviderEnabled("local", true)).thenReturn(List.of(
                providerRow("up", true, false, SettingsView.ProviderConnection.reachable(5L, true)),
                providerRow("unset", false, false, SettingsView.ProviderConnection.notConfigured())));
        String settled = providersFragment(Locale.KOREAN);
        assertThat(settled).doesNotContain("hx-get=\"/settings/llm-status\"");
    }

    @Test
    @DisplayName("GET /settings/llm-status — 상태 칸 조각들이 id 로 칸을 통째로 바꿔 끼우는 형태(out-of-band)로 돌아온다 — 표 전체가 아니다")
    void statusEndpointReturnsOutOfBandCells() throws Exception {
        when(settingsService.refreshProviderConnections()).thenReturn(List.of(
                providerRow("a", true, false, SettingsView.ProviderConnection.reachable(12L, true)),
                providerRow("b", true, false,
                        SettingsView.ProviderConnection.unreachable("models: ConnectException: Connection refused"))));

        String html = mvc.perform(get("/settings/llm-status").locale(Locale.KOREAN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(count(html, "hx-swap-oob=\"true\"")).isEqualTo(2);
        assertThat(html).contains("id=\"prov-status-0\"", "id=\"prov-status-1\"", ">정상<", ">접속불가<",
                "Connection refused");
        assertThat(html).doesNotContain("id=\"llm-providers\"", "<table", "hx-get=", "??");
        verify(settingsService).refreshProviderConnections();
    }

    @Test
    @DisplayName("GET /settings — 처음에는 상태 칸이 '확인 중'이고, 접속 확인 요청이 붙는다(페이지는 서버를 기다리지 않는다)")
    void settingsPageStartsWithCheckingCells() throws Exception {
        SettingItem hot = new SettingItem(SettingsKeys.SEARCH_RRF_K, "settings.item.rrf-k", "60",
                "number", true, false, null, 1.0, 1000.0, 1.0);
        when(settingsService.buildView()).thenReturn(new SettingsView(
                List.of(providerRow("local", true, false, SettingsView.ProviderConnection.pending())),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_hot", "settings.group.search_hot", List.of(hot)))));
        AppUserDetails admin = new AppUserDetails("id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        String html = mvc.perform(get("/settings").locale(Locale.KOREAN).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(">확인 중<", "hx-get=\"/settings/llm-status\"", "id=\"prov-status-0\"");
    }

    // ── 설정 화면 정리: 재기동 필요 · 임베딩 차원 · 접히는 카드 ────────────────────────────────────

    @Test
    @DisplayName("GET /settings — '재기동 필요' 는 그룹 제목 옆에 한 번 붙고, 제목에서 중복 문구가 빠졌으며, 임베딩 차원 옆에는 없다")
    void restartBadgeSitsOnTheGroupTitle() throws Exception {
        SettingItem fixed = new SettingItem(null, "settings.item.rerank-enabled", "false",
                "text", false, false, null, null, null, null);
        SettingsView withNote = new SettingsView(
                List.of(providerRow("local", true, false, SettingsView.ProviderConnection.pending())),
                "COST_FIRST", "0.0", "6000", "bge-m3", "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_fixed", "settings.group.search_fixed", List.of(fixed), "settings.note.restart")));
        when(settingsService.buildView()).thenReturn(withNote);
        AppUserDetails admin = new AppUserDetails("id-1", "admin@local", "", "Admin", "ADMIN", true, false);

        String html = mvc.perform(get("/settings").locale(Locale.KOREAN).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(count(html, "재기동 필요")).as("그룹 제목 옆에 한 번 — 항목 옆·제목 문구·임베딩 차원 옆 어디에도 더 없다").isEqualTo(1);
        assertThat(html).contains("검색 (조회 전용)");
        assertThat(html.indexOf("검색 (조회 전용)")).isLessThan(html.indexOf("재기동 필요"));
        assertThat(html).containsPattern("1024</span>\\s*</div>");   // 임베딩 차원 값 바로 뒤에 배지가 없다

        // note 가 없는 그룹에는 배지가 없다
        SettingsView without = new SettingsView(withNote.providers(), "COST_FIRST", "0.0", "6000", "bge-m3",
                "http://localhost:1234/v1", "1024", "chroma",
                List.of(new SettingGroup("search_fixed", "settings.group.search_fixed", List.of(fixed))));
        when(settingsService.buildView()).thenReturn(without);
        String plain = mvc.perform(get("/settings").locale(Locale.KOREAN).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(plain).doesNotContain("재기동 필요");
    }

    @Test
    @DisplayName("GET /admin/settings/thinking — 카드는 접힌 채(<details> 에 open 이 없다)로 오고, [다시 계산] 은 접힌 머리말 밖(본문)에 있다")
    void thinkingCardIsCollapsedByDefault() throws Exception {
        com.example.ragagent.model.ThinkingPreview preview = previewIn(Locale.KOREAN);
        when(thinkingPreview.preview()).thenReturn(preview);

        String html = mvc.perform(get("/admin/settings/thinking").locale(Locale.KOREAN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<details class=\"thinking-card-details\">");
        assertThat(html).as("처음에는 접혀 있다").doesNotContain("<details class=\"thinking-card-details\" open");
        int summaryEnd = html.indexOf("</summary>");
        assertThat(summaryEnd).isPositive();
        assertThat(html.substring(0, summaryEnd)).contains("thinking-chevron", "생각(추론) 수준 — 호출 지점별", "관리자 전용")
                .as("머리말 안에는 버튼이 없다 — 누를 때 카드까지 접었다 폈다 하지 않게")
                .doesNotContain("<button");
        assertThat(html.indexOf("hx-target=\"#thinking-card\"")).isGreaterThan(summaryEnd);
        assertThat(html).contains("다시 계산");
    }
}
