package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import reactor.core.publisher.Flux;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — B-23 ChatResponse output null 안전성 회귀 테스트.
 *
 * Verifies that ChatResponses.safeText() and each service that uses it
 * never throw NPE when the LLM returns a null text payload.
 */
class ChatResponseNullSafetyTest {

    // ── ChatResponses.safeText() unit tests ───────────────────────────────────

    @Test
    @DisplayName("safeText — null ChatResponse → 빈 문자열 (B-23)")
    void safeText_nullResponse_returnsEmpty() {
        assertThat(ChatResponses.safeText(null)).isEmpty();
    }

    @Test
    @DisplayName("safeText — null getResult() → 빈 문자열 (B-23)")
    void safeText_nullResult_returnsEmpty() {
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(null);
        assertThat(ChatResponses.safeText(resp)).isEmpty();
    }

    @Test
    @DisplayName("safeText — null getText() → 빈 문자열 (B-23)")
    void safeText_nullText_returnsEmpty() {
        ChatResponse resp = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(resp.getResult().getOutput().getText()).thenReturn(null);
        assertThat(ChatResponses.safeText(resp)).isEmpty();
    }

    // ── ClassifierService ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ClassifierService.execute — getText() null → concept 폴백 (B-23)")
    void classifier_nullText_fallsToConcept() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse resp = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
                .thenReturn(resp);
        when(resp.getResult().getOutput().getText()).thenReturn(null);

        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        ClassifierService svc = new ClassifierService(chatClient, messageSource);
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null);
        AgentState result = svc.execute(state);

        assertThat(result.questionType())
                .as("null text should fall back to 'concept'")
                .isEqualTo("concept");
    }

    // ── CriticService ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CriticService.execute — grounded 미설정(null) → grounded=true 폴백 (S-1)")
    void critic_nullGrounded_treatsAsGrounded() {
        CriticService svc = new CriticService();
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null)
                .toBuilder()
                .answer("답변")
                .retrievedDocs(List.of(new Document("doc content", Map.of())))
                .build();   // grounded 미설정 (null)
        AgentState result = svc.execute(state);

        assertThat(result.grounded())
                .as("precomputed grounded null → treat as grounded (fail-safe)")
                .isTrue();
        assertThat(result.needsRetry()).isFalse();
    }

    @Test
    @DisplayName("CriticService.execute — 선계산 grounded=false → needsRetry=true (S-1)")
    void critic_precomputedUngrounded_triggersRetry() {
        CriticService svc = new CriticService();
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null)
                .toBuilder()
                .answer("답변")
                .retrievedDocs(List.of(new Document("doc content", Map.of())))
                .grounded(false)
                .build();
        AgentState result = svc.execute(state);

        assertThat(result.needsRetry()).isTrue();
    }

    // ── ClassifierService — VALID_TYPES 범위 검증 ─────────────────────────────

    @Test
    @DisplayName("ClassifierService — VALID_TYPES 외 응답 시 'concept' 폴백")
    void classifier_invalidType_fallsToConcept() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).stream().content())
                .thenReturn(Flux.just("{\"question_type\": \"unknown_garbage\"}"));

        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        ClassifierService svc = new ClassifierService(chatClient, messageSource);
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null);
        AgentState result = svc.execute(state);

        assertThat(result.questionType())
                .as("VALID_TYPES 외 값은 'concept'으로 폴백돼야 한다")
                .isEqualTo("concept");
    }

    // ── CriticService — retrievedDocs 비어있을 때 즉시 리턴 ───────────────────

    @Test
    @DisplayName("CriticService — retrievedDocs 비어있으면 needsRetry=false")
    void critic_emptyDocs_returnsFalse() {
        CriticService svc = new CriticService();
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null)
                .toBuilder().answer("답변").retrievedDocs(List.of()).build();

        AgentState result = svc.execute(state);

        assertThat(result.needsRetry()).isFalse();
    }
}
