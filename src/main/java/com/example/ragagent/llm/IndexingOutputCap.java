package com.example.ragagent.llm;

/**
 * 인덱싱/백그라운드 호출의 <b>출력 예약</b>을 그 작업이 실제로 낼 만한 크기로 좁힌다.
 *
 * <p><b>왜 필요한가.</b> {@code max_tokens} 는 상한이 아니라 <b>예약</b>이다 — 서버가
 * {@code 프롬프트 + max_tokens ≤ n_ctx} 를 검사하므로, 쓰지도 않을 큰 값을 실어 보내면 그만큼
 * 입력 자리가 사라진다. 그런데 인덱싱 호출들은 per-call 옵션에 온도만 실어 왔고, 그 결과
 * 프로바이더 빈에 구워진 {@code app.llm.max-tokens} 전체(기본 10,000)가 출력으로 예약됐다.
 *
 * <p>실제 관측된 사고가 이것이다: 창 20,480 · {@code max-tokens=10000} 으로 MD 교정을 돌리면
 * <b>창의 절반이 예약</b>인데, 같은 값이 교정 섹션 크기까지 정하므로(4,750자) 입력과 예약이 함께
 * 커져 양쪽에서 창을 밀어붙였다. 재작성 작업의 출력은 입력에 묶여 있어 10,000 토큰을 쓸 일이
 * 애초에 없다.
 *
 * <p>{@code AnswerService} 의 검증 호출이 스스로 조이는 것과 <b>같은 논리, 같은 자리</b>다 —
 * 거기서도 "JSON 몇 필드짜리 응답이 completion 예산 전체를 예약하면 좁은 컨텍스트에서 n_ctx 를
 * 넘기는 것은 근거가 아니라 그 예약"이라는 것이 이유였다.
 *
 * <p><b>모든 값이 {@code app.llm.max-tokens} 에서 나온다.</b> 이 앱은 그 프로퍼티를 "LLM 출력
 * 크기"의 단일 진실 소스로 삼고 있고(블로킹 상한 · 대화 이력 예산 · MD 교정 섹션 크기가 모두
 * 거기서 파생된다), 여기만 독립된 절대 상수를 두면 운영자가 그 값을 내려도 인덱싱 예약만 따라오지
 * 않는다. 그래서 고정 크기 출력은 <b>비율</b>로, 재작성 출력은 <b>입력 추정 × 여유</b>로 잡되 둘 다
 * 설정값에서 잘린다. 절대값은 {@link #MIN_OUTPUT_TOKENS} 하나뿐이며, 이는
 * {@code ResponseMode.maxTokens()} 가 256 에서 바닥을 받치는 것과 같은 성격이다 — 비율이 아무리
 * 작아도 응답이 들어갈 자리는 있어야 한다.
 *
 * <p><b>내리기만 한다.</b> 계산값이 설정된 상한을 넘으면 상한을 쓰고, {@code configuredMax} 가 0
 * 이하면 0 을 돌려 호출부가 프로바이더 기본값을 그대로 쓰게 한다({@code ResponseMode.maxTokens}
 * 와 같은 규약).
 *
 * <p><b>너무 조이면 안 되는 이유</b>도 분명하다. 재작성 응답이 잘리면 {@code MarkdownCorrectionService}
 * 의 마커·펜스 검사에 걸려 그 섹션이 <b>원문 그대로</b> 남는다(우아한 폴백이지만 교정은 조용히
 * 사라진다). 그래서 추정치에 여유를 곱하고, 추정 자체도 한글을 글자당 1토큰으로 세는
 * {@link TokenEstimator}(대체로 과대평가)를 쓴다 — 두 겹 모두 안전한 방향이다.
 */
public final class IndexingOutputCap {

    private IndexingOutputCap() {}

    /**
     * 비율이 아무리 작아도 이만큼은 준다. {@code ResponseMode.maxTokens()} 의 256 바닥과 같은
     * 성격이지만 조금 더 높다 — 이쪽 응답은 한 문장이 아니라 키워드 목록·설명 문단이다.
     */
    public static final int MIN_OUTPUT_TOKENS = 512;

    /**
     * 재작성 출력이 입력보다 커질 수 있는 여지(형식 보정·빈 줄·언어 태그 추가).
     *
     * <p>{@code PromptBudget.rewriteInputChars()} 가 같은 값을 쓴다 — 그쪽은 "본문 S 와 그 예약
     * 1.5S 가 함께 창에 들어가야 한다"를 풀어 입력 상한을 내므로, 두 값이 갈라지면 예약과 입력이
     * 서로 다른 비율을 믿게 된다.
     */
    static final int REWRITE_HEADROOM_PERCENT = 150;

    /**
     * 재작성 작업이 아주 짧은 조각을 받았을 때의 바닥 — {@code max-tokens} 의 이 비율.
     *
     * <p>입력 추정만으로 잡으면 200자짜리 꼬리 섹션에 300 토큰이 배정되는데, 형식 교정이 표나 코드
     * 펜스를 복원하면서 그보다 커지는 경우가 있다. 절대값 대신 비율로 둔 이유는 위 클래스 주석과 같다.
     */
    private static final double REWRITE_FLOOR_RATIO = 0.10;

    /**
     * <b>재작성</b> 작업 — 출력이 입력 크기에 묶여 있다(MD 형식 교정, txt→md 구조화).
     *
     * @param input         LLM 에 보내는 본문(프롬프트 지시문 제외 — 그건 입력이지 출력이 아니다)
     * @param configuredMax {@code app.llm.max-tokens}
     */
    public static int forRewrite(String input, int configuredMax) {
        if (configuredMax <= 0) return 0;
        long fromInput = TokenEstimator.estimate(input) * REWRITE_HEADROOM_PERCENT / 100;
        long floor = Math.round(configuredMax * REWRITE_FLOOR_RATIO);
        return clamp(Math.max(fromInput, floor), configuredMax);
    }

    /**
     * <b>고정 크기</b> 출력 — 응답 모양이 입력과 무관하게 정해져 있다(키워드 몇 개, 설명 두세 문장).
     * 크기를 {@code max-tokens} 의 비율로 받는다.
     *
     * @param ratio         {@code app.llm.max-tokens} 중 이 작업이 쓸 몫. 배치라면 건수를 곱해 넘긴다
     * @param configuredMax {@code app.llm.max-tokens}
     */
    public static int forFixed(double ratio, int configuredMax) {
        if (configuredMax <= 0) return 0;
        return clamp(Math.round(configuredMax * ratio), configuredMax);
    }

    private static int clamp(long expected, int configuredMax) {
        return (int) Math.min(configuredMax, Math.max(MIN_OUTPUT_TOKENS, expected));
    }
}
