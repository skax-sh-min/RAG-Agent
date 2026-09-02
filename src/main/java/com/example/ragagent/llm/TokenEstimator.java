package com.example.ragagent.llm;

/**
 * 텍스트의 토큰 수 추정 — 이 앱에서 실제 토큰 수를 알 수 없을 때 쓰는 <b>단 하나의</b> 가정.
 *
 * <p><b>왜 하나로 모았나.</b> 예전에는 같은 코드베이스에 4배 차이 나는 두 가정이 공존했다
 * (PLAN §6.24 의 남은 이슈):
 * <ul>
 *   <li>{@code LlmRouter.approxTokens()} 와 임베딩 사용량 폴백은 <b>chars/4</b> — 영어 기준
 *       경험칙이다.</li>
 *   <li>{@code ResponseMode} 의 예산과 {@code AnswerService.MAX_EVAL_EXCERPT_CHARS} 산정은
 *       <b>한글 1토큰 ≈ 1글자</b>로 잡는다.</li>
 * </ul>
 * 둘 다 자기 자리에서는 맞다 — 틀린 건 "하나의 텍스트에 어느 쪽을 쓸지"가 호출부마다 달랐다는
 * 점이다. 그 결과 한국어 스트리밍 답변의 사용량이 {@code /llm-usage} 에 <b>약 4배 적게</b>
 * 기록됐다. 앞으로 컨텍스트 창을 근거로 입력 예산을 짜려면 그런 배수 오차가 그대로 예산 오차가
 * 되므로, 그 전에 가정을 하나로 만들어야 한다.
 *
 * <p><b>통일 방식.</b> 둘 중 하나를 고르는 대신 <b>둘을 특수 케이스로 갖는 식</b>을 쓴다:
 * <pre>{@code   tokens ≈ (한글·CJK 글자 수 × 1) + (나머지 글자 수 / 4)}</pre>
 * 순수 한국어면 글자 수와 같아지고(옛 {@code ResponseMode} 가정과 일치), 순수 영어면 chars/4 가
 * 되어(옛 {@code approxTokens} 와 <b>정확히</b> 같은 값) 영어권 배포에는 회귀가 없다. 섞인 글은
 * 그 사이로 자연스럽게 보간된다.
 *
 * <p><b>왜 CJK 를 1로 두나.</b> 한글 음절은 UTF-8 3바이트라 바이트 BPE 에서 음절당 1~2 토큰으로
 * 쪼개지는 것이 보통이고, 최신 토크나이저에서도 1 토큰 아래로 잘 내려가지 않는다. 1.0 은 그
 * 범위의 <b>낙관적인 끝</b>이 아니라 하한에 가깝다 — 즉 이 추정은 예산 용도로 쓸 때 과소평가보다
 * 과대평가 쪽으로 기울며, 컨텍스트 예산에서는 그쪽이 안전한 방향이다(넘치는 것보다 조금 덜 담는
 * 편이 낫다).
 *
 * <p><b>이것은 추정이지 측정이 아니다.</b> 정확한 값은 모델의 토크나이저만 안다. 실제 토큰 수를
 * 주는 경로(블로킹 호출의 {@code ChatResponse} usage 메타데이터)는 <b>반드시 그 값을 쓴다</b> —
 * 이 클래스는 그것을 얻을 수 없는 자리(토큰 델타만 읽는 스트리밍, usage 를 안 주는 임베딩 서버)의
 * 폴백이다.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /** 나머지(라틴·숫자·공백·문장부호) 글자 몇 개가 1 토큰인가 — 영어권 표준 경험칙. */
    private static final int CHARS_PER_TOKEN_NON_CJK = 4;

    /** {@code null}/빈 문자열은 0. */
    public static long estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjk = 0;
        long other = 0;
        // codePoint 단위로 센다 — 이모지·보조 평면 문자가 char 두 개로 세어져 부풀지 않도록.
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjkLike(cp)) cjk++; else other++;
            i += Character.charCount(cp);
        }
        return cjk + (other / CHARS_PER_TOKEN_NON_CJK);
    }

    /** 여러 조각의 합 — 임베딩 배치처럼 문자열 목록을 한 번에 셀 때. */
    public static long estimate(Iterable<String> texts) {
        if (texts == null) return 0;
        long total = 0;
        for (String t : texts) total += estimate(t);
        return total;
    }

    /**
     * 한 글자가 대략 한 토큰인 문자인가 — 한글(음절·자모·호환 자모), CJK 한자, 일본어 가나.
     *
     * <p>범위를 이 정도로 좁게 잡은 이유는 <b>구두점·공백을 여기 넣으면 안 되기 때문</b>이다.
     * 한국어 문장에도 공백과 마침표가 섞여 있고 그것들은 다른 토큰에 흡수되는 쪽이라, CJK 취급하면
     * 추정이 눈에 띄게 부풀어 예산을 필요 이상으로 깎게 된다.
     */
    static boolean isCjkLike(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7A3)     // 한글 음절
            || (cp >= 0x1100 && cp <= 0x11FF)     // 한글 자모
            || (cp >= 0x3130 && cp <= 0x318F)     // 한글 호환 자모
            || (cp >= 0x4E00 && cp <= 0x9FFF)     // CJK 통합 한자
            || (cp >= 0x3400 && cp <= 0x4DBF)     // CJK 통합 한자 확장 A
            || (cp >= 0x3040 && cp <= 0x30FF)     // 히라가나 · 가타카나
            || (cp >= 0xF900 && cp <= 0xFAFF);    // CJK 호환 한자
    }
}
