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
        Consumer<IndexingProgressEvent> onProgress,
        List<String> tags                // Step 5.9: 검색 스코프 태그 (청크 metadata에 저장)
) {
    public IndexRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return single(p, filename, version, userId, List.of(), onProgress);
    }

    public static IndexRequest single(Path p, String filename, String version, String userId,
                                      List<String> tags, Consumer<IndexingProgressEvent> onProgress) {
        return new IndexRequest(p, filename, version, userId, null, null, true, onProgress, tags);
    }

    public static IndexRequest parallel(Path p, String version, String userId, Semaphore gate, String stale) {
        return new IndexRequest(p, p.getFileName().toString(), version, userId,
                gate, stale, false, event -> {}, List.of());
    }
}
