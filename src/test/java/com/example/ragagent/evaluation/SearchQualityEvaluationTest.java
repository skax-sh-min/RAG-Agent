package com.example.ragagent.evaluation;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.service.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §10.7.5 검색 품질 평가 하네스 — 실제 임베딩·LLM 서버 + 실제 색인된 코퍼스(data/)에 대해
 * {@link RetrievalService}의 전체 파이프라인(MultiQuery → 벡터+BM25 하이브리드 → 가중 RRF →
 * (opt-in) 리랭킹)을 실행해 recall@k / nDCG@k를 측정한다.
 *
 * <p><b>기본적으로 skip된다.</b> {@code -Dsearch-eval.enabled=true}로만 실행되며, {@code .env}에
 * 설정된(또는 OS 환경변수의) 실제 LLM/임베딩 엔드포인트와 프로젝트의 실제 {@code data/} 벡터
 * 인덱스를 그대로 사용한다 — CI/기본 빌드에서는 그런 환경이 보장되지 않으므로 게이팅됨
 * ({@code SqliteVecIntegrationTest}의 {@code sqlitevec.path} 게이팅과 동일한 패턴).
 *
 * <p><b>읽기 전용</b> — {@code search()}/{@code searchBatch()}만 호출하며 색인을 추가·삭제하지
 * 않는다. 실행 전 {@code data/documents}의 NEXCORE 문서 3종이 {@code version=latest}로 이미
 * 색인되어 있어야 한다({@code doc_registry} 참고).
 *
 * <p>정량 비교 예시: {@code app.search-hybrid-enabled}/{@code app.search-rerank-enabled} 등을
 * {@code -D} 오버라이드로 바꿔가며 재실행해 recall@k 변화를 비교한다(§10.7.2·§10.7.3 결정 재검증).
 *
 * <pre>
 * mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "file:.env", encoding = "UTF-8")
@EnabledIfSystemProperty(named = "search-eval.enabled", matches = "true")
@ResourceLock("global-state")
class SearchQualityEvaluationTest {

    private static final String GOLD_RESOURCE = "search-eval/nexcore-gold.json";

    @Autowired RetrievalService retrievalService;
    @Autowired AppProperties props;

    @Test
    void reportRecallAndNdcg() throws IOException {
        GoldQuery.GoldSet goldSet = loadGoldSet();
        int k = goldSet.topK();

        List<Double> recalls = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        StringBuilder table = new StringBuilder();

        for (GoldQuery gq : goldSet.cases()) {
            AgentState state = AgentState.of(gq.question(), goldSet.version(), "search-eval", "", RoutingMode.COST_FIRST);
            List<Document> results = retrievalService.execute(state).retrievedDocs();

            List<Boolean> relevance = results.stream()
                    .map(d -> isRelevant(d, gq.mustContainAny()))
                    .toList();
            double recall = SearchQualityMetrics.recallAtK(relevance, k);
            double ndcg = SearchQualityMetrics.ndcgAtK(relevance, k);
            int rank = SearchQualityMetrics.firstRelevantRank(relevance, k);
            recalls.add(recall);
            ndcgs.add(ndcg);
            if (recall == 0.0) misses.add(gq.id());

            table.append("%-12s recall@%d=%.0f  nDCG@%d=%.3f  rank=%s  %s%n"
                    .formatted(gq.id(), k, recall, k, ndcg, rank > 0 ? rank : "-", gq.question()));
        }

        double meanRecall = recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanNdcg = ndcgs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("=== §10.7.5 검색 품질 평가 (" + goldSet.cases().size() + "건, k=" + k + ") ===");
        System.out.printf("config: hybrid=%s rerank=%s multiquery=%s topK=%d%n",
                props.searchHybridEnabled(), props.searchRerankEnabled(), props.searchMultiqueryEnabled(), props.searchTopK());
        System.out.print(table);
        System.out.printf("mean recall@%d = %.3f%n", k, meanRecall);
        System.out.printf("mean nDCG@%d   = %.3f%n", k, meanNdcg);
        if (!misses.isEmpty()) {
            System.out.println("miss (0 hit): " + String.join(", ", misses));
        }

        // Smoke floor, not a quality gate — a real multi-provider search pipeline over real
        // documents should clear this trivially; a collapse near 0 means retrieval is broken,
        // not that a tuning change made it slightly worse.
        assertThat(meanRecall).isGreaterThan(0.3);
    }

    private static boolean isRelevant(Document doc, List<String> mustContainAny) {
        String text = doc.getText();
        if (text == null) return false;
        return mustContainAny.stream().anyMatch(text::contains);
    }

    private static GoldQuery.GoldSet loadGoldSet() throws IOException {
        try (var in = new ClassPathResource(GOLD_RESOURCE).getInputStream()) {
            return new ObjectMapper().readValue(in, GoldQuery.GoldSet.class);
        }
    }
}
