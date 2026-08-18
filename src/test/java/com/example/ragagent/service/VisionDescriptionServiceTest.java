package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
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
 * QA — VisionDescriptionService.
 *
 * describe()는 LlmRouter.executeWithTracking()(TaskType.VISION, RoutingMode.COST_FIRST)으로
 * 라우팅한다 — 이전에는 llmRouter.route()로 모델만 얻어 직접 ChatClient를 구성해 호출해서
 * /llm-usage에 전혀 잡히지 않았다.
 */
class VisionDescriptionServiceTest {

    private LlmRouter llmRouter;
    private VisionDescriptionService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 0.0, 6000, true));
        service = new VisionDescriptionService(llmRouter, props);
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("describe — 정상 응답을 그대로 반환")
    void describe_returnsResponseText() {
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn("이미지 설명입니다.");

        String result = service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(result).isEqualTo("이미지 설명입니다.");
    }

    @Test
    @DisplayName("describe — LlmRouter.executeWithTracking()을 VISION/COST_FIRST로 호출 (사용량 추적)")
    void describe_tracksUsageViaLlmRouter() {
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn("설명");

        service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png");

        verify(llmRouter).executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any());
    }

    @Test
    @DisplayName("describe — Vision 프로바이더 미등록(LlmProviderExhaustedException) 시 플레이스홀더 반환")
    void describe_noVisionProvider_returnsPlaceholder() {
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(new LlmProviderExhaustedException("no vision provider"));

        String result = service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(result).contains("Vision 프로바이더 미등록");
    }

    @Test
    @DisplayName("describe — Vision 미지원 모델(mmproj 관련 에러) 시 플레이스홀더 반환")
    void describe_visionUnsupportedModel_returnsPlaceholder() {
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(new RuntimeException("mmproj file not found"));

        String result = service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(result).contains("Vision 미지원 모델");
    }

    @Test
    @DisplayName("describe — 일반 예외 시 오류 플레이스홀더 반환")
    void describe_genericError_returnsErrorPlaceholder() {
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), any()))
                .thenThrow(new RuntimeException("boom"));

        String result = service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(result).contains("이미지 설명 생성 오류");
    }

    @Test
    @DisplayName("describe — 프롬프트에 전달한 텍스트가 그대로 UserMessage에 담긴다")
    @SuppressWarnings("unchecked")
    void describe_promptContainsGivenText() {
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeWithTracking(eq(TaskType.VISION), eq(RoutingMode.COST_FIRST), callCaptor.capture()))
                .thenReturn("설명");

        service.describe("fake-image".getBytes(StandardCharsets.UTF_8), "image/png", "커스텀 프롬프트");

        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("설명"));
        callCaptor.getValue().apply(chatModel);

        assertThat(promptCaptor.getValue().getUserMessage().getText()).isEqualTo("커스텀 프롬프트");
        assertThat(promptCaptor.getValue().getUserMessage().getMedia()).hasSize(1);
    }
}
