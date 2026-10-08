package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ResponseMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.ai.document.Document;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검색이 0건일 때의 답변 — <b>LLM 을 부르지 않는다</b>.
 *
 * <p>예전에는 문서가 없는 프롬프트로 답변을 받고, 검증이 당연히 {@code sufficient=false} 를 내고,
 * 그래프가 재검색으로 돌아가고, 검색이 같은 이유로 다시 비어 오는 왕복을 {@code 1 + maxRetryCount}
 * 번 돌았다 — 결과가 정해져 있는 호출에 한 턴의 예산을 전부 태우고 끝에 미검증 배지가 붙었다.
 * 더 나쁜 것은 그 사이 모델이 문서 없이 무언가를 지어낼 수 있었다는 점이다.
 *
 * <p>여기서 고정하는 것: 호출이 <b>0회</b>라는 것, 나온 답변이 정형 문구라는 것, 그리고 그 턴이
 * {@code RS} + 미검증으로 <b>정직하게 표시</b>된다는 것.
 */
@ResourceLock("global-state")
class AnswerNoDocumentsTest {

    private static final String CANNED = "## 요약\n문서에 관련 정보가 확인되지 않습니다.";
    private static final String REASON = "검색된 문서가 없어 검증을 수행하지 않았습니다.";

    private LlmRouter llmRouter;
    private AnswerService service;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        when(llmRouter.findProviderName(any(), any())).thenReturn("lm");

        AppProperties props = mock(AppProperties.class);
        when(props.maxRetryCount()).thenReturn(2);
        when(props.llmSafe()).thenReturn(new AppProperties.LlmConfig(
                List.of(), 2, 10, 180, "COST_FIRST", 3, 20, 0.0, 0.1, 0.0, 0.7, true, 8000, 1, true));

        MessageSource messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("prompt");
        when(messageSource.getMessage(eq("chat.answer.no-documents"), any(), any(Locale.class)))
                .thenReturn(CANNED);
        when(messageSource.getMessage(eq("chat.answer.no-documents.reason"), any(), any(Locale.class)))
                .thenReturn(REASON);

        service = new AnswerService(llmRouter, props, messageSource, new ProviderContextWindows());
    }

    private static AgentState state(ResponseMode mode, int docCount) {
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= docCount; i++) docs.add(new Document("문서" + i + " 본문입니다."));
        return AgentState.of("배포 절차가 어떻게 되나요", "v1", "t1", "u1", RoutingMode.COST_FIRST)
                .toBuilder().retrievedDocs(docs).responseMode(mode).build();
    }

    /** 토큰과 노드 진입만 받아 적는 리스너 — 스트리밍 전송 경로가 평소와 같은지 보기 위한 것. */
    private static final class RecordingListener implements GraphListener {
        final List<String> nodes = new ArrayList<>();
        final StringBuilder tokens = new StringBuilder();
        @Override public void onNodeEnter(String nodeName) { nodes.add(nodeName); }
        @Override public void onToken(String text) { tokens.append(text); }
    }

    // ── LLM 호출 0회 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("검색 0건 — 답변·검증 어느 쪽도 LLM 을 부르지 않는다")
    void noDocuments_callsNoLlmAtAll() {
        service.execute(state(ResponseMode.N, 0));

        verify(llmRouter, never()).executeGatedWithUsage(any(), any(), any());
        verify(llmRouter, never()).executeWithTracking(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("검색 0건 — 정형 응답을 그대로 답변으로 쓴다")
    void noDocuments_usesTheCannedAnswer() {
        AgentState out = service.execute(state(ResponseMode.N, 0));

        assertThat(out.answer()).isEqualTo(CANNED);
        assertThat(out.totalInputTokens()).isZero();
        assertThat(out.totalOutputTokens()).isZero();
    }

    // ── 턴 표시 ───────────────────────────────────────────────────────────────

    /**
     * 실제로 만들어진 것이 {@code ## 요약} 한 줄짜리 답변이라 성격이 곧 S 다. 저장된 이 값이
     * 버블의 두 글자 표기(검색 축 R + 성격 S = {@code RS})를 정한다.
     */
    @Test
    @DisplayName("검색 0건 — 응답 모드가 S 로 남아 버블 표기가 RS 가 된다")
    void noDocuments_downgradesTheModeToS() {
        assertThat(service.execute(state(ResponseMode.N, 0)).responseMode()).isEqualTo(ResponseMode.S);
        assertThat(service.execute(state(ResponseMode.C, 0)).responseMode()).isEqualTo(ResponseMode.S);
    }

    /**
     * {@code grounded=null} 이면 배지가 아예 안 뜬다({@code VerificationSnapshot.verdictLabel()}) —
     * 화면에서 평범한 답변과 구분되지 않는다. 검증을 <b>못 한</b> 것이 아니라 <b>할 근거가 없는</b>
     * 답변이므로 미검증으로 표시하고 사유를 툴팁에 싣는다.
     */
    @Test
    @DisplayName("검색 0건 — 미검증 배지가 뜨고 사유가 함께 남는다")
    void noDocuments_isMarkedUnverifiedWithAReason() {
        AgentState out = service.execute(state(ResponseMode.N, 0));

        assertThat(out.grounded()).isFalse();
        assertThat(out.evalReason()).isEqualTo(REASON);
    }

    /**
     * 재시도해도 검색은 같은 이유로 다시 비어서 돌아온다 — 그래프가 RETRIEVAL 로 되돌아가면
     * 그 왕복을 그대로 반복한다. S 는 {@code skipsVerification()} 이라 CRITIC 도 지나지 않는다.
     */
    @Test
    @DisplayName("검색 0건 — 재시도를 요청하지 않고, 모드 성질상 CRITIC 도 건너뛴다")
    void noDocuments_doesNotAskForARetry() {
        AgentState out = service.execute(state(ResponseMode.N, 0));

        assertThat(out.needsRetry()).isFalse();
        assertThat(out.responseMode().skipsVerification())
                .as("S 라서 그래프가 ANSWER 다음에 FINALIZE 로 간다")
                .isTrue();
    }

    // ── 스트리밍 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스트리밍 — 평소와 같은 경로(answer 노드 진입 + 토큰)로 내보내 클라이언트 분기가 필요 없다")
    void noDocuments_streamsThroughTheOrdinaryPath() {
        RecordingListener listener = new RecordingListener();

        AgentState out = service.executeStreaming(state(ResponseMode.N, 0), listener);

        assertThat(listener.nodes).contains("answer");
        assertThat(listener.tokens.toString()).isEqualTo(CANNED);
        assertThat(out.answer()).isEqualTo(CANNED);
        verify(llmRouter, never()).executeGatedWithUsage(any(), any(), any());
    }

    // ── 회귀 가드 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("문서가 하나라도 있으면 이 경로를 타지 않는다 (평소대로 LLM 을 부른다)")
    void withDocuments_takesTheNormalPath() {
        AtomicInteger calls = new AtomicInteger();
        when(llmRouter.executeGatedWithUsage(any(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("stop here — 평소 경로로 들어갔다는 사실만 확인한다");
        });

        assertThatThrownBy(() -> service.execute(state(ResponseMode.N, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).as("문서가 있으면 답변 호출이 실제로 나간다").isPositive();
    }
}
