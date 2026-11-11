package com.example.ragagent.llm;

/**
 * 서버는 떠 있지만 설정한 모델(EMBED_MODEL · RERANK_MODEL)이 서버의 모델 목록에 없어 <b>요청을 보내지 않았다</b>.
 * 서버에 닿지 못한 실패가 아니므로 다른 주소로 넘기는 장애 전환의 대상이 아니다({@code LlmRouter.isConnectionFailure}).
 */
public class ModelUnavailableException extends IllegalStateException {
    public ModelUnavailableException(String message) {
        super(message);
    }
}
