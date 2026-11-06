package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.service.CrossEncoderReranker.Ranked;
import com.example.ragagent.service.CrossEncoderReranker.UnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검색 루프(§6.33) — 점수가 문턱 미만일 때만 다시 찾고, 종료 조건·합치기·실패 처리는 전부 코드가 정한다.
 * 한 턴의 검색 횟수가 설정 상한을 넘지 않는다는 것이 이 테스트가 고정하는 핵심 불변식이다(설계서 §7 1a 완료 기준).
 */
class SearchLoopServiceTest {

    private static final int CUT = 3;
    private static final double MIN_SCORE = 0.0;

    private HybridSearchService hybrid;
    private CrossEncoderReranker crossEncoder;
    private SearchQueryRewriter rewriter;
    private AppProperties props;
    private GraphListener listener;
    private SearchLoopService loop;

    @BeforeEach
    void setUp() {
        hybrid = mock(HybridSearchService.class);
        crossEncoder = mock(CrossEncoderReranker.class);
        rewriter = mock(SearchQueryRewriter.class);
        props = mock(AppProperties.class);
        listener = mock(GraphListener.class);
        when(props.searchRerankMinScoreSafe()).thenReturn(MIN_SCORE);
        when(props.searchMaxAttemptsSafe()).thenReturn(3);
        // 검색은 호출마다 그 검색어 이름이 붙은 후보 5개를 준다. withStoredText 는 그대로 돌려준다.
        when(hybrid.search(any())).thenAnswer(inv -> {
            HybridSearchService.SearchRequest r = inv.getArgument(0);
            return new HybridSearchService.SearchResult(docs(r.searchQuestion(), 5), Map.of());
        });
        when(hybrid.withStoredText(anyList())).thenAnswer(inv -> inv.getArgument(0));
        loop = new SearchLoopService(hybrid, Optional.of(crossEncoder), rewriter, props);
    }

    private static List<Document> docs(String tag, int n) {
        return IntStream.range(0, n).mapToObj(i -> new Document(tag + "#" + i, Map.of())).toList();
    }

    private static HybridSearchService.SearchRequest request(String query) {
        return new HybridSearchService.SearchRequest("u", "latest", query, query, query, List.of(), Set.of(),
                Optional.empty(), 20, false);
    }

    private SearchLoopService.Params params(String query, boolean allowRewrite) {
        return new SearchLoopService.Params(request(query), "t1", Locale.KOREAN, "원문 질문", CUT, allowRewrite, listener);
    }

