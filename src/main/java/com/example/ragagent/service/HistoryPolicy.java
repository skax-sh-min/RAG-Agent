package com.example.ragagent.service;

import com.example.ragagent.ingestion.CuratedTextUtils;
import com.example.ragagent.llm.PromptBudget;
import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.model.ResponseMode;

import java.util.ArrayList;
import java.util.List;

/**
 * §10.13 — {@code [이전 대화]} 에 <b>얼마나</b> 넣고 각 턴을 <b>무엇으로</b> 렌더할지 정하는
 * 순수 클래스 ({@code RetrievalEviction}/{@code AnswerAttribution} 선례).
 *
 * <p><b>왜 한 곳에 모으는가.</b> 이력을 만드는 경로가 둘이다 — 요약 경로
 * ({@code ConversationSummarizerService.buildContext()} 의 {@code [Recent]} 블록)와 폴백 경로
 * ({@code MemoryRepository.getHistory()}). 둘은 이미 서로 다르게 동작했고(전자는 RAG 턴을 요약만,
 * 후자는 전문 그대로), 그래서 <b>같은 스레드가 요약 캐시가 있느냐에 따라 다른 맥락을 본다</b>.
 * 여기에 새 규칙을 한쪽에만 넣으면 캐시 TTL 이 지나는 순간 이력이 갑자기 달라진다.
 *
 * <p><b>규칙은 "Direct 라서"가 아니라 "문서 자리가 비어서"로 잡는다.</b>
 * <pre>{@code   이력 상한 = 입력 예산 − 문서가 차지할 자리}</pre>
 * 모드별 상수를 두 개 만들지 않는 이유는, 이렇게 두면 나중에 {@code topK} 를 낮춘 배포나 검색
 * 결과가 적게 나온 턴에도 같은 논리가 그대로 적용되기 때문이다 — 모드로 분기하면 그 일반화가 막힌다.
 *
 * <p><b>다만 로딩 시점에 그 자리를 확실히 아는 것은 Direct 뿐이다.</b> 이력은 검색보다 먼저 로딩되고
 * ({@code AgentService.chat()} 이 이력 로딩과 분류를 병렬로 돌린다), 그 시점에 문서가 몇 개 올지는
 * 모른다. Direct 만 검색이 아예 돌지 않아 0 이 확정이다. RAG 쪽 불확실성은 검색이 끝난 뒤 도는
 * {@code AnswerService.fitToBudget()} 이 이미 담당하므로, 1차 적용은 Direct 에만 한다.
 */
public final class HistoryPolicy {

    private HistoryPolicy() {}

    /**
     * 요약 섹션이 없는 답변을 {@code [Recent]} 에 실을 때의 글자 상한 — <b>RAG 턴을 물을 때만</b>
     * 걸린다.
     *
     * <p>실제로 이 캡에 닿는 유일한 종류가 DN 답변이었다(RAG 답변은 {@code ## 요약} 섹션만 들어가
     * 캡 근처에 가지 않는다). §10.13 1단계가 DN 에도 요약 섹션을 요구하면서 정상 경로에서는 빠졌지만
     * <b>지우지는 않는다</b> — 모델이 형식을 안 따랐는데 답변이 긴 경우의 안전판이다.
     */
    public static final int RECENT_ANSWER_CAP = 1_200;

    /**
     * <b>프롬프트에 실제로 실리는 턴 수 상한</b> — 가져오는 창({@code app.memory.fetch-limit-turns})의
     * 절반. RAG·Direct 양쪽, 두 이력 경로 모두에 걸린다.
     *
     * <p>가져오는 창과 싣는 양을 분리하는 이유: 요약 경로는 <b>가져온 것 전부</b>를 요약 재료로 쓰고
     * 그중 최근 몇 턴만 원문으로 싣는다. 상한을 가져오는 창 자체에 걸면 요약이 볼 수 있는 과거까지
     * 함께 줄어들어, "오래된 것은 압축하고 최근 것은 원문으로"라는 구조가 무너진다. 절반이라는 값은
     * 그 둘의 비율을 하나의 설정({@code MEMORY_FETCH_LIMIT_TURNS})으로 묶어 두기 위한 것이다 —
     * 창을 넓히면 싣는 양도 함께 넓어지고, 둘이 따로 놀지 않는다.
     *
     * <p>이 상한은 <b>글자 예산과 독립</b>이다(§10.13 의 {@link #budgetChars}). 예산은 "창에
     * 들어가는가"를, 이쪽은 "얼마나 오래된 이야기까지 원문으로 되살릴 것인가"를 정한다 — 창이
     * 넉넉해도 열 턴 전 대화가 원문으로 들어오면 지금 질문의 맥락이 흐려진다.
     */
    public static int promptTurnCap(int fetchLimitTurns) {
        return Math.max(1, fetchLimitTurns / 2);
    }

