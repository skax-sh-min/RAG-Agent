package com.example.ragagent.llm;

/** 자기 서버의 모델 상태를 알려 주는 임베딩 엔드포인트 — {@link LoadBalancingEmbeddingModel} 이 고를 때 읽는다. */
public interface ServerStateAware {
    ServerModelResolver.State serverState();
}
