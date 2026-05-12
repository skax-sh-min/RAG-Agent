package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.*;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serves Thymeleaf pages and HTMX fragments.
 * Calls service layer directly — does not delegate to ApiController.
 */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final AgentService agentService;
    private final StreamingAgentService streamingAgentService;
    private final RagService ragService;
    private final ThreadMetaService threadMetaService;
    private final MemoryService memoryService;
    private final AppProperties props;
    private final LlmUsageRepository usageRepo;
    private final CircuitBreaker circuitBreaker;
    private final LlmRouter llmRouter;

    public WebController(AgentService agentService,
                         StreamingAgentService streamingAgentService,
                         RagService ragService,
                         ThreadMetaService threadMetaService, MemoryService memoryService,
                         AppProperties props, LlmUsageRepository usageRepo,
                         CircuitBreaker circuitBreaker, LlmRouter llmRouter) {
        this.agentService = agentService;
        this.streamingAgentService = streamingAgentService;
        this.ragService = ragService;
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.props = props;
        this.usageRepo = usageRepo;
        this.circuitBreaker = circuitBreaker;
        this.llmRouter = llmRouter;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/")
    public String home(HttpSession session, Model model) {
        String threadId = UUID.randomUUID().toString();
        session.setAttribute("threadId", threadId);
        populateChatModel(model, threadId, "latest", null);
        return "chat";
    }

    @GetMapping("/chat/{threadId}")
    public String chat(@PathVariable String threadId, HttpSession session, Model model) {
        session.setAttribute("threadId", threadId);
        ThreadMeta meta = threadMetaService.findById(threadId).orElse(null);
        String version = meta != null ? meta.version() : "latest";
        populateChatModel(model, threadId, version, meta);
        if (meta != null) {
            model.addAttribute("historyCount", threadMetaService.countTurns(threadId));
            model.addAttribute("turns", memoryService.getTurns(threadId));
        }
        return "chat";
    }

    @GetMapping("/documents")
    public String documents(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
        return "documents";
    }

    @GetMapping("/llm-usage")
    public String llmUsage(Model model) {
        model.addAttribute("reports", buildProviderReports());
        return "llm-usage";
    }

    // ── Chat actions ──────────────────────────────────────────────────────

    @PostMapping("/ui/chat/new")
    public String newChat(HttpSession session) {
        String threadId = UUID.randomUUID().toString();
        session.setAttribute("threadId", threadId);
        return "redirect:/chat/" + threadId;
    }

    @PostMapping(value = "/ui/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@ModelAttribute ChatForm form) {
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMs());
        if (form.question() == null || form.question().isBlank()) {
            emitter.completeWithError(new IllegalArgumentException("question is blank"));
            return emitter;
        }
        threadMetaService.getOrCreate(form.threadId(), form.version());
        Thread worker = Thread.ofVirtual().start(() -> streamingAgentService.run(form, emitter));
        // B-20: cancel the LLM worker when the SSE connection ends (timeout, error, or normal close)
        emitter.onTimeout(worker::interrupt);
        emitter.onError(t -> worker.interrupt());
        emitter.onCompletion(worker::interrupt);
        return emitter;
    }

    @PostMapping("/ui/chat")
    public String postChat(@ModelAttribute ChatForm form, Model model, HttpServletResponse response) {
        if (form.question() == null || form.question().isBlank()) {
            return "fragments/message-error :: message";
        }
        try {
            threadMetaService.getOrCreate(form.threadId(), form.version());

            RoutingMode rm = null;
            if (form.routingMode() != null && !form.routingMode().isBlank()) {
                try { rm = RoutingMode.valueOf(form.routingMode()); } catch (IllegalArgumentException ignored) {}
            }
            log.debug("[postChat] threadId={} directMode={} routingMode={} question={}",
                    form.threadId(), form.isDirectMode(), rm, form.question());
            ChatRequest req = new ChatRequest(form.question(), form.version(), form.threadId(), rm, form.isDirectMode());
            com.example.ragagent.model.ChatResponse resp = agentService.chat(req);
            log.debug("[postChat] done — provider={} tokens={}/{} directMode={}",
                    resp.usedProvider(), resp.totalInputTokens(), resp.totalOutputTokens(), form.isDirectMode());

            threadMetaService.generateTitleAsync(form.threadId(), form.version(), form.question());

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
                model.addAttribute("usedProvider", resp.usedProvider());
                model.addAttribute("tabId", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                response.setHeader("HX-Trigger", "refreshThreadList");
                return "fragments/message-assistant-dual :: message";
            }
        } catch (Exception e) {
            log.error("Chat error", e);
            return "fragments/message-error :: message";
        }
        response.setHeader("HX-Trigger", "refreshThreadList");
        return "fragments/message-assistant :: message";
    }

    // ── Thread management ─────────────────────────────────────────────────

    @PatchMapping("/ui/threads/{threadId}/routing-mode")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRoutingMode(@PathVariable String threadId,
                                   @RequestParam String routingMode) {
        threadMetaService.updateRoutingMode(threadId, routingMode);
    }

    @PatchMapping("/ui/threads/{threadId}/title")
    public String updateTitle(@PathVariable String threadId,
                              @RequestParam String title, Model model) {
        threadMetaService.updateTitle(threadId, title);
        model.addAttribute("thread", threadMetaService.findById(threadId).orElse(null));
        model.addAttribute("activeThreadId", threadId);
        return "fragments/thread-item :: item";
    }

    @DeleteMapping("/ui/threads/{threadId}")
    @ResponseBody
    public ResponseEntity<Void> deleteThread(@PathVariable String threadId) {
        memoryService.clearHistory(threadId);
        threadMetaService.delete(threadId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/ui/threads")
    public String threadList(@RequestParam(required = false) String activeThreadId, Model model) {
        model.addAttribute("threads", threadMetaService.getAll());
        model.addAttribute("activeThreadId", activeThreadId);
        return "fragments/thread-list :: list";
    }

    // ── Document actions ──────────────────────────────────────────────────

    @PostMapping("/ui/documents/upload")
    @ResponseBody
    public ResponseEntity<DocumentInfo> uploadDocument(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "latest") String version) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        String originalFilename = file.getOriginalFilename() != null
                ? Path.of(file.getOriginalFilename()).getFileName().toString() : "upload";
        if (!RagService.isSupportedExtension(originalFilename)) {
            log.warn("Rejected upload: unsupported extension ({})", originalFilename);
            return ResponseEntity.unprocessableEntity().build();
        }
        try {
            Path tmp = Files.createTempFile("rag-upload-", "-" + originalFilename);
            try {
                file.transferTo(tmp);
                DocumentInfo info = ragService.indexDocument(tmp, originalFilename, version);
                return ResponseEntity.ok(info);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            log.error("Upload error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/ui/documents/sync")
    public String syncDocuments(
            @RequestParam(defaultValue = "latest") String version,
            Model model) {
        try {
            SyncResult result = ragService.syncDirectory(version);
            model.addAttribute("success", true);
            model.addAttribute("indexed", result.indexed());
            model.addAttribute("updated", result.updated());
            model.addAttribute("deleted", result.deleted());
        } catch (Exception e) {
            log.error("Sync error", e);
            model.addAttribute("success", false);
            model.addAttribute("indexed", List.of());
            model.addAttribute("updated", List.of());
            model.addAttribute("deleted", List.of());
        }
        return "fragments/sync-result :: result";
    }

    @DeleteMapping("/ui/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String docId,
            @RequestParam(defaultValue = "latest") String version) {
        try {
            ragService.deleteDocument(docId, version);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Document delete error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/ui/documents/list")
    public String documentList(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
        return "fragments/doc-table-body :: body";
    }

    // ── LLM usage ─────────────────────────────────────────────────────────

    /** HTMX fragment — auto-refreshed every 30 s from the llm-usage page. */
    @GetMapping("/ui/llm-usage/cards")
    public String llmUsageCards(Model model) {
        model.addAttribute("reports", buildProviderReports());
        return "fragments/llm-usage-cards :: cards";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void populateChatModel(Model model, String threadId, String version, ThreadMeta meta) {
        model.addAttribute("threadId", threadId);
        model.addAttribute("version", version);
        model.addAttribute("meta", meta);
        model.addAttribute("threads", threadMetaService.getAll());
        model.addAttribute("activeThreadId", threadId);
        model.addAttribute("hasLocalProvider", llmRouter.hasLocalProvider());
        model.addAttribute("routingMode", meta != null ? meta.routingMode() : "COST_FIRST");
    }

    private List<LlmProviderReport> buildProviderReports() {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        return props.llmSafe().providers().stream()
                .map(cfg -> new LlmProviderReport(
                        cfg.name(),
                        cfg.type(),
                        cfg.role(),
                        cfg.model(),
                        usageRepo.getDaily(cfg.name()),
                        usageRepo.getWeekly(cfg.name()),
                        usageRepo.getMonthly(cfg.name()),
                        blocked.get(cfg.name())
                ))
                .toList();
    }
}
