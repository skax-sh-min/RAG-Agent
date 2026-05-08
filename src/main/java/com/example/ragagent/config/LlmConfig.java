package com.example.ragagent.config;

import com.example.ragagent.llm.*;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Comparator;
import java.util.List;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmRouter llmRouter(AppProperties props, LlmUsageRepository usageRepo,
                                CircuitBreaker circuitBreaker) {
        AppProperties.LlmConfig llmCfg = props.llmSafe();

        llmCfg.providers().stream()
                .filter(cfg -> cfg.apiKey() == null || cfg.apiKey().isBlank())
                .forEach(cfg -> log.warn(
                        "Provider [{}] disabled — api-key is empty (set the corresponding env var)", cfg.name()));

        List<LlmProvider> providers = llmCfg.providers().stream()
                .filter(cfg -> cfg.apiKey() != null && !cfg.apiKey().isBlank())
                .map(cfg -> {
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(cfg.baseUrl() != null ? cfg.baseUrl() : "https://api.openai.com")
                            .apiKey(cfg.apiKey())
                            .build();
                    ChatModel model = OpenAiChatModel.builder()
                            .openAiApi(api)
                            .defaultOptions(OpenAiChatOptions.builder()
                                    .model(cfg.model())
                                    .temperature(0.0)
                                    .maxTokens(6000)
                                    .build())
                            .build();
                    String roleStr = cfg.role() != null ? cfg.role().toUpperCase() : "NORMAL";
                    String typeStr = cfg.type() != null ? cfg.type().toUpperCase() : "BOTH";
                    return new LlmProvider(
                            cfg.name(),
                            TaskType.valueOf(typeStr),
                            ProviderRole.valueOf(roleStr),
                            cfg.priority(),
                            cfg.apiKey(),
                            model);
                })
                .sorted(Comparator.comparingInt(LlmProvider::priority))
                .toList();

        RoutingMode defaultMode;
        try {
            defaultMode = llmCfg.defaultRoutingMode() != null
                    ? RoutingMode.valueOf(llmCfg.defaultRoutingMode().toUpperCase())
                    : RoutingMode.COST_FIRST;
        } catch (IllegalArgumentException e) {
            defaultMode = RoutingMode.COST_FIRST;
        }

        double threshold = llmCfg.progressiveThreshold() > 0
                ? llmCfg.progressiveThreshold() : 0.6;

        log.info("LLM providers registered: {}", providers.stream()
                .map(p -> "%s(%s/%s/p%d)".formatted(p.name(), p.role(), p.type(), p.priority()))
                .toList());

        return new LlmRouter(providers, usageRepo, circuitBreaker, defaultMode, threshold);
    }

    @Bean
    @Primary
    public ChatModel primaryChatModel(LlmRouter router) {
        return router.route(TaskType.TEXT, RoutingMode.COST_FIRST);
    }
}
