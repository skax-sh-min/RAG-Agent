package com.example.ragagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String dataDir,
        int maxRetryCount,
        int maxConversationChars,
        int chunkSize,
        int chunkOverlap,
        int searchTopK
) {}
