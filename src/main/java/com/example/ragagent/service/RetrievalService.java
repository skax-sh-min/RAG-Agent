package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.model.MetaKey;
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
        List<Document> unique;
        try {
            List<Query> queries = multiQueryExpander.expand(new Query(state.question()));
            // S-3: embed all variants in one batched call + a single Chroma query, then RRF-merge.
            List<String> queryTexts = queries.stream().map(Query::text).toList();
            List<List<Document>> ranked = ragService.searchBatch(
                    state.userId(), queryTexts, state.version(), defaultTopK);
            unique = mergeRrf(ranked, defaultTopK);
        } catch (Exception e) {
            log.warn("Multi-query expansion failed, falling back to original question: {}", e.getMessage());
            unique = ragService.search(state.userId(), state.question(), state.version(), defaultTopK);
        }

        List<SourceRef> sources = unique.stream()
                .map(d -> new SourceRef(
                        formatSource(d),
                        truncate(d.getText(), 200),
                        String.valueOf(d.getMetadata().getOrDefault(MetaKey.DOC_ID, "")),
                        d.getMetadata().getOrDefault(MetaKey.PAGE_OR_SLIDE, "?")))
                .distinct()
                .toList();

        List<String> imageRefs = unique.stream()
                .map(d -> imagePathsMeta(d.getMetadata()))
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
                .anyMatch(d -> "ocr".equals(d.getMetadata().get(MetaKey.SOURCE_TYPE)));
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
            String imgPathsMeta = imagePathsMeta(doc.getMetadata());
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

    /**
     * Reciprocal Rank Fusion — score(d) = Σ 1/(rank_i + 1 + k) across all query lists where d appears.
     * k=60 is the standard constant from the original RRF paper.
     * Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> ranked, int topK) {
        int k = 60;
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        for (List<Document> list : ranked) {
            for (int i = 0; i < list.size(); i++) {
                Document doc = list.get(i);
                String key = docKey(doc);
                scores.merge(key, 1.0 / (i + 1 + k), Double::sum);
                byKey.putIfAbsent(key, doc);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    private static String docKey(Document doc) {
        String filename = String.valueOf(doc.getMetadata().getOrDefault(MetaKey.FILENAME, ""));
        String page = String.valueOf(doc.getMetadata().getOrDefault(MetaKey.PAGE_OR_SLIDE, ""));
        String preview = doc.getText() == null ? "" : doc.getText().substring(0, Math.min(50, doc.getText().length()));
        return filename + "|" + page + "|" + preview;
    }

    private String formatSource(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String filename = String.valueOf(meta.getOrDefault(MetaKey.FILENAME, "unknown"));
        String version  = String.valueOf(meta.getOrDefault(MetaKey.VERSION, "latest"));
        Object page     = meta.getOrDefault(MetaKey.PAGE_OR_SLIDE, "?");
        return "%s | v%s | p.%s".formatted(filename, version, page);
    }

    /**
     * Safely extracts the "image_paths" metadata value. Chroma may deserialize
     * comma-joined paths as either a String or a List depending on writer/version;
     * a blind (String) cast crashes the entire retrieval on the latter.
     */
    private static String imagePathsMeta(Map<String, Object> meta) {
        Object raw = meta.get(MetaKey.IMAGE_PATHS);
        if (raw instanceof String s) return s;
        if (raw instanceof Collection<?> c) {
            return c.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return null;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
