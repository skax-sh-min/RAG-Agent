package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 검색 루프(AGENT_MCP_DESIGN §5.2) — 후보 풀을 만들고(<b>{@link HybridSearchService}</b>) 교차 인코더로 점수를 매긴 뒤
 * ({@link CrossEncoderReranker}), 1위 점수가 문턱({@code app.search-rerank-min-score}) 미만이면 LLM 이 만든 새 검색어
 * ({@link SearchQueryRewriter})로 다시 찾는다. 최대 {@code app.search-max-attempts} 번(첫 검색 포함).
 *
 * <p><b>누가 무엇을 정하는가.</b> 다시 찾을지는 <b>코드가 점수로</b> 정한다. LLM 은 새 검색어 한 줄만 쓴다 — 작은 로컬 모델은 재검색
 * 여부를 스스로 정하지 못했다(설계서 부록 A.3). 같은 이유로 종료 조건도 전부 코드다: 문턱 이상 · 횟수 소진 · 새 검색어가 비었거나 이미 쓴
 * 것 · 리랭커 장애.
 *
 * <p><b>합치기.</b> 회차마다 리랭크한 결과를 청크 단위로 합치되 점수는 <b>최댓값</b>을 쓰고 점수순으로 자른다 — 재검색이 빗나가도
 * 첫 검색의 결과를 잃지 않는다. 점수는 그 회차의 검색어에 대한 것이라 회차 사이의 비교는 근사이지만, 문턱 판단은 회차 안에서만 한다.
 *
 * <p><b>실패.</b> 첫 회차의 리랭커 장애는 {@link Result#unavailable()} 로 돌려준다 — 호출자({@code RetrievalService})가 RRF 순서로 물러나는
 * 기존 동작을 한다. 두 번째 회차부터의 장애·검색 예외는 루프를 멈추고 그때까지 찾은 결과를 쓴다. 첫 회차의 검색 예외는 호출자의 기존
 * 예외 처리(원문 단일 검색)로 그대로 올라간다.
 */
@Service
public class SearchLoopService {

    private static final Logger log = LoggerFactory.getLogger(SearchLoopService.class);

    private final HybridSearchService hybrid;
    private final Optional<CrossEncoderReranker> crossEncoder;
    /** 없으면(테스트) 재검색은 하지 않는다 — 첫 검색과 리랭크만 한다. */
    private final SearchQueryRewriter rewriter;
    private final AppProperties props;

    @Autowired
    public SearchLoopService(HybridSearchService hybrid, Optional<CrossEncoderReranker> crossEncoder,
                             SearchQueryRewriter rewriter, AppProperties props) {
        this.hybrid = hybrid;
        this.crossEncoder = crossEncoder;
        this.rewriter = rewriter;
        this.props = props;
    }

    /**
     * 한 번의 루프 입력.
     *
     * @param first        첫 회차의 검색 요청(후보 수 · 태그 · 제외 · 재시도 사유까지 호출자가 정한 그대로)
     * @param question     사용자의 원문 질문 — 새 검색어를 쓰는 LLM 이 본다(답변 프롬프트의 {@code [현재 질문]} 과 같다)
     * @param cut          리랭크 뒤 남길 문서 수(재시도 증가분·모드·이력 부스트가 더해진 최종 컷)
     * @param allowRewrite 재검색을 허용하는가 — 턴의 <b>첫</b> RETRIEVAL 에서만 참이다. 답변 검증 실패 재시도는 한 번만 검색해
     *                     최악의 호출 수가 곱해지지 않는다
     */
    public record Params(HybridSearchService.SearchRequest first, String threadId, Locale locale, String question,
                         int cut, boolean allowRewrite, GraphListener listener) {}

    /**
     * @param docs        합쳐서 자른 최종 문서(점수순). 첫 회차에서 리랭커가 장애면 {@code null}
     * @param scores      {@code docs} 와 같은 순서의 리랭크 점수(장애면 빈 목록)
     * @param candidates  첫 회차의 후보 풀(원문으로 되돌린 것) — 리랭커 장애 때 호출자가 물러나는 재료
     * @param metrics     융합 진단(회차를 합친 것, 첫 회차가 우선)
     * @param attempts    실제로 돈 검색 횟수
     * @param queries     쓴 검색어(첫 검색어부터)
     * @param topScore    회차별 1위 점수의 최댓값. 점수를 못 얻었으면 {@link Double#NaN}
     */
    public record Result(List<Document> docs, List<Double> scores, List<Document> candidates,
                         Map<String, RetrievalService.RrfMetrics> metrics, int attempts, List<String> queries,
                         double topScore, int rewriteCalls, int inputTokens, int outputTokens) {
        /** 첫 회차에서 리랭커를 쓸 수 없었다 — 호출자가 RRF 순서로 물러난다. */
        public boolean unavailable() {
            return docs == null;
        }
    }

    public Result run(Params p) {
        CrossEncoderReranker reranker = crossEncoder.orElseThrow(
                () -> new IllegalStateException("교차 인코더 없이 검색 루프를 돌릴 수 없다"));
        double minScore = props.searchRerankMinScoreSafe();
        int maxAttempts = (p.allowRewrite() && rewriter != null) ? props.searchMaxAttemptsSafe() : 1;

        HybridSearchService.SearchRequest req = p.first();
        List<String> used = new ArrayList<>();
        used.add(req.searchQuestion());
        // 청크 키 → 가장 높은 점수의 (문서, 점수). 삽입 순서 = 먼저 본 순서(같은 점수의 안정 정렬 기준).
        Map<String, CrossEncoderReranker.Ranked> merged = new LinkedHashMap<>();
        Map<String, RetrievalService.RrfMetrics> metrics = new HashMap<>();
        List<Document> firstCandidates = List.of();
        double topScore = Double.NaN;
        int attempts = 0;
        int rewriteCalls = 0;
        int inTokens = 0;
        int outTokens = 0;

        while (true) {
            attempts++;
            boolean first = attempts == 1;
            HybridSearchService.SearchResult found;
            List<Document> candidates;
            List<CrossEncoderReranker.Ranked> ranked;
            try {
                found = hybrid.search(req);
                // 키워드 축으로만 찾힌 후보는 본문이 검색용 파생 텍스트다 — 리랭커가 그것을 읽지 않게 '리랭크 전에' 저장 원문으로 되돌린다.
                candidates = hybrid.withStoredText(found.candidates());
                if (first) {
                    firstCandidates = candidates;
                    metrics.putAll(found.metrics());
                }
                ranked = reranker.rerank(req.searchQuestion(), candidates, p.cut());
            } catch (CrossEncoderReranker.UnavailableException ex) {
                if (first) {
                    // 문서 쪽 후보는 이미 만들어졌으니 돌려줘서 호출자가 RRF 순서로 자르게 한다(재검색은 하지 않는다).
                    log.warn("[SEARCH-LOOP] 교차 인코더를 쓸 수 없다: {}", ex.getMessage());
                    return new Result(null, List.of(), firstCandidates, metrics, attempts, List.copyOf(used),
                            Double.NaN, 0, 0, 0);
                }
                log.warn("[SEARCH-LOOP] {}번째 검색의 리랭커 장애 — 찾은 결과로 진행한다: {}", attempts, ex.getMessage());
                attempts--;   // 이 회차는 결과를 내지 못했다
                break;
            } catch (RuntimeException ex) {
                if (first) throw ex;
                log.warn("[SEARCH-LOOP] {}번째 검색 실패 — 찾은 결과로 진행한다: {}", attempts, ex.toString());
                attempts--;
                break;
            }

            for (Map.Entry<String, RetrievalService.RrfMetrics> e : found.metrics().entrySet()) {
                metrics.putIfAbsent(e.getKey(), e.getValue());
            }
            for (CrossEncoderReranker.Ranked r : ranked) {
                merged.merge(HybridSearchService.docKey(r.doc()), r,
                        (old, now) -> now.score() > old.score() ? now : old);
            }
            double roundTop = ranked.isEmpty() ? Double.NEGATIVE_INFINITY : ranked.get(0).score();
            if (Double.isNaN(topScore) || roundTop > topScore) topScore = roundTop;
            if (log.isDebugEnabled()) {
                log.debug("[SEARCH-LOOP] {}/{} 검색어=[{}] 후보 {}개 → {}개, 1위 점수 {} (문턱 {})",
                        attempts, maxAttempts, req.searchQuestion(), candidates.size(), ranked.size(), roundTop, minScore);
            }

            // 종료 — 문턱 이상이거나 횟수를 다 썼다. 점수를 못 얻은(NaN) 일은 없다: 리랭커는 항목이 하나도 없으면 장애로 던진다.
            if (!(roundTop < minScore) || attempts >= maxAttempts) break;

            Optional<SearchQueryRewriter.Next> next = rewriter.nextQuery(req.userId(), p.threadId(), p.question(),
                    used, ranked.stream().map(CrossEncoderReranker.Ranked::doc).toList(), p.locale());
            if (next.isEmpty()) break;
            rewriteCalls++;
            inTokens += next.get().inputTokens();
            outTokens += next.get().outputTokens();
            used.add(next.get().query());
            p.listener().onSearchRetry(attempts + 1, maxAttempts);
            req = new HybridSearchService.SearchRequest(req.userId(), req.version(), next.get().query(),
                    next.get().query(), next.get().query(), req.selectedTags(), req.excludedDocIds(),
                    Optional.empty(), req.candidateK(), false);
        }

        List<CrossEncoderReranker.Ranked> best = new ArrayList<>(merged.values());
        best.sort(Comparator.comparingDouble(CrossEncoderReranker.Ranked::score).reversed());
        List<CrossEncoderReranker.Ranked> cut = best.subList(0, Math.min(p.cut(), best.size()));
        return new Result(cut.stream().map(CrossEncoderReranker.Ranked::doc).toList(),
                cut.stream().map(CrossEncoderReranker.Ranked::score).toList(), firstCandidates, metrics, attempts,
                List.copyOf(used), topScore, rewriteCalls, inTokens, outTokens);
    }
}
