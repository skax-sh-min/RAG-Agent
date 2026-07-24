package com.example.ragagent.model;

/**
 * How long / how detailed the assistant's answer should be — picked per message in the chat input
 * bar (S / M / L, default {@link #M}).
 *
 * <p>A mode expresses its budget purely as {@link #tokenRatio()}: the fraction of
 * {@code app.llm.max-tokens} (LLM_MAX_TOKENS) used as the per-call {@code maxTokens} option, which
 * stops generation early rather than cutting text after the fact. The <em>style</em> of the answer
 * (summary vs. detailed vs. source-preserving) is carried separately by the mode's prompt
 * instruction ({@link #promptKey()}); there is deliberately no character cap — the answer's own
 * absolute ceiling stays {@code AnswerService.MAX_ANSWER_LEN}, unchanged by the mode.
 */
public enum ResponseMode {

    /** 요약적이고 간단하게 — 핵심만. */
    S(0.15),
    /** 쉽고 자세하게 — 기본값. */
    M(0.40),
    /** 원문을 최대한 살리고 최대한 많은 내용을. */
    L(0.90);

    /** Mode used when the client sends nothing / something unrecognized. */
    public static final ResponseMode DEFAULT = M;

    private final double tokenRatio;

    ResponseMode(double tokenRatio) {
        this.tokenRatio = tokenRatio;
    }

    /** Fraction of {@code app.llm.max-tokens} to allow this mode's single answer call. */
    public double tokenRatio() { return tokenRatio; }

    /**
     * Per-call {@code maxTokens} for this mode, derived from the configured {@code LLM_MAX_TOKENS}.
     * Floors at 256 so a small configured ceiling can't shrink S into an unusable stub.
     */
    public int maxTokens(int configuredMaxTokens) {
        if (configuredMaxTokens <= 0) return 0; // caller treats 0 as "leave the provider default"
        return Math.max(256, (int) Math.round(configuredMaxTokens * tokenRatio));
    }

    /** i18n key for this mode's answer-style instruction ({@code messages*.properties}). */
    public String promptKey() {
        return "prompt.answer.style." + name().toLowerCase();
    }

    /** Lenient parse — {@code null}/blank/unknown all fall back to {@link #DEFAULT}, never throws. */
    public static ResponseMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
