package com.example.ragagent.evaluation;

import com.example.ragagent.LogbackTestSupport;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.SettingsKeys;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.model.ChatForm;
import com.example.ragagent.repository.DbTimestamp;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.StreamingAgentService;
import com.example.ragagent.service.ThreadMetaService;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PLAN §6.30 측정 — 축소 사다리(이 설계)와 예전 순서(문서 → 이력)를 <b>같은 입력</b>으로 비교한다. 결과·환경·재현 방법은
 * {@code documents/PROMPT_LADDER_EVALUATION.md}.
 *
 * <p><b>같은 하네스를 두 코드에서 돌린다.</b> 예전 순서는 HEAD 에 없으므로 사다리 직전 커밋의 worktree 에 이 파일을 복사해 돌린다 —
 * 그래서 이 파일은 두 커밋에 다 있는 API(채팅 진입점 {@code StreamingAgentService.run}, {@code MemoryService.addTurn},
 * {@code ProviderContextWindows.record} …)만 쓴다. 이력이 두 쪽에서 같아야 비교가 되므로 앞 턴의 답변은 {@code prepare} 절이 한 번
 * 만들어 파일(fixture)로 두고, 측정 절은 그것을 DB 에 직접 넣는다(파이프라인을 다시 돌리면 답변이 매번 달라진다).
 *
 * <p><b>좁은 창은 선언으로 흉내 낸다</b>({@code -Dladder-eval.window}) — 서버는 그대로(32K)이고 앱의 예산만 그 창으로 계산한다. 기동 뒤
 * {@code ProviderContextWindows} 에 {@code CONFIGURED} 로 기록하므로 재탐지가 덮지 않는다. {@code prepare} 는 창을 선언하지 않고
 * 돈다(앞 턴은 자연스러운 답변이어야 한다).
 *
 * <pre>
 * mvn -o test -Dtest=PromptLadderEvaluationTest -Dladder-eval.enabled=true \
 *     -Dladder-eval.data-dir=target/ladder-eval-data/new -Dladder-eval.label=new-16k \
 *     -Dladder-eval.window=16384 [-Dladder-eval.sections=prepare|long,fu1,fu2,fu3] [-Dladder-eval.stride=2] [-Dladder-eval.top-k=15] \
 *     [-Dladder-eval.priors=target/ladder-eval/priors.json] [-Dladder-eval.out=target/ladder-eval]
 * </pre>
 *
 * <p><b>절</b> — {@code long}: 골든셋 질문 앞에 무관한 붙여넣기를 둔 긴 독립 질문 + 다른 주제의 앞 턴 넷(PLAN 예 1) ·
 * {@code fu1}: 지시어를 문서로 풀 수 있는 후속 질문(후속 질문 세트 same-topic) · {@code fu2}: 직전 답변의 내용을 묻는 질문 ·
 * {@code fu3}: 그중 직전 턴이 Direct 인 것(PLAN 예 2·3). 정답은 골든 마커({@code mustContainAny})가 답변에 있는가(keyHit)다.
 *
 * <p>측정 중에는 답변 뒤 보강(질문 다듬기·추가 질문)과 질의 확장을 끄고 일반/RAG 온도를 0 으로 둔다 — 두 코드가 같은 검색 결과와
 * 같은 답을 내기 쉽게 해서, 차이가 프롬프트 축소에서 오게 하기 위해서다. 스크래치 DB 가 필수다(설정 오버라이드·대화 턴을 쓴다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "file:.env", encoding = "UTF-8")
