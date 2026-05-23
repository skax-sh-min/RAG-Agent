package com.example.ragagent.model;

import com.example.ragagent.repository.LlmUsageRepository;

import java.time.Instant;

/**
 * View model for a single LLM provider's usage stats + circuit-breaker state.
 * Passed to Thymeleaf templates for the /llm-usage page.
 */
public record LlmProviderReport(
        String name,
        String type,
        String role,           // LOCAL | NORMAL | PREMIUM
        String model,
        LlmUsageRepository.PeriodSummary daily,
        LlmUsageRepository.PeriodSummary weekly,
        LlmUsageRepository.PeriodSummary monthly,
        Instant blockedUntil,  // null when provider is operating normally
        boolean configured     // false when apiKey is blank (provider cannot make calls)
) {
    /** True when the circuit breaker is currently open for this provider. */
    public boolean isBlocked() {
        return blockedUntil != null;
    }

    /** ISO-8601 string for use in data-* HTML attributes; null when not blocked. */
    public String blockedUntilIso() {
        return blockedUntil != null ? blockedUntil.toString() : null;
    }
}
