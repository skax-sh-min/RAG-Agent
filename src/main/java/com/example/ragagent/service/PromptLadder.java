package com.example.ragagent.service;

import com.example.ragagent.llm.TokenEstimator;
import com.example.ragagent.model.SentPrompt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * §6.30 프롬프트 축소 사다리 — RAG 답변 프롬프트가 입력 예산을 넘을 때 <b>무엇을 어떤 순서로</b> 줄이는가. 순수 클래스
 * ({@code RetrievalEviction}/{@code HistoryPolicy} 선례): 사전 예산({@code AnswerService.fitToBudget})과 컨텍스트 초과 뒤의
 * 축소 재시도({@code withShrinkRetry})가 같은 사다리를 지나고, {@code /settings} 생각 수준 미리보기의 "문서 k/top-K" 도
 * 이것을 부른다(미리보기 = 런타임).
 *
 * <p><b>왜 사다리인가.</b> 예전 규칙은 하나였다 — 하위 문서를 다 버린 뒤(최상위 1개는 남김) 이력을 오래된 턴부터. 그 순서는
 * "문서 1개 + 이력 전부"와 "문서 여러 개 + 이력 없음" 중 <b>언제나 앞쪽</b>을 골랐는데, 긴 독립 질문(로그·코드 붙여넣기)은
 * 이력이 거의 쓸모없고 근거가 중요하며, 짧은 후속 질문은 직전 턴이 없으면 지시어를 못 푼다. 그래서 잃는 것이 작은 조각부터
 * 자르고, 문서에 <b>top-k 의 50% 바닥</b>을 둔다 — "이 밑으로 내려가기 전에 이력을 먼저 줄인다"는 약속이다(보장이 아니라
 * 순서다: 바닥 아래로도 내려갈 수 있어, 예전보다 더 쉽게 실패하는 경우는 없다).
 *
 * <table>
 *   <caption>칸 — 넘칠 때만 한 조각씩 내려가고, 조각마다 "들어가는가"를 다시 잰다</caption>
 *   <tr><td>1</td><td>직전 질문을 뺀 과거 질문을 {@value HistoryPolicy#PAST_QUESTION_CAP}자로</td></tr>
 *   <tr><td>2</td><td>이력을 직전 1턴만 — 요약 블록 먼저, 그다음 오래된 턴부터 하나씩</td></tr>
 *   <tr><td>3</td><td>하위 문서를 하나씩, 바닥까지</td></tr>
 *   <tr><td>4</td><td>직전 턴의 답변을 요약으로(실제 대상은 직전 Direct 턴 — 이전 RAG 턴은 이미 요약만 실린다)</td></tr>
 *   <tr><td>5</td><td>이력을 전부 뺀다 — 후속 질문이면 다시 쓴 질문 한 줄로. 턴으로 나뉘지 않는 이력(요약 블록뿐)은 그 전에
 *       앞쪽 줄부터 하나씩 뺀다</td></tr>
 *   <tr><td>6</td><td>문서를 1개까지 하나씩</td></tr>
 *   <tr><td>7</td><td>그래도 넘치면 최상위 문서만으로 보낸다 — 서버가 거절하면 축소 재시도 → {@code RAG-LLM-003}</td></tr>
 * </table>
 *
 * <p><b>되돌려 채우기.</b> 들어가면 멈추고, 그 앞 칸들을 <b>거꾸로</b> 하나씩 되돌려 본다 — 각 칸이 뺀 조각을 들어가는 만큼만
 * 다시 넣는다(문서는 빠진 것 중 순위가 높은 것부터, 턴은 최근 것부터; 들어가지 않는 조각을 만나면 그 칸은 거기서 멈추고 앞
 * 칸으로 간다). 칸의 순서는 "무엇을 먼저 포기하는가"이지 "앞 칸을 끝까지 적용해야 다음 칸으로 간다"가 아니다 — 이게 없으면
 * 칸 2 가 그 앞 턴을 버린 뒤 칸 3 이 어차피 문서를 줄여 이력 전부가 들어갈 자리가 남는데도 버린 채 보낸다.
 *
 * <p><b>불변식</b>: 바닥 아래에서는 이력 때문에 문서가 빠지지 않는다.
 *
 * <p><b>재시도(검증 실패) 모드</b> — {@link Input#previousCut} 가 있으면 사다리의 순서가 바뀐다(PLAN §6.30 C·D): 직전 시도가
 * 이력에 한 절단을 먼저 다시 하고(되돌려 채우기로 늘어나는 것은 된다), 넘치는 몫은 <b>문서로</b> 흡수한다 — 바닥까지.
 * 바닥 아래로 내려가야 할 때만 나머지 이력 칸을 평소 순서대로 쓴다. 근거가 모자라서 한 재시도가 맥락까지 깎지 않게.
 */
public final class PromptLadder {

    private PromptLadder() {}

    /** 문서 바닥 — 설정 top-k 의 이만큼(올림). 상수로 시작하고, 측정이 배포마다 다르게 나오면 설정(핫)으로 뺀다. */
    static final int FLOOR_PERCENT = 50;

    /**
     * 축소 재시도에서 <b>문서 칸</b>에 쓸 수 있는 걸음 수의 상한 — 예전 {@code MAX_SHRINK_ATTEMPTS}(5)를 그대로 받는다. 재시도
     * 상한은 이 값과 이력 칸 수(최대 4)의 합이다({@link Plan#maxLevel}): 이력 칸은 한 번씩만 돌고 횟수가 정해져 있지만, 문서는
     * top-k 에 비례해 늘어 상한이 없으면 결정적으로 실패하는 요청에 왕복만 쓴다. 초과는 생성 전에 거절되므로 실패한 시도의
     * 왕복은 빠르다.
     */
    static final int MAX_DOC_MOVES = 5;

    /** 칸. 번호는 PLAN §6.30 의 표와 같다. */
    public enum Rung {
        CAP_QUESTIONS(1), LAST_TURN_ONLY(2), DOCS_TO_FLOOR(3), DIGEST_LAST_ANSWER(4), DROP_HISTORY(5), DOCS_TO_ONE(6);

        public final int number;

        Rung(int number) { this.number = number; }
    }

    /**
     * 문서 바닥 — 설정 top-k 의 {@value #FLOOR_PERCENT}%, 올림, 최소 1, 실제로 검색된 문서 수를 넘지 않는다(10 → 5, 5 → 3,
     * 3 → 2, 1 → 1). 재시도의 escalation({@code topK + retrievalRetries})으로 문서가 늘어도 바닥은 설정값 기준 그대로다.
     */
    public static int floor(int topKSetting, int retrieved) {
        if (retrieved <= 0) return 0;
        int half = Math.max(1, (Math.max(0, topKSetting) * FLOOR_PERCENT + 99) / 100);
        return Math.min(retrieved, half);
    }

    /**
     * 첫 시도의 문서 빼는 순서 — 순위의 역순(최하위부터). 1순위(index 0)는 넣지 않는다: 다 버리면 프롬프트가 "문서를 찾을 수
     * 없습니다"가 된다.
     */
    public static List<Integer> rankRemovalOrder(int docCount) {
        List<Integer> order = new ArrayList<>();
        for (int i = docCount - 1; i >= 1; i--) order.add(i);
        return order;
    }

    /**
     * §6.30 D — 재시도에서 문서를 빼는 순서. 재시도로 들어온 문서(교체분·+1)는 대개 RRF 하위에 서는데, 순위로만 빼면 재시도가
     * 가져온 새 근거가 가장 먼저 잘려 모델은 지난번과 같은 문서를 본다. 그래서 무리를 셋으로 나눈다:
     * <ol>
     *   <li>직전 시도에 실렸지만 근거로 쓰였다고 보고되지 않은 문서</li>
     *   <li>새로 들어온 문서(직전 시도에 실리지 않았던 것)</li>
     *   <li>근거로 쓰였다고 보고된 문서 — 보고가 없으면(모름) 직전에 실렸던 문서 전부</li>
     * </ol>
     * 무리 안에서는 하위부터, 1순위는 언제나 남긴다. 이번 시도에서만 빼는 것이라 교체와 달리 앞쪽 절반도 대상이다.
     *
     * <p><b>보고가 없으면 직전에 실린 문서를 지킨다</b> — 빈 보고는 "안 썼다"가 아니라 "모른다"다({@code RetrievalEviction} 의
     * 가드 ①과 같은 규약). 설계(PLAN §6.30 D)는 반대로 그 문서들을 첫 무리로 뒀는데, 측정에서 바닥 아래로 내려간 재시도가
     * 1순위 + 지난번에 잘린 하위 문서로 갈아탔다가 다음 시도에서 원래 상위 집합으로 돌아오는 왕복을 했다 — 보낸 집합이 매번 달라
     * 조기 종료(§6.30 B)도 걸리지 않아, 12K 긴 질문에서 재시도가 11 → 19회로 늘고 정답 포함이 6 → 4건으로 줄었다
     * (documents/PROMPT_LADDER_EVALUATION.md). 근거 없음 반려는 대개 보고가 비어 오므로 이 경우가 흔하다. 지키면 새 문서가
     * 없을 때 재시도는 같은 문서를 다시 보내고, 같은 재료로 또 반려되면 조기 종료가 멈춘다.
     *
     * @param keys          이번 시도의 문서 키(순위 순)
     * @param previousSent  직전 시도에 실린 문서 키
     * @param reportedUsed  직전 시도의 검증이 근거로 썼다고 보고한 문서 키. 비어 있으면 모름
     */
    public static List<Integer> retryRemovalOrder(List<String> keys, Collection<String> previousSent,
                                                  Collection<String> reportedUsed) {
        List<Integer> unusedSeen = new ArrayList<>();
        List<Integer> fresh = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        boolean usedKnown = reportedUsed != null && !reportedUsed.isEmpty();
        for (int i = keys.size() - 1; i >= 1; i--) {
            String key = keys.get(i);
            if (!previousSent.contains(key)) fresh.add(i);
            else if (!usedKnown || reportedUsed.contains(key)) used.add(i);   // 모름 → 지킨다
            else unusedSeen.add(i);
        }
        List<Integer> order = new ArrayList<>(unusedSeen);
        order.addAll(fresh);
        order.addAll(used);
        return order;
    }

    /**
     * 사다리의 입력.
     *
     * @param docTokens        문서별 토큰 수(순위 순, index 0 = 1순위). 창을 모르면 값은 쓰이지 않는다
     * @param docRemovalOrder  문서를 빼는 순서(index, 1순위 제외) — {@link #rankRemovalOrder} 또는 {@link #retryRemovalOrder}
     * @param floor            문서 바닥({@link #floor})
     * @param history          나눈 이력
     * @param lastAnswerDigest 칸 4 — 직전 턴 답변의 요약. {@code null} = 해당 없음
     * @param condensedLine    칸 5 — 이력 대신 실을 한 줄(후속 질문의 다시 쓴 질문). {@code null} = 없음(이력이 그냥 빠진다)
     * @param fixedTokens      줄이지 않는 몫 — 시스템 프롬프트·질문·경고·직전 시도 메모·머리말
     * @param previousCut      재시도 모드면 직전 시도의 이력 절단, 아니면 {@code null}
     */
    public record Input(List<Long> docTokens, List<Integer> docRemovalOrder, int floor,
                        HistoryPolicy.ParsedHistory history, String lastAnswerDigest, String condensedLine,
                        long fixedTokens, SentPrompt.HistoryCut previousCut) {

        public Input {
            docTokens = List.copyOf(docTokens);
            docRemovalOrder = List.copyOf(docRemovalOrder);
            if (history == null) history = HistoryPolicy.parse("");
        }
    }

    /**
     * 사다리의 결과 — 무엇을 실을 것인가.
     *
     * @param keptDocs       실을 문서 index(순위 순)
     * @param history        실을 이력 문자열 — 줄인 것이 없으면 원문 그대로. 빈 문자열이면 {@code [이전 대화]} 를 싣지 않는다
     * @param cut            이력을 어떻게 줄였는가
     * @param historyRemoved 원래 있던 이력이 전부 빠졌는가(칸 5)
     * @param highestRung    실제로 쓴 가장 깊은 칸 — 0 = 아무것도 줄이지 않았다(로그용)
     * @param fits           추정으로 들어가는가 — 창을 모르면 늘 {@code true}(잴 것이 없다)
     */
    public record Result(List<Integer> keptDocs, String history, SentPrompt.HistoryCut cut,
                         boolean historyRemoved, int highestRung, boolean fits) {}

    private enum Kind { DOC, CAP, DROP_LEAD, DROP_TURN, DIGEST, DROP_LINE, DROP_LAST }

    /** 한 조각 — 칸, 무엇을 빼는가, 대상 index(문서 또는 턴). */
    private record Step(Rung rung, Kind kind, int index) {}

    /**
     * 사다리를 이 입력에 맞춰 세운다.
     *
     * @param limit 입력 예산(토큰). {@code < 0} 이면 창을 모른다 — 사전 축소는 하지 않고, 축소 재시도의 걸음만 쓸 수 있다
     *              (자동 복구가 가장 필요한 배포가 창을 모르는 배포다)
     */
    public static Plan plan(Input in, long limit) {
        return new Plan(in, limit);
    }

    /** 세운 사다리 — 사전 예산이 멈춘 자리와 그 뒤의 걸음을 안다. */
    public static final class Plan {

        private final Input in;
        private final long limit;
        private final List<Step> seq;
        /** 사전 예산이 멈춘 자리 — 이만큼의 조각을 적용하면 들어간다(못 들어가면 전부). */
        private final int stop;

        private Plan(Input in, long limit) {
            this.in = in;
            this.limit = limit;
            this.seq = sequence(in);
            int p = 0;
            if (limit >= 0) {
                boolean[] applied = new boolean[seq.size()];
                while (p < seq.size() && !fits(applied)) applied[p++] = true;
            }
            this.stop = p;
        }

        /**
         * 축소 단계 {@code level} 의 결과. 0 은 사전 예산(멈춘 자리 + 되돌려 채우기)이다. 1 이상은 컨텍스트 초과 뒤의 재시도로,
         * 초과는 추정이 빗나갔다는 확인이라 <b>되돌려 채운 것을 먼저 다시 빼고</b> 그다음 걸음으로 내려간다(남는 자리를 다시
         * 채우지 않는다). 걸음은 이력 칸이면 그 칸의 남은 조각 전부, 문서 칸이면 {@code docStep} 개씩이다.
         */
        public Result at(int level, int docStep) {
            boolean[] applied = new boolean[seq.size()];
            int end = stop;
            for (int i = 0; i < level && end < seq.size(); i++) end = moveEnd(end, docStep);
            for (int i = 0; i < end; i++) applied[i] = true;
            if (level == 0 && limit >= 0 && end > 0 && fits(applied)) backfill(applied, end);
            return result(applied);
        }

        /**
         * 사전 예산 다음에 남은 축소 재시도 횟수 — 이력 칸의 걸음 + 문서 걸음 {@value #MAX_DOC_MOVES} 개까지. 같은 요청을
         * 되풀이하지 않도록, 아무것도 바뀌지 않는 칸은 처음부터 사다리에 없다({@link #sequence}).
         */
        public int maxLevel(int docStep) {
            int history = 0;
            int docs = 0;
            for (int x = stop; x < seq.size(); x = moveEnd(x, docStep)) {
                if (seq.get(x).kind() == Kind.DOC) docs++;
                else history++;
            }
            return history + Math.min(MAX_DOC_MOVES, docs);
        }

        /** {@code x} 에서 시작하는 걸음의 끝(배타). */
        private int moveEnd(int x, int docStep) {
            Step s = seq.get(x);
            int end = x + 1;
            if (s.kind() == Kind.DOC) {
                int taken = 1;
                while (end < seq.size() && taken < Math.max(1, docStep)
                        && seq.get(end).kind() == Kind.DOC && seq.get(end).rung() == s.rung()) {
                    end++;
                    taken++;
                }
            } else {
                while (end < seq.size() && seq.get(end).rung() == s.rung() && seq.get(end).kind() != Kind.DOC) end++;
            }
            return end;
        }

        /**
         * 멈춘 칸에서 그 앞 칸들을 거꾸로 되돌려 본다. 같은 칸이 이어진 조각 묶음을 하나의 칸으로 보고, 묶음 안에서는 마지막에
         * 뺀 것부터(= 문서는 순위가 높은 것부터, 턴은 최근 것부터) 다시 넣는다. 들어가지 않는 조각을 만나면 그 칸은 멈춘다.
         */
        private void backfill(boolean[] applied, int end) {
            int segEnd = end;
            while (segEnd > 0) {
                Rung rung = seq.get(segEnd - 1).rung();
                int segStart = segEnd - 1;
                while (segStart > 0 && seq.get(segStart - 1).rung() == rung) segStart--;
                for (int i = segEnd - 1; i >= segStart; i--) {
                    applied[i] = false;
                    if (!fits(applied)) {
                        applied[i] = true;
                        break;
                    }
                }
                segEnd = segStart;
            }
        }

        private boolean fits(boolean[] applied) {
            if (limit < 0) return true;
            long tokens = in.fixedTokens() + TokenEstimator.estimate(renderHistory(applied));
            boolean[] removed = removedDocs(applied);
            for (int i = 0; i < in.docTokens().size(); i++) {
                if (!removed[i]) tokens += in.docTokens().get(i);
            }
            return tokens <= limit;
        }

        private boolean[] removedDocs(boolean[] applied) {
            boolean[] removed = new boolean[in.docTokens().size()];
            for (int i = 0; i < seq.size(); i++) {
                if (applied[i] && seq.get(i).kind() == Kind.DOC) removed[seq.get(i).index()] = true;
            }
            return removed;
        }

        private boolean has(boolean[] applied, Kind kind) {
            for (int i = 0; i < seq.size(); i++) {
                if (applied[i] && seq.get(i).kind() == kind) return true;
            }
            return false;
        }

        private boolean turnDropped(boolean[] applied, int turn) {
            for (int i = 0; i < seq.size(); i++) {
                Step s = seq.get(i);
                if (applied[i] && s.kind() == Kind.DROP_TURN && s.index() == turn) return true;
            }
            return false;
        }

        private int count(boolean[] applied, Kind kind) {
            int n = 0;
            for (int i = 0; i < seq.size(); i++) {
                if (applied[i] && seq.get(i).kind() == kind) n++;
            }
            return n;
        }

        private String renderHistory(boolean[] applied) {
            HistoryPolicy.ParsedHistory h = in.history();
            boolean capped = has(applied, Kind.CAP);
            boolean leadDropped = has(applied, Kind.DROP_LEAD);
            boolean digested = has(applied, Kind.DIGEST);
            boolean lastDropped = has(applied, Kind.DROP_LAST);
            boolean anyTurnDropped = has(applied, Kind.DROP_TURN);
            int linesDropped = count(applied, Kind.DROP_LINE);
            if (!capped && !leadDropped && !digested && !lastDropped && !anyTurnDropped && linesDropped == 0) {
                return h.original();
            }
            if (lastDropped) return in.condensedLine() == null ? "" : in.condensedLine();
            String lead = leadDropped || h.lead() == null ? null
                    : capped ? HistoryPolicy.capQuestionsIn(h.lead(), HistoryPolicy.PAST_QUESTION_CAP) : h.lead();
            if (lead != null && linesDropped > 0) {
                String[] lines = lead.split("\n", -1);
                lead = String.join("\n", List.of(lines).subList(Math.min(linesDropped, lines.length), lines.length)).strip();
            }
            List<HistoryPolicy.Turn> turns = new ArrayList<>();
            int last = h.turns().size() - 1;
            for (int i = 0; i <= last; i++) {
                HistoryPolicy.Turn t = h.turns().get(i);
                if (i < last) {
                    if (turnDropped(applied, i)) continue;
                    turns.add(capped ? new HistoryPolicy.Turn(
                            HistoryPolicy.capQuestion(t.question(), HistoryPolicy.PAST_QUESTION_CAP), t.answer()) : t);
                } else {
                    turns.add(digested ? new HistoryPolicy.Turn(t.question(), in.lastAnswerDigest()) : t);
                }
            }
            return HistoryPolicy.render(lead, h.recentHeader(), turns);
        }

        private Result result(boolean[] applied) {
            List<Integer> kept = new ArrayList<>();
            boolean[] removed = removedDocs(applied);
            for (int i = 0; i < removed.length; i++) if (!removed[i]) kept.add(i);
            int dropped = 0;
            for (int i = 0; i < seq.size(); i++) {
                if (applied[i] && seq.get(i).kind() == Kind.DROP_TURN) dropped++;
            }
            SentPrompt.HistoryCut cut = new SentPrompt.HistoryCut(has(applied, Kind.CAP), has(applied, Kind.DROP_LEAD),
                    dropped, has(applied, Kind.DIGEST), count(applied, Kind.DROP_LINE), has(applied, Kind.DROP_LAST));
            HistoryPolicy.ParsedHistory h = in.history();
            boolean removedAll = !h.isEmpty() && cut.allDropped();
            int highest = 0;
            for (int i = 0; i < seq.size(); i++) if (applied[i]) highest = Math.max(highest, seq.get(i).rung().number);
            return new Result(kept, renderHistory(applied), cut, removedAll, highest, fits(applied));
        }
    }

    /**
     * 이 입력의 조각을 사다리 순서로 늘어놓는다. 아무것도 바꾸지 않는 조각은 넣지 않는다 — 넣으면 같은 프롬프트를 다시 재고,
     * 축소 재시도라면 같은 요청을 되풀이한다.
     */
    private static List<Step> sequence(Input in) {
        HistoryPolicy.ParsedHistory h = in.history();
        int turnCount = h.turns().size();

        List<Step> cap = new ArrayList<>();
        if (capChangesAnything(h)) cap.add(new Step(Rung.CAP_QUESTIONS, Kind.CAP, -1));
        // 칸 2 는 "직전 턴만 남긴다"라 남길 턴이 있을 때만 덩어리를 뺀다. 턴이 없으면(요약 블록뿐, 또는 턴으로 읽히지 않는 이력)
        // 그 덩어리가 이력 전부라 빼는 것은 칸 5 의 일이다.
        List<Step> lastOnly = new ArrayList<>();
        if (h.lead() != null && turnCount > 0) lastOnly.add(new Step(Rung.LAST_TURN_ONLY, Kind.DROP_LEAD, -1));
        for (int i = 0; i < turnCount - 1; i++) lastOnly.add(new Step(Rung.LAST_TURN_ONLY, Kind.DROP_TURN, i));
        List<Step> digest = new ArrayList<>();
        if (turnCount > 0 && in.lastAnswerDigest() != null && !in.lastAnswerDigest().isBlank()) {
            String answer = h.turns().get(turnCount - 1).answer();
            if (answer != null && in.lastAnswerDigest().length() < answer.length()) {
                digest.add(new Step(Rung.DIGEST_LAST_ANSWER, Kind.DIGEST, -1));
            }
        }
        List<Step> dropAll = new ArrayList<>();
        if (turnCount == 0 && h.lead() != null) {
            // 턴 경계가 없는 이력(요약 경로가 요약 블록만 준 경우 등)은 통째로 버리기 전에 앞쪽(오래된) 줄부터 하나씩 뺀다 —
            // 예전 HistoryPolicy.trimToBudget 의 줄 단위 폴백과 같다. 그게 없던 시절에는 예산이 조금 모자랄 뿐인데 대화 맥락
            // 전체를 잃었다. 마지막 줄 하나는 칸 5 의 "전부 뺀다"가 맡는다.
            int lines = h.lead().split("\n", -1).length;
            for (int k = 0; k < lines - 1; k++) dropAll.add(new Step(Rung.DROP_HISTORY, Kind.DROP_LINE, k));
        }
        if (!h.isEmpty()) dropAll.add(new Step(Rung.DROP_HISTORY, Kind.DROP_LAST, -1));

        List<Integer> order = in.docRemovalOrder().stream()
                .filter(i -> i > 0 && i < in.docTokens().size()).distinct().toList();
        int toFloor = Math.max(0, Math.min(order.size(), in.docTokens().size() - Math.max(1, in.floor())));
        List<Step> docsToFloor = new ArrayList<>();
        List<Step> docsToOne = new ArrayList<>();
        for (int k = 0; k < order.size(); k++) {
            (k < toFloor ? docsToFloor : docsToOne).add(new Step(
                    k < toFloor ? Rung.DOCS_TO_FLOOR : Rung.DOCS_TO_ONE, Kind.DOC, order.get(k)));
        }

        List<Step> seq = new ArrayList<>();
        SentPrompt.HistoryCut prev = in.previousCut();
        if (prev == null) {
            seq.addAll(cap);
            seq.addAll(lastOnly);
            seq.addAll(docsToFloor);
            seq.addAll(digest);
            seq.addAll(dropAll);
            seq.addAll(docsToOne);
            return seq;
        }
        // 재시도 — 직전 시도의 이력 절단을 먼저, 넘치는 몫은 문서로(바닥까지), 그다음에야 나머지 이력 칸.
        List<Step> history = new ArrayList<>();
        history.addAll(cap);
        history.addAll(lastOnly);
        history.addAll(digest);
        history.addAll(dropAll);
        List<Step> again = new ArrayList<>();
        List<Step> rest = new ArrayList<>();
        int olderDropped = 0;
        int linesDropped = 0;
        for (Step s : history) {
            boolean inPrevious = switch (s.kind()) {
                case CAP -> prev.questionsCapped();
                case DROP_LEAD -> prev.leadDropped();
                case DROP_TURN -> olderDropped++ < prev.olderTurnsDropped();
                case DIGEST -> prev.lastAnswerDigested();
                case DROP_LINE -> linesDropped++ < prev.leadLinesDropped();
                case DROP_LAST -> prev.allDropped();
                case DOC -> false;
            };
            (inPrevious ? again : rest).add(s);
        }
        seq.addAll(again);
        seq.addAll(docsToFloor);
        seq.addAll(rest);
        seq.addAll(docsToOne);
        return seq;
    }

    private static boolean capChangesAnything(HistoryPolicy.ParsedHistory h) {
        if (h.lead() != null && !HistoryPolicy.capQuestionsIn(h.lead(), HistoryPolicy.PAST_QUESTION_CAP).equals(h.lead())) {
            return true;
        }
        for (int i = 0; i < h.turns().size() - 1; i++) {
            if (h.turns().get(i).question().length() > HistoryPolicy.PAST_QUESTION_CAP) return true;
        }
        return false;
    }
}
