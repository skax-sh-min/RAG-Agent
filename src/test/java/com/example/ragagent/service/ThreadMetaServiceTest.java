package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.ThreadMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ThreadMetaService getOrCreate() + generateTitleAsync() (EDIT.md #1).
 *
 * generateTitleAsync() now routes through LlmRouter.executeWithTracking() (previously a
 * directly-injected ChatClient that bypassed llm_usage tracking entirely) so title generation
 * shows up on /llm-usage under the "title:" prefix (BackgroundUsage) instead of being invisible.
 *
 * Other methods (getAll/findById/countTurns/updateTitle/updateRoutingMode/delete) are
 * one-line delegation, not covered here. generateTitleAsync() runs on a virtual thread, so
 * assertions on its outcome use Mockito's timeout() to await the async completion instead
 * of racing it. updateTags() is covered separately since it isn't a pure pass-through — it
 * joins the tag list to the repository's CSV storage form via TagUtils.
 */
class ThreadMetaServiceTest {

    private final ThreadMetaRepository repository = mock(ThreadMetaRepository.class);
    private final LlmRouter llmRouter = mock(LlmRouter.class);
    private final AppProperties props = mockProps();
    private final ThreadMetaService service = new ThreadMetaService(repository, llmRouter, props);

    private static AppProperties mockProps() {
        AppProperties p = mock(AppProperties.class);
        when(p.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 0.6, 3, 20, 0.0, 0.1, 0.0, 0.7, 6000, true));
        return p;
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("getOrCreate — 이미 존재하면 그대로 반환, save() 호출 안 함")
    void getOrCreate_existingThread_returnsWithoutSaving() {
        ThreadMeta existing = new ThreadMeta("t1", "u1", "기존 제목", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(existing));

        ThreadMeta result = service.getOrCreate("u1", "t1", "latest");

        assertThat(result).isEqualTo(existing);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("getOrCreate — 없으면 기본 제목/COST_FIRST 로 생성 후 save()")
    void getOrCreate_newThread_createsWithDefaultsAndSaves() {
        when(repository.findById("u1", "t1")).thenReturn(Optional.empty());

        ThreadMeta result = service.getOrCreate("u1", "t1", "latest");

        assertThat(result.threadId()).isEqualTo("t1");
        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.title()).isEqualTo("[latest] 새 대화");
        assertThat(result.routingMode()).isEqualTo("COST_FIRST");
        verify(repository).save(result);
    }

    @Test
    @DisplayName("generateTitleAsync — 스레드 없으면 LLM 호출 없이 no-op")
    void generateTitleAsync_threadNotFound_noOp() {
        when(repository.findById("u1", "t1")).thenReturn(Optional.empty());

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("generateTitleAsync — 이미 커스텀 제목이면 LLM 호출 없이 no-op")
    void generateTitleAsync_customTitleAlreadySet_noOp() {
        ThreadMeta custom = new ThreadMeta("t1", "u1", "이미 지정된 제목", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(custom));

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(llmRouter, never()).executeWithTracking(any(), any(), any(), any());
    }

    @Test
    @DisplayName("generateTitleAsync — 기본 제목이면 LLM 요약을 20자로 잘라 저장")
    void generateTitleAsync_defaultTitle_generatesAndTruncatesTitle() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenReturn("이것은 20자를 초과하는 아주 긴 요약 문장입니다 정말로");

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(repository, timeout(2000)).updateTitle(
                eq("u1"), eq("t1"),
                org.mockito.ArgumentMatchers.argThat(title -> {
                    String summary = title.substring("[latest] ".length());
                    return title.startsWith("[latest] ") && summary.length() <= 20;
                }));
    }

    @Test
    @DisplayName("generateTitleAsync — LlmRouter.executeWithTracking()을 title: 접두사로 호출 (백그라운드 사용량 분리)")
    void generateTitleAsync_tracksUsageUnderTitlePrefix() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(llmRouter.executeWithTracking(any(), any(), any(), any())).thenReturn("요약");

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(llmRouter, timeout(2000)).executeWithTracking(
                eq(TaskType.MICRO_TEXT), eq(RoutingMode.COST_FIRST), eq(BackgroundUsage.TITLE_PREFIX), any());
    }

    @Test
    @DisplayName("generateTitleAsync — 질문이 PromptInjectionGuard.wrap()으로 감싸져 전달됨 (EDIT.md #5)")
    @SuppressWarnings("unchecked")
    void generateTitleAsync_wrapsQuestionInUserQuestionDelimiters() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        ArgumentCaptor<Function<ChatModel, ChatResponse>> callCaptor = ArgumentCaptor.forClass(Function.class);
        when(llmRouter.executeWithTracking(any(), any(), any(), callCaptor.capture())).thenReturn("요약");

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(repository, timeout(2000)).updateTitle(eq("u1"), eq("t1"), anyString());
        ChatModel chatModel = mock(ChatModel.class);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse("요약"));
        callCaptor.getValue().apply(chatModel);

        assertThat(promptCaptor.getValue().getContents())
                .contains("[USER_QUESTION]").contains("[/USER_QUESTION]");
    }

    @Test
    @DisplayName("generateTitleAsync — LLM 실패해도 예외 전파 없이 조용히 무시")
    void generateTitleAsync_llmFailure_failsSilently() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST", "");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(llmRouter.executeWithTracking(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM down"));

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        // 잠시 대기해 백그라운드 가상 스레드가 예외를 조용히 삼키는지 확인 (updateTitle 미호출)
        verify(repository, timeout(1000).times(0)).updateTitle(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("updateTags — 태그 목록을 정규화된 CSV로 합쳐 repository에 전달")
    void updateTags_joinsTagListToCsv() {
        service.updateTags("u1", "t1", List.of("Billing", "policy"));

        verify(repository).updateTags("u1", "t1", "billing,policy");
    }

    @Test
    @DisplayName("updateTags — 빈 목록은 빈 문자열로 저장 (태그 미선택 = 전체 검색)")
    void updateTags_emptyList_storesEmptyString() {
        service.updateTags("u1", "t1", List.of());

        verify(repository).updateTags("u1", "t1", "");
    }
}
