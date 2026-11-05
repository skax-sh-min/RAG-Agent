package com.example.ragagent.llm;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

/**
 * Shared TRACE(full curl)/DEBUG(endpoint+body) request-logging format — the same split
 * {@link LoggingChatModel}/{@link LoggingEmbeddingModel} use for every {@code ChatModel}-routed
 * call. This one is for call sites that talk to the raw {@code OpenAiChatModel}
 * directly instead, bypassing the decorator chain (and therefore {@code LoggingChatModel}) entirely
 * — namely the chat answers' raw streaming path ({@code AnswerStreamer}, shared by
 * {@code AnswerService}/{@code DirectAnswerService}), which exists specifically to avoid
 * {@code OpenAiChatModel}'s internal stream buffering. Without this, those
 * calls (the actual RAG/direct answer request — the one with the retrieved-document context) never
 * showed up in the logs at any level, regardless of prompt size.
 */
public final class LlmCurlLogger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void log(Logger log, String tag, String providerName, String endpoint,
                            String apiKey, String bodyJson) {
        if (log.isTraceEnabled()) {
            log.trace("[{} curl] provider={}\ncurl -s -X POST '{}' \\\n"
                    + "  -H 'Content-Type: application/json' \\\n"
                    + "  -H 'Authorization: Bearer {}' \\\n"
                    + "  -d @- << 'EOF'\n{}\nEOF",
                    tag, providerName, endpoint, maskKey(apiKey), bodyJson);
        } else if (log.isDebugEnabled()) {
            log.debug("[{}] provider={} endpoint={}\n{}", tag, providerName, endpoint, bodyJson);
        }
    }

    /**
     * 요청 본문 JSON — {@link LoggingChatModel} 과 채팅 답변 스트리밍({@code AnswerStreamer})이 <b>같은 모양</b>으로 찍는다:
     * model, stream, messages[role, content], temperature, max_tokens, reasoning_effort, 그리고 {@code extraBody} 항목은
     * 본문 최상위에 그대로. Spring AI 1.x 에서는 {@code OpenAiApi.ChatCompletionRequest} 를 이 모양으로 다시 짰는데, 2.0 은
     * SDK 가 요청을 만들어 그 레코드가 없다 — 대신 호출부가 넘긴 옵션에서 같은 필드를 읽는다.
     *
     * @param model 요청에 실리는 모델 id
     */
    public static String toCurlBodyJson(String model, boolean streaming, List<Message> instructions,
                                        ChatOptions options) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", streaming);

        ArrayNode messages = body.putArray("messages");
        for (Message msg : instructions) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.getMessageType().getValue());
            m.put("content", msg.getText() != null ? msg.getText() : "");
        }

        if (options != null) {
            if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
        }
        if (options instanceof OpenAiChatOptions openAi) {
            // 생각 수준(§6.29)이 표준 필드로 나가는 dialect(openai-effort)의 값 — 빠뜨리면 이 curl 로 재현한 요청만 다른
            // 생각 수준으로 돌아 실제 호출과 다른 결과가 나온다. extraBody(chat_template_kwargs 등)도 같은 이유다.
            if (openAi.getReasoningEffort() != null) body.put("reasoning_effort", openAi.getReasoningEffort());
            if (openAi.getExtraBody() != null) {
                openAi.getExtraBody().forEach((key, value) -> body.set(key, MAPPER.valueToTree(value)));
            }
        }

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "<api-key>";
        return key.substring(0, 4) + "****";
    }

    private LlmCurlLogger() {}
}
