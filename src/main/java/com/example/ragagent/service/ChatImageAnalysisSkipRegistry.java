package com.example.ragagent.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-thread "skip image analysis" signal for the chat SSE path (§ 채팅 이미지 분석 건너뛰기).
 *
 * <p>Deliberately not the same mechanism as the SSE emitter's abort/timeout/error interrupt
 * ({@code ChatController.streamChat()}) — that tears down the whole turn. A user who clicks
 * "건너뛰기" while "이미지 분석 중 (2/5)" is showing wants the answer to proceed with whatever
 * descriptions are already done, not to abort the question entirely. {@code LazyVisionService}
 * polls {@link #isSkipRequested} instead of blocking on every in-flight Vision call; images still
 * in flight when skip fires keep running in the background so their result still lands in the
 * {@code image_descriptions} cache for the next turn that needs them — skip only stops *this*
 * turn's answer from waiting on them.
 *
 * <p>Keyed by {@code threadId} rather than a minted per-turn token: {@code chat-stream.js} only
 * ever has one stream in flight per tab ({@code currentAbort} is a single module-level variable),
 * so threadId is already the client's natural handle on "the turn currently streaming" — no new
 * identifier needs inventing or shipping to the client.
 */
@Component
public class ChatImageAnalysisSkipRegistry {

    private final Map<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /** Called once at the start of a streaming turn — resets any stale flag from a prior turn. */
    public void begin(String threadId) {
        flags.put(threadId, new AtomicBoolean(false));
    }

    /** Called from the turn's {@code finally} block so a flag never outlives its turn. */
    public void end(String threadId) {
        flags.remove(threadId);
    }

    /**
     * User-initiated: stop waiting on remaining Lazy Vision calls for the turn currently streaming
     * on this thread. A no-op (not an error) if no turn is currently in the image-analysis phase —
     * the click race (button clicked just as analysis finishes) is harmless either way.
     */
    public void requestSkip(String threadId) {
        AtomicBoolean flag = flags.get(threadId);
        if (flag != null) flag.set(true);
    }

    /** Polled by {@code LazyVisionService} between/at each completed image. */
    public boolean isSkipRequested(String threadId) {
        AtomicBoolean flag = flags.get(threadId);
        return flag != null && flag.get();
    }
}
