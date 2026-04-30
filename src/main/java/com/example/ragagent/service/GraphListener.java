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

    /** Single-stream token (COST_FIRST / QUALITY_FIRST / PROGRESSIVE). */
    default void onToken(String text) {}

    /** DUAL-mode token with tab routing ("local" | "external"). */
    default void onToken(String tab, String text) {}

    default void onSourcesReady(List<SourceRef> sources) {}

    /** Fired when PROGRESSIVE mode triggers a PREMIUM provider upgrade. */
    default void onUpgrade(String provider) {}
}
