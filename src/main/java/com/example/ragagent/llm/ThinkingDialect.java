package com.example.ragagent.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 생각 수준을 그 서버에 <b>어떤 필드로</b> 말하는가 — 프로바이더마다 하나({@code app.llm.providers[n].thinking-dialect}).
 *
 * <p>수준({@link ThinkingLevel})은 관리자의 의도이고 이것은 그 서버가 알아듣는 말이다. 둘을 나눈 이유는 같은 "낮게"가
 * 서버마다 다른 필드가 되기 때문이다(PLAN §6.29 ③):
 * <ul>
 *   <li>{@link #TEMPLATE_KWARGS} — llama.cpp {@code llama-server}·vLLM·SGLang 이 채팅 템플릿에 넘기는
 *       {@code chat_template_kwargs.enable_thinking}. 켬/끔만 있어 <b>낮게·중간·높게가 같은 값으로 접힌다.</b></li>
 *   <li>{@link #TEMPLATE_KWARGS_EFFORT} — gpt-oss 처럼 템플릿이 {@code reasoning_effort} 를 받는 모델. 끌 수 없어
 *       끔이 {@code low} 로 접힌다.</li>
 *   <li>{@link #OPENAI_EFFORT} — 표준 필드 {@code reasoning_effort}(OpenAI 추론 모델, Gemini 호환 경로).</li>
 *   <li>{@link #NONE} — 아무것도 싣지 않는다. <b>"서버가 정한다"가 남는 유일한 자리</b>다.</li>
 * </ul>
 *
 * <p>{@link #AUTO}(지정하지 않았을 때)는 현행 규칙 그대로다: LOCAL 역할이면 {@code TEMPLATE_KWARGS}, 아니면
 * {@code NONE}. 원격 프로바이더(OpenAI·Gemini 호환 엔드포인트)는 모르는 필드를 400 으로 거부하고 라우터는 그걸
 * 프로바이더 실패로 받아 차단하므로, 원격에 표준 밖 필드를 싣는 것은 운영자가 명시할 때뿐이다.
 */
public enum ThinkingDialect {
    AUTO, NONE, TEMPLATE_KWARGS, TEMPLATE_KWARGS_EFFORT, OPENAI_EFFORT;

    /** 템플릿 인자가 실리는 본문 최상위 필드 — 서버가 거부할 때 오류 문구에서도 이 이름을 찾는다. */
    public static final String TEMPLATE_KWARGS_FIELD = "chat_template_kwargs";

    /** 설정값을 실제로 쓸 dialect 로 푼다 — {@code AUTO}·{@code null} 은 여기서만 풀리고, 결과는 {@code AUTO} 가 아니다. */
    public static ThinkingDialect resolve(ThinkingDialect configured, boolean localServer) {
        if (configured == null || configured == AUTO) return localServer ? TEMPLATE_KWARGS : NONE;
        return configured;
    }

    /** {@code auto}·{@code template-kwargs}·{@code TEMPLATE_KWARGS} 를 모두 받는다. 모르는 값이면 비어 있다. */
    public static Optional<ThinkingDialect> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String v = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ThinkingDialect d : values()) {
            if (d.name().equals(v)) return Optional.of(d);
        }
        return Optional.empty();
    }

    /** 설정 파일에 적는 형태 — {@code template-kwargs}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 이 서버에 그 수준을 무엇으로 보내는가. 화면 표시(5단계)와 실제 전송이 같은 함수를 쓴다.
     *
     * @throws IllegalStateException {@code AUTO} 에 부르면 — {@link #resolve} 를 먼저 거쳐야 한다. 조용히 아무것도
     *         안 싣는 쪽으로 떨어지면 LOCAL 서버의 "생각 끄기"가 아무 오류 없이 사라진다.
     */
    public ThinkingWire wire(ThinkingLevel level) {
        return switch (this) {
            case AUTO -> throw new IllegalStateException("AUTO 는 resolve() 로 먼저 푼다");
            case NONE -> ThinkingWire.NOTHING;
            case TEMPLATE_KWARGS -> new ThinkingWire(
                    Map.of(TEMPLATE_KWARGS_FIELD, Map.of("enable_thinking", level != ThinkingLevel.OFF)),
                    null,
                    level == ThinkingLevel.OFF ? ThinkingWire.Sent.OFF : ThinkingWire.Sent.ON);
            case TEMPLATE_KWARGS_EFFORT -> new ThinkingWire(
                    Map.of(TEMPLATE_KWARGS_FIELD, Map.of("reasoning_effort", effort(level))),
                    null,
                    ThinkingWire.Sent.ON);   // 끌 수 없다 — 끔도 low 로 생각한다
            case OPENAI_EFFORT -> new ThinkingWire(
                    Map.of(),
                    level == ThinkingLevel.OFF ? "none" : effort(level),
                    level == ThinkingLevel.OFF ? ThinkingWire.Sent.OFF : ThinkingWire.Sent.ON);
        };
    }

    /**
     * 이 서버가 구분해서 알아듣는 수준의 폭 — {@code /settings} 프로바이더 표의 "생각 제어" 칸이 읽는다.
     * {@link #wire} 가 실제로 만드는 값과 어긋나지 않게 {@code ThinkingDialectTest} 가 둘을 함께 본다.
     */
    public enum Support {
        /** 아무것도 싣지 않는다 — 서버가 정한다. */
        NONE,
        /** 켬/끔만 — 낮게·중간·높게가 같은 값으로 접힌다. */
        ON_OFF,
        /** 낮게·중간·높게를 구분하고 끔은 {@code none}. */
        EFFORT,
        /** 낮게·중간·높게를 구분하지만 끌 수 없다 — 끔도 {@code low}. */
        EFFORT_NO_OFF
    }

    /** @throws IllegalStateException {@code AUTO} 에 부르면 — {@link #resolve} 를 먼저 거쳐야 한다({@link #wire} 와 같은 이유) */
    public Support support() {
        return switch (this) {
            case AUTO -> throw new IllegalStateException("AUTO 는 resolve() 로 먼저 푼다");
            case NONE -> Support.NONE;
            case TEMPLATE_KWARGS -> Support.ON_OFF;
            case TEMPLATE_KWARGS_EFFORT -> Support.EFFORT_NO_OFF;
            case OPENAI_EFFORT -> Support.EFFORT;
        };
    }

    /** 이 dialect 가 싣는 본문 필드 — 화면에 그대로 적는다. 싣지 않으면 {@code null}. */
    public String field() {
        return switch (this) {
            case AUTO -> throw new IllegalStateException("AUTO 는 resolve() 로 먼저 푼다");
            case NONE -> null;
            case TEMPLATE_KWARGS -> TEMPLATE_KWARGS_FIELD + ".enable_thinking";
            case TEMPLATE_KWARGS_EFFORT -> TEMPLATE_KWARGS_FIELD + ".reasoning_effort";
            case OPENAI_EFFORT -> ThinkingWire.REASONING_EFFORT_FIELD;
        };
    }

    private static String effort(ThinkingLevel level) {
        return switch (level) {
            case OFF, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
        };
    }
}
