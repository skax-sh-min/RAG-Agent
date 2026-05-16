package com.example.ragagent.context;

import java.util.Locale;

public record ThreadContext(String threadId, String userId, Locale locale) {

    public static ThreadContext anonymous(String threadId) {
        return new ThreadContext(threadId, "anonymous", Locale.KOREAN);
    }

    public boolean isAuthenticated() {
        return !"anonymous".equals(userId);
    }
}
