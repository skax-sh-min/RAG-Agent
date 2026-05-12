package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

        ClassifierService svc = new ClassifierService(chatClient);
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null);
        AgentState result = svc.execute(state);

        assertThat(result.questionType())
                .as("null text should fall back to 'concept'")
                .isEqualTo("concept");
    }

    // ── CriticService ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("CriticService.execute — getText() null → grounded=true 폴백 (B-23)")
    void critic_nullText_treatsAsGrounded() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatResponse resp = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).call().chatResponse())
                .thenReturn(resp);
        when(resp.getResult().getOutput().getText()).thenReturn(null);

        CriticService svc = new CriticService(chatClient);
        AgentState state = AgentState.of("테스트", "latest", "t1", "", null)
                .withAnswer("답변")
                .withRetrievedDocs(List.of(new Document("doc content", Map.of())));
        AgentState result = svc.execute(state);

        assertThat(result.grounded())
                .as("null text parse failure should treat answer as grounded")
                .isTrue();
        assertThat(result.needsRetry()).isFalse();
    }
}
