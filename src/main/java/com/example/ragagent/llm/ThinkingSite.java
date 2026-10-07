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
 * <p>3단계(2026-10-02)까지 싣는 곳: 체인을 지나는 호출 전부(사이트 표시) + 체인을 우회하는 채팅 답변 스트리밍
 * ({@code AnswerStreamer} 가 사이트를 받아 요청에 직접 싣는다). 생각 수준이 먹지 않는 LLM 호출은 남아 있지 않다.
 */
public enum ThinkingSite {
    ANSWER_RAG_S("answer-rag-s", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    ANSWER_RAG_N("answer-rag-n", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    ANSWER_RAG_C("answer-rag-c", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    ANSWER_DIRECT_S("answer-direct-s", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    ANSWER_DIRECT_N("answer-direct-n", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    ANSWER_META("answer-meta", Group.ANSWER, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 0),
    EVAL("eval", Group.VERIFY, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 400),
    EVAL_CREATIVE("eval-creative", Group.VERIFY, ThinkingLevel.LOW, Route.conversation(TaskType.TEXT), 400),
    CLASSIFY("classify", Group.QUERY, ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.TEXT), 40),
    CONDENSE("condense", Group.QUERY, ThinkingLevel.OFF, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT), 60),
    /** 작은 모델부터 — 없으면 같은 서버의 큰 모델로 내려간다(§6.21, {@code routeProviderWithFallback}). */
    QUERY_EXPANSION("query-expansion", Group.QUERY, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT, TaskType.LIGHT_TEXT, TaskType.TEXT), 150),
    RERANK("rerank", Group.QUERY, ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.TEXT), 80),
    POST_ANSWER("post-answer", Group.POST, ThinkingLevel.OFF, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT), 250),
    TITLE("title", Group.POST, ThinkingLevel.LOW, Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT), 30),
    SUMMARY("summary", Group.POST, ThinkingLevel.LOW, Route.fixed(RoutingMode.LOCAL_ONLY, TaskType.MICRO_TEXT), 800),
    KEYWORD_CONTEXT("keyword-context", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT), 0),
    MD_CORRECT("md-correct", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_TEXT), 0, true),
    TXT_TO_MD("txt-to-md", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_TEXT), 0, true),
    IMAGE_DESCRIBE("image-describe", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.VISION), 150),
    IMAGE_TYPE("image-type", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.LIGHT_BOTH), 10),
    MD_CORRECT_VISION("md-correct-vision", Group.INDEX, ThinkingLevel.LOW,
            Route.fixed(RoutingMode.LOCAL_ONLY, TaskType.VISION), 150),
    CURATED_SUGGEST("curated-suggest", Group.ADMIN, ThinkingLevel.OFF,
            Route.fixed(RoutingMode.COST_FIRST, TaskType.MICRO_TEXT), 80);

    /**
     * {@code /settings} 미리보기의 표 묶음(PLAN §6.29 ⑦-나 — 채팅 답변 / 검증 / 질문 처리 / 답변 뒤 / 인덱싱 / 관리자).
     * 사이트가 자기 묶음을 선언해야 사이트를 더하고 묶음을 잊는 일이 컴파일 오류로 드러난다.
     */
    public enum Group {
        ANSWER("answer"), VERIFY("verify"), QUERY("query"), POST("post"), INDEX("index"), ADMIN("admin");

        private final String id;

        Group(String id) {
            this.id = id;
        }

        /** 화면 제목 메시지 키의 마지막 마디 — {@code settings.thinking.group.<id>}. */
        public String id() {
            return id;
        }
    }

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
    private final Group group;
    private final ThinkingLevel shippedDefault;
    private final Route route;
    private final boolean rewritesInput;
    private final int expectedOutputTokens;

    ThinkingSite(String id, Group group, ThinkingLevel shippedDefault, Route route, int expectedOutputTokens) {
        this(id, group, shippedDefault, route, expectedOutputTokens, false);
    }

    ThinkingSite(String id, Group group, ThinkingLevel shippedDefault, Route route, int expectedOutputTokens,
                 boolean rewritesInput) {
        this.id = id;
        this.group = group;
        this.shippedDefault = shippedDefault;
        this.route = route;
        this.expectedOutputTokens = expectedOutputTokens;
        this.rewritesInput = rewritesInput;
    }

    /** {@code /settings} 미리보기에서 이 사이트가 속한 표. */
    public Group group() {
        return group;
    }

    /**
     * 이 호출이 <b>생각을 빼고 실제로 내는</b> 출력의 전형 토큰 수 — 미리보기의 "생각 자리"({@code 예약 − 이 값})를 정하는
     * 상수다(PLAN §6.29 ⑦-다). 처음 값은 기존 주석·실측(검증 JSON 은 {@code AnswerService.MAX_EVAL_OUTPUT_TOKENS} 주석의
     * "~400 tokens", 독립화는 한 줄)에서 가져왔고 6단계에서 관측 p95 로 갱신한다.
     *
     * <p><b>0 은 "이 호출의 모양이 정한다"</b>는 뜻이다 — 채팅 답변은 응답 모드의 최소 보장({@code ResponseMode.minChars}),
     * 키워드+맥락은 배치 건수, 재작성은 조각 크기가 필요분이라 상수 하나로 둘 수 없다. 그 계산은 미리보기가 한다.
     */
    public int expectedOutputTokens() {
        return expectedOutputTokens;
    }

    /**
     * 출력이 입력에 묶인 <b>재작성</b>인가(MD 교정·TXT→MD — 입력의 1.5배를 예약한다). 생각 여유의 규칙이 다르다:
     * 창 25% 상한으로 깎지 않고 조각 크기를 줄여 자리를 만든다({@link ThinkingBudget}).
     */
    public boolean rewritesInput() {
        return rewritesInput;
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
        return SETTINGS_KEY_PREFIX + id;
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

    /** {@code llm.thinking.<id>} 형태의 {@code /settings} 오버라이드 키로 사이트를 찾는다 — 모르는 키면 비어 있다. */
    public static Optional<ThinkingSite> bySettingsKey(String key) {
        if (key == null || !key.startsWith(SETTINGS_KEY_PREFIX)) return Optional.empty();
        return byId(key.substring(SETTINGS_KEY_PREFIX.length()));
    }

    /** 오버라이드 키의 접두 — {@link #settingsKey()} 가 이것에 id 를 붙인다. */
    public static final String SETTINGS_KEY_PREFIX = "llm.thinking.";

    public static Optional<ThinkingSite> byId(String id) {
        if (id == null) return Optional.empty();
        for (ThinkingSite site : values()) {
            if (site.id.equals(id)) return Optional.of(site);
        }
        return Optional.empty();
    }
}
