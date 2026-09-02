package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.StorageQuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.stream.Stream;

/**
 * §6.15 — the deployment-wide storage cap ({@code app.upload.max-total-size}, 0 = unlimited).
 *
 * <p><b>Why a global cap and not a per-user one.</b> Document storage is shared
 * ({@code DocRegistry.SHARED}, one {@code data/documents/} directory) and the default deployment is
 * a single operator behind no-auth, so "this user has used N bytes" has nothing to attach to. The
 * quota axis that matches the storage model is the disk itself.
 *
 * <p><b>What counts.</b> Not just the uploaded file: indexing writes a converted Markdown copy and
 * extracts images, and for a slide deck or a scanned PDF those derived artifacts routinely dwarf
 * the source. A cap that measured only {@code documents/} would therefore miss most of what
 * uploading actually consumes, so {@link #usedBytes()} walks all three document-owned trees —
 * {@code documents/}, {@code converted/} and {@code images/}. Everything else under {@code data/}
 * (the SQLite files, audit logs, the Chroma volume) is not upload-driven and is left out.
 *
 * <p><b>The one exclusion is {@code documents/backup/}</b>, where deleting a document archives its
 * original ({@code RagService.archiveSourceFile}). Counting it would break the only remedy this
 * cap's own error message offers: a user at the limit deletes a document, the bytes move from
 * {@code documents/} to {@code documents/backup/}, usage barely drops, and the next upload is
 * refused again — with no screen anywhere that can reclaim the difference. Excluding it is only
 * safe because {@link DocumentBackupCleaner} bounds that directory (newest-per-document, an age
 * limit, and a size cap); the two decisions are halves of one.
 *
 * <p><b>It is a soft cap, on purpose.</b> The check runs at admission, when the only size known is
 * the incoming file's — the derived artifacts do not exist yet and cannot be predicted. So a single
 * upload can still finish over the line; what the cap guarantees is that the <em>next</em> one is
 * refused, because by then the overshoot is on disk and {@link #usedBytes()} sees it. Making it
 * hard would mean either aborting mid-index (leaving a half-written document) or guessing an
 * expansion factor, and a wrong guess rejects uploads that would have fit.
 *
 * <p>Usage is measured by walking the tree rather than tracked in a counter column: deletion then
 * frees space by construction, with no counter to drift out of sync (this is also what makes the
 * {@code /admin} chunk/document deletions, directory sync's deletions and manual {@code rm}s all
 * register without any of them knowing about quotas). The walk is skipped entirely when no limit is
 * configured, which is the default — so the out-of-the-box path costs nothing.
 */
@Service
public class StorageQuotaService {

    private static final Logger log = LoggerFactory.getLogger(StorageQuotaService.class);

    /** Sub-directories of {@code app.data-dir} that document indexing writes into. */
    private static final List<String> MEASURED_DIRS = List.of("documents", "converted", "images");

    /**
     * How long {@link #usedBytesCached()} may reuse a reading. Display-only: {@link #checkCanAccept}
     * never touches it, because a multi-file upload arrives back-to-back and a stale figure would
     * wave several files through the cap in a row.
     */
    private static final Duration DISPLAY_TTL = Duration.ofSeconds(30);

    private final AppProperties props;

    /** Last display reading + when it was taken; replaced wholesale so the pair can't tear. */
    private final AtomicReference<Reading> lastDisplayReading = new AtomicReference<>();

    private record Reading(long bytes, Instant takenAt) {}

    public StorageQuotaService(AppProperties props) {
        this.props = props;
    }

    /** Configured cap in bytes; {@code 0} means unlimited. */
    public long limitBytes() {
        return props.uploadSafe().maxTotalBytes();
    }

    /** True when a cap is configured at all. */
    public boolean hasLimit() {
        return props.uploadSafe().hasLimit();
    }

    /**
     * Refuses an incoming upload that would not fit under the cap.
     *
     * <p>No-op when no limit is configured — including the directory walk, so an unconfigured
     * deployment pays nothing for this call.
     *
     * @param incomingBytes size of the file about to be accepted
     * @param filename      shown in the message so a multi-file upload says which one was refused
     * @throws StorageQuotaExceededException when {@code used + incoming} exceeds the cap (→ 413)
     */
    public void checkCanAccept(long incomingBytes, String filename) {
        long limit = limitBytes();
        if (limit <= 0) return;

        long used = usedBytes();
        if (used + Math.max(0, incomingBytes) <= limit) return;

        String message = "저장 공간이 부족합니다 — %s 을(를) 받으면 상한 %s 를 넘습니다 (현재 사용 %s, 이 파일 %s). 문서를 삭제한 뒤 다시 시도하세요."
                .formatted(filename == null || filename.isBlank() ? "이 파일" : filename,
                        formatBytes(limit), formatBytes(used), formatBytes(incomingBytes));
        log.warn("[QUOTA] 업로드 거부: file={} incoming={} used={} limit={}",
                filename, incomingBytes, used, limit);
        throw new StorageQuotaExceededException(message, used, limit, incomingBytes);
    }

    /**
     * Bytes currently held by the document-owned trees under {@code app.data-dir}, excluding
     * {@code documents/backup/}. Missing directories count as 0 (a fresh install has none of them
     * yet), and an I/O failure makes that whole tree contribute 0 rather than propagating — a
     * quota reading is not worth failing an upload over, and under-counting only ever errs toward
     * accepting.
     */
    public long usedBytes() {
        Path dataDir = Path.of(props.dataDir());
        Path archived = dataDir.resolve("documents").resolve(DocumentBackupCleaner.BACKUP_DIR_NAME);
        long total = 0;
        for (String dir : MEASURED_DIRS) {
            total += treeSize(dataDir.resolve(dir), archived);
        }
        return total;
    }

    /**
     * Usage for display ({@code /settings}), memoized for {@link #DISPLAY_TTL}.
     *
     * <p>{@code /settings} is guest-open in every auth mode, so rendering it must not hand an
     * unauthenticated visitor a way to trigger an unbounded directory walk per request. The number
     * being up to 30s stale is invisible on a page a human reads; the enforcement path deliberately
     * does not share this and re-measures every time.
     */
    public long usedBytesCached() {
        Reading cached = lastDisplayReading.get();
        if (cached != null && Duration.between(cached.takenAt(), Instant.now()).compareTo(DISPLAY_TTL) < 0) {
            return cached.bytes();
        }
        long fresh = usedBytes();
        lastDisplayReading.set(new Reading(fresh, Instant.now()));
        return fresh;
    }

    /** @param excluded subtree to skip entirely; {@code null} to walk everything */
    private long treeSize(Path dir, Path excluded) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> excluded == null || !p.startsWith(excluded))
                    .mapToLong(StorageQuotaService::sizeOf)
                    .sum();
        } catch (IOException | UncheckedIOException e) {
            log.warn("[QUOTA] 사용량 측정 실패 (이 트리는 0으로 집계): {} — {}", dir, e.toString());
            return 0;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;   // vanished between walk and stat (concurrent delete) — not this call's problem
        }
    }

    /** Human-readable byte count for messages and the {@code /settings} row. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (value >= 100 ? "%.0f %s" : "%.1f %s").formatted(value, units[unit]);
    }
}
