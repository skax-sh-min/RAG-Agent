package com.example.ragagent.model;

import java.util.List;

/**
 * 한 턴의 <b>답변 검증 결과</b> — 저장 형태와 표시 규칙을 함께 갖는다 (PLAN §6.24 Step 4-b).
 *
 * <p>이 레코드가 있는 이유는 <b>렌더러가 셋</b>이기 때문이다: 방금 보낸 메시지를 그리는 HTMX
 * 폴백 프래그먼트({@code fragments/message-assistant.html}), 새로고침 후의 대화 기록
 * ({@code chat.html} 의 자체 루프), 그리고 스트리밍({@code chat-stream.js}). 배지 조건을 세 곳에
 * 풀어 쓰면 반드시 갈라지고, 갈라진 것이 화면에서는 보이지 않는다 — {@code SourceRef.staleBadge()}
 * 가 같은 이유로 만들어졌다. 서버 렌더러 둘은 여기 있는 메서드를 그대로 읽고, JS 는 어쩔 수 없이
 * 한 번 더 구현한다(스트리밍 이벤트는 템플릿을 거치지 않는다) — <b>규칙을 바꾸면 양쪽을 함께
 * 고쳐야 한다</b>.
 *
 * <p><b>{@code conversation_turns.verification} 에 JSON 으로 저장된다.</b> 컬럼 넷이 아니라 blob 인
 * 이유는 {@code retrieval_metrics} 와 같다 — 읽는 곳이 이 표시 하나뿐이고 항상 턴 하나의 검증
 * 결과를 통째로 꺼내며, 스키마가 이 레코드를 따라가야 해서 컬럼으로 고정하면 필드가 하나 늘 때마다
 * 마이그레이션이 된다(실제로 이번 단계에서만 {@code envNote} 와 {@code inventedSymbols} 가 차례로
 * 늘었다). 컬럼이 {@code NULL} 이면 "검증 기록이 없는 턴"이고, 그건 이 기능 이전의 모든 턴과
 * meta/Direct 턴이다 — 그 경우 배지를 아무것도 띄우지 않는 예전 동작 그대로다.
 *
 * <p><b>파싱은 관대해야 한다</b>({@code FAIL_ON_UNKNOWN_PROPERTIES} off) — 이 행들은 자신을 쓴
 * 코드보다 오래 살아남으므로, 필드가 하나 추가되면 과거 기록 전체가 안 읽히게 된다
 * ({@code RetrievalMetricsService} 와 같은 규약).
 *
 * @param grounded        검증 통과 여부. {@code null} = 검증 미실행(S 모드, meta/Direct, 검색 결과 없음)
 * @param generative      통과 배지를 {@code 생성}(파랑)으로 표시해야 하는가 — {@code ResponseMode.generative()}.
 *                        {@code response_mode} 로도 유도할 수 있지만 <b>일부러 함께 굳힌다</b>: 이 값은
 *                        "무엇을 검증했는가"라는 그 턴의 사실이고, 뒤에 모드의 성질 정의가 바뀌어도
 *                        과거 턴의 배지가 따라 움직여서는 안 된다
 * @param evalReason      검증을 통과하지 못한 이유 한 문장. 통과했으면 {@code null}
 * @param envNote         환경에 따라 달라질 수 있는 값 안내. 통과 여부와 무관하게 실릴 수 있다
 * @param inventedSymbols 창의 검증이 "문서에 있는 것처럼 쓰였지만 발췌에 없다"고 지목한 이름들.
 *                        재시도를 걸지 않는 경고 전용 값이라(§6.24 Step 2-d) 통과한 답변에도 붙는다
 * @param budgetNote      컨텍스트 예산 때문에 검색 문서·이전 대화 일부가 프롬프트에서 빠졌다는 안내.
 *                        검증 결과가 아니지만 {@code envNote} 와 같은 이유로 여기 함께 저장한다 —
 *                        <b>새로고침 후에도 남아야 하기 때문</b>이다. 저장하지 않으면 대화를 다시 열었을
 *                        때 출처 10개가 아무 단서 없이 나열되고, 사용자는 모델이 그것을 전부 읽었다고
 *                        믿게 된다(축소는 프롬프트에만 걸리고 출처 목록은 줄지 않는다). 배지 규칙이
 *                        렌더러 셋에 흩어지지 않도록 이 레코드로 모으는 것과 같은 논리다
 * @param condensedQuestion §10.12 — 짧은 후속 질문을 검색용으로 다시 쓴 문장. 재작성이 없었으면
 *                        {@code null}. {@code budgetNote} 와 <b>정확히 같은 이유로</b> 여기 있다:
 *                        검증 결과가 아니지만 새로고침 후에도 남아야 한다. 잘못된 재작성은 사용자에게
 *                        "나쁜 검색어"가 아니라 <b>"엉뚱한 답변"</b> 으로만 보여, 이 줄이 없으면 원인을
 *                        짚을 방법이 없다 — 질문 버블에는 원문이 그대로 떠 있기 때문이다. 화면에는
 *                        {@code ui.retrieval-metrics-enabled} 가 켜진 경우에만 나온다(진단값)
 */
