package com.example.ragagent.service;

import com.example.ragagent.model.SentPrompt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §6.30 프롬프트 축소 사다리 — 순수 함수의 골든 테스트.
 *
 * <p>골든 넷은 PLAN §6.30 "같은 상황에서의 결과"의 표다(16k 창 · N 모드 · 스트리밍 — 입력 예산 9,746토큰, 문서 1개 1,500토큰,
 * 짧은 질문의 고정비 649). 턴은 한글로 채워 {@code TokenEstimator} 가 글자 수 그대로 센다(구분자 몇 개가 1~2 토큰을 더한다).
 */
class PromptLadderTest {

    private static final long LIMIT = 9_746;
    private static final long SHORT_FIXED = 649;
    private static final long DOC = 1_500;

    private static String turn(int questionChars, int answerChars) {
        return "Q: " + "가".repeat(questionChars) + "\nA: " + "나".repeat(answerChars);
    }

    private static String history(String... turns) {
        return String.join("\n\n", turns);
    }

    private static PromptLadder.Input input(int docs, long docTokens, long fixed, String history, String digest,
                                            String condensed, SentPrompt.HistoryCut previous) {
        return new PromptLadder.Input(Collections.nCopies(docs, docTokens), PromptLadder.rankRemovalOrder(docs),
                PromptLadder.floor(10, docs), HistoryPolicy.parse(history), digest, condensed, fixed, previous);
    }

    private static PromptLadder.Result fit(PromptLadder.Input in) {
        return PromptLadder.plan(in, LIMIT).at(0, 1);
    }

    @Test
    @DisplayName("바닥 — top-k 의 50%, 올림, 최소 1, 검색된 수를 넘지 않는다")
    void floorRule() {
        assertThat(PromptLadder.floor(10, 10)).isEqualTo(5);
        assertThat(PromptLadder.floor(5, 5)).isEqualTo(3);
        assertThat(PromptLadder.floor(3, 3)).isEqualTo(2);
        assertThat(PromptLadder.floor(1, 1)).isEqualTo(1);
        assertThat(PromptLadder.floor(10, 3)).as("검색된 것이 바닥보다 적으면 전부").isEqualTo(3);
        assertThat(PromptLadder.floor(10, 12)).as("escalation 으로 늘어도 설정값 기준").isEqualTo(5);
        assertThat(PromptLadder.floor(10, 0)).isZero();
    }

    @Nested
    @DisplayName("PLAN §6.30 의 네 예 (16k · N · 스트리밍)")
    class Examples {

        @Test
        @DisplayName("예 1 — 긴 독립 질문: 문서 2 + 최근 2턴 (지금은 문서 1 + 이력 전부)")
        void longStandaloneQuestion() {
            String h = history(turn(8, 540), turn(8, 540), turn(8, 540), turn(8, 540));   // 4 × 550
            PromptLadder.Result r = fit(input(10, DOC, 5_629, h, null, null, null));

            assertThat(r.keptDocs()).containsExactly(0, 1);
            assertThat(HistoryPolicy.countTurns(r.history())).as("되돌려 채운 최근 2턴").isEqualTo(2);
            assertThat(h).endsWith(r.history());
            assertThat(r.highestRung()).isEqualTo(6);
            assertThat(r.fits()).isTrue();
        }

        @Test
        @DisplayName("예 2 — 직전 Direct 턴(요약 있음): 문서 5 + 직전 턴(요약) + 그 앞 턴")
        void followUpAfterDirectWithSummary() {
            String older = turn(8, 490);
            String direct = turn(10, 4_288);
            String digest = "다".repeat(440);
            PromptLadder.Result r = fit(input(10, DOC, SHORT_FIXED, history(older, direct), digest, null, null));

            assertThat(r.keptDocs()).hasSize(5);
            assertThat(r.history()).startsWith(older).endsWith(digest);
            assertThat(r.cut().lastAnswerDigested()).isTrue();
            assertThat(r.cut().olderTurnsDropped()).as("그 앞 턴은 되돌려 채워졌다").isZero();
        }

        @Test
        @DisplayName("예 3 — 직전 Direct 턴(요약 없음): 문서 5 + 직전 턴(앞 1,200자), 그 앞 턴은 자리가 없다")
        void followUpAfterDirectWithoutSummary() {
            String older = turn(8, 490);
            String answer = "나".repeat(5_490);
            String direct = "Q: " + "가".repeat(10) + "\nA: " + answer;
            PromptLadder.Result r = fit(input(10, DOC, SHORT_FIXED, history(older, direct),
                    HistoryPolicy.digest(answer), null, null));

            assertThat(r.keptDocs()).hasSize(5);
            assertThat(HistoryPolicy.countTurns(r.history())).isEqualTo(1);
            assertThat(r.history()).endsWith(HistoryPolicy.digest(answer));
            assertThat(r.cut().olderTurnsDropped()).isEqualTo(1);
        }

