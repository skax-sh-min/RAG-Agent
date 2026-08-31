package com.example.ragagent.model;

import java.util.List;

public record ChatForm(
        String question,
        String threadId,
        String version,
        String routingMode,
        Boolean directMode,
        String tags,
        String responseMode
) {
    public boolean isDirectMode() {
        return Boolean.TRUE.equals(directMode);
    }

    /** Lenient parse of the comma-separated tag input (never throws — empty on blank/invalid). */
    public List<String> selectedTags() {
        return TagUtils.parseTagList(tags);
    }

    /**
     * Lenient parse of the response-mode selector (never throws — {@link ResponseMode#DEFAULT} on
     * blank/unknown), <b>with the Direct-exclusivity guard applied</b> (PLAN §6.24 Step 4-a).
     *
     * <p>검색 결과가 전제인 모드(C)는 Direct(검색 없음)와 함께 성립할 수 없다. 클라이언트가 그
     * 조합의 버튼을 비활성화하지만 <b>그것만으로는 부족하다</b> — 구 L 모드는 서버 가드가 없어
     * 손으로 만든 요청이 그대로 통과했다. 여기서 걸러야 하는 이유는 이 판정이 <b>요청 자체에서만
     * 파생되기 때문</b>이다 — 설정 조회가 필요 없으므로 레코드가 스스로 답할 수 있고, HTMX 폼과
     * SSE 가 이 값 객체를 공유하므로 한 번만 적으면 된다. (설정에 의존하는 가드는 레코드가 답할 수
     * 없어 컨트롤러에 있다 — {@code SettingsService.effectiveResponseMode()} 를 부르는
     * {@code normalizeResponseMode()}/{@code withAvailableResponseMode()}.)
     * {@code AgentGraph} 에도 같은 성질을 묻는 가드가 있지만
     * 그쪽은 그래프 자신의 정합성 보장(널 프롬프트 키 방어)이고, 이쪽은 저장되는 값까지 N으로
     * 맞춘다 — 안 그러면 화면·DB의 {@code response_mode} 는 C인데 실제로는 N으로 답한 턴이 된다.
     */
    public ResponseMode responseModeOrDefault() {
        ResponseMode mode = ResponseMode.parse(responseMode);
        return (isDirectMode() && !mode.allowsDirect()) ? ResponseMode.DEFAULT : mode;
    }
}
