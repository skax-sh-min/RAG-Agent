package com.example.ragagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Transparent ChatModel decorator. At DEBUG level, logs a ready-to-run curl command
 * for every request so developers can replay calls outside the application.
 */
public class LoggingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModel.class);

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

    /** 기본 옵션은 안쪽 모델의 것이다 — ChatClient 가 이 값을 이어받는다(Spring AI 2.0 의 ChatModel 은 getOptions() 를 쓴다). */
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    private void logCurl(Prompt prompt, boolean streaming) {
        if (!log.isDebugEnabled()) return;
        try {
            String json = LlmCurlLogger.toCurlBodyJson(modelName, streaming, prompt.getInstructions(), prompt.getOptions());
            // TRACE: full replayable curl command (headers, auth, heredoc). DEBUG: just the
            // endpoint + body — the curl wrapper text adds little signal but roughly doubles the
            // line length, which was making DEBUG logs unwieldy for large RAG prompts.
            if (log.isTraceEnabled()) {
                log.trace("[LLM curl] provider={}\ncurl -s -X POST '{}' \\\n"
                        + "  -H 'Content-Type: application/json' \\\n"
                        + "  -H 'Authorization: Bearer {}' \\\n"
                        + "  -d @- << 'EOF'\n{}\nEOF",
                        providerName, endpoint, maskKey(apiKey), json);
            } else {
                log.debug("[LLM] provider={} endpoint={}\n{}", providerName, endpoint, json);
            }
        } catch (Exception e) {
            log.debug("[LLM curl] serialization error: {}", e.getMessage());
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "<api-key>";
        return key.substring(0, 4) + "****";
    }
}
