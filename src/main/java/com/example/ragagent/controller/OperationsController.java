package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.TrackingEmbeddingModel;
import com.example.ragagent.model.LlmProviderReport;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Thread management, LLM usage stats: UI pages, HTMX fragments,
 * and REST /api/v1/health + /api/v1/llm/usage.
 */
@Controller
public class OperationsController {

    private final ThreadMetaService threadMetaService;
    private final MemoryService memoryService;
    private final LlmUsageRepository usageRepo;
    private final AppProperties props;
    private final CircuitBreaker circuitBreaker;
    private final AuditLogger auditLogger;

    public OperationsController(ThreadMetaService threadMetaService,
                                MemoryService memoryService,
                                LlmUsageRepository usageRepo,
                                AppProperties props,
                                CircuitBreaker circuitBreaker,
                                AuditLogger auditLogger) {
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.usageRepo = usageRepo;
        this.props = props;
        this.circuitBreaker = circuitBreaker;
        this.auditLogger = auditLogger;
    }

    // ── Page ──────────────────────────────────────────────────────────

    // ── Thread management ─────────────────────────────────────────────

    @PatchMapping("/ui/threads/{threadId}/routing-mode")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRoutingMode(ThreadContext ctx, @PathVariable String threadId,
                                   @RequestParam String routingMode) {
        threadMetaService.updateRoutingMode(ctx.userId(), threadId, routingMode);
        auditLogger.log("thread.routing-mode", threadId, Map.of("mode", routingMode));
    }

    @PatchMapping("/ui/threads/{threadId}/title")
    public String updateTitle(ThreadContext ctx, @PathVariable String threadId,
                              @RequestParam String title, Model model) {
        String userId = ctx.userId();
        threadMetaService.updateTitle(userId, threadId, title);
        model.addAttribute("thread", threadMetaService.findById(userId, threadId).orElse(null));
        model.addAttribute("activeThreadId", threadId);
        return "fragments/thread-item :: item";
    }

