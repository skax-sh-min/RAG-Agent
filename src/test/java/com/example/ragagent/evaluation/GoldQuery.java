package com.example.ragagent.evaluation;

import java.util.List;

/**
 * §10.7.5 검색 품질 평가 하네스 — 골든셋 JSON(src/test/resources/search-eval/*.json) 한 케이스.
 *
 * {@code mustContainAny}는 정답 청크 식별자 대신, 실제 색인된 원문(교정본 MD)에서 그대로
 * 가져온 고유 부분 문자열이다. 재인덱싱으로 청크 경계·spring_doc_id가 바뀌어도 깨지지 않는다 —
 * 검색 결과 문서의 {@code getText()}가 이 문자열 중 하나라도 포함하면 relevant.
 */
public record GoldQuery(String id, String question, List<String> mustContainAny, String sourceHint) {

    /** 골든셋 JSON 파일 최상위 구조. */
    public record GoldSet(String version, int topK, List<GoldQuery> cases) {}
}
