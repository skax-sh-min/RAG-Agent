package com.example.ragagent.ingestion;

import com.example.ragagent.model.MetaKey;
import org.springframework.ai.document.Document;

/**
 * Builds the derived text used for embedding + FTS input: the chunk's context header
 * ({@link MetaKey#CHUNK_CONTEXT}, set by {@link KeywordExtractor}) prepended to the
 * normalized chunk text ({@link MarkdownNoiseNormalizer}). Never used for the text that gets
 * persisted/displayed — {@link Document#getText()} itself stays the original (§10.1).
 */
public final class SearchTextBuilder {

    private SearchTextBuilder() {}

    public static String build(Document doc) {
        String context = str(doc.getMetadata().get(MetaKey.CHUNK_CONTEXT));
        String normalized = MarkdownNoiseNormalizer.normalize(doc.getText());
        return context.isBlank() ? normalized : context + "\n\n" + normalized;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
