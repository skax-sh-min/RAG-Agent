package com.example.ragagent.service;

import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.ingestion.MarkdownNoiseNormalizer;
import com.example.ragagent.ingestion.VectorStoreFacade;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.CuratedQaRepository.CuratedQa;
import com.example.ragagent.repository.MemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §10.10 — promotes 👍'd chat turns into a separately embedded, shared knowledge axis (reserved
 * vector-store version namespace {@value #CURATED_VERSION} — auto-isolated per backend: a
 * distinct Chroma collection / a sqlite-vec partition key, version-agnostic so it survives
 * document re-indexing). See documents/PLAN.md §10.10 for the full design.
 *
 * <p>The DB snapshot ({@code curated_qa}) is written synchronously (cheap local write, same
 * request as the feedback flip); the embedding call is background + debounced so an accidental
 * like→unlike never pays for an LLM/embedding round-trip on the interactive path (§6.12).
 */
@Service
public class CuratedQaService {

    private static final Logger log = LoggerFactory.getLogger(CuratedQaService.class);

    /** Reserved vectorstore version namespace for curated Q&A — never a real document version. */
    public static final String CURATED_VERSION = "curated";

    private static final long DEFAULT_EMBED_DEBOUNCE_MILLIS = 3_000L;

    private final CuratedQaRepository repository;
    private final MemoryService memoryService;
    private final ThreadMetaService threadMetaService;
    private final VectorStoreFacade vectorStore;
    private final long embedDebounceMillis;

