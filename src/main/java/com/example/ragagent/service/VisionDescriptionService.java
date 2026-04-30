package com.example.ragagent.service;

import com.example.ragagent.llm.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

/**
 * Generates Korean text descriptions for images using a vision-capable LLM.
 * Routes via LlmRouter with VISION task type, falling back through LIGHT_BOTH → BOTH.
 * Returns a placeholder string when no vision provider is available.
 */
@Service
public class VisionDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescriptionService.class);

    private final LlmRouter llmRouter;

    public VisionDescriptionService(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    public String describe(byte[] imageBytes, String mimeType) {
        try {
            ChatModel visionModel = llmRouter.route(TaskType.VISION, RoutingMode.COST_FIRST);
            return ChatClient.builder(visionModel).build()
                    .prompt()
                    .user(u -> u.text("이 이미지를 한국어로 간결하게 설명하세요. 최대 3문장.")
                                .media(MimeTypeUtils.parseMimeType(mimeType), imageBytes))
                    .call()
                    .content();
        } catch (LlmProviderExhaustedException e) {
            log.warn("No vision provider available: {}", e.getMessage());
            return "[이미지 설명 불가: Vision 프로바이더 미등록]";
        } catch (Exception e) {
            log.error("Vision description failed", e);
            return "[이미지 설명 생성 오류]";
        }
    }
}
