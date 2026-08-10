package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.ConcurrencyLimitingChatModel;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.TrackingChatModel;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.context.MessageSource;
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
    private final AppProperties props;
    // rerank-enabled is truly structural — the RerankerService bean only exists when it was true at
    // startup (@ConditionalOnProperty), so it can't be hot-swapped; cache it once. topK, multiquery-
    // enabled and hybrid-enabled are now hot-editable and are read fresh from props.xxxSafe() on every
    // execute()/shouldExpand() (alongside retry-escalate, candidate/tag multipliers, RRF k/weight,
    // multiquery min-length) so a /settings override applies on the next search — none are cached.
    private final boolean rerankEnabled;
    private final LazyVisionService lazyVisionService; // null when disabled
    private final Optional<RerankerService> reranker;
    private final ChatImageAnalysisSkipRegistry imageSkipRegistry;

    public RetrievalService(LlmRouter llmRouter, LlmUsageRepository usageRepo, RagService ragService,
                            AppProperties props, Optional<LazyVisionService> lazyVisionOpt,
                            Optional<RerankerService> rerankerOpt, MessageSource messageSource,
                            ChatImageAnalysisSkipRegistry imageSkipRegistry) {
        this.ragService = ragService;
        this.props = props;
        this.rerankEnabled = props.searchRerankEnabled();
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.reranker = rerankerOpt;
        this.imageSkipRegistry = imageSkipRegistry;
        // MultiQueryExpander builds its own ChatClient around the model it's given, so the
        // only way to have its calls recorded in llm_usage is to wrap that model (mirrors
        // TrackingEmbeddingModel's decorator for embeddings). §6.21 (작업2) — query expansion is a
        // reasoning-free chore, so prefer MICRO_TEXT (the dedicated small model when a type=MICRO_TEXT
        // provider is registered) → LIGHT_TEXT → TEXT. Without a small model, MICRO_TEXT/LIGHT_TEXT
        // resolve to the local BOTH model (unchanged); TEXT is the final fallback for cloud-only
        // (TEXT-typed providers, no LOCAL) setups so construction never fails.
        LlmProvider expansionProvider = llmRouter.routeProviderWithFallback(
                List.of(TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT), RoutingMode.COST_FIRST);
        // Gate this persistent model too: MultiQueryExpander calls it internally at a
        // point RetrievalService doesn't control, so executeGated() can't wrap the call site.
        ChatModel gatedExpansionModel =
                new ConcurrencyLimitingChatModel(expansionProvider.chatModel(), expansionProvider, llmRouter);
        ChatModel trackedExpansionModel =
                new TrackingChatModel(gatedExpansionModel, expansionProvider.name(), usageRepo);
        // Swap Spring AI's default (English, diversity-only) expansion prompt for a Korean one that
        // also asks the model to normalize the question toward embedding-search-friendly phrasing
        // (strip filler/honorifics, resolve pronouns) — not just paraphrase it. The app has no
        // per-request locale variance in practice (ThreadContext defaults to Locale.KOREAN), and this
        // expander is built once at bean construction, so a fixed locale here is fine.
        PromptTemplate expansionPromptTemplate = PromptTemplate.builder()
                .template(messageSource.getMessage("prompt.retrieval.expansion", null, Locale.KOREAN))
                .build();
        this.multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(trackedExpansionModel))
                .promptTemplate(expansionPromptTemplate)
                .includeOriginal(true)
                .numberOfQueries(2)
                .build();
    }

    public AgentState execute(AgentState state) {
        return execute(state, GraphListener.NOOP);
    }

    /**
     * Same as {@link #execute(AgentState)}, but reports Lazy Vision progress through
     * {@code listener} (see {@link GraphListener#onImageAnalysisProgress}) — {@code AgentGraph}
     * passes {@link GraphListener#NOOP} on the blocking path, same zero-overhead convention as
     * the other nodes.
     */
    public AgentState execute(AgentState state, GraphListener listener) {
        // normalized search-scope tags (empty → version-only behavior, unchanged).
        List<String> selectedTags = com.example.ragagent.model.TagUtils.parseTagList(state.selectedTags());
        // Read hot-editable tuning fresh each call so /settings overrides apply live.
        boolean retryEscalate = props.searchRetryEscalateSafe();
        int candidateMultiplier = props.searchCandidateMultiplierSafe();
        int tagCandidateMultiplier = props.searchTagCandidateMultiplierSafe();
        int rrfK = props.searchRrfKSafe();
        double rrfKeywordWeight = props.searchRrfKeywordWeightSafe();
        int defaultTopK = props.searchTopKSafe();
        boolean hybridEnabled = props.searchHybridEnabledSafe();
        boolean curatedQaEnabled = props.searchCuratedQaEnabledSafe();
        double curatedQaWeight = props.searchCuratedQaWeightSafe();
        double submissionWeight = props.searchSubmissionWeightSafe();
        int retry = state.retryCount();
        // Escalating candidateK alone only changed WHICH documents competed for the final slots —
        // the cut stayed at defaultTopK, so every attempt handed the answer node exactly topK
        // documents. When the evidence a retry was supposed to surface lands just past that cut,
        // the retry re-fails for the same reason and burns the whole retry budget. The final cut
        // therefore grows too, by one document per retry (topK + retryCount): enough to let a
        // near-miss chunk in, small enough that the answer prompt does not balloon the way the
        // ×(retryCount+1) candidate escalation would. Gated by the same app.search-retry-escalate
        // flag — turning escalation off must switch off the whole behavior, not half of it.
        int effectiveTopK = retryEscalate ? defaultTopK + retry : defaultTopK;
        List<Document> unique;
        try {
            // Escalate candidate count on retry to surface different documents.
            int candidateK = (retryEscalate && retry > 0)
                    ? Math.min(defaultTopK * (retry + 1), defaultTopK * 3)
                    : defaultTopK;
            // The pool can never be smaller than the cut taken from it (possible only in extreme
            // configs, e.g. topK=1 with several retries).
            candidateK = Math.max(candidateK, effectiveTopK);
            // Expand candidate pool further when reranking is active.
            if (rerankEnabled && reranker.isPresent()) {
                candidateK = Math.max(candidateK, defaultTopK * candidateMultiplier);
            }
            // Pre-expand the candidate pool when tags are selected (strict post-filter
            // shrinks the pool; fetch more up-front in one shot — no provider/LLM re-call).
            if (!selectedTags.isEmpty()) {
                candidateK = Math.max(candidateK, defaultTopK * tagCandidateMultiplier);
            }

            // §10.8.1: the expansion LLM call used to sit in front of every search, including the
            // original-question search that doesn't need it. Start the original-query vector search
            // (and the keyword axis) on virtual threads immediately; expand() still runs on the
            // calling thread, but its latency is now overlapped instead of serialized in front.
            // Only the variant queries (which don't exist until expand() returns) search afterward.
            int fetchK = candidateK;
            List<List<Document>> ranked;
            List<Document> keywordHits;
            List<Document> curatedHits;
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<List<Document>> keywordF = CompletableFuture.supplyAsync(
                        () -> hybridEnabled
                                ? ragService.keywordSearch(state.version(), state.question(), fetchK)
                                : List.<Document>of(),
                        exec);
                // §10.10 — curated-Q&A axis: single search against the original question (not
                // multi-query variants — the curated pool is small and question-driven matching is
                // the point), scoped to the reserved "curated" version namespace.
                CompletableFuture<List<Document>> curatedF = CompletableFuture.supplyAsync(
                        () -> curatedQaEnabled
                                ? ragService.search(state.userId(), state.question(), CuratedQaService.CURATED_VERSION, fetchK)
                                : List.<Document>of(),
                        exec);
                if (shouldExpand(state.question())) {
                    CompletableFuture<List<Document>> originalF = CompletableFuture.supplyAsync(
                            () -> ragService.search(state.userId(), state.question(), state.version(), fetchK),
                            exec);
                    // Wrap for the LLM-facing expansion prompt only (delimiter isolation, same as every
                    // other prompt-construction site) — the vector search axes above still embed the
                    // raw state.question() untouched. includeOriginal(true) echoes back exactly the
                    // wrapped string as one "variant", so filter against that same wrapped string
                    // (not the raw question) to strip it before the variant-only batch search below.
                    String expansionInput = PromptInjectionGuard.wrap(state.question());
                    List<String> variantTexts = multiQueryExpander.expand(new Query(expansionInput)).stream()
                            .map(Query::text)
                            .filter(t -> !t.equals(expansionInput))
                            .toList();
                    ranked = new ArrayList<>();
                    ranked.add(originalF.join());
                    if (!variantTexts.isEmpty()) {
                        ranked.addAll(ragService.searchBatch(state.userId(), variantTexts, state.version(), candidateK));
                    }
                } else {
                    ranked = ragService.searchBatch(state.userId(), List.of(state.question()), state.version(), candidateK);
                }
                keywordHits = keywordF.join();
                curatedHits = curatedF.join();
            }
            // 큐레이션 네임스페이스 한 번의 검색 결과를 출처별로 갈라 서로 다른 가중치의 두 축으로 넣는다
            // — 좋아요 승격(검증된 답변)과 지식 제안(사람이 직접 쓴 지식)은 신뢰 수준이 달라 별도 튜닝
            // 대상이기 때문. 각 부분 리스트의 순위는 원래 순서를 유지하므로 축 안에서의 RRF 순위는 그대로다.
            List<Document> likeHits = curatedHitsOfOrigin(curatedHits, CuratedQaRepository.ORIGIN_MANUAL, false);
            List<Document> submissionHits = curatedHitsOfOrigin(curatedHits, CuratedQaRepository.ORIGIN_MANUAL, true);
            List<Document> candidates = mergeRrf(ranked, keywordHits, likeHits, submissionHits,
                    candidateK, rrfK, rrfKeywordWeight, curatedQaWeight, submissionWeight);
            // Strict AND tag filter — applied after RRF, before rerank/cut. Covers vector
            // + BM25 axes uniformly (tags travel in chunk metadata). No no-tag fallback on shortfall.
            candidates = filterByTags(candidates, selectedTags, candidateK);
            // Rerank by LLM relevance, then cut to effectiveTopK (= topK on the first attempt).
            unique = (rerankEnabled && reranker.isPresent())
                    ? reranker.get().rerank(state.question(), candidates, effectiveTopK)
                    : candidates.subList(0, Math.min(effectiveTopK, candidates.size()));
        } catch (Exception e) {
            log.warn("Multi-query expansion failed, falling back to original question: {}", e.getMessage());
            // Keep the tag scope on the fallback path too (else tags leak through on error).
            // Cut to effectiveTopK as well, so a retry is not silently de-escalated by whichever
            // attempt happens to lose the expansion LLM call.
            int fallbackK = selectedTags.isEmpty() ? effectiveTopK
                    : Math.max(effectiveTopK, defaultTopK * tagCandidateMultiplier);
            List<Document> fallback = ragService.search(state.userId(), state.question(), state.version(), fallbackK);
            fallback = filterByTags(fallback, selectedTags, effectiveTopK);
            unique = fallback.subList(0, Math.min(effectiveTopK, fallback.size()));
        }

        List<SourceRef> sources = unique.stream()
                .map(d -> new SourceRef(
                        formatSource(d),
                truncate(previewSource(d), 1200),   // UI 출처 hover 미리보기 길이
                d.getId(),
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
            // Skip any image whose description is already embedded in the chunk text — DOCX/PPTX/PDF
            // uploads with "이미지 설명 추가" checked get a "[이미지 설명: ...]" line injected right
            // after the "[이미지: ...]" marker at indexing time (MarkdownCorrectionService), but that
            // description only ever lives in the markdown text — it's never written to the
            // image_descriptions table LazyVisionService/ImageDescriptionRepository read from. Without
            // this filter, every such image looks like a cache miss on every single turn that
            // retrieves it: a wasted Vision call plus a duplicate "설명: ..." appended right next to
            // the one already in the text (see augmentWithDescriptions() below).
            List<Document> retrieved = unique; // effectively-final capture for the lambda below
            List<String> needsAnalysis = imageRefs.stream()
                    .filter(path -> retrieved.stream().noneMatch(d -> hasEmbeddedDescription(d.getText(), path)))
                    .toList();
            if (!needsAnalysis.isEmpty()) {
                String threadId = state.threadId();
                Map<String, String> descs = lazyVisionService.describeIfNeeded(needsAnalysis,
                        (done, total) -> listener.onImageAnalysisProgress(done, total),
                        () -> imageSkipRegistry.isSkipRequested(threadId));
                if (!descs.isEmpty()) contextDocs = augmentWithDescriptions(unique, descs);
            }
        }

        List<String> warnings = new ArrayList<>(state.retrievalWarnings());
        boolean hasOcr = unique.stream()
                .anyMatch(d -> "ocr".equals(d.getMetadata().get(MetaKey.SOURCE_TYPE)));
        if (hasOcr) {
            warnings.add("⚠️ 이 답변에는 OCR로 처리된 스캔 문서가 포함되어 있습니다. 내용이 부정확할 수 있습니다.");
        }

        // Streaming UI (chat-stream.js) renders source popovers and image thumbnails from these
        // explicit events; without emitting them, sourcePreviewEnabled=true still has nothing to show.
        listener.onSourcesReady(sources);
        listener.onImagesReady(imageRefs);

        return state.toBuilder()
                .retrievedDocs(contextDocs)
                .sources(sources)
                .retrievalWarnings(warnings)
                .imageRefs(imageRefs)
                .needsRetry(false)
                .build();
    }

    /**
     * Gate the multi-query expansion LLM call. Skips when disabled or when the
     * question is shorter than the configured min length (short keyword-ish queries gain
     * little from expansion but pay the LLM round-trip on the critical path).
     * Package-private for unit testing.
     */
    boolean shouldExpand(String question) {
        if (!props.searchMultiqueryEnabledSafe()) return false;
        if (question == null) return false;
        // Hot-editable — read fresh so a /settings override applies without a restart.
        return question.strip().length() >= props.searchMultiqueryMinLengthSafe();
    }

    /**
     * Strict AND tag filter over already-retrieved candidates. A chunk passes only when
     * its {@code tags} metadata contains every selected tag. Empty selection → pass-through
     * (version-only behavior). Never falls back to unfiltered results on shortfall.
     * Package-private for unit testing.
     *
     * <p><b>Curated exemption</b>: this filter runs on the <em>merged</em> pool, which includes the
     * curated-Q&A axis (§10.10), so an untagged curated entry would be dropped by every tag-scoped
     * search — that is what used to make liked answers silently vanish the moment a user touched a
     * tag chip. A curated entry now carries the tags of the question it was promoted from
     * ({@code CuratedQaService.onLike}) or the ones its submitter chose; when it has none, its scope
     * is genuinely unknown and it is treated as belonging to all scopes rather than to none.
     * Document chunks keep the strict behavior — an untagged document is still excluded, since there
     * the tag selection is precisely a corpus filter.
     */
    List<Document> filterByTags(List<Document> candidates, List<String> selectedTags, int candidateK) {
        if (selectedTags == null || selectedTags.isEmpty()) return candidates;
        int before = candidates.size();
        List<Document> filtered = candidates.stream()
                .filter(d -> isScopelessCuratedEntry(d)
                        || com.example.ragagent.model.TagUtils.matchesAnd(
                        com.example.ragagent.model.TagUtils.parseTagList(d.getMetadata().get(MetaKey.TAGS)),
                        selectedTags))
                .toList();
        log.debug("[TAG] selectedTags={} candidateK={} postFilter={}/{}",
                selectedTags, candidateK, filtered.size(), before);
        return filtered;
    }

    /**
     * Partitions curated hits by {@code MetaKey.CURATED_ORIGIN}. {@code wantManual=true} keeps only
     * approved submissions; {@code false} keeps everything else — vectors embedded before this key
     * existed carry no origin at all and fall into the 👍 side, which is what they overwhelmingly are.
     */
    private static List<Document> curatedHitsOfOrigin(List<Document> hits, String manualOrigin, boolean wantManual) {
        if (hits == null || hits.isEmpty()) return List.of();
        return hits.stream()
                .filter(d -> manualOrigin.equals(d.getMetadata().get(MetaKey.CURATED_ORIGIN)) == wantManual)
                .toList();
    }

    /** A curated-Q&A hit carrying no tags at all — see {@link #filterByTags}'s curated exemption. */
    private static boolean isScopelessCuratedEntry(Document d) {
        if (!"curated_qa".equals(d.getMetadata().get(MetaKey.DOC_TYPE))) return false;
        return com.example.ragagent.model.TagUtils
                .parseTagList(d.getMetadata().get(MetaKey.TAGS)).isEmpty();
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
     * Reciprocal Rank Fusion, vector-only, default k=60 — kept for callers/tests that don't
     * care about the keyword axis or weighting. Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> ranked, int topK) {
        return mergeRrf(ranked, List.of(), topK, 60, 1.0);
    }

    /**
     * Weighted Reciprocal Rank Fusion — score(d) = Σ w/(rank_i + 1 + k) across every axis where d appears.
     * Vector axes are group-normalized (weight = 1/axisCount) so a document's score doesn't scale with
     * the number of MultiQuery variants (1~3) — otherwise the single keyword (BM25) axis is structurally
     * outvoted whenever it competes with 2-3 vector axes on an exact-term match. The keyword axis instead
     * carries its own configurable {@code keywordWeight} (default 1.0 = parity with the normalized vector
     * group). When there is no keyword axis (hybrid disabled or no hits), this reduces to unweighted RRF —
     * every vector axis is scaled by the same constant 1/axisCount, so ranking order is unchanged.
     * Kept for callers/tests that don't care about the curated axis (§10.10) — delegates to the 7-arg
     * overload with an empty curated axis. Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    int topK, int k, double keywordWeight) {
        return mergeRrf(vectorRanked, keywordRanked, List.of(), topK, k, keywordWeight, 0.0);
    }

    /**
     * Same as the 5-arg {@link #mergeRrf(List, List, int, int, double)}, plus a third axis for
     * curated Q&A (§10.10, promoted-by-like answers embedded under the reserved {@code "curated"}
     * version namespace) — its own configurable {@code curatedWeight}, same treatment as the
     * keyword axis (flat weight, not group-normalized with the vector axes). Empty/absent axis is
     * a no-op, so this reduces to the 5-arg behavior when curated search is disabled or has no
     * hits. Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    List<Document> curatedRanked, int topK, int k,
                                    double keywordWeight, double curatedWeight) {
        return mergeRrf(vectorRanked, keywordRanked, curatedRanked, List.of(), topK, k,
                keywordWeight, curatedWeight, 0.0);
    }

    /**
     * Same as the 7-arg overload, plus a fourth axis for 지식 제안 (approved user submissions) with
     * its own weight. Both curated axes come from one search of the {@code "curated"} namespace,
     * partitioned by {@code MetaKey.CURATED_ORIGIN} — a 👍-promoted answer was verified by whoever
     * asked, a submission is knowledge someone typed in, so they are worth different amounts and
     * get separate knobs. Empty/absent axis is a no-op. Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    List<Document> curatedRanked, List<Document> submissionRanked,
                                    int topK, int k,
                                    double keywordWeight, double curatedWeight, double submissionWeight) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        double vectorWeight = vectorRanked.isEmpty() ? 0.0 : 1.0 / vectorRanked.size();
        for (List<Document> list : vectorRanked) {
            addRrfAxis(list, vectorWeight, k, scores, byKey);
        }
        if (keywordRanked != null && !keywordRanked.isEmpty()) {
            addRrfAxis(keywordRanked, keywordWeight, k, scores, byKey);
        }
        if (curatedRanked != null && !curatedRanked.isEmpty()) {
            addRrfAxis(curatedRanked, curatedWeight, k, scores, byKey);
        }
        if (submissionRanked != null && !submissionRanked.isEmpty()) {
            addRrfAxis(submissionRanked, submissionWeight, k, scores, byKey);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    private static void addRrfAxis(List<Document> axis, double weight, int k,
                                    Map<String, Double> scores, Map<String, Document> byKey) {
        for (int i = 0; i < axis.size(); i++) {
            Document doc = axis.get(i);
            String key = docKey(doc);
            scores.merge(key, weight / (i + 1 + k), Double::sum);
            byKey.putIfAbsent(key, doc);
        }
    }

    /**
     * Stable dedup key. Prefers {@code doc_id:chunk_index} (set at index time and
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

    /**
     * Citation label rules.
     * Curated hit: fixed label.
        * Chapter-based docs (docx/md/txt): "파일명 | ch X" when a real chapter exists, else "파일명" only.
     * Page-based docs (pptx/pdf etc.): "파일명 | p.N".
     */
    static String formatSource(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if ("curated_qa".equals(meta.get(MetaKey.DOC_TYPE))) {
            return "💬 큐레이션 Q&A";
        }
        String filename = String.valueOf(meta.getOrDefault(MetaKey.FILENAME, "unknown"));
        String chapter = normalizeChapterNo(meta.get(MetaKey.CHAPTER_NO));
        if (chapter != null) {
            return "%s | ch %s".formatted(filename, chapter);
        }
        if (isChapterStructuredFilename(filename)) {
            return filename;
        }
        Object page = meta.getOrDefault(MetaKey.PAGE_OR_SLIDE, "?");
        return "%s | p.%s".formatted(filename, page);
    }

    private static String normalizeChapterNo(Object chapterNo) {
        if (chapterNo == null) return null;
        if (chapterNo instanceof Number n) {
            if (n.doubleValue() <= 0.0d) return null;
            return normalizeIndex(n);
        }
        String raw = chapterNo.toString().trim();
        if (raw.isEmpty()) return null;
        if ("0".equals(raw) || "0.0".equals(raw)) return null;
        return raw;
    }

    private static boolean isChapterStructuredFilename(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".docx") || lower.endsWith(".md") || lower.endsWith(".txt");
    }

    /**
     * Safely extracts the "image_paths" metadata value. Chroma may deserialize
     * comma-joined paths as either a String or a List depending on writer/version;
     * a blind (String) cast crashes the entire retrieval on the latter.
     */
    /**
     * True when {@code text} already has a "[이미지 설명: ...]" line immediately following the
     * "[이미지: {imagePath}]" marker — i.e. the description was injected when the chunk was created
     * ("이미지 설명 추가" on a document upload, or 지식 제안 승인), so a fresh Lazy Vision call would
     * be redundant. Only a match right after the marker counts (not merely "the text contains a
     * description somewhere") — an unrelated image's description elsewhere in a merged chunk must
     * not suppress analysis of this one.
     *
     * <p>The {@code <br>} form counts too: inside a GFM table row a raw newline would split the
     * cell and shatter the table, so both injection sites ({@code MarkdownCorrectionService},
     * {@code CuratedImageStore}) separate with {@code <br>} there instead. Without accepting it
     * here, every table-embedded image looks like a cache miss on every turn and
     * {@link #augmentWithDescriptions} appends a second copy of the same description next to the
     * one already in the text.
     */
    static boolean hasEmbeddedDescription(String text, String imagePath) {
        if (text == null) return false;
        String marker = "[이미지: " + imagePath + "]";
        int idx = text.indexOf(marker);
        if (idx < 0) return false;
        String after = text.substring(idx + marker.length()).stripLeading();
        if (after.startsWith("<br>")) after = after.substring("<br>".length()).stripLeading();
        return after.startsWith("[이미지 설명:");
    }

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

    /**
     * The chat 출처 hover-preview shows a document excerpt, not a restatement of the answer the
     * user is already reading — but a curated Q&A hit's stored text is the full liked/approved
     * answer verbatim (§10.10, kept intact for the admin/curated views), "## 요약"/"## 참고"
     * included whenever the answer was small enough to embed as one vector. A multi-chunk curated
     * answer already has those sections stripped before splitting ({@code
     * CuratedQaService.buildChunkedDocuments}), so without this the preview inconsistently shows
     * a summary depending on answer length alone. Stripping here only affects what the hover
     * preview displays — the retrieval/grounding text ({@code d.getText()} itself) is untouched.
     */
    private static String previewSource(Document d) {
        String text = d.getText();
        if (!"curated_qa".equals(d.getMetadata().get(MetaKey.DOC_TYPE))) return text;
        return CuratedTextUtils.stripStructuralSections(text);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }
}
