package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * §6.15 — retention for {@code data/documents/backup/}, the archive
 * {@code RagService.archiveSourceFile} moves a deleted document's original into.
 *
 * <p><b>Why this exists.</b> Deleting a document never removed its source file — it only moved it
 * here — and nothing ever cleaned this directory, so upload→delete cycles accumulated bytes the
 * user can neither see nor reclaim from any screen. Once §6.15 put a cap on storage that was no
 * longer merely untidy: the deployment could reach its limit with no way back under it, since the
 * one remedy the error message names (delete a document) is what *created* the unreclaimable
 * bytes. {@code StorageQuotaService} therefore stopped counting this directory, and this class is
 * the other half of that decision — the cap only ignores these bytes safely if something bounds
 * them.
 *
 * <p><b>Three rules, applied in order</b> (each pass sees what the previous one left):
 * <ol>
 *   <li><b>One per document.</b> Only the newest archive of a given original filename is kept.
 *       Older ones are strictly worse copies of the same recovery target, and this is the rule that
 *       actually bounds a repeated upload→delete loop — the other two are time and size backstops.</li>
 *   <li><b>Age.</b> Anything older than {@code app.upload.backup-retention-days} (default 30) goes.
 *       Recovery from an accidental delete is a "noticed it this month" affair.</li>
 *   <li><b>Size.</b> If the directory is still over {@code app.upload.backup-max-size} (default
 *       2GB), the oldest go first until it fits.</li>
 * </ol>
 *
 * <p><b>Only files this app wrote are ever deleted.</b> Deletion is restricted to names matching
 * {@code {base}_{yyyyMMdd_HHmmss}{ext}} — the exact shape {@code archiveSourceFile} produces.
 * Anything else in the directory was put there by the operator and is not ours to remove. The size
 * rule still <em>measures</em> everything present (that is what is actually on disk), so a foreign
 * file can make the cap unreachable; that case is logged rather than resolved by deleting it.
 *
 * <p>Timestamps come from the filename, not the filesystem: that is the delete time this app
 * recorded, and it survives a copy or a restore that would reset {@code lastModifiedTime}.
 *
 * <p>Runs at startup (to clear whatever accumulated before this class existed) and after each
 * archive. Every failure is logged and swallowed — a retention sweep must never fail the delete
 * that triggered it, exactly as {@code archiveSourceFile} itself is best-effort.
 */
@Service
public class DocumentBackupCleaner {

    private static final Logger log = LoggerFactory.getLogger(DocumentBackupCleaner.class);

    /** {@code {base}_{yyyyMMdd_HHmmss}{ext}} — greedy prefix so the *last* stamp is the archive one
     *  (a document literally named {@code report_20240101_120000.pdf} keeps its own stamp in base). */
    static final Pattern ARCHIVED_NAME = Pattern.compile("^(.*)_(\\d{8}_\\d{6})(\\.[^.]*)?$");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final AppProperties props;

    public DocumentBackupCleaner(AppProperties props) {
        this.props = props;
    }

    /** One archived file: its path, the original filename it belongs to, and when it was archived. */
    private record Archive(Path path, String originalName, Instant archivedAt, long size) {}

    @EventListener(ApplicationReadyEvent.class)
    void sweepOnStartup() {
        int deleted = sweep();
        if (deleted > 0) log.info("[BACKUP] 기동 시 정리: {}건 삭제", deleted);
    }

