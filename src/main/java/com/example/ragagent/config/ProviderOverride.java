package com.example.ragagent.config;

/**
 * {@code /settings} 의 LLM 라우팅 표에서 고친 프로바이더 하나 — {@code llm_provider_override} 한 행(Flyway V10).
 *
 * <p>{@code application.properties} 의 {@code app.llm.providers[N]} 위에 얹히는 값이고, 이름이 키다(같은 이름의 프로바이더는 한
 * 논리 모델 — {@code ProviderToggle} 과 같은 규칙). {@code null} 칸은 "properties 값 그대로"다. API 키는 일부러 없다 — 키는
 * 환경변수(.env)로만 받는다.
 *
 * @param contextSize 수동으로 넣은 컨텍스트 창(토큰). 다른 칸과 달리 <b>저장 즉시</b> 반영된다
 * @param added       화면에서 새로 만든 프로바이더 — properties 에 없다. 그때는 주소·모델·type·role·우선순위가 다 채워져 있다
 * @param deleted     재기동 뒤 등록하지 않는다(properties 정의를 숨긴다)
 */
public record ProviderOverride(String name, String baseUrl, String model, String type, String role,
                               Integer priority, Integer contextSize, boolean added, boolean deleted) {

    /** 재기동해야 반영되는 칸이 하나라도 있는가 — 컨텍스트만 고친 행은 아니다. */
    public boolean changesRestartFields() {
        return baseUrl != null || model != null || type != null || role != null || priority != null || added || deleted;
    }

    /** 재기동 대상 칸을 비운 사본 — 기동 검증에 실패해 properties 값으로 떨어질 때, 수동 컨텍스트는 남긴다. */
    public ProviderOverride contextOnly() {
        return new ProviderOverride(name, null, null, null, null, null, contextSize, false, false);
    }
}
