package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the derived text used for embedding + FTS input: the chunk's context header
 * ({@link MetaKey#CHUNK_CONTEXT}, set by {@link KeywordExtractor}) prepended to the
 * normalized chunk text ({@link MarkdownNoiseNormalizer}). Never used for the text that gets
 * persisted/displayed — {@link Document#getText()} itself stays the original (§10.1).
 */
public final class SearchTextBuilder {

    private SearchTextBuilder() {}

    /**
     * §10.8.5 — each indexed chunk previously ran this computation twice (once in the vector
     * store provider's {@code add()}, once in {@code KeywordSearchRepository.indexChunks()}).
     * When {@link MetaKey#SEARCH_TEXT} is already present (see {@link #precompute}), reuse it
     * instead of recomputing. Callers that never precompute (e.g. tests building a bare
     * {@code Document}) fall through to the original computation — behavior-identical.
     */
    public static String build(Document doc) {
        Object precomputed = doc.getMetadata().get(MetaKey.SEARCH_TEXT);
        if (precomputed instanceof String s && !s.isBlank()) return s;
        return compute(doc);
    }

    /**
     * Computes {@link #build} once and stores the result under {@link MetaKey#SEARCH_TEXT} so
     * every subsequent {@link #build} call on the returned Document short-circuits. Callers that
     * persist this Document's metadata (Chroma/sqlite-vec {@code add()}) must strip the key
     * first — it is transient (unlike {@link MetaKey#CHUNK_CONTEXT}, which IS persisted so the
     * {@code /admin} chunk editor can show/edit it).
     */
    public static Document precompute(Document doc) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put(MetaKey.SEARCH_TEXT, compute(doc));
        return new Document(doc.getId(), doc.getText(), meta);
    }

    private static String compute(Document doc) {
        String context = str(doc.getMetadata().get(MetaKey.CHUNK_CONTEXT));
        String normalized = MarkdownNoiseNormalizer.normalize(doc.getText());
        return context.isBlank() ? normalized : context + "\n\n" + normalized;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
