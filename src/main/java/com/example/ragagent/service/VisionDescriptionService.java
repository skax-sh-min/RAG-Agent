package com.example.ragagent.service;

import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates Korean text descriptions for images using a vision-capable LLM.
 * Routes via LlmRouter with VISION task type, falling back through LIGHT_BOTH → BOTH.
 * Returns a placeholder string when no vision provider is available.
 */
@Service
public class VisionDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(VisionDescriptionService.class);
    private static final AtomicBoolean UNSUPPORTED_VISION_LOGGED = new AtomicBoolean(false);

    private static final String NO_OPTIONS_SUFFIX =
            " 여러 선택지나 후보 설명을 나열하지 말고, 하나의 완성된 설명 문장만 작성하세요.";

    public static final Map<String, String> PROMPTS = Map.of(
            "diagram",    "이 다이어그램을 한국어로 설명하세요. 구성 요소와 흐름을 포함하여 최대 4문장." + NO_OPTIONS_SUFFIX,
            "screenshot", "이 스크린샷의 내용을 한국어로 설명하세요. 화면에 표시된 주요 정보를 최대 3문장." + NO_OPTIONS_SUFFIX,
            "chart",      "이 차트/그래프를 한국어로 분석하세요. 데이터 추세와 주요 수치를 포함하여 최대 4문장." + NO_OPTIONS_SUFFIX,
            "photo",      "이 사진을 한국어로 간결하게 설명하세요. 최대 2문장." + NO_OPTIONS_SUFFIX,
            "other",      "이 이미지를 한국어로 간결하게 설명하세요. 최대 3문장." + NO_OPTIONS_SUFFIX
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
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes));
            UserMessage userMessage = UserMessage.builder().text(prompt).media(media).build();
            String response = llmRouter.executeWithTracking(TaskType.VISION, RoutingMode.COST_FIRST,
                    model -> model.call(new Prompt(userMessage)));
            return response == null ? "" : response;
        } catch (LlmProviderExhaustedException e) {
            log.warn("No vision provider available: {}", e.getMessage());
            return "[이미지 설명 불가: Vision 프로바이더 미등록]";
        } catch (WebClientResponseException e) {
            log.error("Vision description failed: HTTP {} body={}",
                    e.getStatusCode().value(), compactBody(e.getResponseBodyAsString()));
            return "[이미지 설명 생성 오류]";
        } catch (Exception e) {
            if (isVisionInputUnsupported(e)) {
                if (UNSUPPORTED_VISION_LOGGED.compareAndSet(false, true)) {
                    log.warn("Vision model does not support image input (mmproj missing or text-only model). "
                            + "Falling back to placeholder descriptions.");
                }
                return "[이미지 설명 불가: Vision 미지원 모델]";
            }
            log.error("Vision description failed", e);
            return "[이미지 설명 생성 오류]";
        }
    }

    private boolean isVisionInputUnsupported(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                String lowered = msg.toLowerCase();
                if (lowered.contains("image input is not supported")
                        || lowered.contains("mmproj")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String compactBody(String body) {
        if (body == null || body.isBlank()) return "<empty>";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "...(truncated)" : oneLine;
    }
}
