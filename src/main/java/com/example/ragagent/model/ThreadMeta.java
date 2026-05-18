package com.example.ragagent.model;

public record ThreadMeta(
        String threadId,
        String userId,
        String title,
        String version,
        String createdAt,
        String updatedAt,
        String routingMode
) {}
