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

    private static final Pattern PAGE_MARKER = Pattern.compile("\\[페이지:\\s*\\d+]");

    /** 버려진 수정안 — 파일·청크·사유. */
    public record Discarded(String file, String chunkId, String reason) {}

    public record Report(String instruction, String from, String to, int mentions, boolean truncated, boolean tooShort,
                         int proposals, int unchanged, int manualCurated, List<Discarded> discarded) {}

    public record Started(long jobId, String status) {}

    private final KeywordSearchRepository keywords;
    private final AdminService adminService;
    private final LlmRouter llmRouter;
    private final ChangeJobRepository jobs;
    private final ChangeProposalRepository proposals;
    private final MessageSource messages;
    private final AppProperties props;

    @Autowired
    public MaintenanceWorkflow(KeywordSearchRepository keywords, AdminService adminService, LlmRouter llmRouter,
                               ChangeJobRepository jobs, ChangeProposalRepository proposals,
                               MessageSource messages, AppProperties props) {
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
        boolean literal = from != null && !from.isBlank() && to != null;
        if (!literal && (instruction == null || instruction.isBlank())) {
            throw new IllegalArgumentException("instruction 이(가) 필요합니다");
        }
        if (jobs.countRunning(userId) > 0) throw new IllegalStateException("이미 진행 중인 작업이 있습니다");
        String text = instruction == null || instruction.isBlank() ? from.strip() + " → " + to.strip() : instruction.strip();
        long id = jobs.create(ChangeJobRepository.KIND_MAINTENANCE, text, userId);
        Thread.ofVirtual().name("maintenance-" + id).start(MdcPropagation.wrap(
                () -> proposeAndStore(id, text, from, to, locale)));
        return new Started(id, ChangeJobRepository.RUNNING);
    }

    /** 수정안을 동기로 만들어 대기열과 리포트를 저장한다 — 작업 몸체이자 테스트의 입구. 어떤 실패도 {@code failed} 로 남는다. */
    void proposeAndStore(long jobId, String instruction, String from, String to, Locale locale) {
        try {
            Report report = propose(jobId, instruction, from, to, locale);
            jobs.finish(jobId, ChangeJobRepository.DONE, MAPPER.writeValueAsString(report), null);
            log.info("[MAINTENANCE] 수정안 작성 완료 job={} 언급={} 수정안={} 버림={}", jobId, report.mentions(),
                    report.proposals(), report.discarded().size());
        } catch (Exception e) {
            log.warn("[MAINTENANCE] 실패 job={}: {}", jobId, e.getMessage());
            jobs.finish(jobId, ChangeJobRepository.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    Report propose(long jobId, String instruction, String fromArg, String toArg, Locale locale) {
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
            return new Report(instruction, from, to, 0, false, true, 0, 0, manual, List.of());
        }
        String collection = adminService.collectionFor(VERSION);
        List<ChangeProposalRepository.NewProposal> out = new ArrayList<>();
        List<Discarded> discarded = new ArrayList<>();
        int unchanged = 0;
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
            String ctx = row.metadata().get(MetaKey.CHUNK_CONTEXT);
            String kw = row.metadata().get(MetaKey.EXCERPT_KEYWORDS);
            out.add(new ChangeProposalRepository.NewProposal(jobId, row.docId(), row.filename(), row.id(),
                    ChangeProposalRepository.KIND_LITERAL, before, after, sha256(before),
                    ctx, ctx == null ? null : ctx.replace(from, to), kw, kw == null ? null : kw.replace(from, to),
                    "리터럴 치환: \"" + from + "\" → \"" + to + "\""));
        }
        proposals.insertAll(out);
        return new Report(instruction, from, to, mentions.hits().size(), mentions.truncated(), false,
                out.size(), unchanged, manual, List.copyOf(discarded));
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
