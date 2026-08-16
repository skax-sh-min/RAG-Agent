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
