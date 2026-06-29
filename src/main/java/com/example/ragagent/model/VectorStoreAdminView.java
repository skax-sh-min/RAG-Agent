package com.example.ragagent.model;

import java.util.List;

/**
 * Backend-agnostic vector store status for the {@code /admin} page (Step 5.8).
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
        List<VersionCount> perVersion,  // chunk count per version
        Integer collectionCount,        // chroma only
        String vecVersion,              // sqlite-vec only (vec_version())
        Integer dimension               // sqlite-vec only (embedding dimension)
) {
    public record VersionCount(String version, long chunkCount) {}

    public boolean isSqliteVec() { return "sqlite-vec".equals(backend); }
    public boolean isChroma()    { return "chroma".equals(backend); }

    /** True when document count is known (sqlite-vec); chroma reports -1. */
    public boolean hasDocCount() { return totalDocs >= 0; }
}
