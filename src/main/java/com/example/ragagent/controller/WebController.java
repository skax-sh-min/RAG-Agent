package com.example.ragagent.controller;

import com.example.ragagent.model.*;
import com.example.ragagent.service.*;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Serves Thymeleaf pages and HTMX fragments.
 * Calls service layer directly — does not delegate to ApiController.
 */
@Controller
public class WebController {

    private static final Logger log = LoggerFactory.getLogger(WebController.class);

    private final AgentService agentService;
    private final RagService ragService;
    private final ThreadMetaService threadMetaService;
    private final MemoryService memoryService;

    public WebController(AgentService agentService, RagService ragService,
                         ThreadMetaService threadMetaService, MemoryService memoryService) {
        this.agentService = agentService;
        this.ragService = ragService;
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
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
        }
        return "chat";
    }

    @GetMapping("/documents")
    public String documents(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
        return "documents";
    }

    // ── Chat actions ──────────────────────────────────────────────────────

    @PostMapping("/ui/chat/new")
    public String newChat(HttpSession session) {
        String threadId = UUID.randomUUID().toString();
        session.setAttribute("threadId", threadId);
        return "redirect:/chat/" + threadId;
    }

    /** Full implementation in step 9. */
    @PostMapping("/ui/chat")
    public String postChat(@ModelAttribute ChatForm form, Model model) {
        if (form.question() == null || form.question().isBlank()) {
            return "fragments/message-error :: message";
        }
        try {
            threadMetaService.getOrCreate(form.threadId(), form.version());

            ChatRequest req = new ChatRequest(form.question(), form.version(), form.threadId());
            com.example.ragagent.model.ChatResponse resp = agentService.chat(req);

            memoryService.addTurn(form.threadId(), form.question(), resp.answer());
            threadMetaService.generateTitleAsync(form.threadId(), form.version(), form.question());

            model.addAttribute("answer", resp.answer());
            model.addAttribute("questionType", resp.questionType());
            model.addAttribute("sources", resp.sources());
            model.addAttribute("totalInputTokens", resp.totalInputTokens());
            model.addAttribute("totalOutputTokens", resp.totalOutputTokens());
            model.addAttribute("llmCallCount", resp.llmCallCount());
            model.addAttribute("elapsedSeconds", resp.elapsedSeconds());
        } catch (Exception e) {
            log.error("Chat error", e);
            model.addAttribute("errorMessage", "답변 생성 중 오류가 발생했습니다.");
            return "fragments/message-error :: message";
        }
        return "fragments/message-assistant :: message";
    }

    // ── Thread management ─────────────────────────────────────────────────

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

    /** Full implementation in step 8. */
    @PostMapping("/ui/documents/upload")
    @ResponseBody
    public ResponseEntity<DocumentInfo> uploadDocument(
            @RequestParam MultipartFile file,
            @RequestParam(defaultValue = "latest") String version) {
        return ResponseEntity.badRequest().build();
    }

    /** Full implementation in step 7. */
    @PostMapping("/ui/documents/sync")
    public String syncDocuments(
            @RequestParam(defaultValue = "latest") String version,
            Model model) {
        model.addAttribute("success", false);
        model.addAttribute("message", "준비 중");
        model.addAttribute("indexed", List.of());
        return "fragments/sync-result :: result";
    }

    /** Full implementation in step 7. */
    @DeleteMapping("/ui/documents/{docId}")
    @ResponseBody
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String docId,
            @RequestParam(defaultValue = "latest") String version) {
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ui/documents/list")
    public String documentList(Model model) {
        model.addAttribute("documents", ragService.listDocuments());
        return "fragments/doc-table-body :: body";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void populateChatModel(Model model, String threadId, String version, ThreadMeta meta) {
        model.addAttribute("threadId", threadId);
        model.addAttribute("version", version);
        model.addAttribute("meta", meta);
        model.addAttribute("threads", threadMetaService.getAll());
        model.addAttribute("activeThreadId", threadId);
    }
}