    /**
     * 이력 문자열에 실제로 몇 턴이 들어 있는지 — <b>로그 표시 전용</b>이다.
     *
     * <p>두 경로 모두 한 턴을 {@code "Q: …\nA: …"} 로 쓰므로 줄 첫머리의 {@code "Q: "} 를 센다.
     * {@link #trimToBudget} 이 쓰는 경계와 같은 모양이고, 그쪽 주석이 말하듯 <b>항상 정확하지는
     * 않다</b> — 답변 본문 안에 빈 줄 다음 {@code "Q: "} 로 시작하는 줄이 있으면(FAQ 형식 답변)
     * 더 세어진다. 프롬프트 크기 로그의 가독성을 위한 값이라 그 정도 오차는 감수하고, 어떤 판단에도
     * 쓰지 않는다.
     */
    public static int countTurns(String history) {
        if (history == null || history.isBlank()) return 0;
        int count = 0;
        for (String line : history.split("\n", -1)) {
            if (line.startsWith("Q: ")) count++;
        }
        return count;
    }

    /**
     * Direct 프롬프트에서 이력·질문 말고 고정으로 들어가는 것의 토큰 추정 — 시스템 프롬프트와
     * {@code [이전 대화]}/{@code [현재 질문]} 헤더, 인젝션 가드 래퍼.
     *
     * <p>실측이 아니라 <b>넉넉한 예약</b>이다({@code AnswerService.ANSWER_PROMPT_SECTION_OVERHEAD_TOKENS}
     * 와 같은 성격). 실제 Direct 시스템 프롬프트는 한국어 400자 남짓이라 이 값의 절반도 안 쓰는데,
     * 과소 예약은 곧 창 초과이고 과대 예약은 이력 몇 백 자를 덜 넣는 것뿐이라 방향이 명확하다.
     */
    static final int DIRECT_PROMPT_OVERHEAD_TOKENS = 1_000;

