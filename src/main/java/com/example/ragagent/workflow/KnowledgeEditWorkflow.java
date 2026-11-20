package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordExtractor;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.CrossEncoderReranker;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 자연어 지식 수정의 앱 안 워크플로(AGENT_MCP_DESIGN §10.6, 6c) — "A 기능은 더 이상 사용되지 않습니다" 한 줄로 관련 청크를 찾고, 고칠 곳을 고르고,
 * before/after 수정안을 만든다. <b>코퍼스에는 아무것도 쓰지 않는다</b> — 결과는 {@code change_proposal} 의 {@code pending} 행과 리포트뿐이고,
 * 저장·재임베딩·색인·변환 MD 반영은 관리자가 문서 단위로 승인한 뒤의 {@link ChangeApplyService} 가 한다.
 *
 * <p><b>고정된 순서(D8 — 자유 루프가 아니다).</b> ① 지시 해석(LLM 1회, 값은 지시문의 부분 문자열이어야 한다 — {@link EditIntent}) → ③ 후보(전수 조회 ∪
 * 하이브리드 검색, 교차 인코더 점수) → ④ 청크별 판정(설명함·언급만·무관 + 근거 인용, 인용은 코드가 확인) → ⑤ 유형별 대상 선정 → ⑥ 편집 작업(앵커 편집,
 * 넣는 문장은 LLM 이 주변 글에 맞춰 다듬고 G10 으로 사실을 대조 — 어기면 지시문 문장 그대로) → ⑦ 가드 → ⑧ 맥락·키워드 재생성(지우기·고치기) → ⑩ 저장.
 * {@code replace}(A → B) 로 해석되면 기존 글자 치환 워크플로({@link MaintenanceWorkflow})로 넘긴다.
 *
 * <p>호출 지점은 새 사이트를 만들지 않는다 — 해석은 {@code CONDENSE}, 판정·편집은 {@code RERANK}(영향도 분석과 같다). 사용자당 동시 1건, 가상
 * 스레드, 호출마다 로컬 LLM 의 대화형 게이트가 비기를 기다린다(상한 30초).
 */
@Service
public class KnowledgeEditWorkflow {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEditWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String VERSION = "latest";
    static final String MODE_NL = "nl";
    static final String ROLE_DESCRIBES = "describes";
    static final String ROLE_MENTIONS = "mentions";
    static final String ROLE_UNRELATED = "unrelated";

    static final int MENTION_LIMIT = 200;
    /** 판정 상한 — 넘는 후보는 언급 목록으로만(영향도와 같다). */
    static final int MAX_JUDGED = 40;
    /** 판정·편집에 보여 줄 청크 글자 상한. */
    static final int MAX_CHUNK_CHARS = 3_000;
    /** {@code add_note} 의 추가 적용 후보 수(확정 2026-11-17). */
    static final int MAX_EXTRA_CANDIDATES = 5;
    /**
     * 지우기가 청크 삭제로 바뀌는 조건 — 남은 본문(제목 줄·그림/페이지 표지·펜스 줄·공백 제외)이 <b>한 글자도 없을 때만</b>(2026-11-17 결정). 글자가 하나라도
     * 남으면 늘 문장 수정(남은 본문을 그대로 둔 {@code edit} 수정안)이다. 설계의 40자 문턱은 G3 을 지나 남은 무관한 문장까지 청크째 지웠고(테스트가 잡았다),
     * 그 뒤의 10자도 "주의!" 같은 짧은 문장을 지웠다 — 무엇을 남길지는 관리자가 Diff 로 정한다.
     */
    static final int MIN_REMAINING_CHARS = 1;
    static final int INTENT_OUTPUT_TOKENS = 256;
    static final int JUDGE_OUTPUT_TOKENS = 512;
    static final int EDIT_OUTPUT_TOKENS = 1_024;

    private static final Pattern MARKER_OR_HEADING_LINE = Pattern.compile("(?m)^\\s*(#{1,6}\\s.*|\\[이미지.*]|\\[페이지:\\s*\\d+]|```.*)\\s*$");

    // ── 리포트 ──────────────────────────────────────────────────────────────

    /** 판정 하나 — 대상으로 고르지 않은 청크도 리포트에 남는다. */
    public record Judged(String chunkId, String docId, String file, String role, String quote, Double score) {}

    /** 버린 것 — 파일·청크·사유. */
    public record Discarded(String file, String chunkId, String reason) {}

    /**
     * @param mode            항상 {@code nl} — 저장된 리포트가 어느 워크플로의 것인지 화면이 가른다
     * @param proposals       만든 수정안 수
     * @param mentionsOnly    언급만 하는 청크(수정안 없음 — 화면에서 하나씩 더할 수 있다)
     * @param extraCandidates {@code add_note} 의 추가 적용 후보(점수순, 최대 5)
     * @param fallbacks       다듬은 문장이 G10 을 어겨 지시문 문장 그대로 넣은 수
     */
    public record Report(String mode, String instruction, String type, String subject, String content, String replacement,
                         int mentions, boolean truncated, boolean tooShort, int candidates, int judged, int unjudged,
                         int proposals, int manualCurated, int fallbacks,
                         List<Judged> mentionsOnly, List<Judged> extraCandidates, List<Discarded> discarded) {}

    public record Started(long jobId, String status) {}

    /** 한 청크의 편집 결과 — 수정안을 만들었으면 {@code proposal}, 아니면 {@code discarded}. */
    record Built(ChangeProposalRepository.NewProposal proposal, Discarded discarded, boolean fallback) {}

    private final KeywordSearchRepository keywords;
    private final HybridSearchService hybrid;
    private final Optional<CrossEncoderReranker> reranker;
    private final AdminService adminService;
    private final LlmRouter llmRouter;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final MaintenanceWorkflow maintenance;
    private final MessageSource messages;
    private final AppProperties props;
    private final Duration poll;
    private final Duration maxWait;
    private KeywordExtractor keywordExtractor;

