package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.security.PromptInjectionGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
        StringBuilder buf = new StringBuilder();
        chatClient.prompt()
                .system(prompt)
                .user(PromptInjectionGuard.wrap(question) + "\n\n" + converter.getFormat())
                .stream().content().doOnNext(buf::append).blockLast();
        return parseType(buf.isEmpty() ? null : buf.toString());
    }

    public AgentState execute(AgentState state) {
        String prompt = messageSource.getMessage("prompt.classifier.system", null, state.locale());
        StringBuilder buf = new StringBuilder();
        chatClient.prompt()
                .system(prompt)
                .user(PromptInjectionGuard.wrap(state.question()) + "\n\n" + converter.getFormat())
                .stream().content().doOnNext(buf::append).blockLast();
        return state.toBuilder()
                    .accumulateTokens(0, 0)
                    .questionType(parseType(buf.isEmpty() ? null : buf.toString()))
                    .build();
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
