package com.example.ragagent.config;

import com.example.ragagent.llm.CachingEmbeddingModel;
import com.example.ragagent.llm.EmbeddingConcurrencyTracker;
import com.example.ragagent.llm.LoadBalancingEmbeddingModel;
import com.example.ragagent.llm.LoggingEmbeddingModel;
import com.example.ragagent.llm.TrackingEmbeddingModel;
import com.example.ragagent.repository.LlmUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EmbeddingBeanConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBeanConfig.class);

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(AppProperties props, LlmUsageRepository usageRepo,
                                         EmbeddingConcurrencyTracker concurrencyTracker) {
        AppProperties.EmbeddingConfig cfg = props.embeddingSafe();
        if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            throw new IllegalStateException(
                    "app.embedding.base-url (EMBED_BASE_URL) is required but not configured");
        }
        String model = cfg.model() != null ? cfg.model() : "text-embedding-ada-002";

        // §6.21 E1 — primary base-url + any additional endpoints (must serve the SAME model/dimension,
        // e.g. N GPU replicas). One raw OpenAiEmbeddingModel per URL; when >1, balance least-in-flight.
        List<String> urls = new ArrayList<>();
        urls.add(cfg.baseUrl());
        if (cfg.additionalBaseUrls() != null) {
            for (String u : cfg.additionalBaseUrls()) {
                if (u != null && !u.isBlank()) urls.add(u.trim());
            }
        }

        EmbeddingModel base;
        if (urls.size() == 1) {
            base = buildRawModel(urls.get(0), cfg, model);
            warnIfSingleEndpointOnReplicatedServer(urls.get(0), props.llmSafe().providers());
        } else {
            List<EmbeddingModel> delegates = urls.stream()
                    .map(u -> buildRawModel(u, cfg, model))
                    .toList();
            // 부하 분산 + 닿지 못한 엔드포인트를 건너뛰는 장애 전환(LoadBalancingEmbeddingModel 클래스 주석).
            base = new LoadBalancingEmbeddingModel(delegates, urls);
            log.info("Embedding load balancing + failover (§6.21 E1) across {} endpoints: {}", urls.size(), urls);
        }

        EmbeddingModel tracked = new TrackingEmbeddingModel(base, usageRepo, model,
                cfg.usageFallbackEnabled(), concurrencyTracker);
        if (!props.searchQueryEmbedCacheEnabledSafe()) {
            return tracked;
        }
        // Cache sits outside tracking so a cache hit records no usage — no provider call happened.
        return new CachingEmbeddingModel(tracked, model,
                props.searchQueryEmbedCacheMaxSizeSafe(), props.searchQueryEmbedCacheTtlSecondsSafe());
    }

    /**
     * 채팅은 로컬 서버 두 대로 이중화했는데 임베딩은 그중 한 대에만 걸려 있는 구성을 기동 때 알린다.
     *
     * <p>임베딩은 라우터 밖이라 채팅의 장애 전환을 받지 못한다 — 그 한 대가 죽으면 채팅 모델은 다른 서버로 넘어가도 RAG
     * 턴은 질문 임베딩에서 실패한다. 기본값({@code EMBED_BASE_URL} 이 비면 {@code LOCAL_LLM_URL})이 정확히 그 모양이라
     * 이중화 배포가 모르는 채 빠지기 쉽다. {@code LOCAL_LLM_URL_2} 에서 엔드포인트를 <b>자동으로 만들지 않는</b> 이유는
     * 두 서버가 같은 임베딩 모델·차원을 띄웠다는 보장이 없어서다(섞이면 인덱스가 깨진다) — 그래서 고치지 않고 알리기만 한다.
     * 임베딩이 따로 떨어진 서버를 쓰면 이중화와 무관하므로 말하지 않는다.
     */
    private static void warnIfSingleEndpointOnReplicatedServer(String embedUrl,
                                                               List<AppProperties.ProviderConfig> providers) {
        if (providers == null || !LocalReplicas.anyReplicated(providers)) return;
        String root = LocalReplicas.normalizedRoot(embedUrl);
        providers.stream()
                .filter(p -> p.isEnabled() && !LocalReplicas.peersOf(p, providers).isEmpty())
                .filter(p -> LocalReplicas.normalizedRoot(p.baseUrl()).equals(root))
                .findFirst()
                .ifPresent(p -> log.warn("""
                        [EMBED CONFIG] chat runs on {} replicated local servers, but embeddings use only {} (provider [{}]) — \
                        if that server goes down, RAG turns fail at the query embedding even though chat switches over.
                          -> Set EMBED_ADDITIONAL_BASE_URLS to the other server(s), if they serve the same embedding model (EMBED_MODEL) at the same dimension.""",
                        1 + LocalReplicas.peersOf(p, providers).size(), embedUrl, p.name()));
    }

    /** Builds one raw {@link OpenAiEmbeddingModel} pointed at {@code rawUrl}, sharing {@code cfg}'s
     *  key/model/timeouts — wrapped in {@link LoggingEmbeddingModel} so DEBUG logs show a curl
     *  reproduction of every embedding call, mirroring {@code LlmConfig}'s {@link
     *  com.example.ragagent.llm.LoggingChatModel} for chat calls. */
    private EmbeddingModel buildRawModel(String rawUrl, AppProperties.EmbeddingConfig cfg, String model) {
        String effectiveApiKey = cfg.apiKey() != null && !cfg.apiKey().isBlank() ? cfg.apiKey() : "no-key";
        // maxRetries=1 (i.e. one retry): the SDK default is 2, and a long retry chain on connection
        // failures (e.g. LM Studio not running) leaves the UI on "벡터 DB 저장 중..." for minutes. The
        // SDK's own retry has a short exponential backoff, so a single retry keeps the wait bounded.
        com.openai.core.ClientOptions clientOptions = OpenAiClients.clientOptions(
                rawUrl, effectiveApiKey, cfg.connectTimeoutSeconds(), cfg.readTimeoutSeconds(), 1);
        OpenAiEmbeddingModel raw = OpenAiEmbeddingModel.builder()
                .openAiClient(OpenAiClients.syncClient(clientOptions))
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .build())
                .build();
        return new LoggingEmbeddingModel(raw, TrackingEmbeddingModel.PROVIDER_NAME,
                rawUrl, effectiveApiKey, model);
    }
}
