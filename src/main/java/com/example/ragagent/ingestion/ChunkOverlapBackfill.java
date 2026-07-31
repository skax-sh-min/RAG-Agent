package com.example.ragagent.ingestion;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Stamps the configured {@code app.chunk-overlap} onto documents registered before the registry
 * started recording it (see {@link DocRegistry.DocRegistryEntry#chunkOverlap()}).
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than {@code @PostConstruct} so
 * {@code SettingsService} has already bound its override layer — otherwise the backfill would
 * record the raw property default and silently ignore an operator's {@code /settings} override.
 *
 * <p>Idempotent: the UPDATE only touches rows where the column is still NULL, so a restart after a
 * completed backfill is a no-op, and a document indexed by the current build (which always records
 * its own value) is never overwritten.
 */
@Component
public class ChunkOverlapBackfill {

    private static final Logger log = LoggerFactory.getLogger(ChunkOverlapBackfill.class);

    private final DocRegistry docRegistry;
    private final AppProperties props;

    public ChunkOverlapBackfill(DocRegistry docRegistry, AppProperties props) {
        this.docRegistry = docRegistry;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            int overlap = props.chunkOverlapSafe();
            int updated = docRegistry.backfillMissingChunkOverlap(overlap);
            if (updated > 0) {
                log.info("[REGISTRY] 기존 문서 {}건에 chunk_overlap={} 기록 (내보내기 재조립 정확도용)",
                        updated, overlap);
            }
        } catch (Exception e) {
            // Purely an export-quality improvement — never let it block application startup.
            log.warn("[REGISTRY] chunk_overlap 백필 실패 (무시하고 계속): {}", e.getMessage());
        }
    }
}
