package com.example.ragagent.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/** Magic-byte validation to prevent disguised file uploads (e.g. .txt renamed from .exe). */
public final class FileTypeDetector {

    /**
     * Returns true if the first bytes of {@code file} match the expected signature for
     * {@code declaredExt} (e.g. ".pdf", ".docx").
     */
    public static boolean matches(Path file, String declaredExt) throws IOException {
        // 12 bytes, not 8: WebP's signature is split — "RIFF" at 0-3 and "WEBP" at 8-11 — so the
        // shorter read that covered every other format here can't see the discriminating half.
        byte[] head = new byte[12];
        final int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
            if (read < 4) return false;
        }
        return switch (declaredExt.toLowerCase()) {
            // %PDF
            case ".pdf"           -> head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            // ZIP/PK — Office 2007+ (DOCX, PPTX, XLSX are all ZIP-based)
            case ".docx", ".pptx" -> head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
            // Text: no NUL bytes in the leading bytes
            case ".txt", ".md"    -> isLikelyUtf8Text(head, read);
            // ── Raster images (지식 제안 본문 이미지) ────────────────────────────────
            // \x89PNG
            case ".png"           -> head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            // JPEG SOI + first marker byte
            case ".jpg", ".jpeg"  -> head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
            // GIF87a / GIF89a
            case ".gif"           -> head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8';
            // RIFF....WEBP
            case ".webp"          -> read >= 12
                                     && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                                     && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default               -> false;
        };
    }

    /**
     * Only the {@code read} bytes actually present are inspected — the rest of {@code head} is
     * zero-padding, and scanning it would read as a NUL byte and reject every text file shorter
     * than the buffer.
     */
    private static boolean isLikelyUtf8Text(byte[] head, int read) {
        for (int i = 0; i < read; i++) {
            if (head[i] == 0) return false;
        }
        return true;
    }

    private FileTypeDetector() {}
}
