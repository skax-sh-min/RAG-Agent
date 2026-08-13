package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
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
 * Equivalent to classifier_node in agents.py.
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
        String response = llmRouter.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        return parseType(response);
    }

    public AgentState execute(AgentState state) {
        String systemPrompt = messageSource.getMessage("prompt.classifier.system", null, state.locale());
        String userPrompt = PromptInjectionGuard.wrap(state.question()) + "\n\n" + converter.getFormat();
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(TaskType.TEXT, RoutingMode.COST_FIRST,
                model -> model.call(buildPrompt(systemPrompt, userPrompt)));
        return state.toBuilder()
                    .accumulateTokens(result.inputTokens(), result.outputTokens())
                    .questionType(parseType(result.text()))
                    .build();
    }

    /** §6.18 — general/RAG temperature, hot (read fresh per call so a /settings change applies
     *  without a restart). */
    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature())
                .build();
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), options);
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
