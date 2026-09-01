package com.example.ragagent.exception;

/**
 * §6.15 — thrown when accepting an upload would push the deployment's document storage past
 * {@code app.upload.max-total-size}.
 *
 * <p>413 rather than 507: the cap is an operator policy about this deployment, not a claim that
 * the filesystem is out of room, and the client's remedy is the same one 413 already means here —
 * send less. It shares that status with {@code MaxUploadSizeExceededException} (RAG-UP-003) but
 * carries its own code, because the fix differs: that one is "this file is too big", this one is
 * "there is no room left for any file until something is deleted".
 *
 * <p>Deliberately not 429: retrying later changes nothing on its own. Nothing frees space except
 * a deletion, so there is no wait to advertise and {@link #retryAfterSeconds()} stays -1.
 */
public final class StorageQuotaExceededException extends RagException {

    private final long usedBytes;
    private final long limitBytes;
    private final long incomingBytes;

    public StorageQuotaExceededException(String message, long usedBytes, long limitBytes, long incomingBytes) {
        super("RAG-UP-002", message);
        this.usedBytes = usedBytes;
        this.limitBytes = limitBytes;
        this.incomingBytes = incomingBytes;
    }

    @Override public int httpStatus() { return 413; }

    public long usedBytes() { return usedBytes; }

    public long limitBytes() { return limitBytes; }

    public long incomingBytes() { return incomingBytes; }
}
