package com.example.ragagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

/**
 * Transparent {@link EmbeddingModel} decorator — {@link LoggingChatModel}'s counterpart for
 * embedding calls. At DEBUG level, logs a ready-to-run curl command for every request so
 * embedding traffic shows up in DEBUG logs the same way chat traffic already does (previously
 * embedding requests were invisible regardless of log level).
 *
 * <p>Wraps the raw per-endpoint {@link org.springframework.ai.openai.OpenAiEmbeddingModel} in
 * {@code EmbeddingBeanConfig.buildRawModel()} — i.e. before {@link TrackingEmbeddingModel} /
 * {@link CachingEmbeddingModel} / {@link LoadBalancingEmbeddingModel} — so a cache hit (no real
 * HTTP call) logs nothing, and with §6.21 E1 multi-endpoint load balancing each endpoint's own
 * base-url shows up correctly in its own curl command.
 */
public class LoggingEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmbeddingModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EmbeddingModel delegate;
    private final String providerName;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;

    public LoggingEmbeddingModel(EmbeddingModel delegate, String providerName,
                                 String baseUrl, String apiKey, String modelName) {
        this.delegate     = delegate;
        this.providerName = providerName;
        this.endpoint     = baseUrl.replaceAll("/+$", "") + "/embeddings";
        this.apiKey       = apiKey;
        this.modelName    = modelName;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        logCurl(request);
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        return this.embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    private void logCurl(EmbeddingRequest request) {
        if (!log.isDebugEnabled()) return;
        try {
            List<String> texts = request.getInstructions();
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelName);
            ArrayNode input = body.putArray("input");
            for (String text : texts) input.add(text);

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            // TRACE: full replayable curl command. DEBUG: just endpoint + body — see
            // LoggingChatModel.logCurl() for why (same DEBUG-log-too-long complaint applies here).
            if (log.isTraceEnabled()) {
                log.trace("[EMBED curl] provider={} texts={}\ncurl -s -X POST '{}' \\\n"
                        + "  -H 'Content-Type: application/json' \\\n"
                        + "  -H 'Authorization: Bearer {}' \\\n"
                        + "  -d @- << 'EOF'\n{}\nEOF",
                        providerName, texts.size(), endpoint, maskKey(apiKey), json);
            } else {
                log.debug("[EMBED] provider={} texts={} endpoint={}\n{}", providerName, texts.size(), endpoint, json);
            }
        } catch (Exception e) {
            log.debug("[EMBED curl] serialization error: {}", e.getMessage());
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "<api-key>";
        return key.substring(0, 4) + "****";
    }
}