        @Test
        @DisplayName("예 4 — 짧은 RAG 턴 둘: 지금과 같다(문서 5 + 이력 전부) — 되돌려 채우기가 없으면 그 앞 턴을 잃는다")
        void shortRagTurnsBackfill() {
            String h = history(turn(8, 490), turn(8, 490));
            PromptLadder.Result r = fit(input(10, DOC, SHORT_FIXED, h, null, null, null));

            assertThat(r.keptDocs()).containsExactly(0, 1, 2, 3, 4);
            assertThat(r.history()).isEqualTo(h);
            assertThat(r.cut()).isEqualTo(SentPrompt.HistoryCut.NONE);
            // 예전 규칙(문서 먼저, 이력은 남은 자리)과 같은 결과다
            assertThat(com.example.ragagent.llm.PromptBudget.fitByPrefix(Collections.nCopies(10, DOC), Long::longValue,
                    SHORT_FIXED + com.example.ragagent.llm.TokenEstimator.estimate(h), LIMIT)).hasSize(5);
        }
    }

    @Test
    @DisplayName("다 들어가면 아무것도 하지 않는다 — 이력은 원문 그대로(바이트 단위)")
    void nothingToDo() {
        String h = "[Conversation Summary]\n요약\n\n[Recent]\n" + history(turn(3, 3), turn(3, 3));
        PromptLadder.Result r = fit(input(3, 100, SHORT_FIXED, h, null, null, null));

        assertThat(r.keptDocs()).containsExactly(0, 1, 2);
        assertThat(r.history()).isSameAs(h);
        assertThat(r.highestRung()).isZero();
    }

    @Test
    @DisplayName("칸 1 — 직전 질문을 뺀 과거 질문만 자른다(답변은 건드리지 않는다)")
    void rungOneCapsPastQuestionsOnly() {
        String longQ = "Q: " + "로".repeat(3_000) + "\nA: 답";
        String lastQ = "Q: " + "마".repeat(3_000) + "\nA: 답";
        String h = history(longQ, lastQ);
        // 문서 1개 + 이력 6,000 이 넘치고, 과거 질문을 자르면 들어가는 크기
        PromptLadder.Result r = PromptLadder.plan(input(1, 100, SHORT_FIXED, h, null, null, null), 4_500).at(0, 1);

        assertThat(r.cut().questionsCapped()).isTrue();
        assertThat(r.history()).contains("로".repeat(500) + "…(생략)").contains("마".repeat(3_000));
        assertThat(r.highestRung()).isEqualTo(1);
    }

    @Test
    @DisplayName("칸 1 — LLM 없이 조립된 요약 블록 안의 질문 줄도 자른다")
    void rungOneCapsQuestionsInsideTheSummaryBlock() {
        String lead = "[Conversation Summary]\nQ: " + "로".repeat(3_000) + "\nA: 요약";
        String h = lead + "\n\n[Recent]\n" + turn(3, 3);
        PromptLadder.Result r = PromptLadder.plan(input(1, 100, SHORT_FIXED, h, null, null, null), 1_500).at(0, 1);

        assertThat(r.history()).startsWith("[Conversation Summary]\nQ: " + "로".repeat(500) + "…(생략)\nA: 요약")
                .endsWith("[Recent]\n" + turn(3, 3));
    }

    @Test
    @DisplayName("칸 2 — 요약 블록을 먼저, 그다음 오래된 턴부터 — 직전 턴은 남는다")
    void rungTwoDropsSummaryThenOldestTurns() {
        String h = "[Conversation Summary]\n" + "요".repeat(1_000) + "\n\n[Recent]\n"
                + history(turn(3, 1_000), turn(3, 1_000));
        // 고정 649 + 문서 1개(100) + 이력 3,000 이 안 들어가고 직전 턴만 들어가는 예산
        PromptLadder.Result r = PromptLadder.plan(input(1, 100, SHORT_FIXED, h, null, null, null), 1_800).at(0, 1);

        assertThat(r.cut().leadDropped()).isTrue();
        assertThat(r.cut().olderTurnsDropped()).isEqualTo(1);
        assertThat(r.history()).isEqualTo("[Recent]\n" + turn(3, 1_000));
    }

