package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-turn conversation memory keyed by thread_id.
 * Equivalent to LangGraph MemorySaver in the Python version.
 */
@Service
public class MemoryService {

    private final int maxConversationChars;
    // thread_id -> list of "Q: ...\nA: ..." entries
    private final ConcurrentHashMap<String, List<String>> conversations = new ConcurrentHashMap<>();

    public MemoryService(AppProperties appProperties) {
        this.maxConversationChars = appProperties.maxConversationChars();
    }

    public String getHistory(String threadId) {
        List<String> entries = conversations.getOrDefault(threadId, List.of());
        if (entries.isEmpty()) return "";

        // Join and truncate from the end to stay within char limit
        StringBuilder sb = new StringBuilder();
        for (int i = entries.size() - 1; i >= 0; i--) {
            String entry = entries.get(i);
            if (sb.length() + entry.length() > maxConversationChars) break;
            sb.insert(0, entry + "\n\n");
        }
        return sb.toString().strip();
    }

    public void addTurn(String threadId, String question, String answer) {
        conversations.computeIfAbsent(threadId, k -> new ArrayList<>())
                .add("Q: %s\nA: %s".formatted(question, answer));
    }

    public void clearHistory(String threadId) {
        conversations.remove(threadId);
    }
}
