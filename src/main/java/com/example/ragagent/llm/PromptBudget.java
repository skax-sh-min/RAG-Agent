package com.example.ragagent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 한 호출의 <b>입력</b>에 쓸 수 있는 토큰 예산과, 그 안에 맞추는 자르기 규칙.
 *
 * <p>이 계산이 필요한 이유는 {@code max_tokens} 가 상한이 아니라 <b>예약</b>이기 때문이다. OpenAI
 * 호환 서버는 {@code 프롬프트 + max_tokens ≤ n_ctx} 를 검사하므로, 창의 크기만 아는 것으로는
 * 부족하고 <b>출력에 잡아 둔 자리를 뺀 나머지</b>가 진짜 입력 한도다.
 *
 * <pre>{@code   입력 예산 = 컨텍스트 창 − 출력 예약 − 여유}</pre>
 *
 * <p><b>여유(margin)를 두는 이유.</b> 토큰 수는 {@link TokenEstimator} 의 <b>추정</b>이지 측정이
 * 아니다. 한글을 글자당 1토큰으로 잡는 것은 실제 범위(음절당 1~2)의 아래쪽 끝이라 이 추정은
 * <b>과소평가할 수 있고</b>, 예산에서 과소평가는 곧 초과다. 창의 10%(최소 {@value #MIN_MARGIN})를
 * 비워 그 오차를 흡수한다 — 오차가 크기에 비례하므로 고정값이 아니라 비율이다.
 *
 * <p><b>이 클래스는 창을 모르는 경우를 다루지 않는다.</b> 호출부가 먼저 판단해야 한다: 창을 모르면
 * 예산도 없고, 그때는 <b>아무것도 자르지 않는 것</b>이 맞다(추측한 숫자로 근거 문서를 버리는 것은
 * 컨텍스트 초과보다 나쁘다). {@link ProviderContextWindows} 가 "모름"을 값으로 표현하는 이유와 같다.
 */
public record PromptBudget(int contextWindow, int outputReservation) {

    /** 아주 작은 창에서도 최소한 이만큼은 오차 흡수용으로 비워 둔다. */
    public static final int MIN_MARGIN = 256;

    /** 창의 이 비율을 여유로 둔다(1/10). */
    private static final int MARGIN_DIVISOR = 10;

    public static int marginFor(int contextWindow) {
        return Math.max(MIN_MARGIN, contextWindow / MARGIN_DIVISOR);
    }

    /** 입력에 쓸 수 있는 토큰 수. 예약과 여유가 창을 다 먹으면 0(= 담을 수 있는 것이 없다). */
    public int inputBudget() {
        return Math.max(0, contextWindow - outputReservation - marginFor(contextWindow));
    }

    /**
     * <b>재작성</b> 작업 한 번에 넣을 수 있는 본문 글자 수 — MD 형식 교정·txt→md 구조화용.
     *
     * <p>답변 호출과 셈이 다른 이유는 <b>출력 예약이 입력에 비례</b>하기 때문이다. 재작성은 넣은 만큼
     * 나오므로 {@code IndexingOutputCap.forRewrite()} 가 입력 추정의 1.5배를 예약한다. 그래서 본문 S 는
     * 자기 자신과 자기 예약을 동시에 창에 넣어야 한다:
     *
     * <pre>{@code   지시 프롬프트 + S + 1.5S + 여유 ≤ 창   →   S ≤ (창 − 지시 − 여유) / 2.5}</pre>
     *
     * <p>답변 예산처럼 "예약을 먼저 빼고 남은 것"으로 계산하면 순환이 된다(예약이 S 에 달려 있다).
     * 그 순환을 푼 것이 위 2.5 다.
     *
     * <p>토큰을 글자로 바꿀 때는 <b>1글자 = 1토큰</b>으로 본다({@link TokenEstimator} 의 한글 가정).
     * 영어 문서라면 실제로는 4배 더 들어가지만, 여기서 넉넉하게 잡으면 좁은 창에서 초과가 나므로
     * 보수적인 쪽을 택한다.
     *
     * @param contextWindow          이 호출을 받을 프로바이더의 창(토큰)
     * @param promptOverheadTokens   본문을 뺀 지시 프롬프트의 토큰 수
     * @return 본문에 쓸 수 있는 글자 수. 창이 지시 프롬프트도 못 담으면 0
     */
    public static int rewriteInputChars(int contextWindow, long promptOverheadTokens) {
        long usable = contextWindow - promptOverheadTokens - marginFor(contextWindow);
        if (usable <= 0) return 0;
        return (int) (usable * 100 / (100 + IndexingOutputCap.REWRITE_HEADROOM_PERCENT));
    }

    /**
     * 예산 안에 들어가도록 <b>뒤에서부터</b> 덜어낸 앞부분을 돌려준다.
     *
     * <p>앞이 남는 이유는 이 앱의 검색 결과가 RRF 점수 내림차순이라 <b>뒤가 곧 최저 관련도</b>이기
     * 때문이다({@code RetrievalService} 의 최종 정렬). 관련도 낮은 쪽부터 버리는 것이 답변 품질에
     * 가장 덜 해롭고, 응답 참여도 측정(§ {@code AnswerAttribution})도 검색 하위 문서가 답변에
     * 기여하지 않는 일이 흔하다고 말한다.
     *
     * <p><b>첫 항목은 예산을 넘겨도 남긴다.</b> 문서를 전부 버리면 프롬프트는 "문서를 찾을 수
     * 없습니다" 가 되어 검색이 성공했는데도 모른다고 답하게 된다 — 잘린 근거로 답하는 편이 낫다.
     * {@code AnswerService.buildEvalExcerpts()} 가 예전부터 쓰던 규칙과 같다.
     *
     * @param fixedCost 자를 수 없는 부분(시스템 프롬프트·질문·대화 이력 등)이 이미 쓰는 토큰
     */
    public static <T> List<T> fitByPrefix(List<T> items, ToLongFunction<T> cost,
                                          long fixedCost, long budget) {
        if (items == null || items.isEmpty()) return List.of();
        List<T> kept = new ArrayList<>(items.size());
        long used = fixedCost;
        for (T item : items) {
            long c = cost.applyAsLong(item);
            if (!kept.isEmpty() && used + c > budget) break;
            kept.add(item);
            used += c;
        }
        return kept;
    }
}
