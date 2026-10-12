package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingControl;
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

    /**
     * 출력 예약. 응답은 후보 번호 배열 하나(끔 실측 p95 150토큰)인데, 상한을 싣지 않던 동안은 프로바이더에 기동 때
     * 구워진 {@code max-tokens}(기본 12,000) 전체가 예약됐다 — 이 호출은 후보 풀(기본 30개 × 미리보기 500자)을 통째로
     * 싣는 앱에서 가장 입력이 큰 호출 중 하나라, 16k 창에서는 그 예약 때문에 후보 5개 남짓만 들어갈 자리가 남았고 나머지는
     * 컨텍스트 초과 → 원래 순서로 조용히 물러났다(PLAN §6.29 열린 항목 (d)). 값의 근거는
     * {@code ClassifierService.MAX_OUTPUT_TOKENS} 와 같다(스위치를 받지 않는 서버의 생각까지 받아 준다).
     */
    static final int MAX_OUTPUT_TOKENS = 2_048;

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

            // §6.18 — general/RAG temperature, hot (read fresh per call). §6.29 — rerank 사이트로 표시한다.
            ThinkingSite site = ThinkingSite.RERANK;
            OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                    .temperature(props.llmSafe().temperature()), site);
            int base = baseReservation(props.llmSafe().maxTokens()); // 0 = 프로바이더 기본값 유지
            if (base > 0) builder.maxTokens(base);
            OpenAiChatOptions options = builder.build();
            String response = llmRouter.executeGated(site.taskType(), site.fixedRoutingMode(),
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
     * 이 호출의 <b>기본</b> 출력 예약 — {@link #MAX_OUTPUT_TOKENS} 를 설정 상한으로 누른 것. 0 = 싣지 않는다(프로바이더
     * 기본값). 요청 옵션과 {@code /settings} 의 생각 수준 미리보기가 같은 함수를 지난다(§6.29 ⑦-바).
     */
    static int baseReservation(int configuredMaxTokens) {
        return configuredMaxTokens > 0 ? Math.min(configuredMaxTokens, MAX_OUTPUT_TOKENS) : 0;
    }

    /**
     * §10.7.1 — prepends a "{@code (filename > heading)}" context header before the preview so
     * the LLM knows which document/section a chunk came from, not just its raw text. This reuses
     * {@link KeywordExtractor#buildStructuralContext(Document)}'s deterministic baseline rather
     * than {@link MetaKey#CHUNK_CONTEXT} itself: that field is persisted, but a hit coming back
     * from the keyword (FTS) axis carries only the columns {@code chunk_fts} holds, so it is not
     * reliably present on every candidate this method formats. The structural form is derivable
     * from filename + heading, which every axis does carry.
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
