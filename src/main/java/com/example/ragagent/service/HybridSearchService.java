package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutedChatModel;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.llm.ThinkingSiteChatModel;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.service.RetrievalService.RrfMetrics;
import com.example.ragagent.service.RetrievalService.RrfResult;
import com.example.ragagent.web.MdcPropagation;
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
 * 하이브리드 검색 — 질문 하나로 <b>후보 풀</b>을 만든다: 벡터(질의 확장 포함) · 키워드(BM25) · 큐레이션 세 축을 병렬로 돌려
 * 가중 RRF 로 융합하고, 태그 필터와 직전 시도의 제외를 건 것까지. 최종 컷·리랭크·출처 라벨·이미지는 하지 않는다
 * ({@link RetrievalService} 의 일이다).
 *
 * <p>{@code RetrievalService} 에서 <b>동작을 바꾸지 않고 옮겼다</b>(Phase 1a, AGENT_MCP_DESIGN §2.1) — 이 풀이 Tool(`searchKnowledge`)과
 * 채팅 검색 루프의 공통 재료가 되도록 {@code AgentState} 에서 떼어 냈다. 입력은 {@link SearchRequest}, 출력은 {@link SearchResult}
 * 이고, 핫 설정(하이브리드 켬·RRF k·가중치·큐레이션)은 호출마다 {@code props.xxxSafe()} 로 다시 읽는다.
 *
 * <p>정적 도우미({@code mergeRrf*} · {@code docKey} · {@code gateCurated} …)는 기존 테스트가 {@code RetrievalService} 이름으로
 * 부르므로 그쪽에 위임이 남아 있다. {@link RrfResult}·{@link RrfMetrics} 레코드도 같은 이유로 거기 둔다.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final RagService ragService;
    private final AppProperties props;
    private final MultiQueryExpander multiQueryExpander;

    public HybridSearchService(LlmRouter llmRouter, RagService ragService, AppProperties props,
                               MessageSource messageSource) {
        this.ragService = ragService;
        this.props = props;
        // MultiQueryExpander builds its own ChatClient around the model it's given and calls it at a
        // point the caller doesn't control, so executeGated() can't wrap the call site. §6.21 (작업2) —
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

    /**
     * 검색 한 번의 입력. {@code question} 은 질의 확장 게이트가 보는 <b>원문</b>이고({@link #shouldExpand}),
     * {@code searchQuestion}·{@code keywordQuestion} 은 각각 벡터 축과 키워드 축이 보는 질의다(독립화·긴 질문 처리 결과 —
     * 없으면 원문). {@code candidateK} 는 축마다 가져오는 수이자 융합 뒤 풀 크기다.
     *
     * @param reasonQuery 재시도라면 평가가 남긴 "무엇이 부족했는지" 한 문장 — 벡터 축 하나로 더 들어간다
     * @param allowExpansion 질의 확장(LLM)을 돌려도 되는가 — 교차 인코더가 후보를 다시 줄 세우는 턴은 확장을 건너뛴다(턴의 전처리 LLM
     *                       호출을 늘리지 않고, 측정상 확장이 바꾸는 결과가 골든셋 26건 중 0~1건이라 값이 작다)
     */
    public record SearchRequest(String userId, String version, String question, String searchQuestion,
                         String keywordQuestion, List<String> selectedTags, Set<String> excludedDocIds,
                         Optional<String> reasonQuery, int candidateK, boolean allowExpansion) {}

    /** 융합 뒤 태그 필터와 제외를 거친 후보 풀(점수순)과 융합 진단({@link RetrievalService#docKey} 로 찾는다). */
    public record SearchResult(List<Document> candidates, Map<String, RrfMetrics> metrics) {}

    /**
     * 세 축을 병렬로 돌려 융합하고 태그 필터·제외까지 건 후보 풀을 돌려준다. 예외는 호출자가 처리한다 —
     * {@code RetrievalService} 가 원문 단일 검색으로 물러난다({@link #fallbackSearch}).
     */
    public SearchResult search(SearchRequest req) {
        boolean hybridEnabled = props.searchHybridEnabledSafe();
        boolean curatedQaEnabled = props.searchCuratedQaEnabledSafe();
        double curatedQaWeight = props.searchCuratedQaWeightSafe();
        int rrfK = props.searchRrfKSafe();
        double rrfKeywordWeight = props.searchRrfKeywordWeightSafe();
        int candidateK = req.candidateK();
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
                            ? ragService.keywordSearch(req.version(), req.keywordQuestion(), fetchK)
                            : List.<Document>of(),
                    exec);
            // §10.10 — curated-Q&A axis: single search against the original question (not
            // multi-query variants — the curated pool is small and question-driven matching is
            // the point), scoped to the reserved "curated" version namespace.
            CompletableFuture<CuratedHits> curatedF = CompletableFuture.supplyAsync(
                    () -> curatedQaEnabled
                            ? curatedAxis(req.userId(), req.searchQuestion(), req.keywordQuestion(), fetchK, hybridEnabled)
                            : CuratedHits.EMPTY,
                    exec);
            // 게이트는 **원문 길이**로 판단한다 — 독립화된 질의는 원문보다 길어지는 것이 정상이라
            // 재작성 결과로 재면 확장까지 함께 돌아 한 턴에 질의 전처리 LLM 호출이 둘이 된다.
            // 원문으로 재면 이 분기와 QuestionCondenser.gateOpen() 이 정확한 여집합이라 둘 중
            // 하나만 돈다(§10.12 — "이미 건너뛰던 호출 자리를 쓴다").
            if (req.allowExpansion() && shouldExpand(req.question())) {
                CompletableFuture<List<Document>> originalF = CompletableFuture.supplyAsync(
                        () -> ragService.search(req.userId(), req.searchQuestion(), req.version(), fetchK),
                        exec);
                // Wrap for the LLM-facing expansion prompt only (delimiter isolation, same as every
                // other prompt-construction site) — the vector search axes above still embed the
                // raw req.searchQuestion() untouched. includeOriginal(true) echoes back exactly the
                // wrapped string as one "variant", so filter against that same wrapped string
                // (not the raw question) to strip it before the variant-only batch search below.
                String expansionInput = PromptInjectionGuard.wrap(req.searchQuestion());
                List<String> variantTexts = new ArrayList<>(
                        multiQueryExpander.expand(new Query(expansionInput)).stream()
                                .map(Query::text)
                                .filter(t -> !t.equals(expansionInput))
                                .toList());
                // 재시도라면 평가가 남긴 "무엇이 부족했는지"를 검색 축 하나로 더 넣는다.
                // 확장기는 빈 생성 시점에 프롬프트가 고정돼 있어 per-request 로 지시를 바꿀 수
                // 없지만, 그 사유 문장 자체가 이미 "못 찾은 것"을 이름으로 부르고 있어 질의로
                // 쓸 만하다. LLM 왕복은 늘지 않는다 — 벡터 검색 한 번이 추가될 뿐이다.
                req.reasonQuery().ifPresent(variantTexts::add);
                ranked = new ArrayList<>();
                ranked.add(originalF.join());
                if (!variantTexts.isEmpty()) {
                    ranked.addAll(ragService.searchBatch(req.userId(), variantTexts, req.version(), candidateK));
                }
            } else {
                ranked = ragService.searchBatch(req.userId(), List.of(req.searchQuestion()), req.version(), candidateK);
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
                List<Document> candidates = fused.docs();
        // Strict AND tag filter — applied after RRF, before rerank/cut. Covers vector
        // + BM25 axes uniformly (tags travel in chunk metadata). No no-tag fallback on shortfall.
        candidates = filterByTags(candidates, req.selectedTags(), candidateK);
        // 직전 시도에서 근거로 쓰이지 않은 하위 청크를 뺀다. 자리를 비우는 것이 목적이므로
        // 최종 컷 '전에' 걸어야 다음 후보가 그 자리로 올라온다(§ 재시도 개선).
        candidates = RetrievalEviction.withoutExcluded(candidates, req.excludedDocIds());

        return new SearchResult(candidates, fused.metrics());
    }

    /**
     * 확장·융합이 실패했을 때의 물러남 — 원문 하나로 벡터 검색만 하고 태그 범위는 지킨다(실패 경로에서 태그가 새면 안 된다).
     * 최종 컷은 호출자가 한다.
     */
    List<Document> fallbackSearch(String userId, String version, String searchQuestion,
                                  List<String> selectedTags, int fetchK, int tagFilterK) {
        List<Document> fallback = ragService.search(userId, searchQuestion, version, fetchK);
        return filterByTags(fallback, selectedTags, tagFilterK);
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
        // 긴 질문은 확장하지 않는다 — 전문을 변형 3개로 다시 쓰느라 출력이 질문만큼 커진다. 그 자리는 LongQuestionQuery 가
        // 대신한다(1단계는 LLM 없이, 2단계는 QuestionCondenser.distill 이 검색문·키워드를 만든다). 한 턴의 질의 전처리 LLM 호출이
        // 최대 하나라는 규칙은 그대로다.
        if (LongQuestionQuery.isLong(question)) return false;
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
    /**
     * 긴 질문이면 벡터 절반과 키워드 절반이 <b>다른 질의</b>를 본다 — 벡터는 짧은 검색문, 키워드(와 그 내용어 문턱)는 추려 둔 검색어.
     * 짧은 질문에서는 둘이 같은 문자열이라 위 오버로드와 동작이 같다.
     */
    private CuratedHits curatedAxis(String userId, String vectorQuestion, String keywordQuestion,
                                    int fetchK, boolean hybridEnabled) {
        List<Document> vectorHits =
                ragService.search(userId, vectorQuestion, CuratedQaService.CURATED_VERSION, fetchK);
        List<Document> keywordHits = hybridEnabled
                ? ragService.keywordSearch(CuratedQaService.CURATED_VERSION, keywordQuestion, fetchK)
                : List.of();
        CuratedHits gated = gateCurated(vectorHits, keywordHits, keywordQuestion, props.searchCuratedQaMinSimilaritySafe());
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
    public List<Document> withStoredText(List<Document> docs) {
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

    /** Normalizes a chunk index from any source (Integer/Double/String) to a canonical int string. */
    static String normalizeIndex(Object idx) {
        if (idx instanceof Number n) return Integer.toString(n.intValue());
        String s = idx.toString().trim();
        try {
            return Integer.toString((int) Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
