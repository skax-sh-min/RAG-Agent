package com.example.ragagent.llm;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 한 수준이 한 프로바이더에서 실제로 무엇으로 나가는가 — 요청 본문에 더할 것과, 그 결과 모델이 들은 말.
 *
 * <p>{@link ThinkingDialect#wire} 가 만들고 {@link ThinkingControlChatModel} 이 요청에 싣는다. 화면(5단계)도 같은
 * 값을 그대로 보여 준다 — 표시와 전송이 같은 객체에서 나와야 둘이 어긋나지 않는다.
 *
 * @param extraBody       본문 최상위에 더할 표준 밖 필드(예: {@code chat_template_kwargs}). 비어 있을 수 있다
 * @param reasoningEffort 표준 필드 {@code reasoning_effort} 의 값. 싣지 않으면 {@code null}
 * @param sent            모델에게 전달된 뜻
 */
public record ThinkingWire(Map<String, Object> extraBody, String reasoningEffort, Sent sent) {

    /** 표준 필드의 본문 이름 — 서버가 거부했을 때 오류 문구에서 찾고, 기억하는 단위다. */
    public static final String REASONING_EFFORT_FIELD = "reasoning_effort";

    /**
     * 모델에게 전달된 뜻. {@code NOTHING} 은 "서버가 정한다"이다 — 생각 제어를 받지 못하는 프로바이더이거나, 받던
     * 필드를 서버가 거부해 빼고 보냈을 때.
     */
    public enum Sent { ON, OFF, NOTHING }

    /** 아무것도 싣지 않는다 — 생각 여부는 서버의 기본값이 정한다. */
    public static final ThinkingWire NOTHING = new ThinkingWire(Map.of(), null, Sent.NOTHING);

    public ThinkingWire {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
        if (sent == null) sent = Sent.NOTHING;
    }

    /** 본문 최상위 필드 이름들 — 거부 판정과 거부 기억의 단위다. */
    public Set<String> fields() {
        Set<String> fields = new LinkedHashSet<>(extraBody.keySet());
        if (reasoningEffort != null) fields.add(REASONING_EFFORT_FIELD);
        return fields;
    }

    /**
     * 서버가 거부한 필드를 뺀 결과. 남는 필드가 없으면 {@link #NOTHING} 이다 — 지금의 dialect 들은 필드를 하나씩만
     * 싣기 때문에, 거부된 프로바이더에서는 그 수준이 통째로 "서버가 정한다"가 된다.
     */
    public ThinkingWire without(Set<String> rejected) {
        if (rejected == null || rejected.isEmpty()) return this;
        Map<String, Object> body = new HashMap<>(extraBody);
        body.keySet().removeAll(rejected);
        String effort = rejected.contains(REASONING_EFFORT_FIELD) ? null : reasoningEffort;
        if (body.isEmpty() && effort == null) return NOTHING;
        return new ThinkingWire(body, effort, sent);
    }

    /** 로그용 한 줄 — {@code chat_template_kwargs={enable_thinking=false}} 처럼. 키 순서를 고정해 로그끼리 비교할 수 있게 한다. */
    public String describe() {
        if (sent == Sent.NOTHING) return "보내지 않음";
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(extraBody).forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(k).append('=').append(v);
        });
        if (reasoningEffort != null) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(REASONING_EFFORT_FIELD).append('=').append(reasoningEffort);
        }
        return sb.toString();
    }
}
