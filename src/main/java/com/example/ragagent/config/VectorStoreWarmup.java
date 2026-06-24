package com.example.ragagent.config;

import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.service.VectorStoreRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * S-5: Pre-creates the shared Chroma collection at startup so the first user
 * query does not pay the schema-initialization round-trip.
 *
 * <p>Document storage converges to {@code (DocRegistry.SHARED, "latest")}, so warming
 * that single store covers the search hot path. Failures are swallowed — a cold or
 * unreachable ChromaDB must never block application startup.
 */
@Component
@ConditionalOnProperty(name = "app.vectorstore.type", havingValue = "chroma", matchIfMissing = true)
public class VectorStoreWarmup {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreWarmup.class);

    private static final String DEFAULT_VERSION = "latest";

    private final VectorStoreRegistry registry;

    public VectorStoreWarmup(VectorStoreRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warmUp() {
        long start = System.nanoTime();
        try {
            registry.getStore(DocRegistry.SHARED, DEFAULT_VERSION);
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("[CHROMA] Warmed up shared collection in {} ms", ms);
        } catch (Exception e) {
            log.warn("[CHROMA] Warm-up skipped (ChromaDB unavailable?): {}", e.getMessage());
        }
    }
}
