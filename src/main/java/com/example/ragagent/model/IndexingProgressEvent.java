package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE payload for real-time indexing progress.
 *
 * stage values:
 *   upload flow  — loading | chunking | enriching | storing | done | error
 *   sync flow    — sync_start | sync_file_done | sync_done | error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexingProgressEvent(
        String stage,
        int done,
        int total,
        String filename,
        String message,
        DocumentInfo docResult,
        SyncSummary syncSummary) {

    public record SyncSummary(int indexed, int updated, int deleted) {}

    public static IndexingProgressEvent of(String stage, int done, int total, String filename, String message) {
        return new IndexingProgressEvent(stage, done, total, filename, message, null, null);
    }

    public static IndexingProgressEvent done(DocumentInfo doc) {
        return new IndexingProgressEvent("done", doc.chunks(), doc.chunks(),
                doc.filename(), "완료", doc, null);
    }

    public static IndexingProgressEvent syncDone(SyncResult result) {
        int processed = result.indexed().size() + result.updated().size();
        return new IndexingProgressEvent("sync_done", processed, processed,
                "sync", "동기화 완료", null,
                new SyncSummary(result.indexed().size(), result.updated().size(), result.deleted().size()));
    }

    public static IndexingProgressEvent error(String filename, String message) {
        return new IndexingProgressEvent("error", 0, 0, filename, message, null, null);
    }
}
