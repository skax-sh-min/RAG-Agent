package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 20,000자 절단이 코드 펜스 짝을 깨지 않는가 (PLAN §6.24 Step 3-c).
 *
 * <p>깨진 답변은 화면에서 멀쩡해 보인다. 대가는 나중에 <b>다른 문서에서</b> 치러진다 — 그 답변이
 * 문서 내보내기나 재색인을 타면 {@code MarkdownCorrectionService.normalizeCodeBlocks()} 가 펜스
 * 짝을 확정할 수 없다며 <b>그 문서 전체</b>의 언어 태그·코드 정리를 건너뛴다. 그래서 단언은
 * "보기 좋은가"가 아니라 <b>그 가드가 실제로 통과하는가</b>로 건다.
 *
 * <p><b>N 모드로 돌린다.</b> S 로 돌리면 {@code SummaryOnlyGuard} 가 헤딩 없는 답변에
 * {@code "## 요약"} 줄을 앞에 붙여 절단 경계를 밀어버려, 테스트가 노린 지점이 아닌 곳에서 잘린다
 * (실제로 그렇게 만들었다가 두 케이스가 조용히 무의미해졌다). 그래서 각 테스트는 자기가 노리는
 * 경계를 <b>전제로 먼저 단언한다</b> — 상한이나 앞단 가공이 바뀌면 그 줄이 먼저 알려준다.
 */
class AnswerTruncationFenceTest {

    private static final int MAX_ANSWER_LEN = 20_000;
    private static final String NOTICE = "…(응답이 너무 길어 잘렸습니다)";
    private static final String EVAL_OK = "{\"sufficient\":true,\"grounded\":true}";

    private LlmRouter llmRouter;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        AppProperties props = new AppProperties(
                "./data", 2, 800, 100, 100, 7, 0.0, true, 0, false,
                true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        service = new AnswerService(llmRouter, props, messageSource);
    }

    private String answerFor(String raw) {
        when(llmRouter.executeGatedWithUsage(eq(TaskType.TEXT), eq(RoutingMode.COST_FIRST), any()))
                .thenReturn(new LlmRouter.LlmResult(raw, 0, 0),
                            new LlmRouter.LlmResult(EVAL_OK, 0, 0));
        when(llmRouter.findProviderName(any(), any())).thenReturn("local");
        AgentState state = AgentState.of("질문", "v1", "t1", "", RoutingMode.COST_FIRST)
                .toBuilder().responseMode(ResponseMode.N).build();
        return service.execute(state).answer();
    }

    private static int fenceLines(String text) {
        int n = 0;
        for (String line : text.split("\n", -1)) if (line.stripLeading().startsWith("```")) n++;
        return n;
    }

    private static int fenceMarks(String text) {
        int n = 0;
        for (int i = text.indexOf("```"); i >= 0; i = text.indexOf("```", i + 3)) n++;
        return n;
    }

    /** {@code normalizeCodeBlocks()} 가 문서를 거부하지 않는 두 조건. */
    private static void assertFencePairingIsResolvable(String answer) {
        assertThat(fenceLines(answer) % 2)
                .as("펜스 줄 수가 홀수다 — normalizeCodeBlocks() 가 문서 전체를 건너뛴다")
                .isZero();
        assertThat(fenceLines(answer))
                .as("줄 기반 세기와 ``` 총 개수가 어긋난다(줄 중간 펜스) — 같은 가드에 걸린다")
                .isEqualTo(fenceMarks(answer));
    }

    @Test
    @DisplayName("코드 블록 한가운데서 잘려도 펜스는 닫힌 채로 끝난다")
    void truncationInsideAFenceClosesIt() {
        String raw = "## 구현\n\n```java\n" + "System.out.println(1);\n".repeat(3000) + "```\n";
        // 전제: 상한이 여는 펜스와 닫는 펜스 사이에 떨어진다.
        assertThat(raw.length()).isGreaterThan(MAX_ANSWER_LEN);
        assertThat(fenceLines(raw.substring(0, MAX_ANSWER_LEN))).isEqualTo(1);

        String answer = answerFor(raw);

        assertFencePairingIsResolvable(answer);
        // 안내는 코드가 아니므로 펜스 '바깥'이어야 한다 — 마지막 펜스보다 뒤에 온다.
        assertThat(answer).endsWith(NOTICE);
        assertThat(answer.lastIndexOf("```")).isLessThan(answer.indexOf(NOTICE));
    }

    @Test
    @DisplayName("펜스가 이미 닫힌 지점에서 잘리면 닫는 펜스를 덧붙이지 않는다")
    void truncationOutsideAFenceAddsNothing() {
        String raw = "```java\nint a = 1;\n```\n" + "설명 문장입니다.\n".repeat(4000);
        assertThat(raw.length()).isGreaterThan(MAX_ANSWER_LEN);
        assertThat(fenceLines(raw.substring(0, MAX_ANSWER_LEN))).isEqualTo(2);

        String answer = answerFor(raw);

        assertFencePairingIsResolvable(answer);
        assertThat(fenceLines(answer)).isEqualTo(2);   // 원래의 여닫이 한 쌍 그대로
        assertThat(answer).endsWith(NOTICE);
    }

    @Test
    @DisplayName("여는 펜스 한가운데서 잘리면 잘린 여는 펜스를 남기지 않는다")
    void truncationInsideAFenceOpenerDropsThePartialLine() {
        // 절단이 스스로 만들 수 있는 결함은 홀수 펜스뿐이다(줄 중간 펜스는 원문에 이미 있어야
        // 성립한다 — 절단은 뒤에서 덜어낼 뿐이다). 그 홀수를 만드는 현실적인 자리가 여기다:
        // 상한이 "```java" 한가운데 떨어지면 "```ja" 라는 잘린 여는 펜스가 남는다.
        String raw = "가".repeat(MAX_ANSWER_LEN - 5) + "\n```java\nx\n```\n";
        String naiveCut = raw.substring(0, MAX_ANSWER_LEN);
        assertThat(naiveCut).endsWith("\n```j");
        assertThat(fenceLines(naiveCut)).isEqualTo(1);   // 홀수 — 가드가 문서 전체를 건너뛴다

        String answer = answerFor(raw);

        assertFencePairingIsResolvable(answer);
        // 짝만 맞추고 끝냈다면 "```ja" 에 닫는 펜스가 붙어 언어 태그가 깨진 빈 블록이 남는다.
        assertThat(answer).doesNotContain("```");
        assertThat(answer).endsWith(NOTICE);
    }

    @Test
    @DisplayName("줄바꿈이 없는 거대한 한 줄이면 잘린 끝의 백틱만 걷어낸다")
    void singleGiantLineStripsTrailingBackticksOnly() {
        String raw = "가".repeat(MAX_ANSWER_LEN - 2) + "```" + "나".repeat(100);
        // 전제: 자를 줄 경계가 없고, 순진하게 자르면 문장 중간에 백틱 두 개가 매달린다.
        assertThat(raw.indexOf('\n') < 0).isTrue();
        assertThat(raw.substring(0, MAX_ANSWER_LEN)).endsWith("``");

        String answer = answerFor(raw);

        assertFencePairingIsResolvable(answer);
        assertThat(answer).doesNotContain("``");
    }

    @Test
    @DisplayName("상한 이하 답변은 한 글자도 건드리지 않는다")
    void shortAnswerIsUntouched() {
        String raw = "## 요약\n짧은 답변";
        assertThat(answerFor(raw)).isEqualTo(raw);
    }
}
