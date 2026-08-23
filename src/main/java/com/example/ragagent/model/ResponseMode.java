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
 * {@link #summaryOnly()} / {@link #retrievalBoost()} / {@link #allowsCuration()}).
 * <b>호출부에 모드 값 비교를 두지 않는 것이 이 enum의 계약이다</b> — 그래야 다음 모드를
 * 추가할 때 고칠 곳이 이 파일 한 곳으로 모인다. 새 분기가 필요하면 값을 비교하지 말고
 * 여기에 성질을 하나 더 만들어라. 성질끼리 값이 우연히 같더라도(예: 지금 S에서
 * {@code skipsVerification}과 {@code summaryOnly}가 둘 다 참) 묻는 질문이 다르면 합치지
 * 않는다 — 합쳐두면 뒤에 붙는 모드가 한쪽을 조용히 잘못 상속한다.
 *
 * <p><b>예산</b>은 비율({@link #tokenRatio()})과 글자수 바닥({@link #minChars()}) 중 큰 쪽을
 * 고르되, <b>운영자가 설정한 {@code app.llm.max-tokens}를 절대 넘지 않는다</b>
 * ({@link #maxTokens(int)}의 {@code Math.min}). 예전에는 이 상한이 없어 기본 설정(6,000)에서
 * L이 10,000을 요청했다. 한국어는 1토큰 ≈ 1글자라 이 값은 변환 없이 그대로 쓰인다. 다만 이
 * 상한이 걸리는 곳은 <b>블로킹 호출뿐</b>이고, 스트리밍 답변은 토큰 캡이 없다(토큰 단위 UX).
 * 답변의 절대 상한은 모드와 무관하게 {@code AnswerService.MAX_ANSWER_LEN}(20,000자)이다.
 */
public enum ResponseMode {

    /**
     * 요약형 — 짧게, 요약 섹션 하나로. 검증(eval + CRITIC)을 건너뛴다.
     *
     * <p><b>큐레이션 대상이 아니다</b>({@code curatable=false}). S 답변은 전체가 {@code "## 요약"}
     * 한 섹션이라 큐레이션 임베딩 입력에서 구조 섹션을 걷어내면 <b>본문이 통째로 사라진다</b>
     * ({@code CuratedTextUtils.stripStructuralSections} 는 요약·참고 섹션을 제거하도록 만들어졌고,
     * N 답변에서는 {@code ## 상세 설명}이 남지만 S에는 남을 것이 없다). 그렇게 만들어진 벡터는
     * 질문만 담고 답변 내용을 하나도 담지 못한다. 게다가 S는 애초에 축약된 답변이라 공유 지식으로
     * 승격할 대상도 아니다 — 그래서 좋아요는 S 턴에서 아무 일도 하지 않는다(싫어요는 그대로
     * 동작한다: 다음 대화 컨텍스트에서 제외).
     */
    S(0.15, 2_000,
      "prompt.answer.system.s", "prompt.direct.system.s", null,
      0, false, true),

    /**
     * 표준형 (구 M) — 문서에 충실하게, 구체적이고 자세하게. 기본값.
     *
     * <p>비율은 0.70 — 구 L의 몫을 물려받았다. L이 사라져 <b>요약이 아닌 유일한 모드</b>가 됐으므로
     * 상한을 나눠 가질 상대가 없다. 전환점은 {@code max-tokens} 7,143(= 5,000/0.70)이라 실사용
     * 설정(12,000·16,000)에서는 비율항이 이긴다. 다만 이 예산이 걸리는 곳은 블로킹 호출뿐이라
     * (스트리밍은 캡 없음) 실제 답변 길이가 그만큼 늘어난다는 뜻은 아니다 — 안전판이다.
     */
    N(0.70, 5_000,
      "prompt.answer.system.n", "prompt.direct.system.n", "prompt.answer.eval",
      0, true, false);

    /** 클라이언트가 아무것도/모르는 값을 보냈을 때 쓰는 모드. 옛 {@code "M"}·{@code "L"} 기록도 여기로 흡수된다. */
    public static final ResponseMode DEFAULT = N;

    private final double tokenRatio;
    private final int minChars;
    private final String answerSystemPromptKey;
    private final String directSystemPromptKey;
    private final String evalPromptKey;
    private final int retrievalBoost;
    private final boolean curatable;
    private final boolean summaryOnly;

    ResponseMode(double tokenRatio, int minChars,
                 String answerSystemPromptKey, String directSystemPromptKey, String evalPromptKey,
                 int retrievalBoost, boolean curatable, boolean summaryOnly) {
        this.tokenRatio = tokenRatio;
        this.minChars = minChars;
        this.answerSystemPromptKey = answerSystemPromptKey;
        this.directSystemPromptKey = directSystemPromptKey;
        this.evalPromptKey = evalPromptKey;
        this.retrievalBoost = retrievalBoost;
        this.curatable = curatable;
        this.summaryOnly = summaryOnly;
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
     * <p>S는 5섹션 헤더 이름을 <b>언급조차 하지 않는다</b> — 금지하려고 나열하는 것만으로도
     * 성능이 낮은 로컬 모델은 그 목록을 따라간다.
     */
    public String answerSystemPromptKey() { return answerSystemPromptKey; }

    /**
     * Direct(RAG 없이 직접 질문) 답변의 전용 시스템 프롬프트 키. {@code null}이면 그 모드는
     * Direct에서 쓸 수 없다는 뜻이다({@link #allowsDirect()}). meta(인사/잡담) 답변은 모드와
     * 무관하게 {@code prompt.direct.meta.system}을 쓰므로 이 키를 거치지 않는다.
     */
    public String directSystemPromptKey() { return directSystemPromptKey; }

    /**
     * 이 모드가 쓸 답변 검증 프롬프트 키. {@code null}이면 검증 자체를 하지 않는다
     * ({@link #skipsVerification()}).
     */
    public String evalPromptKey() { return evalPromptKey; }

    /** 이 모드가 검색 결과를 몇 개 더 받을지(0 = 기본 topK 그대로). */
    public int retrievalBoost() { return retrievalBoost; }

    /**
     * 이 모드의 답변을 좋아요 기반 큐레이션 지식(§10.10)으로 승격해도 되는가.
     *
     * <p>{@code false}면 {@code CuratedQaService.onLike()}가 즉시 반환한다 — {@code curated_qa} 행도
     * 만들지 않으므로 좋아요가 사실상 무동작이 된다(LIKE 피드백의 유일한 소비자가 큐레이션이다).
     * 싫어요는 무관하게 계속 동작한다.
     */
    public boolean allowsCuration() { return curatable; }

    /** Direct 모드(검색 없음)에서 고를 수 있는가 — 검색 결과가 전제인 모드는 false. */
    public boolean allowsDirect() { return directSystemPromptKey != null; }

    /** 충분성/근거 검증(ANSWER의 eval 호출 + CRITIC 노드)을 통째로 건너뛰는가. */
    public boolean skipsVerification() { return evalPromptKey == null; }

    /**
     * 답변을 요약 섹션 하나로 잘라내는 후처리를 적용하는가.
     *
     * <p>{@link #skipsVerification()}과 S에서 값이 같지만 <b>다른 성질</b>이라 별도 플래그로
     * 둔다 — 하나는 "검증할 가치가 있을 만큼 긴 답변인가", 다른 하나는 "출력 형태가 요약
     * 하나로 고정인가"이고, 뒤에 붙는 모드에서 둘은 갈릴 수 있다. 두 질문을 한 플래그로
     * 합치면 그때 한쪽을 조용히 잘못 상속한다.
     *
     * <p>이 후처리는 PLAN §6.24 Step 1-c에서 <b>조건부 가드</b>로 강등된다 — 전용 시스템
     * 프롬프트가 생기면 모델이 애초에 요약만 쓰므로, 후처리는 그게 실패했을 때만 도는
     * 안전망이자 프롬프트 효과의 측정 수단이 된다.
     */
    public boolean summaryOnly() { return summaryOnly; }

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
