package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * LLM-based reranking of retrieved document candidates.
 *
 * <p>Uses a single LLM call to score and reorder candidates by relevance to the question,
 * then returns the top-k. Registered as a bean only when reranking is enabled
 * ({@code app.search-rerank-enabled=true}); {@link RetrievalService} receives it as
 * {@code Optional<RerankerService>} and skips reranking when absent.
 *
 * <p>Fail-safe: any parse error falls back to the original order.
 */
@Service
@ConditionalOnProperty(name = "app.search-rerank-enabled", havingValue = "true")
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    // §10.7.1 — was 200 (often cut off an 800-char chunk's substance); widened so the LLM
    // sees enough of the chunk to judge relevance without a large token-budget increase.
    private static final int PREVIEW_LENGTH = 500;

    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final AppProperties props;

    public RerankerService(LlmRouter llmRouter, MessageSource messageSource, AppProperties props) {
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.props = props;
    }

    /**
     * Reranks {@code candidates} by relevance to {@code question} and returns the top {@code topK}.
     * Falls back to original order on any error.
     */
    public List<Document> rerank(String question, List<Document> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        if (candidates.size() <= topK) return candidates;

        try {
            String systemPrompt = messageSource.getMessage("prompt.rerank", null, Locale.KOREAN);
            String userContent = "[질문]\n%s\n\n[문서 목록]\n%s"
                    .formatted(question, formatDocList(candidates));

            // §6.18 — general/RAG temperature, hot (read fresh per call).
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(props.llmSafe().temperature())
                    .build();
            String response = llmRouter.executeGated(TaskType.TEXT, RoutingMode.COST_FIRST,
                    model -> model.call(new Prompt(List.of(
                            new SystemMessage(systemPrompt), new UserMessage(userContent)), options)));

            List<Integer> ranking = parseRanking(response == null ? "" : response, candidates.size());
            List<Document> reranked = ranking.stream()
                    .filter(i -> i >= 0 && i < candidates.size())
                    .distinct()
                    .limit(topK)
                    .map(candidates::get)
                    .toList();

            if (reranked.isEmpty()) {
                log.warn("[Reranker] Empty ranking result, falling back to original order");
                return candidates.subList(0, Math.min(topK, candidates.size()));
            }
            log.debug("[Reranker] Reranked {} candidates → top {}", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("[Reranker] Failed, falling back to original order: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    /**
     * §10.7.1 — prepends a "{@code (filename > heading)}" context header before the preview so
     * the LLM knows which document/section a chunk came from, not just its raw text. This reuses
     * {@link KeywordExtractor#buildStructuralContext(Document)}'s deterministic baseline rather
     * than {@link MetaKey#CHUNK_CONTEXT} itself — that LLM-enhanced field is transient (§10.1,
     * stripped before persistence in both vector store providers) and is never present on a
     * Document once it comes back from a search.
     * Package-private for unit testing.
     */
    static String formatDocList(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String text = doc.getText();
            String preview = (text == null) ? "" : text.substring(0, Math.min(PREVIEW_LENGTH, text.length()));
            String context = KeywordExtractor.buildStructuralContext(doc);
            String header = context.isBlank() ? "" : "(%s) ".formatted(context);
            sb.append("[%d] %s%s\n".formatted(i, header, preview));
        }
        return sb.toString();
    }

    /**
     * Parses a JSON integer array like {@code [2, 0, 3, 1]} from the LLM response.
     * Returns an empty list on any parse failure.
     * Package-private for unit testing.
     */
    static List<Integer> parseRanking(String response, int maxIndex) {
        if (response == null || response.isBlank()) return List.of();
        try {
            String s = response.strip();
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();
            String inner = s.substring(start + 1, end).strip();
            if (inner.isBlank()) return List.of();
            return Arrays.stream(inner.split(","))
                    .map(String::strip)
                    .map(tok -> {
                        try { return Integer.parseInt(tok); }
                        catch (NumberFormatException e) { return -1; }
                    })
                    .filter(i -> i >= 0 && i < maxIndex)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
