package com.example.ragagent.security;

import com.example.ragagent.exception.UnsupportedFileTypeException;
import com.example.ragagent.service.RagService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Single source of truth for upload validation.
 * Replaces duplicate sanitize/extension/magic-byte logic in ApiController and WebController.
 *
 * Usage:
 *   String filename = UploadValidator.sanitizeFilename(file.getOriginalFilename());
 *   Path tmp = UploadValidator.stageToTemp(file, filename);
 *   // ... caller handles tmp deletion when done
 */
public final class UploadValidator {

    /**
     * Sanitizes a client-supplied filename:
     * - strips path separators (keeps only the final segment)
     * - replaces unsafe characters with '_'
     * - rejects blank, leading-dot, and dot-only names
     *
     * @throws IllegalArgumentException on blank or structurally invalid name (→ HTTP 400)
     */
    public static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "upload_" + Instant.now().toEpochMilli();
        }
        String base = Path.of(original).getFileName().toString()
                .replaceAll("[^a-zA-Z0-9._\\-가-힣]", "_");
        if (base.isBlank() || base.startsWith(".") || base.chars().allMatch(c -> c == '.')) {
            throw new IllegalArgumentException("invalid filename: " + original);
        }
        return base;
    }

    /**
     * Checks that the extension of {@code sanitizedFilename} is on the supported list.
     *
     * @throws UnsupportedFileTypeException on unsupported extension (→ HTTP 422)
     */
    public static void checkExtension(String sanitizedFilename) {
        if (!RagService.isSupportedExtension(sanitizedFilename)) {
            throw new UnsupportedFileTypeException("unsupported extension: " + sanitizedFilename);
        }
    }

    /**
     * Transfers the multipart file to a temp file and validates its magic bytes.
     * On magic-byte mismatch the temp file is deleted before throwing.
     * Caller is responsible for deleting the returned path when done.
     *
     * @param file               the uploaded multipart file (must not be empty)
     * @param sanitizedFilename  already-sanitized filename (used for temp-file suffix and ext lookup)
     * @return path of the staged temp file
     * @throws UnsupportedFileTypeException if magic bytes do not match the declared extension (→ HTTP 422)
     * @throws IOException                  on I/O failure during transfer
     */
    public static Path stageToTemp(MultipartFile file, String sanitizedFilename) throws IOException {
        Path tmp = Files.createTempFile("rag-upload-", "-" + sanitizedFilename);
        file.transferTo(tmp);
        String ext = extensionOf(sanitizedFilename);
        if (!FileTypeDetector.matches(tmp, ext)) {
            Files.deleteIfExists(tmp);
            throw new UnsupportedFileTypeException("magic-byte mismatch: " + sanitizedFilename);
        }
        return tmp;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase();
    }

    private UploadValidator() {}
}