public record VerificationSnapshot(
        Boolean grounded,
        boolean generative,
        String evalReason,
        String envNote,
        List<String> inventedSymbols,
        String budgetNote,
        String condensedQuestion
) {
    public VerificationSnapshot {
        inventedSymbols = inventedSymbols == null ? List.of() : List.copyOf(inventedSymbols);
    }

    /** §10.12 이전 모양 — 재작성이 없는 호출부(테스트·과거 기록 재구성)가 그대로 쓴다
     *  ({@code MemoryRepository.MetricsRow} 와 같은 편의 생성자 규약). */
    public VerificationSnapshot(Boolean grounded, boolean generative, String evalReason,
                                String envNote, List<String> inventedSymbols, String budgetNote) {
        this(grounded, generative, evalReason, envNote, inventedSymbols, budgetNote, null);
    }

    /** 저장할 값이 하나도 없는 턴 — 검증을 돌리지 않았고 안내도 없다. 그런 턴은 아예 저장하지 않는다. */
    public boolean isEmpty() {
        return grounded == null && evalReason == null && envNote == null
                && inventedSymbols.isEmpty() && budgetNote == null && condensedQuestion == null;
    }

    /** 검색어가 실제로 원문과 달랐는가 — 렌더러 셋이 이 값 하나로 줄을 띄운다. */
    public boolean hasCondensedQuestion() {
        return condensedQuestion != null && !condensedQuestion.isBlank();
    }

    /**
     * 통과/미통과 배지 문구. {@code null} 이면 배지를 띄우지 않는다(검증 미실행).
     *
     * <p>통과 배지가 모드에 따라 갈리는 이유는 <b>통과한 검증의 질문 자체가 다르기 때문</b>이다 —
     * 표준 모드의 {@code grounded} 는 "답변이 문서에 근거하는가"를, 창의 모드의 {@code apiGrounded} 는
     * "문서 유래라고 제시한 이름이 실재하는가"를 물었다. 같은 초록 배지를 붙이면 사용자는 뒤엣것을
     * 앞엣것으로 읽는데, 창의 모드에서 가장 비싼 오해다.
     */
    public String verdictLabel() {
        if (grounded == null) return null;
        if (!grounded) return "미검증";
        return generative ? "생성" : "검증됨";
    }

    /** {@link #verdictLabel()} 에 붙일 Bootstrap 색 클래스. */
    public String verdictClass() {
        if (grounded == null) return null;
        if (!grounded) return "bg-warning text-dark";
        return generative ? "bg-primary" : "bg-success";
    }

    /** 배지 툴팁. 미통과는 사유를, 생성은 무엇을 검증했는지 알려준다. 없으면 {@code null}. */
    public String verdictTitle() {
        if (grounded == null) return null;
        if (!grounded) return evalReason;
        return generative
                ? "문서를 재료로 생성된 답변입니다. 문서에 없는 이름을 지어내지 않았는지만 검증했습니다."
                : null;
    }

    public boolean hasInventedSymbols() {
        return !inventedSymbols.isEmpty();
    }

    /** 배지 툴팁과 펼친 목록이 함께 쓰는 표기 — 한 곳에서 만든다. */
    public String inventedSymbolsText() {
        return String.join(", ", inventedSymbols);
    }

    /**
     * 미통과 사유를 배지 툴팁 말고 한 줄로도 보여줘야 하는가. 마우스를 올려봐야 알 수 있으면
     * 모바일에서는 확인할 방법이 없다.
     */
    public boolean showsEvalReasonLine() {
        return grounded != null && !grounded && evalReason != null;
    }
}
