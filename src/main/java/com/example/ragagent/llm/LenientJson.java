package com.example.ragagent.llm;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 이 돌려준 JSON 을 읽는 매퍼 — 필드를 빼먹거나 모르는 필드를 덧붙이는 것이 정상인 입력용이다.
 *
 * <p>Jackson 3(Spring Boot 4 / Spring AI 2.0)는 {@code FAIL_ON_NULL_FOR_PRIMITIVES} 를 <b>켜고</b> 시작한다. 판정 JSON
 * ({@code {"sufficient":false}} 처럼 {@code grounded} 를 빼는 응답이 흔하다)을 {@code boolean} 컴포넌트를 가진 레코드로
 * 읽을 때 Jackson 2 에서는 {@code false} 로 읽혔지만 이제는 예외가 되어, 판정 전체가 "읽지 못함"으로 떨어진다(= 검증이 통째로
 * 사라진다). 이 앱의 {@code BeanOutputConverter} 사용처(분류·답변 검증)가 이 매퍼를 넘겨 예전 동작을 지킨다.
 */
public final class LenientJson {

    /** 불변·스레드 안전 — 하나를 공유한다. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private LenientJson() {}
}
