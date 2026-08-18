package com.example.ragagent.service;

import com.example.ragagent.model.SourceRef;
import com.example.ragagent.repository.MemoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of the 검색 진단 수치 (3단계) — turns the JSON blob stored per turn back into rows for
 * the {@code /admin} tuning panel.
 *
 * <p>The panel exists because the per-turn numbers in chat are only visible <em>in the moment</em>:
 * to decide whether {@code SEARCH_RRF_KEYWORD_WEIGHT} or {@code SEARCH_SIMILARITY_THRESHOLD} needs
 * changing, an operator has to look across many turns, and the interesting pattern is usually a
 * mismatch (high similarity that never reaches the answer, or the keyword axis carrying everything).
 * So each row also carries the two summary numbers that make that scannable without expanding it.
 */
@Service
public class RetrievalMetricsService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalMetricsService.class);

    private static final TypeReference<List<SourceRef>> SOURCE_LIST = new TypeReference<>() {};

    /** Questions are shown for context only — the panel is a tuning view, not a transcript reader. */
    private static final int MAX_QUESTION_PREVIEW = 120;

    private final MemoryService memoryService;

    /**
     * Reads stored blobs <b>tolerantly</b>, with unknown properties ignored.
     *
     * <p>These rows outlive the code that wrote them: a blob persisted before {@code answerShare}
     * existed, or after a future field is removed, must still render. With the default strict
     * reader a single added field would silently blank out every historical row — exactly the
     * accumulated data this panel exists to look at. Derived from the injected mapper rather than
     * a fresh one so any app-wide serialization config still applies.
     */
    private final com.fasterxml.jackson.databind.ObjectReader reader;

    public RetrievalMetricsService(MemoryService memoryService, ObjectMapper objectMapper) {
        this.memoryService = memoryService;
        this.reader = objectMapper
                .reader()
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .forType(SOURCE_LIST);
    }

    /**
     * One turn in the panel.
     *
     * @param maxSimilarity best similarity among the turn's sources — "did retrieval find anything
     *                      close at all", null when no source carried a vector score
     * @param usedSourceCount how many sources actually received an answer share. A turn where this
     *                      is far below {@code sources.size()} retrieved a lot and used little,
     *                      which is the signal worth chasing (topK too high, or a prompt problem).
     */
    public record TurnMetrics(long turnId, String askedAt, String question, String responseMode,
                              String provider, List<SourceRef> sources,
                              Double maxSimilarity, int usedSourceCount) {}

    public List<TurnMetrics> recent(int offset, int limit) {
        List<TurnMetrics> out = new ArrayList<>();
        for (MemoryRepository.MetricsRow row : memoryService.findRecentRetrievalMetrics(offset, limit)) {
            List<SourceRef> sources = parse(row);
            if (sources.isEmpty()) continue;   // unreadable blob — skip the row rather than break the panel
            out.add(new TurnMetrics(
                    row.turnId(),
                    row.askedAt(),
                    truncate(row.question()),
                    row.responseMode(),
                    row.provider(),
                    sources,
                    sources.stream().map(SourceRef::similarity).filter(java.util.Objects::nonNull)
                           .max(Double::compare).orElse(null),
                    (int) sources.stream().filter(s -> s.answerShare() != null).count()));
        }
        return out;
    }

    public int count() {
        return memoryService.countRetrievalMetrics();
    }

    /**
     * Re-attaches stored diagnostics to a reopened thread's source lists (`/chat/{threadId}`).
     *
     * <p>The passed-in lists stay authoritative — they are rebuilt from the chunks as they exist
     * <em>now</em> (labels, previews, deleted-chunk placeholders), which the stored blob cannot
     * know about. Only the four numbers are merged in, matched by {@code chunkId}; a source with
     * no stored entry is returned untouched rather than dropped.
     *
     * <p>A DB-reuse turn has no entry at all: it answered from a stored answer without running
     * retrieval, so there is nothing to report even though its previews are borrowed from the
     * original turn.
     */
    public Map<Long, List<SourceRef>> enrich(Map<Long, List<SourceRef>> sourcesByTurn) {
        if (sourcesByTurn == null || sourcesByTurn.isEmpty()) return sourcesByTurn;
        Map<Long, String> blobs = memoryService.findRetrievalMetricsByTurnIds(
                List.copyOf(sourcesByTurn.keySet()));
        if (blobs.isEmpty()) return sourcesByTurn;

        Map<Long, List<SourceRef>> out = new java.util.LinkedHashMap<>(sourcesByTurn.size());
        for (var entry : sourcesByTurn.entrySet()) {
            String blob = blobs.get(entry.getKey());
            List<SourceRef> stored = blob == null
                    ? List.of()
                    : parse(new MemoryRepository.MetricsRow(entry.getKey(), null, null, null, null, blob));
            if (stored.isEmpty()) {
                out.put(entry.getKey(), entry.getValue());
                continue;
            }
            Map<String, SourceRef> byChunkId = new java.util.HashMap<>();
            for (SourceRef s : stored) {
                if (s.chunkId() != null) byChunkId.put(s.chunkId(), s);
            }
            out.put(entry.getKey(), entry.getValue().stream()
                    .map(live -> {
                        SourceRef m = live.chunkId() == null ? null : byChunkId.get(live.chunkId());
                        return m == null ? live : new SourceRef(
                                live.label(), live.preview(), live.chunkId(), live.docId(),
                                live.pageOrSlide(),
                                m.similarity(), m.retrievalShare(), m.axisRanks(), m.answerShare());
                    })
                    .toList());
        }
        return out;
    }

    private List<SourceRef> parse(MemoryRepository.MetricsRow row) {
        try {
            List<SourceRef> parsed = reader.readValue(row.metricsJson());
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            // A blob written by an older/newer field set must not take down the whole panel.
            log.warn("[METRICS] 진단 수치 파싱 실패 turnId={} — 이 행만 건너뜀: {}", row.turnId(), e.toString());
            return List.of();
        }
    }

    private static String truncate(String question) {
        if (question == null) return "";
        String oneLine = question.replaceAll("\\s+", " ").strip();
        return oneLine.length() > MAX_QUESTION_PREVIEW
                ? oneLine.substring(0, MAX_QUESTION_PREVIEW) + "…"
                : oneLine;
    }
}
