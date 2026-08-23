package com.example.ragagent.model;

/**
 * 답변의 성격을 정하는 per-message 모드 — 채팅 입력창에서 고른다 (기본 {@link #N}).
 *
 * <p><b>PLAN.md §6.24</b> — 예전 S/M/L은 "길이 축" 하나였는데, M과 L이 실제로는 구분되지
 * 않았다: 채팅의 유일한 전송 경로인 스트리밍에는 {@code maxTokens}가 붙지 않고, 검색 재료
 * (topK 청크)가 두 모드에서 동일하며, 남은 차이인 프롬프트의 "약 N자" 문구는 긴 출력에서
 * 모델을 움직이지 못했다(실측 M 3,047자 / L 3,187자). 그래서 L을 제거하고 M을 N으로 개명해
 * <b>S(요약) · N(표준)</b>만 남겼고, 앞으로 추가될 C(응용)는 길이가 아니라 <b>근거 엄격도</b>
 * 축에서 갈린다.
 *
 * <p>모드가 늘어날 때 분기가 흩어지지 않도록, 각 모드는 "무엇을 하는가"를 값이 아니라
 * <b>성질</b>로 노출한다({@link #allowsDirect()} / {@link #skipsVerification()} /
 * {@link #retrievalBoost()} / {@link #allowsCuration()}). 호출부는 {@code == ResponseMode.S}
 * 같은 값 비교 대신 이 메서드들을 물어야, 다음 모드를 추가할 때 고칠 곳이 이 파일 한 곳으로
 * 모인다.
 *
 * <p><b>예산</b>은 비율({@link #tokenRatio()})과 글자수 바닥({@link #minChars()}) 중 큰 쪽을
 * 고르되, <b>운영자가 설정한 {@code app.llm.max-tokens}를 절대 넘지 않는다</b>
 * ({@link #maxTokens(int)}의 {@code Math.min}). 예전에는 이 상한이 없어 기본 설정(6,000)에서
 * L이 10,000을 요청했다. 한국어는 1토큰 ≈ 1글자라 이 값은 변환 없이 그대로 쓰인다. 다만 이
 * 상한이 걸리는 곳은 <b>블로킹 호출뿐</b>이고, 스트리밍 답변은 토큰 캡이 없다(토큰 단위 UX).
 * 답변의 절대 상한은 모드와 무관하게 {@code AnswerService.MAX_ANSWER_LEN}(20,000자)이다.
 */
public enum ResponseMode {

    /** 요약형 — 짧게, 요약 섹션 하나로. 검증(eval + CRITIC)을 건너뛴다. */
    S(0.15, 2_000,
      "prompt.answer.system.s", "prompt.direct.system.s", null,
      0, true),

    /** 표준형 (구 M) — 문서에 충실하게, 구체적이고 자세하게. 기본값. */
    N(0.40, 5_000,
      "prompt.answer.system.n", "prompt.direct.system.n", "prompt.answer.eval",
      0, true);

    /** 클라이언트가 아무것도/모르는 값을 보냈을 때 쓰는 모드. 옛 {@code "M"}·{@code "L"} 기록도 여기로 흡수된다. */
    public static final ResponseMode DEFAULT = N;

    private final double tokenRatio;
    private final int minChars;
    private final String answerSystemPromptKey;
    private final String directSystemPromptKey;
    private final String evalPromptKey;
    private final int retrievalBoost;
    private final boolean curatable;

    ResponseMode(double tokenRatio, int minChars,
                 String answerSystemPromptKey, String directSystemPromptKey, String evalPromptKey,
                 int retrievalBoost, boolean curatable) {
        this.tokenRatio = tokenRatio;
        this.minChars = minChars;
        this.answerSystemPromptKey = answerSystemPromptKey;
        this.directSystemPromptKey = directSystemPromptKey;
        this.evalPromptKey = evalPromptKey;
        this.retrievalBoost = retrievalBoost;
        this.curatable = curatable;
    }

    /** {@code app.llm.max-tokens} 중 이 모드가 쓸 비율. */
    public double tokenRatio() { return tokenRatio; }

