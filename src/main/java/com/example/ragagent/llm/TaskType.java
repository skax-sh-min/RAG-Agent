package com.example.ragagent.llm;

public enum TaskType {
    LIGHT_TEXT, // lightweight text-only (classification, short summaries)
    TEXT,       // standard text generation
    VISION,     // image analysis
    LIGHT_BOTH, // lightweight: basic text + basic vision
    BOTH        // full capability: all task types
}
