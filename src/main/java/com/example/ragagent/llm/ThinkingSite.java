package com.example.ragagent.llm;

import java.util.List;
import java.util.Objects;
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
 * <p><b>라우팅의 단일 출처이기도 하다</b>(§6.29 ⑦-바). 사이트가 어느 {@link TaskType} 으로, 어느
 * {@link RoutingMode} 로 라우팅되는지를 여기 두고 호출부가 그 값을 읽는다 — {@code /settings} 미리보기(5단계)가
 * "이 사이트를 지금 누가 받는가"를 같은 값으로 계산하기 위해서다. 호출부에 {@code TaskType} 리터럴을 두면 두 답이
 * 갈라질 수 있어 {@code ThinkingSiteConventionTest} 가 막는다. 채팅 답변·검증처럼 대화가 고른 라우팅 모드를 따르는
 * 사이트는 모드를 갖지 않는다({@link #routingMode(RoutingMode)}).
 *
 * <p>2단계(2026-10-02)까지 표시가 붙은 곳: 체인을 지나는 블로킹 호출 전부. 채팅 화면의 답변 스트리밍(3단계)은
 * 아직 아무것도 싣지 않는다.
 */
public enum ThinkingSite {
    ANSWER_RAG_S("answer-rag-s", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    ANSWER_RAG_N("answer-rag-n", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    ANSWER_RAG_C("answer-rag-c", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    ANSWER_DIRECT_S("answer-direct-s", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    ANSWER_DIRECT_N("answer-direct-n", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    ANSWER_META("answer-meta", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    EVAL("eval", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    EVAL_CREATIVE("eval-creative", ThinkingLevel.LOW, Route.conversation(TaskType.TEXT)),
    CLASSIFY("classify", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.TEXT)),
    CONDENSE("condense", ThinkingLevel.OFF, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT)),
    /** 작은 모델부터 — 없으면 같은 서버의 큰 모델로 내려간다(§6.21, {@code routeProviderWithFallback}). */
    QUERY_EXPANSION("query-expansion", ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT)),
    RERANK("rerank", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.TEXT)),
    POST_ANSWER("post-answer", ThinkingLevel.OFF, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT)),
    TITLE("title", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT)),
    SUMMARY("summary", ThinkingLevel.LOW, Route.fixed(RoutingMode.LOCAL_ONLY, TaskType.MICRO_TEXT)),
    KEYWORD_CONTEXT("keyword-context", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT)),
    MD_CORRECT("md-correct", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_TEXT)),
    TXT_TO_MD("txt-to-md", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_TEXT)),
    IMAGE_DESCRIBE("image-describe", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.VISION)),
    IMAGE_TYPE("image-type", ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_BOTH)),
    MD_CORRECT_VISION("md-correct-vision", ThinkingLevel.LOW, Route.fixed(RoutingMode.LOCAL_ONLY, TaskType.VISION)),
    CURATED_SUGGEST("curated-suggest", ThinkingLevel.OFF, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT));

    /**
     * 사이트의 라우팅.
     *
     * @param taskTypes 첫째가 이 사이트의 작업 유형, 둘째부터는 그것을 받을 프로바이더가 없을 때 내려가는 순서
     *                  ({@code routeProviderWithFallback}). 대부분 하나다
     * @param fixedMode 이 사이트가 늘 쓰는 라우팅 모드. {@code null} 이면 대화가 고른 모드를 따른다
     */
    record Route(List<TaskType> taskTypes, RoutingMode fixedMode) {
        static Route fixed(RoutingMode mode, TaskType... taskTypes) {
            return new Route(List.of(taskTypes), Objects.requireNonNull(mode));
        }

        static Route conversation(TaskType taskType) {
            return new Route(List.of(taskType), null);
        }
    }

    private final String id;
    private final ThinkingLevel shippedDefault;
    private final Route route;

    ThinkingSite(String id, ThinkingLevel shippedDefault, Route route) {
        this.id = id;
        this.shippedDefault = shippedDefault;
        this.route = route;
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

    /** 이 사이트의 작업 유형 — 라우터 호출의 첫 인자. */
    public TaskType taskType() {
        return route.taskTypes().get(0);
    }

    /** 내려가는 순서까지 포함한 작업 유형들(첫째 = {@link #taskType()}). */
    public List<TaskType> taskTypes() {
        return route.taskTypes();
    }

    /** 대화가 고른 라우팅 모드를 따르는가(채팅 답변·검증). */
    public boolean followsConversationRouting() {
        return route.fixedMode() == null;
    }

    /**
     * 이 사이트가 늘 쓰는 라우팅 모드.
     *
     * @throws IllegalStateException 대화의 모드를 따르는 사이트에 부르면 — {@link #routingMode(RoutingMode)} 를 쓴다.
     *         고정 모드인 척 기본값을 돌려주면 PROGRESSIVE·QUALITY_FIRST 대화가 조용히 다른 프로바이더로 간다
     */
    public RoutingMode fixedRoutingMode() {
        if (route.fixedMode() == null) {
            throw new IllegalStateException(name() + " 는 대화의 라우팅 모드를 따른다 — routingMode(conversationMode) 를 쓴다");
        }
        return route.fixedMode();
    }

    /** 이 호출의 라우팅 모드 — 고정 모드가 있으면 그것, 없으면 대화가 고른 모드. */
    public RoutingMode routingMode(RoutingMode conversationMode) {
        return route.fixedMode() != null ? route.fixedMode() : Objects.requireNonNull(conversationMode);
    }

    public static Optional<ThinkingSite> byId(String id) {
        if (id == null) return Optional.empty();
        for (ThinkingSite site : values()) {
            if (site.id.equals(id)) return Optional.of(site);
        }
        return Optional.empty();
    }
}
