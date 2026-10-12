package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    /**
     * 출력 예약 — 답은 단어 하나인데 상한을 싣지 않던 동안은 프로바이더에 기동 때 구워진 {@code max-tokens}(기본 12,000)
     * 전체가 예약됐다(PLAN §6.29 열린 항목 (d)). 값의 근거는 {@code ClassifierService.MAX_OUTPUT_TOKENS} 와 같다.
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

    private final LlmRouter llmRouter;
    private final AppProperties props;

    /**
     * 이 호출의 <b>기본</b> 출력 예약 — {@link #MAX_OUTPUT_TOKENS} 를 설정 상한으로 누른 것. 0 = 싣지 않는다(프로바이더
     * 기본값). 요청 옵션과 {@code /settings} 의 생각 수준 미리보기가 같은 함수를 지난다(§6.29 ⑦-바).
     */
    static int baseReservation(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? Math.min(configuredMaxTokens, MAX_OUTPUT_TOKENS) : 0;
    }

    public ImageTypeClassifier(LlmRouter llmRouter, AppProperties props) {
        this.llmRouter = llmRouter;
        this.props = props;
    }

    public String classify(byte[] imageBytes, String mimeType) {
        try {
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(imageBytes));
            UserMessage userMessage = UserMessage.builder().text(PROMPT).media(media).build();
            ThinkingSite site = ThinkingSite.IMAGE_TYPE;   // §6.29
            OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                    .temperature(props.llmSafe().indexingTemperature()), site);
            int base = baseReservation(props.llmSafe().maxTokens()); // 0 = 프로바이더 기본값 유지
            if (base > 0) builder.maxTokens(base);
            OpenAiChatOptions options = builder.build();
            String response = llmRouter.executeWithTracking(site.taskType(), site.fixedRoutingMode(),
                    model -> model.call(new Prompt(userMessage, options)));
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
