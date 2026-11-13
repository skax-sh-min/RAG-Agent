package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.HttpClientTimeouts;
import com.example.ragagent.config.OpenAiClients;
import com.example.ragagent.llm.LlmPing;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ModelNameResolver;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.ProviderThinkingDialects;
import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * {@code /settings} LLM 라우팅 카드의 「성능 측정」 — 등록된 LLM 프로바이더·임베딩 서버·리랭커 서버에 정해진 입력을 보내
 * 응답 시간과 처리량(TPS)을 잰다(관리자 전용, 팝업 {@code /admin/settings/benchmark}).
 *
 * <p><b>라우터를 거치지 않는다</b>({@link LlmPing} 과 같은 태도). 서킷 브레이커·동시성 게이트·사용량 집계·재시도를 타면
 * 측정이 상태를 바꾸고(실패가 프로바이더를 막는다) 재는 값에 대기 시간이 섞인다. 대신 앱이 실제로 쓰는 주소·해석된 모델 id·
 * API 키·생각 제어 dialect 로 묻는다 — 셸의 curl 과 다른 점이다. 그 대가로 측정 중의 요청은 채팅과 같은 서버를 나눠 쓴다
 * (화면이 그렇게 말한다).
 *
 * <p>요청은 <b>한 번에 하나씩</b> 보낸다 — 동시 요청은 서버의 처리량이 아니라 경합을 잰다. 대상마다 첫 요청 하나는
 * <b>워밍업</b>이라 세지 않는다(JIT 로딩 서버는 첫 요청에서 모델을 올린다). 워밍업이 실패하면 그 대상은 건너뛴다 — 닿지
 * 않는 서버에 같은 실패를 수십 번 적지 않게. 측정은 앱 전체에서 동시에 하나뿐이다.
 *
 * <p>처리량의 정의:
 * <ul>
 *   <li>LLM — <b>E2E TPS</b> = 출력 토큰 ÷ 요청 전체 시간(프리필 포함). 서버가 {@code timings}(llama.cpp)를 주면
 *       <b>디코드 TPS</b>({@code predicted_per_second})와 <b>프리필 TPS</b>({@code prompt_per_second})도 싣는다.
 *       출력 토큰은 {@code usage.completion_tokens}(생각 토큰 포함), 없으면 {@link TokenEstimator} 추정(표시에 "추정").</li>
 *   <li>임베딩 — 요청 하나에 텍스트 하나, 입력 토큰 ÷ 시간. 토큰은 {@code usage.prompt_tokens}, 없으면 추정.</li>
 *   <li>리랭커 — 요청 하나에 질의 하나 + 문서 8개, 문서 수 ÷ 시간과 (질의 + 문서) 추정 토큰 ÷ 시간.</li>
 * </ul>
 */
