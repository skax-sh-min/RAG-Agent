package com.example.ragagent.model;

public record ChatForm(
        String question,
        String threadId,
        String version,
        String routingMode
) {}
