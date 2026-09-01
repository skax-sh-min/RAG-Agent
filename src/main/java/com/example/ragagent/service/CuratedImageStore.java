package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.exception.UnsupportedFileTypeException;
import com.example.ragagent.repository.CuratedQaRepository;
import com.example.ragagent.repository.CuratedSubmissionRepository;
import com.example.ragagent.security.FileTypeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 지식 제안 본문 이미지 — upload, marker bookkeeping, approval-time Vision description, cleanup.
 *
 * <p>Images live at {@code {dataDir}/images/submissions/{sha16}.{ext}} and are referenced from a
 * submission body by the <em>same</em> {@code [이미지: images/.../file.png]} marker the document
 * pipeline emits. Reusing that marker rather than standard Markdown {@code ![](...)} is what makes
 * every downstream consumer work with no changes: {@code /api/v1/images/{docId}/{filename}} already
 * serves the path, {@code admin.html}'s chunk preview already renders it, and
 * {@code RetrievalService} already turns {@code image_paths} metadata into answer thumbnails.
 *
 * <p>The directory name {@code submissions} can never collide with a document's image directory —
 * those are 16-hex content hashes ({@code DocumentIndexer}'s imageId), so the letters rule it out.
 *
 * <p><b>Guest-open surface.</b> The board takes posts without a login in every auth mode, so this
 * is the one place in the app where an unauthenticated caller writes a binary to disk. Hence the
 * layered checks in {@link #store}: extension allowlist → size cap → magic bytes → content-hash
 * filename (the client never picks a path component), plus {@link #sweepOrphans} to keep drafts
 * that were never submitted from accumulating.
 */
@Service
public class CuratedImageStore {

    private static final Logger log = LoggerFactory.getLogger(CuratedImageStore.class);

    /** Sub-directory of {@code {dataDir}/images/} that holds every submission image. */
    public static final String IMAGE_DIR = "submissions";

    /** Only formats {@code DocumentController.getImage} is willing to serve inline as {@code image/*}. */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /** Per-submission marker cap — bounds the Vision fan-out one approval can trigger. */
    public static final int MAX_IMAGES_PER_SUBMISSION = 10;

    /** Per-file cap. Well under the servlet multipart limit so the check fails as a clean 400. */
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** An unreferenced draft image younger than this is left alone — it may still be in a form. */
    private static final Duration ORPHAN_GRACE = Duration.ofHours(24);

    /** Same shape as {@code DocumentIndexer.IMAGE_MARKER} — both marker forms. */
    private static final Pattern IMAGE_MARKER =
            Pattern.compile("\\[이미지(?:\\([^)]*\\))?: ([^\\]]+?)]");

    /**
     * A marker path we are willing to resolve to a real file. Deliberately an allowlist of the
     * exact {@code images/{dir}/{file}.{ext}} shape rather than a {@code ".."} blocklist: the body
     * is user-authored text, and {@link LazyVisionService#describeIfNeeded} resolves whatever it is
     * handed against {@code dataDir} with no containment check of its own.
     */
    private static final Pattern SAFE_IMAGE_PATH =
            Pattern.compile("images/[A-Za-z0-9_-]{1,64}/[A-Za-z0-9._-]{1,128}\\.(?:png|jpg|jpeg|gif|webp)");

    /** Paths this feature owns, i.e. is allowed to delete. Document images are never touched. */
    private static final Pattern OWNED_IMAGE_PATH =
            Pattern.compile("images/" + IMAGE_DIR + "/[A-Za-z0-9._-]{1,128}\\.(?:png|jpg|jpeg|gif|webp)");

    private final AppProperties props;
    private final CuratedSubmissionRepository submissionRepository;
    private final CuratedQaRepository curatedQaRepository;
    /** null when {@code app.image-description.enabled=false} — descriptions are then skipped. */
    private final LazyVisionService lazyVisionService;
    /** §6.15 — the same deployment-wide cap document uploads answer to. These images land under
     *  {@code {dataDir}/images/} and so are already counted by it; leaving the one guest-open
     *  binary-write path unchecked would be the obvious way around a storage cap. */
    private final StorageQuotaService storageQuotaService;

    /** {@code Optional<LazyVisionService>} rather than a plain parameter for the same reason
     *  {@code RetrievalService} takes it that way: the bean is {@code @ConditionalOnProperty} and
     *  simply does not exist when image descriptions are turned off. */
    public CuratedImageStore(AppProperties props,
                             CuratedSubmissionRepository submissionRepository,
                             CuratedQaRepository curatedQaRepository,
                             Optional<LazyVisionService> lazyVisionOpt,
                             StorageQuotaService storageQuotaService) {
        this.props = props;
        this.submissionRepository = submissionRepository;
        this.curatedQaRepository = curatedQaRepository;
        this.lazyVisionService = lazyVisionOpt.orElse(null);
        this.storageQuotaService = storageQuotaService;
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    /**
     * Stores an uploaded image and returns its marker path ({@code images/submissions/{sha16}.png}),
     * ready to be dropped into a body as {@code [이미지: <path>]}.
     *
     * <p>The filename is derived from the content hash, so the client contributes no part of the
     * path and re-uploading the same picture is idempotent instead of duplicating bytes on disk.
     *
     * @throws IllegalArgumentException     empty file or over {@link #MAX_IMAGE_BYTES} (→ 400)
     * @throws com.example.ragagent.exception.StorageQuotaExceededException
     *                                      deployment storage cap reached (→ 413, §6.15)
     * @throws UnsupportedFileTypeException extension not allowed, or magic bytes disagree (→ 422)
     */
    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "이미지가 너무 큽니다 (최대 " + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB).");
        }
        storageQuotaService.checkCanAccept(file.getSize(), file.getOriginalFilename());   // §6.15
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new UnsupportedFileTypeException(
                    "지원하지 않는 이미지 형식입니다 (" + String.join(", ", ALLOWED_EXTENSIONS) + "만 가능).");
        }

        Path tmp = Files.createTempFile("rag-submission-img-", "." + ext);
        try {
            file.transferTo(tmp);
            if (!FileTypeDetector.matches(tmp, "." + ext)) {
                throw new UnsupportedFileTypeException("이미지 내용이 확장자와 일치하지 않습니다.");
            }
            String name = sha256Prefix(tmp) + "." + ext;
            Path dir = imageDir();
            Files.createDirectories(dir);
            // REPLACE_EXISTING is safe precisely because the name is the content hash: the only
            // file it can overwrite is a byte-identical one.
            Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            log.info("[SUBMISSION] 본문 이미지 저장 {} ({} bytes)", name, file.getSize());
            return "images/" + IMAGE_DIR + "/" + name;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
        }
    }

    // ── Markers ──────────────────────────────────────────────────────────────

    /**
     * Every marker path in {@code body}, in order, without de-duplication. Static so
     * {@code CuratedQaService.buildDocument} can populate {@code image_paths} metadata from the
     * stored chunk text without taking a dependency on this whole service — one regex, one
     * definition of "what an image marker looks like".
     */
    public static List<String> markerPaths(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<String> paths = new ArrayList<>();
        Matcher m = IMAGE_MARKER.matcher(body);
        while (m.find()) paths.add(m.group(1).strip());
        return paths;
    }

    /** Distinct marker paths that point at a real, resolvable file under {@code {dataDir}/images/}. */
    public List<String> resolvablePaths(String body) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : markerPaths(body)) {
            if (SAFE_IMAGE_PATH.matcher(p).matches() && Files.isRegularFile(dataDir().resolve(p))) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Rejects a body carrying more images than one approval should ever fan out to. Counts markers,
     * not stored files: a body that references the same picture twice still costs two lookups and
     * renders twice.
     */
    public void validateImageCount(String body) {
        int n = markerPaths(body).size();
        if (n > MAX_IMAGES_PER_SUBMISSION) {
            throw new IllegalArgumentException(
                    "본문에 넣을 수 있는 이미지는 최대 " + MAX_IMAGES_PER_SUBMISSION + "개입니다 (현재 " + n + "개).");
        }
    }

    // ── 승인 시 Vision 설명 주입 ───────────────────────────────────────────────

    /**
     * Returns {@code body} with a {@code [이미지 설명: ...]} line inserted after every image marker
     * that doesn't already have one. Called at approval time, before the body is split into chunks.
     *
     * <p>Why here and not at query time: the description is what makes the picture's content
     * <em>searchable</em>. It has to be part of the text that gets embedded, and embedding happens
     * once, at approval. {@link LazyVisionService} does the actual work — so the result also lands
     * in the shared {@code image_descriptions} cache, and
     * {@code RetrievalService.hasEmbeddedDescription} sees the injected line and skips re-analyzing
     * the image on every later turn that retrieves the chunk.
     *
     * <p>No-op (returns the input unchanged) when image descriptions are disabled, when the body
     * has no resolvable marker, or when every marker is already described — so it is safe to call
     * unconditionally.
     */
    public String describeImages(String body) {
        if (body == null || body.isBlank() || lazyVisionService == null) return body;
        List<String> paths = resolvablePaths(body);
        if (paths.isEmpty()) return body;

        Map<String, String> descriptions;
        try {
            descriptions = lazyVisionService.describeIfNeeded(paths);
        } catch (Exception e) {
            // A Vision outage must not block an approval — the chunk is still perfectly useful
            // without the description, just less findable by the picture's content.
            log.warn("[SUBMISSION] 이미지 설명 생성 실패 — 설명 없이 진행: {}", e.getMessage());
            return body;
        }
        return injectDescriptions(body, descriptions);
    }

    /**
     * Pure text transform, package-private for unit testing. Mirrors
     * {@code MarkdownCorrectionService}'s injection, including the table-cell case: a raw newline
     * inside a {@code |...|} row splits the cell across two physical lines and shatters the table,
     * so there the description is appended after a {@code <br>} instead.
     */
    static String injectDescriptions(String body, Map<String, String> descriptions) {
        if (descriptions.isEmpty()) return body;
        Matcher m = IMAGE_MARKER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String full = m.group();
            String desc = descriptions.get(m.group(1).strip());
            if (desc == null || desc.isBlank() || alreadyDescribed(body, m.end())) {
                m.appendReplacement(out, Matcher.quoteReplacement(full));
                continue;
            }
            String flat = desc.replaceAll("\\s+", " ").strip();
            String separator = inTableRow(body, m.start()) ? "<br>" : "\n";
            m.appendReplacement(out,
                    Matcher.quoteReplacement(full + separator + "[이미지 설명: " + flat + "]"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** True when a {@code [이미지 설명: ...]} already follows the marker that ends at {@code end}. */
    private static boolean alreadyDescribed(String body, int end) {
        String after = body.substring(end).stripLeading();
        return after.startsWith("[이미지 설명:") || after.startsWith("<br>[이미지 설명:");
    }

    private static boolean inTableRow(String body, int markerStart) {
        int lineStart = body.lastIndexOf('\n', Math.max(0, markerStart - 1)) + 1;
        return body.substring(lineStart, markerStart).stripLeading().startsWith("|");
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Deletes the images a rejected/withdrawn submission introduced, but only those no longer
     * referenced anywhere else. Filenames are content hashes, so two people posting the same
     * screenshot share one file — deleting on the first rejection would break the second post.
     */
    public void releaseImages(String body) {
        List<String> owned = markerPaths(body).stream()
                .filter(p -> OWNED_IMAGE_PATH.matcher(p).matches())
                .distinct()
                .toList();
        if (owned.isEmpty()) return;

        Set<String> stillReferenced = referencedPaths();
        for (String path : owned) {
            if (stillReferenced.contains(path)) continue;
            deleteQuietly(dataDir().resolve(path));
        }
    }

    /**
     * Startup sweep for images uploaded into a draft that was never submitted — the form uploads
     * eagerly (so the author can see the picture in 미리보기 before posting), so an abandoned draft
     * leaves bytes behind with no row anywhere to clean up from. Only files older than
     * {@link #ORPHAN_GRACE} are considered, so an image sitting in an open form is never pulled out
     * from under it.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOrphans() {
        Path dir = imageDir();
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
        Set<String> referenced = referencedPaths();
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (!Files.isRegularFile(p)) continue;
                if (referenced.contains("images/" + IMAGE_DIR + "/" + p.getFileName())) continue;
                if (Files.getLastModifiedTime(p).toInstant().isAfter(cutoff)) continue;
                if (deleteQuietly(p)) removed++;
            }
        } catch (IOException e) {
            log.warn("[SUBMISSION] 고아 이미지 정리 실패: {}", e.getMessage());
            return;
        }
        if (removed > 0) {
            log.info("[SUBMISSION] 참조되지 않는 본문 이미지 {}건 정리", removed);
        }
    }

    /**
     * Every submission-owned path referenced by a submission that still matters (pending/approved)
     * or by an active curated row. Rejected and withdrawn submissions are excluded on purpose —
     * they are exactly what {@link #releaseImages} is releasing.
     */
    private Set<String> referencedPaths() {
        Set<String> out = new LinkedHashSet<>();
        Stream.concat(submissionRepository.liveBodiesWithImages().stream(),
                      curatedQaRepository.activeAnswersWithImages().stream())
                .forEach(text -> {
                    Matcher m = OWNED_IMAGE_PATH.matcher(text);
                    while (m.find()) out.add(m.group());
                });
        return out;
    }

    private boolean deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("[SUBMISSION] 이미지 삭제 실패 {}: {}", p, e.getMessage());
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Path dataDir() {
        return Path.of(props.dataDir());
    }

    private Path imageDir() {
        return dataDir().resolve("images").resolve(IMAGE_DIR);
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 16 hex chars (64 bits) of the content hash — same length/rationale as a document's imageId. */
    private static String sha256Prefix(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) { /* digest updated as a side effect */ }
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
