package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.security.PromptInjectionGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies the user question into one of:
 * concept | usage | error | version | meta
 *
 * <p>Only {@code meta} changes the graph's route (it skips retrieval — see {@code AgentGraph});
 * the rest are recorded on the turn.
 */
@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);
    private static final Set<String> VALID_TYPES = Set.of("concept", "usage", "error", "version", "meta");

    private record ClassifierOutput(@JsonProperty("question_type") String questionType) {}

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final BeanOutputConverter<ClassifierOutput> converter =
            new BeanOutputConverter<>(ClassifierOutput.class);

    public ClassifierService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
    }

    public String classifyOnly(String question, Locale locale) {
        String systemPrompt = messageSource.getMessage("prompt.classifier.system", null, locale);
        String userPrompt = PromptInjectionGuard.wrap(question) + "\n\n" + converter.getFormat();
        String response = llmRouter.executeGated(SITE.taskType(), SITE.fixedRoutingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        return parseType(response);
    }

    /**
     * §10.12 — 분류도 <b>독립화된 질문</b>을 본다({@link AgentState#effectiveSearchQuestion()}).
     * 재작성이 없었으면 원문이라 기존 동작 그대로다.
     *
     * <p>분류기에 이력을 통째로 넘기는 대신 이 값을 쓰는 이유는, 흐름을 바꾸는 분류가
     * {@code meta} 하나뿐이라 그 한 갈래를 위해 매 턴 이력을 5지선다에 태울 값이 없고, 애초에
     * 분류기에 필요한 것이 이력이 아니라 자립적인 질문이기 때문이다 — {@code "왜?"} 가 잡담으로
     * 읽히는 문제는 검색이 틀리는 문제와 <b>같은 값</b>으로 풀린다.
     */
    public AgentState execute(AgentState state) {
        String systemPrompt = messageSource.getMessage("prompt.classifier.system", null, state.locale());
        String userPrompt = PromptInjectionGuard.wrap(state.effectiveSearchQuestion())
                + "\n\n" + converter.getFormat();
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(SITE.taskType(), SITE.fixedRoutingMode(),
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        return state.toBuilder()
                    .accumulateTokens(result.inputTokens(), result.outputTokens())
                    .questionType(parseType(result.text()))
                    .build();
    }

    /** §6.29 — 이 호출 지점. 생각 수준(app.llm.thinking.classify)과 라우팅이 여기서 나온다. */
    private static final ThinkingSite SITE = ThinkingSite.CLASSIFY;

    /**
     * 출력 예약. 응답은 JSON 한 줄인데, 상한을 싣지 않던 동안은 프로바이더에 기동 때 구워진 {@code max-tokens}(기본
     * 12,000) 전체가 예약됐다 — 16k 창이면 그것만으로 입력 자리가 2,746 토큰으로 줄고, {@code /settings} 의
     * {@code max-tokens} 를 바꿔도 따라가지 않았다(PLAN §6.29 열린 항목 (d)).
     *
     * <p>한 줄짜리치고 큰 이유는 {@code PostAnswerService.MAX_OUTPUT_TOKENS} 와 같다 — 생각은 끄고 부르지만(출하값),
     * 그 스위치를 받지 않는 서버에서는 모델이 여전히 생각한다(낮게 실측 p95 420~970). 그 생각이 상한에 걸리면 본문이
     * 비고 분류가 {@code concept} 로 떨어진다.
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

    /** §6.18 — general/RAG temperature, hot (read fresh per call so a /settings change applies
     *  without a restart). */
    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), SITE);
        // 0 이하 = "프로바이더 기본값 유지" (AnswerService.evalOptions 와 같은 규약). 핫 — 매 호출 다시 읽는다.
        int base = baseReservation(props.llmSafe().maxTokens());
        if (base > 0) builder.maxTokens(base);
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), builder.build());
    }

    /**
     * 이 호출의 <b>기본</b> 출력 예약 — {@link #MAX_OUTPUT_TOKENS} 를 설정 상한으로 누른 것. 0 = 싣지 않는다(프로바이더
     * 기본값). 요청 옵션과 {@code /settings} 의 생각 수준 미리보기가 같은 함수를 지난다(§6.29 ⑦-바).
     */
    static int baseReservation(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? Math.min(configuredMaxTokens, MAX_OUTPUT_TOKENS) : 0;
    }

    private String parseType(String response) {
        try {
            String type = converter.convert(response).questionType().toLowerCase();
            return VALID_TYPES.contains(type) ? type : "concept";
        } catch (Exception e) {
            log.warn("Classifier output parse failed, defaulting to 'concept': {}", e.getMessage());
            return "concept";
        }
    }

}
