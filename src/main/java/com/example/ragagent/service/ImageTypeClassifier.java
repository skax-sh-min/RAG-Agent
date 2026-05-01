package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

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
            ChatModel model = llmRouter.route(TaskType.LIGHT_BOTH, RoutingMode.COST_FIRST);
            String result = ChatClient.builder(model).build()
                    .prompt()
                    .user(u -> u.text(PROMPT)
                                .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
                    .call()
                    .content();
            String type = result == null ? "other" : result.strip().toLowerCase();
            return VALID_TYPES.contains(type) ? type : "other";
        } catch (Exception e) {
            log.warn("Image type classification failed, defaulting to 'other': {}", e.getMessage());
            return "other";
        }
    }
}
