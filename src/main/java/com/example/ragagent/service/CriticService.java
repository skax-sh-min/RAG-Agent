package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Validates that the generated answer is grounded in the retrieved documents.
 * If ungrounded, sets needsRetry=true (within shared retry budget).
 *
 * Equivalent to critic_node in agents.py.
 */
@Service
public class CriticService {

    private static final Logger log = LoggerFactory.getLogger(CriticService.class);

    private record CriticOutput(boolean grounded) {}

    private final ChatClient chatClient;
    private final MessageSource messageSource;
    private final BeanOutputConverter<CriticOutput> converter =
            new BeanOutputConverter<>(CriticOutput.class);

    public CriticService(ChatClient chatClient, MessageSource messageSource) {
        this.chatClient = chatClient;
        this.messageSource = messageSource;
    }

    public AgentState execute(AgentState state) {
        if (state.retrievedDocs().isEmpty()) {
            return state.withNeedsRetry(false);
        }

        String excerpts = state.retrievedDocs().stream()
                .limit(5)
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = """
                [문서 발췌]
                %s

                [답변]
                %s

                %s
                """.formatted(excerpts, state.answer(), converter.getFormat());

        String systemPrompt = messageSource.getMessage("prompt.critic.system", null, state.locale());
        ChatResponse chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

        state = accumulateTokens(state, chatResponse);
        boolean grounded = parseGrounded(ChatResponses.safeText(chatResponse));
        return state.withGrounded(grounded).withNeedsRetry(!grounded);
    }

    private boolean parseGrounded(String response) {
        try {
            return converter.convert(response).grounded();
        } catch (Exception e) {
            log.warn("Critic output parse failed, treating as grounded: {}", e.getMessage());
            return true;
        }
    }

    private static AgentState accumulateTokens(AgentState state, ChatResponse resp) {
        var usage = resp.getMetadata().getUsage();
        int in  = (usage != null && usage.getPromptTokens()     != null) ? usage.getPromptTokens()     : 0;
        int out = (usage != null && usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
        return state.withTokensAccumulated(in, out);
    }
}
