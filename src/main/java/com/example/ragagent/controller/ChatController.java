package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.exception.LlmContextOverflowException;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.*;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ChatImageAnalysisSkipRegistry;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.QuestionReuseService;
import com.example.ragagent.service.RetrievalMetricsService;
import com.example.ragagent.service.SettingsService;
import com.example.ragagent.service.StreamingAgentService;
import com.example.ragagent.service.ThreadMetaService;
import com.example.ragagent.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Chat pages (Thymeleaf), HTMX chat fragments, and REST /api/v1/chat.
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 입력창 아래 뜨는 이전 질문 제안 목록의 최대 개수. */
    private static final int MAX_QUESTION_SUGGESTIONS = 12;

    private final AgentService agentService;
    private final StreamingAgentService streamingAgentService;
    private final ThreadMetaService threadMetaService;
    private final MemoryService memoryService;
    private final ConversationSummarizerService summarizerService;
    private final AppProperties props;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final ChatImageAnalysisSkipRegistry imageSkipRegistry;
    private final QuestionReuseService questionReuseService;
    private final SettingsService settingsService;
    private final RetrievalMetricsService retrievalMetricsService;

    @Autowired
    public ChatController(AgentService agentService,
                          StreamingAgentService streamingAgentService,
                          ThreadMetaService threadMetaService,
                          MemoryService memoryService,
                          ConversationSummarizerService summarizerService,
                          AppProperties props,
                          LlmRouter llmRouter,
                          MessageSource messageSource,
                          ChatImageAnalysisSkipRegistry imageSkipRegistry,
                          ObjectProvider<QuestionReuseService> questionReuseService,
                          SettingsService settingsService,
                          RetrievalMetricsService retrievalMetricsService) {
        this.agentService = agentService;
        this.streamingAgentService = streamingAgentService;
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.summarizerService = summarizerService;
        this.props = props;
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.imageSkipRegistry = imageSkipRegistry;
        this.questionReuseService = questionReuseService.getIfAvailable();
        this.settingsService = settingsService;
        this.retrievalMetricsService = retrievalMetricsService;
    }

    // ── Page routes ───────────────────────────────────────────────────

    @GetMapping("/")
    public String home(ThreadContext ctx, HttpSession session, Model model) {
        String threadId = UUID.randomUUID().toString();
        session.setAttribute("threadId", threadId);
        populateChatModel(model, ctx.userId(), threadId, "latest", null);
        return "chat";
    }

    @GetMapping("/chat/{threadId}")
    public String chat(@PathVariable String threadId, ThreadContext ctx, HttpSession session, Model model) {
        session.setAttribute("threadId", threadId);
        String userId = ctx.userId();
        ThreadMeta meta = threadMetaService.findById(userId, threadId).orElse(null);
        String version = meta != null ? meta.version() : "latest";
        populateChatModel(model, userId, threadId, version, meta);
        if (meta != null) {
            model.addAttribute("historyCount", threadMetaService.countTurns(userId, threadId));
            var turns = memoryService.getTurns(userId, threadId);
            model.addAttribute("turns", turns);
            model.addAttribute("turnImageRefsByTurnId", memoryService.getTurnImageRefs(userId, threadId));
            // 저장해 둔 검증 결과로 배지를 되살린다 (§6.24 Step 4-b) — 값이 없는 턴은 맵에
            // 아예 없고, 그게 곧 "배지 없음"이다(이 컬럼 이전의 모든 턴 + meta/Direct·S 턴).
            model.addAttribute("verificationByTurnId",
                    memoryService.getVerifications(turns.stream().map(t -> t.id()).toList()));
                if (questionReuseService != null) {
                // 저장해 둔 검색 진단 수치를 다시 붙인다 — 목록 자체는 현재 청크 기준으로
                // 재구성된 쪽이 권위이고(라벨·미리보기·삭제 placeholder), 수치만 chunkId로 병합된다.
                model.addAttribute("turnSourcesByTurnId",
                    retrievalMetricsService.enrich(
                        turns.stream().collect(Collectors.toMap(
                            t -> t.id(),
                            t -> questionReuseService.sourceRefsForTurn(t.id())))));
                }
        }
        return "chat";
    }

    // ── Chat actions ──────────────────────────────────────────────────

    @PostMapping("/ui/chat/new")
    public String newChat(HttpSession session) {
        String threadId = UUID.randomUUID().toString();
        session.setAttribute("threadId", threadId);
        return "redirect:/chat/" + threadId;
    }

    /**
     * §6.10 — fired when the user starts typing. Cold-start safety net for threads whose summary
     * cache hasn't been warmed yet (the primary trigger is now right after each answer — see
     * {@link ConversationSummarizerService#precomputeAfterTurn}). Runs in the background, never
     * blocks the caller.
     */
    @PostMapping("/ui/chat/summary/precompute")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void precomputeSummary(ThreadContext ctx, @RequestParam String threadId) {
        String userId = ctx.userId();
        Locale locale = ctx.locale();
        Thread.ofVirtual().start(() -> summarizerService.precompute(userId, threadId, locale));
    }

    @PostMapping(value = "/ui/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(ThreadContext ctx,
                                 @ModelAttribute ChatForm form,
                                 @RequestParam(name = "response-mode-radio", required = false) String responseModeRadio) {
        final ChatForm normalizedForm = normalizeResponseMode(form, responseModeRadio);
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMs());
        if (normalizedForm.question() == null || normalizedForm.question().isBlank()) {
            emitter.completeWithError(new IllegalArgumentException("question is blank"));
            return emitter;
        }
        String userId = ctx.userId();
        threadMetaService.getOrCreate(userId, normalizedForm.threadId(), normalizedForm.version());
        threadMetaService.updateTags(userId, normalizedForm.threadId(), normalizedForm.selectedTags());
        Thread worker = Thread.ofVirtual().start(() -> streamingAgentService.run(userId, normalizedForm, emitter));
        emitter.onTimeout(() -> {
            log.warn("[TIMEOUT:SSE] thread={} timeoutMs={} (app.sse-timeout-seconds={}s)",
                    normalizedForm.threadId(), props.sseTimeoutMs(), props.sseTimeoutMs() / 1000);
            worker.interrupt();
        });
        emitter.onError(t -> {
            log.warn("[SSE] emitter error thread={} type={} msg={}", normalizedForm.threadId(),
                    t == null ? "null" : t.getClass().getSimpleName(),
                    t == null ? "null" : t.getMessage());
            worker.interrupt();
        });
        emitter.onCompletion(() -> {
            log.debug("[SSE] completed thread={}", normalizedForm.threadId());
            worker.interrupt();
        });
        return emitter;
    }

    /**
     * User clicked "건너뛰기" on the "이미지 분석 중 (N/M)" indicator — stop waiting for the
     * remaining Lazy Vision calls and let the answer proceed with whatever's already described.
     * Distinct from the full "중지" button (which aborts the SSE connection outright, see
     * {@link #streamChat}): this keeps the turn running, so it's a plain 204, not a stream
     * teardown. A no-op (still 204) if the turn isn't currently in the image-analysis phase — the
     * click race (button clicked just as analysis finishes) is harmless either way.
     */
    @PostMapping("/ui/chat/stream/skip-images")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skipImageAnalysis(@RequestParam String threadId) {
        imageSkipRegistry.requestSkip(threadId);
    }

    @PostMapping("/ui/chat")
    public String postChat(ThreadContext ctx,
                           @ModelAttribute ChatForm form,
                           @RequestParam(name = "response-mode-radio", required = false) String responseModeRadio,
                           Model model, HttpServletResponse response) {
        form = normalizeResponseMode(form, responseModeRadio);
        if (form.question() == null || form.question().isBlank()) {
            return "fragments/message-error :: message";
        }
        String userId = ctx.userId();
        try {
            threadMetaService.getOrCreate(userId, form.threadId(), form.version());
            threadMetaService.updateTags(userId, form.threadId(), form.selectedTags());

            RoutingMode rm = null;
            if (form.routingMode() != null && !form.routingMode().isBlank()) {
                try { rm = RoutingMode.valueOf(form.routingMode()); } catch (IllegalArgumentException ignored) {}
            }
            log.debug("[postChat] threadId={} directMode={} routingMode={} question={}",
                    form.threadId(), form.isDirectMode(), rm, form.question());
            ChatRequest req = new ChatRequest(form.question(), form.version(), form.threadId(), rm,
                    form.isDirectMode(), form.selectedTags(), form.responseModeOrDefault());
            ChatResponse resp = agentService.chat(ctx, req);
            log.debug("[postChat] done — provider={} tokens={}/{} directMode={}",
                    resp.usedProvider(), resp.totalInputTokens(), resp.totalOutputTokens(), form.isDirectMode());

            threadMetaService.generateTitleAsync(userId, form.threadId(), form.version(), form.question());

            String receivedAt = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            model.addAttribute("receivedAt", receivedAt);
            model.addAttribute("threadId", form.threadId());
            model.addAttribute("turnId", resp.turnId());
            model.addAttribute("answer", resp.answer());
            model.addAttribute("questionType", resp.questionType());
            model.addAttribute("sources", resp.sources());
            model.addAttribute("imageRefs", resp.imageRefs());
            model.addAttribute("totalInputTokens", resp.totalInputTokens());
            model.addAttribute("totalOutputTokens", resp.totalOutputTokens());
            model.addAttribute("llmCallCount", resp.llmCallCount());
            model.addAttribute("elapsedSeconds", resp.elapsedSeconds());
            model.addAttribute("premiumUpgraded", resp.premiumUpgraded());
            model.addAttribute("grounded", resp.grounded());
            model.addAttribute("evalReason", resp.evalReason());
            model.addAttribute("envNote", resp.envNote());
            model.addAttribute("budgetNote", resp.budgetNote());
            // 배지 규칙은 VerificationSnapshot 한 곳에 있다 — 이 프래그먼트와 대화 기록
            // 루프가 같은 레코드를 읽는다(§6.24 Step 4-b). 조건을 템플릿에 풀어 쓰면
            // 두 렌더러가 갈라지고, 갈라진 것은 화면에서 보이지 않는다.
            model.addAttribute("verification", new VerificationSnapshot(
                    resp.grounded(), resp.generative(), resp.evalReason(), resp.envNote(),
                    resp.inventedSymbols(), resp.budgetNote()));
            model.addAttribute("usedProvider", resp.usedProvider());
            // 좋아요가 이 모드에서 실제로 동작하는가 — 서버가 성질로 계산한다
            // (SSE done 의 "proposable", 대화 기록의 Turn.proposable() 과 같은 값).
            model.addAttribute("proposable", form.responseModeOrDefault().allowsSubmission());
            model.addAttribute("curationBlockedKey",
                    form.responseModeOrDefault().submissionBlockedMessageKey());
        } catch (LlmContextOverflowException e) {
            // 하위 타입이라 반드시 소진 catch 보다 앞에 와야 한다(자바 규칙이자 이 구분의 전부다).
            log.warn("LLM context window exceeded: {}", e.getMessage());
            model.addAttribute("errorMessage", messageSource.getMessage(
                    "error.llm.context-overflow", null, LocaleContextHolder.getLocale()));
            return "fragments/message-error :: message";
        } catch (LlmProviderExhaustedException e) {
            log.warn("LLM providers exhausted: {}", e.getMessage());
            model.addAttribute("errorMessage", messageSource.getMessage(
                    "error.llm.exhausted", null, LocaleContextHolder.getLocale()));
            return "fragments/message-error :: message";
        } catch (Exception e) {
            log.error("Chat error", e);
            return "fragments/message-error :: message";
        }
        response.setHeader("HX-Trigger", "refreshThreadList");
        return "fragments/message-assistant :: message";
    }

    // ── REST API ──────────────────────────────────────────────────────

    @PostMapping("/api/v1/chat")
    @ResponseBody
    public ResponseEntity<ChatResponse> chatApi(ThreadContext ctx, @RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ChatResponse response = agentService.chat(ctx, withAvailableResponseMode(request));
        return ResponseEntity.ok(response);
    }

    /**
     * 응답 모드를 <b>저장될 값</b>으로 확정한다 — 라디오 파라미터(있으면 우선)와 hidden 필드 중
     * 하나를 고르고, 운영자가 꺼 둔 모드는 {@link SettingsService#effectiveResponseMode}로 강등한다.
     *
     * <p>라디오가 비었을 때 폼을 그대로 돌려주던 예전 구현은 <b>운영자 스위치를 통째로 비껴갔다</b> —
     * 그 경로(직접 만든 POST, 라디오 없는 클라이언트)에서는 hidden 필드의 값이 검사 없이 흘러갔다.
     * 이제 두 경우 모두 같은 판정을 지난다.
     *
     * <p>강등을 여기서 하는 이유는 {@code ChatForm} 이 레코드라 설정 계층에 닿을 수 없기 때문이다
     * (Direct 배타 가드는 요청 자체에서 파생되므로 레코드 안에 있다). HTMX·SSE 두 경로가 모두
     * 이 메서드를 지나므로 저장되는 {@code response_mode} 도 실제로 답한 모드와 일치한다.
     */
    private ChatForm normalizeResponseMode(ChatForm form, String responseModeRadio) {
        String raw = (responseModeRadio == null || responseModeRadio.isBlank())
                ? form.responseMode()
                : responseModeRadio;
        ResponseMode selected = settingsService.effectiveResponseMode(ResponseMode.parse(raw));
        return new ChatForm(
                form.question(),
                form.threadId(),
                form.version(),
                form.routingMode(),
                form.directMode(),
                form.tags(),
                selected.name());
    }

    /**
     * REST 요청의 응답 모드에 같은 운영자 스위치를 적용한다 — 채팅 UI 를 거치지 않는 경로라
     * 버튼 감추기가 존재하지 않고, 서버 판정만이 유일한 관문이다({@code ChatRequest} 의 Direct 배타
     * 가드와 같은 자리, 다른 이유). 항상 새 레코드를 만들어 compact 생성자의 기존 가드도 다시 지난다.
     */
    private ChatRequest withAvailableResponseMode(ChatRequest request) {
        return new ChatRequest(
                request.question(),
                request.version(),
                request.threadId(),
                request.routingMode(),
                request.directMode(),
                request.selectedTags(),
                settingsService.effectiveResponseMode(request.responseMode()));
    }

    @GetMapping("/api/v1/questions/suggest")
    @ResponseBody
    public List<QuestionReuseService.Suggestion> suggestQuestions(
            ThreadContext ctx,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "scope", defaultValue = "shared") String scope,
            @RequestParam(name = "limit", defaultValue = "12") int limit) {
        if (questionReuseService == null || q == null || q.strip().length() < 2) return List.of();
        // 이전 질문 제안은 최대 12개 (요청이 더 크게 와도 서버에서 자른다)
        int bounded = Math.max(1, Math.min(limit, MAX_QUESTION_SUGGESTIONS));
        return questionReuseService.suggest(ctx.userId(), QuestionReuseService.Scope.SHARED, q, bounded);
    }

    @PostMapping("/api/v1/questions/reuse")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reuseQuestionAnswer(
            ThreadContext ctx,
            @RequestParam("turnId") long turnId,
            @RequestParam("threadId") String threadId,
            @RequestParam(name = "version", defaultValue = "latest") String version,
            @RequestParam(name = "scope", defaultValue = "shared") String scope) {
        if (questionReuseService == null) {
            return ResponseEntity.ok(Map.of(
                "reused", false,
                "fallback", true,
                "message", "질문 재사용 기능을 사용할 수 없습니다."));
        }

        QuestionReuseService.ReuseLookup lookup =
            questionReuseService.reuseLookup(ctx.userId(), QuestionReuseService.Scope.SHARED, turnId);
        if (!lookup.reusable()) {
            return ResponseEntity.ok(Map.of(
                "reused", false,
                "fallback", true,
                "question", lookup.question() == null ? "" : lookup.question(),
                "message", lookup.reason() == null ? "검증에 실패하여 일반 질의로 전환합니다." : lookup.reason()));
        }

        String askedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC).format(Instant.now());
        long savedTurnId = memoryService.addTurn(
            ctx.userId(), threadId,
            lookup.question(), "",
            askedAt, 0, 0, 0,
            "db-reuse", 0, "M", "", lookup.sourceTurnId());
        questionReuseService.cloneTurnSources(lookup.sourceTurnId(), savedTurnId, ctx.userId(), threadId);
        threadMetaService.generateTitleAsync(ctx.userId(), threadId, version, lookup.question());

        return ResponseEntity.ok(Map.of(
            "reused", true,
            "turnId", savedTurnId,
            "question", lookup.question(),
            "answer", lookup.answer(),
            "sources", questionReuseService.sourceRefsForTurn(lookup.sourceTurnId()),
            "sourceChunkIds", lookup.sourceChunkIds(),
            "sourceTurnId", lookup.sourceTurnId(),
            "provider", "db-reuse"));
    }

    /**
     * Full untruncated chunk text for the chat 출처 badge's click-to-expand "원문 보기" modal — the
     * badge itself only ever carries the truncated hover-preview text (§UI 출처 hover 미리보기 길이).
     * Not admin-gated: document/curated-Q&A storage is shared with no per-user isolation, and any
     * indexed content here is already reachable indirectly through chat retrieval, so a direct
     * lookup by (unguessable) chunk id exposes nothing new.
     */
    @GetMapping("/api/v1/chunks/{chunkId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> chunkFullText(@PathVariable String chunkId) {
        String text = questionReuseService == null ? null : questionReuseService.chunkFullText(chunkId);
        if (text == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "청크를 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(Map.of("content", text));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void populateChatModel(Model model, String userId, String threadId, String version, ThreadMeta meta) {
        model.addAttribute("threadId", threadId);
        model.addAttribute("version", version);
        model.addAttribute("meta", meta);
        model.addAttribute("threads", threadMetaService.getAll(userId));
        model.addAttribute("activeThreadId", threadId);
        model.addAttribute("hasLocalProvider", llmRouter.hasLocalProvider());
        model.addAttribute("localOnlyDeployment", llmRouter.getDefaultMode() == RoutingMode.LOCAL_ONLY);
        model.addAttribute("routingMode", meta != null ? meta.routingMode() : "COST_FIRST");
        model.addAttribute("creativeModeEnabled", settingsService.creativeModeEnabled());
        model.addAttribute("sourcePreviewEnabled", settingsService.sourcePreviewEnabled());
        model.addAttribute("retrievalMetricsEnabled", settingsService.retrievalMetricsEnabled());
    }
}