    /**
     * 이 턴의 이력에 쓸 수 있는 <b>글자 수</b>.
     *
     * <p>토큰을 글자로 바꿀 때 <b>1토큰 = 1글자</b>로 본다 — {@link TokenEstimator} 의 한국어 가정이고,
     * {@code PromptBudget.rewriteInputChars()} 가 같은 방향으로 보수적으로 잡는다.
     *
     * <p><b>창을 모르면 아무것도 하지 않는다</b> — 추측한 숫자로 이력을 늘리거나 줄이지 않고
     * {@code fallbackChars}(= {@code MemoryService.maxConversationChars()}) 를 그대로 돌려준다.
     * {@code ProviderContextWindows} 가 "모름"을 값으로 표현하는 이유와 같다.
     *
     * <p><b>이 값은 넓히기만 하는 것이 아니다.</b> 창이 작은 배포에서는 오늘의 고정 5,000자보다
     * 작게 나올 수 있고, 그때 줄이는 것이 옳다 — Direct 경로에는 지금 예산 가드가 없어서 그 5,000자가
     * 이미 창을 넘기고 있었다. 규칙 하나를 정직하게 적용하면 확대와 축소가 같은 식에서 나온다.
     *
     * @param contextWindow      이 호출을 받을 프로바이더의 창(토큰). {@code <= 0} 이면 모름
     * @param outputReservation  이 호출이 출력에 잡아 둘 자리
     *                           ({@code AnswerService.outputReservation()})
     * @param documentTokens     이 턴에서 검색 문서가 가져갈 자리. Direct 는 0
     * @param questionTokens     질문의 토큰 추정
     * @param fallbackChars      창을 모를 때 쓸 값
     */
    public static int budgetChars(int contextWindow, int outputReservation,
                                  int documentTokens, long questionTokens, int fallbackChars) {
        if (contextWindow <= 0) return fallbackChars;
        long usable = new PromptBudget(contextWindow, outputReservation).inputBudget()
                - documentTokens - questionTokens - DIRECT_PROMPT_OVERHEAD_TOKENS;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, usable));
    }

    /**
     * 한 턴의 답변을 {@code [이전 대화]} 에 어떻게 싣을지.
     *
     * <p><b>두 축이 동시에 관여한다.</b> {@code askingDirect}(지금 묻는 턴이 Direct 인가)가
     * <em>얼마나</em>를 정하고, {@code responseMode}(이전 턴이 어떤 모드였나)가 <em>무엇을</em>
     * 정한다. 서로 다른 턴의 서로 다른 축이라 하나로 합칠 수 없다.
     *
     * <table>
     *   <caption>이전 턴 → 지금 묻는 턴</caption>
     *   <tr><td>RAG(RS·RN·RC) → RAG</td><td>{@code ## 요약} 만(없으면 {@value #RECENT_ANSWER_CAP}자 캡)</td></tr>
     *   <tr><td><b>Direct(DS·DN) → RAG</b></td><td>Direct 로 물을 때와 같게 — 아래 두 줄</td></tr>
     *   <tr><td>S(RS·DS) → Direct</td><td>{@code ## 요약} — 그게 답변 전부다</td></tr>
     *   <tr><td>N·C(RN·RC·DN) → Direct</td><td>{@code ## 요약}·{@code ## 참고} 를 뺀 나머지</td></tr>
     * </table>
     *
     * <p><b>이전 턴이 Direct 였으면 지금 묻는 턴이 RAG 라도 줄이지 않는다.</b> 요약으로 줄이는
     * 근거가 "RAG 턴은 검색을 다시 하므로 이전 답변 전문은 {@code [검색된 문서]} 의 복제"라는
     * 것인데, Direct 턴에는 복제될 문서가 <b>애초에 없었다</b> — 그 답변의 산문이 그 턴에 대한
     * 유일한 기록이고, 사용자가 Direct 를 고른 것 자체가 "문서 밖의 이야기를 하자"는 의도다.
     * 줄이면 그 의도가 다음 턴에서 사라진다. 조건이 "DN 이면"이 아니라 "Direct 였으면"인 이유는
     * DS 가 이미 {@code ## 요약} = 답변 전부라 같은 갈래로 떨어져도 결과가 같기 때문이고
     * (C 는 Direct 와 배타), 그래야 값이 아니라 성질로 분기하는 이 클래스의 규칙이 유지된다.
     *
     * <p><b>대가</b>: 검증 프롬프트에는 이력이 들어가지 않으므로({@code AnswerService.buildEvalPrompt})
     * 모델이 이력의 Direct 산문에 기대어 답하면 {@code grounded=false} 로 판정될 수 있다. 답변
     * 시스템 프롬프트가 "[검색된 문서]에 포함된 내용만 근거로"라고 이미 못 박고 있고, 실패해도
     * 재시도가 답변만 다시 쓰며(§6.27) 최종적으로는 미검증 배지로 정직하게 드러나므로 감수한다.
     *
     * <p><b>같은 이전 턴이 다음에 무엇을 묻느냐에 따라 다르게 렌더된다.</b> 자의적이지 않다 —
     * 이번 턴에 무엇이 함께 들어가야 하는지가 다르고, {@code fitToBudget()} 이 검색 문서를 턴마다
     * 다시 재는 것과 같은 논리다.
     *
     * <p><b>DN 의 순효과는 캡 제거뿐이다.</b> DN 은 세 번째 갈래로 떨어지는데 뺄 섹션이 애초에 없어
     * (1단계 이후에는 {@code ## 요약} 이 빠진다) 본문이 그대로 남고, {@code ## 참고} 제거도 검색이
     * 없으니 무의미하다. 그것이 이 항목이 노리는 바다.
     *
     * <p>{@code ## 참고}(인용 목록)를 빼는 이유는 검색이 돌지 않는 Direct 후속 턴에서 출처 목록은
     * 값이 거의 없고 길이는 길기 때문이다. 반면 C 의 {@code ## 검증되지 않은 부분} 은 남는다 —
     * {@link CuratedTextUtils#stripStructuralSections} 가 요약·참고만 건드리고, 다음 턴이 "무엇이
     * 확인되지 않았는지"를 아는 것은 실제로 쓸모가 있다.
     *
     * @param responseMode 이전 턴의 저장된 모드 문자열. null/레거시 값은 {@link ResponseMode#parse}
     *                     가 N 으로 읽는다 — 값을 못 읽었다는 이유로 본문을 버리지 않는 방향이다
     */
    public static String renderAnswer(String answer, String responseMode, boolean askingDirect,
                                      boolean previousTurnWasDirect) {
        if (answer == null) return "";
        String ownSummary = CuratedTextUtils.extractSummarySection(answer);
        if (!askingDirect && !previousTurnWasDirect) {
            // 지금까지의 규칙 그대로. RAG 턴은 검색을 다시 하므로 이전 답변 전문은 [검색된 문서] 를
            // 모델의 검증되지 않은 산문으로 한 번 더 복제하는 셈이 된다.
            return ownSummary.isBlank() ? cap(answer, RECENT_ANSWER_CAP) : ownSummary;
        }
        // 분기는 값이 아니라 성질로 한다 — ResponseModeBranchConventionTest 가 모드 값 비교를
        // 빌드 실패로 막는다(그 가드는 텍스트 검사라 이 주석에 그 패턴을 적는 것조차 걸린다).
        // summaryOnly() 가 마침 정확히 S 만 참이라 새 성질을 만들지 않는다.
        if (ResponseMode.parse(responseMode).summaryOnly()) {
            return ownSummary.isBlank() ? answer : ownSummary;
        }
        String body = CuratedTextUtils.stripStructuralSections(answer);
        return body.isBlank() ? answer : body;
    }

    private static String cap(String answer, int cap) {
        if (cap <= 0 || answer.length() <= cap) return answer;
        return answer.substring(0, cap).strip() + "…";
    }

    /**
     * 오래된 쪽부터 버려 예산에 맞춘다 — 가능하면 <b>턴 경계</b>(빈 줄 + {@code "Q: "})에서.
     *
     * <p>이력은 폴백 경로에서 {@code "Q: …\nA: …"} 를 빈 줄로 이어 붙여 만들어지므로 그 경계가
     * 존재한다. 다만 <b>항상 그 모양인 것은 아니다</b>: 요약 경로는 요약문 + 최근 턴을 섞어 주고,
     * 답변 본문 안에 빈 줄 다음 {@code "Q: "} 로 시작하는 줄이 있으면(FAQ 형식 답변) 경계가 더 잘게
     * 잡힌다.
     *
     * <p>그래서 경계를 <b>찾지 못했을 때 전부 버리지 않는다</b>. 예전 구현은 분할 결과가 한 덩어리면
     * 그 하나를 지우고 빈 문자열을 반환했다 — 요약 경로의 이력이 통째로 사라지는 것이 정확히 그
     * 경우였고, 예산이 조금 모자랄 뿐인데 대화 맥락 전체를 잃었다.
     *
     * @param budget 남은 <b>토큰</b> 예산
     */
    public static String trimToBudget(String history, long budget) {
        if (history == null || history.isBlank()) return "";
        if (budget <= 0) return "";
        if (TokenEstimator.estimate(history) <= budget) return history;

        List<String> turns = new ArrayList<>(List.of(history.split("\n\n(?=Q: )")));
        if (turns.size() > 1) {
            while (turns.size() > 1) {
                turns.removeFirst();   // 가장 오래된 턴
                String candidate = String.join("\n\n", turns);
                if (TokenEstimator.estimate(candidate) <= budget) return candidate;
            }
            // 마지막 한 턴만 남았는데도 넘친다 — 아래 줄 단위 절단으로 넘긴다.
            history = turns.getFirst();
        }
        return trimLeadingLines(history, budget);
    }

    /**
     * 턴 경계를 쓸 수 없을 때의 폴백 — 앞에서부터 <b>줄 단위</b>로 덜어낸다.
     *
     * <p>문자 인덱스로 자르지 않는 이유는 문장·코드가 반 토막 나면 남은 이력이 오히려 모델을
     * 헷갈리게 하기 때문이다. 한 줄도 못 남기면 빈 문자열이다(그 한 줄조차 예산을 넘는 경우).
     */
    private static String trimLeadingLines(String text, long budget) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n")));
        while (!lines.isEmpty()) {
            lines.removeFirst();
            String candidate = String.join("\n", lines);
            if (TokenEstimator.estimate(candidate) <= budget) return candidate.strip();
        }
        return "";
    }
}
