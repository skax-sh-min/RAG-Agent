package com.example.ragagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

import static com.example.ragagent.llm.TaskType.*;

public record LlmProvider(
        String name,
        TaskType type,
        ProviderRole role,
        int priority,
        String apiKey,
        String baseUrl,
        String model,
        boolean stream,
        ChatModel chatModel,
        OpenAiApi openAiApi
) {
    /**
     * Which task types this provider is eligible for.
     *
     * <p>The three text tasks form a capability ladder — {@code MICRO_TEXT ⊂ LIGHT_TEXT ⊂ TEXT} —
     * and every type absorbs the ones below it, so a deployment that registers only the heavier
     * model still gets the lighter chores done instead of losing them. {@code VISION} is a separate
     * axis; {@code LIGHT_BOTH}/{@code BOTH} combine the two.
     *
     * <p>{@code LIGHT_BOTH} appears on both sides — as a provider type and as a request
     * ({@code ImageTypeClassifier} is the only caller that asks for it, meaning "a multimodal model,
     * nothing heavyweight required"). Its branch used to list only the tasks <em>below</em> it and
     * omit itself, which left {@code BOTH} as the sole type able to serve that request: registering
     * the local model as {@code LIGHT_BOTH} — the "범용 로컬 LLM" the value exists for — made image
     * type classification the one image task that could not run on it, while image description
     * (a {@code VISION} request) worked fine.
     *
     * <p><b>{@code TEXT} absorbing the lighter tasks does not re-open what §6.21 closed.</b> That
     * split is enforced by how each <em>task</em> is labelled — classify and meta direct-answer are
     * {@code TEXT} precisely so a small {@code MICRO_TEXT} offload model can never pick them up —
     * not by starving {@code TEXT} providers of work. Where an <em>optional</em> chore must not
     * borrow the answer-serving tier, the guard is
     * {@link LlmRouter#hasMicroTextOffloadProvider()}, not this method (see
     * {@code ConversationSummarizerService}); routing {@code MICRO_TEXT} down to the answer model
     * has always been deliberate for chores with no non-LLM alternative, since {@code BOTH} absorbs
     * it too.
     *
     * <p>Leaving {@code TEXT} unable to serve the lighter tasks silently disabled keyword+context
     * extraction, MD correction, TXT structuring, thread titles and conversation summaries on two
     * real configurations: a single local model registered as {@code LOCAL_LLM_TYPE=TEXT}, and any
     * cloud-only deployment (every cloud provider shipped in {@code application.properties} is
     * {@code type=TEXT}, so those setups had <em>zero</em> eligible providers for
     * {@code MICRO_TEXT}/{@code LIGHT_TEXT}). Both surfaced only as a DEBUG-level
     * "All providers exhausted for task=MICRO_TEXT" — chat kept working, because chat is the one
     * path that routes on {@code TEXT}.
     */
    public boolean supports(TaskType req) {
        return switch (this.type) {
            case MICRO_TEXT -> req == MICRO_TEXT;
            case LIGHT_TEXT -> req == LIGHT_TEXT || req == MICRO_TEXT;
            case TEXT       -> req == TEXT || req == LIGHT_TEXT || req == MICRO_TEXT;
            case VISION     -> req == VISION;
            case LIGHT_BOTH -> req == LIGHT_BOTH || req == LIGHT_TEXT || req == MICRO_TEXT || req == VISION;
            case BOTH       -> true;
        };
    }

    public boolean hasValidApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
