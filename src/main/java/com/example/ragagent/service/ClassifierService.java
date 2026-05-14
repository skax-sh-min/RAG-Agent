package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

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

    private final ChatClient chatClient;
    private final MessageSource messageSource;
    private final BeanOutputConverter<ClassifierOutput> converter =
            new BeanOutputConverter<>(ClassifierOutput.class);

    public ClassifierService(ChatClient chatClient, MessageSource messageSource) {
        this.chatClient = chatClient;
        this.messageSource = messageSource;
    }

    public String classifyOnly(String question, Locale locale) {
        String prompt = messageSource.getMessage("prompt.classifier.system", null, locale);
        String raw = chatClient.prompt()
                .system(prompt)
                .user(question + "\n\n" + converter.getFormat())
                .call()
                .content();
        return parseType(raw);
    }

    public AgentState execute(AgentState state) {
        String prompt = messageSource.getMessage("prompt.classifier.system", null, state.locale());
        ChatResponse chatResponse = chatClient.prompt()
                .system(prompt)
                .user(state.question() + "\n\n" + converter.getFormat())
                .call()
                .chatResponse();

        state = accumulateTokens(state, chatResponse);
        return state.withQuestionType(parseType(ChatResponses.safeText(chatResponse)));
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

    private static AgentState accumulateTokens(AgentState state, ChatResponse resp) {
        var usage = resp.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        return state.withTokensAccumulated(in, out);
    }
}
