package com.example.ragagent.exception;

/**
 * Internal control-flow signal for §6.16.1 indexing cancellation — thrown from
 * {@code DocumentIndexer}/{@code KeywordExtractor} when a user-initiated cancel interrupts
 * the indexing worker thread. Caught inside the async worker lambda in
 * {@code DocumentController}; never reaches {@code GlobalExceptionHandler}, so it does not
 * extend the {@code RagException} sealed hierarchy.
 */
public class IndexingCancelledException extends RuntimeException {
    public IndexingCancelledException(String message) {
        super(message);
    }
}
