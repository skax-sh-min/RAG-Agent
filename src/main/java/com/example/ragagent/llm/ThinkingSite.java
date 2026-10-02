package com.example.ragagent.llm;

import java.util.Optional;

/**
 * LLM 을 부르는 자리(호출 지점) — 생각 수준을 따로 정할 수 있는 단위다(PLAN §6.29 ②).
 *
 * <p>호출부는 옵션에 자기 사이트만 표시한다({@link ThinkingControl#mark}). 수준은 설정에서 읽고
 * ({@code app.llm.thinking.<id>}), 그 수준을 어떤 필드로 보낼지는 받는 프로바이더가 정해진 뒤
 * {@link ThinkingControlChatModel} 이 정한다 — 어느 프로바이더가 받을지는 라우터가 나중에 정하기 때문이다.
 *
 * <p><b>출하 기본값</b>은 참조 배포(llama.cpp + 생각이 기본으로 켜진 모델)에서 지금과 같은 전송 결과가 되게
 * 골랐다: 아무것도 보내지 않던 자리는 그 서버의 기본값대로 생각하고 있었으므로 {@code LOW}, 이미 생각을 끄던
 * 세 자리는 {@code OFF}. {@code application.properties} 에도 같은 값이 사이트마다 한 줄씩 있고 둘이 같은지는
 * {@code ThinkingSiteTest} 가 지킨다 — 이 값은 파일의 줄이 빠지거나 값이 틀렸을 때 떨어지는 자리다.
 *
 * <p>1단계에서 실제로 표시하는 곳은 {@link #CONDENSE}·{@link #POST_ANSWER}·{@link #CURATED_SUGGEST} 셋뿐이다.
 * 나머지는 표시가 없어 지금처럼 아무것도 싣지 않고 나간다(2·3단계에서 배선한다).
 */
public enum ThinkingSite {
    ANSWER_RAG_S("answer-rag-s", ThinkingLevel.LOW),
    ANSWER_RAG_N("answer-rag-n", ThinkingLevel.LOW),
    ANSWER_RAG_C("answer-rag-c", ThinkingLevel.LOW),
    ANSWER_DIRECT_S("answer-direct-s", ThinkingLevel.LOW),
    ANSWER_DIRECT_N("answer-direct-n", ThinkingLevel.LOW),
    ANSWER_META("answer-meta", ThinkingLevel.LOW),
    EVAL("eval", ThinkingLevel.LOW),
    EVAL_CREATIVE("eval-creative", ThinkingLevel.LOW),
    CLASSIFY("classify", ThinkingLevel.LOW),
    CONDENSE("condense", ThinkingLevel.OFF),
    QUERY_EXPANSION("query-expansion", ThinkingLevel.LOW),
    RERANK("rerank", ThinkingLevel.LOW),
    POST_ANSWER("post-answer", ThinkingLevel.OFF),
    TITLE("title", ThinkingLevel.LOW),
    SUMMARY("summary", ThinkingLevel.LOW),
    KEYWORD_CONTEXT("keyword-context", ThinkingLevel.LOW),
    MD_CORRECT("md-correct", ThinkingLevel.LOW),
    TXT_TO_MD("txt-to-md", ThinkingLevel.LOW),
    IMAGE_DESCRIBE("image-describe", ThinkingLevel.LOW),
    IMAGE_TYPE("image-type", ThinkingLevel.LOW),
    MD_CORRECT_VISION("md-correct-vision", ThinkingLevel.LOW),
    CURATED_SUGGEST("curated-suggest", ThinkingLevel.OFF);

    private final String id;
    private final ThinkingLevel shippedDefault;

    ThinkingSite(String id, ThinkingLevel shippedDefault) {
        this.id = id;
        this.shippedDefault = shippedDefault;
    }

    /** 설정 키의 마지막 마디 — {@code app.llm.thinking.<id>}. */
    public String id() {
        return id;
    }

    /** {@code application.properties} 의 줄이 없거나 값이 틀렸을 때 쓰는 값. */
    public ThinkingLevel shippedDefault() {
        return shippedDefault;
    }

    /** {@code application.properties} 에 적는 키. 로그·경고 문구가 운영자에게 가리킬 자리다. */
    public String propertyKey() {
        return "app.llm.thinking." + id;
    }

    /**
     * {@code /settings} 오버라이드 키 — 다른 핫 키({@code llm.temperature} 등)처럼 {@code app.} 을 뺀 형태다.
     * 5단계에서 {@code /settings} 가 이 키로 쓰고, {@code AppProperties.LlmConfig.thinkingLevel()} 이 이 키로 읽는다.
     */
    public String settingsKey() {
        return "llm.thinking." + id;
    }

    public static Optional<ThinkingSite> byId(String id) {
        if (id == null) return Optional.empty();
        for (ThinkingSite site : values()) {
            if (site.id.equals(id)) return Optional.of(site);
        }
        return Optional.empty();
    }
}
