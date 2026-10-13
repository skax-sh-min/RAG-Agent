package com.example.ragagent.evaluation;

import org.junit.jupiter.api.parallel.ResourceLock;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.QuestionCondenser;
import com.example.ragagent.service.RagService;
import com.example.ragagent.service.RetrievalService;
import com.example.ragagent.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
 * <p><b>골든셋 절은 검색만 한다</b>({@code search()}/{@code searchBatch()}) — 색인을 추가·삭제하지 않는다. 다만 앱 컨텍스트가
 * 뜨면서 Flyway·백필이 그 DB 에 쓰므로, 개발 DB 를 건드리고 싶지 않으면 {@code -Dsearch-eval.data-dir=<data/ 의 스크래치 복사본>}
 * 을 준다({@code memory.db} 한 파일이면 된다). 실행 전 {@code data/documents}의 NEXCORE 문서가 {@code version=latest}로 이미
 * 색인되어 있어야 한다({@code doc_registry} 참고).
 *
 * <p><b>정답 판정은 공백·마크다운을 걷고 한다</b>({@link SearchQualityMetrics#containsAnyMarker}) — 문서를 다시 교정·인덱싱하면 같은
 * 문장에 장식이 붙는다. 실행할 때마다 <b>코퍼스 점검</b>도 찍는다: 정답 문자열이 지금 코퍼스의 어느 청크({@code chunk_fts})에도
 * 없는 케이스는 검색이 완벽해도 맞힐 수 없으므로, recall 을 그 상한과 함께 읽어야 한다(문서가 바뀌면 골든셋을 고칠 신호).
 *
 * <p><b>후속 질문 절</b>({@code -Dsearch-eval.followup=true}, §10.12 독립화의 측정 — {@code nexcore-followup.json}): 앞 질문을 이력으로
 * 넣고 짧은 후속 질문을 다섯 갈래로 검색한다 — {@code raw}(후속 질문 그대로, 확장·독립화 없음) · {@code pipeline}(지금의 동작: 길이가
 * 독립화 게이트 안이면 독립화, 밖이면 질의 확장) · {@code condensed}(길이와 무관하게 독립화, 확장 없음 — 게이트를 넓히면 어떻게
 * 되는가) · {@code concat}(LLM 없이 앞 질문과 이어 붙인 검색어, 확장 없음 — 독립화의 값싼 대안) · {@code full}(정답 케이스의 자립적인
 * 원래 질문 = 상한). 여섯째 {@code fused} 는 검색하지 않고 {@code raw}·{@code concat} 의 결과를 RRF 로 합친다(두 검색어로 함께
 * 검색하는 방식의 근사, {@link SearchQualityMetrics#rrfFuse}). 케이스는 {@code same-topic}(앞 질문의 맥락에 기댄다)과
 * {@code topic-switch}(짧지만 다른 주제 — 앞 질문을 섞으면 손해를 보는 쪽) 두 종류다. 이 절은 이력(대화 턴)과 설정을 <b>쓰므로</b>
 * {@code search-eval.data-dir} 가 없으면 돌지 않는다. 스레드 id 는 실행마다 새로 만든다 — 같은 DB 로 다시 돌려도 이력이 겹치지 않게.
 *
 * <p>정량 비교 예시: {@code -Dsearch-eval.multiquery=false} 로 질의 확장을 끄거나, {@code app.search-hybrid-enabled} 등을
 * 바꿔가며 재실행해 recall@k 변화를 비교한다. 케이스별 결과는 {@code <out>/<label>/gold.jsonl}·{@code followup.jsonl} 에 남는다
 * ({@code -Dsearch-eval.out}, 기본 {@code target/search-eval} · {@code -Dsearch-eval.label}, 기본 {@code run}).
 * 골든셋 대신 다른 질문 묶음을 돌리려면 {@code -Dsearch-eval.gold=<클래스패스 자원>} — 예: {@code search-eval/curated-related.json}
 * (큐레이션 항목 하나를 겨냥한 질문들, 그 항목이 상위에 오는지를 본다 — SEARCH_EVALUATION §3.7).
 *
 * <pre>
 * mvn test -Dtest=SearchQualityEvaluationTest -Dsearch-eval.enabled=true \
 *     [-Dsearch-eval.data-dir=target/search-eval-data] [-Dsearch-eval.multiquery=false] \
 *     [-Dsearch-eval.followup=true] [-Dsearch-eval.label=base-mq]
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "file:.env", encoding = "UTF-8")
@EnabledIfSystemProperty(named = "search-eval.enabled", matches = "true")
@ResourceLock("global-state")
class SearchQualityEvaluationTest {

    private static final String GOLD_RESOURCE = "search-eval/nexcore-gold.json";
    private static final String FOLLOWUP_RESOURCE = "search-eval/nexcore-followup.json";
    private static final String USER = "search-eval";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TURN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 스크래치 데이터 디렉터리와 질의 확장 스위치 — 둘 다 {@code .env} 보다 앞서야 해서 여기서 싣는다. */
    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        String dir = System.getProperty("search-eval.data-dir");
        if (dir != null && !dir.isBlank()) {
            registry.add("DATA_DIR", () -> dir);
            registry.add("app.data-dir", () -> dir);
        }
        String multiquery = System.getProperty("search-eval.multiquery");
        if (multiquery != null && !multiquery.isBlank()) {
            registry.add("app.search-multiquery-enabled", () -> multiquery);
        }
    }

    @Autowired RetrievalService retrievalService;
    @Autowired RagService ragService;
    @Autowired AppProperties props;
    @Autowired QuestionCondenser condenser;
    @Autowired MemoryService memory;
    @Autowired SettingsService settings;
    @Autowired @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbc;

    private final Path out = Path.of(System.getProperty("search-eval.out", "target/search-eval"),
            System.getProperty("search-eval.label", "run"));

    @Test
    void reportRecallAndNdcg() throws IOException {
        GoldQuery.GoldSet goldSet = loadGoldSet();
        int k = goldSet.topK();
        List<String> corpus = corpusTexts(goldSet.version());

        List<Double> recalls = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        List<String> notInCorpus = new ArrayList<>();
        StringBuilder table = new StringBuilder();

        for (GoldQuery gq : goldSet.cases()) {
            boolean inCorpus = corpus.stream().anyMatch(t -> SearchQualityMetrics.containsAnyMarker(t, gq.mustContainAny()));
            if (!inCorpus) notInCorpus.add(gq.id());
            AgentState state = AgentState.of(gq.question(), goldSet.version(), "search-eval", "", RoutingMode.COST_FIRST);
            long t0 = System.nanoTime();
            List<Document> results = retrievalService.execute(state).retrievedDocs();
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<Boolean> relevance = results.stream()
                    .map(d -> SearchQualityMetrics.containsAnyMarker(d.getText(), gq.mustContainAny()))
                    .toList();
            double recall = SearchQualityMetrics.recallAtK(relevance, k);
            double ndcg = SearchQualityMetrics.ndcgAtK(relevance, k);
            int rank = SearchQualityMetrics.firstRelevantRank(relevance, k);
            recalls.add(recall);
            ndcgs.add(ndcg);
            if (recall == 0.0) misses.add(gq.id());

            table.append("%-12s recall@%d=%.0f  nDCG@%d=%.3f  rank=%s%s  %s%n"
                    .formatted(gq.id(), k, recall, k, ndcg, rank > 0 ? rank : "-", inCorpus ? "" : " (코퍼스에 정답 없음)",
                            gq.question()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case", gq.id());
            row.put("question", gq.question());
            row.put("recall", recall);
            row.put("ndcg", ndcg);
            row.put("rank", rank);
            row.put("docs", results.size());
            row.put("inCorpus", inCorpus);
            row.put("ms", ms);
            row.put("top", topFiles(results, k));
            // 키워드(BM25) 축 단독의 순위 — 융합 전 그 축이 정답을 상위 k 안에 가져오는가(질의 → MATCH 식의 변화를 잴 때).
            row.put("bm25Rank", SearchQualityMetrics.firstRelevantRank(ragService.keywordSearch(goldSet.version(), gq.question(), k)
                    .stream().map(d -> SearchQualityMetrics.containsAnyMarker(d.getText(), gq.mustContainAny())).toList(), k));
            emit("gold", row);
        }

        double meanRecall = recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanNdcg = ndcgs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        System.out.println("=== §10.7.5 검색 품질 평가 (" + goldSet.cases().size() + "건, k=" + k + ") ===");
        System.out.printf("config: hybrid=%s rerank=%s multiquery=%s(min-length=%d) topK=%d threshold=%s%n",
                props.searchHybridEnabledSafe(), props.searchRerankEnabled(), props.searchMultiqueryEnabledSafe(),
                props.searchMultiqueryMinLengthSafe(), props.searchTopKSafe(), props.searchSimilarityThresholdSafe());
        System.out.printf("corpus: %d/%d 케이스의 정답이 지금 코퍼스에 있다(= recall 상한)%s%n",
                goldSet.cases().size() - notInCorpus.size(), goldSet.cases().size(),
                notInCorpus.isEmpty() ? "" : " — 없음: " + String.join(", ", notInCorpus));
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

    /**
     * 후속 질문 한 건 — 앞 질문과 정답은 골든셋 id 로 가리킨다(정답 문자열을 한 곳에 둔다). {@code kind} 는
     * {@code same-topic}(앞 질문의 맥락에 기댄다) · {@code topic-switch}(짧지만 앞 질문과 다른 주제를 꺼낸다 — 앞 질문을
     * 검색어에 섞으면 손해를 보는 쪽).
     */
    record FollowUpCase(String id, String kind, List<String> previousIds, String question, String targetId) {}

    /** 후속 질문 절의 갈래 — 검색하는 다섯 개 + 그중 둘을 합친 {@code fused}(맨 뒤, 검색하지 않는다). */
    private static final List<String> FOLLOWUP_VARIANTS = List.of("raw", "pipeline", "condensed", "concat", "full", "fused");

    record FollowUpSet(String version, int topK, String note, List<FollowUpCase> cases) {}

    @Test
    void reportFollowUps() throws IOException {
        if (!Boolean.getBoolean("search-eval.followup")) return;
        String dataDir = System.getProperty("search-eval.data-dir");
        if (dataDir == null || dataDir.isBlank()) {
            System.out.println("[search-eval] followup 절은 이력·설정을 쓴다 — -Dsearch-eval.data-dir=<스크래치 복사본> 없이 돌지 않는다");
            return;
        }
        Map<String, GoldQuery> gold = loadGoldSet().cases().stream()
                .collect(Collectors.toMap(GoldQuery::id, Function.identity()));
        FollowUpSet set;
        try (var in = new ClassPathResource(FOLLOWUP_RESOURCE).getInputStream()) {
            set = JSON.readValue(in, FollowUpSet.class);
        }
        int k = set.topK();
        boolean multiqueryConfigured = props.searchMultiqueryEnabledSafe();
        // 스레드 id 에 실행마다 다른 값을 넣는다 — 같은 스크래치 DB 로 다시 돌릴 때 앞 질문이 같은 스레드에 겹겹이 쌓여
        // 독립화의 재료가 "같은 질문 ×N"이 되던 것을 막는다(2026-10-12 의 재실행들이 그 상태로 쟀다).
        String runToken = Long.toString(System.currentTimeMillis(), 36);
        Map<String, List<Double>> recallByVariant = new LinkedHashMap<>();
        StringBuilder table = new StringBuilder();
        try {
            for (FollowUpCase fu : set.cases()) {
                GoldQuery target = gold.get(fu.targetId());
                String kind = fu.kind() == null ? "same-topic" : fu.kind();
                boolean gateOpen = condenser.gateOpen(fu.question());
                StringBuilder line = new StringBuilder("%-6s %-12s len=%2d gate=%-5s"
                        .formatted(fu.id(), kind, fu.question().length(), gateOpen));
                Map<String, List<Document>> resultsByVariant = new LinkedHashMap<>();
                for (String variant : FOLLOWUP_VARIANTS) {
                    String question;
                    boolean expansion = false;
                    Optional<QuestionCondenser.Condensed> rewritten = Optional.empty();
                    Long ms = null;
                    List<Document> results;
                    if ("fused".equals(variant)) {
                        // fused = 원문 검색 결과와 이어 붙인 검색어의 결과를 RRF 로 합친 것(LLM 없음) — 이어 붙이기가 주제를 바꾼
                        // 질문에서 앞 주제로 끌려가는 손해를 원문 쪽 목록이 막아 주는가.
                        question = "raw + concat";
                        results = SearchQualityMetrics.rrfFuse(
                                List.of(resultsByVariant.get("raw"), resultsByVariant.get("concat")),
                                props.searchRrfKSafe(), Document::getId);
                    } else {
                        String thread = "se-" + runToken + "-" + fu.id() + "-" + variant;
                        for (String prevId : fu.previousIds()) {
                            memory.addTurn(USER, thread, gold.get(prevId).question(), "답변",
                                    LocalDateTime.now().format(TURN_TIME), 10, 10, 100, "local", 1, "N", "");
                        }
                        // concat = LLM 없이 앞 질문들과 후속 질문을 이어 붙인 검색어(독립화의 값싼 대안이 되는가).
                        String previousText = fu.previousIds().stream().map(id -> gold.get(id).question())
                                .collect(Collectors.joining(" "));
                        question = switch (variant) {
                            case "full" -> target.question();
                            case "concat" -> previousText + " " + fu.question();
                            default -> fu.question();
                        };
                        // 확장 스위치 — raw·condensed·concat 은 확장 없이(검색어의 몫만 본다), pipeline·full 은 설정된 그대로.
                        expansion = ("pipeline".equals(variant) || "full".equals(variant)) && multiqueryConfigured;
                        settings.update(SettingsKeys.SEARCH_MULTIQUERY_ENABLED, Boolean.toString(expansion));
                        boolean condense = "condensed".equals(variant) || ("pipeline".equals(variant) && gateOpen);
                        long t0 = System.nanoTime();
                        rewritten = condense
                                ? condenser.condense(USER, thread, fu.question(), Locale.KOREAN) : Optional.empty();
                        AgentState state = AgentState.of(question, set.version(), thread, "", RoutingMode.COST_FIRST).toBuilder()
                                .searchQuestion(rewritten.map(QuestionCondenser.Condensed::searchQuestion).orElse(null))
                                .build();
                        results = retrievalService.execute(state).retrievedDocs();
                        ms = (System.nanoTime() - t0) / 1_000_000;
                    }
                    resultsByVariant.put(variant, results);
                    List<Boolean> relevance = results.stream()
                            .map(d -> SearchQualityMetrics.containsAnyMarker(d.getText(), target.mustContainAny()))
                            .toList();
                    double recall = SearchQualityMetrics.recallAtK(relevance, k);
                    int rank = SearchQualityMetrics.firstRelevantRank(relevance, k);
                    recallByVariant.computeIfAbsent(kind + " " + variant, v -> new ArrayList<>()).add(recall);
                    line.append("  %s=%s".formatted(variant, rank > 0 ? rank : "-"));

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("case", fu.id());
                    row.put("kind", kind);
                    row.put("variant", variant);
                    row.put("question", question);
                    row.put("length", fu.question().length());
                    row.put("gateOpen", gateOpen);
                    row.put("expansion", expansion);
                    row.put("rewritten", rewritten.map(QuestionCondenser.Condensed::searchQuestion).orElse(null));
                    row.put("recall", recall);
                    row.put("ndcg", SearchQualityMetrics.ndcgAtK(relevance, k));
                    row.put("rank", rank);
                    row.put("ms", ms);   // 독립화 호출 포함, fused 는 null(검색하지 않는다)
                    row.put("top", topFiles(results, k));
                    emit("followup", row);
                }
                table.append(line).append("  | ").append(fu.question()).append('\n');
            }
        } finally {
            settings.reset(SettingsKeys.SEARCH_MULTIQUERY_ENABLED);
        }
        System.out.println("=== §10.12 후속 질문 검색 (" + set.cases().size() + "건, k=" + k + ", 독립화 게이트 = 길이 < "
                + props.searchMultiqueryMinLengthSafe() + ") — 값은 정답 청크의 순위(- = top-" + k + " 밖) ===");
        System.out.print(table);
        recallByVariant.forEach((kindVariant, list) -> System.out.printf("mean recall@%d %-22s = %.3f (n=%d)%n", k, kindVariant,
                list.stream().mapToDouble(Double::doubleValue).average().orElse(0.0), list.size()));
    }

    /** 상위 k 개 결과의 문서 이름 — 놓친 케이스에서 무엇이 자리를 차지했는지 본다. */
    private static List<String> topFiles(List<Document> results, int k) {
        return results.stream().limit(k)
                .map(d -> String.valueOf(d.getMetadata().getOrDefault(MetaKey.FILENAME, d.getMetadata().getOrDefault(MetaKey.DOC_ID, "?"))))
                .toList();
    }

    /** 코퍼스 점검의 재료 — 두 벡터 백엔드 모두에 있는 {@code chunk_fts} 의 검색 텍스트(맥락 + 장식을 걷은 본문). */
    private List<String> corpusTexts(String version) {
        try {
            return vectorJdbc.queryForList("SELECT content FROM chunk_fts WHERE version = ?", String.class, version);
        } catch (Exception e) {
            System.out.println("[search-eval] 코퍼스 점검 생략 — chunk_fts 를 읽지 못했다: " + e.getMessage());
            return List.of();
        }
    }

    private void emit(String section, Map<String, Object> row) throws IOException {
        Files.createDirectories(out);
        Files.writeString(out.resolve(section + ".jsonl"), JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static GoldQuery.GoldSet loadGoldSet() throws IOException {
        try (var in = new ClassPathResource(System.getProperty("search-eval.gold", GOLD_RESOURCE)).getInputStream()) {
            return JSON.readValue(in, GoldQuery.GoldSet.class);
        }
    }
}