    /** 설정된 max-tokens가 작아도 이 모드가 확보하려는 최소 글자/토큰 수. */
    public int minChars() { return minChars; }

    /**
     * 이 모드의 per-call {@code maxTokens}: 비율분과 {@link #minChars()} 중 큰 값을 취하되
     * <b>{@code configured}를 넘지 않는다</b>. 작은 설정값이 S를 쓸모없는 토막으로 만들지
     * 않도록 256에서 한 번 바닥을 받치지만, 그 바닥조차 상한을 넘지는 못한다.
     *
     * @param configured {@code app.llm.max-tokens}. 0 이하면 0을 반환해 호출부가 프로바이더
     *                   기본값을 그대로 쓰게 한다.
     */
    public int maxTokens(int configured) {
        if (configured <= 0) return 0; // 호출부는 0을 "프로바이더 기본값 유지"로 해석한다
        int ratioTokens = (int) Math.round(configured * tokenRatio);
        return Math.min(configured, Math.max(256, Math.max(ratioTokens, minChars)));
    }

    /**
     * RAG 답변의 전용 시스템 프롬프트 키.
     *
     * <p>모드마다 프롬프트를 통째로 바꾸는 것이 핵심이다 — 공용 프롬프트에 "위 형식을 쓰지
     * 마세요" 같은 부정 지시를 얹어 뒤집는 방식은 S에서 이미 실패했다(모델이 5섹션을 그대로
     * 생성 → 사후 절단 → 화면과 DB 불일치). 없는 형식은 따라갈 수 없다.
     *
     * <p><b>번들 키는 PLAN §6.24 Step 1-a에서 추가된다</b> — 그 전까지 이 값을 실제로
     * {@code MessageSource}에 넘기면 {@code NoSuchMessageException}이 난다.
     */
    public String answerSystemPromptKey() { return answerSystemPromptKey; }

    /**
     * Direct(RAG 없이 직접 질문) 답변의 전용 시스템 프롬프트 키. {@code null}이면 그 모드는
     * Direct에서 쓸 수 없다는 뜻이다({@link #allowsDirect()}). 번들 키 추가 시점은
     * {@link #answerSystemPromptKey()}와 같다(Step 1-b).
     */
    public String directSystemPromptKey() { return directSystemPromptKey; }

    /**
     * 이 모드가 쓸 답변 검증 프롬프트 키. {@code null}이면 검증 자체를 하지 않는다
     * ({@link #skipsVerification()}).
     */
    public String evalPromptKey() { return evalPromptKey; }

    /** 이 모드가 검색 결과를 몇 개 더 받을지(0 = 기본 topK 그대로). */
    public int retrievalBoost() { return retrievalBoost; }

    /** 이 모드의 답변을 좋아요 기반 큐레이션 지식(§10.10)으로 승격해도 되는가. */
    public boolean allowsCuration() { return curatable; }

    /** Direct 모드(검색 없음)에서 고를 수 있는가 — 검색 결과가 전제인 모드는 false. */
    public boolean allowsDirect() { return directSystemPromptKey != null; }

    /** 충분성/근거 검증(ANSWER의 eval 호출 + CRITIC 노드)을 통째로 건너뛰는가. */
    public boolean skipsVerification() { return evalPromptKey == null; }

    /** 모드별 답변 스타일 지시문의 i18n 키 ({@code messages*.properties}). */
    public String promptKey() {
        return "prompt.answer.style." + name().toLowerCase();
    }

    /**
     * 관대한 파싱 — {@code null}/공백/모르는 값 모두 {@link #DEFAULT}로 떨어지고 예외를 던지지
     * 않는다. 이 관대함이 <b>M→N 개명의 마이그레이션 비용을 0으로 만든다</b>:
     * {@code conversation_turns.response_mode}에 남은 {@code "M"}·{@code "L"} 문자열과, 브라우저
     * {@code localStorage}에 남은 옛 선택값이 모두 조용히 N으로 흡수된다.
     */
    public static ResponseMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
