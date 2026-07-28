package com.example.ragagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Shared TRACE(full curl)/DEBUG(endpoint+body) request-logging format — the same split
 * {@link LoggingChatModel}/{@link LoggingEmbeddingModel} use for every {@code ChatModel}-routed
 * call. This one is for call sites that talk to {@link org.springframework.ai.openai.api.OpenAiApi}
 * directly instead, bypassing {@code ChatModel} (and therefore {@code LoggingChatModel}) entirely
 * — namely {@code AnswerService}/{@code DirectAnswerService}'s raw streaming path, which exists
 * specifically to avoid {@code OpenAiChatModel}'s internal stream buffering. Without this, those
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
     * Rebuilds the body JSON in the same shape/field order {@link LoggingChatModel} uses
     * (model, stream, messages[role, content], temperature, max_tokens) instead of serializing the
     * real {@link OpenAiApi.ChatCompletionRequest} record as-is — the record's declared field order
     * (messages first, ~25 fields before stream/temperature) produced a visibly different-looking
     * log line even though the content was identical, which was confusing side-by-side with the
     * ChatModel-routed calls' logs.
     */
    public static String toCurlBodyJson(OpenAiApi.ChatCompletionRequest request) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model());
        body.put("stream", Boolean.TRUE.equals(request.stream()));

        ArrayNode messages = body.putArray("messages");
        for (OpenAiApi.ChatCompletionMessage msg : request.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role().name().toLowerCase());
            m.put("content", msg.content() != null ? msg.content() : "");
        }

        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens()   != null) body.put("max_tokens",  request.maxTokens());

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "<api-key>";
        return key.substring(0, 4) + "****";
    }

    private LlmCurlLogger() {}
}
