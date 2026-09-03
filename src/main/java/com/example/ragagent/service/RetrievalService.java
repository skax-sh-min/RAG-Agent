package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.ConcurrencyLimitingChatModel;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TokenEstimator;
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
    /** 재시도 때 "청크를 더 실어도 되는가"를 묻기 위해서만 보관한다 — 답변을 받을 프로바이더를
     *  {@code AnswerService} 와 같은 방식으로 먼저 물어본다. */
    private final LlmRouter llmRouter;
    private final ProviderContextWindows contextWindows;
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
                            ChatImageAnalysisSkipRegistry imageSkipRegistry,
                            ProviderContextWindows contextWindows) {
        this.llmRouter = llmRouter;
        this.contextWindows = contextWindows;
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
        // §10.12 — 검색 축 셋(벡터·BM25/FTS·큐레이션)과 리랭커가 보는 질의. 짧은 후속 질문이
        // 독립화됐으면 그 결과, 아니면 원문이다. 답변 프롬프트는 이 값을 쓰지 않는다
        // (AgentState.effectiveSearchQuestion() 참고) — 재작성이 빗나가도 검색만 틀린다.
        String searchQuestion = state.effectiveSearchQuestion();
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
        // 재시도 횟수가 아니라 '검색을 다시 한 횟수'가 escalation 의 입력이다 — grounded 실패
        // 재시도는 검색을 건너뛰고 ANSWER 로 바로 가므로(AgentGraph), retryCount 를 쓰면 검색을
        // 하지도 않은 만큼 escalation 이 앞서 나간다.
        int retry = state.retrievalRetries();
        // Escalating candidateK alone only changed WHICH documents competed for the final slots —
        // the cut stayed at defaultTopK, so every attempt handed the answer node exactly topK
        // documents. When the evidence a retry was supposed to surface lands just past that cut,
        // the retry re-fails for the same reason and burns the whole retry budget. The final cut
        // therefore grows too, by one document per retry (topK + retryCount): enough to let a
        // near-miss chunk in, small enough that the answer prompt does not balloon the way the
        // ×(retryCount+1) candidate escalation would. Gated by the same app.search-retry-escalate
        // flag — turning escalation off must switch off the whole behavior, not half of it.
        // §6.24 Step 2-c — 응답 모드의 검색 부스트. 재시도 증가분과 '더해진다': 재시도는 "이번
        // 시도에 근거가 모자랐다"는 사후 신호이고, 부스트는 "이 모드는 원래 재료가 더 필요하다"는
        // 사전 성질이라 서로를 대체하지 않는다. 오늘은 모든 모드가 0이다 — 이 값을 실제로 올리면
        // AnswerService.MAX_EVAL_EXCERPT_CHARS 를 먼저 손봐야 하고(§6.24 Step 4-c), 그러지 않으면
        // 하위 순위 문서가 검증 대상에서 빠지면서 "그 문서에만 있는 값을 정확히 인용한 답변이
        // grounded=false 판정을 받는" 오탐이 되살아난다.
        int modeBoost = Math.max(0, state.responseMode().retrievalBoost());
        // 재시도당 +1 은 그대로 두되, 컨텍스트에 여유가 있을 때만 늘린다. 늘리는 쪽이 기존 동작이라
        // 창을 모르면 늘린다 — 이 앱의 "창을 모르면 아무것도 하지 않는다"는 추측으로 '줄이지'
        // 말라는 뜻이고, 여기서 줄이는 쪽이 동작 변경이다.
        int extraDocs = retryEscalate ? retry : 0;
        if (extraDocs > 0 && !hasContextHeadroomFor(state, extraDocs)) {
            log.info("[RETRIEVAL] 컨텍스트 여유 부족 — 재시도 문서 증가(+{})를 생략하고 교체만 적용한다 thread={}",
                    extraDocs, state.threadId());
            extraDocs = 0;
        }
        int effectiveTopK = defaultTopK + extraDocs + modeBoost;
        List<Document> unique;
        // Empty on the expansion-failure fallback path below, which skips fusion entirely — every
        // consumer must therefore tolerate a missing entry rather than assume one exists.
        Map<String, RrfMetrics> fusedMetrics = Map.of();
        try {
            // Escalate candidate count on retry to surface different documents.
            // 재시도당 ×2 였다. 교체가 들어오면서 그만큼의 신규 후보가 필요 없어졌다 — topK 의
            // 1/3 을 비우므로 그 자리를 채울 만큼(≈ ×1.3)만 있으면 되고, ×1.5 는 거기에 여유를
            // 얹은 값이다. 후보 풀을 키우는 것도 공짜가 아니다(융합·태그 필터·리랭크가 그 위에서 돈다).
            int candidateK = (retryEscalate && retry > 0)
                    ? (int) Math.min(Math.round(defaultTopK * (1 + 0.5 * retry)), (long) defaultTopK * 3)
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
                                ? ragService.keywordSearch(state.version(), searchQuestion, fetchK)
                                : List.<Document>of(),
                        exec);
                // §10.10 — curated-Q&A axis: single search against the original question (not
                // multi-query variants — the curated pool is small and question-driven matching is
                // the point), scoped to the reserved "curated" version namespace.
                CompletableFuture<List<Document>> curatedF = CompletableFuture.supplyAsync(
                        () -> curatedQaEnabled
                                ? ragService.search(state.userId(), searchQuestion, CuratedQaService.CURATED_VERSION, fetchK)
                                : List.<Document>of(),
                        exec);
                // 게이트는 **원문 길이**로 판단한다 — 독립화된 질의는 원문보다 길어지는 것이 정상이라
                // 재작성 결과로 재면 확장까지 함께 돌아 한 턴에 질의 전처리 LLM 호출이 둘이 된다.
                // 원문으로 재면 이 분기와 QuestionCondenser.gateOpen() 이 정확한 여집합이라 둘 중
                // 하나만 돈다(§10.12 — "이미 건너뛰던 호출 자리를 쓴다").
                if (shouldExpand(state.question())) {
                    CompletableFuture<List<Document>> originalF = CompletableFuture.supplyAsync(
                            () -> ragService.search(state.userId(), searchQuestion, state.version(), fetchK),
                            exec);
                    // Wrap for the LLM-facing expansion prompt only (delimiter isolation, same as every
                    // other prompt-construction site) — the vector search axes above still embed the
                    // raw searchQuestion untouched. includeOriginal(true) echoes back exactly the
                    // wrapped string as one "variant", so filter against that same wrapped string
                    // (not the raw question) to strip it before the variant-only batch search below.
                    String expansionInput = PromptInjectionGuard.wrap(searchQuestion);
                    List<String> variantTexts = new ArrayList<>(
                            multiQueryExpander.expand(new Query(expansionInput)).stream()
                                    .map(Query::text)
                                    .filter(t -> !t.equals(expansionInput))
                                    .toList());
                    // 재시도라면 평가가 남긴 "무엇이 부족했는지"를 검색 축 하나로 더 넣는다.
                    // 확장기는 빈 생성 시점에 프롬프트가 고정돼 있어 per-request 로 지시를 바꿀 수
                    // 없지만, 그 사유 문장 자체가 이미 "못 찾은 것"을 이름으로 부르고 있어 질의로
                    // 쓸 만하다. LLM 왕복은 늘지 않는다 — 벡터 검색 한 번이 추가될 뿐이다.
                    reasonQuery(state).ifPresent(variantTexts::add);
                    ranked = new ArrayList<>();
                    ranked.add(originalF.join());
                    if (!variantTexts.isEmpty()) {
                        ranked.addAll(ragService.searchBatch(state.userId(), variantTexts, state.version(), candidateK));
                    }
                } else {
                    ranked = ragService.searchBatch(state.userId(), List.of(searchQuestion), state.version(), candidateK);
                }
                keywordHits = keywordF.join();
                curatedHits = curatedF.join();
            }
            // 큐레이션은 축 하나다 (§10.11). 예전에는 좋아요 승격과 지식 제안을 MetaKey.CURATED_ORIGIN
            // 으로 갈라 각자 가중치를 줬는데, 그 구분의 근거는 "앱이 만든 미편집·무검토 출력" 대
            // "사람이 쓴 텍스트"였다. 이제 모든 유입이 사람 편집 + 관리자 승인을 거치므로 그 차이가
            // 사라졌다. CURATED_ORIGIN 자체는 감사·통계용으로 계속 실리며, 검색 분기에서만 빠졌다.
            RrfResult fused = mergeRrfScored(ranked, keywordHits, curatedHits,
                    candidateK, rrfK, rrfKeywordWeight, curatedQaWeight);
            fusedMetrics = fused.metrics();
            List<Document> candidates = fused.docs();
            // Strict AND tag filter — applied after RRF, before rerank/cut. Covers vector
            // + BM25 axes uniformly (tags travel in chunk metadata). No no-tag fallback on shortfall.
            candidates = filterByTags(candidates, selectedTags, candidateK);
            // 직전 시도에서 근거로 쓰이지 않은 하위 청크를 뺀다. 자리를 비우는 것이 목적이므로
            // 최종 컷 '전에' 걸어야 다음 후보가 그 자리로 올라온다(§ 재시도 개선).
            candidates = RetrievalEviction.withoutExcluded(candidates, Set.copyOf(state.excludedDocIds()));
            // Rerank by LLM relevance, then cut to effectiveTopK (= topK on the first attempt).
            unique = (rerankEnabled && reranker.isPresent())
                    ? reranker.get().rerank(searchQuestion, candidates, effectiveTopK)
                    : candidates.subList(0, Math.min(effectiveTopK, candidates.size()));
        } catch (Exception e) {
            log.warn("Multi-query expansion failed, falling back to original question: {}", e.getMessage());
            // Keep the tag scope on the fallback path too (else tags leak through on error).
            // Cut to effectiveTopK as well, so a retry is not silently de-escalated by whichever
            // attempt happens to lose the expansion LLM call.
            int fallbackK = selectedTags.isEmpty() ? effectiveTopK
                    : Math.max(effectiveTopK, defaultTopK * tagCandidateMultiplier);
            List<Document> fallback = ragService.search(state.userId(), searchQuestion, state.version(), fallbackK);
            fallback = filterByTags(fallback, selectedTags, effectiveTopK);
            unique = fallback.subList(0, Math.min(effectiveTopK, fallback.size()));
        }

        // § 표시 이름 — one batch lookup for the whole turn's retrieved chunks instead of one
        // query per chunk; formatSource() falls back to the real filename for any docId absent
        // from the map (no override set, or lookup failed).
        List<String> docIds = unique.stream()
                .map(d -> String.valueOf(d.getMetadata().getOrDefault(MetaKey.DOC_ID, "")))
                .filter(id -> !id.isBlank())
                .toList();
        Map<String, String> displayNames = ragService.findDisplayNames(docIds);

        final Map<String, RrfMetrics> rrfMetrics = fusedMetrics;
        // Share is normalized over the FINAL cut, not the candidate pool — the question it answers
        // is "of what the answer node actually received, how much weight was this chunk", so a
        // chunk dropped by the tag filter or the rerank cut must not dilute the denominator.
        double rrfTotal = unique.stream()
                .map(d -> rrfMetrics.get(docKey(d)))
                .filter(Objects::nonNull)
                .mapToDouble(RrfMetrics::rrfScore)
                .sum();

        List<SourceRef> sources = unique.stream()
                .map(d -> {
                    RrfMetrics m = rrfMetrics.get(docKey(d));
                    // Similarity falls back to the document's own score so the fallback path (no
                    // fusion) still reports it; share/ranks are genuinely unknown there.
                    Double similarity = m != null && m.vectorSimilarity() != null
                            ? m.vectorSimilarity() : d.getScore();
                    Double share = (m != null && rrfTotal > 0.0) ? m.rrfScore() / rrfTotal : null;
                    return new SourceRef(
                            formatSource(d, displayNames),
                            truncate(previewSource(d), 700),   // UI 출처 hover 미리보기 길이
                            d.getId(),
                            String.valueOf(d.getMetadata().getOrDefault(MetaKey.DOC_ID, "")),
                            d.getMetadata().getOrDefault(MetaKey.PAGE_OR_SLIDE, "?"),
                            similarity,
                            share,
                            m != null ? m.axisRanks() : null);
                })
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
     * 재시도에서 문서를 {@code extraDocs} 개 더 실어도 <b>검증 호출</b>이 발췌를 자르지 않을지 본다.
     *
     * <p><b>기준이 답변 호출이 아니라 검증 호출인 이유</b>: 이 앱에서 가장 큰 단일 요청은 검증이다
     * (질문 + 답변 전문 + 발췌 + 응답 스키마가 한 번에 들어간다). 그리고 넘칠 때 나는 사고는
     * 컨텍스트 초과가 아니라 조용한 품질 저하다 — 발췌가 잘리면 {@code AnswerService} 가 근거 없음
     * 판정을 신뢰할 수 없다고 보고 {@code grounded=null}(판정 없음)로 떨어뜨린다. 즉 <b>재시도를
     * 거듭할수록 판정이 사라지는</b> 구조라, 문서를 늘리는 결정은 그 예산을 보고 내려야 한다.
     *
     * <p>직전 답변을 실측에 쓴다 — 재시도 시점에는 방금 반려된 답변이 {@code state.answer()} 에
     * 있고, 다음 답변의 길이를 추정하는 가장 좋은 재료가 그것이다. 없으면(첫 시도) 이 메서드는
     * 호출되지 않는다.
     *
     * <p>창을 모르면 {@code true} — 늘리는 쪽이 기존 동작이므로, 모르는 상태에서 동작을 바꾸지 않는다.
     */
    /**
     * 재시도에서 추가로 검색할 질의 — 평가가 낸 반려 사유 한 문장.
     *
     * <p>원래 질문은 그대로 자기 축으로 검색되고, 이건 <b>별도 축</b>으로 들어가 RRF 로 융합된다.
     * 원 질문을 바꾸지 않는 것이 중요하다 — 사유 문장이 엉뚱하면 그 축의 순위만 나빠질 뿐,
     * 질문 축의 결과는 그대로 남는다.
     *
     * <p>{@code PromptInjectionGuard.wrap()} 을 쓰지 않는다: 이건 프롬프트가 아니라 임베딩할
     * 검색어이고, 벡터 축은 원래 {@code state.question()} 도 감싸지 않고 그대로 임베딩한다.
     */
    private static Optional<String> reasonQuery(AgentState state) {
        if (state.retrievalRetries() <= 0) return Optional.empty();
        String reason = state.evalReason();
        if (reason == null || reason.isBlank()) return Optional.empty();
        return Optional.of(reason.strip());
    }

    private boolean hasContextHeadroomFor(AgentState state, int extraDocs) {
        int window = contextWindows.tokensOrZero(llmRouter.findProviderName(TaskType.TEXT, state.routingMode()));
        if (window <= 0) return true;

        long docsCost = state.retrievedDocs().stream()
                .mapToLong(d -> TokenEstimator.estimate(d.getText()))
                .sum();
        long perDoc = state.retrievedDocs().isEmpty()
                ? 0
                : docsCost / state.retrievedDocs().size();          // 직전 문서들의 평균 크기
        long fixed = TokenEstimator.estimate(state.answer())
                + TokenEstimator.estimate(state.question())
                + EVAL_OVERHEAD_TOKENS;
        long budget = new PromptBudget(window, AnswerService.MAX_EVAL_OUTPUT_TOKENS).inputBudget();
        return budget - fixed - docsCost - perDoc * extraDocs > 0;
    }

    /**
     * 검증 프롬프트에서 문서·답변·질문을 뺀 나머지(시스템 프롬프트 + 응답 스키마 + 라벨)의 몫.
     *
     * <p>측정이 아니라 <b>넉넉히 잡은 허용치</b>다. 정확히 재려면 모드별 시스템 프롬프트와
     * {@code BeanOutputConverter} 의 스키마 문자열을 이 클래스로 끌고 와야 하는데, 그 둘은 문서
     * 하나 크기에도 못 미치면서 계산만 두 곳으로 갈라 놓는다. 과대 추정은 "늘리지 않음"으로
     * 떨어지므로 안전한 방향이다.
     */
    private static final int EVAL_OVERHEAD_TOKENS = 1_500;

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
        return mergeRrfScored(vectorRanked, keywordRanked, curatedRanked,
                topK, k, keywordWeight, curatedWeight).docs();
    }

    /**
     * Per-chunk retrieval diagnostics produced as a by-product of fusion. {@code vectorSimilarity}
     * is the best cosine across the vector axes and is null for a chunk that only ever appeared on
     * the BM25/curated axes (those carry rank, not distance).
     */
    record RrfMetrics(double rrfScore, Double vectorSimilarity, String axisRanks) {}

    /** Fusion output plus the diagnostics, keyed by {@link #docKey(Document)}. */
    record RrfResult(List<Document> docs, Map<String, RrfMetrics> metrics) {}

    /**
     * Same fusion as {@link #mergeRrf}, but also keeps the numbers it computes instead of
     * discarding them once sorting is done. The plain {@code mergeRrf} overloads delegate here and
     * drop the metrics, so ranking behavior is bit-identical and existing callers are unaffected.
     *
     * <p>Metrics are keyed by {@code docKey} rather than attached to the {@link Document}: the
     * documents flow on into the answer prompt and (for curated/admin paths) back into the vector
     * store, and this codebase already pays for one transient-metadata key ({@code
     * MetaKey.SEARCH_TEXT}) that every provider's {@code add()} has to remember to strip. A side
     * map cannot leak into storage at all, and it survives the rerank/tag-filter steps that rebuild
     * the document list.
     */
    static RrfResult mergeRrfScored(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                     List<Document> curatedRanked, int topK, int k,
                                     double keywordWeight, double curatedWeight) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        Map<String, Double> bestSimilarity = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> ranksByKey = new LinkedHashMap<>();
        double vectorWeight = vectorRanked.isEmpty() ? 0.0 : 1.0 / vectorRanked.size();
        for (List<Document> list : vectorRanked) {
            addRrfAxis(list, vectorWeight, k, AXIS_VECTOR, true, scores, byKey, bestSimilarity, ranksByKey);
        }
        if (keywordRanked != null && !keywordRanked.isEmpty()) {
            addRrfAxis(keywordRanked, keywordWeight, k, AXIS_KEYWORD, false, scores, byKey, bestSimilarity, ranksByKey);
        }
        if (curatedRanked != null && !curatedRanked.isEmpty()) {
            addRrfAxis(curatedRanked, curatedWeight, k, AXIS_CURATED, false, scores, byKey, bestSimilarity, ranksByKey);
        }
        List<Map.Entry<String, Double>> ordered = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .toList();
        Map<String, RrfMetrics> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : ordered) {
            metrics.put(e.getKey(), new RrfMetrics(
                    e.getValue(), bestSimilarity.get(e.getKey()), formatAxisRanks(ranksByKey.get(e.getKey()))));
        }
        return new RrfResult(ordered.stream().map(e -> byKey.get(e.getKey())).toList(), metrics);
    }

    private static final String AXIS_VECTOR = "vec";
    private static final String AXIS_KEYWORD = "bm25";
    /** §10.11 — one curated axis. Was {@code like}/{@code sub}; the diagnostics panel shows the
     *  new name from the next search on, and an older stored string keeps its own labels. */
    private static final String AXIS_CURATED = "curated";
    /** Axis print order — fixed so the rendered string is stable turn to turn. */
    private static final List<String> AXIS_ORDER =
            List.of(AXIS_VECTOR, AXIS_KEYWORD, AXIS_CURATED);

    private static void addRrfAxis(List<Document> axis, double weight, int k,
                                    String axisName, boolean carriesSimilarity,
                                    Map<String, Double> scores, Map<String, Document> byKey,
                                    Map<String, Double> bestSimilarity,
                                    Map<String, Map<String, Integer>> ranksByKey) {
        for (int i = 0; i < axis.size(); i++) {
            Document doc = axis.get(i);
            String key = docKey(doc);
            scores.merge(key, weight / (i + 1 + k), Double::sum);
            // Prefer a document that actually carries a similarity score. The keyword axis builds
            // its Documents from SQL rows with no score at all, so a plain putIfAbsent would blank
            // out the similarity of any chunk the BM25 axis happened to reach first.
            Document existing = byKey.get(key);
            if (existing == null || (existing.getScore() == null && doc.getScore() != null)) {
                byKey.put(key, doc);
            }
            // Best rank wins when a chunk appears on several vector axes (MultiQuery variants).
            ranksByKey.computeIfAbsent(key, x -> new LinkedHashMap<>())
                      .merge(axisName, i + 1, Math::min);
            if (carriesSimilarity && doc.getScore() != null) {
                bestSimilarity.merge(key, doc.getScore(), Math::max);
            }
        }
    }

    /** {@code {vec:2, bm25:5}} → {@code "vec:2, bm25:5"}, in {@link #AXIS_ORDER}. */
    private static String formatAxisRanks(Map<String, Integer> ranks) {
        if (ranks == null || ranks.isEmpty()) return null;
        return AXIS_ORDER.stream()
                .filter(ranks::containsKey)
                .map(axis -> axis + ":" + ranks.get(axis))
                .collect(java.util.stream.Collectors.joining(", "));
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
     *
     * @param displayNames docId → display-name override (§ 표시 이름), from {@link RagService#findDisplayNames};
     *                     the label shows the override when present, but the <b>real</b> filename still
     *                     drives {@link #isChapterStructuredFilename} — a display name is cosmetic and
     *                     typically has no extension, so it must never decide the citation format.
     */
    static String formatSource(Document doc, Map<String, String> displayNames) {
        Map<String, Object> meta = doc.getMetadata();
        if ("curated_qa".equals(meta.get(MetaKey.DOC_TYPE))) {
            return "💬 큐레이션 Q&A";
        }
        String realFilename = String.valueOf(meta.getOrDefault(MetaKey.FILENAME, "unknown"));
        String docId = String.valueOf(meta.getOrDefault(MetaKey.DOC_ID, ""));
        String filename = (displayNames != null) ? displayNames.getOrDefault(docId, realFilename) : realFilename;
        String chapter = normalizeChapterNo(meta.get(MetaKey.CHAPTER_NO));
        if (chapter != null) {
            return "%s | ch %s".formatted(filename, chapter);
        }
        if (isChapterStructuredFilename(realFilename)) {
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
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + " ……";
    }
}
