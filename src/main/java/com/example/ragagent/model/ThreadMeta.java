package com.example.ragagent.model;

public record ThreadMeta(
        String threadId,
        String title,
        String version,
        String createdAt,
        String updatedAt
) {}
