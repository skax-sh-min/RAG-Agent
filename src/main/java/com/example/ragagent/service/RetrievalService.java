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
    private final boolean multiqueryEnabled;
    private final int multiqueryMinLength;
    private final boolean hybridEnabled;
    private final boolean retryEscalate;
    private final boolean rerankEnabled;
    private final int candidateMultiplier;
    private final int tagCandidateMultiplier;          // Step 5.9: candidate expansion when tags selected
    private final LazyVisionService lazyVisionService; // null when disabled
    private final Optional<RerankerService> reranker;

    public RetrievalService(ChatModel chatModel, RagService ragService, AppProperties props,
                            Optional<LazyVisionService> lazyVisionOpt,
                            Optional<RerankerService> rerankerOpt) {
        this.ragService = ragService;
        this.defaultTopK = props.searchTopK();
        this.multiqueryEnabled = props.searchMultiqueryEnabled();
        this.multiqueryMinLength = props.searchMultiqueryMinLengthSafe();
        this.hybridEnabled = props.searchHybridEnabled();
        this.retryEscalate = props.searchRetryEscalate();
        this.rerankEnabled = props.searchRerankEnabled();
        this.candidateMultiplier = props.searchCandidateMultiplierSafe();
        this.tagCandidateMultiplier = props.searchTagCandidateMultiplierSafe();
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.reranker = rerankerOpt;
        this.multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .includeOriginal(true)
                .numberOfQueries(2)
                .build();
    }

    public AgentState execute(AgentState state) {
        // Step 5.9: normalized search-scope tags (empty → version-only behavior, unchanged).
        List<String> selectedTags = com.example.ragagent.model.TagUtils.parseTagList(state.selectedTags());
        List<Document> unique;
        try {
            // S-2: escalate candidate count on retry to surface different documents.
            int retry = state.retryCount();
            int candidateK = (retryEscalate && retry > 0)
                    ? Math.min(defaultTopK * (retry + 1), defaultTopK * 3)
                    : defaultTopK;
            // R-3: expand candidate pool further when reranking is active.
            if (rerankEnabled && reranker.isPresent()) {
                candidateK = Math.max(candidateK, defaultTopK * candidateMultiplier);
            }
            // Step 5.9: pre-expand the candidate pool when tags are selected (strict post-filter
            // shrinks the pool; fetch more up-front in one shot — no provider/LLM re-call).
            if (!selectedTags.isEmpty()) {
                candidateK = Math.max(candidateK, defaultTopK * tagCandidateMultiplier);
            }

            // S-4: skip the expansion LLM call for disabled mode or short keyword-ish queries.
            List<String> queryTexts = shouldExpand(state.question())
                    ? multiQueryExpander.expand(new Query(state.question())).stream().map(Query::text).toList()
                    : List.of(state.question());
            // S-3: embed all variants in one batched call + a single Chroma query, then RRF-merge.
            List<List<Document>> ranked = ragService.searchBatch(
                    state.userId(), queryTexts, state.version(), candidateK);
            // R-2: add a BM25 keyword axis to the fusion when hybrid search is enabled.
            if (hybridEnabled) {
                List<Document> keywordHits = ragService.keywordSearch(
                        state.version(), state.question(), candidateK);
                if (!keywordHits.isEmpty()) {
                    ranked = new ArrayList<>(ranked);
                    ranked.add(keywordHits);
                }
            }
            List<Document> candidates = mergeRrf(ranked, candidateK);
            // Step 5.9: strict AND tag filter — applied after RRF, before rerank/cut. Covers vector
            // + BM25 axes uniformly (tags travel in chunk metadata). No no-tag fallback on shortfall.
            candidates = filterByTags(candidates, selectedTags, candidateK);
            // R-3: rerank by LLM relevance, then cut to defaultTopK.
            unique = (rerankEnabled && reranker.isPresent())
                    ? reranker.get().rerank(state.question(), candidates, defaultTopK)
                    : candidates.subList(0, Math.min(defaultTopK, candidates.size()));
        } catch (Exception e) {
            log.warn("Multi-query expansion failed, falling back to original question: {}", e.getMessage());
            // Step 5.9: keep the tag scope on the fallback path too (else tags leak through on error).
            int fallbackK = selectedTags.isEmpty() ? defaultTopK
                    : Math.max(defaultTopK, defaultTopK * tagCandidateMultiplier);
            List<Document> fallback = ragService.search(state.userId(), state.question(), state.version(), fallbackK);
            fallback = filterByTags(fallback, selectedTags, defaultTopK);
            unique = fallback.subList(0, Math.min(defaultTopK, fallback.size()));
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

        return state.toBuilder()
                .retrievedDocs(contextDocs)
                .sources(sources)
                .retrievalWarnings(warnings)
                .imageRefs(imageRefs)
                .needsRetry(false)
                .build();
    }

    /**
     * S-4: gate the multi-query expansion LLM call. Skips when disabled or when the
     * question is shorter than the configured min length (short keyword-ish queries gain
     * little from expansion but pay the LLM round-trip on the critical path).
     * Package-private for unit testing.
     */
    boolean shouldExpand(String question) {
        if (!multiqueryEnabled) return false;
        if (question == null) return false;
        return question.strip().length() >= multiqueryMinLength;
    }

    /**
     * Step 5.9: strict AND tag filter over already-retrieved candidates. A chunk passes only when
     * its {@code tags} metadata contains every selected tag. Empty selection → pass-through
     * (version-only behavior). Never falls back to unfiltered results on shortfall.
     * Package-private for unit testing.
     */
    List<Document> filterByTags(List<Document> candidates, List<String> selectedTags, int candidateK) {
        if (selectedTags == null || selectedTags.isEmpty()) return candidates;
        int before = candidates.size();
        List<Document> filtered = candidates.stream()
                .filter(d -> com.example.ragagent.model.TagUtils.matchesAnd(
                        com.example.ragagent.model.TagUtils.parseTagList(d.getMetadata().get(MetaKey.TAGS)),
                        selectedTags))
                .toList();
        log.debug("[TAG] selectedTags={} candidateK={} postFilter={}/{}",
                selectedTags, candidateK, filtered.size(), before);
        return filtered;
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

    /**
     * R-4: stable dedup key. Prefers {@code doc_id:chunk_index} (set at index time and
     * shared across vector + keyword sources for hybrid fusion); falls back to the legacy
     * filename|page|preview for chunks indexed before chunk_index existed.
     */
    static String docKey(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        Object docId = meta.get(MetaKey.DOC_ID);
        Object chunkIdx = meta.get(MetaKey.CHUNK_INDEX);
        if (docId != null && chunkIdx != null) {
            return docId + ":" + normalizeIndex(chunkIdx);
        }
        String filename = String.valueOf(meta.getOrDefault(MetaKey.FILENAME, ""));
        String page = String.valueOf(meta.getOrDefault(MetaKey.PAGE_OR_SLIDE, ""));
        String preview = doc.getText() == null ? "" : doc.getText().substring(0, Math.min(50, doc.getText().length()));
        return filename + "|" + page + "|" + preview;
    }

    /** Normalizes a chunk index from any source (Integer/Double/String) to a canonical int string. */
    private static String normalizeIndex(Object idx) {
        if (idx instanceof Number n) return Integer.toString(n.intValue());
        String s = idx.toString().trim();
        try {
            return Integer.toString((int) Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return s;
        }
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
