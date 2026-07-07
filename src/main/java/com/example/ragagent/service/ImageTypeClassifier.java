package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Set;

/**
 * Classifies an image into one of: diagram / screenshot / chart / photo / other.
 * Uses LIGHT_BOTH model via LlmRouter for efficiency.
 * Active only when app.image-description.enabled=true.
 */
@Service
@ConditionalOnProperty(name = "app.image-description.enabled", havingValue = "true")
public class ImageTypeClassifier {

    private static final Logger log = LoggerFactory.getLogger(ImageTypeClassifier.class);

    private static final Set<String> VALID_TYPES = Set.of("diagram", "screenshot", "chart", "photo", "other");

    private static final String PROMPT = """
            이 이미지의 유형을 다음 중 하나로만 답하세요 (영어 소문자):
            diagram, screenshot, chart, photo, other
            다른 설명 없이 단어 하나만 출력하세요.
            """;

    private final LlmRouter llmRouter;

    public ImageTypeClassifier(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    public String classify(byte[] imageBytes, String mimeType) {
        try {
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes));
            UserMessage userMessage = UserMessage.builder().text(PROMPT).media(media).build();
            String response = llmRouter.executeWithTracking(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST,
                    model -> model.call(new Prompt(userMessage)));
            String type = response == null ? "other" : response.strip().toLowerCase();
            return VALID_TYPES.contains(type) ? type : "other";
        } catch (WebClientResponseException e) {
            log.warn("Image type classification failed: HTTP {} body={} (defaulting to 'other')",
                    e.getStatusCode().value(), compactBody(e.getResponseBodyAsString()));
            return "other";
        } catch (Exception e) {
            log.warn("Image type classification failed, defaulting to 'other': {}", e.getMessage());
            return "other";
        }
    }

    private static String compactBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "...(truncated)" : oneLine;
    }
}
