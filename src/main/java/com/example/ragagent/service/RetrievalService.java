package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Retrieves relevant documents from the vector store.
 *
 * Strategy: ask the LLM to generate the optimal search query, then execute
 * similarity search. Falls back to the original question if LLM fails.
 *
 * Equivalent to retrieval_node in agents.py.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final String QUERY_GEN_PROMPT = """
            사용자의 질문에서 벡터 검색에 최적화된 키워드 중심의 검색 쿼리를 생성하세요.

            예시:
            - 질문: "Spring Security에서 JWT 토큰 검증 오류가 납니다"
              → query: "Spring Security JWT 토큰 검증 오류 해결"
            - 질문: "JPA N+1 문제란?"
              → query: "JPA N+1 문제 개념 원인"
            """;

    private record QueryOutput(String query) {}

    private final ChatClient chatClient;
    private final RagService ragService;
    private final int defaultTopK;
    private final BeanOutputConverter<QueryOutput> queryConverter =
            new BeanOutputConverter<>(QueryOutput.class);

    public RetrievalService(ChatClient chatClient, RagService ragService, AppProperties props) {
        this.chatClient = chatClient;
        this.ragService = ragService;
        this.defaultTopK = props.searchTopK();
    }

    public void execute(AgentState state) {
        String query = generateQuery(state);
        List<Document> docs = ragService.search(query, state.getVersion(), defaultTopK);
        List<Document> unique = deduplicate(docs);

        List<String> sources = unique.stream()
                .map(this::formatSource)
                .distinct()
                .toList();

        List<String> warnings = new ArrayList<>(state.getRetrievalWarnings());
        boolean hasOcr = unique.stream()
                .anyMatch(d -> "ocr".equals(d.getMetadata().get("source_type")));
        if (hasOcr) {
            warnings.add("⚠️ 이 답변에는 OCR로 처리된 스캔 문서가 포함되어 있습니다. 내용이 부정확할 수 있습니다.");
        }

        state.setRetrievedDocs(unique);
        state.setSources(sources);
        state.setRetrievalWarnings(warnings);
        state.setNeedsRetry(false);
    }

    private String generateQuery(AgentState state) {
        try {
            String response = chatClient.prompt()
                    .system(QUERY_GEN_PROMPT)
                    .user(state.getQuestion() + "\n\n" + queryConverter.getFormat())
                    .call()
                    .content();
            String query = queryConverter.convert(response).query();
            return (query == null || query.isBlank()) ? state.getQuestion() : query;
        } catch (Exception e) {
            log.warn("Query generation failed, falling back to original question: {}", e.getMessage());
            return state.getQuestion();
        }
    }

    private List<Document> deduplicate(List<Document> docs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            String filename = String.valueOf(doc.getMetadata().getOrDefault("filename", ""));
            String page = String.valueOf(doc.getMetadata().getOrDefault("page_or_slide", ""));
            String preview = doc.getText() == null ? "" : doc.getText().substring(0, Math.min(50, doc.getText().length()));
            String key = filename + "|" + page + "|" + preview;
            if (seen.add(key)) result.add(doc);
        }
        return result;
    }

    private String formatSource(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String filename = String.valueOf(meta.getOrDefault("filename", "unknown"));
        String version  = String.valueOf(meta.getOrDefault("version", "latest"));
        Object page     = meta.getOrDefault("page_or_slide", "?");
        return "%s | v%s | p.%s".formatted(filename, version, page);
    }
}
