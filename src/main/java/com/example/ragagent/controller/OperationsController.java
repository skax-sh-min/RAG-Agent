package com.example.ragagent.controller;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.CircuitBreaker;
import com.example.ragagent.model.LlmProviderReport;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.MemoryService;
import com.example.ragagent.service.ThreadMetaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    public OperationsController(ThreadMetaService threadMetaService,
                                MemoryService memoryService,
                                LlmUsageRepository usageRepo,
                                AppProperties props,
                                CircuitBreaker circuitBreaker) {
        this.threadMetaService = threadMetaService;
        this.memoryService = memoryService;
        this.usageRepo = usageRepo;
        this.props = props;
        this.circuitBreaker = circuitBreaker;
    }

    // ── Page ──────────────────────────────────────────────────────────

    // ── Thread management ─────────────────────────────────────────────

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

    /** Provider-level daily / weekly / monthly summary + Circuit Breaker state. */
    @GetMapping("/api/v1/llm/usage")
    @ResponseBody
    public List<UsageReport> getLlmUsage() {
        Map<String, Instant> blocked = circuitBreaker.getBlockedProviders();
        return props.llmSafe().providers().stream()
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
                })
                .toList();
    }

    /** Daily token history per provider for Chart.js stacked bar chart. */
    @GetMapping("/api/v1/llm/usage/history")
    @ResponseBody
    public Map<String, List<LlmUsageRepository.DailyRow>> getLlmUsageHistory(
            @RequestParam(defaultValue = "30") int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        return props.llmSafe().providers().stream().collect(
                Collectors.toMap(
                        AppProperties.ProviderConfig::name,
                        cfg -> usageRepo.getDailyHistory(cfg.name(), safeDays),
                        (a, b) -> a
                )
        );
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
