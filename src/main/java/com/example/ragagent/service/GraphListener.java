package com.example.ragagent.service;

import com.example.ragagent.model.SourceRef;

import java.util.List;

/**
 * Hook interface injected into AgentGraph.runStreaming().
 * The NOOP constant is used by the existing blocking path so it incurs zero overhead.
 */
public interface GraphListener {

    GraphListener NOOP = new GraphListener() {};

    default void onNodeEnter(String nodeName) {}

    default void onToken(String text) {}

    default void onSourcesReady(List<SourceRef> sources) {}

    /** Fired alongside onSourcesReady when the retrieved documents reference extracted images. */
    default void onImagesReady(List<String> imageRefs) {}

    /** Fired when PROGRESSIVE mode triggers a PREMIUM provider upgrade. */
    default void onUpgrade(String provider) {}

    /**
     * Fired once, right before the blocking sufficiency+grounded evaluation call
     * ({@code AnswerService.evaluate()}) — the answer has finished streaming but the turn isn't
     * done yet, and that evaluation call can take several seconds to tens of seconds with no
     * token/stage event of its own. Lets a streaming client show a "verifying" indicator instead
     * of going silent until the next event ({@link #onRetry} or the terminal "done").
     */
    default void onVerifying() {}

    /**
     * Fired when the graph decides the just-streamed answer failed verification (answer
     * insufficiency or critic ungroundedness) and is about to loop back to RETRIEVAL with an
     * expanded scope. Lets the streaming client preserve the unverified answer (marked 미검증)
     * and show a retry notice before the fresh attempt re-streams.
     *
     * @param reason     "answer" (insufficient) or "critic" (ungrounded)
     * @param retryCount the upcoming attempt's retry number (1-based)
     */
    default void onRetry(String reason, int retryCount) {}
}