@Service
public class ModelBenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(ModelBenchmarkService.class);

    static final int CONNECT_TIMEOUT_SECONDS = 5;
    static final int LLM_READ_TIMEOUT_SECONDS = 300;
    static final int AUX_READ_TIMEOUT_SECONDS = 60;
    /** 생각을 끈 요청의 출력 상한 — 프롬프트가 3~5문장을 청하므로 넉넉하다. */
    public static final int MAX_TOKENS_THINKING_OFF = 512;
    /** 생각을 켠 요청의 출력 상한 — 생각 토큰이 같은 상한을 나눠 쓴다. */
    public static final int MAX_TOKENS_THINKING_ON = 2048;
    static final int MIN_ROUNDS = 1;
    static final int MAX_ROUNDS = 5;
    static final int DEFAULT_ROUNDS = 3;
    /** 끝난 측정을 몇 개까지 기억하는가(메모리만 — 재기동하면 없다). */
    static final int KEEP_JOBS = 5;

    /** LLM 측정 프롬프트 — 출력 길이가 비슷하도록 모두 "3~5문장"을 청한다. 데이터셋이라 번역하지 않는다. */
    public static final List<String> LLM_PROMPTS = List.of(
            "HTTP 와 HTTPS 의 차이를 3~5문장으로 설명하세요.",
            "데이터베이스 인덱스가 조회를 빠르게 만드는 원리를 3~5문장으로 설명하세요.",
            "REST API 에서 GET 과 POST 를 언제 쓰는지 예를 들어 3~5문장으로 설명하세요.",
            "다음 오류의 흔한 원인과 확인 순서를 3~5문장으로 알려 주세요: java.net.ConnectException: Connection refused",
            "캐시를 도입할 때 주의할 점 세 가지를 각각 한 문장으로 쓰세요.",
            "Git 에서 merge 와 rebase 의 차이를 3~5문장으로 설명하세요.");

    /** 임베딩 측정 텍스트 — 짧은 구절부터 긴 문단까지 길이를 섞는다. */
    public static final List<String> EMBEDDING_TEXTS = List.of(
            "서버 포트 설정",
            "로그인 세션이 8시간 뒤에 만료되는 이유",
            "애플리케이션은 기본적으로 8080 포트에서 실행되며, 환경 변수 SERVER_PORT 로 바꿀 수 있다.",
            "데이터베이스 백업은 매일 새벽 3시 30분에 실행되고 최근 7개만 보관한다. 백업 파일은 data/db-backups 아래에 날짜와 시각을 이름으로 저장된다.",
            "The embedding server accepts OpenAI-compatible requests at /v1/embeddings and returns one vector per input text.",
            "업로드한 문서는 청크로 나뉘어 임베딩된다. 청크 크기는 기본 800자이고, 앞뒤 청크와 일부 내용이 겹치도록 오버랩을 둔다. "
                    + "표와 코드 블록은 가능한 한 한 청크 안에 남도록 경계를 조정하며, 이미지가 있는 경우 이미지 설명을 본문 뒤에 붙인다. "
                    + "검색 시에는 벡터 유사도와 키워드(BM25) 점수를 함께 사용해 순위를 매긴 뒤 교차 인코더로 다시 정렬한다.",
            "오류 코드 RAG-RATE-001 은 요청 한도를 넘었다는 뜻이다. 응답의 Retry-After 헤더에 적힌 초만큼 기다린 뒤 다시 시도하면 된다.",
            "관리자는 설정 화면에서 검색 튜닝 값을 바꿀 수 있다. 대부분의 값은 재기동 없이 다음 검색부터 반영되지만, 리랭커를 켜고 끄는 값은 "
                    + "재기동해야 한다. 생각 수준 카드는 관리자에게만 보이며, 호출 지점마다 생각을 끄거나 켤 수 있다. 값을 바꾸면 출력 예약과 "
                    + "입력 예산이 함께 다시 계산되고, 카드에 그 숫자가 즉시 표시된다. 변경 이력은 감사 로그에 남는다.");

    /** 리랭커 측정 문서 — 질의마다 같은 8개를 순위 매긴다. 실제 검색 청크에 가깝게 문서마다 100~300자. */
    public static final List<String> RERANK_DOCUMENTS = List.of(
            "애플리케이션은 기본적으로 8080 포트에서 실행된다. 포트를 바꾸려면 환경 변수 SERVER_PORT 를 지정하거나 application.properties 의 server.port 값을 고친 뒤 재기동한다. 이미 다른 프로그램이 그 포트를 쓰고 있으면 기동 로그에 'Port 8080 was already in use' 가 남고 앱이 뜨지 않으므로, 먼저 점유한 프로세스를 확인한다.",
            "데이터베이스 백업은 매일 새벽 3시 30분(app.time-zone 기준)에 자동으로 실행되고 최근 7개만 보관한다. 파일은 data/db-backups 아래에 memory-날짜-시각.db 이름으로 저장된다. 관리자 화면의 DB 백업 카드에서 즉시 백업을 실행할 수도 있으며, 문서 원본과 벡터 데이터는 이 백업에 포함되지 않는다.",
            "로그인 세션은 마지막 요청 뒤 8시간이 지나면 만료되어 다시 로그인해야 한다. 브라우저가 쿠키를 지우거나 시크릿 창을 닫아도 세션이 끊긴다. 여러 탭에서 동시에 로그아웃하면 다른 탭의 요청도 로그인 화면으로 돌아가므로, 작업 중에 세션이 자주 끊긴다면 쿠키 차단 설정과 서버 재기동 여부를 함께 확인한다.",
            "업로드 한도는 파일당 50MB 이며, 넘으면 413 응답과 함께 RAG-UP-001 오류를 돌려준다. 배포 전체 저장 용량 상한이 설정돼 있으면 남은 용량보다 큰 파일도 413 과 RAG-UP-002 로 거절된다. 프록시를 앞에 둔 배포에서는 프록시의 요청 본문 크기 제한이 먼저 걸려 앱 로그에 아무것도 남지 않을 수 있다.",
            "요청 한도를 넘으면 429 Too Many Requests 와 Retry-After 헤더를 돌려준다. 기본 한도는 사용자당 분당 120회이고, 파일 업로드는 분당 10회로 따로 센다. 응답 본문의 오류 코드는 RAG-RATE-001 이며, 헤더에 적힌 초만큼 기다리면 다시 요청할 수 있다.",
            "임베딩 서버가 응답하지 않으면 그 주소를 30초 동안 건너뛰고 같은 요청을 목록의 다음 주소로 보낸다. 연결 거부나 연결 타임아웃만 이렇게 처리하고, 5xx 응답이나 읽기 타임아웃은 다른 주소로 넘기지 않는다. 주소는 EMBED_BASE_URLS 에 세미콜론으로 여러 개 적는다.",
            "검색 결과가 하나도 없으면 LLM 을 부르지 않고 '관련 문서를 찾지 못했다'는 정형 응답을 돌려준다. 이 경우 답변은 미검증으로 표시되고 재검색도 하지 않는다. 태그 칩을 켜 둔 상태라면 그 태그가 붙은 문서만 찾으므로, 태그를 끄고 다시 물어보면 결과가 나올 수 있다.",
            "관리자 계정은 처음 접속할 때 /setup 화면에서 만든다. 관리자가 하나라도 생기면 그 화면은 닫히고, 이후의 가입은 일반 사용자 권한만 받는다. 관리자 비밀번호를 잊었다면 다른 관리자가 사용자 관리 화면에서 재설정해야 하며, 관리자가 하나뿐이라면 데이터베이스에서 직접 처리해야 한다.");

    public static final List<String> RERANK_QUERIES = List.of(
            "서버 포트를 바꾸는 방법",
            "백업은 언제 몇 개까지 보관하나요?",
            "세션이 자꾸 끊기는 이유",
            "파일 업로드가 413 으로 실패한다",
            "429 Too Many Requests 가 나올 때",
            "임베딩 서버를 두 대로 늘렸는데, 한 대가 꺼지면 요청이 자동으로 다른 서버로 넘어가나요? 넘어가지 않는 경우도 있나요?",
            "분명히 관련 문서를 올렸는데 채팅에서 물어보면 관련 문서를 찾지 못했다고만 나옵니다. 어떤 설정을 먼저 확인해야 하나요?");

    public enum Kind { LLM, EMBEDDING, RERANKER }

    /** LLM 측정의 생각 모드. {@code DEFAULT} 는 생각 제어를 받지 않는 프로바이더 — 아무것도 싣지 않는다(서버가 정한다). */
    public enum ThinkingMode { OFF, ON, DEFAULT }

    /**
     * 화면에 보이는 측정 대상. API 키는 담지 않는다(뷰로 나간다).
     *
     * @param id       폼 값 — {@code llm:<이름>} · {@code embed:<n>} · {@code rerank:<n>}
     * @param thinking LLM 의 생각 제어 필드(화면 표시용, 없으면 {@code null})
     * @param local    LOCAL 역할 프로바이더 또는 임베딩·리랭커 서버 — 기본 선택. 원격(과금될 수 있다)은 선택하지 않은 채 연다
     * @param disabled {@code /settings} 토글로 꺼진 프로바이더(측정은 할 수 있다)
     */
    public record TargetView(String id, Kind kind, String name, String baseUrl, String model,
                             String thinking, boolean local, boolean disabled) {}

    /** 측정 대상의 실체 — 키와 dialect 를 함께 든다. */
    record Target(TargetView view, String apiKey, ThinkingDialect dialect) {}

    /** 측정 옵션. */
    public record Options(boolean thinkingOff, boolean thinkingOn, int rounds) {
        public Options {
            rounds = Math.max(MIN_ROUNDS, Math.min(MAX_ROUNDS, rounds));
            if (!thinkingOff && !thinkingOn) thinkingOff = true;   // 둘 다 끄면 측정할 것이 없다 — 끔으로 잰다
        }
    }

    /**
     * 요청 하나의 결과. {@code null} = 서버가 주지 않았다/해당 없음.
     *
     * @param tokens          출력 토큰(LLM) · 입력 토큰(임베딩·리랭커)
     * @param tokensEstimated 서버가 세지 않아 추정했는가
     * @param tps             {@code tokens} ÷ 요청 전체 시간
     * @param docs            리랭커가 순위 매긴 문서 수
     */
    public record Sample(boolean ok, long latencyMs, Long tokens, boolean tokensEstimated, Double tps,
                         Double decodeTps, Double prefillTps, Long reasoningTokens, Integer docs, Integer dimensions,
                         String error) {
        static Sample failed(long latencyMs, String error) {
            return new Sample(false, latencyMs, null, false, null, null, null, null, null, null, error);
        }

        public Double docsPerSecond() {
            return docs == null || latencyMs <= 0 ? null : docs * 1000.0 / latencyMs;
        }
    }

    /** 최소·평균·최대 — 값이 하나도 없으면 셋 다 {@code null}. */
    public record Stat(Double min, Double avg, Double max) {
        static final Stat EMPTY = new Stat(null, null, null);

        static Stat of(Collection<Double> values) {
            List<Double> v = values.stream().filter(Objects::nonNull).toList();
            if (v.isEmpty()) return EMPTY;
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
            for (double d : v) { min = Math.min(min, d); max = Math.max(max, d); sum += d; }
            return new Stat(min, sum / v.size(), max);
        }

        public boolean present() {
            return avg != null;
        }

        /** 화면용 {@code 최소 / 평균 / 최대} — 값이 없으면 {@code -}. */
        public String text(int decimals) {
            return present() ? fmt(min, decimals) + " / " + fmt(avg, decimals) + " / " + fmt(max, decimals) : "-";
        }

        /** 화면용 평균 하나 — 값이 없으면 {@code -}. */
        public String avgText(int decimals) {
            return present() ? fmt(avg, decimals) : "-";
        }

        private static String fmt(double v, int decimals) {
            return String.format(java.util.Locale.ROOT, "%,." + Math.max(0, decimals) + "f", v);
        }
    }

    /** 대상 하나의 한 모드(LLM 은 생각 끔/켬, 임베딩·리랭커는 하나) — 요청 결과가 쌓인다. */
    public static final class ModeRun {
        private final ThinkingMode mode;
        private final String sent;
        private final int planned;
        private final List<Sample> samples = new CopyOnWriteArrayList<>();

        ModeRun(ThinkingMode mode, String sent, int planned) {
            this.mode = mode;
            this.sent = sent;
            this.planned = planned;
        }

        public ThinkingMode mode() { return mode; }
        /** 실제로 실은 생각 제어(로그와 같은 한 줄) — 임베딩·리랭커는 {@code null}. */
        public String sent() { return sent; }
        public int planned() { return planned; }
        public int done() { return samples.size(); }
        public List<Sample> samples() { return List.copyOf(samples); }

        public long okCount() { return samples.stream().filter(Sample::ok).count(); }

        public Stat latency() { return stat(s -> (double) s.latencyMs()); }
        public Stat tps() { return stat(Sample::tps); }
        public Stat decodeTps() { return stat(Sample::decodeTps); }
        public Stat prefillTps() { return stat(Sample::prefillTps); }
        public Stat docsPerSecond() { return stat(Sample::docsPerSecond); }
        public Stat tokens() { return stat(s -> s.tokens() == null ? null : s.tokens().doubleValue()); }
        public Stat reasoningTokens() { return stat(s -> s.reasoningTokens() == null ? null : s.reasoningTokens().doubleValue()); }

        public boolean tokensEstimated() {
            return samples.stream().anyMatch(s -> s.ok() && s.tokensEstimated());
        }

        public Integer dimensions() {
            return samples.stream().map(Sample::dimensions).filter(Objects::nonNull).findFirst().orElse(null);
        }

        /** 첫 실패의 사유 — 실패가 없으면 {@code null}. */
        public String firstError() {
            return samples.stream().filter(s -> !s.ok()).map(Sample::error).findFirst().orElse(null);
        }

        private Stat stat(Function<Sample, Double> f) {
            return Stat.of(samples.stream().filter(Sample::ok).map(f).toList());
        }

        void add(Sample s) { samples.add(s); }
    }

    public enum RunStatus { WAITING, RUNNING, DONE, SKIPPED, CANCELLED }

    /** 대상 하나의 측정. */
    public static final class TargetRun {
        private final TargetView target;
        private final List<ModeRun> modes = new CopyOnWriteArrayList<>();
        private volatile RunStatus status = RunStatus.WAITING;
        private volatile String resolvedModel;
        private volatile String error;
        private volatile Long warmupMs;

        TargetRun(TargetView target) { this.target = target; }

        public TargetView target() { return target; }
        public List<ModeRun> modes() { return List.copyOf(modes); }
        public RunStatus status() { return status; }
        /** 요청 본문에 실은 모델 id — 임베딩·리랭커는 서버 목록에서 해석한 값일 수 있다. */
        public String resolvedModel() { return resolvedModel; }
        /** 워밍업 실패 등으로 건너뛴 사유. */
        public String error() { return error; }
        public Long warmupMs() { return warmupMs; }
    }

    /** 측정 작업 하나 — 화면이 폴링으로 읽는다. 메모리에만 있다. */
    public static final class Job {
        private final long id;
        private final Options options;
        private final List<TargetRun> runs;
        private volatile boolean running = true;
        private volatile boolean cancelRequested;

        Job(long id, Options options, List<TargetRun> runs) {
            this.id = id;
            this.options = options;
            this.runs = List.copyOf(runs);
        }

        public long id() { return id; }
        public Options options() { return options; }
        public List<TargetRun> runs() { return runs; }
        public boolean running() { return running; }
        public boolean cancelRequested() { return cancelRequested; }

        // 화면의 데이터셋 크기 문구 — 템플릿에서 T(...) 로 상수를 읽지 않게
        public int llmPrompts() { return LLM_PROMPTS.size(); }
        public int embeddingTexts() { return EMBEDDING_TEXTS.size(); }
        public int rerankQueries() { return RERANK_QUERIES.size(); }
        public int rerankDocuments() { return RERANK_DOCUMENTS.size(); }

        /** 끝난 요청 수 / 계획한 요청 수(워밍업 제외) — 진행 막대. */
        public int progressPercent() {
            int planned = 0, done = 0;
            for (TargetRun r : runs) {
                for (ModeRun m : r.modes()) { planned += m.planned(); done += m.done(); }
                if (r.status() == RunStatus.SKIPPED || r.status() == RunStatus.CANCELLED) {
                    for (ModeRun m : r.modes()) done += m.planned() - m.done();
                }
            }
            if (planned == 0) return running ? 0 : 100;
            return (int) Math.min(100, Math.round(done * 100.0 / planned));
        }
    }

    /** 이미 측정이 돌고 있다 — 화면이 그 작업으로 안내한다. */
    public static final class BusyException extends RuntimeException {
        private final long runningJobId;

        BusyException(long runningJobId) {
            super("benchmark already running: " + runningJobId);
            this.runningJobId = runningJobId;
        }

        public long runningJobId() { return runningJobId; }
    }

    private final LlmRouter router;
    private final AppProperties props;
    private final ProviderThinkingDialects thinkingDialects;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private Job current;

    public ModelBenchmarkService(LlmRouter router, AppProperties props, ProviderThinkingDialects thinkingDialects) {
        this.router = router;
        this.props = props;
        this.thinkingDialects = thinkingDialects;
    }

    // ── 대상 ────────────────────────────────────────────────────────────────

    /** 측정할 수 있는 대상 전부 — LLM 프로바이더(우선순위 순) → 임베딩 주소 → 리랭커 주소. */
    public List<TargetView> targets() {
        return resolveTargets().stream().map(Target::view).toList();
    }

    List<Target> resolveTargets() {
        List<Target> out = new ArrayList<>();
        for (LlmProvider p : router.allProviders()) {
            ThinkingDialect dialect = thinkingDialects.dialectOf(p.name());
            out.add(new Target(new TargetView("llm:" + p.name(), Kind.LLM, p.name(), p.baseUrl(), p.model(),
                    dialect.field(), p.role() == ProviderRole.LOCAL, router.isDisabled(p.name())),
                    p.hasValidApiKey() ? p.apiKey() : null, dialect));
        }
        AppProperties.EmbeddingConfig e = props.embeddingSafe();
        List<String> embedUrls = new ArrayList<>();
        if (e.baseUrl() != null && !e.baseUrl().isBlank()) embedUrls.add(e.baseUrl());
        if (e.additionalBaseUrls() != null) embedUrls.addAll(e.additionalBaseUrls());
        for (int i = 0; i < embedUrls.size(); i++) {
            out.add(new Target(new TargetView("embed:" + i, Kind.EMBEDDING, "embedding-" + (i + 1), embedUrls.get(i),
                    e.model(), null, true, false), blankToNull(e.apiKey()), ThinkingDialect.NONE));
        }
        AppProperties.RerankerConfig r = props.rerankerSafe();
        List<String> rerankUrls = props.rerankerBaseUrls();
        for (int i = 0; i < rerankUrls.size(); i++) {
            out.add(new Target(new TargetView("rerank:" + i, Kind.RERANKER, "reranker-" + (i + 1), rerankUrls.get(i),
                    r.model(), null, true, false), blankToNull(r.apiKey()), ThinkingDialect.NONE));
        }
        return out;
    }

    // ── 작업 ────────────────────────────────────────────────────────────────

    /**
     * 고른 대상으로 측정을 시작한다 — 가상 스레드에서 돌고 곧바로 작업을 돌려준다.
     *
     * @throws BusyException            이미 하나가 돌고 있으면
     * @throws IllegalArgumentException 고른 대상이 하나도 없으면(모르는 id 는 무시한다)
     */
    public synchronized Job start(Collection<String> targetIds, Options options) {
        if (current != null && current.running()) throw new BusyException(current.id());
        Set<String> wanted = targetIds == null ? Set.of() : Set.copyOf(targetIds);
        List<Target> chosen = resolveTargets().stream().filter(t -> wanted.contains(t.view().id())).toList();
        if (chosen.isEmpty()) throw new IllegalArgumentException("측정할 대상을 하나 이상 고르세요");

        List<TargetRun> runs = new ArrayList<>();
        for (Target t : chosen) {
            TargetRun run = new TargetRun(t.view());
            for (ModeRun m : plannedModes(t, options)) run.modes.add(m);
            runs.add(run);
        }
        Job job = new Job(ids.incrementAndGet(), options, runs);
        current = job;
        jobs.put(job.id(), job);
        while (jobs.size() > KEEP_JOBS) jobs.remove(jobs.keySet().iterator().next());
        log.info("[BENCH] 시작 job={} targets={} options={}", job.id(),
                chosen.stream().map(t -> t.view().id()).toList(), options);
        Thread.ofVirtual().name("model-bench-" + job.id()).start(MdcPropagation.wrap(() -> run(job, chosen)));
        return job;
    }

    public synchronized Optional<Job> job(long id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** 지금 돌고 있는 작업 — 화면을 다시 열었을 때 이어서 보여 준다. */
    public synchronized Optional<Job> running() {
        return current != null && current.running() ? Optional.of(current) : Optional.empty();
    }

    /** 멈춤 요청 — 지금 보내고 있는 요청이 끝나면 멈춘다(요청을 끊지는 않는다). */
    public boolean cancel(long id) {
        Job job = job(id).orElse(null);
        if (job == null || !job.running()) return false;
        job.cancelRequested = true;
        return true;
    }

    private List<ModeRun> plannedModes(Target t, Options o) {
        TargetView v = t.view();
        return switch (v.kind()) {
            case LLM -> {
                List<ModeRun> modes = new ArrayList<>();
                if (t.dialect().support() == ThinkingDialect.Support.NONE) {
                    modes.add(new ModeRun(ThinkingMode.DEFAULT, ThinkingWire.NOTHING.describe(), LLM_PROMPTS.size()));
                } else {
                    if (o.thinkingOff()) modes.add(new ModeRun(ThinkingMode.OFF, wireFor(t, ThinkingMode.OFF).describe(), LLM_PROMPTS.size()));
                    if (o.thinkingOn()) modes.add(new ModeRun(ThinkingMode.ON, wireFor(t, ThinkingMode.ON).describe(), LLM_PROMPTS.size()));
                }
                yield modes;
            }
            case EMBEDDING -> List.of(new ModeRun(ThinkingMode.DEFAULT, null, EMBEDDING_TEXTS.size() * o.rounds()));
            case RERANKER -> List.of(new ModeRun(ThinkingMode.DEFAULT, null, RERANK_QUERIES.size() * o.rounds()));
        };
    }

    private ThinkingWire wireFor(Target t, ThinkingMode mode) {
        return switch (mode) {
            case OFF -> t.dialect().wire(ThinkingLevel.OFF);
            case ON -> t.dialect().wire(ThinkingLevel.MEDIUM);
            case DEFAULT -> ThinkingWire.NOTHING;
        };
    }

    private void run(Job job, List<Target> targets) {
        try {
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                TargetRun run = job.runs().get(i);
                if (job.cancelRequested()) { run.status = RunStatus.CANCELLED; continue; }
                run.status = RunStatus.RUNNING;
                try {
                    runTarget(job, t, run);
                    if (run.status == RunStatus.RUNNING) run.status = job.cancelRequested() ? RunStatus.CANCELLED : RunStatus.DONE;
                } catch (RuntimeException e) {
                    run.error = LlmPing.describe(e);
                    run.status = RunStatus.SKIPPED;
                    log.warn("[BENCH] job={} target={} 실패: {}", job.id(), t.view().id(), run.error);
                }
            }
        } finally {
            job.running = false;
            log.info("[BENCH] 끝 job={} cancelled={}", job.id(), job.cancelRequested());
        }
    }

    private void runTarget(Job job, Target t, TargetRun run) {
        switch (t.view().kind()) {
            case LLM -> runLlm(job, t, run);
            case EMBEDDING -> runEmbedding(job, t, run);
            case RERANKER -> runReranker(job, t, run);
        }
    }

    // ── LLM ─────────────────────────────────────────────────────────────────

    private void runLlm(Job job, Target t, TargetRun run) {
        String base = OpenAiClients.sdkBaseUrl(t.view().baseUrl());
        String model = t.view().model();
        run.resolvedModel = model;
        RestClient client = client(LLM_READ_TIMEOUT_SECONDS);
        // 워밍업 — 첫 모드의 생각 설정으로 첫 프롬프트를 한 번(세지 않는다)
        ModeRun first = run.modes().get(0);
        Sample warm = llmCall(client, base, model, t.apiKey(), LLM_PROMPTS.get(0), wireFor(t, first.mode()),
                maxTokensFor(first.mode()));
        if (!warm.ok()) {
            run.error = warm.error();
            run.status = RunStatus.SKIPPED;
            return;
        }
        run.warmupMs = warm.latencyMs();
        for (ModeRun m : run.modes()) {
            ThinkingWire wire = wireFor(t, m.mode());
            for (String prompt : LLM_PROMPTS) {
                if (job.cancelRequested()) return;
                m.add(llmCall(client, base, model, t.apiKey(), prompt, wire, maxTokensFor(m.mode())));
            }
        }
    }

    private static int maxTokensFor(ThinkingMode mode) {
        return mode == ThinkingMode.ON ? MAX_TOKENS_THINKING_ON : MAX_TOKENS_THINKING_OFF;
    }

    /** 요청 본문 — 생각 제어는 실제 전송과 같은 {@link ThinkingWire}(본문 최상위 필드 + 표준 {@code reasoning_effort}). */
    static Map<String, Object> llmBody(String model, String prompt, ThinkingWire wire, int maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model == null ? "" : model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        body.put("stream", false);
        body.putAll(wire.extraBody());
        if (wire.reasoningEffort() != null) body.put(ThinkingWire.REASONING_EFFORT_FIELD, wire.reasoningEffort());
        return body;
    }

    private Sample llmCall(RestClient client, String base, String model, String apiKey, String prompt,
                           ThinkingWire wire, int maxTokens) {
        long t0 = System.nanoTime();
        try {
            JsonNode res = client.post().uri(base + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .body(llmBody(model, prompt, wire, maxTokens))
                    .retrieve()
                    .body(JsonNode.class);
            return parseLlm(res, elapsedMs(t0));
        } catch (Exception e) {
            return Sample.failed(elapsedMs(t0), LlmPing.describe(e));
        }
    }

    static Sample parseLlm(JsonNode res, long latencyMs) {
        JsonNode choice = res == null ? null : res.path("choices").path(0);
        if (choice == null || choice.isMissingNode()) {
            return Sample.failed(latencyMs, "chat/completions: unexpected body (no choices[])");
        }
        JsonNode message = choice.path("message");
        String content = textOrEmpty(message.path("content"));
        String reasoning = textOrEmpty(message.path("reasoning_content"));
        JsonNode usage = res.path("usage");
        Long completion = longOrNull(usage.path("completion_tokens"));
        boolean estimated = completion == null;
        long tokens = estimated ? TokenEstimator.estimate(content) + TokenEstimator.estimate(reasoning) : completion;
        Long reasoningTokens = longOrNull(usage.path("completion_tokens_details").path("reasoning_tokens"));
        if (reasoningTokens == null && !reasoning.isEmpty()) reasoningTokens = TokenEstimator.estimate(reasoning);
        JsonNode timings = res.path("timings");
        Double decode = doubleOrNull(timings.path("predicted_per_second"));
        Double prefill = doubleOrNull(timings.path("prompt_per_second"));
        return new Sample(true, latencyMs, tokens, estimated, perSecond(tokens, latencyMs), decode, prefill,
                reasoningTokens, null, null, null);
    }

    // ── 임베딩 ──────────────────────────────────────────────────────────────

    private void runEmbedding(Job job, Target t, TargetRun run) {
        String base = OpenAiClients.sdkBaseUrl(t.view().baseUrl());
        String model = resolveModel(base, t.apiKey(), t.view().model());
        run.resolvedModel = model;
        RestClient client = client(AUX_READ_TIMEOUT_SECONDS);
        Sample warm = embedCall(client, base, model, t.apiKey(), EMBEDDING_TEXTS.get(0));
        if (!warm.ok()) {
            run.error = warm.error();
            run.status = RunStatus.SKIPPED;
            return;
        }
        run.warmupMs = warm.latencyMs();
        ModeRun m = run.modes().get(0);
        for (int round = 0; round < job.options().rounds(); round++) {
            for (String text : EMBEDDING_TEXTS) {
                if (job.cancelRequested()) return;
                m.add(embedCall(client, base, model, t.apiKey(), text));
            }
        }
    }

    private Sample embedCall(RestClient client, String base, String model, String apiKey, String text) {
        long t0 = System.nanoTime();
        try {
            JsonNode res = client.post().uri(base + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .body(Map.of("model", model == null ? "" : model, "input", List.of(text)))
                    .retrieve()
                    .body(JsonNode.class);
            return parseEmbedding(res, text, elapsedMs(t0));
        } catch (Exception e) {
            return Sample.failed(elapsedMs(t0), LlmPing.describe(e));
        }
    }

    static Sample parseEmbedding(JsonNode res, String text, long latencyMs) {
        JsonNode vector = res == null ? null : res.path("data").path(0).path("embedding");
        if (vector == null || !vector.isArray() || vector.isEmpty()) {
            return Sample.failed(latencyMs, "embeddings: unexpected body (no data[0].embedding)");
        }
        Long counted = longOrNull(res.path("usage").path("prompt_tokens"));
        boolean estimated = counted == null || counted <= 0;
        long tokens = estimated ? TokenEstimator.estimate(text) : counted;
        return new Sample(true, latencyMs, tokens, estimated, perSecond(tokens, latencyMs), null, null, null, null,
                vector.size(), null);
    }

    // ── 리랭커 ──────────────────────────────────────────────────────────────

    private void runReranker(Job job, Target t, TargetRun run) {
        String root = rerankRoot(t.view().baseUrl());
        String model = resolveModel(root + "/v1", t.apiKey(), t.view().model());
        run.resolvedModel = model;
        RestClient client = client(AUX_READ_TIMEOUT_SECONDS);
        Sample warm = rerankCall(client, root, model, t.apiKey(), RERANK_QUERIES.get(0));
        if (!warm.ok()) {
            run.error = warm.error();
            run.status = RunStatus.SKIPPED;
            return;
        }
        run.warmupMs = warm.latencyMs();
        ModeRun m = run.modes().get(0);
        for (int round = 0; round < job.options().rounds(); round++) {
            for (String query : RERANK_QUERIES) {
                if (job.cancelRequested()) return;
                m.add(rerankCall(client, root, model, t.apiKey(), query));
            }
        }
    }

    private Sample rerankCall(RestClient client, String root, String model, String apiKey, String query) {
        long t0 = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model == null ? "" : model);
            body.put("query", query);
            body.put("documents", RERANK_DOCUMENTS);
            body.put("top_n", RERANK_DOCUMENTS.size());
            JsonNode res = client.post().uri(root + "/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (apiKey != null) h.setBearerAuth(apiKey); })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseRerank(res, query, elapsedMs(t0));
        } catch (Exception e) {
            return Sample.failed(elapsedMs(t0), LlmPing.describe(e));
        }
    }

    static Sample parseRerank(JsonNode res, String query, long latencyMs) {
        JsonNode results = res == null ? null : res.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return Sample.failed(latencyMs, "rerank: unexpected body (no results[])");
        }
        Long counted = longOrNull(res.path("usage").path("prompt_tokens"));
        if (counted == null) counted = longOrNull(res.path("usage").path("total_tokens"));
        boolean estimated = counted == null || counted <= 0;
        // 교차 인코더는 (질의, 문서) 쌍마다 질의를 다시 읽는다
        long tokens = estimated
                ? TokenEstimator.estimate(query) * RERANK_DOCUMENTS.size() + TokenEstimator.estimate(RERANK_DOCUMENTS)
                : counted;
        return new Sample(true, latencyMs, tokens, estimated, perSecond(tokens, latencyMs), null, null, null,
                results.size(), null, null);
    }

    /** 주소에서 끝의 {@code /} 와 {@code /v1} 을 걷는다 — 경로는 {@code /v1/rerank} 로 고정({@code CrossEncoderReranker} 와 같다). */
    static String rerankRoot(String configured) {
        String s = configured == null ? "" : configured.strip().replaceAll("/+$", "");
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        return s.replaceAll("/+$", "");
    }

    // ── 공통 ────────────────────────────────────────────────────────────────

    /**
     * 설정한 모델명을 서버 표기로 — 목록에서 정확 일치 → 하나만 포함 → 여럿 포함이면 가장 짧은 id({@code ServerModelResolver} 와
     * 같은 규칙). 목록을 못 읽으면 설정값 그대로 보낸다(측정 요청이 사유를 말해 준다).
     */
    private String resolveModel(String v1Base, String apiKey, String configured) {
        try {
            List<String> ids = LlmPing.listModelIds(v1Base, apiKey, CONNECT_TIMEOUT_SECONDS, AUX_READ_TIMEOUT_SECONDS);
            ModelNameResolver.Resolution res = ModelNameResolver.resolve(configured, ids);
            if (res.found()) return res.resolved();
            if (res.ambiguous()) {
                return res.candidates().stream().min(Comparator.comparingInt(String::length)).orElse(configured);
            }
        } catch (Exception e) {
            log.debug("[BENCH] 모델 목록 조회 실패({}) — 설정값 그대로: {}", v1Base, LlmPing.describe(e));
        }
        return configured;
    }

    private static RestClient client(int readTimeoutSeconds) {
        return HttpClientTimeouts.restClientBuilder(CONNECT_TIMEOUT_SECONDS, readTimeoutSeconds).build();
    }

    static Double perSecond(long amount, long latencyMs) {
        return latencyMs <= 0 ? null : amount * 1000.0 / latencyMs;
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "" : n.asText("");
    }

    private static Long longOrNull(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asLong();
    }

    private static Double doubleOrNull(JsonNode n) {
        return n == null || !n.isNumber() ? null : n.asDouble();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
