package com.example.ragagent.tool;

import com.example.ragagent.repository.AgentToolCallRepository;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.security.PromptInjectionGuard;
import com.example.ragagent.service.QuestionCondenser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Tool 선택(§6.33 3단계) — 턴당 <b>LLM 한 번</b>으로 "이 질문에 어떤 Tool 이 맞는가"를 고른다.
 *
 * <p><b>네이티브 Tool Calling 이 아니라 프롬프트 JSON 이다</b>(사전 평가 — AGENT_EVALUATION.md: 이 서버·모델에서 네이티브는 정확도 0.20 ·
 * 형식 실패 0.59, 프롬프트 JSON 은 0.939 · 우회 0 · 형식 실패 0). 그래서 요청에 {@code tools} 를 싣지 않고 도구 목록을 프롬프트에 적어
 * {@code {"tool": "...", "arguments": {...}}} 를 받는다 — 서버의 Tool 파서·템플릿에 의존하지 않고, 사용량 계측 세 곳(
 * {@code CalibratingChatModel}·{@code LlmCurlLogger}·{@code ThinkingObservations})이 Tool 메시지를 몰라도 된다.
 *
 * <p><b>어떤 실패도 턴을 실패시키지 않는다</b> — 호출 실패·형식 오류·모르는 Tool·인자 오류·권한 없는 Tool 은 전부 "계획 없음"
 * ({@link Planned#plan()} 이 {@code null})이고 그래프는 기존 {@code RETRIEVAL} 로 간다. Agent 가 검색을 없앨 수 없게 하는 장치다(설계서 D3).
 *
 * <p>호출 지점은 {@link ThinkingSite#CONDENSE} 를 쓴다 — 한 줄 JSON 을 내는 같은 성질의 호출이고
 * ({@code SearchQueryRewriter}·{@code QuestionCondenser.distill()} 이 같은 선례), 새 사이트를 만들면 생각 수준 카드·미리보기·문서의
 * "21곳"이 함께 바뀌는데 얻는 것은 별도 손잡이 하나뿐이다(생각 끔 · 출력 예약 256 · 고정 라우팅).
 *
 * <p>모델에게 보이는 Tool 은 {@link #PLANNABLE} 중 이 호출자에게 허용된 것뿐이다. 영향도·유지보수 Tool(4단계)은 아직 없고, 문서
 * 읽기 보조 Tool({@code getChunkContext}·{@code getDocument}·{@code generateDiff})은 채팅의 선택지가 아니다.
 */
@Service
public class ToolPlanService {

    private static final Logger log = LoggerFactory.getLogger(ToolPlanService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 채팅에서 모델이 고를 수 있는 Tool(이 순서로 프롬프트에 적는다). 영향도(4a)까지 — 유지보수 Tool 은 4b 가 끝나야 더한다. */
    static final List<String> PLANNABLE = List.of(ToolPlan.SEARCH_TOOL, "findRelatedDocuments", "queryCatalog", "analyzeImpact",
            ToolDispatchService.CHANGE_TOOL);

    /**
     * 채팅의 도구 목록 프롬프트에서 숨기는 인자 — 유지보수의 {@code semantic}·{@code caseVariants} 는 비용과 위험이 있어 <b>화면에서만</b> 켠다(채팅은 항상
     * 끈 채로 시작한다). 모델이 지어서 채워도 {@link ToolDispatchService} 가 읽지 않는다.
     */
    static final java.util.Map<String, Set<String>> CHAT_HIDDEN_PARAMS =
            java.util.Map.of(ToolDispatchService.CHANGE_TOOL, Set.of("semantic", "caseVariants"));

    /** 이 턴의 선택 결과와 그 호출의 토큰 — 그래프 바깥의 호출이라 호출부가 상태에 더한다({@code QuestionCondenser.Condensed} 와 같은 이유). */
    public record Planned(ToolPlan plan, int inputTokens, int outputTokens, boolean called) {
        public static Planned none() {
            return new Planned(null, 0, 0, false);
        }
    }

    private final LlmRouter llmRouter;
    private final ToolCatalog catalog;
    private final MessageSource messageSource;
    private final AppProperties props;
    private final boolean enabled;
    /** null = 되묻기 없음(테스트·구 구성) — 확정 경로가 없고, 유지보수 계획은 되묻기 없이는 시작되지 않으므로 사실상 그 Tool 이 쓸모없다. */
    private final PendingChangeConfirmations pending;

    @org.springframework.beans.factory.annotation.Autowired
    public ToolPlanService(LlmRouter llmRouter, ToolCatalog catalog, MessageSource messageSource, AppProperties props,
                           @Value("${app.agent.enabled:false}") boolean enabled, PendingChangeConfirmations pending) {
        this.llmRouter = llmRouter;
        this.catalog = catalog;
        this.messageSource = messageSource;
        this.props = props;
        this.enabled = enabled;
        this.pending = pending;
    }

    /** 되묻기 저장소 없이 만든다 — 테스트용. */
    public ToolPlanService(LlmRouter llmRouter, ToolCatalog catalog, MessageSource messageSource, AppProperties props, boolean enabled) {
        this(llmRouter, catalog, messageSource, props, enabled, null);
    }

    /** 관측 저장소 — 세터 주입(생성자가 여럿이다). 없으면 기록하지 않는다. */
    private AgentToolCallRepository observations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setObservations(AgentToolCallRepository observations) {
        this.observations = observations;
    }

    /** {@code app.agent.enabled}(기본 꺼짐) — 꺼져 있으면 이 단계는 LLM 을 부르지 않고 그래프도 새 경로를 타지 않는다. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 이 요청에 Tool 선택을 돌려도 되는가 — 켜져 있고, RAG 로 묻는 표준(N) 모드 턴일 때만. Direct 는 검색을 건너뛰는 요청이라 고를 Tool 이
     * 없고, S·C 는 답변의 성격이 달라 시작 범위에서 뺀다(설계서 §5.1).
     */
    public boolean appliesTo(boolean directMode, ResponseMode mode) {
        return enabled && !directMode && mode != null && mode.allowsAgent();
    }

    /**
     * 채팅 진입점 둘이 부르는 한 줄 — 적용 대상이 아니면 LLM 을 부르지 않고 {@link Planned#none()} 이다. 호출자는 요청 사용자의 id 만 넘기고
     * 관리자 여부는 넘기지 않는다: 채팅은 늘 공개 호출자로 계획한다({@link ToolDispatchService} 도 같은 호출자로 실행한다).
     */
    public Planned planFor(boolean directMode, ResponseMode mode, String question, String userId, Locale locale) {
        return planFor(directMode, mode, question, userId, false, null, locale);
    }

    /**
     * @param admin    요청 스레드에서 구한 관리자 여부 — 관리자에게만 유지보수 Tool 이 보이고 되묻기 확정이 적용된다
     * @param threadId 되묻기 대기는 (사용자, 대화) 단위라 대화를 안다
     */
    public Planned planFor(boolean directMode, ResponseMode mode, String question, String userId, boolean admin,
                           String threadId, Locale locale) {
        if (!appliesTo(directMode, mode)) return Planned.none();
        if (admin && pending != null) {
            // 되묻기에 대한 답이면(바로 다음 메시지가 긍정 표현과 정확히 같을 때) 모델을 부르지 않고 확정 계획으로 간다. 어떤 말이든 대기는 지워진다.
            Optional<PendingChangeConfirmations.Pending> confirmed = pending.resolve(userId, threadId, question);
            if (confirmed.isPresent()) {
                PendingChangeConfirmations.Pending c = confirmed.get();
                String args = MAPPER.createObjectNode().put("instruction", c.instruction()).put("from", c.from()).put("to", c.to()).toString();
                log.info("[TOOL-PLAN] 유지보수 되묻기 확정 user={} thread={}", userId, threadId);
                return new Planned(new ToolPlan(ToolDispatchService.CHANGE_TOOL, args, true), 0, 0, false);
            }
        }
        return plan(question, new ToolCaller(userId, admin, "chat"), locale, threadId);
    }

    /**
     * @param question 검색이 보는 질문({@code AgentState.effectiveSearchQuestion()}) — 이력은 넣지 않는다(§6.31 이력 제외와 충돌하지 않는다)
     * @return 항상 값이 있다. 고르지 않았거나 읽지 못했으면 {@link Planned#plan()} 이 {@code null}
     */
    public Planned plan(String question, ToolCaller caller, Locale locale) {
        return plan(question, caller, locale, null);
    }

    private Planned plan(String question, ToolCaller caller, Locale locale, String threadId) {
        if (!enabled || question == null || question.isBlank()) return Planned.none();
        long startedAt = System.nanoTime();
        try {
            List<ToolCallback> visible = visibleTools(caller);
            if (visible.size() <= 1) return Planned.none();   // 고를 것이 검색 하나뿐이면 물어볼 이유가 없다
            String systemPrompt = messageSource.getMessage("prompt.agent.plan", null, locale)
                    .replace("{tools}", toolList(visible))
                    .replace("{rules}", ruleList(visible, locale));
            String userMessage = PromptInjectionGuard.wrap(question);
            LlmRouter.LlmResult result = llmRouter.executeGatedWithUsage(
                    ThinkingSite.CONDENSE.taskType(), ThinkingSite.CONDENSE.fixedRoutingMode(),
                    model -> model.call(new Prompt(
                            List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), options())));
            ToolPlan plan = parse(result.text(), visible);
            String outcome = outcomeOf(result.text(), plan);
            String chosen = plan == null ? null : plan.toolName();
            if (plan != null && ToolDispatchService.IMPACT_TOOL.equals(plan.toolName()) && !asksAboutImpact(question)) {
                // 수정 요청("A 를 B 로 바꿔줘")을 가장 가까운 Tool 로 받아 영향도 분석이 돌지 않게 한다 — 비관리자에게는 수정 Tool 이 안 보여 생기는 오선택이다.
                log.info("[TOOL-PLAN] analyzeImpact 거절 — 질문에 영향을 묻는 표현이 없다");
                plan = null;
                outcome = AgentToolCallRepository.REJECTED;
            }
            observe(threadId, caller, chosen, outcome, startedAt);
            log.info("[TOOL-PLAN] tool={} args={}", plan == null ? "-" : plan.toolName(), plan == null ? "-" : plan.argumentsJson());
            return new Planned(plan, result.inputTokens(), result.outputTokens(), true);
        } catch (Exception e) {
            // 턴을 실패시킬 이유가 아니다 — 소진·게이트 대기 초과·타임아웃이면 기존 RETRIEVAL 이 기존 방식으로 보고한다.
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            log.warn("[TOOL-PLAN] Tool 선택 실패 — 검색으로 진행한다: {}", e.getMessage());
            observe(threadId, caller, null, AgentToolCallRepository.ERROR, startedAt);
            return Planned.none();
        }
    }

    /** 계획 호출 한 번의 결과를 관측 테이블에 남긴다(세터로 주입된 저장소가 있을 때만 — 실패해도 턴에 영향이 없다). */
    private void observe(String threadId, ToolCaller caller, String tool, String status, long startedAtNanos) {
        if (observations == null) return;
        observations.record(threadId, caller.userId(), AgentToolCallRepository.PLAN, tool, status, null,
                (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    /** 모델의 답을 관측 상태로 가른다 — 고른 Tool 이 있으면 planned, 명시적 NONE 이면 none, 그 밖에(읽지 못함·모르는/보이지 않는 Tool·인자 불일치)는 invalid. */
    static String outcomeOf(String response, ToolPlan plan) {
        if (plan != null) return AgentToolCallRepository.PLANNED;
        int a = response == null ? -1 : response.indexOf('{');
        int b = response == null ? -1 : response.lastIndexOf('}');
        if (a < 0 || b <= a) return AgentToolCallRepository.INVALID;
        try {
            String tool = MAPPER.readTree(response.substring(a, b + 1)).path("tool").asString("");
            return tool.isBlank() || "NONE".equalsIgnoreCase(tool) ? AgentToolCallRepository.NO_TOOL : AgentToolCallRepository.INVALID;
        } catch (RuntimeException e) {
            return AgentToolCallRepository.INVALID;
        }
    }

    private static final java.util.regex.Pattern IMPACT_MARKER = java.util.regex.Pattern.compile(
            "영향|범위|분석|어떤\\s*문서|어느\\s*문서|문서\\s*(중|가|는)?\\s*어디|어디가|어디를|impact|affect|which\\s+docs?|which\\s+documents?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 질문이 "바꾸면 어떤 문서가 영향을 받는가"를 묻는 표현을 담고 있는가 — 모델이 고른 {@code analyzeImpact} 를 코드가 한 번 더 확인하는 데 쓴다. */
    static boolean asksAboutImpact(String question) {
        return question != null && IMPACT_MARKER.matcher(question).find();
    }

    /** 이 호출자에게 보이는 채팅 선택지 — {@link #PLANNABLE} 순서. */
    List<ToolCallback> visibleTools(ToolCaller caller) {
        List<ToolCallback> allowed = catalog.forCaller(caller);
        List<ToolCallback> out = new ArrayList<>();
        for (String name : PLANNABLE) {
            allowed.stream().filter(cb -> cb.getToolDefinition().name().equals(name)).findFirst().ifPresent(out::add);
        }
        return out;
    }

    /**
     * 모델의 답에서 Tool 하나를 읽는다. 첫 {@code {} ~ 마지막 {@code }} 만 본다(머리말·코드 펜스 무시). 읽지 못하거나, {@code NONE} 이거나,
     * 보이지 않는 Tool 이거나, 인자가 스키마와 맞지 않으면 {@code null}.
     */
    static ToolPlan parse(String response, List<ToolCallback> visible) {
        if (response == null) return null;
        int a = response.indexOf('{');
        int b = response.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode root = MAPPER.readTree(response.substring(a, b + 1));
            String tool = root.path("tool").asString("");
            if (tool.isBlank() || "NONE".equalsIgnoreCase(tool)) return null;
            ToolCallback cb = visible.stream().filter(c -> c.getToolDefinition().name().equals(tool)).findFirst().orElse(null);
            if (cb == null) return null;
            JsonNode args = root.path("arguments");
            if (args.isMissingNode() || args.isNull()) args = MAPPER.createObjectNode();
            if (!args.isObject() || !argumentsMatch(args, cb.getToolDefinition().inputSchema())) return null;
            return new ToolPlan(tool, args.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 필수 인자가 다 있고 비어 있지 않으며, 스키마에 없는 인자가 없는가 — 사전 평가의 "인자 유효"와 같은 정의. */
    static boolean argumentsMatch(JsonNode args, String schemaJson) {
        try {
            JsonNode schema = MAPPER.readTree(schemaJson);
            Set<String> known = new HashSet<>();
            schema.path("properties").propertyNames().forEach(known::add);
            for (String name : args.propertyNames()) {
                if (!known.contains(name)) return false;
            }
            for (JsonNode r : schema.path("required")) {
                JsonNode v = args.path(r.asString(""));
                if (v.isMissingNode() || v.isNull() || (v.isString() && v.asString("").isBlank())) return false;
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 도구 목록 한 줄씩 — 이름 · 설명 · 인자(필수 표시). 사전 평가 하네스와 같은 모양이다. */
    static String toolList(List<ToolCallback> tools) {
        StringBuilder sb = new StringBuilder();
        for (ToolCallback t : tools) {
            JsonNode schema = MAPPER.readTree(t.getToolDefinition().inputSchema());
            Set<String> required = new HashSet<>();
            schema.path("required").forEach(r -> required.add(r.asString("")));
            List<String> params = new ArrayList<>();
            Set<String> hidden = CHAT_HIDDEN_PARAMS.getOrDefault(t.getToolDefinition().name(), Set.of());
            for (String name : schema.path("properties").propertyNames()) {
                if (hidden.contains(name)) continue;
                params.add(name + (required.contains(name) ? "(필수)" : ""));
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(t.getToolDefinition().name()).append(": ").append(t.getToolDefinition().description())
                    .append(" 인자: ").append(String.join(", ", params));
        }
        return sb.toString();
    }

    /** 보이는 Tool 마다의 선택 규칙 한 줄 — 보이지 않는 Tool 의 규칙은 싣지 않는다(모르는 이름을 고르게 하지 않는다). */
    String ruleList(List<ToolCallback> tools, Locale locale) {
        StringBuilder sb = new StringBuilder();
        for (ToolCallback t : tools) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("- ").append(messageSource.getMessage("prompt.agent.plan.rule." + t.getToolDefinition().name(), null, locale));
        }
        return sb.toString();
    }

    /** 분류기·독립화와 같은 일반 temperature(핫). 생각 수준·출력 예약은 {@code CONDENSE} 사이트 그대로다. */
    private OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = ThinkingControl.mark(OpenAiChatOptions.builder()
                .temperature(props.llmSafe().temperature()), ThinkingSite.CONDENSE);
        int base = QuestionCondenser.baseReservation(props.llmSafe().maxTokens());
        if (base > 0) builder.maxTokens(base);
        return builder.build();
    }
}
