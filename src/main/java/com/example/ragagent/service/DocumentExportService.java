package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.export.DocxRenderer;
import com.example.ragagent.export.ExportFormat;
import com.example.ragagent.export.ExportPreprocessor;
import com.example.ragagent.export.ChunkReassembler;
import com.example.ragagent.ingestion.DocRegistry;
import com.example.ragagent.export.PlainTextRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Document export (§ 문서 내보내기): rebuilds a readable document from a document's indexed chunks
 * and renders it as MD / TXT / DOCX.
 *
 * <p>Chunks — not the saved {@code data/converted/*.md} — are the source, so the export reflects
 * whatever is actually indexed right now, including edits made in {@code /admin}. That means the
 * search-oriented artifacts have to be undone first; {@link ChunkReassembler} owns that contract.
 *
 * <p>An MD export whose document has images is delivered as a ZIP ({@code document.md} +
 * {@code images/}) so the relative image links resolve after download; without images it is a plain
 * {@code .md} file.
 */
@Service
public class DocumentExportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExportService.class);

    /** Upper bound on chunks pulled for one export — matches AdminService's own fetch cap. */
    private static final int MAX_CHUNKS = 10_000;

    private final AdminService adminService;
    private final MarkdownCorrectionService correctionService;
    private final DocRegistry docRegistry;
    private final AppProperties props;

    public DocumentExportService(AdminService adminService,
                                 MarkdownCorrectionService correctionService,
                                 DocRegistry docRegistry,
                                 AppProperties props) {
        this.adminService = adminService;
        this.correctionService = correctionService;
        this.docRegistry = docRegistry;
        this.props = props;
    }

    /** Export options chosen in the document-list export dialog. */
    public record Options(boolean includeImageDescriptions, boolean addHeadingNumbersAndToc) {}

    /** Rendered payload ready to stream to the browser. */
    public record Result(byte[] content, String filename, String contentType) {}

    /**
     * @param docId   document to export
     * @param version vector-store version/collection the document lives in
     * @throws IllegalArgumentException when the document has no indexed chunks (nothing to export)
     */
    public Result export(String docId, String version, ExportFormat format, Options options) {
        List<AdminService.ChunkRow> rows = adminService.getChunks(collectionOf(version), docId, 0, MAX_CHUNKS);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("내보낼 청크가 없습니다: " + docId);
        }

        String sourceName = rows.get(0).filename();
        boolean pptx = sourceName != null && sourceName.toLowerCase(Locale.ROOT).endsWith(".pptx");

        List<String> texts = rows.stream().map(AdminService.ChunkRow::fullText).toList();
        String rebuilt = ChunkReassembler.reassemble(texts, overlapFor(docId));
        // Deterministic, no-LLM blank-line/fence/table normalization — the same pass the ↺ re-index
        // path applies, so exported markdown follows the project's usual formatting rules.
        rebuilt = correctionService.postProcess(rebuilt, pptx);

        // PPTX slide titles aren't a chapter hierarchy, so numbering/TOC never applies there —
        // mirrors the upload-time addHeadingNumbers rule (see DocumentIndexer's .pptx branch).
        boolean numbering = options.addHeadingNumbersAndToc() && !pptx;
        String baseName = stripExtension(sourceName == null || sourceName.isBlank() ? docId : sourceName);

        return switch (format) {
            case MD   -> renderMarkdown(rebuilt, options, numbering, baseName, docId);
            case TXT  -> renderText(rebuilt, options, numbering, baseName);
            case DOCX -> renderDocx(rebuilt, options, numbering, baseName, docId);
        };
    }

    // ── per-format rendering ─────────────────────────────────────────────────────────────────

    private Result renderMarkdown(String rebuilt, Options options, boolean numbering,
                                  String baseName, String docId) {
        Set<String> used = new LinkedHashSet<>();
        String md = ExportPreprocessor.preprocess(rebuilt, options.includeImageDescriptions(), numbering,
                (path, atLineStart) -> {
                    used.add(path);
                    // Inline markdown image syntax is valid mid-line too, so a table cell keeps working.
                    return "![" + fileNameOf(path) + "](images/" + fileNameOf(path) + ")";
                });

        List<Path> images = resolveImages(used);
        if (images.isEmpty()) {
            return new Result(md.getBytes(StandardCharsets.UTF_8),
                    baseName + ".md", ExportFormat.MD.contentType());
        }
        byte[] zip = zip(baseName + ".md", md, images);
        log.info("[EXPORT] docId={} format=md(zip) images={}", docId, images.size());
        return new Result(zip, baseName + ".zip", ExportFormat.ZIP_CONTENT_TYPE);
    }

    private Result renderText(String rebuilt, Options options, boolean numbering, String baseName) {
        String md = ExportPreprocessor.preprocess(rebuilt, options.includeImageDescriptions(), numbering,
                (path, atLineStart) -> "(이미지: " + fileNameOf(path) + ")");
        return new Result(PlainTextRenderer.render(md).getBytes(StandardCharsets.UTF_8),
                baseName + ".txt", ExportFormat.TXT.contentType());
    }

    private Result renderDocx(String rebuilt, Options options, boolean numbering,
                              String baseName, String docId) {
        String md = ExportPreprocessor.preprocess(rebuilt, options.includeImageDescriptions(), numbering,
                (path, atLineStart) -> {
                    Path resolved = resolveImage(path);
                    if (resolved == null) return "(이미지 없음: " + fileNameOf(path) + ")";
                    // The renderer only embeds a picture from a token on its own line; a mid-line
                    // marker lives in a table cell, where injecting a line break would split the
                    // row — name the image inline there instead of embedding it.
                    return atLineStart
                            ? DocxRenderer.IMAGE_TOKEN + resolved.toAbsolutePath()
                            : "(이미지: " + fileNameOf(path) + ")";
                });
        try {
            byte[] bytes = DocxRenderer.render(md, baseName);
            return new Result(bytes, baseName + ".docx", ExportFormat.DOCX.contentType());
        } catch (Exception e) {
            log.error("[EXPORT] DOCX 생성 실패 docId={}: {}", docId, e.getMessage());
            throw new IllegalStateException("DOCX 생성에 실패했습니다: " + e.getMessage(), e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * The overlap this document's chunks were actually cut with, recorded in {@code doc_registry}
     * at index time. Falls back to the current setting only when the registry has no row at all
     * (chunks exist without a registry entry) — an existing row is authoritative even at 0, and
     * startup backfill guarantees registered documents have a value.
     *
     * <p>Using the stored value rather than today's setting matters because {@code app.chunk-overlap}
     * is hot-editable: retuning it after indexing would otherwise make {@link ChunkReassembler}
     * hunt for an overlap the chunks don't have, or miss one they do.
     */
    private int overlapFor(String docId) {
        return docRegistry.findByDocId(docId)
                .map(DocRegistry.DocRegistryEntry::chunkOverlap)
                .filter(java.util.Objects::nonNull)
                .orElseGet(props::chunkOverlapSafe);
    }

    /** sqlite-vec identifies a "collection" by the version string; chroma uses {@code manual_<v>}. */
    private String collectionOf(String version) {
        String v = (version == null || version.isBlank()) ? "latest" : version.strip();
        return "sqlite-vec".equals(props.vectorStoreSafe().type()) ? v : "manual_" + v;
    }

    private List<Path> resolveImages(Set<String> markerPaths) {
        List<Path> found = new ArrayList<>();
        for (String p : markerPaths) {
            Path resolved = resolveImage(p);
            if (resolved != null) found.add(resolved);
        }
        return found;
    }

    /**
     * Resolves an {@code images/{imageId}/{file}} marker path under the data directory. Returns
     * {@code null} when the file is gone (manually cleaned up), which every caller renders as a
     * note instead of failing the export.
     */
    private Path resolveImage(String markerPath) {
        try {
            Path base = Path.of(props.dataDir()).toAbsolutePath().normalize();
            Path candidate = base.resolve(markerPath).normalize();
            if (!candidate.startsWith(base)) return null;           // path-traversal guard
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] zip(String mdName, String markdown, List<Path> images) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(mdName));
            zos.write(markdown.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (Path img : images) {
                zos.putNextEntry(new ZipEntry("images/" + img.getFileName()));
                Files.copy(img, zos);
                zos.closeEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("ZIP 생성에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private static String fileNameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        // Windows/browser-safe download filename.
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
