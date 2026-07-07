package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ImageTypeClassifier (§6.14 잔여).
 *
 * classify()는 LlmRouter.executeWithTracking()(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST)으로
 * 라우팅한다 — 이전에는 llmRouter.route()로 모델만 얻어 직접 ChatClient를 구성해 호출해서
 * /llm-usage에 전혀 잡히지 않았다.
 */
class ImageTypeClassifierTest {

    private LlmRouter llmRouter;
    private ImageTypeClassifier classifier;
    private static final byte[] IMAGE = "fake-image".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        classifier = new ImageTypeClassifier(llmRouter);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("classify — 유효한 타입 응답을 그대로 반환 (소문자 정규화)")
    void classify_validType_returnsNormalized() {
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn("DIAGRAM");

        assertThat(classifier.classify(IMAGE, "image/png")).isEqualTo("diagram");
    }

    @Test
    @DisplayName("classify — VALID_TYPES 밖의 응답은 other로 폴백")
    void classify_invalidType_fallsBackToOther() {
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn("unknown_garbage");

        assertThat(classifier.classify(IMAGE, "image/png")).isEqualTo("other");
    }

    @Test
    @DisplayName("classify — null 응답은 other로 폴백")
    void classify_nullResponse_fallsBackToOther() {
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(null);

        assertThat(classifier.classify(IMAGE, "image/png")).isEqualTo("other");
    }

    @Test
    @DisplayName("classify — 예외 발생 시 other로 폴백 (예외 전파 없음)")
    void classify_exception_fallsBackToOther() {
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThat(classifier.classify(IMAGE, "image/png")).isEqualTo("other");
    }

    @Test
    @DisplayName("classify — LlmRouter.executeWithTracking()을 LIGHT_BOTH/COST_FIRST로 호출 (사용량 추적)")
    void classify_tracksUsageViaLlmRouter() {
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn("photo");

        classifier.classify(IMAGE, "image/png");

        verify(llmRouter).executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), any());
    }

    @Test
    @DisplayName("classify — 프롬프트에 이미지가 미디어로 첨부된다")
    @SuppressWarnings("unchecked")
    void classify_attachesImageAsMedia() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeWithTracking(eq(TaskType.LIGHT_BOTH), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn("photo");

        classifier.classify(IMAGE, "image/png");

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("photo"));
        callCaptor.getValue().apply(chatModel);

        assertThat(promptCaptor.getValue().getUserMessage().getMedia()).hasSize(1);
    }
}
