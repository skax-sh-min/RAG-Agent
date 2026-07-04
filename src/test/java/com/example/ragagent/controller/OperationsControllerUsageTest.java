package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContextResolver;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA — OperationsController LLM/embedding usage reporting (§6.6) + inactive-provider
 * filtering (§6.7)
 *
 * Verifies the embedding pseudo-provider ("embed:&lt;model&gt;", type=EMBEDDING) appears
 * alongside chat providers in all three usage surfaces without disturbing the existing
 * chat provider entries, and that unconfigured chat providers are hidden unless they have
 * historical usage.
 */
@WebMvcTest(value = OperationsController.class, properties = "app.auth.enabled=true")
@Import({com.example.ragagent.context.WebMvcConfig.class, com.example.ragagent.security.SecurityConfig.class})
@WithMockUser
class OperationsControllerUsageTest {

    @Autowired MockMvc mvc;

    @MockitoBean ThreadMetaService threadMetaService;
    @MockitoBean MemoryService memoryService;
    @MockitoBean LlmUsageRepository usageRepo;
    @MockitoBean AppProperties props;
    @MockitoBean CircuitBreaker circuitBreaker;
    @MockitoBean ChatModel chatModel;
    @MockitoBean ThreadContextResolver threadContextResolver;
    @MockitoBean AuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        var chatProvider = new AppProperties.ProviderConfig(
                "local", "http://localhost:1234/v1", "sk-fake", "gemma", "BOTH", "LOCAL", 0, true);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(chatProvider), 2, 10, 180, "COST_FIRST", 0.6));
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(
                "http://localhost:1234/v1", null, "nomic-embed", 768, 10, 120, true));
        when(circuitBreaker.getBlockedProviders()).thenReturn(Map.of());

        var zero = new LlmUsageRepository.PeriodSummary(0, 0, 0);
        when(usageRepo.getDaily(org.mockito.ArgumentMatchers.anyString())).thenReturn(zero);
        when(usageRepo.getWeekly(org.mockito.ArgumentMatchers.anyString())).thenReturn(zero);
        when(usageRepo.getMonthly(org.mockito.ArgumentMatchers.anyString())).thenReturn(zero);
        when(usageRepo.getDailyHistory(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("GET /api/v1/llm/usage — 채팅 프로바이더 + embed:<model> 행(type=EMBEDDING) 포함")
    void usageReport_includesEmbeddingRow() throws Exception {
        mvc.perform(get("/api/v1/llm/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("local"))
                .andExpect(jsonPath("$[1].provider").value("embed:nomic-embed"))
                .andExpect(jsonPath("$[1].type").value("EMBEDDING"))
                .andExpect(jsonPath("$[1].model").value("nomic-embed"))
                .andExpect(jsonPath("$[1].blockedUntil").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/llm/usage/history — 맵에 chat provider 키 + embed:<model> 키 모두 포함")
    void usageHistory_includesEmbeddingKey() throws Exception {
        mvc.perform(get("/api/v1/llm/usage/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local").exists())
                .andExpect(jsonPath("$['embed:nomic-embed']").exists());
    }

    @Test
    @DisplayName("GET /ui/llm-usage/cards — EMBEDDING 배지 + embed:<model> 카드 렌더")
    void usageCards_rendersEmbeddingCard() throws Exception {
        mvc.perform(get("/ui/llm-usage/cards"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("embed:nomic-embed")))
                .andExpect(content().string(containsString("EMBEDDING")));
    }

    // ── §6.7 — inactive (unconfigured) provider filtering ────────────────────

    /** apiKey="" → unconfigured; only shown when usedProviders() contains its name. */
    private void withGhostProvider() {
        var local = new AppProperties.ProviderConfig(
                "local", "http://localhost:1234/v1", "sk-fake", "gemma", "BOTH", "LOCAL", 0, true);
        var ghost = new AppProperties.ProviderConfig(
                "ghost", "https://api.example.com", "", "ghost-model", "TEXT", "NORMAL", 1, true);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(local, ghost), 2, 10, 180, "COST_FIRST", 0.6));
    }

    @Test
    @DisplayName("§6.7 — 미설정(apiKey 없음) + 사용 이력 없는 provider는 카드·표·차트 어디에도 안 보임")
    void unconfiguredProviderWithoutHistory_excludedEverywhere() throws Exception {
        withGhostProvider();
        when(usageRepo.usedProviders()).thenReturn(Set.of());

        mvc.perform(get("/api/v1/llm/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].provider", not(org.hamcrest.Matchers.hasItem("ghost"))));
        mvc.perform(get("/api/v1/llm/usage/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ghost").doesNotExist());
        mvc.perform(get("/ui/llm-usage/cards"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("ghost-model"))));
    }

    @Test
    @DisplayName("§6.7 — 미설정이지만 사용 이력 있는 provider는 계속 표시됨(이력 보존)")
    void unconfiguredProviderWithHistory_stillShownEverywhere() throws Exception {
        withGhostProvider();
        when(usageRepo.usedProviders()).thenReturn(Set.of("ghost"));

        mvc.perform(get("/api/v1/llm/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].provider", org.hamcrest.Matchers.hasItem("ghost")));
        mvc.perform(get("/api/v1/llm/usage/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ghost").exists());
        mvc.perform(get("/ui/llm-usage/cards"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ghost-model")));
    }

    @Test
    @DisplayName("§6.7 — 설정된(apiKey 있음) provider는 사용 이력 0이어도 항상 표시(회귀 방지)")
    void configuredProviderWithZeroUsage_alwaysShown() throws Exception {
        withGhostProvider();
        when(usageRepo.usedProviders()).thenReturn(Set.of());

        mvc.perform(get("/api/v1/llm/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].provider", org.hamcrest.Matchers.hasItem("local")));
    }
}
