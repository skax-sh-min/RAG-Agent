package com.example.ragagent.ingestion;

import com.example.ragagent.model.IndexingProgressEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Unified parameters for a single document indexing operation.
 * Replaces the separate parameter lists of indexDocument() and indexDocumentParallel().
 */
public record IndexRequest(
        Path path,
        String filename,
        String version,
        String ownerId,
        Semaphore parallelGate,          // null → doIndex creates its own from props
        String staleDocId,               // null → no stale-doc deletion after indexing
        boolean saveRegistryAfter,
        boolean addImageDescriptions,    // true → Markdown correction adds local image descriptions
        boolean addHeadingNumbers,       // true → Markdown second pass adds heading numbers + code-block polish
        boolean skipLlmCorrection,       // true → .md upload only: skip the LLM rewrite in MD correction (deterministic passes still run)
        Consumer<IndexingProgressEvent> onProgress,
        List<String> tags,               // 검색 스코프 태그 (청크 metadata에 저장)
        String precomputedSha256         // §10.8.4 — null이면 index()가 직접 재계산
) {
    public IndexRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return single(p, filename, version, userId, List.of(), false, onProgress);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      List<String> tags, Consumer<IndexingProgressEvent> onProgress) {
        return single(p, filename, version, userId, tags, false, onProgress);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      List<String> tags, boolean addImageDescriptions,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return new IndexRequest(p, filename, version, userId,
            null, null, true, addImageDescriptions, false, false, onProgress, tags, null);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      List<String> tags, boolean addImageDescriptions,
                                      boolean addHeadingNumbers,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return single(p, filename, version, userId, tags, addImageDescriptions, addHeadingNumbers,
                false, onProgress);
    }

    /**
     * {@code skipLlmCorrection=true} — only honoured for a {@code .md} upload (see the {@code .md}
     * branch in {@code DocumentIndexer.index()}); converter output (DOCX/PPTX/PDF/TXT) always goes
     * through the LLM pass, since that pass is what makes machine-converted markdown usable.
     */
    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      List<String> tags, boolean addImageDescriptions,
                                      boolean addHeadingNumbers, boolean skipLlmCorrection,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return new IndexRequest(p, filename, version, userId,
            null, null, true, addImageDescriptions, addHeadingNumbers, skipLlmCorrection,
            onProgress, tags, null);
    }

    public static IndexRequest parallel(Path p, String version, String userId, Semaphore gate, String stale) {
        return parallel(p, version, userId, gate, stale, null);
    }

    /** §10.8.4 — syncDirectory() already hashed the file in its detection pass; skip index()'s re-hash. */
    public static IndexRequest parallel(Path p, String version, String userId, Semaphore gate, String stale,
                                        String precomputedSha256) {
        return new IndexRequest(p, p.getFileName().toString(), version, userId,
                gate, stale, false, false, false, false, event -> {}, List.of(), precomputedSha256);
    }
}