    @DeleteMapping("/ui/threads/{threadId}")
    @ResponseBody
    public ResponseEntity<Void> deleteThread(ThreadContext ctx, @PathVariable String threadId) {
        String userId = ctx.userId();
        memoryService.clearHistory(userId, threadId);
        threadMetaService.delete(userId, threadId);
        auditLogger.log("thread.delete", threadId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/ui/threads")
    public String threadList(ThreadContext ctx,
                             @RequestParam(required = false) String activeThreadId, Model model) {
        model.addAttribute("threads", threadMetaService.getAll(ctx.userId()));
        model.addAttribute("activeThreadId", activeThreadId);
        return "fragments/thread-list :: list";
    }

    // ── Turn feedback (like/dislike) ─────────────────────────────────────

    private static final Set<String> VALID_FEEDBACK = Set.of("LIKE", "DISLIKE", "NONE");

    /**
     * DISLIKE is a hard-exclusion signal consumed by MemoryRepository.getHistory() —
     * disliked turns drop out of future prompt context. LIKE is stored for future use only.
     */
    @PatchMapping("/ui/threads/{threadId}/turns/{turnId}/feedback")
    @ResponseBody
    public ResponseEntity<Void> updateTurnFeedback(ThreadContext ctx, @PathVariable String threadId,
                                                    @PathVariable long turnId, @RequestParam String feedback) {
        String normalized = feedback == null ? "" : feedback.strip().toUpperCase();
        if (!VALID_FEEDBACK.contains(normalized)) {
            throw new IllegalArgumentException("feedback must be one of " + VALID_FEEDBACK);
        }
        String userId = ctx.userId();
        var existing = memoryService.getFeedback(userId, threadId, turnId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        String dbValue = "NONE".equals(normalized) ? null : normalized;
        memoryService.updateFeedback(userId, threadId, turnId, dbValue);
        auditLogger.log("turn.feedback", threadId, Map.of(
                "turnId", turnId,
                "from", existing.get().feedback() == null ? "NONE" : existing.get().feedback(),
                "to", normalized));
        return ResponseEntity.noContent().build();
    }

    // ── LLM usage ─────────────────────────────────────────────────────

    @GetMapping("/llm-usage")
    public String llmUsagePage(Model model) {
        model.addAttribute("reports", buildProviderReports());
        return "llm-usage";
    }

    /** HTMX fragment — auto-refreshed every 30 s from the llm-usage page. */
    @GetMapping("/ui/llm-usage/cards")
    public String llmUsageCards(Model model) {
        model.addAttribute("reports", buildProviderReports());
        return "fragments/llm-usage-cards :: cards";
    }

    // ── REST API ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/health")
    @ResponseBody
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "rag-agent",
                "timestamp", Instant.now().toString()
        );
    }

    /** Provider-level daily / weekly / monthly summary + Circuit Breaker state, plus one embedding row (§6.6). */
    @GetMapping("/api/v1/llm/usage")
    @ResponseBody
    public List<UsageReport> getLlmUsage() {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        Stream<UsageReport> chatUsage = visibleChatProviders().stream()
                .map(cfg -> {
                    String name = cfg.name();
                    Instant until = blocked.get(name);
                    return new UsageReport(
                            name,
                            cfg.type(),
                            cfg.model(),
                            usageRepo.getDaily(name),
                            usageRepo.getWeekly(name),
                            usageRepo.getMonthly(name),
                            until != null ? until.toString() : null
                    );
                });
        String embedName = embeddingProviderName();
        UsageReport embedUsage = new UsageReport(
                embedName,
                "EMBEDDING",
                props.embeddingSafe().model(),
                usageRepo.getDaily(embedName),
                usageRepo.getWeekly(embedName),
                usageRepo.getMonthly(embedName),
                null
        );
        return Stream.concat(chatUsage, Stream.of(embedUsage)).toList();
    }

    /** Daily token history per provider for Chart.js stacked bar chart, plus the embedding row (§6.6). */
    @GetMapping("/api/v1/llm/usage/history")
    @ResponseBody
    public Map<String, List<LlmUsageRepository.DailyRow>> getLlmUsageHistory(
            @RequestParam(defaultValue = "30") int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        Map<String, List<LlmUsageRepository.DailyRow>> history = new HashMap<>();
        for (AppProperties.ProviderConfig cfg : visibleChatProviders()) {
            history.put(cfg.name(), usageRepo.getDailyHistory(cfg.name(), safeDays));
        }
        String embedName = embeddingProviderName();
        history.put(embedName, usageRepo.getDailyHistory(embedName, safeDays));
        return history;
    }

    // ── Response records ──────────────────────────────────────────────

    public record UsageReport(
            String provider,
            String type,
            String model,
            LlmUsageRepository.PeriodSummary daily,
            LlmUsageRepository.PeriodSummary weekly,
            LlmUsageRepository.PeriodSummary monthly,
            String blockedUntil
    ) {}

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Chat provider cards/rows plus one always-shown embedding card (§6.6). Embedding has no
     * ProviderRole (role=null → no role badge) and is never circuit-broken (blockedUntil=null) —
     * the "EMBEDDING" type badge is what visually separates it from chat providers.
     */
    private List<LlmProviderReport> buildProviderReports() {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        Stream<LlmProviderReport> chatReports = visibleChatProviders().stream()
                .map(cfg -> new LlmProviderReport(
                        cfg.name(),
                        cfg.type(),
                        cfg.role(),
                        cfg.model(),
                        usageRepo.getDaily(cfg.name()),
                        usageRepo.getWeekly(cfg.name()),
                        usageRepo.getMonthly(cfg.name()),
                        blocked.get(cfg.name()),
                        isConfigured(cfg)
                ));
        String embedName = embeddingProviderName();
        LlmProviderReport embedReport = new LlmProviderReport(
                embedName,
                "EMBEDDING",
                null,
                props.embeddingSafe().model(),
                usageRepo.getDaily(embedName),
                usageRepo.getWeekly(embedName),
                usageRepo.getMonthly(embedName),
                null,
                true
        );
        return Stream.concat(chatReports, Stream.of(embedReport)).toList();
    }

    /**
     * Chat providers to surface in the cards/table/chart (§6.7). Configured providers always
     * show, even with zero usage. Unconfigured (no API key) providers show only if they have
     * historical usage — hides never-used placeholder providers while preserving history for
     * ones that were used and later had their key removed. Applied once here so all three
     * usage surfaces (cards, REST usage, REST history) agree on the same visible set.
     */
    private List<AppProperties.ProviderConfig> visibleChatProviders() {
        Set<String> used = usageRepo.usedProviders();
        return props.llmSafe().providers().stream()
                .filter(cfg -> isConfigured(cfg) || used.contains(cfg.name()))
                .toList();
    }

    private static boolean isConfigured(AppProperties.ProviderConfig cfg) {
        return cfg.apiKey() != null && !cfg.apiKey().isBlank();
    }

    /** {@code "embed:" + model} — matches the key TrackingEmbeddingModel records under. */
    private String embeddingProviderName() {
        String model = props.embeddingSafe().model();
        return TrackingEmbeddingModel.PROVIDER_PREFIX + (model != null ? model : "unknown");
    }
}
