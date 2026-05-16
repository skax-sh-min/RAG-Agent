package com.example.ragagent.ingestion;

import com.example.ragagent.model.IndexingProgressEvent;

import java.nio.file.Path;
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
        Consumer<IndexingProgressEvent> onProgress
) {
    public static IndexRequest single(Path p, String filename, String version,
                                      Consumer<IndexingProgressEvent> onProgress) {
        return new IndexRequest(p, filename, version, "anonymous", null, null, true, onProgress);
    }

    public static IndexRequest parallel(Path p, String version, Semaphore gate, String stale) {
        return new IndexRequest(p, p.getFileName().toString(), version, "anonymous",
                gate, stale, false, event -> {});
    }
}
