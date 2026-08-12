package com.example.ragagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE payload for real-time indexing progress.
 *
 * stage values:
 *   upload flow  — loading | structuring (TXT) | describing_images (Vision, addImageDescriptions=true) | correcting (DOCX/TXT) | chunking | enriching | storing | done | error | cancelled
 *   sync flow    — sync_start | sync_file_done | sync_file_error | error | cancelled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexingProgressEvent(
        String stage,
        int done,
        int total,
        String filename,
        String message,
        DocumentInfo docResult) {

    public static IndexingProgressEvent of(String stage, int done, int total, String filename, String message) {
        return new IndexingProgressEvent(stage, done, total, filename, message, null);
    }

    public static IndexingProgressEvent done(DocumentInfo doc) {
        return new IndexingProgressEvent("done", doc.chunks(), doc.chunks(),
                doc.filename(), "완료", doc);
    }

    public static IndexingProgressEvent error(String filename, String message) {
        return new IndexingProgressEvent("error", 0, 0, filename, message, null);
    }

    /** §6.16.1 — terminal: user cancelled the task via the cancel endpoint. */
    public static IndexingProgressEvent cancelled() {
        return new IndexingProgressEvent("cancelled", 0, 0, null, "사용자가 취소함", null);
    }

    /** Non-terminal: one file failed during sync, but sync continues for remaining files. */
    public static IndexingProgressEvent syncFileError(int done, int total, String filename, String message) {
        return new IndexingProgressEvent("sync_file_error", done, total, filename, message, null);
    }

    /** Terminal: the taskId is unrecognized — never existed, or its outcome aged out of
     *  {@link com.example.ragagent.service.IndexingProgressService}'s retention window. Lets a
     *  reconnecting client stop waiting instead of sitting on a connection nothing will ever
     *  arrive on. */
    public static IndexingProgressEvent unknown() {
        return new IndexingProgressEvent("unknown", 0, 0, null,
                "작업 상태를 확인할 수 없습니다 (만료되었거나 존재하지 않는 작업)", null);
    }

    /** Non-terminal, status-check only: the task is still actively running. Never sent over the
     *  SSE progress channel itself — only returned by the one-shot status endpoint. */
    public static IndexingProgressEvent running() {
        return new IndexingProgressEvent("running", 0, 0, null, "진행 중", null);
    }
}
