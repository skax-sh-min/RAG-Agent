package com.example.ragagent.llm;

public enum RoutingMode {
    COST_FIRST,    // LOCAL→NORMAL→PREMIUM, circuit-break fallback
    QUALITY_FIRST, // PREMIUM→NORMAL→LOCAL, circuit-break fallback
    PROGRESSIVE,   // COST_FIRST 시작 → 품질 미달 시 QUALITY_FIRST 재실행
    LOCAL_ONLY     // LOCAL 전용, 외부 API 차단
}