    @Autowired
    public KnowledgeEditWorkflow(KeywordSearchRepository keywords, HybridSearchService hybrid, Optional<CrossEncoderReranker> reranker,
                                 AdminService adminService, LlmRouter llmRouter, ChangeJobRepository jobs,
                                 ChangeProposalRepository proposals, MaintenanceWorkflow maintenance, MessageSource messages,
                                 AppProperties props) {
        this(keywords, hybrid, reranker, adminService, llmRouter, jobs, proposals, maintenance, messages, props,
                Duration.ofMillis(250), Duration.ofSeconds(30));
    }

    /** 테스트용 — 채팅 양보 대기를 줄인다. */
    KnowledgeEditWorkflow(KeywordSearchRepository keywords, HybridSearchService hybrid, Optional<CrossEncoderReranker> reranker,
                          AdminService adminService, LlmRouter llmRouter, ChangeJobRepository jobs,
                          ChangeProposalRepository proposals, MaintenanceWorkflow maintenance, MessageSource messages,
                          AppProperties props, Duration poll, Duration maxWait) {
        this.keywords = keywords;
        this.hybrid = hybrid;
        this.reranker = reranker == null ? Optional.empty() : reranker;
        this.adminService = adminService;
        this.llmRouter = llmRouter;
        this.jobs = jobs;
        this.proposals = proposals;
        this.maintenance = maintenance;
        this.messages = messages;
        this.props = props;
        this.poll = poll;
        this.maxWait = maxWait;
    }

    /** 맥락·키워드 재생성(⑧) — 세터 주입(없으면 옛 값을 그대로 둔다). */
    @Autowired(required = false)
    public void setKeywordExtractor(KeywordExtractor keywordExtractor) {
        this.keywordExtractor = keywordExtractor;
    }

    // ── 시작 ────────────────────────────────────────────────────────────────

