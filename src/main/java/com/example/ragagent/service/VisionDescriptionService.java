package com.example.ragagent.service;

import com.example.ragagent.llm.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.Map;

/**
 * Generates Korean text descriptions for images using a vision-capable LLM.
 * Routes via LlmRouter with VISION task type, falling back through LIGHT_BOTH → BOTH.
 * Returns a placeholder string when no vision provider is available.
 */
@Service
public class VisionDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescriptionService.class);

    public static final Map<String, String> PROMPTS = Map.of(
            "diagram",    "이 다이어그램을 한국어로 설명하세요. 구성 요소와 흐름을 포함하여 최대 4문장.",
            "screenshot", "이 스크린샷의 내용을 한국어로 설명하세요. 화면에 표시된 주요 정보를 최대 3문장.",
            "chart",      "이 차트/그래프를 한국어로 분석하세요. 데이터 추세와 주요 수치를 포함하여 최대 4문장.",
            "photo",      "이 사진을 한국어로 간결하게 설명하세요. 최대 2문장.",
            "other",      "이 이미지를 한국어로 간결하게 설명하세요. 최대 3문장."
    );

    private final LlmRouter llmRouter;

    public VisionDescriptionService(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    public String describe(byte[] imageBytes, String mimeType) {
        return describe(imageBytes, mimeType, PROMPTS.get("other"));
    }

    public String describe(byte[] imageBytes, String mimeType, String prompt) {
        try {
            ChatModel visionModel = llmRouter.route(TaskType.VISION, RoutingMode.COST_FIRST);
            return ChatClient.builder(visionModel).build()
                    .prompt()
                    .user(u -> u.text(prompt)
                                .media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes)))
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
