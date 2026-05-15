package com.example.ragagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Transparent ChatModel decorator. At DEBUG level, logs a ready-to-run curl command
 * for every request so developers can replay calls outside the application.
 */
public class LoggingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel delegate;
    private final String providerName;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;

    public LoggingChatModel(ChatModel delegate, String providerName,
                            String baseUrl, String apiKey, String modelName) {
        this.delegate     = delegate;
        this.providerName = providerName;
        this.endpoint     = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey       = apiKey;
        this.modelName    = modelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        logCurl(prompt, false);
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        logCurl(prompt, true);
        return delegate.stream(prompt);
    }

    private void logCurl(Prompt prompt, boolean streaming) {
        if (!log.isDebugEnabled()) return;
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelName);
            body.put("stream", streaming);

            ArrayNode messages = body.putArray("messages");
            for (Message msg : prompt.getInstructions()) {
                ObjectNode m = messages.addObject();
                m.put("role", msg.getMessageType().getValue());
                m.put("content", msg.getText() != null ? msg.getText() : "");
            }

            if (prompt.getOptions() instanceof OpenAiChatOptions opts) {
                if (opts.getTemperature() != null) body.put("temperature", opts.getTemperature());
                if (opts.getMaxTokens()     != null) body.put("max_tokens",  opts.getMaxTokens());
            }

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            log.debug("[LLM curl] provider={}\ncurl -s -X POST '{}' \\\n"
                    + "  -H 'Content-Type: application/json' \\\n"
                    + "  -H 'Authorization: Bearer {}' \\\n"
                    + "  -d @- << 'EOF'\n{}\nEOF",
                    providerName, endpoint, maskKey(apiKey), json);
        } catch (Exception e) {
            log.debug("[LLM curl] serialization error: {}", e.getMessage());
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "<api-key>";
        return key.substring(0, 4) + "****";
    }
}
