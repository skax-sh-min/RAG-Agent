package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.llm.ThinkingBudget;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Retrieves relevant documents for a turn and shapes them for the answer node: the final cut (optionally after an
 * LLM rerank), source labels, images (Lazy Vision) and the retry/history-exclusion document counts.
 *
 * <p>The candidate pool itself — vector (with MultiQuery expansion), keyword and curated axes, weighted RRF,
 * tag filter, exclusion — is built by {@link HybridSearchService} (Phase 1a); the static helpers and the RRF
 * result records stay here so existing callers and tests keep their names.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final RagService ragService;
    /** 재시도 때 "청크를 더 실어도 되는가"를 묻기 위해서만 보관한다 — 답변을 받을 프로바이더를
     *  {@code AnswerService} 와 같은 방식으로 먼저 물어본다. */
    private final LlmRouter llmRouter;
    private final ProviderContextWindows contextWindows;
    private final HybridSearchService hybrid;
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

    /**
     * 스프링이 쓰는 생성자 — 하이브리드 검색은 빈으로 받는다(Tool 계층이 같은 빈을 쓴다).
     */
    @Autowired
    public RetrievalService(HybridSearchService hybrid, LlmRouter llmRouter, RagService ragService,
                            AppProperties props, Optional<LazyVisionService> lazyVisionOpt,
                            Optional<RerankerService> rerankerOpt,
                            ChatImageAnalysisSkipRegistry imageSkipRegistry,
                            ProviderContextWindows contextWindows, ThinkingBudget thinkingBudget) {
        this.hybrid = hybrid;
        this.llmRouter = llmRouter;
        this.contextWindows = contextWindows;
        this.thinkingBudget = thinkingBudget;
        this.ragService = ragService;
        this.props = props;
        this.rerankEnabled = props.searchRerankEnabled();
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.reranker = rerankerOpt;
        this.imageSkipRegistry = imageSkipRegistry;
    }

    /**
     * 기존 시그니처 — 테스트가 이 모양으로 만든다. 하이브리드 검색(질의 확장기 포함)을 직접 만든다.
     */
    public RetrievalService(LlmRouter llmRouter, RagService ragService,
                            AppProperties props, Optional<LazyVisionService> lazyVisionOpt,
                            Optional<RerankerService> rerankerOpt, MessageSource messageSource,
                            ChatImageAnalysisSkipRegistry imageSkipRegistry,
                            ProviderContextWindows contextWindows, ThinkingBudget thinkingBudget) {
        this(new HybridSearchService(llmRouter, ragService, props, messageSource), llmRouter, ragService, props,
                lazyVisionOpt, rerankerOpt, imageSkipRegistry, contextWindows, thinkingBudget);
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
        // 키워드(BM25) 축은 긴 질문에서 따로 추린 검색어를 본다(없으면 위와 같다) — AgentState.effectiveKeywordQuestion() 참고.
        String keywordQuestion = state.effectiveKeywordQuestion();
        // Read hot-editable tuning fresh each call so /settings overrides apply live.
        boolean retryEscalate = props.searchRetryEscalateSafe();
        int candidateMultiplier = props.searchCandidateMultiplierSafe();
        int tagCandidateMultiplier = props.searchTagCandidateMultiplierSafe();
        int defaultTopK = props.searchTopKSafe();
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

            // 축 실행(벡터·키워드·큐레이션)·융합·태그 필터·제외는 HybridSearchService 가 한다(Phase 1a).
            HybridSearchService.SearchResult found = hybrid.search(new HybridSearchService.SearchRequest(
                    state.userId(), state.version(), state.question(), searchQuestion, keywordQuestion,
                    selectedTags, Set.copyOf(state.excludedDocIds()), reasonQuery(state), candidateK));
            fusedMetrics = found.metrics();
            List<Document> candidates = found.candidates();
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
            List<Document> fallback = hybrid.fallbackSearch(state.userId(), state.version(), searchQuestion,
                    selectedTags, fallbackK, effectiveTopK);
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

    /** 질의 확장 게이트 — {@link HybridSearchService#shouldExpand}. 테스트가 이 이름으로 부른다. */
    boolean shouldExpand(String question) {
        return hybrid.shouldExpand(question);
    }

    /** 엄격 AND 태그 필터 — {@link HybridSearchService#filterByTags}. */
    List<Document> filterByTags(List<Document> candidates, List<String> selectedTags, int candidateK) {
        return hybrid.filterByTags(candidates, selectedTags, candidateK);
    }

    /** 큐레이션 축의 문턱 — {@link HybridSearchService#gateCurated}. */
    static HybridSearchService.CuratedHits gateCurated(List<Document> vectorHits, List<Document> keywordHits,
                                                       String question, double minSimilarity) {
        return HybridSearchService.gateCurated(vectorHits, keywordHits, question, minSimilarity);
    }

    /** 검색 텍스트에 들어 있는 내용어 수 — {@link HybridSearchService#matchedContentWords}. */
    static int matchedContentWords(String text, List<String> contentWords) {
        return HybridSearchService.matchedContentWords(text, contentWords);
    }

    /** 키워드 축 전용 히트를 저장 원문으로 되돌린다 — {@link HybridSearchService#withStoredText}. */
    List<Document> withStoredText(List<Document> docs) {
        return hybrid.withStoredText(docs);
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

    /** Reciprocal Rank Fusion — 실체는 {@link HybridSearchService#mergeRrf(List, int)}. 테스트가 이 이름으로 부른다. */
    static List<Document> mergeRrf(List<List<Document>> ranked, int topK) {
        return HybridSearchService.mergeRrf(ranked, topK);
    }

    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    int topK, int k, double keywordWeight) {
        return HybridSearchService.mergeRrf(vectorRanked, keywordRanked, topK, k, keywordWeight);
    }

    static List<Document> mergeRrf(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                    List<Document> curatedRanked, int topK, int k,
                                    double keywordWeight, double curatedWeight) {
        return HybridSearchService.mergeRrf(vectorRanked, keywordRanked, curatedRanked, topK, k,
                keywordWeight, curatedWeight);
    }

    /**
     * Per-chunk retrieval diagnostics produced as a by-product of fusion. {@code vectorSimilarity}
     * is the best cosine across the vector axes — the document one and the curated axis's vector
     * half — and is null for a chunk that only ever appeared on a BM25 axis (those carry rank, not
     * distance). A curated chunk found only by keyword therefore has no similarity <b>and that is
     * the honest value</b>: there is no cosine to report, and the rendered {@code bm25:N} says why.
     */
    record RrfMetrics(double rrfScore, Double vectorSimilarity, String axisRanks) {}

    /** Fusion output plus the diagnostics, keyed by {@link HybridSearchService#docKey(Document)}. */
    record RrfResult(List<Document> docs, Map<String, RrfMetrics> metrics) {}

    static RrfResult mergeRrfScored(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                     List<Document> curatedRanked, int topK, int k,
                                     double keywordWeight, double curatedWeight) {
        return HybridSearchService.mergeRrfScored(vectorRanked, keywordRanked, curatedRanked, topK, k,
                keywordWeight, curatedWeight);
    }

    static RrfResult mergeRrfScored(List<List<Document>> vectorRanked, List<Document> keywordRanked,
                                     List<Document> curatedRanked, List<Document> curatedKeywordRanked,
                                     int topK, int k,
                                     double keywordWeight, double curatedWeight) {
        return HybridSearchService.mergeRrfScored(vectorRanked, keywordRanked, curatedRanked,
                curatedKeywordRanked, topK, k, keywordWeight, curatedWeight);
    }

    /** 안정적인 중복 제거 키 — {@link HybridSearchService#docKey}. */
    static String docKey(Document doc) {
        return HybridSearchService.docKey(doc);
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
        String chunk = idx == null ? "0" : HybridSearchService.normalizeIndex(idx);
        return "0".equals(chunk) ? row : row + "-" + chunk;
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
            return HybridSearchService.normalizeIndex(n);
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
