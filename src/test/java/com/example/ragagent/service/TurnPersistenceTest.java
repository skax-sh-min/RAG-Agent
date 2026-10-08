package com.example.ragagent.service;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.model.VerificationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 턴이 끝났을 때 저장되는 여섯 가지 — 채팅 진입점 <b>둘</b>이 공유하는 절차.
 *
 * <p>이 클래스가 생기기 전에는 {@code AgentService} 와 {@code StreamingAgentService} 가 같은
 * 여섯 줄을 복제하고 있었고, 그 대가는 이미 치렀다: 저장할 응답 모드를 요청의 것에서 결과의
 * 것으로 바꿔야 했을 때 같은 수정을 양쪽에 해야 했다. 한쪽만 고쳤다면 스트리밍으로 온 턴만
 * 조용히 옛 값으로 저장됐을 것이고, 화면에는 아무 차이도 안 보인다.
 */
class TurnPersistenceTest {

    private MemoryService memoryService;
    private ConversationSummarizerService summarizer;
    private QuestionReuseService questionReuse;
    private TurnPersistence persistence;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        summarizer = mock(ConversationSummarizerService.class);
        questionReuse = mock(QuestionReuseService.class);
        when(memoryService.addTurn(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), any(), anyInt(), anyString(), any(), anyBoolean()))
                .thenReturn(42L);
        persistence = new TurnPersistence(memoryService, summarizer, questionReuse);
    }

    private static TurnPersistence.Turn turn() {
        return new TurnPersistence.Turn("u1", "t1", "질문", List.of("인프라"),
                false, Locale.KOREAN, "2026-09-08 10:00:00", 1_234L);
    }

    private static AgentState answered(ResponseMode mode) {
        return AgentState.of("질문", "v1", "t1", "u1", RoutingMode.COST_FIRST)
                .toBuilder().answer("답변입니다.").responseMode(mode).build();
    }

    @Test
    @DisplayName("여섯 가지가 이 순서로 저장된다 — 기록 → 이미지 → 진단 → 검증 → 재사용 출처 → 요약")
    void save_writesTheSixThingsInOrder() {
        Long turnId = persistence.save(turn(), answered(ResponseMode.N));

        assertThat(turnId).isEqualTo(42L);
        InOrder order = inOrder(memoryService, questionReuse, summarizer);
        order.verify(memoryService).addTurn(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), any(), anyInt(), anyString(), any(),
                anyBoolean());
        order.verify(memoryService).saveTurnImageRefs(eq(42L), eq("u1"), eq("t1"), any());
        order.verify(memoryService).saveRetrievalMetrics(eq(42L), any());
        order.verify(memoryService).saveVerification(eq(42L), any());
        order.verify(questionReuse).recordTurnSources(eq(42L), eq("u1"), eq("t1"), any(), any());
        order.verify(summarizer).precomputeAfterTurn("u1", "t1", 42L, Locale.KOREAN);
    }

    /**
     * 저장되는 모드는 <b>결과</b>의 것이다 — 그래프가 바꾸는 경우가 있고(검색 0건 → S), 그 값이
     * 버블의 두 글자 표기와 좋아요 가능 여부를 정한다. 검증 스냅샷도 같은 값을 읽으므로 둘이
     * 갈리면 한 턴이 자기 모드에 대해 두 가지를 말하게 된다.
     */
    @Test
    @DisplayName("응답 모드는 결과에서 읽는다 — 기록과 검증 스냅샷이 같은 값을 본다")
    void save_takesTheModeFromTheResult() {
        persistence.save(turn(), answered(ResponseMode.S));

        ArgumentCaptor<String> mode = ArgumentCaptor.forClass(String.class);
        verify(memoryService).addTurn(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), any(), anyInt(), mode.capture(), any(),
                anyBoolean());
        assertThat(mode.getValue()).isEqualTo("S");

        ArgumentCaptor<VerificationSnapshot> snap = ArgumentCaptor.forClass(VerificationSnapshot.class);
        verify(memoryService).saveVerification(anyLong(), snap.capture());
        assertThat(snap.getValue().generative()).isEqualTo(ResponseMode.S.generative());
    }

    @Test
    @DisplayName("답변이 없으면 아무것도 저장하지 않고 null 을 돌려준다")
    void save_blankAnswer_persistsNothing() {
        AgentState empty = AgentState.of("질문", "v1", "t1", "u1", RoutingMode.COST_FIRST)
                .toBuilder().answer("   ").build();

        assertThat(persistence.save(turn(), empty)).isNull();

        verifyNoInteractions(memoryService, summarizer, questionReuse);
    }

    /** 질문 재사용을 배선하지 않은 구성에서는 그 단계만 빠지고 나머지는 그대로 저장된다. */
    @Test
    @DisplayName("questionReuseService 가 없어도 나머지 다섯 가지는 저장된다")
    void save_withoutQuestionReuse_stillPersistsTheRest() {
        TurnPersistence noReuse = new TurnPersistence(memoryService, summarizer, null);

        assertThat(noReuse.save(turn(), answered(ResponseMode.N))).isEqualTo(42L);

        verify(memoryService).saveVerification(anyLong(), any());
        verify(summarizer).precomputeAfterTurn(anyString(), anyString(), anyLong(), any());
        verify(questionReuse, never()).recordTurnSources(anyLong(), anyString(), anyString(), any(), any());
    }
}
