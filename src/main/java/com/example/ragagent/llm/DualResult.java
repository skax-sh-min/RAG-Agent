package com.example.ragagent.llm;

public record DualResult(
        String localAnswer,      // LOCAL 모델 답변
        String localProvider,    // 사용된 LOCAL 프로바이더명
        String externalAnswer,   // 외부(NORMAL/PREMIUM) 모델 답변
        String externalProvider  // 사용된 외부 프로바이더명
) {}