    public CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                            ThreadMetaService threadMetaService, VectorStoreFacade vectorStore) {
        this(repository, memoryService, threadMetaService, vectorStore, DEFAULT_EMBED_DEBOUNCE_MILLIS);
    }

    /** Package-private — lets tests shrink the debounce instead of waiting out the real 3s. */
    CuratedQaService(CuratedQaRepository repository, MemoryService memoryService,
                     ThreadMetaService threadMetaService, VectorStoreFacade vectorStore,
                     long embedDebounceMillis) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.threadMetaService = threadMetaService;
        this.vectorStore = vectorStore;
        this.embedDebounceMillis = embedDebounceMillis;
    }

    /**
     * Call after a turn's feedback transitions INTO {@code LIKE}. Upserts the curated_qa snapshot
     * synchronously, then embeds on a background virtual thread after a short debounce:
     * <ol>
     *   <li>sleep {@link #embedDebounceMillis} — lets a fat-fingered like→unlike cancel before any
     *       embedding API call is made (cost-saving; correctness does not depend on this step)</li>
     *   <li>re-check feedback right before the embed call — skip entirely if no longer LIKE</li>
     *   <li>after the embed call succeeds, re-check once more and compensate (delete) if the turn
     *       was unliked while the call was in flight</li>
     * </ol>
     * Mirrors the DISLIKE-discard guard in {@link ConversationSummarizerService#precompute}.
     */
    public void onLike(String userId, String threadId, long turnId) {
        Optional<MemoryRepository.Turn> turnOpt = memoryService.getTurn(userId, threadId, turnId);
        if (turnOpt.isEmpty()) {
            log.warn("[CURATED] onLike: turn not found userId={} threadId={} turnId={}", userId, threadId, turnId);
            return;
        }
        MemoryRepository.Turn turn = turnOpt.get();
        String version = threadMetaService.findById(userId, threadId)
                .map(t -> t.version())
                .orElse(null);

        long curatedId = repository.upsertActive(turnId, userId, threadId,
                turn.question(), turn.answer(), version);

        Thread.ofVirtual().name("curated-embed-" + curatedId).start(() -> {
            try {
                Thread.sleep(embedDebounceMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!isStillLiked(userId, threadId, turnId)) {
                log.debug("[CURATED] embed skipped (unliked during debounce) turnId={}", turnId);
                return;
            }
            embed(curatedId, userId, threadId, turnId);
        });
    }

    /**
     * Call after a turn's feedback transitions OUT OF {@code LIKE} (to NONE or DISLIKE).
     * Deactivates the row synchronously (fast, local); vector removal runs on a background thread
     * since a Chroma delete is a network round-trip (§6.12 — never block the interactive path on
     * remote I/O). Safe no-op if nothing was ever embedded (delete-by-id on a missing id is a
     * no-op on both backends) or if the turn was never promoted at all.
     */
    public void onUnlike(String userId, String threadId, long turnId) {
        Optional<CuratedQa> existing = repository.findBySourceTurnId(turnId);
        if (existing.isEmpty() || !"active".equals(existing.get().status())) return;

        repository.deactivate(turnId);
        long curatedId = existing.get().id();
        Thread.ofVirtual().name("curated-deindex-" + curatedId).start(() ->
                deleteVector(curatedId));
    }

    private void embed(long curatedId, String userId, String threadId, long turnId) {
        Optional<CuratedQa> rowOpt = repository.findById(curatedId);
        if (rowOpt.isEmpty() || !"active".equals(rowOpt.get().status())) return;
        CuratedQa row = rowOpt.get();

        Document doc = buildDocument(row);
        try {
            vectorStore.add(DocRegistry.SHARED, CURATED_VERSION, List.of(doc));
        } catch (Exception e) {
            log.warn("[CURATED] embed failed curatedId={} turnId={}: {}", curatedId, turnId, e.getMessage());
            return;
        }

        // Compensating re-check: an unlike that raced during the (network) embed call itself gets
        // undone immediately instead of left as a dangling active-in-vectorstore/inactive-in-DB
        // entry — narrows the race window to just this call's own duration.
        if (!isStillLiked(userId, threadId, turnId)) {
            log.debug("[CURATED] embed committed then reverted (unliked during embed) turnId={}", turnId);
            deleteVector(curatedId);
            return;
        }
        log.info("[CURATED] embedded curatedId={} turnId={}", curatedId, turnId);
    }

    /**
     * Builds the curated Document: {@code getText()} = full answer (participates/§10.1 stored
     * text, "## 참고" section intact — useful for a human reading the curated entry). The search
     * vector is a separately precomputed override under {@link MetaKey#SEARCH_TEXT} — question +
     * normalize(answer with the "## 참고" citation section stripped, see {@link CuratedTextUtils})
     * — which {@code SearchTextBuilder.build()} already prefers over recomputing from
     * {@code getText()} (§10.8.5), so no changes are needed to either {@code VectorStoreProvider}.
     */
    private Document buildDocument(CuratedQa row) {
        String searchText = row.question() + "\n\n"
                + MarkdownNoiseNormalizer.normalize(CuratedTextUtils.stripReferenceSection(row.answer()));

        Map<String, Object> meta = new HashMap<>();
        meta.put(MetaKey.DOC_ID, "curated:" + row.id());
        meta.put(MetaKey.FILENAME, "curated_qa");
        meta.put(MetaKey.VERSION, CURATED_VERSION);
        meta.put(MetaKey.DOC_TYPE, "curated_qa");
        meta.put(MetaKey.SOURCE_TYPE, "curated_qa");
        meta.put(MetaKey.CHUNK_INDEX, 0);
        meta.put(MetaKey.PAGE_OR_SLIDE, 1);
        meta.put(MetaKey.SEARCH_TEXT, searchText); // transient override — stripped before persistence

        return new Document(springDocId(row.id()), row.answer(), meta);
    }

    private void deleteVector(long curatedId) {
        try {
            vectorStore.deleteByDocIds(DocRegistry.SHARED, CURATED_VERSION, List.of(springDocId(curatedId)));
        } catch (Exception e) {
            log.warn("[CURATED] vector delete failed curatedId={}: {}", curatedId, e.getMessage());
        }
    }

    private boolean isStillLiked(String userId, String threadId, long turnId) {
        return memoryService.getFeedback(userId, threadId, turnId)
                .map(f -> "LIKE".equals(f.feedback()))
                .orElse(false);
    }

    private static String springDocId(long curatedId) {
        return "curated-" + curatedId;
    }
}
