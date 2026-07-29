package com.example.ragagent.export;

import java.util.Locale;

/**
 * Target formats for document export (§ 문서 내보내기). PPTX is deliberately absent — rebuilding
 * slides from reassembled prose needs slide-boundary and layout rules that don't follow from the
 * chunk data, so it is left as a separate future item.
 */
public enum ExportFormat {

    MD("md",   "text/markdown; charset=UTF-8"),
    TXT("txt", "text/plain; charset=UTF-8"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Content type for an MD export that had to be bundled with its images. */
    public static final String ZIP_CONTENT_TYPE = "application/zip";

    private final String extension;
    private final String contentType;

    ExportFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension()   { return extension; }
    public String contentType() { return contentType; }

    /** Lenient parse — unknown/blank input is rejected so the controller can answer 400. */
    public static ExportFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("내보내기 형식을 지정해야 합니다");
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "md", "markdown" -> MD;
            case "txt", "text"    -> TXT;
            case "docx", "word"   -> DOCX;
            default -> throw new IllegalArgumentException("지원하지 않는 내보내기 형식: " + raw);
        };
    }
}
