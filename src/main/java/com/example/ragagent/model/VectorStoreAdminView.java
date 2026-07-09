package com.example.ragagent.model;

import java.util.List;

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
        String operationalDbPath,       // memory.db path (nullable)
        String vectorDbPath             // vector.db path — separate file or same as operational; null when unknown
) {
    public boolean isSqliteVec() { return "sqlite-vec".equals(backend); }
    public boolean isChroma()    { return "chroma".equals(backend); }

    /** True when document count is known (sqlite-vec); chroma reports -1. */
    public boolean hasDocCount() { return totalDocs >= 0; }

    /** True when the vector tables live in a dedicated file distinct from memory.db (active). */
    public boolean isDbSeparated() {
        return vectorDbPath != null && !vectorDbPath.equals(operationalDbPath);
    }
}
