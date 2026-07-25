package com.example.ragagent.model;

/**
 * How long / how detailed the assistant's answer should be — picked per message in the chat input
 * bar (S / M / L, default {@link #M}).
 *
 * <p>Each mode's budget is the LARGER of two floors: a fraction of {@code app.llm.max-tokens}
 * ({@link #tokenRatio()}) and a minimum character count ({@link #minChars()}) — so a small
 * configured ceiling can't collapse every mode down to the same tiny budget (previously S and M
 * were hard to tell apart). For Korean text, 1 LLM token is roughly 1 character (unlike English's
 * ~4 chars/token), so {@link #maxTokens(int)}'s return value is reused as-is — no chars↔tokens
 * conversion — both as the blocking call's {@code ChatOptions.maxTokens} AND as the "약 N자"
 * character target named in the mode's prompt instruction ({@link #promptKey()}), which is the
 * streaming path's *only* length control since it has no hard per-call token cap (token-by-token
 * UX). The answer's absolute ceiling stays {@code AnswerService.MAX_ANSWER_LEN} (20,000 chars),
 * unchanged by the mode.
 */
public enum ResponseMode {

    /** 요약적이고 간단하게 — 핵심만. */
    S(0.15, 2_000),
    /** 쉽고 자세하게 — 기본값. */
    M(0.40, 5_000),
    /** 원문을 최대한 살리고 최대한 많은 내용을. */
    L(0.70, 10_000);

    /** Mode used when the client sends nothing / something unrecognized. */
    public static final ResponseMode DEFAULT = M;

    private final double tokenRatio;
    private final int minChars;

    ResponseMode(double tokenRatio, int minChars) {
        this.tokenRatio = tokenRatio;
        this.minChars = minChars;
    }

    /** Fraction of {@code app.llm.max-tokens} to allow this mode's single answer call. */
    public double tokenRatio() { return tokenRatio; }

    /** Minimum character/token floor for this mode, regardless of the configured max-tokens. */
    public int minChars() { return minChars; }

    /**
     * Per-call {@code maxTokens} for this mode: the larger of the ratio share of the configured
     * {@code LLM_MAX_TOKENS} and {@link #minChars()} (used directly as a token count — see class
     * javadoc). Floors at 256 so a small configured ceiling can't shrink S into an unusable stub.
     */
    public int maxTokens(int configuredMaxTokens) {
        if (configuredMaxTokens <= 0) return 0; // caller treats 0 as "leave the provider default"
        int ratioTokens = (int) Math.round(configuredMaxTokens * tokenRatio);
        return Math.max(256, Math.max(ratioTokens, minChars));
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
