package com.example.ragagent.tool;

import com.example.ragagent.repository.AgentToolCallRepository;
import com.example.ragagent.agent.AgentState;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.workflow.EditIntent;
import com.example.ragagent.workflow.KnowledgeEditWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * {@code TOOL} 노드의 실체(§6.33 3단계) — 모델이 고른 검색 아닌 Tool 을 <b>그래프 안에서, 게이트 퍼밋 밖으로</b> 실행하고 그 결과를 답변 노드가
 * 읽을 근거 문서로 바꾼다. 답변·검증·사다리는 건드리지 않는다 — 결과는 평소 검색 결과와 같은 자리({@code retrievedDocs})에 들어간다.
 *
 * <p><b>호출자는 서버가 정한다</b> — 요청 사용자의 id, 관리자 아님, 채널 {@code chat}. 채팅의 선택지는 처음부터 이 호출자에게 보이는 공개
 * Tool 뿐이고({@code ToolPlanService}), 그래도 모델이 만든 인자·이름이 권한을 넓히지 못하도록 실행은 {@link ToolCatalog#call} 의 권한 검사를
 * 다시 지난다(거부는 {@code tool.denied} 로 감사 기록된다).
 *
 * <p><b>실패는 턴을 실패시키지 않는다</b> — 어떤 예외든 계획을 지워 돌려주고(그래프가 {@code RETRIEVAL} 로 간다) 사용자 중단만 전파한다.
 *
 * <p><b>출처({@code sources})는 비운다.</b> 카탈로그 응답은 시간이 지나면 낡는데 재사용 출처({@code turn_source_ref})가 만들어지면 그 답이 재사용 후보가
 * 된다(설계서 §5.1). 근거 문서의 파일명은 Tool 이름이다.
 */
@Service
public class ToolDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatchService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 분석을 시작할 뿐 근거 문서를 만들지 않는 Tool — 결과를 문서로 읽어 답하게 하지 않고 정형 안내를 답으로 남긴다(설계서 §5.3). */
    static final String IMPACT_TOOL = "analyzeImpact";

    /** 문서 유지보수 요청 Tool(관리자 전용) — 채팅에서는 <b>되묻고 확정되어야</b> 시작한다({@link #changeRequest}). */
    public static final String CHANGE_TOOL = "requestDocumentChange";

    /** 바꿀 말·새 말의 길이 상한 — {@code MaintenanceWorkflow.MAX_TERM_CHARS} 와 같다(식별자·용어이지 문장이 아니다). */
    private static final int MAX_TERM_CHARS = 200;

    private final ToolCatalog catalog;
    private final MessageSource messages;
    /** null = 되묻기 저장소 없음(테스트·구 구성) — 유지보수 계획은 되물을 곳이 없으므로 시작하지 않고 검색으로 내려간다. */
    private final PendingChangeConfirmations pending;

    @Autowired
    public ToolDispatchService(ToolCatalog catalog, MessageSource messages, PendingChangeConfirmations pending) {
        this.catalog = catalog;
        this.messages = messages;
        this.pending = pending;
    }

    /** 되묻기 저장소 없이 — 테스트용. */
    public ToolDispatchService(ToolCatalog catalog, MessageSource messages) {
        this(catalog, messages, null);
    }

    /** 메시지 번들 없이 — 테스트용. 정형 안내는 한국어 기본 문구다. */
    public ToolDispatchService(ToolCatalog catalog) {
        this(catalog, null, null);
    }

    /** 자연어 수정의 해석(6c) — 세터 주입(생성자가 여럿이다). 없으면 바꿀 말·새 말이 없는 요청은 지금처럼 안내로 끝난다. */
    private KnowledgeEditWorkflow knowledgeEdit;

    @Autowired(required = false)
    public void setKnowledgeEdit(KnowledgeEditWorkflow knowledgeEdit) {
        this.knowledgeEdit = knowledgeEdit;
    }

    /** 관측 저장소 — 세터 주입(생성자가 여럿이다). 없으면 기록하지 않는다. */
    private AgentToolCallRepository observations;

    @Autowired(required = false)
    public void setObservations(AgentToolCallRepository observations) {
        this.observations = observations;
    }

    private void observe(AgentState state, ToolPlan plan, String status, Integer resultSize, long startedAtNanos) {
        if (observations == null) return;
        observations.record(state.threadId(), state.userId(), AgentToolCallRepository.EXEC, plan.toolName(), status, resultSize,
                (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    /**
     * @return 성공이면 {@code retrievedDocs} 에 Tool 결과 문서 하나가 실리고 {@code sources} 는 빈 채 {@link AgentState#toolPlan()} 이 남는다. 실패면
     *         {@code toolPlan} 이 {@code null} 이고 나머지는 그대로다 — 호출자(그래프)는 그것으로 {@code RETRIEVAL} 로 갈라진다
     */
    public AgentState execute(AgentState state) {
        ToolPlan plan = state.toolPlan();
        if (plan == null) return state;
        long startedAt = System.nanoTime();
        // 호출자는 서버가 정한다 — 요청 스레드에서 구해 상태에 실린 관리자 여부(모델이 만든 인자·이름은 권한을 넓히지 못한다).
        ToolCaller caller = new ToolCaller(state.userId(), state.callerAdmin(), "chat");
        try {
            if (plan.isExpiredConfirmation()) {
                return expiredAnswer(state, plan);   // 아무것도 부르지 않는다
            }
            if (CHANGE_TOOL.equals(plan.toolName())) {
                AgentState changed = changeRequest(state, plan, caller);
                observe(state, plan, changed.toolPlan() == null ? AgentToolCallRepository.ERROR : AgentToolCallRepository.OK, null, startedAt);
                return changed;
            }
            String result = catalog.call(plan.toolName(), plan.argumentsJson(), caller);
            if (result == null || result.isBlank()) {
                log.info("[TOOL] 빈 결과 — 검색으로 진행한다 tool={} thread={}", plan.toolName(), state.threadId());
                observe(state, plan, AgentToolCallRepository.EMPTY, 0, startedAt);
                return state.toBuilder().toolPlan(null).build();
            }
            observe(state, plan, AgentToolCallRepository.OK, result.length(), startedAt);
            if (IMPACT_TOOL.equals(plan.toolName())) {
                return startedAnswer(state, result);
            }
            Document doc = new Document("tool-" + plan.toolName(), result, Map.of(
                    MetaKey.FILENAME, "Tool: " + plan.toolName(),
                    MetaKey.PAGE_OR_SLIDE, "-"));
            log.info("[TOOL] 실행 tool={} thread={} resultChars={}", plan.toolName(), state.threadId(), result.length());
            return state.toBuilder().retrievedDocs(List.of(doc)).sources(List.of()).build();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);   // 사용자 중단은 그대로 전파한다
            }
            log.warn("[TOOL] Tool 실행 실패 — 검색으로 진행한다 tool={} thread={}: {}", plan.toolName(), state.threadId(), e.getMessage());
            observe(state, plan, AgentToolCallRepository.ERROR, null, startedAt);
            return state.toBuilder().toolPlan(null).build();
        }
    }

    /**
     * 영향도 분석을 시작한 턴의 답 — 정형 문구와 작업 링크. 검색 0건 경로와 같은 모양으로 남긴다: 응답 모드는 {@code S}(검증 없음 · 좋아요 제안
     * 불가), 근거 문서·출처는 없고 판정도 없다. 그래프는 {@code ANSWER} 를 건너뛰고 {@code FINALIZE} 로 간다.
     */
    private AgentState startedAnswer(AgentState state, String toolResult) {
        JsonNode r = MAPPER.readTree(toolResult);
        boolean busy = "busy".equals(r.path("status").asString(""));
        String answer = busy
                ? text("chat.answer.impact-busy", "이미 진행 중인 영향도 분석이 있습니다. 끝난 뒤 다시 요청해 주세요.", state)
                : text("chat.answer.impact-started", "영향도 분석을 시작했습니다 (작업 #{0}). 끝나면 아래 링크에서 리포트를 볼 수 있습니다: [/ui/impact-jobs/{0}](/ui/impact-jobs/{0})", state,
                        String.valueOf(r.path("jobId").asLong(0)));
        log.info("[TOOL] 영향도 분석 {} thread={}", busy ? "중복 요청" : "시작", state.threadId());
        return fixedAnswer(state, answer);
    }

    /**
     * 채팅의 유지보수 요청 — <b>되묻고, 사용자가 "네" 라고 답해야 시작한다</b>(§6.33 4b).
     * <ol>
     *   <li><b>관리자가 아니면 아무것도 하지 않는다</b>(계획을 지워 검색으로 내려간다) — 모델이 이 Tool 을 골랐더라도, 되묻기 문장조차 보내지 않는다.</li>
     *   <li>바꿀 말·새 말이 없거나 같거나 너무 길면 시작도 되묻기도 하지 않고 "무엇을 무엇으로 바꿀지" 안내만 한다(모델이 인자를 못 채웠다).</li>
     *   <li>처음 고른 계획({@code confirmed=false}): Tool 을 부르지 않고 "A 를 B 로 바꾸는 수정안을 만들까요?" 를 답으로 남기며 요청을 기억한다.
     *       <b>코퍼스는 이 단계에서도 승인 전에는 바뀌지 않는다.</b> {@code semantic}·{@code caseVariants} 같은 옵션은 화면(링크)에서만 켠다.</li>
     *   <li>확정된 계획({@code confirmed=true} — 되묻기에 "네"): {@link ToolCatalog#call} 로 Tool 을 실제로 부른다(여기서 관리자 권한을 한 번 더 검사한다).
     *       옵션은 싣지 않으므로 항상 끈 채로 시작한다.</li>
     * </ol>
     */
    private AgentState changeRequest(AgentState state, ToolPlan plan, ToolCaller caller) {
        if (!state.callerAdmin() || pending == null) {
            log.warn("[TOOL] 유지보수 요청을 시작하지 않는다 admin={} pendingStore={} thread={}", state.callerAdmin(), pending != null, state.threadId());
            return state.toBuilder().toolPlan(null).build();
        }
        JsonNode a = MAPPER.readTree(plan.argumentsJson());
        String from = a.path("from").asString("").strip();
        String to = a.path("to").asString("").strip();
        String instruction = a.path("instruction").asString("").strip();
        if (plan.confirmed() && !a.path("type").asString("").isBlank()) {
            // 확정된 자연어 수정 — 되물을 때 보여 준 해석(저장소의 값)을 그대로 넘긴다. 모델이 채운 값이 아니다(확정 계획은 저장소만 만든다).
            return startConfirmed(state, caller, a);
        }
        if (!plan.confirmed() && (from.isEmpty() || to.isEmpty()) && knowledgeEdit != null) {
            return askNaturalLanguage(state);
        }
        if (from.isEmpty() || to.isEmpty() || from.equals(to) || from.length() > MAX_TERM_CHARS || to.length() > MAX_TERM_CHARS) {
            return fixedAnswer(state, text("chat.answer.change-needs-terms",
                    "무엇을 무엇으로 바꿀지 분명하지 않습니다. 예: PostgreSQL 을 Oracle 로 바꿔 줘", state));
        }
        if (!plan.confirmed()) {
            pending.put(state.userId(), state.threadId(), new PendingChangeConfirmations.Pending(instruction.isEmpty() ? from + " → " + to : instruction, from, to));
            log.info("[TOOL] 유지보수 되묻기 user={} thread={}", state.userId(), state.threadId());
            return fixedAnswer(state, text("chat.answer.change-ask", "「{0}」을(를) 「{1}」(으)로 바꾸는 수정안을 만들까요? 만들려면 네 라고 답해 주세요. "
                    + "옵션은 {2} 에서 직접 요청하세요.", state, from, to, "/admin/change-jobs?from=" + enc(from) + "&to=" + enc(to)));
        }
        String json = MAPPER.createObjectNode().put("instruction", instruction.isEmpty() ? from + " → " + to : instruction)
                .put("from", from).put("to", to).toString();
        String result = catalog.call(CHANGE_TOOL, json, caller);   // 관리자 권한을 여기서 한 번 더 검사한다(거부는 예외 → 검색으로)
        JsonNode r = MAPPER.readTree(result);
        boolean busy = "busy".equals(r.path("status").asString(""));
        log.info("[TOOL] 유지보수 {} user={} thread={}", busy ? "중복 요청" : "시작", state.userId(), state.threadId());
        return fixedAnswer(state, busy
                ? text("chat.answer.change-busy", "이미 진행 중인 유지보수 요청이 있습니다. 끝난 뒤 다시 요청해 주세요.", state)
                : text("chat.answer.change-started", "수정안 만들기를 시작했습니다 (작업 #{0}). 끝나면 아래 화면에서 Diff 를 검토하고 승인하세요: [/admin/change-jobs/{0}](/admin/change-jobs/{0})", state,
                        String.valueOf(r.path("jobId").asLong(0))));
    }

    /**
     * 자연어 수정 되묻기(AGENT_MCP_DESIGN §10.10) — <b>사용자가 친 원문</b>을 해석한다(모델이 고쳐 쓴 지시문이 아니라 — 해석 값은 그 문장의 부분 문자열이어야
     * 한다). 해석된 유형·대상·문장을 되읽어 주고 그 해석을 그대로 기억한다. 글자 바꾸기로 해석되면 지금의 "A 를 B 로" 되묻기와 같다. 못 읽으면 화면 링크.
     */
    private AgentState askNaturalLanguage(AgentState state) {
        String question = state.question() == null ? "" : state.question().strip();
        EditIntent intent;
        try {
            intent = knowledgeEdit.interpret(question, state.locale());
        } catch (Exception e) {
            log.info("[TOOL] 자연어 수정 해석 실패 thread={}: {}", state.threadId(), e.getMessage());
            return fixedAnswer(state, text("chat.answer.edit-unclear",
                    "무엇을 어떻게 바꿀지 읽지 못했습니다. 화면에서 유형과 대상을 골라 요청하세요: [/admin/change-jobs](/admin/change-jobs)", state));
        }
        if (intent.formOnly()) {
            // 내용 삭제·설명 수정은 지시문 해석으로 받지 않는다(EditIntent.FORM_ONLY — 6c 사전 평가) — 되묻지도 기억하지도 않고, 유형과 대상을 미리 채운
            // 화면 링크를 준다. 거기서 관리자가 유형을 직접 고르고 수정안은 검토 화면에서 하나씩 확인한다.
            log.info("[TOOL] 자연어 수정 폼 전용 유형 type={} thread={}", intent.type(), state.threadId());
            StringBuilder link = new StringBuilder("/admin/change-jobs?type=").append(enc(intent.type()))
                    .append("&subject=").append(enc(intent.subject() == null ? "" : intent.subject()));
            if (intent.content() != null) link.append("&content=").append(enc(intent.content()));
            return fixedAnswer(state, text("chat.answer.edit-form-only." + intent.type(), intent.type().equals(EditIntent.REMOVE)
                            ? "「{0}」에 대한 설명을 지우는 요청은 채팅으로 받지 않습니다 — 원문을 지우는 수정이라 관리자 화면에서 유형을 직접 고르고 수정안을 하나씩 확인해야 합니다: [{1}]({1})"
                            : "「{0}」의 설명을 고치는 요청은 채팅으로 받지 않습니다 — 원문을 바꾸는 수정이라 관리자 화면에서 유형을 직접 고르고 수정안을 하나씩 확인해야 합니다: [{1}]({1})",
                    state, intent.subject(), link.toString()));
        }
        if (intent.isReplace()) {
            pending.put(state.userId(), state.threadId(), new PendingChangeConfirmations.Pending(question, intent.from(), intent.to()));
            return fixedAnswer(state, text("chat.answer.change-ask", "「{0}」을(를) 「{1}」(으)로 바꾸는 수정안을 만들까요? 만들려면 네 라고 답해 주세요. "
                    + "옵션은 {2} 에서 직접 요청하세요.", state, intent.from(), intent.to(),
                    "/admin/change-jobs?from=" + enc(intent.from()) + "&to=" + enc(intent.to())));
        }
        pending.put(state.userId(), state.threadId(), new PendingChangeConfirmations.Pending(question, null, null,
                intent.type(), intent.subject(), intent.content(), intent.replacement()));
        log.info("[TOOL] 자연어 수정 되묻기 type={} user={} thread={}", intent.type(), state.userId(), state.threadId());
        String key = "chat.answer.edit-ask." + intent.type();
        // 여기까지 오는 유형은 사용 중단·주의 사항 둘뿐이다(내용 삭제·설명 수정은 위에서 화면으로 돌렸다).
        String fallback = EditIntent.DEPRECATE.equals(intent.type())
                ? "「{0}」을(를) 사용 중단으로 표시하는 수정안을 만들까요? {0} 을(를) 설명하는 섹션마다 안내 문장을 넣고 원문은 지우지 않습니다. 만들려면 네 라고 답해 주세요."
                : "「{0}」 관련 청크에 다음 주의 사항을 넣는 수정안을 만들까요? 만들려면 네 라고 답해 주세요.\n\n> {1}";
        return fixedAnswer(state, text(key, fallback, state, intent.subject(), intent.content() == null ? "" : intent.content()));
    }

    /**
     * 만료된 되묻기에 "네" 라고 답한 턴 — Tool 을 부르지 않고 "확인 시간이 지나 시작하지 않았다"를 정형 답으로 남긴다. 무엇이 만료됐는지는 기억했던
     * 요청(바꿀 말 → 새 말, 또는 자연어 수정의 대상)으로 말한다. 관리자가 아니면(저장소는 관리자만 부르지만) 검색으로 내려간다.
     */
    private AgentState expiredAnswer(AgentState state, ToolPlan plan) {
        if (!state.callerAdmin()) return state.toBuilder().toolPlan(null).build();
        JsonNode a = MAPPER.readTree(plan.argumentsJson());
        String from = a.path("from").asString("").strip();
        String to = a.path("to").asString("").strip();
        String subject = a.path("subject").asString("").strip();
        String what = !from.isEmpty() && !to.isEmpty() ? from + " → " + to
                : !subject.isEmpty() ? subject : a.path("instruction").asString("").strip();
        log.info("[TOOL] 유지보수 되묻기 만료 — 시작하지 않는다 user={} thread={}", state.userId(), state.threadId());
        return fixedAnswer(state, text("chat.answer.change-expired",
                "「{0}」 수정안 요청의 확인 시간({1}분)이 지나 취소되었습니다. 아무 작업도 시작하지 않았습니다. 다시 요청하시면 새로 확인합니다.",
                state, what, String.valueOf(PendingChangeConfirmations.TTL_MINUTES)));
    }

    /** 확정된 자연어 수정을 시작한다 — 관리자 권한은 {@link ToolCatalog#call} 이 한 번 더 본다. */
    private AgentState startConfirmed(AgentState state, ToolCaller caller, JsonNode a) {
        tools.jackson.databind.node.ObjectNode args = MAPPER.createObjectNode()
                .put("instruction", a.path("instruction").asString(""))
                .put("type", a.path("type").asString(""));
        for (String k : new String[]{"subject", "content", "replacement"}) {
            String v = a.path(k).asString("");
            if (!v.isBlank()) args.put(k, v);
        }
        String result = catalog.call(CHANGE_TOOL, args.toString(), caller);
        JsonNode r = MAPPER.readTree(result);
        boolean busy = "busy".equals(r.path("status").asString(""));
        log.info("[TOOL] 자연어 수정 {} user={} thread={}", busy ? "중복 요청" : "시작", state.userId(), state.threadId());
        return fixedAnswer(state, busy
                ? text("chat.answer.change-busy", "이미 진행 중인 유지보수 요청이 있습니다. 끝난 뒤 다시 요청해 주세요.", state)
                : text("chat.answer.change-started", "수정안 만들기를 시작했습니다 (작업 #{0}). 끝나면 아래 화면에서 Diff 를 검토하고 승인하세요: [/admin/change-jobs/{0}](/admin/change-jobs/{0})", state,
                        String.valueOf(r.path("jobId").asLong(0))));
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 정형 답 — 근거 문서·출처·판정이 없는 턴(검색 0건 경로와 같은 모양): 응답 모드 {@code S}(검증 없음 · 좋아요 제안 불가). 그래프가 ANSWER 를 건너뛰고
     * FINALIZE 로 간다.
     */
    private AgentState fixedAnswer(AgentState state, String answer) {
        return state.toBuilder()
                .answer(answer)
                .responseMode(com.example.ragagent.model.ResponseMode.S)
                .retrievedDocs(List.of()).sources(List.of())
                .needsRetry(false).grounded(null).evalReason(null).envNote(null)
                .build();
    }

    private String text(String key, String fallback, AgentState state, Object... args) {
        if (messages == null) return java.text.MessageFormat.format(fallback, args);
        return messages.getMessage(key, args, fallback, state.locale());
    }
}
