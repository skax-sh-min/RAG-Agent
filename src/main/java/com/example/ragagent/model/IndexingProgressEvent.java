package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE payload for real-time indexing progress.
 *
 * stage values:
 *   upload flow  — loading | structuring (TXT) | correcting (DOCX/TXT) | chunking | enriching | storing | done | error | cancelled
 *   sync flow    — sync_start | sync_file_done | sync_file_error | sync_done | error | cancelled
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

    /** §6.16.1 — terminal: user cancelled the task via the cancel endpoint. */
    public static IndexingProgressEvent cancelled() {
        return new IndexingProgressEvent("cancelled", 0, 0, null, "사용자가 취소함", null, null);
    }

    /** Non-terminal: one file failed during sync, but sync continues for remaining files. */
    public static IndexingProgressEvent syncFileError(int done, int total, String filename, String message) {
        return new IndexingProgressEvent("sync_file_error", done, total, filename, message, null, null);
    }
}
