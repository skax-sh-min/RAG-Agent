package com.example.ragagent.agent;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state passed through all agent graph nodes.
 * Equivalent to LangGraph's TypedDict AgentState in the Python version.
 */
public class AgentState {

    private String question;
    private String version = "latest";
    private String threadId = "default";

    // Classifier output: concept | usage | error | version | meta
    private String questionType;

    // Retrieval output
    private List<Document> retrievedDocs = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
    private List<String> retrievalWarnings = new ArrayList<>();

    // Answer output
    private String answer;

    // ReAct / Critic loop control
    private int retryCount = 0;
    private boolean needsRetry = false;

    // Prior conversation injected as context (max maxConversationChars)
    private String conversationHistory = "";

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }

    public List<Document> getRetrievedDocs() { return retrievedDocs; }
    public void setRetrievedDocs(List<Document> retrievedDocs) { this.retrievedDocs = retrievedDocs; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public List<String> getRetrievalWarnings() { return retrievalWarnings; }
    public void setRetrievalWarnings(List<String> retrievalWarnings) { this.retrievalWarnings = retrievalWarnings; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public int getRetryCount() { return retryCount; }
    public void incrementRetryCount() { this.retryCount++; }

    public boolean isNeedsRetry() { return needsRetry; }
    public void setNeedsRetry(boolean needsRetry) { this.needsRetry = needsRetry; }

    public String getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(String conversationHistory) { this.conversationHistory = conversationHistory; }
}
