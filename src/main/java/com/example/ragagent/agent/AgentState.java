package com.example.ragagent.agent;

import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.SourceRef;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Locale;

/**
 * Immutable state record passed through all agent graph nodes.
 * Each node returns a new instance via state.toBuilder().xxx().build() — no shared mutable state.
 * Equivalent to LangGraph's TypedDict AgentState in the Python version.
 */
public record AgentState(
        String question,
        String version,
        String threadId,
        String userId,
        String questionType,
        List<Document> retrievedDocs,
        List<SourceRef> sources,
        List<String> retrievalWarnings,
        List<String> imageRefs,
        String answer,
        int retryCount,
        boolean needsRetry,
        String conversationHistory,
        int totalInputTokens,
        int totalOutputTokens,
        int llmCallCount,
        RoutingMode routingMode,
        String usedProvider,
        String premiumUpgraded,   // PROGRESSIVE: PREMIUM 프로바이더명 (null=미적용)
        String dualLocalAnswer,   // DUAL: LOCAL 모델 답변
        String dualLocalProvider, // DUAL: LOCAL 프로바이더명
        Boolean grounded,         // CRITIC 결과 (null=CRITIC 미실행)
        boolean directMode,       // RAG 없이 LLM 직접 호출
        Locale locale,            // UI 언어 설정 — LLM 시스템 프롬프트 언어 선택에 사용
        List<String> selectedTags // 검색 스코프 태그 (빈 리스트 = version-only 검색)
) {
    public AgentState {
        retrievedDocs     = retrievedDocs     == null ? List.of() : List.copyOf(retrievedDocs);
        sources           = sources           == null ? List.of() : List.copyOf(sources);
        retrievalWarnings = retrievalWarnings == null ? List.of() : List.copyOf(retrievalWarnings);
        imageRefs         = imageRefs         == null ? List.of() : List.copyOf(imageRefs);
        selectedTags      = selectedTags      == null ? List.of() : List.copyOf(selectedTags);
        if (userId      == null) userId      = "anonymous";
        if (routingMode == null) routingMode = RoutingMode.COST_FIRST;
        if (locale      == null) locale      = Locale.KOREAN;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static AgentState of(String question, String version, String threadId,
                                 String conversationHistory, RoutingMode routingMode) {
        return of(question, version, threadId, "anonymous", conversationHistory, routingMode, false, Locale.KOREAN);
    }

    public static AgentState of(String question, String version, String threadId,
                                 String conversationHistory, RoutingMode routingMode,
                                 boolean directMode) {
        return of(question, version, threadId, "anonymous", conversationHistory, routingMode, directMode, Locale.KOREAN);
    }

    public static AgentState of(String question, String version, String threadId,
                                 String conversationHistory, RoutingMode routingMode,
                                 boolean directMode, Locale locale) {
        return of(question, version, threadId, "anonymous", conversationHistory, routingMode, directMode, locale);
    }

    public static AgentState of(String question, String version, String threadId,
                                 String userId, String conversationHistory, RoutingMode routingMode,
                                 boolean directMode, Locale locale) {
        return new AgentState(
                question, version, threadId, userId,
                null, List.of(), List.of(), List.of(), List.of(),
                null, 0, false,
                conversationHistory,
                0, 0, 0,
                routingMode, null, null, null, null, null,
                directMode, locale, List.of());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    public boolean isDualMode()  { return routingMode == RoutingMode.DUAL; }
    public boolean wasUpgraded() { return premiumUpgraded != null; }

    // ── Builder factory ───────────────────────────────────────────────────────

    public Builder toBuilder()        { return new Builder(this); }
    public static Builder builder()   { return new Builder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private String question;
        private String version;
        private String threadId;
        private String userId                    = "anonymous";
        private String questionType;
        private List<Document> retrievedDocs    = List.of();
        private List<SourceRef> sources          = List.of();
        private List<String> retrievalWarnings   = List.of();
        private List<String> imageRefs           = List.of();
        private String answer;
        private int retryCount;
        private boolean needsRetry;
        private String conversationHistory       = "";
        private int totalInputTokens;
        private int totalOutputTokens;
        private int llmCallCount;
        private RoutingMode routingMode          = RoutingMode.COST_FIRST;
        private String usedProvider;
        private String premiumUpgraded;
        private String dualLocalAnswer;
        private String dualLocalProvider;
        private Boolean grounded;
        private boolean directMode;
        private Locale locale                    = Locale.KOREAN;
        private List<String> selectedTags        = List.of();

        Builder() {}

        Builder(AgentState s) {
            this.question           = s.question;
            this.version            = s.version;
            this.threadId           = s.threadId;
            this.userId             = s.userId;
            this.questionType       = s.questionType;
            this.retrievedDocs      = s.retrievedDocs;
            this.sources            = s.sources;
            this.retrievalWarnings  = s.retrievalWarnings;
            this.imageRefs          = s.imageRefs;
            this.answer             = s.answer;
            this.retryCount         = s.retryCount;
            this.needsRetry         = s.needsRetry;
            this.conversationHistory = s.conversationHistory;
            this.totalInputTokens   = s.totalInputTokens;
            this.totalOutputTokens  = s.totalOutputTokens;
            this.llmCallCount       = s.llmCallCount;
            this.routingMode        = s.routingMode;
            this.usedProvider       = s.usedProvider;
            this.premiumUpgraded    = s.premiumUpgraded;
            this.dualLocalAnswer    = s.dualLocalAnswer;
            this.dualLocalProvider  = s.dualLocalProvider;
            this.grounded           = s.grounded;
            this.directMode         = s.directMode;
            this.locale             = s.locale;
            this.selectedTags       = s.selectedTags;
        }

        public Builder question(String v)                  { this.question = v;           return this; }
        public Builder version(String v)                   { this.version = v;            return this; }
        public Builder threadId(String v)                  { this.threadId = v;           return this; }
        public Builder userId(String v)                    { this.userId = v;             return this; }
        public Builder questionType(String v)              { this.questionType = v;       return this; }
        public Builder retrievedDocs(List<Document> v)     { this.retrievedDocs = v;      return this; }
        public Builder sources(List<SourceRef> v)          { this.sources = v;            return this; }
        public Builder retrievalWarnings(List<String> v)   { this.retrievalWarnings = v;  return this; }
        public Builder imageRefs(List<String> v)           { this.imageRefs = v;          return this; }
        public Builder answer(String v)                    { this.answer = v;             return this; }
        public Builder retryCount(int v)                   { this.retryCount = v;         return this; }
        public Builder incrementRetry()                    { this.retryCount++;            return this; }
        public Builder needsRetry(boolean v)               { this.needsRetry = v;         return this; }
        public Builder conversationHistory(String v)       { this.conversationHistory = v; return this; }
        public Builder routingMode(RoutingMode v)          { this.routingMode = v;        return this; }
        public Builder usedProvider(String v)              { this.usedProvider = v;       return this; }
        public Builder premiumUpgraded(String v)           { this.premiumUpgraded = v;    return this; }
        public Builder grounded(Boolean v)                 { this.grounded = v;           return this; }
        public Builder directMode(boolean v)               { this.directMode = v;         return this; }
        public Builder locale(Locale v)                    { this.locale = v;             return this; }
        public Builder selectedTags(List<String> v)        { this.selectedTags = v;       return this; }

        public Builder dualResult(String localAnswer, String localProvider) {
            this.dualLocalAnswer   = localAnswer;
            this.dualLocalProvider = localProvider;
            return this;
        }

        public Builder accumulateTokens(int inputTokens, int outputTokens) {
            this.totalInputTokens  += inputTokens;
            this.totalOutputTokens += outputTokens;
            this.llmCallCount++;
            return this;
        }

        public AgentState build() {
            return new AgentState(
                    question, version, threadId, userId, questionType,
                    retrievedDocs, sources, retrievalWarnings, imageRefs,
                    answer, retryCount, needsRetry, conversationHistory,
                    totalInputTokens, totalOutputTokens, llmCallCount,
                    routingMode, usedProvider, premiumUpgraded,
                    dualLocalAnswer, dualLocalProvider, grounded,
                    directMode, locale, selectedTags);
        }
    }
}
