package com.example.ragagent.controller;

import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.exception.LlmProviderExhaustedException;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.*;
import com.example.ragagent.service.AgentService;
import com.example.ragagent.service.ConversationSummarizerService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.StreamingAgentService;
import com.example.ragagent.service.ThreadMetaService;
import com.example.ragagent.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
    private final AppProperties props;
    private final LlmRouter llmRouter;
    private final MessageSource messageSource;

    public ChatController(AgentService agentService,
                          StreamingAgentService streamingAgentService,
                          ThreadMetaService threadMetaService,
                          MemoryService memoryService,
                          ConversationSummarizerService summarizerService,
                          AppProperties props,
                          LlmRouter llmRouter,
                          MessageSource messageSource) {
        this.agentService = agentService;
        this.streamingAgentService = streamingAgentService;
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.summarizerService = summarizerService;
        this.props = props;
        this.llmRouter = llmRouter;
        this.messageSource = messageSource;
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
            model.addAttribute("turns", memoryService.getTurns(userId, threadId));
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

    @PostMapping("/ui/chat")
    public String postChat(ThreadContext ctx, @ModelAttribute ChatForm form,
                           Model model, HttpServletResponse response) {
        if (form.question() == null || form.question().isBlank()) {
            return "fragments/message-error :: message";
        }
        String userId = ctx.userId();
        try {
            threadMetaService.getOrCreate(userId, form.threadId(), form.version());

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
            model.addAttribute("usedProvider", resp.usedProvider());
            if (resp.dualLocalAnswer() != null) {
                model.addAttribute("dualLocalAnswer", resp.dualLocalAnswer());
                model.addAttribute("dualLocalProvider", resp.dualLocalProvider());
                model.addAttribute("tabId", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                response.setHeader("HX-Trigger", "refreshThreadList");
                return "fragments/message-assistant-dual :: message";
            }
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
    }
}
