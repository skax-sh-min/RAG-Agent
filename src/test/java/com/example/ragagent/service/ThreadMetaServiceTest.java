package com.example.ragagent.service;

import com.example.ragagent.model.ThreadMeta;
import com.example.ragagent.repository.ThreadMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA — ThreadMetaService getOrCreate() + generateTitleAsync() (EDIT.md #1)
 *
 * Other methods (getAll/findById/countTurns/updateTitle/updateRoutingMode/delete) are
 * one-line delegation, not covered here. generateTitleAsync() runs on a virtual thread, so
 * assertions on its outcome use Mockito's timeout() to await the async completion instead
 * of racing it.
 */
class ThreadMetaServiceTest {

    private final ThreadMetaRepository repository = mock(ThreadMetaRepository.class);
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final ThreadMetaService service = new ThreadMetaService(repository, chatClient);

    @Test
    @DisplayName("getOrCreate — 이미 존재하면 그대로 반환, save() 호출 안 함")
    void getOrCreate_existingThread_returnsWithoutSaving() {
        ThreadMeta existing = new ThreadMeta("t1", "u1", "기존 제목", "latest", "now", "now", "COST_FIRST");
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

        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("generateTitleAsync — 이미 커스텀 제목이면 LLM 호출 없이 no-op")
    void generateTitleAsync_customTitleAlreadySet_noOp() {
        ThreadMeta custom = new ThreadMeta("t1", "u1", "이미 지정된 제목", "latest", "now", "now", "COST_FIRST");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(custom));

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("generateTitleAsync — 기본 제목이면 LLM 요약을 20자로 잘라 저장")
    void generateTitleAsync_defaultTitle_generatesAndTruncatesTitle() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(chatClient.prompt().user(anyString()).stream().content())
                .thenReturn(Flux.just("이것은 20자를 초과하는 아주 긴 요약 문장입니다 정말로"));

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(repository, timeout(2000)).updateTitle(
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.eq("t1"),
                org.mockito.ArgumentMatchers.argThat(title -> {
                    String summary = title.substring("[latest] ".length());
                    return title.startsWith("[latest] ") && summary.length() <= 20;
                }));
    }

    @Test
    @DisplayName("generateTitleAsync — 질문이 PromptInjectionGuard.wrap()으로 감싸져 전달됨 (EDIT.md #5)")
    void generateTitleAsync_wrapsQuestionInUserQuestionDelimiters() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(chatClient.prompt().user(anyString()).stream().content())
                .thenReturn(Flux.just("요약"));
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt();
        org.mockito.Mockito.clearInvocations(requestSpec);

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        verify(requestSpec, timeout(2000)).user(captor.capture());
        assertThat(captor.getValue()).contains("[USER_QUESTION]").contains("[/USER_QUESTION]");
    }

    @Test
    @DisplayName("generateTitleAsync — LLM 실패해도 예외 전파 없이 조용히 무시")
    void generateTitleAsync_llmFailure_failsSilently() {
        ThreadMeta fresh = new ThreadMeta("t1", "u1", "[latest] 새 대화", "latest", "now", "now", "COST_FIRST");
        when(repository.findById("u1", "t1")).thenReturn(Optional.of(fresh));
        when(chatClient.prompt().user(anyString()).stream().content())
                .thenReturn(Flux.error(new RuntimeException("LLM down")));

        service.generateTitleAsync("u1", "t1", "latest", "질문");

        // 잠시 대기해 백그라운드 가상 스레드가 예외를 조용히 삼키는지 확인 (updateTitle 미호출)
        verify(repository, timeout(1000).times(0)).updateTitle(anyString(), anyString(), anyString());
    }
}