    @Test
    @DisplayName("불변식 — 이력이 남아 있는 동안 문서는 바닥 아래로 내려가지 않는다")
    void docsStayAtTheFloorWhileHistoryRemains() {
        // 직전 턴 하나가 커서 바닥(5)과 함께 못 들어간다 — 이력이 먼저 빠지고, 남는 자리에 문서가 되돌아온다
        String h = turn(10, 4_000);
        PromptLadder.Result r = fit(input(10, DOC, SHORT_FIXED, h, null, null, null));

        assertThat(r.cut().allDropped()).isTrue();
        assertThat(r.keptDocs()).as("이력을 뺀 자리에 문서가 바닥 위로 되돌아온다").hasSize(6);
        assertThat(r.history()).isEmpty();
    }

    @Test
    @DisplayName("칸 5 — 후속 질문이면 이력 대신 다시 쓴 질문 한 줄을 싣는다")
    void rungFiveSubstitutesTheCondensedQuestion() {
        String line = "(다시 쓴 질문)";
        PromptLadder.Result r = fit(input(10, DOC, SHORT_FIXED, turn(10, 9_000), null, line, null));

        assertThat(r.cut().allDropped()).isTrue();
        assertThat(r.historyRemoved()).isTrue();
        assertThat(r.history()).isEqualTo(line);
    }

    @Test
    @DisplayName("턴 경계가 없는 이력은 통째로 버리기 전에 앞쪽 줄부터 뺀다")
    void unstructuredHistoryIsTrimmedByLine() {
        String h = "오래된요약" + "옛".repeat(2_000) + "\n최근요약줄";
        PromptLadder.Result r = PromptLadder.plan(input(1, 100, SHORT_FIXED, h, null, null, null), 1_000).at(0, 1);

        assertThat(r.history()).isEqualTo("최근요약줄");
        assertThat(r.cut().leadLinesDropped()).isEqualTo(1);
        assertThat(r.cut().allDropped()).isFalse();
    }

    @Test
    @DisplayName("아무리 줄여도 안 들어가면 최상위 문서만으로 보낸다(칸 7)")
    void sendsTheTopDocumentWhenNothingFits() {
        PromptLadder.Result r = PromptLadder.plan(input(10, DOC, SHORT_FIXED, turn(3, 3), null, null, null), 100).at(0, 1);

        assertThat(r.keptDocs()).containsExactly(0);
        assertThat(r.fits()).isFalse();
    }

    @Nested
    @DisplayName("축소 재시도 — 사전 예산과 같은 사다리의 다음 걸음")
    class ShrinkLevels {

        @Test
        @DisplayName("창 모름 — 사전 축소는 없고, 걸음은 이력 칸 하나 또는 문서 shrink-step 개씩")
        void unknownWindowWalksTheLadder() {
            String h = history(turn(3, 3), turn(3, 3), turn(3, 3));
            PromptLadder.Plan plan = PromptLadder.plan(input(8, DOC, SHORT_FIXED, h, null, null, null), -1);

            assertThat(plan.at(0, 1).keptDocs()).hasSize(8);
            assertThat(plan.at(0, 1).history()).isEqualTo(h);
            PromptLadder.Result one = plan.at(1, 1);
            assertThat(one.keptDocs()).as("칸 2 가 먼저 — 문서는 그대로").hasSize(8);
            assertThat(one.history()).isEqualTo(turn(3, 3));
            assertThat(plan.at(2, 1).keptDocs()).hasSize(7);
            assertThat(plan.at(4, 1).keptDocs()).as("바닥(5)까지").hasSize(5);
            assertThat(plan.at(5, 1).history()).as("그다음 이력 전부").isEmpty();
            assertThat(plan.at(5, 1).keptDocs()).hasSize(5);
            assertThat(plan.at(6, 1).keptDocs()).hasSize(4);
            assertThat(plan.at(3, 2).keptDocs()).as("shrink-step=2 면 두 개씩(바닥에서 멈춘다)").hasSize(5);
            // 이력 칸 2개(칸 2·5) + 문서 걸음 7개 중 5개까지
            assertThat(plan.maxLevel(1)).isEqualTo(7);
        }

        @Test
        @DisplayName("문서 1개 · 이력 없음이면 줄일 것이 없다 — 같은 요청을 되풀이하지 않는다")
        void nothingLeftToShrink() {
            assertThat(PromptLadder.plan(input(1, DOC, SHORT_FIXED, "", null, null, null), -1).maxLevel(1)).isZero();
        }

