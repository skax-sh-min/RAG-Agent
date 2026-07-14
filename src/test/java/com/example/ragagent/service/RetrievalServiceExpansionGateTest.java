package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 회귀 — 멀티쿼리 확장 게이트(shouldExpand) 동작.
 */
class RetrievalServiceExpansionGateTest {

    private RetrievalService service(boolean enabled, int minLength) {
        AppProperties props = mock(AppProperties.class);
        when(props.searchTopK()).thenReturn(7);
        when(props.searchMultiqueryEnabled()).thenReturn(enabled);
        when(props.searchMultiqueryMinLengthSafe()).thenReturn(minLength);
        when(props.searchRetryEscalateSafe()).thenReturn(true);
        when(props.searchRerankEnabled()).thenReturn(false);
        when(props.searchCandidateMultiplierSafe()).thenReturn(3);
        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);
        return new RetrievalService(llmRouter, mock(LlmUsageRepository.class), mock(RagService.class), props,
                Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("minLength=0 → 항상 확장 (기본 동작 보존)")
    void minLengthZero_alwaysExpands() {
        RetrievalService svc = service(true, 0);
        assertThat(svc.shouldExpand("짧음")).isTrue();
        assertThat(svc.shouldExpand("아주 긴 질문 문장입니다")).isTrue();
    }

    @Test
    @DisplayName("길이 < minLength → 확장 생략, 이상 → 확장")
    void lengthGate() {
        RetrievalService svc = service(true, 12);
        assertThat(svc.shouldExpand("로그인 방법")).isFalse();          // 6자
        assertThat(svc.shouldExpand("결제 오류 코드 1234 의미와 해결법")).isTrue(); // 12자 이상
    }

    @Test
    @DisplayName("공백 trim 후 길이로 판정")
    void trimsBeforeMeasuring() {
        RetrievalService svc = service(true, 5);
        assertThat(svc.shouldExpand("   짧   ")).isFalse();
    }

    @Test
    @DisplayName("enabled=false → 길이 무관 확장 생략")
    void disabled_neverExpands() {
        RetrievalService svc = service(false, 0);
        assertThat(svc.shouldExpand("아주 긴 질문 문장입니다 정말로")).isFalse();
    }

    @Test
    @DisplayName("null 질문 → 확장 생략")
    void nullQuestion_noExpand() {
        assertThat(service(true, 0).shouldExpand(null)).isFalse();
    }
}
