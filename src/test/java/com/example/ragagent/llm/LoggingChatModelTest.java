package com.example.ragagent.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA — LoggingChatModel's DEBUG/TRACE split (§ the DEBUG log used to always be the full
 * replayable curl command, which made DEBUG logs for large RAG answer prompts unwieldy). DEBUG
 * now gets the condensed endpoint+body form (no curl wrapper/auth header); TRACE gets the full
 * curl command, same as DEBUG used to.
 */
@ResourceLock("global-state")
class LoggingChatModelTest {

    private final ChatModel delegate = mock(ChatModel.class);
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogCapture() {
        logbackLogger = com.example.ragagent.LogbackTestSupport.logger(LoggingChatModel.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
        logbackLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogCapture() {
        logbackLogger.detachAppender(logAppender);
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("답변"))));
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("시스템 지시"), new UserMessage("질문")));
    }

    @Test
    @DisplayName("call() — delegate에 그대로 위임하고 응답을 변형 없이 반환")
    void callDelegatesAndReturnsResponseUnchanged() {
        ChatResponse expected = response();
        when(delegate.call(any(Prompt.class))).thenReturn(expected);
        var model = new LoggingChatModel(delegate, "local", "http://localhost:1234/v1", "sk-test-key-123456", "llama");

        ChatResponse actual = model.call(prompt());

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("DEBUG 활성 시 provider/endpoint + body만 남고, curl/인증 헤더는 없다")
    void logsEndpointAndBodyAtDebugLevel() {
        when(delegate.call(any(Prompt.class))).thenReturn(response());
        var model = new LoggingChatModel(delegate, "local", "http://localhost:1234/v1", "sk-test-key-123456", "llama");

        model.call(prompt());

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("local")
                    .contains("http://localhost:1234/v1/chat/completions")
                    .contains("질문")
                    .doesNotContain("curl -s -X POST")
                    .doesNotContain("Authorization")
                    .doesNotContain("sk-t****")
                    .doesNotContain("sk-test-key-123456");
        });
    }

    @Test
    @DisplayName("TRACE 활성 시 재현 가능한 전체 curl 명령(인증 헤더 마스킹 포함)이 남는다")
    void logsFullCurlAtTraceLevel() {
        logbackLogger.setLevel(Level.TRACE);
        when(delegate.call(any(Prompt.class))).thenReturn(response());
        var model = new LoggingChatModel(delegate, "local", "http://localhost:1234/v1", "sk-test-key-123456", "llama");

        model.call(prompt());

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            String msg = event.getFormattedMessage();
            assertThat(msg).contains("local")
                    .contains("curl -s -X POST")
                    .contains("http://localhost:1234/v1/chat/completions")
                    .contains("질문")
                    .contains("sk-t****") // masked, not the raw key
                    .doesNotContain("sk-test-key-123456");
        });
    }

    @Test
    @DisplayName("DEBUG만 활성이고 TRACE는 비활성이면 TRACE 레벨 로그는 남지 않는다")
    void debugOnly_neverLogsAtTraceLevel() {
        when(delegate.call(any(Prompt.class))).thenReturn(response());
        var model = new LoggingChatModel(delegate, "local", "http://localhost:1234/v1", "sk-test-key-123456", "llama");

        model.call(prompt());

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.TRACE);
    }

    @Test
    @DisplayName("DEBUG 비활성이면 로그를 남기지 않는다")
    void logsNothingWhenDebugDisabled() {
        logbackLogger.setLevel(Level.INFO);
        when(delegate.call(any(Prompt.class))).thenReturn(response());
        var model = new LoggingChatModel(delegate, "local", "http://localhost:1234/v1", "key", "llama");

        model.call(prompt());

        assertThat(logAppender.list).isEmpty();
    }
}
