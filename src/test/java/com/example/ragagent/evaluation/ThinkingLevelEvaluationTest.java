package com.example.ragagent.evaluation;

import com.example.ragagent.LogbackTestSupport;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.ingestion.ChunkSplitter;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingObservations;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.service.AnswerService;
import com.example.ragagent.service.ClassifierService;
import com.example.ragagent.service.CuratedQuestionSuggester;
import com.example.ragagent.service.DirectAnswerService;
import com.example.ragagent.service.GraphListener;
import com.example.ragagent.service.MarkdownCorrectionService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.PostAnswerService;
import com.example.ragagent.service.QuestionCondenser;
import com.example.ragagent.service.RetrievalService;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.TextToMarkdownService;
import com.example.ragagent.service.ThreadMetaService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PLAN §6.29 6단계 — 호출 지점마다 생각 <b>끔 / 켬(낮게)</b> 을 같은 입력으로 번갈아 부르고, 지연·출력 토큰·생각 토큰·잘림과 결과
 * 품질을 잰다. 기본값({@code ThinkingSite.shippedDefault})과 상수({@code expectedOutputTokens}, {@code ThinkingBudget.headroom})
 * 를 <b>추측이 아니라 관측으로</b> 정하기 위한 하네스다. 2026-10-07 의 측정 결과·환경·표·재현 방법은
 * {@code documents/THINKING_EVALUATION.md} 에 있다.
 *
 * <p><b>§10.7.5 검색 품질 하네스({@link SearchQualityEvaluationTest})와 같은 방식으로 게이팅된다</b> — 기본적으로 skip 이고
 * {@code -Dthinking-eval.enabled=true} 로만 돈다. 실제 LLM·임베딩 서버({@code .env})와 실제 색인된 코퍼스를 쓴다. 다만 이 하네스는
 * 검색 하네스와 달리 <b>쓴다</b>: 설정 오버라이드(수준을 바꾼다)·대화 턴·제목·요약이 DB 에 남는다. 그래서
 * {@code -Dthinking-eval.data-dir=<data/ 의 스크래치 복사본>} 이 <b>필수</b>다 — 없으면 시작하지 않는다(개발 DB 를 더럽히지 않기
 * 위해서다). 최소한 {@code memory.db} 한 파일을 복사해 두면 된다.
 *
 * <pre>
 * mvn test -Dtest=ThinkingLevelEvaluationTest -Dthinking-eval.enabled=true \
 *     -Dthinking-eval.data-dir=C:/scratch/eval-data \
 *     [-Dthinking-eval.sections=classify,condense,retrieval,rerank,answer,answerS,answerC,evalacc,post,title,curated,direct,directS,keyword,md,txt] \
 *     [-Dthinking-eval.n=26] [-Dthinking-eval.stride=2] [-Dthinking-eval.answer-combos=2] \
 *     [-Dthinking-eval.rerank=true] [-Dthinking-eval.out=target/thinking-eval]
 * </pre>
 *
 * <p><b>절마다 드는 시간과 옵션</b>(llama.cpp + gemma-4-E2B, 2026-10-07 — 낮게가 생각을 켜 두 배 안팎이다): 한 줄짜리 호출 절
 * (classify·condense·retrieval·title)은 합쳐 30~40분, {@code answer} 는 사례 하나가 2~3분이라 {@code stride=2}(0·2·4… 번째
 * 13건)로 줄여 돌리고 {@code answer}·{@code evalacc}·{@code post}·{@code curated} 는 <b>같은 JVM 에서 같이</b> 돌린다 —
 * 끔 답변({@code OFF_ANSWERS})을 한 번만 만들어 나눠 쓴다(약 70분). {@code answer-combos=3} 은 검증만 켠 짝을 더한다(검증은
 * {@code evalacc} 가 더 싸게 잰다). {@code rerank} 는 리랭커가 구조적 빈이라 {@code -Dthinking-eval.rerank=true} 로 띄운 컨텍스트에서만
 * 돌고 아니면 skip 한다. <b>서버 하나를 직렬로 쓰므로 절을 병렬로 돌리지 않는다</b>(지연이 서로를 밀어 측정이 흐려진다).
 *
 * <p><b>측정은 스크래치 DB 의 설정 오버라이드를 그대로 쓴다.</b> 운영자가 {@code llm.temperature}·{@code llm.direct-temperature} 등을
 * 고쳐 둔 DB 를 복사해 왔다면 그 값으로 잰다 — 출하값(기본 온도)으로 재려면 복사본의 {@code settings_override} 에서 그 행을
 * 지운다. 샘플링 온도는 형식 준수(예: Direct 의 {@code ## 요약} 첫머리)에 크게 영향을 준다.
 *
 * <p><b>결과는 JSONL</b>({@code <out>/<section>.jsonl}, 한 줄이 호출 한 번)이다. {@code python scripts/thinking_eval_summary.py
 * <out>} 이 호출 지점별 끔 ↔ 낮게 표로 요약한다. 호출 한 번의 지연은 서비스 호출 전체의
 * 벽시계이고, 토큰은 {@link ThinkingObservations} 의 표본(서버가 센 {@code completion_tokens})이다. 생각 토큰은 같은 입력의
 * 끔 호출 대비 초과분으로 읽는다(블로킹 응답에서는 Spring AI 가 생각 본문을 버려 직접 셀 수 없고, 관측의 자동 판정은 출력이
 * 길면 생각을 놓친다 — 긴 출력의 절은 두 수준의 출력 토큰 차이를 본다). 같은 입력을 번갈아 부르되
 * 사례마다 순서를 돌려 앞선 호출이 남기는 캐시·열(熱) 효과가 한쪽으로 쏠리지 않게 한다.
 *
 * <p>llama.cpp 는 켬/끔만 받으므로 "켬" 은 {@code LOW} 로 잰다(중간·높게는 같은 값으로 나간다 — §6.29 ③).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "file:.env", encoding = "UTF-8")
@EnabledIfSystemProperty(named = "thinking-eval.enabled", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ResourceLock("global-state")
class ThinkingLevelEvaluationTest {

    private static final String GOLD_RESOURCE = "search-eval/nexcore-gold.json";
    private static final String USER = "thinking-eval";
    private static final String VERSION = "latest";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TURN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 쓰는 하네스라 개발 DB 를 가리키지 못하게 한다 — 스크래치 데이터 디렉터리가 필수다. */
    @DynamicPropertySource
    static void isolatedData(DynamicPropertyRegistry registry) {
        String dir = System.getProperty("thinking-eval.data-dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("-Dthinking-eval.data-dir=<data/ 의 스크래치 복사본> 이 필요하다: "
                    + "이 하네스는 설정 오버라이드·대화 턴·제목을 DB 에 쓴다. 개발 DB(./data)를 직접 쓰지 않는다.");
        }
        registry.add("DATA_DIR", () -> dir);
        registry.add("app.data-dir", () -> dir);
        // 리랭커는 빈 생성 시점에 결정되는 opt-in 이라 "rerank" 절은 이 스위치를 켠 컨텍스트에서만 의미가 있다.
        if (Boolean.getBoolean("thinking-eval.rerank")) registry.add("app.search-rerank-enabled", () -> "true");
    }

    @Autowired AppProperties props;
    @Autowired SettingsService settings;
    @Autowired ThinkingObservations observations;
    @Autowired ClassifierService classifier;
    @Autowired QuestionCondenser condenser;
    @Autowired MemoryService memory;
    @Autowired RetrievalService retrieval;
    @Autowired AnswerService answers;
    @Autowired DirectAnswerService direct;
    @Autowired PostAnswerService postAnswer;
    @Autowired ThreadMetaService threadMeta;
    @Autowired CuratedQuestionSuggester curated;
    @Autowired KeywordExtractor keywords;
    @Autowired MarkdownCorrectionService mdCorrection;
    @Autowired TextToMarkdownService txtToMd;
    @Autowired ChunkSplitter chunkSplitter;

    private final Path out = Path.of(System.getProperty("thinking-eval.out", "target/thinking-eval"));
    private final int limit = Integer.getInteger("thinking-eval.n", Integer.MAX_VALUE);
    private final Set<String> sections = Arrays.stream(System.getProperty("thinking-eval.sections", "all").split(","))
            .map(String::strip).collect(Collectors.toSet());

    /** 세션 안에서 공유하는 것 — 앞 절이 만든 걸 뒤 절이 재사용해, 절 하나만 돌려도 필요한 재료를 스스로 만든다. */
    private static final Map<String, AgentState> FROZEN = new HashMap<>();
    private static final Map<String, String> OFF_ANSWERS = new HashMap<>();

    private boolean on(String section) {
        return sections.contains("all") || sections.contains(section);
    }

    @AfterEach
    void restoreSettings() {
        for (ThinkingSite site : ThinkingSite.values()) settings.reset(site.settingsKey());
        settings.reset(SettingsKeys.SEARCH_MULTIQUERY_ENABLED);
    }

    // ── 공통 도구 ────────────────────────────────────────────────────────────────────────────────

    private void use(ThinkingSite site, ThinkingLevel level) {
        settings.update(site.settingsKey(), level.value());
    }

    @SuppressWarnings("unchecked")
    private void clearObservations() {
        ((Map<Object, Object>) ReflectionTestUtils.getField(observations, "samples")).clear();
    }

    private List<ThinkingObservations.Sample> samplesOf(ThinkingSite site) {
        List<ThinkingObservations.Sample> all = new ArrayList<>();
        observations.snapshot().forEach((key, list) -> {
            if (key.site() == site) all.addAll(list);
        });
        return all;
    }

    private record Timed<T>(T value, long ms, List<ThinkingObservations.Sample> samples) {}

    private <T> Timed<T> timed(ThinkingSite site, Supplier<T> call) {
        clearObservations();
        long t0 = System.nanoTime();
        T value = call.get();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Timed<>(value, ms, samplesOf(site));
    }

    /** 표본들을 한 줄의 숫자로 — 서버가 센 출력 토큰·생각 흔적·잘림·호출 수. */
    private static Map<String, Object> sampleFields(List<ThinkingObservations.Sample> samples) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", samples.size());
        m.put("outTok", samples.stream().map(ThinkingObservations.Sample::outputTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        m.put("thinkTokEst", samples.stream().map(ThinkingObservations.Sample::thinkingTokens)
                .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum());
        m.put("thinkSeen", samples.stream().filter(ThinkingObservations.Sample::thinkingObserved).count());
        m.put("trunc", samples.stream().filter(ThinkingObservations.Sample::truncated).count());
        m.put("llmMs", samples.stream().mapToLong(ThinkingObservations.Sample::latencyMs).sum());
        m.put("sent", samples.isEmpty() ? null : samples.get(0).sent().name());
        return m;
    }

    private void emit(String section, Map<String, Object> row) throws IOException {
        Files.createDirectories(out);
        Path file = out.resolve(section + ".jsonl");
        Files.writeString(file, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Map<String, Object> row(String section, String caseId, ThinkingSite site, ThinkingLevel level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", section);
        m.put("case", caseId);
        m.put("site", site.id());
        m.put("level", level.value());
        return m;
    }

    /** 사례마다 순서를 돌린다 — 앞선 호출의 캐시·열 효과가 한 수준 쪽으로 쏠리지 않게. */
    private static List<ThinkingLevel> order(int index, ThinkingLevel... levels) {
        List<ThinkingLevel> list = new ArrayList<>(Arrays.asList(levels));
        java.util.Collections.rotate(list, index % list.size());
        return list;
    }

    private List<GoldQuery> goldCases() throws IOException {
        try (var in = new ClassPathResource(GOLD_RESOURCE).getInputStream()) {
            GoldQuery.GoldSet set = JSON.readValue(in, GoldQuery.GoldSet.class);
            return set.cases().stream().limit(limit).toList();
        }
    }

    private static boolean relevant(Document doc, List<String> markers) {
        String text = doc.getText();
        return text != null && markers.stream().anyMatch(text::contains);
    }

    private static boolean keyHit(String answer, List<String> markers) {
        if (answer == null) return false;
        String flat = answer.replaceAll("\\s+", "");
        return markers.stream().anyMatch(m -> flat.contains(m.replaceAll("\\s+", "")));
    }

    /** 사례의 검색 결과를 한 번만 얻어 둔다 — 답변·검증·후처리 절이 같은 문서를 본다(질의 확장은 끔으로 고정). */
    private AgentState frozen(GoldQuery gq) {
        return FROZEN.computeIfAbsent(gq.id(), id -> {
            use(ThinkingSite.QUERY_EXPANSION, ThinkingLevel.OFF);
            AgentState state = AgentState.of(gq.question(), VERSION, "te-frozen-" + id, "", RoutingMode.COST_FIRST);
            return retrieval.execute(state);
        });
    }

    private AgentState answerState(GoldQuery gq, String thread) {
        AgentState base = frozen(gq);
        return AgentState.of(gq.question(), VERSION, thread, "", RoutingMode.COST_FIRST).toBuilder()
                .retrievedDocs(base.retrievedDocs())
                .sources(base.sources())
                .retrievalWarnings(base.retrievalWarnings())
                .questionType("concept")
                .responseMode(ResponseMode.N)
                .build();
    }

    /** 스트리밍 답변 한 번의 타임라인 — 첫 토큰까지·전체·생각 델타 수. */
    private static final class Timeline implements GraphListener {
        final long t0 = System.nanoTime();
        long answerEnterMs = -1;
        long firstTokenMs = -1;
        long verifyingMs = -1;
        int tokens;
        int thinking;

        private long now() {
            return (System.nanoTime() - t0) / 1_000_000;
        }

        @Override public void onNodeEnter(String node) {
            if ("answer".equals(node) && answerEnterMs < 0) answerEnterMs = now();
        }

        @Override public void onToken(String text) {
            if (firstTokenMs < 0) firstTokenMs = now();
            tokens++;
        }

        @Override public void onThinking() {
            thinking++;
        }

        @Override public void onVerifying() {
            verifyingMs = now();
        }
    }

    private void putTimeline(Map<String, Object> m, Timeline t, long totalMs) {
        m.put("totalMs", totalMs);
        m.put("firstTokenMs", t.firstTokenMs);
        m.put("verifyingMs", t.verifyingMs);
        m.put("tokenDeltas", t.tokens);
        m.put("thinkDeltas", t.thinking);
    }

    // ═══ 1. 질문 분류 ═══════════════════════════════════════════════════════════════════════════

    private static final List<String> CLASSIFY_EXTRA = List.of(
            "안녕하세요", "너는 뭘 할 수 있어?", "이 서비스는 어떻게 사용하는 거야?", "고마워요 도움이 됐어요",
            "배치 작업이 새벽에 갑자기 멈췄는데 어디부터 확인해야 해?", "DU 와 FU 중 어느 쪽에 SQL 을 둬야 하나요?",
            "트랜잭션 로그 설정을 바꾸는 절차를 단계별로 알려줘", "지난번 질문 이어서 설명해줘");

    @Test
    @Order(10)
    void classify() throws IOException {
        if (!on("classify")) return;
        List<String> questions = new ArrayList<>(goldCases().stream().map(GoldQuery::question).toList());
        questions.addAll(CLASSIFY_EXTRA);
        for (int i = 0; i < questions.size(); i++) {
            String q = questions.get(i);
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.CLASSIFY, level);
                Timed<String> t = timed(ThinkingSite.CLASSIFY, () -> classifier.classifyOnly(q, Locale.KOREAN));
                Map<String, Object> r = row("classify", "q" + i, ThinkingSite.CLASSIFY, level);
                r.put("question", q);
                r.put("type", t.value());
                r.put("ms", t.ms());
                r.putAll(sampleFields(t.samples()));
                emit("classify", r);
            }
        }
    }

    // ═══ 2. 질문 독립화(condense) ════════════════════════════════════════════════════════════════

    /** 이전 질문 · 짧은 후속 질문 · 재작성된 질문에 있어야 할 말(하나라도). 이 후속 질문들은 앞 질문 없이는 검색할 수 없다. */
    private record FollowUp(String previous, String followUp, List<String> mustMention) {}

    private static final List<FollowUp> FOLLOW_UPS = List.of(
            new FollowUp("IBatchContext 객체는 무엇을 전달하기 위한 것인가?", "그걸로 처리일자는 어떻게 가져와?", List.of("IBatchContext", "배치")),
            new FollowUp("업무 애플리케이션에서 예외가 발생하면 어떤 예외 클래스로 던져야 하는가?", "그 안에 뭘 넣어야 해?", List.of("KISBException", "예외")),
            new FollowUp("NOTX로 실행된 SQL은 본 거래 트랜잭션이 롤백되어도 영향을 받는가?", "그럼 커밋은 언제 돼?", List.of("NOTX")),
            new FollowUp("CommonArea는 어떤 용도의 영역인가?", "도메인별 영역은 어떤 타입이야?", List.of("CommonArea")),
            new FollowUp("PU, FU, DU는 각각 어떤 역할을 담당하는가?", "DU는 뭘 하는 거야?", List.of("DU", "DataUnit")),
            new FollowUp("배치 프로그램 클래스는 어떤 클래스를 상속받아 작성하나?", "그 클래스의 beforeExecute 는?", List.of("AbsBatchComponent", "배치", "beforeExecute")),
            new FollowUp("로컬 환경에서 onlinelog.level의 기본값은 무엇인가?", "운영에서는?", List.of("onlinelog", "log level", "로그")),
            new FollowUp("EIMS에서 배포받은 UIO는 수동으로 편집할 수 있는가?", "숫자는 어떻게 표현돼?", List.of("EIMS", "UIO")),
            new FollowUp("NEXCORE 메타 로더는 어떤 역할을 하는가?", "노드매니저는?", List.of("노드매니저", "노드 매니저")),
            new FollowUp("DM(Data Method) 간의 호출이 가능한가?", "FM 은?", List.of("FM", "DM")));

    @Test
    @Order(20)
    void condense() throws IOException {
        if (!on("condense")) return;
        List<FollowUp> cases = FOLLOW_UPS.stream().limit(limit).toList();
        for (int i = 0; i < cases.size(); i++) {
            FollowUp fu = cases.get(i);
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                String thread = "te-cond-" + i + "-" + level.value();
                memory.addTurn(USER, thread, fu.previous(), "답변", LocalDateTime.now().format(TURN_TIME),
                        10, 10, 100, "local", 1, "N", "");
                use(ThinkingSite.CONDENSE, level);
                Timed<Optional<QuestionCondenser.Condensed>> t = timed(ThinkingSite.CONDENSE,
                        () -> condenser.condense(USER, thread, fu.followUp(), Locale.KOREAN));
                Map<String, Object> r = row("condense", "f" + i, ThinkingSite.CONDENSE, level);
                r.put("previous", fu.previous());
                r.put("followUp", fu.followUp());
                String rewritten = t.value().map(QuestionCondenser.Condensed::searchQuestion).orElse(null);
                r.put("rewritten", rewritten);
                r.put("rewrote", rewritten != null);
                r.put("mentionsKey", rewritten != null && fu.mustMention().stream().anyMatch(rewritten::contains));
                r.put("ms", t.ms());
                r.putAll(sampleFields(t.samples()));
                emit("condense", r);
            }
        }
    }

    // ═══ 3. 검색(질의 확장) ══════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    void retrievalWithExpansion() throws IOException {
        if (!on("retrieval")) return;
        List<GoldQuery> cases = goldCases();
        // 설정 A: 확장 없음 · B: 확장(끔) · C: 확장(낮게). 확장 호출이 실제로 쓸모 있는지와 생각이 보탬이 되는지를 함께 본다.
        record Config(String name, boolean multiquery, ThinkingLevel level) {}
        List<Config> configs = List.of(new Config("noExpansion", false, ThinkingLevel.OFF),
                new Config("expansionOff", true, ThinkingLevel.OFF), new Config("expansionLow", true, ThinkingLevel.LOW));
        // 확장 결과를 읽지 못하면 Spring AI 가 WARN 한 줄을 남기고 원문 질의로 돌아간다 — 그 실패율이 켬의 비용일 수 있다.
        Logger expanderLog = LogbackTestSupport.logger("org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander");
        ListAppender<ILoggingEvent> expanderEvents = new ListAppender<>();
        expanderEvents.start();
        expanderLog.addAppender(expanderEvents);
        try {
        for (int i = 0; i < cases.size(); i++) {
            GoldQuery gq = cases.get(i);
            List<Config> rotated = new ArrayList<>(configs);
            java.util.Collections.rotate(rotated, i % rotated.size());
            for (Config c : rotated) {
                settings.update(SettingsKeys.SEARCH_MULTIQUERY_ENABLED, Boolean.toString(c.multiquery()));
                use(ThinkingSite.QUERY_EXPANSION, c.level());
                AgentState state = AgentState.of(gq.question(), VERSION, "te-ret-" + i, "", RoutingMode.COST_FIRST);
                expanderEvents.list.clear();
                Timed<AgentState> t = timed(ThinkingSite.QUERY_EXPANSION, () -> retrieval.execute(state));
                boolean expansionFailed = expanderEvents.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("does not contain the requested"));
                List<Boolean> relevance = t.value().retrievedDocs().stream().map(d -> relevant(d, gq.mustContainAny())).toList();
                Map<String, Object> r = row("retrieval", gq.id(), ThinkingSite.QUERY_EXPANSION, c.level());
                r.put("config", c.name());
                r.put("question", gq.question());
                r.put("recall10", SearchQualityMetrics.recallAtK(relevance, 10));
                r.put("ndcg10", SearchQualityMetrics.ndcgAtK(relevance, 10));
                r.put("rank", SearchQualityMetrics.firstRelevantRank(relevance, 10));
                r.put("docs", relevance.size());
                r.put("expansionFailed", expansionFailed);
                r.put("ms", t.ms());
                r.putAll(sampleFields(t.samples()));
                emit("retrieval", r);
            }
        }
        } finally {
            expanderLog.detachAppender(expanderEvents);
        }
    }

    /**
     * 리랭크(opt-in) — {@code -Dthinking-eval.rerank=true -Dthinking-eval.sections=rerank} 로 돌린다. 질의 확장은 끔으로 고정하고
     * 리랭크의 끔/낮게만 바꾼다. "리랭크 없음" 기준선은 retrieval 절의 expansionOff 설정과 같은 조건이다(그쪽은 리랭커 빈이 없는 컨텍스트).
     */
    @Test
    @Order(35)
    void rerank() throws IOException {
        if (!on("rerank")) return;
        org.junit.jupiter.api.Assumptions.assumeTrue(props.searchRerankEnabled(),
                "-Dthinking-eval.rerank=true 로 리랭커가 켜진 컨텍스트가 필요하다");
        List<GoldQuery> cases = goldCases();
        use(ThinkingSite.QUERY_EXPANSION, ThinkingLevel.OFF);
        for (int i = 0; i < cases.size(); i++) {
            GoldQuery gq = cases.get(i);
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.RERANK, level);
                AgentState state = AgentState.of(gq.question(), VERSION, "te-rr-" + i, "", RoutingMode.COST_FIRST);
                Timed<AgentState> t = timed(ThinkingSite.RERANK, () -> retrieval.execute(state));
                List<Boolean> relevance = t.value().retrievedDocs().stream().map(d -> relevant(d, gq.mustContainAny())).toList();
                Map<String, Object> r = row("rerank", gq.id(), ThinkingSite.RERANK, level);
                r.put("question", gq.question());
                r.put("recall10", SearchQualityMetrics.recallAtK(relevance, 10));
                r.put("ndcg10", SearchQualityMetrics.ndcgAtK(relevance, 10));
                r.put("rank", SearchQualityMetrics.firstRelevantRank(relevance, 10));
                r.put("docs", relevance.size());
                r.put("ms", t.ms());
                r.putAll(sampleFields(t.samples()));
                emit("rerank", r);
            }
        }
    }

    // ═══ 4. 답변(스트리밍) + 검증 ════════════════════════════════════════════════════════════════

    private record Combo(ThinkingLevel answer, ThinkingLevel eval) {
        String name() {
            return "a" + answer.value() + "-e" + eval.value();
        }
    }

    private void runAnswer(String section, GoldQuery gq, int index, ResponseMode mode, Combo combo) throws IOException {
        ThinkingSite answerSite = mode.ragThinkingSite();
        use(answerSite, combo.answer());
        use(mode.evalThinkingSite(), combo.eval());
        AgentState state = answerState(gq, "te-ans-" + gq.id() + "-" + combo.name()).toBuilder().responseMode(mode).build();
        Timeline timeline = new Timeline();
        clearObservations();
        long t0 = System.nanoTime();
        AgentState result = answers.executeStreaming(state, timeline);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        List<ThinkingObservations.Sample> answerSamples = samplesOf(answerSite);
        List<ThinkingObservations.Sample> evalSamples = samplesOf(mode.evalThinkingSite());

        Map<String, Object> r = row(section, gq.id(), answerSite, combo.answer());
        r.put("combo", combo.name());
        r.put("mode", mode.name());
        r.put("evalLevel", combo.eval().value());
        r.put("question", gq.question());
        putTimeline(r, timeline, totalMs);
        r.put("docs", state.retrievedDocs().size());
        r.put("answer", result.answer());
        r.put("answerChars", result.answer() == null ? 0 : result.answer().length());
        r.put("keyHit", keyHit(result.answer(), gq.mustContainAny()));
        r.put("grounded", result.grounded());           // null = 판정 없음
        r.put("needsRetry", result.needsRetry());
        r.put("evalReason", result.evalReason());
        r.put("budgetNote", result.budgetNote());
        r.put("usedDocs", result.usedDocIndices());
        r.put("llmCalls", result.llmCallCount());
        Map<String, Object> a = sampleFields(answerSamples);
        a.forEach((k, v) -> r.put("ans_" + k, v));
        Map<String, Object> e = sampleFields(evalSamples);
        e.forEach((k, v) -> r.put("eval_" + k, v));
        emit(section, r);
        if (combo.answer() == ThinkingLevel.OFF && mode == ResponseMode.N && result.answer() != null) {
            OFF_ANSWERS.putIfAbsent(gq.id(), result.answer());
        }
    }

    @Test
    @Order(40)
    void answerAndEval() throws IOException {
        if (!on("answer")) return;
        List<GoldQuery> cases = goldCases();
        // 세 번째 짝((끔, 낮게) — 검증만 켠 흐름)은 evalAccuracy 절이 같은 입력으로 더 싸게 재므로 기본 2개만 돌리고,
        // -Dthinking-eval.answer-combos=3 일 때만 더한다(한 사례에 검증만 1~2분이 더 든다).
        List<Combo> combos = new ArrayList<>(List.of(new Combo(ThinkingLevel.OFF, ThinkingLevel.OFF),
                new Combo(ThinkingLevel.LOW, ThinkingLevel.OFF)));
        if (Integer.getInteger("thinking-eval.answer-combos", 2) >= 3) {
            combos.add(new Combo(ThinkingLevel.OFF, ThinkingLevel.LOW));
        }
        List<GoldQuery> picked = strided(cases, Integer.MAX_VALUE);
        for (int k = 0; k < picked.size(); k++) {
            List<Combo> rotated = new ArrayList<>(combos);
            java.util.Collections.rotate(rotated, k % rotated.size());
            for (Combo combo : rotated) runAnswer("answer", picked.get(k), k, ResponseMode.N, combo);
        }
    }

    /**
     * 무거운 절(답변·검증 정확도·답변 뒤 보강·큐레이션 제안)이 쓰는 사례 고르기 — 사례 하나가 1~3분이라
     * {@code -Dthinking-eval.stride=2} 면 0, 2, 4… 번째를 {@code cap} 개까지 고른다(범주가 고루 남는다). 절마다 같은
     * 번호를 고르므로 답변 캐시({@code OFF_ANSWERS})를 함께 써서 같은 JVM 안에서 답변을 한 번만 만든다. 수준 순서 회전은
     * 고른 순번 기준이다.
     */
    private static List<GoldQuery> strided(List<GoldQuery> all, int cap) {
        int stride = Math.max(1, Integer.getInteger("thinking-eval.stride", 1));
        List<GoldQuery> picked = new ArrayList<>();
        for (int i = 0; i < all.size() && picked.size() < cap; i += stride) picked.add(all.get(i));
        return picked;
    }

    /** 요약(S) 모드 — 답이 짧아 생각의 비중이 다르다. 사례 수를 줄여 끔/낮게만 본다. */
    @Test
    @Order(41)
    void answerSummaryMode() throws IOException {
        if (!on("answerS")) return;
        List<GoldQuery> cases = goldCases().stream().limit(Math.min(limit, 10)).toList();
        for (int i = 0; i < cases.size(); i++) {
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                runAnswer("answerS", cases.get(i), i, ResponseMode.S, new Combo(level, ThinkingLevel.OFF));
            }
        }
    }

    /** 응용(C) 모드 — 문서를 재료로 코드·산출물을 만든다. 검증은 EVAL_CREATIVE(발명한 이름이 있는가)다. 사례가 적어 끔/낮게의 경향만 본다. */
    private static final List<String> CREATIVE_PROMPTS = List.of(
            "AbsBatchComponent 를 상속하는 배치 프로그램의 기본 골격 코드를 작성해줘",
            "KISBException 을 던지는 온라인 서비스 메소드 예제를 만들어줘",
            "IDataSet 을 입력과 출력으로 쓰는 온라인 서비스 메소드 예제 코드를 작성해줘",
            "IBatchContext 에서 처리일자를 읽어 로그로 남기는 배치 예제를 작성해줘");

    @Test
    @Order(42)
    void answerCreativeMode() throws IOException {
        if (!on("answerC")) return;
        List<String> prompts = CREATIVE_PROMPTS.stream().limit(limit).toList();
        for (int i = 0; i < prompts.size(); i++) {
            GoldQuery gq = new GoldQuery("creative-" + (i + 1), prompts.get(i), List.of(), "");
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                runAnswer("answerC", gq, i, ResponseMode.C, new Combo(level, level));
            }
        }
    }

    // ═══ 5. 검증 판정의 정확도(라벨이 있는 짝) ═══════════════════════════════════════════════════

    private String offAnswer(GoldQuery gq) {
        return OFF_ANSWERS.computeIfAbsent(gq.id(), id -> {
            use(ThinkingSite.ANSWER_RAG_N, ThinkingLevel.OFF);
            use(ThinkingSite.EVAL, ThinkingLevel.OFF);
            AgentState result = answers.executeStreaming(answerState(gq, "te-off-" + id), new GraphListener() {});
            return result.answer();
        });
    }

    /**
     * 검증 호출만 따로 — <b>양성</b> 짝(답변과 그 답변을 만든 문서)과 <b>음성</b> 짝(같은 답변과 <b>다른 주제의 문서</b>)을 끔/낮게로
     * 판정시킨다. 음성은 문서가 답변을 받쳐 주지 못하므로 {@code grounded=false} 여야 한다. 양성의 정답은 완전히 확실하지는
     * 않다(소형 모델의 답변이 틀릴 수 있다) — 그래서 절대 정확도가 아니라 <b>끔과 낮게의 차이</b>를 읽는다.
     */
    private static final Pattern BACKTICK_IDENTIFIER = Pattern.compile("`([A-Za-z][A-Za-z0-9]*)`");

    /**
     * 답변 속 백틱 식별자(두 단어 이상의 카멜케이스)의 단어 순서를 뒤집어 <b>문서에 없는 이름을 단 답변</b>을 만든다 —
     * 검증이 실제로 잡아야 하는 오류(지어낸 클래스·메소드 이름)이고 정답이 구성상 확실하다({@code AbsBatchComponent} →
     * {@code ComponentBatchAbs}). 바꿀 식별자가 없으면 {@code null}.
     */
    static String mutateIdentifiers(String answer) {
        Matcher m = BACKTICK_IDENTIFIER.matcher(answer);
        StringBuilder sb = new StringBuilder();
        int changed = 0;
        while (m.find()) {
            String id = m.group(1);
            String[] words = id.split("(?<=[a-z0-9])(?=[A-Z])");
            String replacement = id;
            if (words.length >= 2) {
                List<String> reversed = new ArrayList<>(Arrays.asList(words));
                java.util.Collections.reverse(reversed);
                replacement = String.join("", reversed);
                changed++;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement("`" + replacement + "`"));
        }
        m.appendTail(sb);
        return changed == 0 ? null : sb.toString();
    }

    @Test
    @Order(50)
    void evalAccuracy() throws IOException {
        if (!on("evalacc")) return;
        List<GoldQuery> cases = goldCases();
        List<GoldQuery> picked = strided(cases, Integer.MAX_VALUE);
        for (int k = 0; k < picked.size(); k++) {
            GoldQuery gq = picked.get(k);
            int i = cases.indexOf(gq);
            GoldQuery other = cases.get((i + cases.size() / 2) % cases.size());
            if (other.id().split("-")[0].equals(gq.id().split("-")[0])) other = cases.get((i + cases.size() / 3 + 1) % cases.size());
            String answer = offAnswer(gq);
            if (answer == null || answer.isBlank()) continue;
            Map<String, AgentState> pairs = new LinkedHashMap<>();
            pairs.put("positive", answerState(gq, "te-ev-p-" + gq.id()).toBuilder().answer(answer).build());
            pairs.put("negative", answerState(gq, "te-ev-n-" + gq.id()).toBuilder()
                    .answer(answer).retrievedDocs(frozen(other).retrievedDocs()).build());
            String mutated = mutateIdentifiers(answer);
            if (mutated != null) {
                pairs.put("mutated", answerState(gq, "te-ev-m-" + gq.id()).toBuilder().answer(mutated).build());
            }
            for (var entry : pairs.entrySet()) {
                for (ThinkingLevel level : order(k, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                    use(ThinkingSite.EVAL, level);
                    AgentState state = entry.getValue();
                    Timed<AgentState> t = timed(ThinkingSite.EVAL,
                            () -> ReflectionTestUtils.invokeMethod(answers, "evaluate", state, state.answer(), Locale.KOREAN));
                    AgentState verdict = t.value();
                    Map<String, Object> r = row("evalacc", gq.id(), ThinkingSite.EVAL, level);
                    r.put("pair", entry.getKey());
                    r.put("answerHead", state.answer().substring(0, Math.min(160, state.answer().length())));
                    r.put("grounded", verdict == null ? null : verdict.grounded());
                    r.put("needsRetry", verdict != null && verdict.needsRetry());
                    r.put("evalReason", verdict == null ? null : verdict.evalReason());
                    r.put("ms", t.ms());
                    r.putAll(sampleFields(t.samples()));
                    emit("evalacc", r);
                }
            }
        }
    }

    // ═══ 6. 답변 뒤 보강 · 제목 · 큐레이션 제안 ═════════════════════════════════════════════════

    @Test
    @Order(60)
    void postAnswer() throws Exception {
        if (!on("post")) return;
        List<GoldQuery> cases = strided(goldCases(), Math.min(limit, 12));
        for (int i = 0; i < cases.size(); i++) {
            GoldQuery gq = cases.get(i);
            String answer = offAnswer(gq);
            if (answer == null || answer.isBlank()) continue;
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                String thread = "te-post-" + gq.id() + "-" + level.value();
                long turnId = memory.addTurn(USER, thread, gq.question(), answer, LocalDateTime.now().format(TURN_TIME),
                        10, 10, 100, "local", 1, "N", "");
                AgentState result = answerState(gq, thread).toBuilder().answer(answer).build();
                use(ThinkingSite.POST_ANSWER, level);
                clearObservations();
                long t0 = System.nanoTime();
                postAnswer.afterTurn(turnId, USER, thread, gq.question(), false, Locale.KOREAN, result);
                // 화면의 기다림은 한 번에 최대 MAX_EXTRAS_WAIT(25초, 기본 요청은 20초)다. 여기서는 실제 소요를 재려고 그 한도를
                // 넘겨 다시 묻는다 — 한 번만 물으면 25초를 넘기는 수준이 "실패"로 보인다. 화면 기준의 성공은 withinUiWait 로 따로 센다.
                Optional<PostAnswerService.Extras> extras = Optional.empty();
                while (extras.isEmpty() && System.nanoTime() - t0 < Duration.ofSeconds(180).toNanos()) {
                    long asked = System.nanoTime();
                    extras = postAnswer.awaitExtras(turnId, USER, thread, PostAnswerService.MAX_EXTRAS_WAIT);
                    // 등록된 기다림이 없으면 바로 빈 값이 돌아온다 — 제자리 회전을 막는다.
                    if (extras.isEmpty() && System.nanoTime() - asked < Duration.ofSeconds(1).toNanos()) Thread.sleep(200);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Map<String, Object> r = row("post", gq.id(), ThinkingSite.POST_ANSWER, level);
                r.put("question", gq.question());
                r.put("withinUiWait", ms <= 20_000);
                r.put("ok", extras.isPresent() && !extras.get().isEmpty());
                r.put("clarified", extras.map(PostAnswerService.Extras::clarifiedQuestion).orElse(null));
                r.put("followUps", extras.map(PostAnswerService.Extras::followUps).orElse(List.of()));
                r.put("ms", ms);
                r.putAll(sampleFields(samplesOf(ThinkingSite.POST_ANSWER)));
                emit("post", r);
            }
        }
    }

    @Test
    @Order(61)
    void title() throws Exception {
        if (!on("title")) return;
        List<GoldQuery> cases = goldCases().stream().limit(Math.min(limit, 12)).toList();
        for (int i = 0; i < cases.size(); i++) {
            GoldQuery gq = cases.get(i);
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                String thread = "te-title-" + gq.id() + "-" + level.value();
                threadMeta.getOrCreate(USER, thread, VERSION);
                use(ThinkingSite.TITLE, level);
                clearObservations();
                long t0 = System.nanoTime();
                threadMeta.generateTitleAsync(USER, thread, VERSION, gq.question());
                String title = null;
                String defaultTitle = "[%s] 새 대화".formatted(VERSION);
                while (System.nanoTime() - t0 < Duration.ofSeconds(120).toNanos()) {
                    title = threadMeta.findById(USER, thread).map(m -> m.title()).orElse(null);
                    if (title != null && !title.equals(defaultTitle)) break;
                    Thread.sleep(150);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Map<String, Object> r = row("title", gq.id(), ThinkingSite.TITLE, level);
                r.put("question", gq.question());
                r.put("title", title);
                r.put("ok", title != null && !title.equals(defaultTitle));
                r.put("ms", ms);
                r.putAll(sampleFields(samplesOf(ThinkingSite.TITLE)));
                emit("title", r);
            }
        }
    }

    @Test
    @Order(62)
    void curatedSuggest() throws IOException {
        if (!on("curated")) return;
        List<GoldQuery> cases = strided(goldCases(), Math.min(limit, 12));
        for (int i = 0; i < cases.size(); i++) {
            GoldQuery gq = cases.get(i);
            String answer = offAnswer(gq);
            if (answer == null || answer.isBlank()) continue;
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.CURATED_SUGGEST, level);
                Timed<Optional<String>> t = timed(ThinkingSite.CURATED_SUGGEST,
                        () -> curated.suggest(gq.question(), answer, Locale.KOREAN));
                Map<String, Object> r = row("curated", gq.id(), ThinkingSite.CURATED_SUGGEST, level);
                r.put("question", gq.question());
                r.put("suggested", t.value().orElse(null));
                r.put("ok", t.value().isPresent());
                r.put("ms", t.ms());
                r.putAll(sampleFields(t.samples()));
                emit("curated", r);
            }
        }
    }

    // ═══ 7. Direct · meta 답변(스트리밍) ═════════════════════════════════════════════════════════

    private static final List<String> DIRECT_QUESTIONS = List.of(
            "자바에서 HashMap 과 ConcurrentHashMap 의 차이를 설명해줘",
            "트랜잭션 격리 수준 네 가지를 비교해줘",
            "REST 와 gRPC 는 언제 각각 쓰는 게 좋아?",
            "SQL 인덱스가 오히려 쿼리를 느리게 만드는 경우는?",
            "스레드 안전한 싱글톤을 구현하는 방법을 알려줘",
            "정규식으로 한국 휴대폰 번호를 검증하려면?",
            "git rebase 와 merge 는 무엇이 다르고 언제 쓰나?",
            "캐시 무효화 전략에는 어떤 것들이 있어?");

    private static final List<String> META_QUESTIONS = List.of(
            "안녕하세요", "너는 뭘 할 수 있어?", "이 서비스는 어떻게 사용하는 거야?", "고마워요", "넌 누구야?", "도움말 좀 보여줘");

    @Test
    @Order(70)
    void directAndMeta() throws IOException {
        if (!on("direct")) return;
        List<String> directs = DIRECT_QUESTIONS.stream().limit(limit).toList();
        for (int i = 0; i < directs.size(); i++) {
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.ANSWER_DIRECT_N, level);
                runDirect("direct", "d" + i, directs.get(i), true, ThinkingSite.ANSWER_DIRECT_N, level);
            }
        }
        List<String> metas = META_QUESTIONS.stream().limit(limit).toList();
        for (int i = 0; i < metas.size(); i++) {
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.ANSWER_META, level);
                runDirect("meta", "m" + i, metas.get(i), false, ThinkingSite.ANSWER_META, level);
            }
        }
    }

    /** Direct 요약(S) 모드 — 답이 짧아 생각이 차지하는 비중이 N 과 다르다. 출하값을 따로 정하려고 N 과 나눠 잰다. */
    @Test
    @Order(71)
    void directSummaryMode() throws IOException {
        if (!on("directS")) return;
        List<String> directs = DIRECT_QUESTIONS.stream().limit(Math.min(limit, 6)).toList();
        for (int i = 0; i < directs.size(); i++) {
            for (ThinkingLevel level : order(i, ThinkingLevel.OFF, ThinkingLevel.LOW)) {
                use(ThinkingSite.ANSWER_DIRECT_S, level);
                runDirect("directS", "d" + i, directs.get(i), true, ThinkingSite.ANSWER_DIRECT_S, level, ResponseMode.S);
            }
        }
    }

    private void runDirect(String section, String caseId, String question, boolean directMode, ThinkingSite site,
                           ThinkingLevel level) throws IOException {
        runDirect(section, caseId, question, directMode, site, level, ResponseMode.N);
    }

    private void runDirect(String section, String caseId, String question, boolean directMode, ThinkingSite site,
                           ThinkingLevel level, ResponseMode mode) throws IOException {
        AgentState state = AgentState.of(question, VERSION, "te-" + section + "-" + caseId + "-" + level.value(), "",
                RoutingMode.COST_FIRST, directMode).toBuilder().questionType(directMode ? "concept" : "meta")
                .responseMode(mode).build();
        Timeline timeline = new Timeline();
        clearObservations();
        long t0 = System.nanoTime();
        AgentState result = direct.executeStreaming(state, timeline);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;
        Map<String, Object> r = row(section, caseId, site, level);
        r.put("question", question);
        putTimeline(r, timeline, totalMs);
        r.put("answer", result.answer());
        r.put("answerChars", result.answer() == null ? 0 : result.answer().length());
        r.putAll(sampleFields(samplesOf(site)));
        emit(section, r);
    }

    // ═══ 8. 인덱싱 — 키워드+맥락 · MD 교정 · TXT→MD ══════════════════════════════════════════════

    private static String readConverted(String fileNamePrefix) throws IOException {
        Path dir = Path.of(System.getProperty("thinking-eval.corpus", "data/converted"));
        try (var files = Files.list(dir)) {
            Path file = files.filter(p -> p.getFileName().toString().startsWith(fileNamePrefix)).sorted().findFirst()
                    .orElseThrow(() -> new IllegalStateException(dir + " 에 " + fileNamePrefix + "* 가 없다"));
            return Files.readString(file, StandardCharsets.UTF_8);
        }
    }

    @Test
    @Order(80)
    void keywordAndContext() throws IOException {
        if (!on("keyword")) return;
        String md = readConverted("com-kube-fwk-exception.md_93bbe649_corrected");
        List<Document> chunks = chunkSplitter.splitDocuments(List.of(new Document(md, new HashMap<>(Map.of(
                MetaKey.FILENAME, "com-kube-fwk-exception.md")))), "com-kube-fwk-exception.md", 1500, 0, 250);
        List<Document> sample = chunks.stream().limit(Math.min(limit, 18)).toList();
        for (ThinkingLevel level : List.of(ThinkingLevel.OFF, ThinkingLevel.LOW)) {
            use(ThinkingSite.KEYWORD_CONTEXT, level);
            clearObservations();
            long t0 = System.nanoTime();
            List<Document> enriched = keywords.enrichParallel(sample, new Semaphore(1), "com-kube-fwk-exception.md", e -> {});
            long ms = (System.nanoTime() - t0) / 1_000_000;
            List<ThinkingObservations.Sample> samples = samplesOf(ThinkingSite.KEYWORD_CONTEXT);
            for (int i = 0; i < enriched.size(); i++) {
                Document d = enriched.get(i);
                String kw = String.valueOf(d.getMetadata().getOrDefault(MetaKey.EXCERPT_KEYWORDS, ""));
                String ctx = String.valueOf(d.getMetadata().getOrDefault(MetaKey.CHUNK_CONTEXT, ""));
                String structural = KeywordExtractor.buildStructuralContext(sample.get(i));
                List<String> words = Arrays.stream(kw.split("[,，]")).map(String::strip).filter(s -> !s.isBlank()).toList();
                String text = sample.get(i).getText() == null ? "" : sample.get(i).getText().toLowerCase();
                Map<String, Object> r = row("keyword", "c" + i, ThinkingSite.KEYWORD_CONTEXT, level);
                r.put("keywords", kw);
                r.put("context", ctx);
                r.put("nKeywords", words.size());
                r.put("keywordsInText", words.stream().filter(w -> text.contains(w.toLowerCase())).count());
                r.put("llmContext", !ctx.equals(structural));          // 구조 맥락만 남았다면 LLM 결과를 못 읽고 폴백한 것
                emit("keyword", r);
            }
            Map<String, Object> total = row("keyword", "TOTAL", ThinkingSite.KEYWORD_CONTEXT, level);
            total.put("chunks", enriched.size());
            total.put("ms", ms);
            total.putAll(sampleFields(samples));
            emit("keyword", total);
        }
    }

    @Test
    @Order(81)
    void markdownCorrection() throws IOException {
        if (!on("md")) return;
        String raw = readConverted("FWK_COMFNCT_BATCH_샘플_예졔_분석.md_ca2e4d83.md");
        if (raw.length() > 9_000) raw = raw.substring(0, raw.lastIndexOf('\n', 9_000));
        for (ThinkingLevel level : List.of(ThinkingLevel.OFF, ThinkingLevel.LOW)) {
            use(ThinkingSite.MD_CORRECT, level);
            Path outFile = out.resolve("md-correct-" + level.value() + ".md");
            Files.createDirectories(out);
            String input = raw;
            Timed<String> t = timed(ThinkingSite.MD_CORRECT, () -> mdCorrection.correct(input, "te-md-" + level.value(), outFile));
            Map<String, Object> r = row("md", "doc", ThinkingSite.MD_CORRECT, level);
            r.put("inChars", input.length());
            r.put("outChars", t.value() == null ? 0 : t.value().length());
            r.put("ms", t.ms());
            r.put("identical", input.equals(t.value()));
            r.putAll(sampleFields(t.samples()));
            emit("md", r);
        }
    }

    @Test
    @Order(82)
    void textToMarkdown() throws IOException {
        if (!on("txt")) return;
        String md = readConverted("com-kube-fwk-exception.md_93bbe649_corrected");
        // 마크다운 서식을 걷어낸 평문 — 6,000자 안쪽 한 블록
        String plain = md.replaceAll("(?m)^#+\\s*", "").replaceAll("[*`|]", "").replaceAll("\\n{3,}", "\n\n");
        if (plain.length() > 5_000) plain = plain.substring(0, plain.lastIndexOf('\n', 5_000));
        for (ThinkingLevel level : List.of(ThinkingLevel.OFF, ThinkingLevel.LOW)) {
            use(ThinkingSite.TXT_TO_MD, level);
            String input = plain;
            Timed<String> t = timed(ThinkingSite.TXT_TO_MD, () -> txtToMd.convert(input, "te-txt-" + level.value()));
            Map<String, Object> r = row("txt", "doc", ThinkingSite.TXT_TO_MD, level);
            r.put("inChars", input.length());
            r.put("outChars", t.value() == null ? 0 : t.value().length());
            r.put("ms", t.ms());
            r.put("unchanged", input.equals(t.value()));
            r.putAll(sampleFields(t.samples()));
            emit("txt", r);
        }
    }
}
