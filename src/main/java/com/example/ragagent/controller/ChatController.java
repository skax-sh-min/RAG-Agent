package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.*;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ChatImageAnalysisSkipRegistry;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.QuestionReuseService;
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

/**
 * Chat pages (Thymeleaf), HTMX chat fragments, and REST /api/v1/chat.
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentService agentService;
    private final StreamingAgentService streamingAgentService;
    private final ThreadMetaService threadMetaService;
    private final MemoryService memoryService;
    private final ConversationSummarizerService summarizerService;
    private final CuratedQaService curatedQaService;
    private final AppProperties props;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;
    private final ChatImageAnalysisSkipRegistry imageSkipRegistry;
    private final QuestionReuseService questionReuseService;
    private final SettingsService settingsService;

    @Autowired
    public ChatController(AgentService agentService,
                          StreamingAgentService streamingAgentService,
                          ThreadMetaService threadMetaService,
                          MemoryService memoryService,
                          ConversationSummarizerService summarizerService,
                          CuratedQaService curatedQaService,
                          AppProperties props,
                          LlmRouter llmRouter,
                          MessageSource messageSource,
                          ChatImageAnalysisSkipRegistry imageSkipRegistry,
                          ObjectProvider<QuestionReuseService> questionReuseService,
                          SettingsService settingsService) {
        this.agentService = agentService;
        this.streamingAgentService = streamingAgentService;
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.summarizerService = summarizerService;
        this.curatedQaService = curatedQaService;
        this.props = props;
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
        this.imageSkipRegistry = imageSkipRegistry;
        this.questionReuseService = questionReuseService.getIfAvailable();
        this.settingsService = settingsService;
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
            // §10.10 embedding-fallback — badge for turns whose curated Q&A promotion never
            // managed to embed (surfaced here since it can only be known after the fact; the
            // background embed attempt runs seconds after the like, long past this page's
            // original response).
            model.addAttribute("curatedEmbedFailedTurnIds",
                    curatedQaService.findFailedTurnIds(turns.stream().map(t -> t.id()).toList()));
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
    public SseEmitter streamChat(ThreadContext ctx, @ModelAttribute ChatForm form) {
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMs());
        if (form.question() == null || form.question().isBlank()) {
            emitter.completeWithError(new IllegalArgumentException("question is blank"));
            return emitter;
        }
        String userId = ctx.userId();
        threadMetaService.getOrCreate(userId, form.threadId(), form.version());
        threadMetaService.updateTags(userId, form.threadId(), form.selectedTags());
        Thread worker = Thread.ofVirtual().start(() -> streamingAgentService.run(userId, form, emitter));
        emitter.onTimeout(() -> {
            log.warn("[TIMEOUT:SSE] thread={} timeoutMs={} (app.sse-timeout-seconds={}s)",
                    form.threadId(), props.sseTimeoutMs(), props.sseTimeoutMs() / 1000);
            worker.interrupt();
        });
        emitter.onError(t -> {
            log.warn("[SSE] emitter error thread={} type={} msg={}", form.threadId(),
                    t == null ? "null" : t.getClass().getSimpleName(),
                    t == null ? "null" : t.getMessage());
            worker.interrupt();
        });
        emitter.onCompletion(() -> {
            log.debug("[SSE] completed thread={}", form.threadId());
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
    public String postChat(ThreadContext ctx, @ModelAttribute ChatForm form,
                           Model model, HttpServletResponse response) {
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
            model.addAttribute("usedProvider", resp.usedProvider());
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
        ChatResponse response = agentService.chat(ctx, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/v1/questions/suggest")
    @ResponseBody
    public List<QuestionReuseService.Suggestion> suggestQuestions(
            ThreadContext ctx,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "scope", defaultValue = "shared") String scope,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        if (questionReuseService == null || q == null || q.strip().length() < 2) return List.of();
        int bounded = Math.max(1, Math.min(limit, 20));
        return questionReuseService.suggest(ctx.userId(), QuestionReuseService.Scope.parse(scope), q, bounded);
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
            questionReuseService.reuseLookup(ctx.userId(), QuestionReuseService.Scope.parse(scope), turnId);
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
            "sourceChunkIds", lookup.sourceChunkIds(),
            "sourceTurnId", lookup.sourceTurnId(),
            "provider", "db-reuse"));
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
        model.addAttribute("sourcePreviewEnabled", settingsService.sourcePreviewEnabled());
    }
}
