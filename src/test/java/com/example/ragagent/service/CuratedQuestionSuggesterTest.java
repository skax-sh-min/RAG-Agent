package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 큐레이션 질문 구체화 제안 — 이 클래스가 지켜야 할 것은 <b>제안만 한다</b>는 것과,
 * 쓸 수 없는 제안을 조용히 흘려보내지 않는다는 것이다. 질문은 이 검색 축의 텍스트 앞부분이자
 * 모든 청크에 반복 부여되는 값이라, 나쁜 제안이 그대로 반영되면 항목 전체가 엉뚱한 질의에 걸린다.
 */
class CuratedQuestionSuggesterTest {

    private static final String PROMPT = "현재:{question}\n본문:{answer}";

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        when(props.llmSafe()).thenReturn(mock(AppProperties.LlmConfig.class));
        return props;
    }

    private static MessageSource bundle() {
        MessageSource ms = mock(MessageSource.class);
        when(ms.getMessage(eq("prompt.curated.question"), any(), any(Locale.class))).thenReturn(PROMPT);
        return ms;
    }

    private static LlmRouter routerReturning(String response) {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeWithTracking(any(TaskType.class), any(RoutingMode.class), anyString(), any()))
                .thenReturn(response);
        return router;
    }

    @Test
    @DisplayName("본문을 근거로 더 구체적인 질문을 제안한다 — MICRO_TEXT · 배경 사용량으로 잡힌다")
    void suggestsFromBody() {
        LlmRouter router = routerReturning("VPN 접속이 안 될 때 확인할 설정은?");
        var suggester = new CuratedQuestionSuggester(router, bundle(), props());

        var out = suggester.suggest("그거 어떻게 해?", "VPN 프로파일에서 split tunneling 을 끕니다.", Locale.KOREAN);

        assertThat(out).contains("VPN 접속이 안 될 때 확인할 설정은?");
        verify(router).executeWithTracking(eq(TaskType.MICRO_TEXT), eq(RoutingMode.COST_FIRST),
                eq(BackgroundUsage.QUESTION_PREFIX), any());
    }

    @Test
    @DisplayName("본문이 비면 LLM 을 부르지 않는다 — 제안할 근거 자체가 없다")
    void blankBodySkipsTheCall() {
        LlmRouter router = mock(LlmRouter.class);
        var suggester = new CuratedQuestionSuggester(router, bundle(), props());

        assertThat(suggester.suggest("질문", "   ", Locale.KOREAN)).isEmpty();
        verify(router, never()).executeWithTracking(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("현재 질문을 그대로 돌려주면 '제안 없음'이다 — 이미 충분히 구체적이라는 뜻")
    void unchangedSuggestionIsNoProposal() {
        var suggester = new CuratedQuestionSuggester(
                routerReturning("이미 구체적인 질문"), bundle(), props());

        assertThat(suggester.suggest("이미 구체적인 질문", "본문", Locale.KOREAN)).isEmpty();
    }

    @Test
    @DisplayName("호출이 실패해도 예외를 올리지 않는다 — 제안이 없을 뿐 편집은 계속돼야 한다")
    void llmFailureDegradesToNoProposal() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeWithTracking(any(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("All providers exhausted"));
        var suggester = new CuratedQuestionSuggester(router, bundle(), props());

        assertThat(suggester.suggest("질문", "본문", Locale.KOREAN)).isEmpty();
    }

    @Test
    @DisplayName("본문은 상한까지만 프롬프트에 싣는다 — 질문 한 줄에 본문 전체가 필요하지 않다")
    @SuppressWarnings("unchecked")
    void bodyIsCappedInThePrompt() {
        String body = "가".repeat(CuratedQuestionSuggester.MAX_ANSWER_CHARS + 500);
        LlmRouter router = routerReturning("다시 쓴 질문");
        var suggester = new CuratedQuestionSuggester(router, bundle(), props());

        suggester.suggest("질문", body, Locale.KOREAN);

        ArgumentCaptor<Function<ChatModel, ChatResponse>> fn = ArgumentCaptor.forClass(Function.class);
        verify(router).executeWithTracking(any(), any(), anyString(), fn.capture());
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(chatResponse("x"));
        fn.getValue().apply(model);
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());

        assertThat(prompt.getValue().getContents().length())
                .isLessThan(body.length());
    }

    @Test
    @DisplayName("한 줄만 취하고 감싼 따옴표는 벗긴다 / 설명을 늘어놓으면 버린다")
    void parseKeepsOneUsableLine() {
        assertThat(CuratedQuestionSuggester.parse("\"VPN 설정은 어디서 바꾸나요?\"\n부연 설명"))
                .isEqualTo("VPN 설정은 어디서 바꾸나요?");
        assertThat(CuratedQuestionSuggester.parse(
                "가".repeat(CuratedQuestionSuggester.MAX_QUESTION_CHARS + 1))).isNull();
        assertThat(CuratedQuestionSuggester.parse("")).isNull();
        assertThat(CuratedQuestionSuggester.parse(null)).isNull();
    }
}
