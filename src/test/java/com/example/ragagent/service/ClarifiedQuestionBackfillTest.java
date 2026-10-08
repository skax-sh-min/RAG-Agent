package com.example.ragagent.service;

import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.repository.QuestionReuseRepository;
import com.example.ragagent.repository.QuestionReuseRepository.BackfillTarget;
import com.example.ragagent.service.ClarifiedQuestionBackfill.State;
import com.example.ragagent.service.ClarifiedQuestionBackfill.Status;
import com.example.ragagent.service.PostAnswerService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 과거 질문 다듬기 일괄 처리({@link ClarifiedQuestionBackfill}) — 최신순으로 끝까지 가는가, 멈추라면 멈추는가,
 * 채팅에 양보하는가, 설정이 꺼지거나 LLM 이 죽어 있으면 멈추는가. 양보 시간은 테스트용 생성자로 줄인다.
 */
class ClarifiedQuestionBackfillTest {

    private final QuestionReuseRepository repository = mock(QuestionReuseRepository.class);
    private final PostAnswerService postAnswer = mock(PostAnswerService.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final LlmRouter router = mock(LlmRouter.class);
    private ClarifiedQuestionBackfill backfill;

    @BeforeEach
    void setUp() {
        when(settings.clarifiedQuestionEnabled()).thenReturn(true);
        when(router.localTier1Concurrency()).thenReturn(Optional.of(new LlmRouter.ConcurrencySnapshot(0, 3)));
        backfill = new ClarifiedQuestionBackfill(repository, postAnswer, settings, router,
                Duration.ofMillis(30), Duration.ofMillis(5));
    }

    private static BackfillTarget target(long id) {
        return new BackfillTarget(id, "u1", "t1", "질문 " + id, "답변 " + id);
    }

    private void outcome(long turnId, Outcome outcome) {
        when(postAnswer.backfill(eq(turnId), anyString(), anyString(), anyString(), anyString())).thenReturn(outcome);
    }

    private static void awaitTrue(Supplier<Boolean> condition) throws InterruptedException {
        for (int i = 0; i < 400 && !condition.get(); i++) Thread.sleep(5);
        assertThat(condition.get()).as("조건이 2초 안에 참이 되지 않았다").isTrue();
    }

    @Test
    @DisplayName("커서 아래로 묶음을 끝까지 최신순으로 처리하고 완료한다 — 결과별로 센다")
    void processesBatchesNewestFirstUntilNothingIsLeft() throws Exception {
        when(repository.findClarifyBackfillTargets(Long.MAX_VALUE, ClarifiedQuestionBackfill.BATCH))
                .thenReturn(List.of(target(9), target(5)));
        when(repository.findClarifyBackfillTargets(5L, ClarifiedQuestionBackfill.BATCH)).thenReturn(List.of(target(2)));
        when(repository.findClarifyBackfillTargets(2L, ClarifiedQuestionBackfill.BATCH)).thenReturn(List.of());
        outcome(9, Outcome.CLARIFIED);
        outcome(5, Outcome.KEPT_ORIGINAL);
        outcome(2, Outcome.FAILED);

        backfill.start();
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();

        Status status = backfill.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.running()).isFalse();
        assertThat(status.processed()).isEqualTo(3);
        assertThat(status.clarified()).isEqualTo(1);
        assertThat(status.keptOriginal()).isEqualTo(1);
        assertThat(status.failed()).isEqualTo(1);
        assertThat(status.finishedAt()).isNotNull();
        InOrder order = inOrder(postAnswer);
        order.verify(postAnswer).backfill(eq(9L), eq("u1"), eq("t1"), eq("질문 9"), eq("답변 9"));
        order.verify(postAnswer).backfill(eq(5L), anyString(), anyString(), anyString(), anyString());
        order.verify(postAnswer).backfill(eq(2L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("멈추라고 하면 지금 한 건을 끝낸 뒤 멈춘다 — 그동안 상태는 '멈추는 중'")
    void stopsAfterTheCurrentItem() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findClarifyBackfillTargets(anyLong(), anyInt()))
                .thenReturn(List.of(target(9), target(5), target(2)));
        when(postAnswer.backfill(eq(9L), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            inCall.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Outcome.CLARIFIED;
        });

        backfill.start();
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();
        Status stopping = backfill.stop();
        assertThat(stopping.state()).isEqualTo(State.STOPPING);
        assertThat(stopping.running()).isTrue();
        release.countDown();
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();

        assertThat(backfill.status().state()).isEqualTo(State.STOPPED);
        assertThat(backfill.status().processed()).isEqualTo(1);
        verify(postAnswer, times(1)).backfill(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("채팅이 바쁘면 조용해질 때까지 기다린다 — 그동안 호출하지 않고 상태는 '채팅을 기다리는 중'")
    void yieldsToChat() throws Exception {
        AtomicBoolean busy = new AtomicBoolean(true);
        when(router.localTier1Concurrency()).thenAnswer(inv ->
                Optional.of(new LlmRouter.ConcurrencySnapshot(busy.get() ? 1 : 0, 3)));
        when(repository.findClarifyBackfillTargets(Long.MAX_VALUE, ClarifiedQuestionBackfill.BATCH))
                .thenReturn(List.of(target(9)));
        when(repository.findClarifyBackfillTargets(9L, ClarifiedQuestionBackfill.BATCH)).thenReturn(List.of());
        outcome(9, Outcome.CLARIFIED);

        backfill.start();
        awaitTrue(() -> backfill.status().state() == State.WAITING_FOR_CHAT);
        Thread.sleep(50);
        verify(postAnswer, never()).backfill(anyLong(), anyString(), anyString(), anyString(), anyString());

        busy.set(false);
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();
        assertThat(backfill.status().state()).isEqualTo(State.DONE);
        verify(postAnswer).backfill(eq(9L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("질문 다듬기 설정이 꺼져 있으면 시작하지 않고, 도는 중에 꺼지면 멈춘다")
    void respectsTheClarifyingSwitch() throws Exception {
        when(settings.clarifiedQuestionEnabled()).thenReturn(false);
        Status refused = backfill.start();
        assertThat(refused.state()).isEqualTo(State.DISABLED);
        assertThat(refused.running()).isFalse();
        verifyNoInteractions(postAnswer);

        when(settings.clarifiedQuestionEnabled()).thenReturn(true, true, false);   // 시작 · 첫 턴 · 둘째 턴
        when(repository.findClarifyBackfillTargets(anyLong(), anyInt())).thenReturn(List.of(target(9), target(5)));
        outcome(9, Outcome.CLARIFIED);
        backfill.start();
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();

        assertThat(backfill.status().state()).isEqualTo(State.DISABLED);
        assertThat(backfill.status().processed()).isEqualTo(1);
        verify(postAnswer, never()).backfill(eq(5L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("연달아 다섯 번 실패하면 멈춘다 — LLM 이 죽어 있으면 남은 턴을 실패로 훑을 뿐이다")
    void stopsAfterConsecutiveFailures() throws Exception {
        when(repository.findClarifyBackfillTargets(anyLong(), anyInt()))
                .thenReturn(List.of(target(9), target(8), target(7), target(6), target(5), target(4), target(3)));
        when(postAnswer.backfill(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Outcome.FAILED);

        backfill.start();
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();

        Status status = backfill.status();
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.failed()).isEqualTo(ClarifiedQuestionBackfill.MAX_CONSECUTIVE_FAILURES);
        assertThat(status.message()).contains("연달아");
        verify(postAnswer, never()).backfill(eq(4L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("이미 돌고 있으면 시작은 새로 돌리지 않는다 — 한 번에 하나만")
    void startWhileRunningDoesNotStartASecondRun() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findClarifyBackfillTargets(Long.MAX_VALUE, ClarifiedQuestionBackfill.BATCH))
                .thenReturn(List.of(target(9)));
        when(repository.findClarifyBackfillTargets(9L, ClarifiedQuestionBackfill.BATCH)).thenReturn(List.of());
        when(postAnswer.backfill(eq(9L), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            inCall.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Outcome.CLARIFIED;
        });

        backfill.start();
        assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(backfill.start().running()).isTrue();
        release.countDown();
        assertThat(backfill.awaitFinish(Duration.ofSeconds(5))).isTrue();

        verify(postAnswer, times(1)).backfill(eq(9L), anyString(), anyString(), anyString(), anyString());
        assertThat(backfill.status().state()).isEqualTo(State.DONE);
    }

    /**
     * 생성자가 둘(스프링용 · 테스트용)이라 스프링이 고를 쪽에 {@code @Autowired} 가 있어야 한다 — 빠지면 스프링이 기본
     * 생성자를 찾다 실패해 <b>앱이 뜨지 않는다</b>(실제로 그렇게 깨진 채 단위 테스트는 전부 통과했다: 이 클래스의 다른
     * 테스트는 생성자를 직접 부른다). 여기서는 스프링의 진짜 생성자 선택을 거친다.
     */
    @Test
    @DisplayName("스프링이 만들 수 있다 — 주입할 생성자를 스프링이 고른다")
    void springCanConstructIt() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(QuestionReuseRepository.class, () -> repository);
            context.registerBean(PostAnswerService.class, () -> postAnswer);
            context.registerBean(SettingsService.class, () -> settings);
            context.registerBean(LlmRouter.class, () -> router);
            context.register(ClarifiedQuestionBackfill.class);
            context.refresh();

            assertThat(context.getBean(ClarifiedQuestionBackfill.class).status().state()).isEqualTo(State.IDLE);
        }
    }

    @Test
    @DisplayName("한 번도 돌리지 않았으면 '시작 전'과 남은 턴 수만 보인다")
    void statusBeforeAnyRun() {
        when(repository.countClarifyBackfillTargets()).thenReturn(42);

        Status status = backfill.status();

        assertThat(status.state()).isEqualTo(State.IDLE);
        assertThat(status.remaining()).isEqualTo(42);
        assertThat(status.running()).isFalse();
    }
}
