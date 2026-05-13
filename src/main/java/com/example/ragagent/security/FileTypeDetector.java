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
        byte[] head = new byte[8];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.read(head);
            if (n < 4) return false;
        }
        return switch (declaredExt.toLowerCase()) {
            // %PDF
            case ".pdf"           -> head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            // ZIP/PK — Office 2007+ (DOCX, PPTX, XLSX are all ZIP-based)
            case ".docx", ".pptx" -> head[0] == 0x50 && head[1] == 0x4B && head[2] == 0x03 && head[3] == 0x04;
            // Text: no NUL bytes in first 8 bytes
            case ".txt", ".md"    -> isLikelyUtf8Text(head);
            default               -> false;
        };
    }

    private static boolean isLikelyUtf8Text(byte[] head) {
        for (byte b : head) {
            if (b == 0) return false;
        }
        return true;
    }

    private FileTypeDetector() {}
}
