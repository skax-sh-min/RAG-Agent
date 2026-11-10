package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.ChunkDiff;
import com.example.ragagent.service.ChunkImageEditor;
import com.example.ragagent.service.MarkdownCorrectionService;
import com.example.ragagent.web.MdcPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 문서 유지보수 워크플로의 <b>수정안 만들기</b> 단계(§6.33 4b-1, AGENT_MCP_DESIGN §5.5) — "PostgreSQL 을 Oracle 로 바꿔". <b>코퍼스에는 아무것도
 * 쓰지 않는다</b>: 청크 본문·벡터·FTS·변환 MD 모두 그대로이고, 결과는 {@code change_proposal} 대기열({@code pending})과 {@code change_job} 리포트뿐이다.
 * 승인·적용(백업 · 해시 대조 · 임베딩 먼저 · MD 되쓰기)은 이 단계 뒤의 별도 조각이다.
 *
 * <p><b>고정된 순서.</b> ① 바꿀 말({@code from} → {@code to}) 정하기 — 둘 다 받았으면 그대로, 아니면 LLM 이 지시문에서 JSON 으로 뽑는다(추측으로
 * 실행하지 않는다: 못 읽으면 작업이 실패한다) → ② 전수 조회({@link KeywordSearchRepository#findMentions}, 영향도와 같은 것) → ③ 청크마다
 * <b>결정적 치환</b>(코드가 한다 — 본문과 {@code chunk_context}·{@code excerpt_keywords} 에 같은 치환. 검색 텍스트가 {@code chunk_context + 본문}이라
 * 본문만 고치면 옛 말로 계속 검색된다) → ④ <b>불변식 검사</b>(이미지 마커·페이지 마커 보존, 코드 펜스가 새로 깨지지 않음 — 어기면 그 수정안을 버리고
 * 리포트에 사유를 남긴다) → ⑤ 대기열 저장. 의미를 바꿔 써야 하는 청크(LLM 수정안)는 이 조각에 없다.
 *
 * <p>큐레이션 Q&A 는 수정안을 만들지 않는다 — 단일 출처가 {@code curated_qa} 라 청크를 고쳐도 재임베딩 때 되돌아간다. 몇 곳인지만 세어 "수동 처리"로
 * 리포트한다. 대문자·소문자만 다르게 걸린 청크는 글자 그대로 치환할 곳이 없어 건너뛰고 센다. 3글자 미만은 trigram 이 못 찾는다({@code tooShort}).
 *
 * <p><b>의미 변경 수정안(선택, 기본 꺼짐).</b> 글자 치환만으로는 모자란 곳 — 제품이 바뀌면 함께 바뀌는 드라이버 클래스·포트·SQL 문법 — 은
 * LLM 이 <b>치환이 끝난 본문</b>을 받아 함께 바뀌어야 할 부분만 최소로 고친다. 모델의 출력은 믿지 않고 코드가 가드로 거른다
 * ({@link #validateRewrite}): 바꿀 말이 남아 있거나 · 새 말이 줄었거나 · 마커·펜스 불변식을 어기거나 · 길이가 크게 달라졌거나 · 너무 많은 줄이 바뀌었으면
 * 그 시도는 버리고 <b>리터럴 수정안을 그대로</b> 둔다(리포트에 사유). 통과한 것은 {@code semantic} 수정안(화면에 "의미 변경 · 검토 필요")이다.
 * 의미 변경은 변환 MD 에 되쓸 수 없다 — 치환이 아니라 자유 서술이라 MD 의 어느 자리인지 가리킬 수 없으므로, 그 문서는 적용 때
 * {@code MD_PARTIAL} 로 남는다({@link ChangeApplyService}).
 *
 * <p>관리자 전용이다(호출자가 보장한다). 한 사용자당 동시에 한 건만 돈다. 앱이 죽어 {@code running} 으로 남은 작업은 영향도와 같은 훅이
 * {@code interrupted} 로 바꾼다.
 */
@Service
public class MaintenanceWorkflow {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWorkflow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 전수 조회가 읽는 청크의 상한 — 넘으면 리포트가 잘렸다고 밝힌다(일부만 고치면 일관성이 깨지므로 운영자가 알아야 한다). */
    static final int MENTION_LIMIT = 200;
    static final String VERSION = "latest";
    /** 바꿀 말 추출 호출의 출력 예약 — JSON 두 필드. */
    static final int MAX_OUTPUT_TOKENS = 256;
    /** 바꿀 말의 길이 상한 — 이보다 길면 식별자가 아니라 문장이라 리터럴 치환 대상이 아니다. */
    static final int MAX_TERM_CHARS = 200;

    /** 한 작업에서 LLM 이 다시 쓰는 청크의 상한 — 호출이 청크 수만큼 늘기 때문이다. 넘는 청크는 리터럴 수정안만 둔다. */
    static final int MAX_SEMANTIC = 40;
    /** 다시 쓸 청크의 글자 상한 — 이보다 길면 소형 모델의 창·출력 예약에 안 들어가 건너뛴다. */
    static final int MAX_SEMANTIC_CHARS = 3_000;
    /** 다시 쓰기 호출의 출력 예약 상한 — 본문 전체를 다시 내므로 추출 호출(256)보다 훨씬 크다. */
    static final int MAX_REWRITE_OUTPUT_TOKENS = 4_096;
    /** 다시 쓴 본문의 길이가 치환 결과의 이 비율 안이어야 한다 — 요약하거나 부풀린 것은 "최소한으로 고침"이 아니다. */
    static final double MIN_LENGTH_RATIO = 0.6;
    static final double MAX_LENGTH_RATIO = 1.4;
    /** 바뀐(삭제된) 줄이 전체의 이 비율을 넘으면 너무 많이 고친 것이다. */
    static final double MAX_CHANGED_LINE_RATIO = 0.5;
    /**
     * 사라진 낱말 수가 새로 생긴 낱말 수보다 이 값 넘게 많으면 "고친 것"이 아니라 "지운 것"이다. 함께 바뀌어야 할 내용(드라이버·SQL 문법·포트)은 다른 것으로
     * <b>바뀌지</b> 사라지지 않는다 — 실측(2026-11-10, gemma-4-E2B)에서 모델은 무관한 구절("SQLException인 경우")을 통째로 지우고도 길이·줄 수 가드를 통과했다.
     */
    static final int MAX_NET_DELETED_TOKENS = 1;

    public static final String KIND_SEMANTIC_NOTE = "검토 필요 — LLM 이 용어 치환 외에 함께 바뀔 내용을 고쳤다";

    private static final Pattern PAGE_MARKER = Pattern.compile("\\[페이지:\\s*\\d+]");

    /** 버려진 수정안 — 파일·청크·사유. */
    public record Discarded(String file, String chunkId, String reason) {}

    /**
     * @param semantic         이 작업이 의미 변경 수정안을 시도했는가
     * @param semanticTried    LLM 이 다시 쓴 청크 수
     * @param semanticProposed 그중 가드를 통과해 의미 변경 수정안이 된 수
     * @param semanticSkipped  시도했지만 버린 것·건너뛴 것(길이·상한·가드 위반·호출 실패) — 그 청크는 리터럴 수정안으로 남는다
     */
    public record Report(String instruction, String from, String to, int mentions, boolean truncated, boolean tooShort,
                         int proposals, int unchanged, int manualCurated, List<Discarded> discarded,
                         boolean semantic, int semanticTried, int semanticProposed, List<Discarded> semanticSkipped) {}

    public record Started(long jobId, String status) {}

    private final KeywordSearchRepository keywords;
    private final AdminService adminService;
    private final LlmRouter llmRouter;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final MessageSource messages;
    private final AppProperties props;
    private final java.time.Duration poll;
    private final java.time.Duration maxWait;

    @Autowired
    public MaintenanceWorkflow(KeywordSearchRepository keywords, AdminService adminService, LlmRouter llmRouter,
                               ChangeJobRepository jobs, ChangeProposalRepository proposals,
                               MessageSource messages, AppProperties props) {
        this(keywords, adminService, llmRouter, jobs, proposals, messages, props,
                java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(30));
    }

    /** 테스트용 — 채팅 양보 대기를 줄인다. */
    MaintenanceWorkflow(KeywordSearchRepository keywords, AdminService adminService, LlmRouter llmRouter,
                        ChangeJobRepository jobs, ChangeProposalRepository proposals,
                        MessageSource messages, AppProperties props,
                        java.time.Duration poll, java.time.Duration maxWait) {
        this.poll = poll;
        this.maxWait = maxWait;
        this.keywords = keywords;
        this.adminService = adminService;
        this.llmRouter = llmRouter;
        this.jobs = jobs;
        this.proposals = proposals;
        this.messages = messages;
        this.props = props;
    }

    /**
     * 작업을 만들고 가상 스레드에서 돌린다.
     *
     * @param from 바꿀 말(없으면 지시문에서 뽑는다)
     * @param to   새 말(없으면 지시문에서 뽑는다)
     * @throws IllegalStateException    이 사용자의 유지보수 작업이 이미 돌고 있을 때
     * @throws IllegalArgumentException 지시문이 비었고 {@code from}/{@code to} 도 없을 때
     */
    public Started start(String userId, String instruction, String from, String to, Locale locale) {
        return start(userId, instruction, from, to, false, locale);
    }

    /** @param semantic 글자 치환이 끝난 청크를 LLM 이 한 번 더 보고 함께 바뀌어야 할 내용을 고치게 한다(선택 — 호출이 청크 수만큼 늘고 결과는 "검토 필요") */
    public Started start(String userId, String instruction, String from, String to, boolean semantic, Locale locale) {
        boolean literal = from != null && !from.isBlank() && to != null;
        if (!literal && (instruction == null || instruction.isBlank())) {
            throw new IllegalArgumentException("instruction 이(가) 필요합니다");
        }
        if (jobs.countRunning(userId) > 0) throw new IllegalStateException("이미 진행 중인 작업이 있습니다");
        String text = instruction == null || instruction.isBlank() ? from.strip() + " → " + to.strip() : instruction.strip();
        long id = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, text, userId);
        Thread.ofVirtual().name("maintenance-" + id).start(MdcPropagation.wrap(
                () -> proposeAndStore(id, text, from, to, semantic, locale)));
        return new Started(id, ChangeJobRepository.RUNNING);
    }

    /** 수정안을 동기로 만들어 대기열과 리포트를 저장한다 — 작업 몸체이자 테스트의 입구. 어떤 실패도 {@code failed} 로 남는다. */
    void proposeAndStore(long jobId, String instruction, String from, String to, Locale locale) {
        proposeAndStore(jobId, instruction, from, to, false, locale);
    }

    void proposeAndStore(long jobId, String instruction, String from, String to, boolean semantic, Locale locale) {
        try {
            Report report = propose(jobId, instruction, from, to, semantic, locale);
            jobs.finish(jobId, ChangeJobRepository.DONE, MAPPER.writeValueAsString(report), null);
            log.info("[MAINTENANCE] 수정안 작성 완료 job={} 언급={} 수정안={} 버림={}", jobId, report.mentions(),
                    report.proposals(), report.discarded().size());
        } catch (Exception e) {
            log.warn("[MAINTENANCE] 실패 job={}: {}", jobId, e.getMessage());
            jobs.finish(jobId, ChangeJobRepository.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    Report propose(long jobId, String instruction, String fromArg, String toArg, Locale locale) {
        return propose(jobId, instruction, fromArg, toArg, false, locale);
    }

    Report propose(long jobId, String instruction, String fromArg, String toArg, boolean semantic, Locale locale) {
        String from;
        String to;
        if (fromArg != null && !fromArg.isBlank() && toArg != null) {
            from = fromArg.strip();
            to = toArg.strip();
        } else {
            String[] pair = extractTerms(instruction, locale);   // 못 읽으면 예외 — 추측으로 실행하지 않는다
            from = pair[0];
            to = pair[1];
        }
        if (from.length() > MAX_TERM_CHARS || to.length() > MAX_TERM_CHARS) {
            throw new IllegalArgumentException("바꿀 말이 너무 깁니다(" + MAX_TERM_CHARS + "자 이하의 식별자·용어만 치환할 수 있습니다)");
        }
        if (from.equals(to)) throw new IllegalArgumentException("바꿀 말과 새 말이 같습니다");

        int manual = keywords.findMentions("curated", from, MENTION_LIMIT).hits().size();
        KeywordSearchRepository.Mentions mentions = keywords.findMentions(VERSION, from, MENTION_LIMIT);
        if (mentions.tooShort()) {
            return new Report(instruction, from, to, 0, false, true, 0, 0, manual, List.of(), semantic, 0, 0, List.of());
        }
        String collection = adminService.collectionFor(VERSION);
        List<ChangeProposalRepository.NewProposal> out = new ArrayList<>();
        List<Discarded> discarded = new ArrayList<>();
        List<Discarded> semanticSkipped = new ArrayList<>();
        int unchanged = 0;
        int semanticTried = 0;
        int semanticProposed = 0;
        for (var hit : mentions.hits()) {
            AdminService.ChunkRow row = adminService.getChunk(collection, hit.getId());
            if (row == null) continue;   // 조회와 읽기 사이에 사라진 청크
            String before = row.fullText() == null ? "" : row.fullText();
            if (!before.contains(from)) { unchanged++; continue; }   // 대소문자만 다르게 걸림 — 글자 그대로 바꿀 곳이 없다
            String after = before.replace(from, to);
            String problem = invariantViolation(before, after);
            if (problem != null) {
                discarded.add(new Discarded(row.filename(), row.id(), problem));
                continue;
            }
            String finalAfter = after;
            String kind = ChangeProposalRepository.KIND_LITERAL;
            String rationale = "리터럴 치환: \"" + from + "\" → \"" + to + "\"";
            if (semantic) {
                if (after.length() > MAX_SEMANTIC_CHARS) {
                    semanticSkipped.add(new Discarded(row.filename(), row.id(), "너무 길어 의미 변경을 건너뜀(" + after.length() + "자)"));
                } else if (semanticTried >= MAX_SEMANTIC) {
                    semanticSkipped.add(new Discarded(row.filename(), row.id(), "의미 변경 상한(" + MAX_SEMANTIC + "개)을 넘어 건너뜀"));
                } else {
                    semanticTried++;
                    Rewrite rw = semanticRewrite(after, from, to, locale);
                    if (rw.text() != null) {
                        finalAfter = rw.text();
                        kind = ChangeProposalRepository.KIND_SEMANTIC;
                        rationale = KIND_SEMANTIC_NOTE + (rw.reason().isBlank() ? "" : ": " + rw.reason());
                        semanticProposed++;
                    } else if (rw.skipped() != null) {
                        semanticSkipped.add(new Discarded(row.filename(), row.id(), rw.skipped()));
                    }
                }
            }
            String ctx = row.metadata().get(MetaKey.CHUNK_CONTEXT);
            String kw = row.metadata().get(MetaKey.EXCERPT_KEYWORDS);
            out.add(new ChangeProposalRepository.NewProposal(jobId, row.docId(), row.filename(), row.id(),
                    kind, before, finalAfter, sha256(before),
                    ctx, ctx == null ? null : ctx.replace(from, to), kw, kw == null ? null : kw.replace(from, to),
                    rationale));
        }
        proposals.insertAll(out);
        return new Report(instruction, from, to, mentions.hits().size(), mentions.truncated(), false,
                out.size(), unchanged, manual, List.copyOf(discarded),
                semantic, semanticTried, semanticProposed, List.copyOf(semanticSkipped));
    }

    /**
     * 수정안이 지켜야 할 것 — 이미지 마커·페이지 마커가 그대로이고 코드 펜스 문제가 <b>새로</b> 생기지 않았는가. 위반 사유 또는 {@code null}.
     * (펜스는 원래 있던 문제까지 탓하지 않는다 — 이 수정이 만든 것만 본다.)
     */
    static String invariantViolation(String before, String after) {
        if (!markerPaths(before).equals(markerPaths(after))) return "이미지 마커가 바뀜";
        if (!pageMarkers(before).equals(pageMarkers(after))) return "페이지 마커가 바뀜";
        if (MarkdownCorrectionService.findFenceProblems(after).size() > MarkdownCorrectionService.findFenceProblems(before).size()) {
            return "코드 펜스가 깨짐";
        }
        return null;
    }

    private static List<String> markerPaths(String text) {
        return ChunkImageEditor.markers(text).stream().map(m -> (m.unconvertible() ? "!" : "") + m.path()).toList();
    }

    private static List<String> pageMarkers(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PAGE_MARKER.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 다시 쓰기 시도의 결과 — {@code text} 가 있으면 가드를 통과한 새 본문, 없으면 {@code skipped}(버린 사유)가 있거나(없으면 "고칠 것 없음"). */
    record Rewrite(String text, String reason, String skipped) {
        static Rewrite none() { return new Rewrite(null, "", null); }
        static Rewrite skipped(String why) { return new Rewrite(null, "", why); }
    }

    /**
     * 치환이 끝난 본문을 LLM 이 한 번 더 보고 함께 바뀌어야 할 내용을 고친다. <b>실패는 전부 "리터럴 수정안으로 남김"</b>이다 — 호출 실패·형식
     * 오류·"변경 없음"·가드 위반 어느 것도 작업을 실패시키지 않는다.
     */
    private Rewrite semanticRewrite(String literalAfter, String from, String to, Locale locale) {
        awaitChatQuiet();
        try {
            String system = messages.getMessage("prompt.maintenance.rewrite", null, locale)
                    .replace("{from}", from).replace("{to}", to);
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.RERANK.taskType(), ThinkingSite.RERANK.fixedRoutingMode(),
                    model -> model.call(new Prompt(List.of(new SystemMessage(system),
                            new UserMessage(PromptInjectionGuard.wrap(literalAfter))), rewriteOptions())));
            return interpretRewrite(result.text(), literalAfter, from, to);
        } catch (Exception e) {
            log.warn("[MAINTENANCE] 의미 변경 호출 실패 — 리터럴 수정안으로 남긴다: {}", e.getMessage());
            return Rewrite.skipped("의미 변경 호출 실패");
        }
    }

    private static final Pattern VERDICT = Pattern.compile("^\\s*판정\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern REASON = Pattern.compile("^\\s*이유\\s*:\\s*(.+)$", Pattern.MULTILINE);
    private static final String TEXT_DELIMITER = "---본문---";

    /**
     * 모델의 답을 읽고 가드로 거른다. 형식: {@code 판정: 변경 필요|변경 없음} · {@code 이유: …} · {@code ---본문---} 다음 줄부터 새 본문 전체. JSON 이
     * 아닌 이유는 본문에 따옴표·줄바꿈·코드 펜스가 가득해 이스케이프가 깨지기 쉽기 때문이다.
     */
    static Rewrite interpretRewrite(String response, String literalAfter, String from, String to) {
        if (response == null) return Rewrite.skipped("응답 없음");
        Matcher v = VERDICT.matcher(response);
        if (!v.find()) return Rewrite.skipped("판정을 읽지 못함");
        String verdict = v.group(1).strip();
        if (verdict.contains("없음")) return Rewrite.none();
        if (!verdict.contains("필요")) return Rewrite.skipped("판정을 읽지 못함");
        int d = response.indexOf(TEXT_DELIMITER);
        if (d < 0) return Rewrite.skipped("본문 구분선이 없음");
        String text = response.substring(d + TEXT_DELIMITER.length()).strip();
        Matcher r = REASON.matcher(response.substring(0, d));
        String reason = r.find() ? r.group(1).strip() : "";
        String problem = validateRewrite(literalAfter, text, from, to);
        return problem == null ? new Rewrite(text, reason, null) : Rewrite.skipped("가드 위반 — " + problem);
    }

    /**
     * 다시 쓴 본문을 믿지 않고 거르는 가드 — 위반 사유 또는 {@code null}. 모델은 "최소한으로 고쳐라"는 지시를 어기고 요약하거나 새 말을 되돌리거나 마커를
     * 지우기 쉽다.
     *
     * @param literalAfter 글자 치환이 끝난 본문(다시 쓰기의 입력 — 비교 기준)
     */
    static String validateRewrite(String literalAfter, String rewritten, String from, String to) {
        if (rewritten == null || rewritten.isBlank()) return "본문이 비었다";
        if (rewritten.equals(literalAfter)) return "치환 결과와 같다";
        if (rewritten.contains(from)) return "바꿀 말이 되살아났다";
        if (MarkdownWriteBack.occurrences(rewritten, to) < MarkdownWriteBack.occurrences(literalAfter, to)) return "새 말이 줄었다";
        String invariant = invariantViolation(literalAfter, rewritten);
        if (invariant != null) return invariant;
        double ratio = literalAfter.isEmpty() ? 1.0 : (double) rewritten.length() / literalAfter.length();
        if (ratio < MIN_LENGTH_RATIO || ratio > MAX_LENGTH_RATIO) return "길이가 크게 달라졌다(" + Math.round(ratio * 100) + "%)";
        ChunkDiff.Result diff = ChunkDiff.compare(literalAfter, rewritten);
        if (diff == null || diff.tooLarge()) return "비교하기에 너무 크다";
        int lines = Math.max(1, (int) literalAfter.lines().count());
        if (diff.removedLines() > Math.max(3, lines * MAX_CHANGED_LINE_RATIO)) return "너무 많은 줄을 고쳤다(" + diff.removedLines() + "/" + lines + "줄)";
        int[] moved = tokenChurn(literalAfter, rewritten);
        if (moved[0] - moved[1] > MAX_NET_DELETED_TOKENS) return "내용을 지웠다(사라진 낱말 " + moved[0] + " · 생긴 낱말 " + moved[1] + ")";
        return null;
    }

    /**
     * 공백으로 나눈 낱말의 <b>다중집합</b> 차이 — {@code [사라진 수, 생긴 수]}. 순서·줄바꿈이 바뀐 것은 세지 않고, 같은 낱말이 여러 번 나오면 개수로
     * 센다(한 군데를 지우면 사라진 것으로 센다).
     */
    static int[] tokenChurn(String before, String after) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (String t : before.split("\\s+")) if (!t.isEmpty()) counts.merge(t, 1, Integer::sum);
        int added = 0;
        for (String t : after.split("\\s+")) {
            if (t.isEmpty()) continue;
            Integer c = counts.get(t);
            if (c != null && c > 0) counts.put(t, c - 1); else added++;
        }
        int removed = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new int[]{removed, added};
    }

    /** 채팅이 로컬 LLM 을 쓰는 동안 기다린다 — 상한을 넘으면 그냥 보낸다(영원히 굶지 않게). 영향도 분석과 같은 신호다. */
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

    private OpenAiChatOptions rewriteOptions() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), ThinkingSite.RERANK);
        int configured = props.llmSafe().maxTokens();
        builder.maxTokens(configured > 0 ? Math.min(configured, MAX_REWRITE_OUTPUT_TOKENS) : MAX_REWRITE_OUTPUT_TOKENS);
        return builder.build();
    }

    /** 지시문에서 {@code {"from","to"}} 를 뽑는다 — 읽지 못하거나 비었으면 예외(작업이 실패한다). */
    private String[] extractTerms(String instruction, Locale locale) {
        String system = messages.getMessage("prompt.maintenance.extract", null, locale);
        LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                ThinkingSite.CONDENSE.taskType(), ThinkingSite.CONDENSE.fixedRoutingMode(),
                model -> model.call(new Prompt(List.of(new SystemMessage(system),
                        new UserMessage(PromptInjectionGuard.wrap(instruction))), options())));
        return parseTerms(result.text());
    }

    static String[] parseTerms(String response) {
        int a = response == null ? -1 : response.indexOf('{');
        int b = response == null ? -1 : response.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("지시문에서 바꿀 말을 읽지 못했습니다 — from/to 를 직접 입력해 주세요");
        try {
            JsonNode j = MAPPER.readTree(response.substring(a, b + 1));
            String from = j.path("from").asString("").strip();
            String to = j.path("to").asString("").strip();
            if (from.isEmpty() || to.isEmpty()) {
                throw new IllegalArgumentException("지시문에서 바꿀 말과 새 말을 모두 읽지 못했습니다 — from/to 를 직접 입력해 주세요");
            }
            return new String[]{from, to};
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("지시문에서 바꿀 말을 읽지 못했습니다 — from/to 를 직접 입력해 주세요");
        }
    }

    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), ThinkingSite.CONDENSE);
        int configured = props.llmSafe().maxTokens();
        builder.maxTokens(configured > 0 ? Math.min(configured, MAX_OUTPUT_TOKENS) : MAX_OUTPUT_TOKENS);
        return builder.build();
    }

    static String sha256(String s) {
        return com.example.ragagent.model.TextHash.sha256(s);
    }
}
