package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.service.HybridSearchService;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 영향도 분석 워크플로(§6.33 4a, AGENT_MCP_DESIGN §5.4) — "이것을 바꾸거나 지우면 어떤 문서가 영향을 받는가".
 *
 * <p><b>고정된 순서다.</b> ① 전수 조회({@link KeywordSearchRepository#findMentions} — 순위 검색은 "관련 있는 몇 개"를 찾는 도구라 언급된 곳
 * 전부가 필요한 영향도에는 모자라다) → ② 저장 원문으로 확인 → ③ 후보 상한({@value #MAX_JUDGED})까지 청크마다 LLM 이 영향 여부를 판정 → ④
 * <b>인용을 코드가 검증</b>(영향 있다는 판정의 근거 인용이 원문의 부분 문자열이 아니면 그 판정은 "근거 미확인") → ⑤ 리포트 저장. LLM 은 ③ 안의
 * 판단만 한다 — 계획을 세우지도, 아무것도 쓰지도 않는다. <b>코퍼스는 바뀌지 않는다</b>(작업 기록 {@code change_job} 만 쓴다).
 *
 * <p>정직하게 밝히는 한계: 3글자 미만 식별자는 trigram 색인이 찾지 못한다({@link Report#tooShort}) · 후보가 상한을 넘으면 나머지는 판정 없이 파일별
 * 언급 수로만 보인다({@link Report#unjudged}) · 전수 조회 상한({@value #MENTION_LIMIT})을 넘으면 {@link Report#truncated} · 의미적으로만 연관된
 * (글자가 다른) 청크는 이 단계에서 찾지 않는다.
 *
 * <p>채팅에 양보한다 — 로컬 LLM 의 대화형 게이트가 비어 있을 때 호출을 보낸다({@code ClarifiedQuestionBackfill} 과 같은 신호). 한 사용자당 동시에 한
 * 건만 돈다. 앱이 죽어 {@code running} 으로 남은 작업은 기동 때 {@code interrupted} 로 바뀐다({@link #markInterruptedAtStartup}).
 */
@Service
public class ImpactAnalysisWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 판정하는 청크의 상한 — 넘는 만큼은 판정 없이 언급 수로만 보인다. */
    static final int MAX_JUDGED = 40;
    /** 전수 조회가 읽는 청크의 상한 — 이 값을 넘으면 리포트가 잘렸다고 밝힌다. */
    static final int MENTION_LIMIT = 200;
    /** 판정 호출 하나의 출력 예약 — JSON 세 필드. */
    static final int MAX_OUTPUT_TOKENS = 512;
    /** 판정에 싣는 청크 본문의 글자 상한. */
    static final int MAX_CHUNK_CHARS = 2_500;
    /** 버전 — 영향도는 최신 코퍼스를 본다(Tool 은 version 인자를 받지 않는다). */
    static final String VERSION = "latest";

    public static final String VERDICT_IMPACTED = "impacted";
    public static final String VERDICT_NOT_IMPACTED = "not_impacted";
    /** 영향 있다고 했지만 근거 인용이 원문에 없다 — 사람이 확인해야 한다. */
    public static final String VERDICT_UNVERIFIED = "unverified";
    /** 판정 호출이 실패했거나 JSON 을 읽지 못했다. */
    public static final String VERDICT_ERROR = "error";

    public record Finding(String docId, String file, String chunkId, String page, String verdict, String reason, String quote) {}

    /** 파일별 판정하지 못한 언급 수. */
    public record Unjudged(String docId, String file, int chunks) {}

    public record Report(String subject, String changeNote, int mentions, boolean truncated, boolean tooShort,
                         int judged, List<Finding> findings, List<Unjudged> unjudged) {}

    /** 시작 결과 — {@code jobId} 는 {@code change_job.id}. */
    public record Started(long jobId, String status) {}

    private final KeywordSearchRepository keywords;
    private final HybridSearchService hybrid;
    private final LlmRouter llmRouter;
    private final ChangeJobRepository jobs;
    private final MessageSource messages;
    private final AppProperties props;
    private final Duration poll;
    private final Duration maxWait;

    @Autowired
    public ImpactAnalysisWorkflow(KeywordSearchRepository keywords, HybridSearchService hybrid, LlmRouter llmRouter,
                                  ChangeJobRepository jobs, MessageSource messages, AppProperties props) {
        this(keywords, hybrid, llmRouter, jobs, messages, props, Duration.ofMillis(250), Duration.ofSeconds(30));
    }

    /** 테스트용 — 양보 대기를 줄인다. */
    ImpactAnalysisWorkflow(KeywordSearchRepository keywords, HybridSearchService hybrid, LlmRouter llmRouter,
                           ChangeJobRepository jobs, MessageSource messages, AppProperties props,
                           Duration poll, Duration maxWait) {
        this.keywords = keywords;
        this.hybrid = hybrid;
        this.llmRouter = llmRouter;
        this.jobs = jobs;
        this.messages = messages;
        this.props = props;
        this.poll = poll;
        this.maxWait = maxWait;
    }

    /** 기동이 끝나면 한 번 — 죽은 작업을 {@code interrupted} 로. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void onReady() {
        try {
            markInterruptedAtStartup();
        } catch (Exception e) {
            log.warn("[IMPACT] 기동 시 작업 정리 실패: {}", e.getMessage());
        }
    }

    public int markInterruptedAtStartup() {
        int n = jobs.markRunningInterrupted();
        if (n > 0) log.warn("[IMPACT] 이전 실행에서 끝나지 않은 작업 {}건을 interrupted 로 표시했다", n);
        return n;
    }

    /**
     * 작업을 만들고 가상 스레드에서 돌린다. 이 사용자의 작업이 이미 돌고 있으면 새로 만들지 않는다.
     *
     * @throws IllegalStateException 이미 진행 중인 작업이 있을 때
     * @throws IllegalArgumentException 대상이 비었을 때
     */
    public Started start(String userId, String subject, String changeNote, Locale locale) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject 이(가) 필요합니다");
        if (jobs.countRunning(userId) > 0) throw new IllegalStateException("이미 진행 중인 영향도 분석이 있습니다");
        String text = changeNote == null || changeNote.isBlank() ? subject.strip() : subject.strip() + " — " + changeNote.strip();
        long id = jobs.create(ChangeJobRepository.KIND_IMPACT, text, userId);
        Thread.ofVirtual().name("impact-" + id).start(MdcPropagation.wrap(() -> analyzeAndStore(id, subject, changeNote, locale)));
        return new Started(id, ChangeJobRepository.RUNNING);
    }

    /** 분석을 동기로 돌려 리포트를 저장한다 — {@link #start} 의 작업 몸체이자 테스트의 입구. 어떤 실패도 {@code failed} 로 남는다. */
    void analyzeAndStore(long jobId, String subject, String changeNote, Locale locale) {
        try {
            Report report = analyze(subject, changeNote, locale);
            jobs.finish(jobId, ChangeJobRepository.DONE, MAPPER.writeValueAsString(report), null);
            log.info("[IMPACT] 완료 job={} 언급={} 판정={} 영향={}", jobId, report.mentions(), report.judged(),
                    report.findings().stream().filter(f -> VERDICT_IMPACTED.equals(f.verdict())).count());
        } catch (Exception e) {
            log.warn("[IMPACT] 실패 job={}: {}", jobId, e.getMessage());
            jobs.finish(jobId, ChangeJobRepository.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    /** 리포트를 만든다(저장하지 않는다). */
    Report analyze(String subject, String changeNote, Locale locale) {
        String term = subject.strip();
        KeywordSearchRepository.Mentions mentions = keywords.findMentions(VERSION, term, MENTION_LIMIT);
        if (mentions.tooShort()) {
            return new Report(term, changeNote, 0, false, true, 0, List.of(), List.of());
        }
        // 색인의 파생 텍스트가 아니라 저장 원문으로 — 판정과 인용 검증이 사용자가 보는 본문 기준이어야 한다.
        List<Document> chunks = hybrid.withStoredText(mentions.hits());
        List<Document> toJudge = chunks.size() > MAX_JUDGED ? chunks.subList(0, MAX_JUDGED) : chunks;
        List<Finding> findings = new ArrayList<>();
        for (Document chunk : toJudge) {
            awaitChatQuiet();
            findings.add(judge(term, changeNote, chunk, locale));
        }
        Map<String, Unjudged> rest = new LinkedHashMap<>();
        for (Document d : chunks.subList(toJudge.size(), chunks.size())) {
            String docId = String.valueOf(d.getMetadata().getOrDefault(MetaKey.DOC_ID, ""));
            String file = String.valueOf(d.getMetadata().getOrDefault(MetaKey.FILENAME, docId));
            rest.merge(docId, new Unjudged(docId, file, 1), (a, b) -> new Unjudged(a.docId(), a.file(), a.chunks() + 1));
        }
        return new Report(term, changeNote, chunks.size(), mentions.truncated(), false,
                findings.size(), List.copyOf(findings), List.copyOf(rest.values()));
    }

    private Finding judge(String subject, String changeNote, Document chunk, Locale locale) {
        Map<String, Object> meta = chunk.getMetadata();
        String docId = String.valueOf(meta.getOrDefault(MetaKey.DOC_ID, ""));
        String file = String.valueOf(meta.getOrDefault(MetaKey.FILENAME, docId));
        String page = String.valueOf(meta.getOrDefault(MetaKey.PAGE_OR_SLIDE, ""));
        String text = chunk.getText() == null ? "" : chunk.getText();
        String shown = text.length() > MAX_CHUNK_CHARS ? text.substring(0, MAX_CHUNK_CHARS) : text;
        try {
            String system = messages.getMessage("prompt.impact.judge", null, locale)
                    .replace("{subject}", subject)
                    .replace("{note}", changeNote == null || changeNote.isBlank() ? "-" : changeNote.strip());
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.RERANK.taskType(), ThinkingSite.RERANK.fixedRoutingMode(),
                    model -> model.call(new Prompt(List.of(new SystemMessage(system),
                            new UserMessage(PromptInjectionGuard.wrap(shown))), options())));
            return parseJudgement(result.text(), shown, chunk.getId(), docId, file, page);
        } catch (Exception e) {
            log.warn("[IMPACT] 판정 호출 실패 file={}: {}", file, e.getMessage());
            return new Finding(docId, file, chunk.getId(), page, VERDICT_ERROR, "판정 호출 실패", "");
        }
    }

    /**
     * 모델의 판정 JSON 을 읽는다. 영향 있음({@code impacted=true})은 <b>인용이 원문(공백을 한 칸으로 줄인)의 부분 문자열일 때만</b> 인정하고,
     * 아니면 {@link #VERDICT_UNVERIFIED} 다 — 모델이 지어낸 근거로 "영향 있음"이 리포트에 실리지 않게 하는 검증이다.
     */
    static Finding parseJudgement(String response, String chunkText, String chunkId, String docId, String file, String page) {
        int a = response == null ? -1 : response.indexOf('{');
        int b = response == null ? -1 : response.lastIndexOf('}');
        if (a < 0 || b <= a) return new Finding(docId, file, chunkId, page, VERDICT_ERROR, "판정을 읽지 못했다", "");
        try {
            JsonNode j = MAPPER.readTree(response.substring(a, b + 1));
            boolean impacted = j.path("impacted").asBoolean(false);
            String reason = j.path("reason").asString("").strip();
            String quote = j.path("quote").asString("").strip();
            if (!impacted) return new Finding(docId, file, chunkId, page, VERDICT_NOT_IMPACTED, reason, "");
            boolean verified = !quote.isEmpty() && normalize(chunkText).contains(normalize(quote));
            return new Finding(docId, file, chunkId, page, verified ? VERDICT_IMPACTED : VERDICT_UNVERIFIED, reason, quote);
        } catch (RuntimeException e) {
            return new Finding(docId, file, chunkId, page, VERDICT_ERROR, "판정을 읽지 못했다", "");
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    /** 채팅이 로컬 LLM 을 쓰는 동안 기다린다 — 상한을 넘으면 그냥 보낸다(영원히 굶지 않게). */
    private void awaitChatQuiet() {
        long deadline = System.nanoTime() + maxWait.toNanos();
        try {
            while (llmRouter.localTier1Concurrency().map(s -> s.inUse() > 0).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(poll.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), ThinkingSite.RERANK);
        int configured = props.llmSafe().maxTokens();
        builder.maxTokens(configured > 0 ? Math.min(configured, MAX_OUTPUT_TOKENS) : MAX_OUTPUT_TOKENS);
        return builder.build();
    }
}
