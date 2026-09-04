package com.example.ragagent.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 프롬프트를 구성 요소별로 재서 한 줄로 찍는 디버그 로그 포매터.
 *
 * <pre>{@code
 * [PROMPT] 답변 · thread=t1 mode=RN — 시스템(240 tok, 690 byte), 이전 대화 3턴(1,832 tok, 4,964 byte),
 *          문서 10청크(6,832 tok, 17,964 byte), 질문(32 tok, 64 byte)
 *          | 합계 8,936 tok, 23,682 byte | 창 32,768 tok, 입력 예산 29,000 tok (31% 사용)
 * }</pre>
 *
 * <p><b>왜 토큰과 바이트를 함께 찍는가.</b> 두 수치가 답하는 질문이 다르다 — 창을 넘겼는지는
 * 토큰이 정하지만 그 토큰은 {@link TokenEstimator} 의 <b>추정</b>이고(한국어 1글자 = 1토큰 가정),
 * 바이트는 실제로 소켓으로 나간 양이다. 둘의 비가 평소와 다르면 추정이 빗나가고 있다는 신호이고
 * (§ TokenEstimateCalibration 이 사후에 재는 것과 같은 종류의 관측), 무엇보다 "무엇이 프롬프트를
 * 부풀렸는가"는 추정값 하나로는 판단할 수 없다.
 *
 * <p>순수 클래스다({@code RetrievalEviction}/{@code HistoryPolicy} 선례) — 로거를 들지 않고
 * 문자열만 만든다. 호출부가 {@code log.isDebugEnabled()} 로 감싸는 것을 전제로 하며, 그래서
 * 토큰 추정(문자열 전체 순회)이 디버그가 꺼진 배포에서 아예 실행되지 않는다.
 */
public final class PromptSizeLog {

    private final String kind;
    private final List<String> parts = new ArrayList<>();
    private long totalTokens;
    private long totalBytes;

    private PromptSizeLog(String kind) {
        this.kind = kind;
    }

    /** @param kind 이 프롬프트가 무엇인지 — {@code "답변"}, {@code "검증"} 등 */
    public static PromptSizeLog of(String kind) {
        return new PromptSizeLog(kind);
    }

    /** {@code 질문(32 tok, 64 byte)} — 빈 조각은 건너뛴다(없는 블록을 0으로 찍지 않는다). */
    public PromptSizeLog add(String label, String text) {
        return add(label, null, text);
    }

    /**
     * {@code 이전 대화 3턴(1,832 tok, 4,964 byte)} — 개수가 의미 있는 블록용.
     *
     * @param quantity 라벨 뒤에 붙일 수량 표기({@code "3턴"}, {@code "10청크"}). {@code null} 이면 생략
     */
    public PromptSizeLog add(String label, String quantity, String text) {
        if (text == null || text.isEmpty()) return this;
        long tokens = TokenEstimator.estimate(text);
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        totalTokens += tokens;
        totalBytes += bytes;
        parts.add("%s%s(%,d tok, %,d byte)".formatted(
                label, quantity == null || quantity.isBlank() ? "" : " " + quantity, tokens, bytes));
        return this;
    }

    /** 합계 뒤에 붙일 꼬리말(창·예산·재시도 단계 등). {@code null}/빈 값이면 붙지 않는다. */
    public String render(String tail) {
        String body = parts.isEmpty() ? "(빈 프롬프트)" : String.join(", ", parts);
        String line = "%s — %s | 합계 %,d tok, %,d byte".formatted(kind, body, totalTokens, totalBytes);
        return (tail == null || tail.isBlank()) ? line : line + " | " + tail;
    }

    public long totalTokens() {
        return totalTokens;
    }

    /**
     * {@code 창 32,768 tok, 입력 예산 29,000 tok (31% 사용)} — 꼬리말의 표준형.
     *
     * <p><b>창을 모르면 그렇게 적는다</b>({@code ProviderContextWindows} 가 "모름"을 값으로
     * 표현하는 것과 같은 이유). 0 을 창으로 찍으면 로그를 읽는 사람이 사용률 100% 로 오해한다.
     *
     * @param window 프로바이더 창(토큰). {@code <= 0} 이면 모름
     * @param inputBudget 이 호출이 입력에 쓸 수 있는 토큰. {@code <= 0} 이면 생략
     */
    public String budgetTail(String providerName, int window, long inputBudget) {
        StringBuilder sb = new StringBuilder();
        sb.append("프로바이더 ").append(providerName == null || providerName.isBlank() ? "?" : providerName);
        if (window <= 0) {
            sb.append(", 창 모름");
            return sb.toString();
        }
        sb.append(", 창 %,d tok".formatted(window));
        if (inputBudget > 0) {
            sb.append(", 입력 예산 %,d tok (%d%% 사용)".formatted(
                    inputBudget, Math.round(totalTokens * 100.0 / inputBudget)));
        }
        return sb.toString();
    }
}
