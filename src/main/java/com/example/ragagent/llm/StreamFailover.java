package com.example.ragagent.llm;

import com.example.ragagent.exception.LlmBackpressureException;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 채팅 답변 스트리밍의 <b>프로바이더 선택 + 장애 전환</b> — {@code AnswerService}·{@code DirectAnswerService} 가 같이 쓴다.
 *
 * <p><b>왜 라우터 밖에 있는가.</b> 스트리밍은 {@code OpenAiApi.chatCompletionStream()} 을 직접 불러 블로킹 호출이 지나는
 * {@link LlmRouter#executeGated} 의 순회(프로바이더 선택 → 실패 시 차단 → 다음 프로바이더)를 통째로 우회한다. 그래서 이 순회를
 * 스트리밍에 맞게 따로 해야 하는데, 갈래마다 다르게 쓰면 전환 규칙이 둘로 갈라진다 — 한 곳에 둔다. 순회는 여기, "무엇이 서버
 * 장애인가"의 판정과 차단은 {@link LlmRouter#failOver} 다(블로킹 경로와 같은 분류·같은 차단 규칙).
 *
 * <p><b>첫 토큰이 나가면 끝이다.</b> {@code outputStarted} 가 참이면 어떤 실패도 전환하지 않고 그대로 올린다 — 다른 서버로 다시
 * 열면 화면에 앞부분이 두 번 찍히고, 이미 답의 절반을 본 사용자에게 다른 모델의 답을 이어 붙이는 셈이 된다. 반대로 토큰 전의
 * 실패(연결 거부·연결 타임아웃·5xx)는 사용자가 아무것도 보지 못했으므로 조용히 다른 프로바이더로 넘어가도 된다.
 *
 * <p><b>무한히 돌지 않는다.</b> 실패한 프로바이더는 {@code failOver} 가 차단하므로 다음 {@code routeProvider} 가 다른 쪽을 고른다.
 * 그래도 같은 프로바이더가 다시 나오면(차단이 걸리지 않은 경우) 더 나아갈 곳이 없다는 뜻이라 원래 예외를 올린다. 후보가
 * 바닥나면 {@code routeProvider} 가 {@code LlmProviderExhaustedException}(남은 시간이 담긴 사용자 문구)을 던진다.
 */
public final class StreamFailover {

    private StreamFailover() {}

    /**
     * @param provider 실제로 스트림을 끝낸 프로바이더 — 장애 전환이 있었으면 처음 고른 것과 다르다
     * @param value    {@code stream} 이 돌려준 결과
     */
    public record Served<T>(LlmProvider provider, T value) {}

    /**
     * 프로바이더를 골라 스트림을 열고, 첫 토큰 전에 서버 장애로 실패하면 다음 프로바이더로 다시 연다.
     *
     * @param outputStarted 사용자에게 답 토큰이 이미 나갔는가. 참이면 전환하지 않는다
     * @param stream        고른 프로바이더로 스트림을 끝까지 소비하고 결과를 돌려준다. 프로바이더의 동시성 퍼밋은 이 호출이 도는
     *                      동안 쥐고 있고, 실패하면 다음 프로바이더를 고르기 전에 놓는다. 프롬프트가 프로바이더의 창에 의존하므로
     *                      프로바이더가 바뀌면 이 함수가 새 프로바이더로 다시 호출된다 — 매번 처음부터 만들어야 한다
     */
    public static <T> Served<T> run(LlmRouter router, TaskType taskType, RoutingMode mode,
                                    BooleanSupplier outputStarted, Function<LlmProvider, T> stream) {
        Set<String> tried = new HashSet<>();
        LlmProvider provider = router.routeProvider(taskType, mode);
        while (true) {
            tried.add(provider.name());
            try (LlmRouter.Permit permit = router.acquirePermit(provider)) {
                return new Served<>(provider, stream.apply(provider));
            } catch (LlmBackpressureException e) {
                throw e;   // 용량 압박이지 서버 장애가 아니다 — 차단도 전환도 하지 않는다
            } catch (RuntimeException e) {
                if (outputStarted.getAsBoolean() || !router.failOver(provider, taskType, mode, e)) throw e;
                LlmProvider next = router.routeProvider(taskType, mode);   // 차단된 쪽은 건너뛴다
                if (tried.contains(next.name())) throw e;                  // 같은 곳이 또 나왔다 — 넘어갈 곳이 없다
                provider = next;
            }
        }
    }
}
