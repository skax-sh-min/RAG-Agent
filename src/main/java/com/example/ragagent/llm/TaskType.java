package com.example.ragagent.llm;

public enum TaskType {
    MICRO_TEXT, // §6.21 — reasoning-free chores (keyword+context, summary, title, query expansion): smallest model
    LIGHT_TEXT, // lightweight text-only (classification, meta direct-answer)
    TEXT,       // standard text generation
    VISION,     // image analysis
    LIGHT_BOTH, // lightweight: basic text + basic vision
    BOTH        // full capability: all task types
}
