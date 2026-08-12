package com.example.ragagent.controller;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.context.ThreadContext;
import com.example.ragagent.llm.BackgroundLlmConcurrencyTracker;
import com.example.ragagent.llm.BackgroundUsage;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.TrackingEmbeddingModel;
import com.example.ragagent.model.LlmProviderReport;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.repository.MemoryRepository;
import com.example.ragagent.service.CuratedQaService;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
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
    private final CuratedQaService curatedQaService;
    private final LlmRouter llmRouter;
    private final EmbeddingConcurrencyTracker embeddingConcurrencyTracker;
    private final BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker;

    public OperationsController(ThreadMetaService threadMetaService,
                                MemoryService memoryService,
                                LlmUsageRepository usageRepo,
                                AppProperties props,
                                CircuitBreaker circuitBreaker,
                                AuditLogger auditLogger,
                                CuratedQaService curatedQaService,
                                LlmRouter llmRouter,
                                EmbeddingConcurrencyTracker embeddingConcurrencyTracker,
                                BackgroundLlmConcurrencyTracker backgroundConcurrencyTracker) {
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.usageRepo = usageRepo;
        this.props = props;
        this.circuitBreaker = circuitBreaker;
        this.auditLogger = auditLogger;
        this.curatedQaService = curatedQaService;
        this.llmRouter = llmRouter;
        this.embeddingConcurrencyTracker = embeddingConcurrencyTracker;
        this.backgroundConcurrencyTracker = backgroundConcurrencyTracker;
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
     * disliked turns drop out of future prompt context. LIKE promotes the turn into the
     * curated-Q&A search axis via {@link CuratedQaService} (§10.10).
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

        // §10.10 — promote/retract the curated-Q&A snapshot on a LIKE transition (either
        // direction). previous/normalized are both already-uppercased VALID_FEEDBACK members.
        String previous = existing.get().feedback() == null ? "NONE" : existing.get().feedback();
        if ("LIKE".equals(normalized) && !"LIKE".equals(previous)) {
            curatedQaService.onLike(userId, threadId, turnId);
        } else if (!"LIKE".equals(normalized) && "LIKE".equals(previous)) {
            curatedQaService.onUnlike(userId, threadId, turnId);
        }

        auditLogger.log("turn.feedback", threadId, Map.of(
                "turnId", turnId,
                "from", previous,
                "to", normalized));
        return ResponseEntity.noContent().build();
    }

    /**
     * §10.10 step ④ — owner-only inline edit of a turn's curated-Q&A entry (chat window "편집").
     * Ownership check reuses the exact same {@code getFeedback(userId, threadId, turnId)} scoping
     * as the feedback endpoint above — a thread only ever contains the current user's own turns,
     * so no separate authorization mechanism is needed (see PLAN.md §10.10 "UI 분리"). Admin edits
     * go through the separate {@code /admin/curated/{id}} endpoint (AdminController), which can
     * reach any user's entry.
     */
    @PatchMapping("/ui/threads/{threadId}/turns/{turnId}/curated")
    @ResponseBody
    public ResponseEntity<Void> updateCuratedAnswer(ThreadContext ctx, @PathVariable String threadId,
                                                     @PathVariable long turnId, @RequestParam String answer) {
        String userId = ctx.userId();
        if (memoryService.getFeedback(userId, threadId, turnId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        boolean updated = curatedQaService.updateAnswerForTurn(userId, threadId, turnId, answer);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        auditLogger.log("curated.edit", threadId, Map.of("turnId", turnId, "by", "owner"));
        return ResponseEntity.noContent().build();
    }

    /** Fetches the current curated answer text to populate the chat inline edit box. Same ownership
     *  scoping as {@link #updateCuratedAnswer}. */
    @GetMapping("/ui/threads/{threadId}/turns/{turnId}/curated")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getCuratedAnswer(ThreadContext ctx, @PathVariable String threadId,
                                                                  @PathVariable long turnId) {
        String userId = ctx.userId();
        if (memoryService.getFeedback(userId, threadId, turnId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return curatedQaService.findActiveByTurn(turnId)
                .map(row -> ResponseEntity.ok(Map.of("answer", row.answer())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Hides one image from a chat turn (owner-scoped to userId + threadId). */
    @PatchMapping("/ui/threads/{threadId}/turns/{turnId}/images/exclude")
    @ResponseBody
    public ResponseEntity<Void> excludeTurnImage(ThreadContext ctx, @PathVariable String threadId,
                                                  @PathVariable long turnId, @RequestParam String imageRef) {
        String userId = ctx.userId();
        if (imageRef == null || imageRef.isBlank()) {
            throw new IllegalArgumentException("imageRef is required");
        }
        if (memoryService.getFeedback(userId, threadId, turnId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        memoryService.excludeTurnImageRef(userId, threadId, turnId, imageRef);
        auditLogger.log("turn.image.exclude", threadId, Map.of("turnId", turnId, "imageRef", imageRef));
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

    /**
     * In-flight vs. capacity for the "main" LOCAL LLM tier ({@code role=LOCAL, priority=1}),
     * polled by the header's {@code LLM: inUse/capacity} indicator every ~3s. {@code available=false}
     * (no {@code inUse}/{@code capacity}) when no such provider is currently available — the
     * indicator hides itself in that case rather than showing a meaningless {@code 0/0}.
     *
     * <p>{@code inUse} folds in {@link EmbeddingConcurrencyTracker#get()} and {@link
     * BackgroundLlmConcurrencyTracker#get()} — embedding calls (indexing or query-time) and
     * indexing/background chat LLM calls (keyword+context extraction, MD correction, TXT
     * structuring, summarization, title generation, indexing-time Vision) never acquire {@link
     * LlmRouter}'s chat concurrency permits, so without these the indicator would sit at 0 during
     * indexing no matter how busy the LOCAL model actually is. The combined total is clamped to
     * {@code capacity} so the display never shows something like {@code 5/3} — neither embedding
     * concurrency (`EMBED_MAX_CONCURRENT_BATCHES`, possibly a different endpoint entirely) nor
     * background LLM concurrency (`app.indexing.max-concurrent-llm-calls`) shares the chat tier's
     * budget, so a raw sum could otherwise exceed it.
     */
    @GetMapping("/api/v1/llm/concurrency")
    @ResponseBody
    public Map<String, Object> getLlmConcurrency() {
        return llmRouter.localTier1Concurrency()
                .<Map<String, Object>>map(s -> {
                    int inUse = Math.min(s.capacity(),
                            s.inUse() + embeddingConcurrencyTracker.get() + backgroundConcurrencyTracker.get());
                    return Map.of(
                            "available", true,
                            "inUse", inUse,
                            "capacity", s.capacity());
                })
                .orElseGet(() -> Map.of("available", false));
    }

    /** Provider-level daily / weekly / monthly summary + Circuit Breaker state, plus one embedding row (§6.6) and orphan rows (§6.8). */
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
        Stream<UsageReport> backgroundUsage = backgroundCategories().stream()
                .map(prefix -> new UsageReport(
                        BackgroundUsage.label(prefix),
                        "BACKGROUND",
                        backgroundModelLabel(prefix),
                        usageRepo.getDailyByPrefix(prefix),
                        usageRepo.getWeeklyByPrefix(prefix),
                        usageRepo.getMonthlyByPrefix(prefix),
                        null
                ));
        Stream<UsageReport> orphanUsage = orphanProviderNames().stream().sorted()
                .map(name -> new UsageReport(
                        name,
                        "ORPHAN",
                        null,
                        usageRepo.getDaily(name),
                        usageRepo.getWeekly(name),
                        usageRepo.getMonthly(name),
                        null
                ));
        return Stream.of(chatUsage, Stream.of(embedUsage), backgroundUsage, orphanUsage)
                .flatMap(s -> s).toList();
    }

    /** Daily token history per provider for Chart.js stacked bar chart, plus embedding (§6.6) and orphan (§6.8) rows. */
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
        for (String prefix : backgroundCategories()) {
            history.put(BackgroundUsage.label(prefix), usageRepo.getDailyHistoryByPrefix(prefix, safeDays));
        }
        for (String name : orphanProviderNames()) {
            history.put(name, usageRepo.getDailyHistory(name, safeDays));
        }
        return history;
    }

    /**
     * Deletes all llm_usage rows for a genuinely orphaned provider name (§6.8) — one that
     * isn't in the current chat provider config and isn't the currently active embedding
     * model. Rejects (400) any name still live in config so an operator can never wipe an
     * active provider's history through this endpoint. Scoped under /admin/** so it inherits
     * ROLE_ADMIN gating (SecurityConfig) and no-auth mode's automatic admin identity for
     * /admin/** paths (NoAuthAutoLoginFilter) with no new plumbing.
     */
    @DeleteMapping("/admin/llm-usage/{provider:.+}")
    public String deleteOrphanUsage(@PathVariable String provider, Model model) {
        if (!orphanProviderNames().contains(provider)) {
            throw new IllegalArgumentException("Not an orphan provider (still in config): " + provider);
        }
        int deleted = usageRepo.deleteByProvider(provider);
        auditLogger.log("llm-usage.delete-orphan", provider, Map.of("deletedRows", deleted));
        model.addAttribute("reports", buildProviderReports());
        return "fragments/llm-usage-cards :: cards";
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
     * Chat provider cards/rows, one always-shown embedding card (§6.6), any background-usage
     * cards (conversation summarization, indexing keyword extraction/format correction,
     * thread title generation; only shown once they have history), plus any orphan cards
     * (§6.8). Embedding/background have no ProviderRole (role=null → no role badge) and are
     * never circuit-broken (blockedUntil=null) — the type badge ("EMBEDDING"/"BACKGROUND") is
     * what visually separates them from chat providers. Orphan cards similarly use
     * type="ORPHAN", are never "configured" (dims the card, reusing the existing opacity
     * styling), and set deletable=true so the fragment renders a delete button only for them.
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
                        isConfigured(cfg),
                        false
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
                true,
                false
        );
        Stream<LlmProviderReport> backgroundReports = backgroundCategories().stream()
                .map(prefix -> new LlmProviderReport(
                        BackgroundUsage.label(prefix),
                        "BACKGROUND",
                        null,
                        backgroundModelLabel(prefix),
                        usageRepo.getDailyByPrefix(prefix),
                        usageRepo.getWeeklyByPrefix(prefix),
                        usageRepo.getMonthlyByPrefix(prefix),
                        null,
                        true,
                        false
                ));
        Stream<LlmProviderReport> orphanReports = orphanProviderNames().stream().sorted()
                .map(name -> new LlmProviderReport(
                        name,
                        "ORPHAN",
                        null,
                        null,
                        usageRepo.getDaily(name),
                        usageRepo.getWeekly(name),
                        usageRepo.getMonthly(name),
                        null,
                        false,
                        true
                ));
        return Stream.of(chatReports, Stream.of(embedReport), backgroundReports, orphanReports)
                .flatMap(s -> s).toList();
    }

    /**
     * Chat providers to surface in the cards/table/chart (§6.7). Configured (§isEnabled) providers
     * always show, even with zero usage. Unconfigured providers (missing api-key for a cloud
     * provider, or a blank base-url for any provider including LOCAL — see {@link
     * AppProperties.ProviderConfig#isEnabled()}) show only if they have historical usage — hides
     * never-used placeholder providers while preserving history for ones that were used and later
     * had their key/url removed. Applied once here so all three usage surfaces (cards, REST usage,
     * REST history) agree on the same visible set.
     */
    private List<AppProperties.ProviderConfig> visibleChatProviders() {
        Set<String> used = usageRepo.usedProviders();
        return props.llmSafe().providers().stream()
                .filter(cfg -> isConfigured(cfg) || used.contains(cfg.name()))
                .toList();
    }

    /** Delegates to {@link AppProperties.ProviderConfig#isEnabled()} — the same LOCAL-exempt
     *  api-key + always-required base-url check {@code LlmConfig.llmRouter()} uses to decide
     *  which providers actually get registered, so a provider never shows "정상" here unless
     *  it would truly answer a chat call. */
    private static boolean isConfigured(AppProperties.ProviderConfig cfg) {
        return cfg.isEnabled();
    }

    /** {@code "embed:" + model} — matches the key TrackingEmbeddingModel records under. */
    private String embeddingProviderName() {
        String model = props.embeddingSafe().model();
        return TrackingEmbeddingModel.PROVIDER_PREFIX + (model != null ? model : "unknown");
    }

    /**
     * {@link BackgroundUsage} categories (prefix, e.g. {@code "title:"}) with at least one recorded
     * call — conversation summarization, indexing keyword extraction/format correction, thread
     * title generation. One card/row per category, merged across whichever underlying LOCAL
     * provider(s) actually served each call (local/local-fast/local-2, §6.21) — an operator cares
     * about "how much did title generation cost", not which local slot happened to answer each
     * call, and splitting by provider name here previously produced confusing duplicate cards
     * (e.g. both {@code title:local} and {@code title:local-fast}) for what is really one workload.
     * Only categories with actual history are shown (unlike embedding's always-shown single row,
     * there's no single "current" background provider to default to, and several prefixes exist),
     * sorted for stable rendering.
     */
    private Set<String> backgroundCategories() {
        Set<String> used = usageRepo.usedProviders();
        Set<String> categories = new java.util.TreeSet<>();
        for (String prefix : BackgroundUsage.prefixes()) {
            if (used.stream().anyMatch(name -> name.startsWith(prefix))) {
                categories.add(prefix);
            }
        }
        return categories;
    }

    /**
     * Comma-joined underlying provider names (the part after the prefix) that contributed to a
     * background category, e.g. {@code "title:"} → {@code "local, local-fast"}. Shown in the
     * card/row's model-name slot in place of a real model name, since a merged category has no
     * single model — this is what actually answered the calls (LOCAL only; background calls never
     * route to a cloud provider).
     */
    private String backgroundModelLabel(String prefix) {
        return usageRepo.usedProviderNamesWithPrefix(prefix).stream()
                .map(name -> name.substring(prefix.length()))
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Provider names with historical usage that don't correspond to any live config today
     * (§6.8) — a chat provider removed entirely from app.llm.providers, or a stale
     * embed:&lt;old-model&gt; row left behind after EMBED_MODEL was changed. Background usage
     * is excluded — it's expected, ongoing usage, not stale config. Unlike §6.7's plain
     * inactive filter (which only hides/shows names still present in config), these are
     * actively surfaced so an operator can review and delete them.
     */
    private Set<String> orphanProviderNames() {
        Set<String> orphans = new HashSet<>(usageRepo.usedProviders());
        props.llmSafe().providers().forEach(cfg -> orphans.remove(cfg.name()));
        orphans.remove(embeddingProviderName());
        orphans.removeIf(BackgroundUsage::isBackground);
        return orphans;
    }
}
