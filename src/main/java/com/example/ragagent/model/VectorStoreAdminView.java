package com.example.ragagent.model;

/**
 * Backend-agnostic vector store status for the {@code /admin} page.
 *
 * <p>Common fields apply to both backends; backend-specific fields are null on the other backend:
 * <ul>
 *   <li>chroma — {@code collectionCount} populated; {@code vecVersion}/{@code dimension} null.</li>
 *   <li>sqlite-vec — {@code vecVersion}/{@code dimension} populated; {@code collectionCount} null.</li>
 * </ul>
 */
public record VectorStoreAdminView(
        String backend,                 // "chroma" | "sqlite-vec"
        boolean healthy,
        long totalDocs,                 // distinct documents; -1 = unknown (chroma)
        long totalChunks,
        Integer collectionCount,        // chroma only
        String vecVersion,              // sqlite-vec only (vec_version())
        Integer dimension,              // sqlite-vec only (embedding dimension)
        String dbPath                   // the one SQLite file the app opened; null when unknown (unit tests)
) {
    public boolean isSqliteVec() { return "sqlite-vec".equals(backend); }
    public boolean isChroma()    { return "chroma".equals(backend); }

    /** True when document count is known (sqlite-vec); chroma reports -1. */
    public boolean hasDocCount() { return totalDocs >= 0; }

    /** Bare filename (no directory) for the compact /admin status-card display — full path moves to a hover popover. */
    public String dbFileName() {
        return extractFileName(dbPath);
    }

    /** Splits on both '/' and '\' regardless of the running OS, since a configured path may use either separator. */
    private static String extractFileName(String path) {
        if (path == null) {
            return null;
        }
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /**
     * HTML content (line break, no escaping) for the /admin status card's DB-path hover popover: the
     * full path and what that file holds. The contents line is the point — the card used to list an
     * "operational DB" and a "vector DB", and on a split deployment the first of them held nothing.
     * A chroma deployment keeps its vectors on the Chroma server, so the file holds everything else.
     */
    public String dbPathPopoverHtml() {
        String contents = isChroma()
                ? "운영 데이터 + 키워드 색인 (벡터는 Chroma 서버)"
                : "운영 데이터 + 벡터 + 키워드 색인";
        return dbPath + "<br>" + contents;
    }
}
