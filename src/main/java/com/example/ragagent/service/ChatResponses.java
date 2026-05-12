package com.example.ragagent.service;

import org.springframework.ai.chat.model.ChatResponse;

/** Package-private null-safety helpers for ChatResponse text extraction. */
final class ChatResponses {
    private ChatResponses() {}

    /**
     * Safely extracts text from a ChatResponse, returning "" instead of throwing NPE
     * when any step in the getResult().getOutput().getText() chain is null.
     */
    static String safeText(ChatResponse response) {
        if (response == null) return "";
        var result = response.getResult();
        if (result == null) return "";
        var output = result.getOutput();
        if (output == null) return "";
        String text = output.getText();
        return text != null ? text : "";
    }
}
