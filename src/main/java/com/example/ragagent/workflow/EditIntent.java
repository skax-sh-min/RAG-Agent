package com.example.ragagent.workflow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 자연어 지식 수정의 해석된 의도(AGENT_MCP_DESIGN §10.3·§10.6 ①) — "A 기능은 더 이상 사용되지 않습니다" → {@code deprecate}, 대상 "A 기능".
 *
 * <p><b>모델이 지어내지 못하게 한다.</b> LLM 이 해석한 경우 {@code subject}·{@code content}·{@code replacement} 는 지시문의 부분 문자열이어야 한다(공백
 * 정규화, 대소문자 무시) — 대상이나 넣을 문장을 모델이 만들어 넣으면 거절한다. 별칭은 받지 않는다. 사람(폼·MCP)이 직접 고른 값은 이 검사를 하지 않는다.
 *
 * @param type        {@code deprecate} · {@code remove} · {@code add_note} · {@code revise} · {@code replace}
 * @param subject     대상(기능·함수·용어) — {@code replace} 외에는 필수
 * @param content     넣을 주의 사항({@code add_note}) · 새 설명({@code revise})
 * @param replacement 사용 중단의 대안("대신 X") — 선택
 * @param from        {@code replace} 의 바꿀 말
 * @param to          {@code replace} 의 새 말
 */
public record EditIntent(String type, String subject, String content, String replacement, String from, String to) {

    public static final String DEPRECATE = "deprecate";
    public static final String REMOVE = "remove";
    public static final String ADD_NOTE = "add_note";
    public static final String REVISE = "revise";
    public static final String REPLACE = "replace";
    public static final Set<String> TYPES = Set.of(DEPRECATE, REMOVE, ADD_NOTE, REVISE, REPLACE);

    static final int MAX_SUBJECT_CHARS = 200;
    static final int MAX_CONTENT_CHARS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EditIntent {
        type = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        subject = blankToNull(subject);
        content = blankToNull(content);
        replacement = blankToNull(replacement);
        from = blankToNull(from);
        to = blankToNull(to);
    }

    /**
     * <b>지시문 해석으로는 받지 않는 유형</b> — 원문을 지우거나 바꾸는 둘. 6c 사전 평가({@code documents/KNOWLEDGE_EDIT_EVALUATION.md}
     * §1, 판단표 둘째 줄 — 2026-11-20)에서 사람 수용률이 {@code remove} 21% · {@code revise} 44% 로 기준(70%)에 한참 못 미쳤다. 해석 자체는 맞았다 —
     * 모자란 것은 판정 precision 과 편집 품질이다. 그래서 채팅 되묻기 · {@code /admin} 의 "자동" · 신고 폼의 "자동" · MCP 의 지시문만 요청에서는 거절하고,
     * 관리자가 <b>유형을 직접 고른 폼</b>과 외부 에이전트의 청크 편집 제안(6b {@code proposeChunkEdit})으로만 받는다. 해석 프롬프트는 바꾸지 않는다 —
     * 지우기 요청을 다른 유형으로 잘못 읽게 하는 것보다 지우기로 읽고 거절하며 길을 알려 주는 편이 낫다.
     */
    public static final Set<String> FORM_ONLY = Set.of(REMOVE, REVISE);

    /** 이 유형은 지시문 해석으로는 받지 않는다({@link #FORM_ONLY}). */
    public boolean formOnly() {
        return FORM_ONLY.contains(type);
    }

    /** 해석으로 받지 않는 유형을 거절할 때의 사유 — 작업 실패 사유·MCP 오류로 그대로 보인다. */
    public static String formOnlyReason(String type) {
        String label = REMOVE.equals(type) ? "내용 삭제" : "설명 수정";
        return "「" + label + "」은(는) 지시문 해석으로 받지 않습니다 — 원문을 지우거나 바꾸는 수정이라 /admin/change-jobs 에서 수정 유형을 직접 고르거나,"
                + " 외부 에이전트라면 findChunks·proposeChunkEdit 로 제안하세요";
    }

    public boolean isReplace() {
        return REPLACE.equals(type);
    }

    /**
     * 모양 검사 — 유형이 알려진 것이고 필수 값이 있으며 길이가 맞는가. 위반 사유 또는 {@code null}.
     */
    public String shapeProblem() {
        if (!TYPES.contains(type)) return "수정 유형을 정하지 못했습니다";
        if (isReplace()) {
            if (from == null || to == null) return "바꿀 말과 새 말이 필요합니다";
            if (from.equals(to)) return "바꿀 말과 새 말이 같습니다";
            if (from.length() > MAX_SUBJECT_CHARS || to.length() > MAX_SUBJECT_CHARS) return "바꿀 말이 너무 깁니다";
            return null;
        }
        if (subject == null) return "대상(기능·함수 이름)을 정하지 못했습니다";
        if (subject.length() > MAX_SUBJECT_CHARS) return "대상이 너무 깁니다(" + MAX_SUBJECT_CHARS + "자 이하)";
        if ((ADD_NOTE.equals(type) || REVISE.equals(type)) && content == null) {
            return ADD_NOTE.equals(type) ? "넣을 주의 사항 문장이 필요합니다" : "새 설명이 필요합니다";
        }
        if (content != null && content.length() > MAX_CONTENT_CHARS) return "문장이 너무 깁니다(" + MAX_CONTENT_CHARS + "자 이하)";
        if (replacement != null && replacement.length() > MAX_SUBJECT_CHARS) return "대안이 너무 깁니다";
        return null;
    }

    /**
     * LLM 이 해석한 의도의 검사 — 모양 검사에 더해, 대상·문장·대안·바꿀 말이 <b>지시문 안에 있는 말</b>이어야 한다. 위반 사유 또는 {@code null}.
     */
    public String groundingProblem(String instruction) {
        String shape = shapeProblem();
        if (shape != null) return shape;
        String hay = norm(instruction);
        for (String v : new String[]{subject, content, replacement, from, to}) {
            if (v != null && !hay.contains(norm(v))) return "해석한 값이 지시문에 없습니다: " + v;
        }
        return null;
    }

    /**
     * 의도 해석 호출의 JSON 응답을 읽는다 — 첫 {@code {} ~ 마지막 {@code }}. 못 읽으면 {@link IllegalArgumentException}.
     */
    public static EditIntent parse(String response) {
        int a = response == null ? -1 : response.indexOf('{');
        int b = response == null ? -1 : response.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("지시문을 해석하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요");
        try {
            JsonNode j = MAPPER.readTree(response.substring(a, b + 1));
            return new EditIntent(j.path("type").asString(""), j.path("subject").asString(""), j.path("content").asString(""),
                    j.path("replacement").asString(""), j.path("from").asString(""), j.path("to").asString(""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("지시문을 해석하지 못했습니다 — 화면에서 유형과 대상을 직접 고르세요");
        }
    }

    /** {@code change_job.intent_json} 에 남길 모양. */
    public String toJson(String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("type", type);
        if (subject != null) m.put("subject", subject);
        if (content != null) m.put("content", content);
        if (replacement != null) m.put("replacement", replacement);
        if (from != null) m.put("from", from);
        if (to != null) m.put("to", to);
        return MAPPER.writeValueAsString(m);
    }

    /** {@link #toJson} 의 역 — 못 읽으면 {@code null}. */
    public static EditIntent fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode j = MAPPER.readTree(json);
            return new EditIntent(j.path("type").asString(""), j.path("subject").asString(""), j.path("content").asString(""),
                    j.path("replacement").asString(""), j.path("from").asString(""), j.path("to").asString(""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