        @Test
        @DisplayName("창을 알면 되돌려 채운 것을 먼저 다시 빼고 그다음 칸으로 내려간다")
        void overflowDropsTheBackfillThenGoesDeeper() {
            String h = history(turn(8, 490), turn(8, 490));   // 예 4 — 0단계는 문서 5 + 이력 전부
            PromptLadder.Plan plan = PromptLadder.plan(input(10, DOC, SHORT_FIXED, h, null, null, null), LIMIT);

            assertThat(plan.at(0, 1).history()).isEqualTo(h);
            PromptLadder.Result one = plan.at(1, 1);
            assertThat(one.keptDocs()).hasSize(5);
            assertThat(one.history()).as("되돌려 채운 그 앞 턴을 다시 빼고, 다음 칸(5)으로").isEmpty();
            assertThat(plan.at(2, 1).keptDocs()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("검증 실패 재시도 (§6.30 C·D)")
    class Retry {

        /** 문서 1,000토큰 — 첫 시도는 그 앞 턴을 빼고 문서 8개, 이력을 지키면 문서 7개가 들어가는 크기. */
        private PromptLadder.Input retryInput(SentPrompt.HistoryCut previous) {
            String h = history(turn(10, 1_490), turn(10, 90));
            return new PromptLadder.Input(Collections.nCopies(11, 1_000L), PromptLadder.rankRemovalOrder(11),
                    PromptLadder.floor(10, 11), HistoryPolicy.parse(h), null, null, SHORT_FIXED, previous);
        }

        @Test
        @DisplayName("C — 재시도는 이력을 직전 시도보다 더 줄이지 않는다: 넘치는 몫은 문서가 흡수한다(바닥까지)")
        void retryKeepsTheHistory() {
            PromptLadder.Result first = PromptLadder.plan(retryInput(null), LIMIT).at(0, 1);
            assertThat(first.cut().olderTurnsDropped()).as("전제 — 첫 시도라면 그 앞 턴을 뺀다").isEqualTo(1);
            assertThat(first.keptDocs()).hasSize(8);

            PromptLadder.Result retry = PromptLadder.plan(retryInput(SentPrompt.HistoryCut.NONE), LIMIT).at(0, 1);
            assertThat(retry.cut()).isEqualTo(SentPrompt.HistoryCut.NONE);
            assertThat(retry.keptDocs()).hasSize(7);
        }

        @Test
        @DisplayName("C — 직전 시도가 줄인 만큼은 다시 줄인다(그보다 더 줄이지 않을 뿐)")
        void retryStartsFromThePreviousCut() {
            SentPrompt.HistoryCut previous = new SentPrompt.HistoryCut(false, false, 1, false, 0, false);
            PromptLadder.Result retry = PromptLadder.plan(retryInput(previous), LIMIT).at(0, 1);

            assertThat(retry.cut().olderTurnsDropped()).isEqualTo(1);
            assertThat(retry.keptDocs()).hasSize(8);
        }

        @Test
        @DisplayName("D — 직전에 봤지만 안 쓴 것 → 새로 들어온 것 → 쓴 것 순으로 뺀다(1순위는 남긴다)")
        void retryRemovalOrder() {
            List<String> keys = List.of("a", "b", "c", "d", "e");
            assertThat(PromptLadder.retryRemovalOrder(keys, Set.of("a", "b", "c", "d"), Set.of("b")))
                    .containsExactly(3, 2, 4, 1);
            assertThat(PromptLadder.retryRemovalOrder(keys, Set.of("a", "b", "c", "d"), Set.of()))
                    .as("보고가 없으면(모름) 직전에 실렸던 문서 전부가 첫 무리").containsExactly(3, 2, 1, 4);
        }

        @Test
        @DisplayName("D — 재시도가 가져온 새 문서는 순위가 낮아도 나중에 빠진다")
        void newDocumentsAreDroppedLast() {
            // 5개 중 4개만 들어간다 — 순위로만 빼면 새로 들어온 e(최하위)가 빠진다
            List<Integer> order = PromptLadder.retryRemovalOrder(List.of("a", "b", "c", "d", "e"),
                    Set.of("a", "b", "c", "d"), Set.of("a", "b"));
            PromptLadder.Input in = new PromptLadder.Input(Collections.nCopies(5, 1_000L), order, 1,
                    HistoryPolicy.parse(""), null, null, 0, SentPrompt.HistoryCut.NONE);

            assertThat(PromptLadder.plan(in, 4_000).at(0, 1).keptDocs()).containsExactly(0, 1, 2, 4);
        }
    }
}