    /** 검색어마다 점수를 정한다: 입력 순서대로 {@code scoresByQuery} 의 첫 점수부터 내림차순으로 매긴 {@code Ranked}를 준다. */
    private void scores(Map<String, double[]> scoresByQuery) {
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            List<Document> in = inv.getArgument(1);
            int topK = inv.getArgument(2);
            double[] sc = scoresByQuery.get(q);
            List<Ranked> out = new ArrayList<>();
            for (int i = 0; i < in.size() && i < sc.length && out.size() < topK; i++) out.add(new Ranked(in.get(i), sc[i]));
            return out;
        });
    }

    private void nextQueries(String... queries) {
        var stub = when(rewriter.nextQuery(anyString(), anyString(), anyString(), anyList(), anyList(), any()));
        org.mockito.stubbing.OngoingStubbing<Optional<SearchQueryRewriter.Next>> chain = null;
        for (String q : queries) {
            Optional<SearchQueryRewriter.Next> next = Optional.of(new SearchQueryRewriter.Next(q, 100, 10));
            chain = (chain == null) ? stub.thenReturn(next) : chain.thenReturn(next);
        }
    }

    @Test
    @DisplayName("1위 점수가 문턱 이상이면 한 번만 검색하고 새 검색어를 만들지 않는다")
    void stopsAtOnceWhenTheFirstScoreClearsTheThreshold() {
        scores(Map.of("q0", new double[]{2.5, 1.0, -3.0}));

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.docs()).hasSize(CUT);
        assertThat(r.topScore()).isEqualTo(2.5);
        assertThat(r.rewriteCalls()).isZero();
        verify(hybrid, times(1)).search(any());
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
        verify(listener, never()).onSearchRetry(anyInt(), anyInt());
    }

    @Test
    @DisplayName("문턱 미만이면 LLM 의 새 검색어로 다시 찾고, 새 검색어가 모든 검색 입력에 들어간다(확장 없이)")
    void rewritesAndSearchesAgainWithTheNewQueryEverywhere() {
        scores(Map.of("q0", new double[]{-1.0, -2.0}, "다른 표현", new double[]{3.0, 1.0}));
        nextQueries("다른 표현");

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.queries()).containsExactly("q0", "다른 표현");
        assertThat(r.rewriteCalls()).isEqualTo(1);
        assertThat(r.inputTokens()).isEqualTo(100);
        assertThat(r.outputTokens()).isEqualTo(10);
        assertThat(r.topScore()).as("회차별 1위 점수의 최댓값").isEqualTo(3.0);
        verify(listener).onSearchRetry(2, 3);

        ArgumentCaptor<HybridSearchService.SearchRequest> cap = ArgumentCaptor.forClass(HybridSearchService.SearchRequest.class);
        verify(hybrid, times(2)).search(cap.capture());
        HybridSearchService.SearchRequest second = cap.getAllValues().get(1);
        assertThat(second.question()).isEqualTo("다른 표현");
        assertThat(second.searchQuestion()).isEqualTo("다른 표현");
        assertThat(second.keywordQuestion()).isEqualTo("다른 표현");
        assertThat(second.allowExpansion()).as("전처리 LLM 호출을 늘리지 않는다").isFalse();
        assertThat(second.candidateK()).isEqualTo(20);
        // 리랭커도 새 검색어로 점수를 매긴다
        verify(crossEncoder).rerank(eq("다른 표현"), anyList(), eq(CUT));
    }

    private SearchLoopService.Params condensedParams(String condensed, String raw) {
        return new SearchLoopService.Params(request(condensed), "t1", Locale.KOREAN, raw, CUT, true, listener, raw);
    }

    @Test
    @DisplayName("독립화한 검색어의 점수가 낮으면 LLM 호출 없이 원문을 먼저 시험한다 — 재작성이 앞 질문을 끌어온 실패를 되돌린다")
    void triesTheRawQuestionBeforeAskingTheLlmWhenTheCondensedQueryScoresLow() {
        scores(Map.of("XA 타입의 영향은 무엇인가요?", new double[]{-2.0, -3.0}, "XA 타입이면?", new double[]{4.0, 1.0}));

        SearchLoopService.Result r = loop.run(condensedParams("XA 타입의 영향은 무엇인가요?", "XA 타입이면?"));

        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.queries()).containsExactly("XA 타입의 영향은 무엇인가요?", "XA 타입이면?");
        assertThat(r.rewriteCalls()).as("원문 시험은 LLM 호출이 아니다").isZero();
        assertThat(r.inputTokens()).isZero();
        assertThat(r.topScore()).isEqualTo(4.0);
        assertThat(r.docs().get(0).getText()).startsWith("XA 타입이면?");
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
        verify(listener).onSearchRetry(2, 3);
        ArgumentCaptor<HybridSearchService.SearchRequest> cap = ArgumentCaptor.forClass(HybridSearchService.SearchRequest.class);
        verify(hybrid, times(2)).search(cap.capture());
        HybridSearchService.SearchRequest second = cap.getAllValues().get(1);
        assertThat(second.question()).isEqualTo("XA 타입이면?");
        assertThat(second.searchQuestion()).isEqualTo("XA 타입이면?");
        assertThat(second.keywordQuestion()).isEqualTo("XA 타입이면?");
        assertThat(second.allowExpansion()).isFalse();
    }

    @Test
    @DisplayName("원문도 낮으면 그 다음 회차부터 평소처럼 LLM 의 새 검색어로 간다 — 원문 시험은 한 번뿐이고 총 횟수 상한을 지킨다")
    void rawTrialIsOnceAndThenTheLlmRewriterTakesOver() {
        scores(Map.of("독립화", new double[]{-1}, "원문", new double[]{-1}, "새 표현", new double[]{-1}, "또", new double[]{-1}));
        nextQueries("새 표현", "또");

        SearchLoopService.Result r = loop.run(condensedParams("독립화", "원문"));

        assertThat(r.attempts()).isEqualTo(3);
        assertThat(r.queries()).containsExactly("독립화", "원문", "새 표현");
        assertThat(r.rewriteCalls()).isEqualTo(1);
        verify(hybrid, times(3)).search(any());
    }

    @Test
    @DisplayName("독립화한 검색어의 점수가 문턱 이상이면 원문을 시험하지 않는다")
    void noRawTrialWhenTheCondensedQueryClearsTheThreshold() {
        scores(Map.of("독립화", new double[]{2.0}));

        SearchLoopService.Result r = loop.run(condensedParams("독립화", "원문"));

        assertThat(r.attempts()).isEqualTo(1);
        verify(hybrid, times(1)).search(any());
    }

    @Test
    @DisplayName("원문이 첫 검색어와 같거나 비어 있으면 원문 시험을 건너뛰고 새 검색어로 간다")
    void rawTrialSkippedWhenThereIsNothingNewToTry() {
        scores(Map.of("같은", new double[]{-1}, "새", new double[]{5.0}));
        nextQueries("새");

        SearchLoopService.Result same = loop.run(condensedParams("같은", "같은"));
        assertThat(same.queries()).containsExactly("같은", "새");
        assertThat(same.rewriteCalls()).isEqualTo(1);

        nextQueries("새");
        SearchLoopService.Result blank = loop.run(condensedParams("같은", "  "));
        assertThat(blank.queries()).containsExactly("같은", "새");
    }

    @Test
    @DisplayName("상한이 1 이면 원문 시험도 하지 않는다 — 검증 실패 재시도(allowRewrite=false)에서 호출 수가 곱해지지 않는다")
    void noRawTrialWhenRewritingIsNotAllowed() {
        scores(Map.of("독립화", new double[]{-1}));

        SearchLoopService.Params p = new SearchLoopService.Params(
                request("독립화"), "t1", Locale.KOREAN, "원문", CUT, false, listener, "원문");
        SearchLoopService.Result r = loop.run(p);

        assertThat(r.attempts()).isEqualTo(1);
        verify(hybrid, times(1)).search(any());
    }

    @Test
    @DisplayName("회차를 합칠 때 같은 청크는 점수 최댓값으로 남고 점수순으로 잘린다 — 재검색이 빗나가도 첫 결과를 잃지 않는다")
    void mergesRoundsByMaxScoreAndSortsByScore() {
        // 두 회차가 청크 B 를 공유한다
        doAnswer(inv -> {
            HybridSearchService.SearchRequest r = inv.getArgument(0);
            return new HybridSearchService.SearchResult(
                    r.searchQuestion().equals("q0") ? List.of(new Document("A"), new Document("B"), new Document("C"))
                            : List.of(new Document("B"), new Document("D"), new Document("E")), Map.of());
        }).when(hybrid).search(any());
        scores(Map.of("q0", new double[]{-0.5, -1.0, -2.0},          // A -0.5, B -1.0, C -2.0 → 문턱 미만
                "다른 표현", new double[]{-0.2, -0.6, -4.0},            // B -0.2, D -0.6, E -4.0 → 1위가 -0.2 라 아직 미만
                "세 번째", new double[]{-9.0, -9.0, -9.0}));
        nextQueries("다른 표현", "세 번째");

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(3);
        // B 는 -1.0(첫 회차)과 -0.2(둘째 회차) 중 큰 값, 합친 뒤 점수 내림차순 3개
        assertThat(r.docs()).extracting(Document::getText).containsExactly("B", "A", "D");
        assertThat(r.scores()).containsExactly(-0.2, -0.5, -0.6);
        assertThat(r.topScore()).isEqualTo(-0.2);
    }

    @Test
    @DisplayName("한 턴의 검색은 search-max-attempts 를 넘지 않는다 — 계속 문턱 미만이어도 3회(재검색 2회)에서 멈춘다")
    void neverExceedsMaxAttempts() {
        scores(Map.of("q0", new double[]{-1}, "a", new double[]{-1}, "b", new double[]{-1}, "c", new double[]{-1}));
        nextQueries("a", "b", "c");

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(3);
        assertThat(r.rewriteCalls()).isEqualTo(2);
        verify(hybrid, times(3)).search(any());
        verify(rewriter, times(2)).nextQuery(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("상한이 1 이면 재검색을 하지 않는다")
    void maxAttemptsOneDisablesTheLoop() {
        when(props.searchMaxAttemptsSafe()).thenReturn(1);
        scores(Map.of("q0", new double[]{-5}));

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(1);
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재검색이 허용되지 않은 호출(검증 실패 재시도)은 점수가 낮아도 한 번만 검색한다")
    void doesNotRewriteWhenNotAllowed() {
        scores(Map.of("q0", new double[]{-5}));

        SearchLoopService.Result r = loop.run(params("q0", false));

        assertThat(r.attempts()).isEqualTo(1);
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("새 검색어 생성기가 없으면(테스트·미구성) 재검색 없이 첫 결과로 끝난다")
    void noRewriterMeansNoRewrite() {
        SearchLoopService noRewriter = new SearchLoopService(hybrid, Optional.of(crossEncoder), null, props);
        scores(Map.of("q0", new double[]{-5}));

        SearchLoopService.Result r = noRewriter.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.docs()).hasSize(1);
    }

    @Test
    @DisplayName("새 검색어가 비어 있으면(실패·이미 쓴 검색어) 그때까지 찾은 결과로 끝난다")
    void stopsWhenNoNewQuery() {
        scores(Map.of("q0", new double[]{-5, -6}));
        when(rewriter.nextQuery(anyString(), anyString(), anyString(), anyList(), anyList(), any()))
                .thenReturn(Optional.empty());

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(1);
        assertThat(r.rewriteCalls()).isZero();
        assertThat(r.docs()).hasSize(2);
        verify(hybrid, times(1)).search(any());
    }

    @Test
    @DisplayName("둘째 회차에서 리랭커가 죽으면 루프를 멈추고 첫 회차의 결과를 쓴다 — 예외는 나가지 않는다")
    void secondRoundRerankerFailureKeepsTheFirstResult() {
        when(crossEncoder.rerank(eq("q0"), anyList(), anyInt())).thenAnswer(inv -> {
            List<Document> in = inv.getArgument(1);
            return List.of(new Ranked(in.get(0), -1.0), new Ranked(in.get(1), -2.0));
        });
        when(crossEncoder.rerank(eq("다른 표현"), anyList(), anyInt())).thenThrow(new UnavailableException("서버 다운"));
        nextQueries("다른 표현");

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.unavailable()).isFalse();
        assertThat(r.attempts()).as("결과를 내지 못한 회차는 세지 않는다").isEqualTo(1);
        assertThat(r.docs()).extracting(Document::getText).containsExactly("q0#0", "q0#1");
    }

    @Test
    @DisplayName("둘째 회차의 검색 예외도 첫 결과로 끝난다")
    void secondRoundSearchFailureKeepsTheFirstResult() {
        scores(Map.of("q0", new double[]{-1.0, -2.0}));
        nextQueries("다른 표현");
        doAnswer(inv -> {
            HybridSearchService.SearchRequest r = inv.getArgument(0);
            if (!r.searchQuestion().equals("q0")) throw new IllegalStateException("벡터 서버 오류");
            return new HybridSearchService.SearchResult(docs("q0", 5), Map.of());
        }).when(hybrid).search(any());

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.docs()).hasSize(2);
        assertThat(r.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 회차에서 리랭커를 쓸 수 없으면 후보를 돌려주고(호출자가 RRF 순서로 자른다) 재검색은 하지 않는다")
    void firstRoundRerankerFailureReturnsCandidatesForFallback() {
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenThrow(new UnavailableException("서버 다운"));

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.unavailable()).isTrue();
        assertThat(r.candidates()).hasSize(5);
        verify(hybrid, times(1)).search(any());
        verify(rewriter, never()).nextQuery(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("첫 회차의 검색 예외는 그대로 올라간다 — 호출자의 기존 폴백(원문 단일 검색)이 받는다")
    void firstRoundSearchFailurePropagates() {
        doThrow(new IllegalStateException("벡터 서버 오류")).when(hybrid).search(any());

        assertThatThrownBy(() -> loop.run(params("q0", true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("후보가 하나도 없어도(점수 없음) 재검색 후보로 본다 — 빈 결과는 낮은 점수와 같다")
    void emptyCandidatesCountAsALowScore() {
        doAnswer(inv -> {
            HybridSearchService.SearchRequest r = inv.getArgument(0);
            return new HybridSearchService.SearchResult(
                    r.searchQuestion().equals("q0") ? List.of() : docs("x", 2), Map.of());
        }).when(hybrid).search(any());
        when(crossEncoder.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            List<Document> in = inv.getArgument(1);
            List<Ranked> out = new ArrayList<>();
            for (Document d : in) out.add(new Ranked(d, 1.0));
            return out;
        });
        nextQueries("다른 표현");

        SearchLoopService.Result r = loop.run(params("q0", true));

        assertThat(r.attempts()).isEqualTo(2);
        assertThat(r.docs()).hasSize(2);
    }

    @Test
    @DisplayName("문턱은 매 호출 다시 읽는다(핫) — 같은 점수가 문턱을 올리면 재검색 대상이 된다")
    void thresholdIsReadPerCall() {
        scores(Map.of("q0", new double[]{0.5}, "a", new double[]{4.0}));
        nextQueries("a");
        assertThat(loop.run(params("q0", true)).attempts()).isEqualTo(1);

        when(props.searchRerankMinScoreSafe()).thenReturn(1.0);
        assertThat(loop.run(params("q0", true)).attempts()).isEqualTo(2);
    }
}
