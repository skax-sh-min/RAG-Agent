package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Retrieves relevant documents from the vector store.
 *
 * Uses MultiQueryExpander to generate semantically diverse query variants from the
 * original question, then merges and deduplicates results for higher recall.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final RagService ragService;
    private final MultiQueryExpander multiQueryExpander;
    private final int defaultTopK;
    private final LazyVisionService lazyVisionService; // null when disabled

    public RetrievalService(ChatModel chatModel, RagService ragService, AppProperties props,
                            Optional<LazyVisionService> lazyVisionOpt) {
        this.ragService = ragService;
        this.defaultTopK = props.searchTopK();
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .includeOriginal(true)
                .numberOfQueries(2)
                .build();
    }

    public AgentState execute(AgentState state) {
        List<Document> allDocs = new ArrayList<>();
        try {
            List<Query> queries = multiQueryExpander.expand(new Query(state.question()));
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                queries.stream()
                    .map(q -> CompletableFuture.supplyAsync(
                        () -> ragService.search(q.text(), state.version(), defaultTopK), exec))
                    .toList()
                    .forEach(f -> allDocs.addAll(f.join()));
            }
        } catch (Exception e) {
            log.warn("Multi-query expansion failed, falling back to original question: {}", e.getMessage());
            allDocs.addAll(ragService.search(state.question(), state.version(), defaultTopK));
        }

        List<Document> unique = deduplicate(allDocs);

        List<SourceRef> sources = unique.stream()
                .map(d -> new SourceRef(
                        formatSource(d),
                        truncate(d.getText(), 200),
                        String.valueOf(d.getMetadata().getOrDefault("doc_id", "")),
                        d.getMetadata().getOrDefault("page_or_slide", "?")))
                .distinct()
                .toList();

        List<String> imageRefs = unique.stream()
                .map(d -> (String) d.getMetadata().get("image_paths"))
                .filter(p -> p != null && !p.isBlank())
                .flatMap(p -> Arrays.stream(p.split(",")))
                .map(String::strip)
                .filter(p -> !p.isBlank())
                .distinct()
                .toList();

        List<Document> contextDocs = unique;
        if (!imageRefs.isEmpty() && lazyVisionService != null) {
            Map<String, String> descs = lazyVisionService.describeIfNeeded(imageRefs);
            if (!descs.isEmpty()) contextDocs = augmentWithDescriptions(unique, descs);
        }

        List<String> warnings = new ArrayList<>(state.retrievalWarnings());
        boolean hasOcr = unique.stream()
                .anyMatch(d -> "ocr".equals(d.getMetadata().get("source_type")));
        if (hasOcr) {
            warnings.add("⚠️ 이 답변에는 OCR로 처리된 스캔 문서가 포함되어 있습니다. 내용이 부정확할 수 있습니다.");
        }

        return state
                .withRetrievedDocs(contextDocs)
                .withSources(sources)
                .withRetrievalWarnings(warnings)
                .withImageRefs(imageRefs)
                .withNeedsRetry(false);
    }

    private List<Document> augmentWithDescriptions(List<Document> docs, Map<String, String> descriptions) {
        return docs.stream().map(doc -> {
            String text = doc.getText();
            if (text == null) return doc;

            String augmented = text;
            // Inline marker replacement (DOCX)
            for (Map.Entry<String, String> e : descriptions.entrySet()) {
                String marker = "[이미지: " + e.getKey() + "]";
                if (augmented.contains(marker)) {
                    augmented = augmented.replace(marker, marker + "\n설명: " + e.getValue());
                }
            }

            // Append for docs without inline markers (PPTX/PDF)
            String imgPathsMeta = (String) doc.getMetadata().get("image_paths");
            if (imgPathsMeta != null && !imgPathsMeta.isBlank()) {
                StringBuilder appendix = new StringBuilder();
                for (String p : imgPathsMeta.split(",")) {
                    p = p.strip();
                    String desc = descriptions.get(p);
                    if (desc != null && !augmented.contains("[이미지: " + p + "]")) {
                        appendix.append("\n[이미지 설명: ").append(desc).append("]");
                    }
                }
                if (!appendix.isEmpty()) augmented = augmented + appendix;
            }

            return augmented.equals(text) ? doc : new Document(augmented, doc.getMetadata());
        }).toList();
    }

    private List<Document> deduplicate(List<Document> docs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Document> result = new ArrayList<>();
        for (Document doc : docs) {
            if (result.size() >= defaultTopK) break;
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

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
