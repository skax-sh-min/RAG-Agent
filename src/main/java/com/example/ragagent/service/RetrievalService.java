package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.web.MdcPropagation;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutedChatModel;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingSiteChatModel;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    /** 재시도 컨텍스트 여유 판단의 검증 예약에 생각 여유를 더한다(§6.29 ④). */
    private final ThinkingBudget thinkingBudget;

    public RetrievalService(LlmRouter llmRouter, RagService ragService,
                            AppProperties props, Optional<LazyVisionService> lazyVisionOpt,
                            Optional<RerankerService> rerankerOpt, MessageSource messageSource,
                            ChatImageAnalysisSkipRegistry imageSkipRegistry,
                            ProviderContextWindows contextWindows, ThinkingBudget thinkingBudget) {
        this.llmRouter = llmRouter;
        this.contextWindows = contextWindows;
        this.thinkingBudget = thinkingBudget;
        this.ragService = ragService;
        this.props = props;
        this.rerankEnabled = props.searchRerankEnabled();
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.reranker = rerankerOpt;
        this.imageSkipRegistry = imageSkipRegistry;
        // MultiQueryExpander builds its own ChatClient around the model it's given and calls it at a
        // point RetrievalService doesn't control, so executeGated() can't wrap the call site. §6.21 (작업2) —
        // query expansion is a reasoning-free chore, so it routes as MICRO_TEXT (the dedicated small model
        // when a type=MICRO_TEXT provider is registered; every larger type absorbs it, LlmProvider.supports).
        //
        // 모델은 기동 때 프로바이더를 골라 쥐지 않는다 — 호출마다 라우터가 고른다(RoutedChatModel). 예전에는 여기서 한 번
        // 고른 프로바이더(같은 priority 의 둘이 한가하면 먼저 등록된 LOCAL_LLM_URL 쪽)를 평생 쥐고 게이트·사용량
        // 데코레이터만 감쌌다. 그래서 그 서버가 죽어도 확장은 계속 거기로 갔고, 차단도 전환도 없이 질문마다 타임아웃만큼
        // 느려졌다. 게이트·사용량 기록·차단·다음 프로바이더로의 전환은 이제 라우터의 블로킹 경로가 그대로 한다.
        // Swap Spring AI's default (English, diversity-only) expansion prompt for a Korean one that
        // also asks the model to normalize the question toward embedding-search-friendly phrasing
        // (strip filler/honorifics, resolve pronouns) — not just paraphrase it. The app has no
        // per-request locale variance in practice (ThreadContext defaults to Locale.KOREAN), and this
        // expander is built once at bean construction, so a fixed locale here is fine.
        PromptTemplate expansionPromptTemplate = PromptTemplate.builder()
                .template(messageSource.getMessage("prompt.retrieval.expansion", null, Locale.KOREAN))
                .build();
        // §6.29 — 확장기는 자기 ChatClient 로 프롬프트를 직접 만들어 호출부가 옵션에 사이트를 표시할 자리가 없다.
        // 그래서 모델 앞에서 지나가는 프롬프트마다 표시한다(app.llm.thinking.query-expansion).
        this.multiQueryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(
                        new ThinkingSiteChatModel(new RoutedChatModel(llmRouter, ThinkingSite.QUERY_EXPANSION),
                                ThinkingSite.QUERY_EXPANSION)))
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
        // therefore grows too, by one document per re-retrieval (topK + retrievalRetries, unless the
        // context-headroom check below vetoes it): enough to let a near-miss chunk in, small enough
        // that the answer prompt does not balloon the way the old ×(retryCount+1) candidate
        // escalation would. Gated by the same app.search-retry-escalate flag — turning escalation
        // off must switch off the whole behavior, not half of it.
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
        // §6.31 이력 제외 — 이력이 차지하던 자리만큼 문서를 더 검색한다(historyBoostFor). 재시도 +1·모드 부스트와 더해진다.
        int baseTopK = defaultTopK + extraDocs + modeBoost;
        int historyBoost = historyBoostFor(state, baseTopK);
        int effectiveTopK = baseTopK + historyBoost;
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
            CuratedHits curatedHits;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Executor exec = MdcPropagation.propagating(pool);   // 축별 검색 로그도 이 턴의 traceId 로
                CompletableFuture<List<Document>> keywordF = CompletableFuture.supplyAsync(
                        () -> hybridEnabled
                                ? ragService.keywordSearch(state.version(), searchQuestion, fetchK)
                                : List.<Document>of(),
                        exec);
                // §10.10 — curated-Q&A axis: single search against the original question (not
                // multi-query variants — the curated pool is small and question-driven matching is
                // the point), scoped to the reserved "curated" version namespace.
                CompletableFuture<CuratedHits> curatedF = CompletableFuture.supplyAsync(
                        () -> curatedQaEnabled
                                ? curatedAxis(state.userId(), searchQuestion, fetchK, hybridEnabled)
                                : CuratedHits.EMPTY,
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
            RrfResult fused = mergeRrfScored(ranked, keywordHits,
                    curatedHits.vector(), curatedHits.keyword(),
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
        // 키워드 축으로만 찾힌 청크는 본문이 chunk_fts 의 검색용 파생 텍스트다 — 답변·검증·출처 미리보기로 가기 전에
        // 저장된 원문으로 되돌린다(최종 컷에 남은 것만, 조회 한 번).
        unique = withStoredText(unique);
        // §6.31 — 부스트로 들어온 문서가 검증 발췌에 다 들어가는지는 실제 문서 크기로 잰다(검색 전에는 알 수 없다). 넘치면
        // 꼬리(= 부스트분)부터 덜어 낸다. 기본 컷(baseTopK)은 건드리지 않는다 — 켜지 않은 턴과 같은 문서는 늘 실린다.
        int appliedBoost = Math.max(0, Math.min(historyBoost, unique.size() - baseTopK));
        if (appliedBoost > 0) {
            int fits = historyBoostThatFits(state, unique, baseTopK, appliedBoost, listener != GraphListener.NOOP);
            if (fits < appliedBoost) {
                log.info("[RETRIEVAL] 이력 제외 부스트 {}→{} — 검증 발췌 예산에 맞춰 줄였다 thread={}",
                        appliedBoost, fits, state.threadId());
                unique = unique.subList(0, baseTopK + fits);
            }
            appliedBoost = fits;
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
                .historyBoost(appliedBoost)
                .build();
    }

    /**
     * §6.31 — 이력 제외를 켠 턴에 최종 컷에 더할 문서 수: {@code 이력 상한 ÷ chunk-size}(내림). 이력 상한은 RAG 턴이 늘 쓰는
     * {@link MemoryService#maxConversationChars()} 의 식이라 기본값에서 6,000 ÷ 1,500 = +4 다.
     *
     * <p><b>왜 이력 상한인가.</b> 이력을 최대 그만큼 빼고 같은 양의 문서를 넣는 <b>교환</b>이라 답변 프롬프트의 최악 크기가
     * 지금(문서 topK + 이력 상한)과 같다 — 창을 몰라도 지금보다 위험해지지 않고, 창을 추정하지 않으므로 "모름" 원칙을 건드리지
     * 않는다. 실제 이력 길이로 잡으면 새 대화에서 체크가 아무 일도 하지 않고, 창으로 채우면 창을 모르는 배포에서 쓸 수 없다.
     *
     * <p><b>검증 쪽에는 교환이 없다</b>(검증 프롬프트에는 원래 이력이 없다) — 문서만큼 그대로 커진다. 그래서 상한이 둘이다:
     * 여기서는 {@code (기본 컷 + 부스트) × chunk-size} 가 검증의 발췌 글자 상한({@code AnswerService.MAX_EVAL_EXCERPT_CHARS})을
     * 넘지 않게, 검색 뒤에는 창을 알면 실제 문서 크기로 발췌 예산을 잰다({@link #historyBoostThatFits}).
     */
    private int historyBoostFor(AgentState state, int baseTopK) {
        if (!state.excludeHistory()) return 0;
        int chunkSize = props.chunkSizeSafe();
        if (chunkSize <= 0) return 0;
        int boost = MemoryService.conversationChars(props.llmSafe().maxTokens()) / chunkSize;
        int excerptRoom = AnswerService.MAX_EVAL_EXCERPT_CHARS / chunkSize - baseTopK;
        return Math.max(0, Math.min(boost, excerptRoom));
    }

    /**
     * §6.31 — 부스트분 가운데 검증 발췌 예산에 들어가는 수. {@link #hasContextHeadroomFor} 와 같은 판정이되, 이미 검색이 끝나
     * <b>실제 문서</b>로 잰다(기본 컷 {@code base} 개를 먼저 싣고, 그 뒤를 하나씩). 답변 크기는 직전 답변이 있으면 그것(재시도),
     * 없으면(첫 시도) 그 모드의 답변 출력 예약으로 추정한다 — 답변이 그보다 길 수는 없다.
     *
     * <p>창을 모르면 그대로 둔다 — 답변 쪽 최악은 지금과 같고, 검증이 넘치면 서버의 초과 오류가 축소 재시도로 간다.
     *
     * @param streaming 이 턴이 스트리밍으로 답하는가 — 답변 출력 예약이 달라진다({@code AgentGraph} 가 스트리밍이면 실제 리스너를,
     *                  블로킹이면 {@code NOOP} 을 넘긴다)
     */
    private int historyBoostThatFits(AgentState state, List<Document> docs, int base, int boost, boolean streaming) {
        ThinkingSite evalSite = state.responseMode().evalThinkingSite();
        String evalProvider = llmRouter.findProviderName(evalSite.taskType(), evalSite.routingMode(state.routingMode()));
        int window = contextWindows.tokensOrZero(evalProvider);
        if (window <= 0) return boost;

        long answerTokens;
        if (state.answer() != null && !state.answer().isBlank()) {
            answerTokens = TokenEstimator.estimate(state.answer());
        } else {
            ThinkingSite answerSite = state.responseMode().ragThinkingSite();
            String answerProvider = llmRouter.findProviderName(answerSite.taskType(),
                    answerSite.routingMode(state.routingMode()));
            answerTokens = AnswerService.answerReservation(thinkingBudget, answerSite, answerProvider,
                    state.responseMode(), streaming, props.llmSafe().maxTokens()).tokens();
        }
        long budget = new PromptBudget(window, AnswerService.evalReservation(thinkingBudget, evalSite, evalProvider,
                props.llmSafe().maxTokens()).tokens()).inputBudget();
        long used = answerTokens + TokenEstimator.estimate(state.question()) + EVAL_OVERHEAD_TOKENS;
        for (int i = 0; i < base; i++) used += TokenEstimator.estimate(docs.get(i).getText());
        int fits = 0;
        while (fits < boost) {
            long next = TokenEstimator.estimate(docs.get(base + fits).getText());
            if (budget - used - next <= 0) break;
            used += next;
            fits++;
        }
        return fits;
    }

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
    private boolean hasContextHeadroomFor(AgentState state, int extraDocs) {
        // 검증 호출의 예산 — 그 검증을 받을 프로바이더(검증 사이트의 라우팅) 기준이다.
        ThinkingSite evalSite = state.responseMode().evalThinkingSite();
        String provider = llmRouter.findProviderName(evalSite.taskType(), evalSite.routingMode(state.routingMode()));
        int window = contextWindows.tokensOrZero(provider);
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
        // 검증 호출이 실제로 잡을 예약 — 기본 예약 + 생각 여유(§6.29 ④). AnswerService 의 발췌 예산과 같은 함수다.
        long budget = new PromptBudget(window, AnswerService.evalReservation(thinkingBudget, evalSite, provider,
                props.llmSafe().maxTokens()).tokens()).inputBudget();
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
     * tag chip. A curated entry now carries the tags its 지식 제안 was approved with (a 좋아요 출신
     * proposal is prefilled with the scope the answer was given in, §10.11 —
     * {@code CuratedQaService.buildDocument}); when it has none, its scope
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
     * 큐레이션 축의 두 절반. <b>합쳐서 하나로 돌려주지 않는다</b> — 두 리스트의 순위는 서로 비교
     * 불가능한 값(코사인 순 vs BM25 순)이라, 이어붙여 그 위치를 하나의 순위로 쓰면 키워드 검색
     * 1위가 병합 리스트에서 2위가 되어 화면에도 RRF 에도 어느 쪽 순위도 아닌 숫자가 남는다.
     *
     * <p>{@code keyword} 는 {@code vector} 가 가져오지 못한 id 만 담는다 — 그래서 두 리스트는
     * 서로소이고, 각각 축으로 융합해도 한 청크가 가중치를 두 번 받지 않는다.
     */
    record CuratedHits(List<Document> vector, List<Document> keyword) {
        static final CuratedHits EMPTY = new CuratedHits(List.of(), List.of());
    }

    /**
     * 큐레이션 축의 후보 = <b>벡터 ∪ BM25</b>, 합쳐서 축 하나로 돌려준다 (§10.11 "큐레이션은 축 하나다").
     *
     * <p><b>왜 키워드 검색을 여기서 도는가.</b> {@code KeywordSearchRepository.search()} 는
     * {@code WHERE ... AND version = ?} 로 거르는데 위쪽 키워드 축은 <b>문서 version</b> 으로
     * 부른다. 큐레이션 청크는 예약 네임스페이스 {@code "curated"} 에 색인되므로 그 질의로는
     * 절대 나오지 않는다 — 즉 {@code CuratedQaService} 가 FTS 행을 쓰기 시작한 것만으로는
     * {@code excerpt_keywords} 가 여전히 아무 데서도 읽히지 않는다. 이 축에서 같은 네임스페이스로
     * 한 번 더 물어봐야 비로소 그 값이 검색에 참여한다.
     *
     * <p><b>키워드 전용 히트에는 메타데이터를 찍어 준다.</b> {@code chunk_fts} 에는
     * {@code doc_type} 컬럼이 없어서 FTS 에서 만든 Document 는 자기가 큐레이션인 줄 모른다.
     * 그대로 두면 {@link #filterByTags} 의 큐레이션 면제가 걸리지 않아 태그 칩을 하나라도 켜는
     * 순간 탈락하고(면제가 생긴 원인이 바로 그 증상이다), 출처 라벨도 문서처럼 붙는다.
     *
     * <p><b>순서.</b> 벡터 히트가 앞이고 키워드 전용 히트가 뒤다 — 이 축은 질문 기반 매칭을
     * 전제로 설계됐고, BM25 를 더한 목적은 <b>벡터가 놓친 정확한 단어</b>를 후보에 넣는 것이다.
     * 그런 항목은 정의상 벡터 목록에 없으므로 중요한 것은 등장 여부이지 정확한 등수가 아니다.
     *
     * <p><b>문서 축보다 높은 문턱을 건다</b>({@link #gateCurated}). 큐레이션 풀은 작아서(실제 배포 1건) 무엇이든 문턱만
     * 넘으면 이 축의 1위가 되고, RRF 는 순위만 보므로 그 1위는 상위 문서와 비슷한 점수를 받는다 — 문서 축의 임계값
     * (0.3~0.35)과 OR 로 묶인 키워드 검색으로는 관련 없는 항목 하나가 거의 모든 답변의 출처에 끼었다(골든셋 26문항 중
     * 25, SEARCH_EVALUATION §3.7).
     */
    private CuratedHits curatedAxis(String userId, String question, int fetchK, boolean hybridEnabled) {
        List<Document> vectorHits =
                ragService.search(userId, question, CuratedQaService.CURATED_VERSION, fetchK);
        List<Document> keywordHits = hybridEnabled
                ? ragService.keywordSearch(CuratedQaService.CURATED_VERSION, question, fetchK)
                : List.of();
        CuratedHits gated = gateCurated(vectorHits, keywordHits, question, props.searchCuratedQaMinSimilaritySafe());
        if (log.isDebugEnabled() && (gated.vector().size() < vectorHits.size() || !keywordHits.isEmpty())) {
            log.debug("[RETRIEVAL] 큐레이션 축 — 벡터 {}개 중 {}개 통과(최소 유사도), 키워드 {}개 중 키워드로만 {}개 받음",
                    vectorHits.size(), gated.vector().size(), keywordHits.size(), gated.keyword().size());
        }
        return gated;
    }

    /** 키워드로만 찾힌 큐레이션 항목이 받아들여지려면 본문에 있어야 하는 질문의 내용어 수(질문의 내용어가 더 적으면 그 수). */
    static final int MIN_CURATED_KEYWORD_MATCHES = 2;

    /**
     * 큐레이션 축의 두 절반에 문턱을 건다 — 순수 함수(테스트용으로 열어 둔다).
     *
     * <ul>
     *   <li><b>벡터 절반</b>: 유사도가 {@code minSimilarity} 미만이면 버린다. 문서 축의 임계값은 벡터 저장소 안에서 이미
     *       걸렸다 — 이 값은 그 위에 한 번 더 거는 큐레이션 전용 바닥이다.</li>
     *   <li><b>키워드 절반</b>: 벡터 절반에 남지 않은 항목만 보고, 질문의 <b>내용어</b>({@link QuestionKeywords#extract} —
     *       의문·기능어를 뺀 어간)가 그 항목의 검색 텍스트에 {@value #MIN_CURATED_KEYWORD_MATCHES}개 이상(질문의 내용어가 더
     *       적으면 전부) 있어야 받는다. 키워드 검색은 질문의 낱말을 OR 로 묶으므로, 예전에는 {@code 어떻게} 같은 흔한
     *       낱말 하나만 맞아도 들어왔다. 내용어가 없는 질문이면 키워드로만 찾힌 항목은 받지 않는다.</li>
     * </ul>
     * 벡터에서 떨어진 항목도 키워드 조건을 채우면 키워드 절반으로 들어온다 — 그것이 BM25 를 더한 목적(벡터가 놓친 정확한
     * 단어)이다.
     */
    static CuratedHits gateCurated(List<Document> vectorHits, List<Document> keywordHits, String question,
                                   double minSimilarity) {
        List<Document> vector = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Document d : vectorHits) {
            double similarity = d.getScore() == null ? 0.0 : d.getScore();
            if (similarity < minSimilarity) continue;
            vector.add(d);
            seen.add(d.getId());
        }
        List<String> contentWords = QuestionKeywords.extract(question);
        int needed = Math.min(MIN_CURATED_KEYWORD_MATCHES, contentWords.size());
        List<Document> keywordOnly = new ArrayList<>();
        if (needed > 0) {
            for (Document d : keywordHits) {
                if (seen.contains(d.getId())) continue;
                if (matchedContentWords(d.getText(), contentWords) < needed) continue;
                if (seen.add(d.getId())) keywordOnly.add(markCurated(d));
            }
        }
        return new CuratedHits(List.copyOf(vector), List.copyOf(keywordOnly));
    }

    /**
     * 검색 텍스트에 들어 있는 내용어 수(대소문자 무시). 한글 어간은 조사·어미가 붙은 모양({@code 역할} → {@code 역할을})을 맞혀야 해
     * 부분 문자열로 찾고, <b>영문·숫자로만 된 낱말은 낱말 단위로</b> 찾는다 — 부분 문자열로 찾던 동안 {@code PU, FU, DU} 같은
     * 두 글자 약어가 본문의 {@code CPU}·{@code pmEDU04…} 안에서 맞아 무관한 항목이 "내용어 둘"을 채웠다(SEARCH_EVALUATION §3.7).
     */
    static int matchedContentWords(String text, List<String> contentWords) {
        if (text == null || text.isBlank()) return 0;
        String haystack = text.toLowerCase(Locale.ROOT);
        int n = 0;
        for (String w : contentWords) {
            if (ASCII_WORD.matcher(w).matches() ? containsAsciiWord(haystack, w) : haystack.contains(w)) n++;
        }
        return n;
    }

    private static final java.util.regex.Pattern ASCII_WORD = java.util.regex.Pattern.compile("[a-z0-9_.-]+");

    /** {@code word} 가 앞뒤로 영문·숫자가 붙지 않은 채 나오는가 — {@code cpu} 안의 {@code pu} 는 아니다. */
    private static boolean containsAsciiWord(String haystack, String word) {
        for (int i = haystack.indexOf(word); i >= 0; i = haystack.indexOf(word, i + 1)) {
            int end = i + word.length();
            boolean startOk = i == 0 || !isAsciiAlnum(haystack.charAt(i - 1));
            boolean endOk = end == haystack.length() || !isAsciiAlnum(haystack.charAt(end));
            if (startOk && endOk) return true;
        }
        return false;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * 키워드(BM25) 축으로만 찾힌 청크를 <b>저장된 원문</b>으로 되돌린다.
     *
     * <p>{@code chunk_fts.content} 는 원문이 아니라 검색용 파생 텍스트다({@code SearchTextBuilder} — 파일명·LLM 이 쓴
     * 맥락 요약을 앞에 붙이고 마크다운 장식을 걷은 본문). 키워드 축은 그 텍스트로 {@link Document} 를 만들고, 융합은
     * 같은 청크가 벡터 축에도 있을 때만 벡터 쪽 문서(원문)로 바꾼다({@link #addRrfAxis}). 그래서 키워드 축에만 있던
     * 청크는 그 파생 텍스트가 그대로 답변 프롬프트·검증 발췌·출처 미리보기·응답 참여도로 갔다 — LLM 이 만든 요약이 문서
     * 내용인 것처럼 근거가 됐다(질의 확장으로 벡터 축이 셋으로 나뉘면 키워드 1위가 최종 컷에 들어온다).
     *
     * <p>알아보는 법은 점수다: 벡터 축의 문서는 언제나 유사도를 나르고, 키워드 축의 문서는 나르지 않는다(융합도 점수가
     * 있는 쪽을 남긴다). 원문은 버전별로 한 번에 읽는다. 문서를 식별하는 키({@code doc_id}·{@code chunk_index}·
     * {@code version} — {@link #docKey} 와 융합 진단의 열쇠)와 큐레이션 표시({@link #markCurated})는 받은 쪽 값을
     * 지키고, 나머지 메타데이터는 저장본으로 채운다. 원문을 찾지 못한 청크(그사이 지워졌거나 백엔드가 읽기를 지원하지
     * 않는다)는 받은 그대로 둔다 — 고친 것은 아니지만, 그 때문에 검색을 실패시킬 일도 아니다.
     */
    List<Document> withStoredText(List<Document> docs) {
        Map<String, List<String>> idsByVersion = new LinkedHashMap<>();
        for (Document d : docs) {
            if (d.getScore() != null || d.getId() == null) continue;
            String version = String.valueOf(d.getMetadata().getOrDefault(MetaKey.VERSION, ""));
            idsByVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(d.getId());
        }
        if (idsByVersion.isEmpty()) return docs;
        Map<String, Document> stored = new HashMap<>();
        for (Map.Entry<String, List<String>> e : idsByVersion.entrySet()) {
            try {
                for (Document s : ragService.findChunksByIds(e.getKey(), e.getValue())) stored.put(s.getId(), s);
            } catch (Exception ex) {
                log.warn("[RETRIEVAL] 키워드 축 청크의 원문을 읽지 못했다 — 검색용 텍스트를 그대로 쓴다 version={}: {}",
                        e.getKey(), ex.getMessage());
            }
        }
        if (stored.isEmpty()) return docs;
        log.debug("[RETRIEVAL] 키워드 축에만 있던 청크 {}개를 원문으로 되돌림 (요청 {}개)", stored.size(),
                idsByVersion.values().stream().mapToInt(List::size).sum());
        return docs.stream().map(d -> {
            Document s = d.getScore() == null ? stored.get(d.getId()) : null;
            if (s == null || s.getText() == null) return d;
            Map<String, Object> meta = new HashMap<>(d.getMetadata());
            meta.putAll(s.getMetadata());
            for (String key : List.of(MetaKey.DOC_ID, MetaKey.CHUNK_INDEX, MetaKey.VERSION,
                    MetaKey.DOC_TYPE, MetaKey.SOURCE_TYPE)) {
                Object kept = d.getMetadata().get(key);
                if (kept != null) meta.put(key, kept);
            }
            return Document.builder().id(d.getId()).text(s.getText()).metadata(meta).build();
        }).toList();
    }

    /** FTS 에서 온 큐레이션 히트에 이 축임을 알리는 두 키를 찍는다 — {@link #curatedAxis} 참고. */
    private static Document markCurated(Document ftsHit) {
        Map<String, Object> meta = new HashMap<>(ftsHit.getMetadata());
        meta.put(MetaKey.DOC_TYPE, "curated_qa");
        meta.put(MetaKey.SOURCE_TYPE, "curated_qa");
        return Document.builder().id(ftsHit.getId()).text(ftsHit.getText()).metadata(meta).build();
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
     * carries its own configurable {@code keywordWeight} ({@code app.search-rrf-keyword-weight}, shipped
     * at 0.5; 1.0 would be parity with the normalized vector group, and this method's own no-argument
     * fallback). When there is no keyword axis (hybrid disabled or no hits), this reduces to unweighted RRF —
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
     * curated Q&A (§10.10/§10.11, admin-approved 지식 제안 embedded under the reserved {@code "curated"}
     * version namespace) — its own configurable {@code curatedWeight}, same treatment as the
     * keyword axis (flat weight, not group-normalized with the vector axes). Empty/absent axis is
     * a no-op, so this reduces to the 5-arg behavior when curated search is disabled or has no
     * hits. Package-private for unit testing.
     */
    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    List<Document> curatedRanked, int topK, int k,
                                    double keywordWeight, double curatedWeight) {
        return mergeRrfScored(vectorRanked, keywordRanked, curatedRanked, List.of(),
                topK, k, keywordWeight, curatedWeight).docs();
    }

    /**
     * Per-chunk retrieval diagnostics produced as a by-product of fusion. {@code vectorSimilarity}
     * is the best cosine across the vector axes — the document one and the curated axis's vector
     * half — and is null for a chunk that only ever appeared on a BM25 axis (those carry rank, not
     * distance). A curated chunk found only by keyword therefore has no similarity <b>and that is
     * the honest value</b>: there is no cosine to report, and the rendered {@code bm25:N} says why.
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
        return mergeRrfScored(vectorRanked, keywordRanked, curatedRanked, List.of(),
                topK, k, keywordWeight, curatedWeight);
    }

    /**
     * @param curatedKeywordRanked 큐레이션 축의 <b>키워드 절반</b>({@link CuratedHits#keyword()}).
     *                             {@code curatedRanked} 와 서로소이며 같은 {@code curatedWeight} 를
     *                             쓰고, 진단에는 {@code bm25} 로 표시된다. 하이브리드가 꺼져 있으면
     *                             비어 있고, 그때는 위 오버로드와 결과가 같다.
     */
    static RrfResult mergeRrfScored(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                     List<Document> curatedRanked, List<Document> curatedKeywordRanked,
                                     int topK, int k,
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
            // 이 절반은 진짜 벡터 검색이라 코사인을 나른다 — 예전에는 축 전체를 "유사도 없음" 으로
            // 넘겨 RrfMetrics.vectorSimilarity 가 늘 비었고, 화면이 d.getScore() 폴백(융합이 없는
            // 경로용이라고 적혀 있는 그것)에 기대 있었다.
            addRrfAxis(curatedRanked, curatedWeight, k, AXIS_CURATED, true, scores, byKey, bestSimilarity, ranksByKey);
        }
        // 큐레이션의 키워드 절반. 라벨을 AXIS_KEYWORD 로 <b>재사용</b>하는 것은 충돌이 없기
        // 때문이다 — KeywordSearchRepository.search() 가 version 으로 거르므로 큐레이션 청크는
        // 문서 BM25 축에, 문서 청크는 이쪽에 절대 오지 못한다. 청크 하나 기준으로 "bm25" 는 언제나
        // 한 가지 뜻이다. 가중치는 벡터 절반과 같은 curatedWeight 를 쓴다: 다르게 주는 것은 "키워드
        // 로만 찾힌 큐레이션 항목을 덜 믿겠다"는 정책 결정이라 표시 수정에 딸려 갈 일이 아니다.
        if (curatedKeywordRanked != null && !curatedKeywordRanked.isEmpty()) {
            addRrfAxis(curatedKeywordRanked, curatedWeight, k, AXIS_KEYWORD, false,
                    scores, byKey, bestSimilarity, ranksByKey);
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

    /**
     * 큐레이션 출처의 위치 표시 — 문서 쪽 {@code | p.12} · {@code | ch 3} 과 같은 자리다. 예전에는
     * 이 축만 라벨뿐이라 출처 목록에 같은 문구가 여러 줄 뜨면 어느 항목인지 구분할 수 없었다.
     *
     * <p>값은 {@code doc_id}({@code curated:1})의 행 번호이고, 한 행이 여러 청크로 나뉜 경우에만
     * 청크 번호가 뒤에 붙는다 — 즉 {@code #1} · {@code #1-1} 로, {@code /admin} 에 보이는 청크 id
     * ({@code curated-1} · {@code curated-1-1})의 숫자와 그대로 맞는다. id 문자열을 파싱하지 않고
     * 메타데이터에서 만드는 이유는 표시가 id 명명 규칙에 묶이지 않게 하기 위해서다.
     *
     * @return 위치 문구, 또는 행 번호를 알 수 없으면 {@code null}(라벨만 쓴다)
     */
    private static String curatedLocator(Map<String, Object> meta) {
        String docId = meta.get(MetaKey.DOC_ID) == null ? "" : meta.get(MetaKey.DOC_ID).toString();
        int colon = docId.lastIndexOf(':');
        String row = colon >= 0 ? docId.substring(colon + 1).trim() : "";
        if (row.isEmpty()) return null;
        Object idx = meta.get(MetaKey.CHUNK_INDEX);
        String chunk = idx == null ? "0" : normalizeIndex(idx);
        return "0".equals(chunk) ? row : row + "-" + chunk;
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
     * <p><b>페이지를 모르면 그 자리를 아예 비운다</b> — 예전에는 {@code "파일명 | p.?"} 였다.
     * 챕터 쪽은 이미 같은 규칙이었다({@code "ch ?"} 대신 파일명만), 페이지 쪽만 물음표를 찍고
     * 있었다. 이게 드러나는 자리는 <b>삭제된 청크</b>다: 다시 연 대화에서 출처를 그릴 때
     * {@code turn_source_ref} 는 페이지를 스냅샷하지 않고 라이브 조인으로 가져오는데, 청크가
     * 지워졌으면 그 조인이 비어 모든 삭제 청크가 {@code p.?} 로 찍혔다. {@code ?} 는 "페이지가
     * 있는데 못 읽었다"처럼 보이지만 실제로는 <b>가리킬 페이지가 더는 존재하지 않는다</b>.
     *
     * @param displayNames docId → display-name override (§ 표시 이름), from {@link RagService#findDisplayNames};
     *                     the label shows the override when present, but the <b>real</b> filename still
     *                     drives {@link #isChapterStructuredFilename} — a display name is cosmetic and
     *                     typically has no extension, so it must never decide the citation format.
     */
    static String formatSource(Document doc, Map<String, String> displayNames) {
        Map<String, Object> meta = doc.getMetadata();
        if ("curated_qa".equals(meta.get(MetaKey.DOC_TYPE))) {
            String at = curatedLocator(meta);
            return at == null ? "💬 큐레이션 Q&A" : "💬 큐레이션 Q&A | #" + at;
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
        String page = normalizePage(meta.get(MetaKey.PAGE_OR_SLIDE));
        if (page == null) {
            return filename;
        }
        return "%s | p.%s".formatted(filename, page);
    }

    /** 표시할 만한 페이지 값이면 그 문자열, 아니면 {@code null}(비었거나 {@code "?"} 자리표시자). */
    private static String normalizePage(Object pageOrSlide) {
        if (pageOrSlide == null) return null;
        String raw = pageOrSlide.toString().trim();
        if (raw.isEmpty() || "?".equals(raw) || "null".equalsIgnoreCase(raw)) return null;
        return raw;
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