    /**
     * 작업을 만들고 가상 스레드에서 돌린다.
     *
     * @param intent 사람이 직접 고른 의도(폼·MCP·채팅에서 확인한 해석) — {@code null} 이면 지시문을 LLM 으로 해석한다
     * @throws IllegalStateException    이 사용자의 작업이 이미 돌고 있을 때
     * @throws IllegalArgumentException 지시문도 의도도 없거나, 준 의도의 모양이 틀렸을 때
     */
    public Started start(String userId, String instruction, EditIntent intent, Locale locale) {
        String text = instruction == null ? "" : instruction.strip();
        if (text.isEmpty() && intent == null) throw new IllegalArgumentException("지시문이 필요합니다");
        if (intent != null) {
            String problem = intent.shapeProblem();
            if (problem != null) throw new IllegalArgumentException(problem);
            if (text.isEmpty()) text = describe(intent);
        }
        if (jobs.countRunning(userId) > 0) throw new IllegalStateException("이미 진행 중인 작업이 있습니다");
        long id = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, text, userId);
        String finalText = text;
        Thread.ofVirtual().name("knowledge-edit-" + id).start(MdcPropagation.wrap(() -> run(id, finalText, intent, locale)));
        return new Started(id, ChangeJobRepository.RUNNING);
    }

    /**
     * 청크 오류 신고에서 시작한다(6d, AGENT_MCP_DESIGN §10.12) — 지시문은 관리자가 신고 코멘트로 채워 고친 것이고, <b>대상은 그 청크 하나로 고정</b>된다:
     * 후보 찾기(전수 조회·하이브리드)와 판정을 건너뛰고 그 청크에만 편집 단계(⑥~⑩)를 돌린다. 글자 바꾸기로 해석되면 그 청크에만 글자 치환을 한다.
     * 작업에 청크가 남아({@code change_job.report_chunk_id}) 그 수정안이 적용되면 {@link ChangeApplyService} 가 신고를 해결로 닫는다.
     *
     * @param intent 사람이 고른 의도 — {@code null} 이면 지시문을 LLM 으로 해석한다
     * @throws IllegalStateException    이 사용자의 작업이 이미 돌고 있을 때
     * @throws IllegalArgumentException 지시문도 의도도 없거나, 의도의 모양이 틀렸거나, 청크가 없거나 큐레이션 청크일 때
     */
    public Started startFromReport(String userId, String chunkId, String instruction, EditIntent intent, Locale locale) {
        if (chunkId == null || chunkId.isBlank()) throw new IllegalArgumentException("신고된 청크가 필요합니다");
        String text = instruction == null ? "" : instruction.strip();
        if (text.isEmpty() && intent == null) throw new IllegalArgumentException("지시문이 필요합니다");
        if (intent != null) {
            String problem = intent.shapeProblem();
            if (problem != null) throw new IllegalArgumentException(problem);
            if (text.isEmpty()) text = describe(intent);
        }
        AdminService.ChunkRow row = adminService.getChunk(adminService.collectionFor(VERSION), chunkId);
        if (row == null) throw new IllegalArgumentException("신고된 청크가 지금 문서에 없습니다(삭제·재인덱싱): " + chunkId);
        if (row.isCurated()) throw new IllegalArgumentException("큐레이션 Q&A 청크는 지식 제안 편집에서 고칩니다");
        if (jobs.countRunning(userId) > 0) throw new IllegalStateException("이미 진행 중인 작업이 있습니다");
        long id = jobs.createForReport(ChangeJobRepository.KIND_MAINTENANCE, text, userId, chunkId);
        String finalText = text;
        Thread.ofVirtual().name("knowledge-edit-report-" + id)
                .start(MdcPropagation.wrap(() -> runForReport(id, finalText, intent, chunkId, locale)));
        return new Started(id, ChangeJobRepository.RUNNING);
    }

    /** 신고에서 시작한 작업의 몸체 — 테스트의 입구. 어떤 실패도 {@code failed} 로 남는다. */
    void runForReport(long jobId, String instruction, EditIntent given, String chunkId, Locale locale) {
        try {
            EditIntent intent = given != null ? given : interpret(instruction, locale);
            if (intent.isReplace()) {
                jobs.setIntent(jobId, intent.toJson("literal"));
                maintenance.proposeAndStoreForChunk(jobId, instruction, intent.from(), intent.to(), chunkId);
                return;
            }
            jobs.setIntent(jobId, intent.toJson(MODE_NL));
            Report report = proposeForReportedChunk(jobId, instruction, intent, chunkId, locale);
            jobs.finish(jobId, ChangeJobRepository.DONE, MAPPER.writeValueAsString(report), null);
            log.info("[KNOWLEDGE-EDIT] 신고 청크 완료 job={} type={} chunk={} 수정안={}", jobId, intent.type(), chunkId, report.proposals());
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 신고 청크 실패 job={}: {}", jobId, e.getMessage());
            jobs.finish(jobId, ChangeJobRepository.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * 그 청크 하나에만 ⑥~⑩ — 판정을 하지 않으므로 대상 역할은 {@code describes}(관리자가 신고를 보고 고른 청크다), 근거 인용은 없다. 리포트의 후보는 1,
     * 판정은 0 이다.
     */
    Report proposeForReportedChunk(long jobId, String instruction, EditIntent intent, String chunkId, Locale locale) {
        String collection = adminService.collectionFor(VERSION);
        AdminService.ChunkRow row = adminService.getChunk(collection, chunkId);
        if (row == null || row.isCurated()) throw new IllegalArgumentException("신고된 청크를 읽지 못했습니다: " + chunkId);
        Built b = build(jobId, instruction, intent, collection, new Judged(chunkId, row.docId(), row.filename(), ROLE_DESCRIBES, "", null), locale);
        List<ChangeProposalRepository.NewProposal> out = b.proposal() == null ? List.of() : List.of(b.proposal());
        proposals.insertAll(out);
        return new Report(MODE_NL, instruction, intent.type(), intent.subject(), intent.content(), intent.replacement(),
                0, false, false, 1, 0, 0, out.size(), 0, b.fallback() ? 1 : 0,
                List.of(), List.of(), b.discarded() == null ? List.of() : List.of(b.discarded()));
    }

    /** 작업 몸체 — 테스트의 입구. 어떤 실패도 {@code failed} 로 남는다. */
    void run(long jobId, String instruction, EditIntent given, Locale locale) {
        try {
            EditIntent intent = given != null ? given : interpret(instruction, locale);
            if (intent.isReplace()) {
                jobs.setIntent(jobId, intent.toJson("literal"));
                maintenance.proposeAndStore(jobId, instruction, intent.from(), intent.to(), locale);
                return;
            }
            jobs.setIntent(jobId, intent.toJson(MODE_NL));
            Report report = propose(jobId, instruction, intent, locale);
            jobs.finish(jobId, ChangeJobRepository.DONE, MAPPER.writeValueAsString(report), null);
            log.info("[KNOWLEDGE-EDIT] 완료 job={} type={} 후보={} 판정={} 수정안={}", jobId, intent.type(), report.candidates(),
                    report.judged(), report.proposals());
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 실패 job={}: {}", jobId, e.getMessage());
            jobs.finish(jobId, ChangeJobRepository.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    /**
     * ① 지시 해석 — LLM 이 JSON 으로 낸 의도를 {@link EditIntent#groundingProblem} 으로 거른다. 못 읽거나 지시문에 없는 값을 내면 예외(추측으로 실행하지
     * 않는다). 채팅의 되묻기도 이것을 부른다.
     */
    public EditIntent interpret(String instruction, Locale locale) {
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("지시문이 필요합니다");
        String system = messages.getMessage("prompt.edit.intent", null, locale);
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                ThinkingSite.CONDENSE.taskType(), ThinkingSite.CONDENSE.fixedRoutingMode(),
                model -> model.call(new Prompt(List.of(new SystemMessage(system),
                        new UserMessage(PromptInjectionGuard.wrap(instruction))), options(ThinkingSite.CONDENSE, INTENT_OUTPUT_TOKENS))));
        EditIntent intent = EditIntent.parse(result.text());
        String problem = intent.groundingProblem(instruction);
        if (problem != null) throw new IllegalArgumentException(problem + " — 화면에서 유형과 대상을 직접 고르세요");
        return intent;
    }

    // ── 수정안 만들기 ────────────────────────────────────────────────────────

    Report propose(long jobId, String instruction, EditIntent intent, Locale locale) {
        String subject = intent.subject();
        int manual = keywords.findMentions("curated", subject, MENTION_LIMIT).hits().size();
        KeywordSearchRepository.Mentions mentions = keywords.findMentions(VERSION, subject, MENTION_LIMIT);

        // ③ 후보 — 전수 조회(글자 그대로) ∪ 하이브리드 검색(뜻). 저장 원문으로 되돌린다(판정·인용 검증이 사용자가 보는 본문 기준이어야 한다).
        Map<String, Document> pool = new LinkedHashMap<>();
        for (Document d : hybrid.withStoredText(mentions.hits())) pool.putIfAbsent(d.getId(), d);
        try {
            int k = props.effectiveRerankCandidates();
            HybridSearchService.SearchResult found = hybrid.search(new HybridSearchService.SearchRequest(
                    "knowledge-edit", VERSION, instruction, instruction, subject, List.of(), Set.of(), Optional.empty(), k, false));
            for (Document d : hybrid.withStoredText(found.candidates())) {
                if (!isCurated(d)) pool.putIfAbsent(d.getId(), d);
            }
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 하이브리드 후보 실패 — 전수 조회만 쓴다: {}", e.getMessage());
        }
        List<Document> candidates = new ArrayList<>(pool.values());
        Map<String, Double> scores = scores(instruction, candidates);
        if (!scores.isEmpty()) {
            candidates.sort(Comparator.comparingDouble((Document d) -> scores.getOrDefault(d.getId(), Double.NEGATIVE_INFINITY)).reversed());
        }

        // ④ 판정
        List<Judged> judged = new ArrayList<>();
        List<Discarded> discarded = new ArrayList<>();
        List<Document> toJudge = candidates.subList(0, Math.min(MAX_JUDGED, candidates.size()));
        for (Document d : toJudge) {
            String text = d.getText() == null ? "" : d.getText();
            boolean literal = text.toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT));
            if (literal && !ImpactAnalysisWorkflow.hasStandaloneOccurrence(text, subject)) {
                judged.add(judgedOf(d, ROLE_UNRELATED, "", scores.get(d.getId())));
                continue;   // 더 긴 식별자의 일부로만 나온다 — 모델에 묻지 않는다
            }
            awaitChatQuiet();
            judged.add(judge(intent, d, scores.get(d.getId()), locale));
        }

        // ⑤ 대상 선정
        List<Judged> describes = judged.stream().filter(j -> ROLE_DESCRIBES.equals(j.role())).toList();
        List<Judged> targets = new ArrayList<>();
        List<Judged> extra = new ArrayList<>();
        switch (intent.type()) {
            case EditIntent.DEPRECATE -> targets.addAll(firstPerSection(describes, pool));
            case EditIntent.ADD_NOTE -> {
                if (!describes.isEmpty()) targets.add(describes.get(0));
                extra.addAll(describes.subList(Math.min(1, describes.size()), Math.min(1 + MAX_EXTRA_CANDIDATES, describes.size())));
            }
            default -> targets.addAll(describes);
        }

        // ⑥~⑩ 편집 작업 · 가드 · 맥락 · 저장
        List<ChangeProposalRepository.NewProposal> out = new ArrayList<>();
        int fallbacks = 0;
        String collection = adminService.collectionFor(VERSION);
        for (Judged t : targets) {
            Built b = build(jobId, instruction, intent, collection, t, locale);
            if (b.proposal() != null) out.add(b.proposal());
            if (b.discarded() != null) discarded.add(b.discarded());
            if (b.fallback()) fallbacks++;
        }
        proposals.insertAll(out);
        List<Judged> mentionsOnly = judged.stream().filter(j -> ROLE_MENTIONS.equals(j.role())).toList();
        return new Report(MODE_NL, instruction, intent.type(), subject, intent.content(), intent.replacement(),
                mentions.hits().size(), mentions.truncated(), mentions.tooShort(), candidates.size(), judged.size(),
                candidates.size() - toJudge.size(), out.size(), manual, fallbacks,
                List.copyOf(mentionsOnly), List.copyOf(extra), List.copyOf(discarded));
    }

    /**
     * 화면에서 고른 청크(추가 적용 후보·언급만)로 수정안을 더한다 — 작업의 저장된 의도로 ⑥~⑩만 다시 돌린다.
     *
     * @return 만든 수정안 수
     */
    public int proposeForChunks(long jobId, List<String> chunkIds, Locale locale) {
        ChangeJobRepository.Job job = jobs.find(jobId)
                .filter(j -> ChangeJobRepository.KIND_MAINTENANCE.equals(j.kind()))
                .orElseThrow(() -> new IllegalArgumentException("유지보수 작업을 찾을 수 없습니다: " + jobId));
        if (job.fromReport()) throw new IllegalArgumentException("신고에서 시작한 작업은 대상이 그 청크로 고정됩니다: " + jobId);
        EditIntent intent = EditIntent.fromJson(job.intentJson());
        if (intent == null || intent.isReplace() || intent.subject() == null) {
            throw new IllegalArgumentException("자연어 수정 작업이 아닙니다: " + jobId);
        }
        Set<String> already = new java.util.HashSet<>();
        for (ChangeProposalRepository.Proposal p : proposals.listByJob(jobId)) already.add(p.chunkId());
        String collection = adminService.collectionFor(VERSION);
        List<ChangeProposalRepository.NewProposal> out = new ArrayList<>();
        for (String id : chunkIds == null ? List.<String>of() : chunkIds) {
            if (id == null || already.contains(id)) continue;
            AdminService.ChunkRow row = adminService.getChunk(collection, id);
            if (row == null || row.isCurated()) continue;
            Judged t = new Judged(id, row.docId(), row.filename(), ROLE_DESCRIBES, "", null);
            Built b = build(jobId, job.requestText(), intent, collection, t, locale);
            if (b.proposal() != null) {
                out.add(b.proposal());
                already.add(id);
            }
        }
        proposals.insertAll(out);
        return out.size();
    }

    /** ⑥~⑧ 청크 하나의 수정안. */
    Built build(long jobId, String instruction, EditIntent intent, String collection, Judged target, Locale locale) {
        AdminService.ChunkRow row = adminService.getChunk(collection, target.chunkId());
        if (row == null || row.isCurated()) return new Built(null, new Discarded(target.file(), target.chunkId(), "청크를 읽지 못했다"), false);
        String before = row.fullText() == null ? "" : row.fullText();
        try {
            awaitChatQuiet();
            Edit edit = switch (intent.type()) {
                case EditIntent.DEPRECATE -> deprecate(before, intent, locale);
                case EditIntent.ADD_NOTE -> addNote(before, intent, locale);
                case EditIntent.REMOVE -> removeOrRevise(before, intent, instruction, true, locale);
                case EditIntent.REVISE -> removeOrRevise(before, intent, instruction, false, locale);
                default -> Edit.skip("지원하지 않는 유형");
            };
            if (edit.reason() != null) return new Built(null, new Discarded(row.filename(), row.id(), edit.reason()), false);

            String kind = edit.deleteChunk() ? ChangeProposalRepository.KIND_DELETE_CHUNK : ChangeProposalRepository.KIND_EDIT;
            String after = edit.deleteChunk() ? "" : edit.after();
            String ctxBefore = row.metadata().get(MetaKey.CHUNK_CONTEXT);
            String kwBefore = row.metadata().get(MetaKey.EXCERPT_KEYWORDS);
            String ctxAfter = null;
            String kwAfter = null;
            if (!edit.deleteChunk() && (EditIntent.REMOVE.equals(intent.type()) || EditIntent.REVISE.equals(intent.type()))) {
                String[] regenerated = regenerateContext(after, row);
                if (regenerated != null) { ctxAfter = regenerated[0]; kwAfter = regenerated[1]; }
            }
            String rationale = rationale(intent, edit);
            return new Built(new ChangeProposalRepository.NewProposal(jobId, row.docId(), row.filename(), row.id(), kind,
                    before, after, TextHash.sha256(before), ctxAfter == null ? null : ctxBefore, ctxAfter,
                    kwAfter == null ? null : kwBefore, kwAfter, rationale, edit.opsJson(), target.role(),
                    target.quote() == null || target.quote().isBlank() ? null : target.quote(), null), null, edit.fallback());
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 편집 실패 chunk={}: {}", row.id(), e.getMessage());
            return new Built(null, new Discarded(row.filename(), row.id(), "편집 호출 실패"), false);
        }
    }

    /** 편집 결과 — {@code reason} 이 있으면 만들지 않았다. */
    record Edit(String after, boolean deleteChunk, String opsJson, boolean fallback, String note, String reason) {
        static Edit skip(String why) { return new Edit(null, false, null, false, null, why); }
    }

    /** 사용 중단 — 위치는 코드가 정하고(대상 이름이 든 제목 아래 · 없으면 대상이 처음 나오는 블록 앞), 문장은 LLM 이 다듬고 G10 으로 대조한다. 원문은 지우지 않는다. */
    Edit deprecate(String before, EditIntent intent, Locale locale) {
        String reference = intent.replacement() == null
                ? message("edit.sentence.deprecated", locale, intent.subject())
                : message("edit.sentence.deprecated-with", locale, intent.subject(), intent.replacement());
        int at = deprecationPosition(before, intent.subject());
        String context = around(before, at);
        Polished polished = polish(reference, context, intent, locale, false);
        String block = message("edit.block.deprecated", locale, polished.sentence());
        if (AnchoredEdit.normalize(before).contains(AnchoredEdit.normalize(block))
                || AnchoredEdit.normalize(before).contains(AnchoredEdit.normalize(polished.sentence()))) {
            return Edit.skip("같은 안내가 이미 있다");
        }
        String after = insertAt(before, at, block);
        String invariant = MaintenanceWorkflow.invariantViolation(before, after);
        if (invariant != null) return Edit.skip(invariant);
        String ops = MAPPER.writeValueAsString(List.of(Map.of("op", "INSERT", "at", at, "text", block)));
        return new Edit(after, false, ops, polished.fallback(), polished.fallback() ? polished.why() : null, null);
    }

    /** 주의 사항 추가 — 모델이 위치(인용)와 다듬은 문장을 내고, 위치를 못 찾으면 청크 끝에 넣는다. */
    Edit addNote(String before, EditIntent intent, Locale locale) {
        String reference = intent.content();
        Polished polished = polish(reference, shown(before), intent, locale, true);
        String block = message("edit.block.note", locale, polished.sentence());
        if (AnchoredEdit.normalize(before).contains(AnchoredEdit.normalize(polished.sentence()))) return Edit.skip("같은 주의 사항이 이미 있다");
        AnchoredEdit.Result r = polished.anchor() == null ? null
                : AnchoredEdit.apply(before, List.of(AnchoredEdit.Operation.insertAfter(polished.anchor(), block)));
        String after;
        String ops;
        if (r != null && r.usable()) {
            after = r.text();
            ops = MAPPER.writeValueAsString(r.applied());
        } else {
            after = insertAt(before, before.length(), block);
            ops = MAPPER.writeValueAsString(List.of(Map.of("op", "INSERT", "at", "end", "text", block)));
        }
        String invariant = MaintenanceWorkflow.invariantViolation(before, after);
        if (invariant != null) return Edit.skip(invariant);
        return new Edit(after, false, ops, polished.fallback(), polished.fallback() ? polished.why() : null, null);
    }

    /** 내용 삭제 · 설명 수정 — 모델이 앵커 편집을 내고, 코드가 G3(관련성)·G4(새 사실, 고치기만)와 {@link AnchoredEdit} 의 가드로 거른다. */
    Edit removeOrRevise(String before, EditIntent intent, String instruction, boolean remove, Locale locale) {
        String key = remove ? "prompt.edit.ops.remove" : "prompt.edit.ops.revise";
        String system = messages.getMessage(key, null, locale)
                .replace("{subject}", intent.subject())
                .replace("{content}", intent.content() == null ? "-" : intent.content())
                .replace("{instruction}", instruction == null ? "" : instruction.strip());
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                ThinkingSite.RERANK.taskType(), ThinkingSite.RERANK.fixedRoutingMode(),
                model -> model.call(new Prompt(List.of(new SystemMessage(system),
                        new UserMessage(PromptInjectionGuard.wrap(shown(before)))), options(ThinkingSite.RERANK, EDIT_OUTPUT_TOKENS))));
        ParsedOps parsed = parseOps(result.text());
        if (parsed.verdict() == Verdict.NONE) return Edit.skip("모델이 바꿀 곳이 없다고 판단했다" + reasonSuffix(parsed.reason()));
        if (parsed.verdict() == Verdict.UNREADABLE) return Edit.skip("편집 응답을 읽지 못했다");
        if (remove && parsed.verdict() == Verdict.DELETE_CHUNK) {
            if (EditGuards.wholeChunkBelongsTo(before, intent.subject())) {
                return new Edit("", true, "[{\"op\":\"DELETE_CHUNK\"}]", false, null, null);
            }
            return Edit.skip("청크 전체 삭제를 제안했지만 청크의 본문이 전부 대상 섹션 안에 있지 않다");
        }
        List<AnchoredEdit.Operation> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (AnchoredEdit.Operation op : parsed.ops()) {
            if (remove && op.op() != AnchoredEdit.Op.DELETE && op.op() != AnchoredEdit.Op.DELETE_SECTION) {
                dropped.add("삭제 요청인데 지우기 외의 작업"); continue;
            }
            if (!remove && op.op() != AnchoredEdit.Op.REPLACE) { dropped.add("고치기 요청인데 바꾸기 외의 작업"); continue; }
            String g3 = EditGuards.relevance(before, op, intent.subject());
            if (g3 != null) { dropped.add(g3); continue; }
            if (!remove) {
                String g4 = EditGuards.newFacts(before, op.text(), instruction);
                if (g4 != null) { dropped.add(g4); continue; }
            }
            kept.add(op);
        }
        if (kept.isEmpty()) return Edit.skip(dropped.isEmpty() ? "편집 작업이 없다" : "가드 위반 — " + String.join(" · ", dropped));
        AnchoredEdit.Result r = AnchoredEdit.apply(before, kept);
        if (!r.usable()) {
            List<String> why = new ArrayList<>(dropped);
            for (AnchoredEdit.Rejected x : r.rejected()) why.add(x.reason());
            if (r.problem() != null) why.add(r.problem());
            return Edit.skip("가드 위반 — " + String.join(" · ", why));
        }
        if (remove && remainingBody(r.text()) < MIN_REMAINING_CHARS) {
            return new Edit("", true, MAPPER.writeValueAsString(r.applied()), false, "제목·표지 말고 남은 본문이 없어 청크 삭제로 바꿨다", null);
        }
        String note = dropped.isEmpty() && r.rejected().isEmpty() ? null
                : "일부 작업을 버림 — " + String.join(" · ", dropped) + (r.rejected().isEmpty() ? "" : " · " + r.rejected().size() + "개 인용 실패");
        return new Edit(r.text(), false, MAPPER.writeValueAsString(r.applied()), false, note, null);
    }

    // ── LLM 호출과 응답 읽기 ─────────────────────────────────────────────────

    private Judged judge(EditIntent intent, Document chunk, Double score, Locale locale) {
        String text = chunk.getText() == null ? "" : chunk.getText();
        try {
            String system = messages.getMessage("prompt.edit.judge", null, locale)
                    .replace("{subject}", intent.subject())
                    .replace("{type}", message("edit.type." + intent.type(), locale));
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.RERANK.taskType(), ThinkingSite.RERANK.fixedRoutingMode(),
                    model -> model.call(new Prompt(List.of(new SystemMessage(system),
                            new UserMessage(PromptInjectionGuard.wrap(shown(text)))), options(ThinkingSite.RERANK, JUDGE_OUTPUT_TOKENS))));
            return parseJudgement(result.text(), text, chunk, score);
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 판정 실패 chunk={}: {}", chunk.getId(), e.getMessage());
            return judgedOf(chunk, ROLE_UNRELATED, "", score);
        }
    }

    /** 판정 JSON — 설명함은 인용이 원문에 있을 때만 인정한다(없으면 언급만으로 내린다: 수정안을 만들지 않고 목록에 남긴다). */
    static Judged parseJudgement(String response, String chunkText, Document chunk, Double score) {
        int a = response == null ? -1 : response.indexOf('{');
        int b = response == null ? -1 : response.lastIndexOf('}');
        if (a < 0 || b <= a) return judgedOf(chunk, ROLE_UNRELATED, "", score);
        try {
            JsonNode j = MAPPER.readTree(response.substring(a, b + 1));
            String role = j.path("role").asString("").strip().toLowerCase(Locale.ROOT);
            String quote = j.path("quote").asString("").strip();
            boolean verified = !quote.isEmpty() && EditIntent.norm(chunkText).contains(EditIntent.norm(quote));
            if (ROLE_DESCRIBES.equals(role)) return judgedOf(chunk, verified ? ROLE_DESCRIBES : ROLE_MENTIONS, verified ? quote : "", score);
            if (ROLE_MENTIONS.equals(role)) return judgedOf(chunk, ROLE_MENTIONS, verified ? quote : "", score);
            return judgedOf(chunk, ROLE_UNRELATED, "", score);
        } catch (RuntimeException e) {
            return judgedOf(chunk, ROLE_UNRELATED, "", score);
        }
    }

    /** 다듬은 문장 — 통과 못 하면 기준 문장 그대로({@code fallback}). {@code anchor} 는 {@code add_note} 의 위치 인용. */
    record Polished(String sentence, String anchor, boolean fallback, String why) {}

    Polished polish(String reference, String context, EditIntent intent, Locale locale, boolean wantAnchor) {
        try {
            String system = messages.getMessage(wantAnchor ? "prompt.edit.insert.note" : "prompt.edit.insert.deprecated", null, locale)
                    .replace("{subject}", intent.subject())
                    .replace("{reference}", reference);
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.RERANK.taskType(), ThinkingSite.RERANK.fixedRoutingMode(),
                    model -> model.call(new Prompt(List.of(new SystemMessage(system),
                            new UserMessage(PromptInjectionGuard.wrap(context))), options(ThinkingSite.RERANK, JUDGE_OUTPUT_TOKENS))));
            Map<String, String> fields = parseFields(result.text());
            String sentence = fields.get("글");
            String anchor = fields.get("뒤");
            String g10 = EditGuards.sentence(sentence, reference, context, intent.subject());
            if (g10 != null) return new Polished(reference, anchor, true, g10);
            return new Polished(sentence.strip(), anchor, false, null);
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 문장 다듬기 실패 — 지시문 문장 그대로: {}", e.getMessage());
            return new Polished(reference, null, true, "다듬기 호출 실패");
        }
    }

    enum Verdict { CHANGE, NONE, DELETE_CHUNK, UNREADABLE }

    record ParsedOps(Verdict verdict, String reason, List<AnchoredEdit.Operation> ops) {}

    /**
     * 편집 응답(줄 형식 — 본문에 따옴표·줄바꿈·코드가 많아 JSON 이 아니다): {@code 판정:} · {@code 이유:} · {@code @@ 바꾸기}({@code 원문:}·{@code 새글:}) ·
     * {@code @@ 지우기}({@code 처음:}·{@code 끝:}) · {@code @@ 섹션 지우기}({@code 제목:}). 값은 다음 키 줄이나 {@code @@} 줄 전까지 여러 줄일 수 있다.
     */
    static ParsedOps parseOps(String response) {
        if (response == null || response.isBlank()) return new ParsedOps(Verdict.UNREADABLE, "", List.of());
        Verdict verdict = Verdict.UNREADABLE;
        String reason = "";
        List<AnchoredEdit.Operation> ops = new ArrayList<>();
        String section = null;
        Map<String, StringBuilder> cur = new LinkedHashMap<>();
        String lastKey = null;
        for (String line : (response + "\n@@ 끝").split("\n", -1)) {
            String s = line.strip();
            if (s.startsWith("@@")) {
                if (section != null) {
                    AnchoredEdit.Operation op = toOp(section, cur);
                    if (op != null) ops.add(op);
                }
                section = s.substring(2).strip();
                cur = new LinkedHashMap<>();
                lastKey = null;
                continue;
            }
            int colon = s.indexOf(':');
            String key = colon > 0 ? s.substring(0, colon).strip() : null;
            if (section == null && "판정".equals(key)) {
                String v = s.substring(colon + 1).strip();
                if (v.contains("전체 삭제") || v.contains("청크 삭제")) verdict = Verdict.DELETE_CHUNK;
                else if (v.contains("없음")) verdict = Verdict.NONE;
                else if (v.contains("필요")) verdict = Verdict.CHANGE;
                continue;
            }
            if (section == null && "이유".equals(key)) { reason = s.substring(colon + 1).strip(); continue; }
            if (section != null && key != null && Set.of("원문", "새글", "처음", "끝", "제목").contains(key)) {
                cur.put(key, new StringBuilder(line.substring(line.indexOf(':') + 1).strip()));
                lastKey = key;
                continue;
            }
            if (section != null && lastKey != null) cur.get(lastKey).append('\n').append(line);
        }
        if (verdict == Verdict.CHANGE && ops.isEmpty()) verdict = Verdict.UNREADABLE;
        return new ParsedOps(verdict, reason, List.copyOf(ops));
    }

    private static AnchoredEdit.Operation toOp(String section, Map<String, StringBuilder> f) {
        java.util.function.Function<String, String> v = k -> f.get(k) == null ? null : f.get(k).toString().strip();
        if (section.startsWith("섹션")) return v.apply("제목") == null ? null : AnchoredEdit.Operation.deleteSection(v.apply("제목"));
        if (section.startsWith("바꾸")) {
            return v.apply("원문") == null || v.apply("새글") == null ? null : AnchoredEdit.Operation.replace(v.apply("원문"), v.apply("새글"));
        }
        if (section.startsWith("지우")) return v.apply("처음") == null ? null : AnchoredEdit.Operation.delete(v.apply("처음"), v.apply("끝"));
        return null;
    }

    /** {@code 키: 값} 줄들을 읽는다(넣기 응답). */
    static Map<String, String> parseFields(String response) {
        Map<String, String> out = new HashMap<>();
        if (response == null) return out;
        for (String line : response.split("\n")) {
            String s = line.strip();
            int colon = s.indexOf(':');
            if (colon <= 0) continue;
            String key = s.substring(0, colon).strip();
            if (key.equals("글") || key.equals("뒤")) out.putIfAbsent(key, s.substring(colon + 1).strip());
        }
        return out;
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 교차 인코더 점수(있으면) — 없거나 실패하면 빈 맵(후보 순서 그대로). */
    private Map<String, Double> scores(String query, List<Document> candidates) {
        Map<String, Double> out = new HashMap<>();
        if (reranker.isEmpty() || candidates.isEmpty()) return out;
        try {
            for (CrossEncoderReranker.Ranked r : reranker.get().rerank(query, candidates, candidates.size())) out.put(r.doc().getId(), r.score());
        } catch (Exception e) {
            log.warn("[KNOWLEDGE-EDIT] 리랭크 실패 — 점수 없이 진행: {}", e.getMessage());
        }
        return out;
    }

    /** 사용 중단 안내는 (문서, 섹션 제목)마다 가장 앞 청크 하나에만 — 같은 섹션의 청크마다 넣으면 재조립된 문서에 안내가 여러 번 나온다. */
    private List<Judged> firstPerSection(List<Judged> describes, Map<String, Document> pool) {
        Map<String, Judged> best = new LinkedHashMap<>();
        Map<String, Integer> bestIndex = new HashMap<>();
        for (Judged j : describes) {
            Document d = pool.get(j.chunkId());
            Map<String, Object> m = d == null ? Map.of() : d.getMetadata();
            String key = j.docId() + "\u0000" + m.getOrDefault(MetaKey.HEADING, "");
            int idx = parseInt(m.get(MetaKey.CHUNK_INDEX));
            if (!best.containsKey(key) || idx < bestIndex.get(key)) {
                best.put(key, j);
                bestIndex.put(key, idx);
            }
        }
        return new ArrayList<>(best.values());
    }

    /**
     * 사용 중단 안내를 넣을 자리 — ① 대상 이름이 (단어 경계에서) 든 첫 제목 줄의 끝, ② 없으면 대상이 처음 나오는 블록의 시작
     * (코드 펜스 안이면 그 펜스의 여는 줄 앞), ③ 청크에 대상이 없으면 첫 제목 줄 끝 또는 맨 앞.
     *
     * <p>예전에는 청크가 제목으로 시작하면 늘 그 첫 제목 아래였다. 청크의 첫 제목은 흔히 위치 표시용 상위 제목
     * ({@code ### 3.4 어플리케이션 호출}, {@code ## 6. 클래스: …ProcessUnit})이라 메서드 하나의 중단이 섹션 전체의 중단처럼 읽혔고,
     * 6c 사전 평가에서 거절된 사용 중단 수정안이 전부 그 모양이었다(KNOWLEDGE_EDIT_EVALUATION §3.3).
     */
    static int deprecationPosition(String text, String subject) {
        String[] lines = text.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 0, off = 0; i < lines.length; off += lines[i].length() + 1, i++) starts[i] = off;

        int firstHeading = -1;
        int occurrence = -1;          // 대상이 처음 나오는 줄(제목 아님)
        int occurrenceFence = -1;     // 그 줄이 펜스 안이면 여는 줄
        boolean inFence = false;
        int fenceOpen = -1;
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            if (s.startsWith("```") || s.startsWith("~~~")) {
                if (!inFence) fenceOpen = i;
                inFence = !inFence;
                continue;
            }
            boolean heading = !inFence && HEADING_LINE.matcher(s).matches();
            boolean has = ImpactAnalysisWorkflow.hasStandaloneOccurrence(lines[i], subject);
            if (heading) {
                if (firstHeading < 0) firstHeading = i;
                if (has) return starts[i] + lines[i].length();
            } else if (has && occurrence < 0) {
                occurrence = i;
                occurrenceFence = inFence ? fenceOpen : -1;
            }
        }
        if (occurrence >= 0) {
            int line = occurrenceFence >= 0 ? occurrenceFence : occurrence;
            // 블록의 시작 — 위로 빈 줄이나 제목 줄을 만날 때까지(펜스 여는 줄이면 그 줄 자체)
            while (occurrenceFence < 0 && line > 0 && !lines[line - 1].isBlank()
                    && !HEADING_LINE.matcher(lines[line - 1].strip()).matches()) line--;
            return starts[line];
        }
        return firstHeading >= 0 && firstHeading == firstNonBlank(lines) ? starts[firstHeading] + lines[firstHeading].length() : 0;
    }

    private static final Pattern HEADING_LINE = Pattern.compile("#{1,6}\\s+\\S.*");

    private static int firstNonBlank(String[] lines) {
        for (int i = 0; i < lines.length; i++) if (!lines[i].isBlank()) return i;
        return -1;
    }

    /** {@code at} 에 블록을 빈 줄로 띄워 넣는다. */
    static String insertAt(String text, int at, String block) {
        String head = text.substring(0, at);
        String tail = text.substring(at);
        String pre = head.isEmpty() || head.endsWith("\n\n") ? "" : head.endsWith("\n") ? "\n" : "\n\n";
        String post = tail.isEmpty() || tail.startsWith("\n\n") ? "" : tail.startsWith("\n") ? "\n" : "\n\n";
        return head + pre + block + post + tail;
    }

    private static String around(String text, int at) {
        int from = Math.max(0, at - 600);
        int to = Math.min(text.length(), at + 600);
        return text.substring(from, to);
    }

    private static String shown(String text) {
        return text.length() > MAX_CHUNK_CHARS ? text.substring(0, MAX_CHUNK_CHARS) : text;
    }

    /** 제목 줄·마커·펜스 줄·공백을 뺀 남은 본문 길이. */
    static int remainingBody(String text) {
        return MARKER_OR_HEADING_LINE.matcher(text).replaceAll("").replaceAll("\\s+", "").length();
    }

    private String[] regenerateContext(String after, AdminService.ChunkRow row) {
        if (keywordExtractor == null) return null;
        try {
            Map<String, Object> meta = new HashMap<>(row.metadata());
            Document enriched = keywordExtractor.enrichSingle(new Document(after, meta));
            Object ctx = enriched.getMetadata().get(MetaKey.CHUNK_CONTEXT);
            Object kw = enriched.getMetadata().get(MetaKey.EXCERPT_KEYWORDS);
            if (ctx == null && kw == null) return null;
            return new String[]{ctx == null ? null : ctx.toString(), kw == null ? null : kw.toString()};
        } catch (Exception e) {
            log.debug("[KNOWLEDGE-EDIT] 맥락 재생성 실패 — 옛 값 유지: {}", e.getMessage());
            return null;
        }
    }

    private String rationale(EditIntent intent, Edit edit) {
        StringBuilder sb = new StringBuilder(switch (intent.type()) {
            case EditIntent.DEPRECATE -> "사용 중단 안내: " + intent.subject();
            case EditIntent.ADD_NOTE -> "주의 사항 추가: " + intent.subject();
            case EditIntent.REMOVE -> edit.deleteChunk() ? "청크 삭제: " + intent.subject() : "내용 삭제: " + intent.subject();
            case EditIntent.REVISE -> "설명 수정: " + intent.subject();
            default -> intent.type();
        });
        if (edit.fallback()) sb.append(" · 다듬은 문장이 검사를 통과하지 못해 지시문 문장을 그대로 넣음");
        if (edit.note() != null) sb.append(" · ").append(edit.note());
        return sb.toString();
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : " (" + reason + ")";
    }

    private static Judged judgedOf(Document d, String role, String quote, Double score) {
        Map<String, Object> m = d.getMetadata();
        String docId = String.valueOf(m.getOrDefault(MetaKey.DOC_ID, ""));
        return new Judged(d.getId(), docId, String.valueOf(m.getOrDefault(MetaKey.FILENAME, docId)), role, quote, score);
    }

    private static boolean isCurated(Document d) {
        return "curated_qa".equals(String.valueOf(d.getMetadata().get(MetaKey.DOC_TYPE)))
                || "curated".equals(String.valueOf(d.getMetadata().get(MetaKey.VERSION)));
    }

    private static int parseInt(Object o) {
        try {
            return o == null ? Integer.MAX_VALUE : Integer.parseInt(o.toString().strip());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private String message(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }

    /** 사람이 고른 의도의 한 줄 설명 — 지시문이 비었을 때 작업 이름. */
    static String describe(EditIntent intent) {
        return switch (intent.type()) {
            case EditIntent.DEPRECATE -> intent.subject() + " 사용 중단";
            case EditIntent.REMOVE -> intent.subject() + " 내용 삭제";
            case EditIntent.ADD_NOTE -> intent.subject() + " 주의 사항: " + intent.content();
            case EditIntent.REVISE -> intent.subject() + " 설명 수정: " + intent.content();
            case EditIntent.REPLACE -> intent.from() + " → " + intent.to();
            default -> intent.type();
        };
    }

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

    private OpenAiChatOptions options(ThinkingSite site, int outputTokens) {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), site);
        int configured = props.llmSafe().maxTokens();
        builder.maxTokens(configured > 0 ? Math.min(configured, outputTokens) : outputTokens);
        return builder.build();
    }
}