@EnabledIfSystemProperty(named = "ladder-eval.enabled", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ResourceLock("global-state")
class PromptLadderEvaluationTest {

    private static final String USER = "ladder-eval";
    private static final String VERSION = "latest";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void isolatedData(DynamicPropertyRegistry registry) {
        String dir = System.getProperty("ladder-eval.data-dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("-Dladder-eval.data-dir=<data/ 의 스크래치 복사본> 이 필요하다 — 이 하네스는 "
                    + "설정 오버라이드와 대화 턴을 DB 에 쓴다.");
        }
        registry.add("DATA_DIR", () -> dir);
        registry.add("app.data-dir", () -> dir);
    }

    @Autowired StreamingAgentService chat;
    @Autowired ThreadMetaService threadMeta;
    @Autowired MemoryService memory;
    @Autowired SettingsService settings;
    @Autowired AppProperties props;
    @Autowired ProviderContextWindows windows;

    private final String label = System.getProperty("ladder-eval.label", "run");
    private final int window = Integer.getInteger("ladder-eval.window", 0);
    private final int stride = Math.max(1, Integer.getInteger("ladder-eval.stride", 2));
    /** §6.31 — 측정하는 턴에 "이력 제외"를 켠다(앞 턴 fixture 를 만드는 prepare 에는 걸지 않는다). */
    private final boolean excludeHistory = Boolean.getBoolean("ladder-eval.exclude-history");
    private final Path out = Path.of(System.getProperty("ladder-eval.out", "target/ladder-eval"));
    private final Path priorsFile = Path.of(System.getProperty("ladder-eval.priors", "target/ladder-eval/priors.json"));
    private final Set<String> sections = Arrays.stream(System.getProperty("ladder-eval.sections", "long,fu1,fu2,fu3")
            .split(",")).map(String::strip).collect(Collectors.toSet());

    // ── 질문 ─────────────────────────────────────────────────────────────────────────────────────────────

    record FollowUpCase(String id, String kind, List<String> previousIds, String question, String targetId) {}
    record FollowUpSet(String version, int topK, String note, List<FollowUpCase> cases) {}

    /** ② 직전 답변의 내용을 묻는다 — 정답 마커는 앞 질문의 것(그 답변에만 있던 이름을 다시 묻는다). */
    record AboutAnswer(String id, String previousId, String question) {}

    private static final List<AboutAnswer> ABOUT_ANSWER = List.of(
            new AboutAnswer("f2-01", "batch-01", "방금 답에서 상속받으라고 한 그 클래스 이름을 정확히 다시 알려줘."),
            new AboutAnswer("f2-02", "batch-04", "그 메소드 이름 철자를 다시 정확히 써 줘."),
            new AboutAnswer("f2-03", "arch-05", "아까 말한 그 예외 클래스로 던지는 코드 예시를 보여줘."),
            new AboutAnswer("f2-04", "arch-07", "방금 그 시그니처를 메소드 선언 한 줄로 다시 써 줘."),
            new AboutAnswer("f2-05", "arch-08", "그 설정값을 properties 파일에 쓰는 한 줄로 보여줘."),
            new AboutAnswer("f2-06", "arch-03", "세 가지 각각의 영문 전체 이름을 다시 정리해 줘."),
            new AboutAnswer("f2-07", "sample-08", "그 타입이 무엇이었는지 한 단어로 다시 말해줘."),
            new AboutAnswer("f2-08", "batch-05", "방금 말한 것 중 배치 쪽 트랜잭션 방식만 다시 짧게 정리해 줘."));

    /** ③ 직전 턴이 Direct — 일반론을 길게 들은 뒤 "우리 문서 기준으로"를 묻는다(PLAN 예 2·3). */
    record AfterDirect(String id, String directQuestion, String question, String targetId) {}

    private static final List<AfterDirect> AFTER_DIRECT = List.of(
            new AfterDirect("f3-01", "자바 배치 프로그램은 보통 어떤 클래스를 상속받아서 만들어? 일반적인 방법을 예시와 함께 자세히 설명해 줘.",
                    "그걸 우리 프레임워크 문서 기준으로 하면 어떤 클래스를 상속해?", "batch-01"),
            new AfterDirect("f3-02", "배치 작업에서 처리일자(기준일자)를 다루는 일반적인 방법을 예시와 함께 자세히 설명해 줘.",
                    "우리 문서에서는 그 처리일자를 조회하는 메소드가 뭐야?", "batch-04"),
            new AfterDirect("f3-03", "온라인 거래와 배치의 트랜잭션 처리는 일반적으로 어떻게 달라? 예시와 함께 자세히 설명해 줘.",
                    "그 차이를 우리 문서 기준으로 정리해 줘.", "batch-05"),
            new AfterDirect("f3-04", "업무 애플리케이션에서 예외를 던질 때의 일반적인 설계 원칙을 예시와 함께 자세히 설명해 줘.",
                    "우리 문서 기준으로는 어떤 예외 클래스로 던져야 해?", "arch-05"),
            new AfterDirect("f3-05", "계층형 아키텍처에서 프로세스·기능·데이터 단위로 나누는 방식을 예시와 함께 자세히 설명해 줘.",
                    "우리 문서의 PU, FU, DU는 각각 뭐야?", "arch-03"),
            new AfterDirect("f3-06", "로깅 레벨을 환경별로 다르게 두는 일반적인 방법을 예시와 함께 자세히 설명해 줘.",
                    "우리 문서에서 로컬 환경의 onlinelog.level 기본값은 뭐야?", "arch-08"),
            new AfterDirect("f3-07", "금액 같은 숫자 데이터를 자바에서 다룰 때 어떤 타입을 쓰는 게 좋은지 예시와 함께 자세히 설명해 줘.",
                    "그럼 우리 EIMS 배포에서는 숫자를 어떤 타입으로 표현해?", "sample-08"),
            new AfterDirect("f3-08", "분산 트랜잭션에서 2PC를 쓰는 것의 장단점을 예시와 함께 자세히 설명해 줘.",
                    "우리 문서에서는 타 DB가 XA 타입일 때 2-PC를 권장해?", "arch-12"));

    /** {@code long} 절의 앞 턴 넷 — 질문과 다른 주제여야 이력이 "쓸모없는" 긴 독립 질문이 된다. */
    private static List<String> unrelatedPriors(String goldId) {
        if (goldId.startsWith("batch")) return List.of("sample-01", "sample-04", "sample-07", "arch-01");
        if (goldId.startsWith("arch")) return List.of("batch-01", "batch-03", "batch-05", "sample-04");
        return List.of("arch-01", "arch-05", "arch-08", "batch-04");
    }

    /** 긴 질문의 붙여넣기 — 질문과 무관한 장애 보고서. 질문 한도(2,000자) 안에서 1,500자 남짓. */
    private static final String PASTE = """
            [붙여넣은 장애 보고서]
            일시: 2026-10-20 09:12 ~ 09:47 (35분)
            영향: 사내 메신저 첨부파일 업로드 실패, 일부 사용자의 로그인 지연
            경과: 09:12 모니터링에서 파일 서버 응답 지연 경보가 울렸다. 09:15 당직자가 파일 서버의 디스크 사용률이 97%에 이른 것을 확인했고, \
            09:20 오래된 임시 파일을 정리하는 작업을 시작했다. 정리 중 백업 에이전트가 같은 디렉터리를 잠그고 있어 삭제가 반복해서 실패했다. \
            09:31 백업 에이전트를 일시 중지하고 정리를 다시 실행했으며, 09:40 디스크 사용률이 71%로 내려갔다. 09:47 업로드 성공률이 평소 수준으로 돌아왔다.
            원인: 분기 결산 자료가 임시 디렉터리에 대량으로 쌓였는데 정리 스케줄이 주 1회라 따라가지 못했다. 디스크 경보 임계값도 95%로 높게 잡혀 있어 \
            여유가 거의 없을 때에야 경보가 울렸다. 백업 에이전트와 정리 작업의 시간대가 겹친다는 점은 운영 문서에 적혀 있지 않았다.
            조치: 임시 디렉터리 정리 주기를 매일로 바꾸고, 디스크 경보 임계값을 85%와 92% 두 단계로 나눴다. 백업 에이전트는 정리 작업이 끝난 뒤에 \
            시작하도록 순서를 바꿨고, 결산 기간에는 임시 디렉터리를 별도 볼륨으로 분리하기로 했다. 재발 방지 대책은 다음 주 운영 회의에서 다시 검토한다.
            참고: 같은 시간대에 메일 서버 인증서 갱신 작업이 예정돼 있었으나 장애와 무관하여 예정대로 진행했다. 사용자 문의는 총 42건이었고 \
            대부분 첨부파일 재전송 방법에 관한 것이었다. 고객센터 안내 문구는 09:25 에 게시했고 09:50 에 내렸다.
            [붙여넣은 회의록 — 10월 운영 회의]
            참석: 인프라 3명, 서비스 운영 2명, 보안 1명
            안건 1. 사무실 이전에 따른 네트워크 장비 재배치 — 11월 둘째 주 주말에 진행하기로 했다. 이전 전날 저녁에 전체 장비 목록을 다시 확인하고, \
            이전 당일에는 코어 스위치부터 순서대로 옮긴다. 무선 접속 장치는 층별 배치도가 확정된 뒤 따로 설치한다. 출입 카드 시스템은 시설팀과 \
            일정을 맞춰야 하므로 담당자를 정해 다음 주까지 연락하기로 했다.
            안건 2. 노후 노트북 교체 — 구매 요청은 연말까지 접수하고, 교체 대상은 지급일 기준 5년이 지난 장비로 한정한다. 개인 자료 이전은 \
            사용자가 직접 하되, 요청이 있으면 지원팀이 원격으로 돕는다. 반납 장비의 디스크는 보안 지침에 따라 완전 삭제 후 폐기 업체에 넘긴다.
            안건 3. 비밀번호 정책 변경 — 변경 주기를 90일에서 180일로 늘리는 대신 다단계 인증을 전 직원에게 적용한다. 공지는 이번 달 말에 내보내고, \
            적용 첫 주에는 지원 창구 운영 시간을 두 시간 늘린다. 예외 신청 절차는 보안팀이 별도로 정리해 공유하기로 했다.
            """;

    // ── 앞 턴(fixture) ──────────────────────────────────────────────────────────────────────────────────

    /** 이력에 넣을 앞 턴 하나 — 질문·답변·Direct 여부. */
    record Prior(String key, String question, String answer, boolean direct) {}

    private GoldQuery.GoldSet gold() throws IOException {
        try (InputStream in = new ClassPathResource("search-eval/nexcore-gold.json").getInputStream()) {
            return JSON.readValue(in, GoldQuery.GoldSet.class);
        }
    }

    private FollowUpSet followUps() throws IOException {
        try (InputStream in = new ClassPathResource("search-eval/nexcore-followup.json").getInputStream()) {
            return JSON.readValue(in, FollowUpSet.class);
        }
    }

    private Map<String, GoldQuery> goldById() throws IOException {
        return gold().cases().stream().collect(Collectors.toMap(GoldQuery::id, g -> g, (a, b) -> a, LinkedHashMap::new));
    }

    /** 측정이 쓰는 RAG 앞 턴의 골든 id — {@code prepare} 가 이것만 만든다. */
    private Set<String> neededRagPriors() throws IOException {
        Set<String> ids = new java.util.TreeSet<>();
        for (FollowUpCase fu : sameTopic()) ids.addAll(fu.previousIds());
        ABOUT_ANSWER.forEach(a -> ids.add(a.previousId()));
        for (GoldQuery g : longCases()) ids.addAll(unrelatedPriors(g.id()));
        return ids;
    }

    private List<FollowUpCase> sameTopic() throws IOException {
        return followUps().cases().stream().filter(c -> "same-topic".equals(c.kind())).toList();
    }

    private List<GoldQuery> longCases() throws IOException {
        List<GoldQuery> all = gold().cases();
        List<GoldQuery> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i += stride) picked.add(all.get(i));
        return picked;
    }

    private Map<String, Prior> loadPriors() throws IOException {
        if (!Files.exists(priorsFile)) {
            throw new IllegalStateException(priorsFile + " 가 없다 — 먼저 -Dladder-eval.sections=prepare 로 앞 턴을 만든다");
        }
        List<Prior> list = JSON.readValue(priorsFile.toFile(), new TypeReference<List<Prior>>() {});
        return list.stream().collect(Collectors.toMap(Prior::key, p -> p, (a, b) -> a, LinkedHashMap::new));
    }

    // ── 절 ────────────────────────────────────────────────────────────────────────────────────────────

    /** 측정 조건 — 답변 뒤 보강·질의 확장 끔, 일반/RAG 온도 0. 창을 선언하면 그 창으로 예산을 잡는다. */
    private void setUp(boolean declareWindow) {
        settings.update(SettingsKeys.LLM_CLARIFIED_QUESTION_ENABLED, "false");
        settings.update(SettingsKeys.LLM_FOLLOW_UP_QUESTIONS_ENABLED, "false");
        settings.update(SettingsKeys.SEARCH_MULTIQUERY_ENABLED, "false");
        settings.update(SettingsKeys.LLM_TEMPERATURE, "0.0");
        // 이 코퍼스의 청크는 평균 ~470토큰이라(계획서 예시는 1,500) top-K 10 · 16K 에서는 축소가 거의 일어나지 않는다 —
        // 운영 DB 의 실제 값(15)으로 올려 잰다.
        String topK = System.getProperty("ladder-eval.top-k");
        if (topK != null && !topK.isBlank()) settings.update(SettingsKeys.SEARCH_TOP_K, topK);
        if (declareWindow && window > 0) {
            for (AppProperties.ProviderConfig p : props.llmSafe().providers()) {
                windows.record(p.name(), window, ProviderContextWindows.Source.CONFIGURED);
            }
        }
    }

    @Test
    @Order(1)
    void prepare() throws Exception {
        if (!sections.contains("prepare")) return;
        setUp(false);
        Map<String, GoldQuery> gold = goldById();
        // 이미 만든 앞 턴은 다시 만들지 않는다 — 답변이 빈 것(실패)만 다시 묻는다.
        Map<String, Prior> existing = Files.exists(priorsFile) ? loadPriors() : Map.of();
        List<Prior> priors = new ArrayList<>();
        int n = 0;
        for (String id : neededRagPriors()) {
            Prior kept = existing.get(id);
            if (kept != null && !kept.answer().isBlank()) { priors.add(kept); continue; }
            GoldQuery g = gold.get(id);
            Turn t = ask("prep-" + id + "-" + System.nanoTime(), g.question(), false, false);
            priors.add(new Prior(id, g.question(), t.answer(), false));
            System.out.printf("[prepare] %d %s %d자 error=%s%n", ++n, id, t.answer().length(), t.error());
        }
        for (AfterDirect d : AFTER_DIRECT) {
            Prior kept = existing.get(d.id());
            if (kept != null && !kept.answer().isBlank()) { priors.add(kept); continue; }
            Turn t = ask("prep-" + d.id() + "-" + System.nanoTime(), d.directQuestion(), true, false);
            priors.add(new Prior(d.id(), d.directQuestion(), t.answer(), true));
            System.out.printf("[prepare] %d %s(Direct) %d자 error=%s%n", ++n, d.id(), t.answer().length(), t.error());
        }
        Files.createDirectories(priorsFile.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(priorsFile.toFile(), priors);
    }

    @Test
    @Order(2)
    void measure() throws Exception {
        if (sections.stream().noneMatch(s -> s.equals("long") || s.startsWith("fu"))) return;
        setUp(true);
        Map<String, GoldQuery> gold = goldById();
        Map<String, Prior> priors = loadPriors();
        String nonce = Long.toString(System.currentTimeMillis(), 36);

        if (sections.contains("long")) {
            for (GoldQuery g : longCases()) {
                List<Prior> before = unrelatedPriors(g.id()).stream().map(priors::get).toList();
                String question = PASTE.strip() + "\n\n위 자료와는 별개로 질문이 있어. " + g.question();
                record("long", g.id(), before, question, g.mustContainAny(), nonce);
            }
        }
        if (sections.contains("fu1")) {
            for (FollowUpCase fu : sameTopic()) {
                List<Prior> before = fu.previousIds().stream().map(priors::get).toList();
                record("fu1", fu.id(), before, fu.question(), gold.get(fu.targetId()).mustContainAny(), nonce);
            }
        }
        if (sections.contains("fu2")) {
            for (AboutAnswer a : ABOUT_ANSWER) {
                record("fu2", a.id(), List.of(priors.get(a.previousId())), a.question(),
                        gold.get(a.previousId()).mustContainAny(), nonce);
            }
        }
        if (sections.contains("fu3")) {
            for (AfterDirect d : AFTER_DIRECT) {
                record("fu3", d.id(), List.of(priors.get(d.id())), d.question(),
                        gold.get(d.targetId()).mustContainAny(), nonce);
            }
        }
    }

    // ── 한 사례 ─────────────────────────────────────────────────────────────────────────────────────────

    /** 앞 턴을 새 대화에 넣고 질문 하나를 채팅 진입점으로 보낸 뒤 결과를 한 줄로 남긴다. */
    private void record(String section, String id, List<Prior> before, String question, List<String> markers,
                        String nonce) throws IOException {
        String thread = "le-" + label + "-" + id + "-" + nonce;
        threadMeta.getOrCreate(USER, thread, VERSION);
        for (Prior p : before) {
            memory.addTurn(USER, thread, p.question(), p.answer(), DbTimestamp.now(), 10, 10, 100, "local", 1, "N", "",
                    p.direct(), null);
        }
        Logs logs = Logs.attach();
        Turn t;
        try {
            t = ask(thread, question, false, excludeHistory);
        } finally {
            logs.detach();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("window", window);
        row.put("excludeHistory", excludeHistory);
        row.put("topK", props.searchTopKSafe());
        row.put("section", section);
        row.put("id", id);
        row.put("keyHit", t.error() == null && SearchQualityMetrics.containsAnyMarker(t.answer(), markers));
        row.put("grounded", t.done().get("grounded"));
        row.put("evalReason", t.done().get("evalReason"));
        row.put("retries", t.retries());
        row.put("llmCalls", t.done().get("llmCalls"));
        row.put("inputTokens", t.done().get("inputTokens"));
        row.put("budgetNote", t.done().get("budgetNote"));
        Object excluded = t.done().get("promptExcluded");
        row.put("promptExcluded", excluded instanceof List<?> l ? l.size() : 0);
        row.put("condensed", t.done().get("condensedQuestion"));
        row.put("budgetLogs", logs.count("[BUDGET]"));
        row.put("shrinkLogs", logs.count("[SHRINK]"));
        row.put("earlyStops", logs.count("retry stopped early"));
        row.put("budgetLines", logs.lines("[BUDGET]"));
        row.put("answerChars", t.answer().length());
        row.put("elapsedMs", t.elapsedMs());
        row.put("error", t.error());
        row.put("answer", t.answer());
        Path file = out.resolve(label).resolve(section + ".jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.printf("[%s] %s %s keyHit=%s grounded=%s retries=%d note=%s%n", label, section, id, row.get("keyHit"),
                row.get("grounded"), t.retries(), row.get("budgetNote"));
    }

    /** 채팅 한 턴의 결과 — 화면이 받는 SSE 이벤트를 모은 것. */
    record Turn(String answer, Map<String, Object> done, int retries, long elapsedMs, String error) {}

    private Turn ask(String thread, String question, boolean direct, boolean excludeHistory) {
        // 화면은 대화의 라우팅 모드를 늘 숨은 필드로 보낸다(chat.html #form-routing-mode) — 같은 값을 싣는다.
        String routingMode = threadMeta.getOrCreate(USER, thread, VERSION).routingMode();
        Capture capture = new Capture();
        long start = System.nanoTime();
        chat.run(USER, new ChatForm(question, thread, VERSION, routingMode, direct, "", "N", excludeHistory), capture);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> done = capture.done == null ? Map.of() : capture.done;
        Object finalAnswer = done.get("finalAnswer");
        String answer = finalAnswer instanceof String s ? s : capture.tokens.toString();
        return new Turn(answer, done, capture.retries, elapsed, capture.error);
    }

    /** {@link StreamingAgentService} 가 보내는 SSE 이벤트를 받아 두는 emitter — 컨트롤러 없이 진입점을 그대로 부르기 위해. */
    static final class Capture extends SseEmitter {
        private static final Pattern EVENT = Pattern.compile("event:([^\\n]+)\\ndata:(.*)\\n\\n", Pattern.DOTALL);
        final StringBuilder tokens = new StringBuilder();
        Map<String, Object> done;
        int retries;
        String error;

        @Override
        public synchronized void send(SseEventBuilder builder) {
            StringBuilder raw = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) raw.append(d.getData());
            Matcher m = EVENT.matcher(raw);
            if (!m.matches()) return;   // 하트비트 주석 등
            String name = m.group(1).strip();
            Map<String, Object> data;
            try {
                data = JSON.readValue(m.group(2), new TypeReference<Map<String, Object>>() {});
            } catch (IOException e) {
                return;
            }
            switch (name) {
                case "token" -> tokens.append(data.getOrDefault("text", ""));
                case "retry" -> { retries++; tokens.setLength(0); }
                case "done" -> done = data;
                case "error" -> error = String.valueOf(data.get("message"));
                default -> { }
            }
        }
    }

    /** 이 사례 동안 답변·그래프 로거가 남긴 줄 — {@code [BUDGET]}·{@code [SHRINK]}·조기 종료를 센다. */
    static final class Logs {
        private final List<Logger> loggers = List.of(
                LogbackTestSupport.logger("com.example.ragagent.service.AnswerService"),
                LogbackTestSupport.logger("com.example.ragagent.agent.AgentGraph"));
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        static Logs attach() {
            Logs logs = new Logs();
            logs.appender.start();
            logs.loggers.forEach(l -> l.addAppender(logs.appender));
            return logs;
        }

        void detach() {
            loggers.forEach(l -> l.detachAppender(appender));
            appender.stop();
        }

        long count(String needle) {
            return appender.list.stream().filter(e -> e.getFormattedMessage().contains(needle)).count();
        }

        List<String> lines(String needle) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(s -> s.contains(needle)).toList();
        }
    }
}
