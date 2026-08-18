package com.example.ragagent.llm;

public enum TaskType {
    MICRO_TEXT, // §6.21 — reasoning-free chores (keyword+context, summary, title, query expansion): smallest model
    LIGHT_TEXT, // lightweight text-only — currently only the document-conversion background callers
                // (MarkdownCorrectionService, TextToMarkdownService)
    TEXT,       // standard text generation — answer + combined eval + rerank, AND the
                // quality-sensitive classify / meta direct-answer (deliberately NOT LIGHT_TEXT:
                // keeping them on the answer model's type is what prevents a small offload model
                // from ever picking them up)
    VISION,     // image analysis
    LIGHT_BOTH, // lightweight: basic text + basic vision
    BOTH        // full capability: all task types
}
