package com.example.ragagent;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI 2.0 의 OpenAI 모델이 내놓는 응답 청크와 SDK 예외를 테스트에서 만드는 도구.
 *
 * <p>{@code OpenAiChatModel.stream()} 이 {@code ChatResponse} 를 내보내므로 목도 같은 모양으로 만든다. 그 모양은 2026-11-05
 * 실제 응답으로 확인한 것이다: 답 청크는 델타(조각)이고,
 * 생각 본문({@code reasoningContent})은 델타가 아니라 <b>지금까지 쌓인 전체</b>로 매 청크에 붙는다.
 */
public final class LlmTestSupport {

    /** 응답 메시지 메타데이터에서 생각 본문을 찾는 키 — Spring AI 2.0 의 {@code OpenAiChatModel} 이 쓰는 값. */
    public static final String REASONING_KEY = "reasoningContent";

    private LlmTestSupport() {}

    /**
     * Spring AI 2.0 의 {@code ChatClient.builder(model)} 은 모델의 {@code getOptions()} 를 이어받는다 — 목은 {@code null} 을 줘서
     * 거기서 NPE 가 난다. 실제 모델은 늘 옵션을 가지므로 빈 {@code OpenAiChatOptions} 를 주는 목을 쓴다.
     */
    public static org.springframework.ai.chat.model.ChatModel chatModelMock() {
        org.springframework.ai.chat.model.ChatModel model = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(model.getOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().build());
        return model;
    }

    // ── 스트림 청크 ──────────────────────────────────────────────────────────

    /** 답 델타 하나. */
    public static ChatResponse answer(String text) {
        return chunk(text, null, null);
    }

    /** 끝 청크 — {@code finish_reason=stop}. */
    public static ChatResponse stop() {
        return chunk(null, null, "STOP");
    }

    /** 끝 청크 — 주어진 {@code finish_reason}(대문자, Spring AI 가 주는 모양). */
    public static ChatResponse finish(String reason) {
        return chunk(null, null, reason);
    }

    /** 생각 본문이 지금까지 {@code cumulative} 로 쌓인 시점의 청크(답은 비어 있다). */
    public static ChatResponse thoughtSoFar(String cumulative) {
        return chunk(null, cumulative, null);
    }

    /**
     * 생각 델타들을 Spring AI 2.0 의 모양으로 — 청크마다 지금까지 쌓인 전체가 붙는다. {@code thoughts("a", "b")} 는
     * {@code "a"}, {@code "ab"} 를 실은 두 청크다.
     */
    public static List<ChatResponse> thoughts(String... deltas) {
        List<ChatResponse> out = new ArrayList<>();
        StringBuilder sofar = new StringBuilder();
        for (String delta : deltas) {
            sofar.append(delta);
            out.add(thoughtSoFar(sofar.toString()));
        }
        return out;
    }

    /** 가장 일반적인 청크 — 값이 없는 칸은 {@code null}. */
    public static ChatResponse chunk(String content, String reasoningSoFar, String finishReason) {
        AssistantMessage.Builder<?> message = AssistantMessage.builder().content(content == null ? "" : content);
        if (reasoningSoFar != null) message.properties(Map.of(REASONING_KEY, reasoningSoFar));
        ChatGenerationMetadata.Builder meta = ChatGenerationMetadata.builder();
        if (finishReason != null) meta.finishReason(finishReason);
        return new ChatResponse(List.of(new Generation(message.build(), meta.build())));
    }

    /** 서버가 보고한 사용량을 실은 응답(원본 {@code CompletionUsage} 를 네이티브로 단다). */
    public static ChatResponse withUsage(ChatResponse base, long completionTokens, long reasoningTokens) {
        com.openai.models.completions.CompletionUsage nativeUsage = com.openai.models.completions.CompletionUsage.builder()
                .promptTokens(10).completionTokens(completionTokens).totalTokens(10 + completionTokens)
                .completionTokensDetails(com.openai.models.completions.CompletionUsage.CompletionTokensDetails.builder()
                        .reasoningTokens(reasoningTokens).build())
                .build();
        return new ChatResponse(base.getResults(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, (int) completionTokens, (int) (10 + completionTokens), nativeUsage))
                .build());
    }

    // ── SDK 예외 ─────────────────────────────────────────────────────────────

    /** 서버가 400 으로 거절했다 — 메시지에 {@code message} 가 실린다({@code "400: message"}). */
    public static BadRequestException badRequest(String message) {
        return BadRequestException.builder().headers(Headers.builder().build()).error(error(message)).build();
    }

    /** 서버 오류 — 상태 코드는 5xx 또는 503 등. */
    public static InternalServerException serverError(int status, String message) {
        return InternalServerException.builder().statusCode(status).headers(Headers.builder().build())
                .error(error(message)).build();
    }

    /** 429 — {@code Retry-After} 헤더가 있다. */
    public static RateLimitException rateLimited(String retryAfter, String message) {
        return RateLimitException.builder().headers(Headers.builder().put("Retry-After", retryAfter).build())
                .error(error(message)).build();
    }

    /** 서버에 닿지 못했거나 응답 도중 끊겼다 — SDK 가 I/O 오류를 감싸는 모양({@code "Request failed"}). */
    public static OpenAIIoException io(Throwable cause) {
        return new OpenAIIoException("Request failed", cause);
    }

    private static ErrorObject error(String message) {
        return ErrorObject.builder().message(message).type("invalid_request_error")
                .code((String) null).param((String) null).build();
    }
}