    /**
     * Applies the three retention rules to {@code data/documents/backup/}.
     *
     * @return number of files deleted (0 when the directory does not exist or nothing qualified)
     */
    public int sweep() {
        Path dir = backupDir();
        if (!Files.isDirectory(dir)) return 0;
        try {
            AppProperties.UploadConfig cfg = props.uploadSafe();
            List<Archive> archives = listArchives(dir);
            List<Path> doomed = new ArrayList<>();

            keepNewestPerDocument(archives, doomed);
            dropExpired(archives, cfg.backupRetentionDaysOrZero(), doomed);
            dropUntilUnderSize(dir, archives, cfg.backupMaxBytes(), doomed);

            int deleted = 0;
            for (Path p : doomed) {
                try {
                    Files.deleteIfExists(p);
                    deleted++;
                    log.debug("[BACKUP] 삭제: {}", p.getFileName());
                } catch (IOException e) {
                    log.warn("[BACKUP] 삭제 실패 {}: {}", p.getFileName(), e.toString());
                }
            }
            return deleted;
        } catch (Exception e) {
            // Retention is housekeeping — never let it break the delete that called it.
            log.warn("[BACKUP] 정리 실패: {}", e.toString());
            return 0;
        }
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    /** Rule 1 — newest archive per original filename survives; the rest are removed from
     *  {@code archives} (so later rules don't reconsider them) and marked for deletion. */
    private void keepNewestPerDocument(List<Archive> archives, List<Path> doomed) {
        Map<String, List<Archive>> byName = new LinkedHashMap<>();
        for (Archive a : archives) byName.computeIfAbsent(a.originalName(), k -> new ArrayList<>()).add(a);

        for (List<Archive> group : byName.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(Archive::archivedAt).reversed());
            for (Archive stale : group.subList(1, group.size())) {
                doomed.add(stale.path());
                archives.remove(stale);
            }
        }
    }

    /** Rule 2 — older than {@code retentionDays}. {@code <= 0} disables this rule only. */
    private void dropExpired(List<Archive> archives, int retentionDays, List<Path> doomed) {
        if (retentionDays <= 0) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        archives.removeIf(a -> {
            if (a.archivedAt().isAfter(cutoff)) return false;
            doomed.add(a.path());
            return true;
        });
    }

    /**
     * Rule 3 — oldest-first until the directory fits. {@code <= 0} disables this rule only.
     *
     * <p>The measured total is the <b>whole</b> directory, not just what rules 1-2 left, because
     * that is what occupies the disk; only app-written archives are eligible for deletion, so a
     * large foreign file can leave the total above the cap — reported, not force-resolved.
     */
    private void dropUntilUnderSize(Path dir, List<Archive> archives, long maxBytes, List<Path> doomed) {
        if (maxBytes <= 0) return;
        long total = directorySize(dir) - doomed.stream().mapToLong(DocumentBackupCleaner::sizeOf).sum();
        if (total <= maxBytes) return;

        archives.sort(Comparator.comparing(Archive::archivedAt));   // oldest first
        for (Archive a : archives) {
            if (total <= maxBytes) return;
            doomed.add(a.path());
            total -= a.size();
        }
        if (total > maxBytes) {
            log.warn("[BACKUP] 상한({} bytes)까지 줄이지 못했다 — 남은 {} bytes 는 이 앱이 만들지 않은 파일이다: {}",
                    maxBytes, total, dir);
        }
    }

    // ── Filesystem ───────────────────────────────────────────────────────────

    private List<Archive> listArchives(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Archive> out = new ArrayList<>();
            files.filter(Files::isRegularFile).forEach(p -> {
                Matcher m = ARCHIVED_NAME.matcher(p.getFileName().toString());
                if (!m.matches()) return;                      // operator's own file — not ours to delete
                Instant at = parseStamp(m.group(2));
                if (at == null) return;
                String ext = m.group(3) == null ? "" : m.group(3);
                out.add(new Archive(p, m.group(1) + ext, at, sizeOf(p)));
            });
            return out;
        }
    }

    private static Instant parseStamp(String stamp) {
        try {
            return LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;   // digits matched but aren't a real date (e.g. 20261345_997799)
        }
    }

    private static long directorySize(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(DocumentBackupCleaner::sizeOf).sum();
        } catch (IOException | UncheckedIOException e) {
            log.warn("[BACKUP] 용량 측정 실패 {}: {}", dir, e.toString());
            return 0;
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** {@code {dataDir}/documents/backup} — the single definition of where archives live,
     *  shared with {@code StorageQuotaService}'s exclusion so the two can't drift apart. */
    public Path backupDir() {
        return Path.of(props.dataDir()).resolve("documents").resolve(BACKUP_DIR_NAME);
    }

    /** Directory name {@code RagService.archiveSourceFile} writes into. */
    public static final String BACKUP_DIR_NAME = "backup";
}
