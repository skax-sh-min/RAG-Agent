package com.example.ragagent.llm;

import com.example.ragagent.llm.ServerModelResolver.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 임베딩 로드밸런서의 서버 선택 — 정상 → 접속불가 순이고, 모델없음 서버로는 요청을 보내지 않는다. */
class LoadBalancingModelStateTest {

    /** 상태를 알리는 임베딩 엔드포인트 — 불린 횟수를 센다. */
    private static final class Stub implements EmbeddingModel, ServerStateAware {
        final State state;
        final String name;
        final List<String> calls;

        Stub(String name, State state, List<String> calls) {
            this.name = name;
            this.state = state;
            this.calls = calls;
        }

        @Override public State serverState() { return state; }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (state == State.MODEL_MISSING) throw new ModelUnavailableException("model missing on " + name);
            calls.add(name);
            return new EmbeddingResponse(List.of(new Embedding(new float[]{1f}, 0)));
        }

        @Override public float[] embed(org.springframework.ai.document.Document d) { return new float[]{1f}; }
        @Override public int dimensions() { return 1; }
    }

    private static EmbeddingRequest req() {
        return new EmbeddingRequest(List.of("q"), null);
    }

    @Test
    @DisplayName("모델없음 서버는 건너뛰고, 접속불가보다 정상(모름 포함)을 먼저 쓴다")
    void prefersHealthyThenUnreachableAndSkipsMissing() {
        List<String> calls = new ArrayList<>();
        var lb = new LoadBalancingEmbeddingModel(List.of(
                new Stub("missing", State.MODEL_MISSING, calls),
                new Stub("down", State.UNREACHABLE, calls),
                new Stub("ok", State.OK, calls)));

        for (int i = 0; i < 4; i++) lb.call(req());

        assertThat(calls).containsOnly("ok");
    }

    @Test
    @DisplayName("정상이 없으면 접속불가로 알려진 서버라도 보내 본다 — 그 사이 돌아왔을 수 있다")
    void triesUnreachableWhenNothingHealthy() {
        List<String> calls = new ArrayList<>();
        var lb = new LoadBalancingEmbeddingModel(List.of(
                new Stub("missing", State.MODEL_MISSING, calls),
                new Stub("down", State.UNREACHABLE, calls)));

        lb.call(req());

        assertThat(calls).containsExactly("down");
    }

    @Test
    @DisplayName("전부 모델없음이면 요청을 보내지 않고 ModelUnavailableException")
    void errorsWhenEveryServerLacksTheModel() {
        List<String> calls = new ArrayList<>();
        var lb = new LoadBalancingEmbeddingModel(List.of(
                new Stub("a", State.MODEL_MISSING, calls), new Stub("b", State.MODEL_MISSING, calls)));

        assertThatThrownBy(() -> lb.call(req())).isInstanceOf(ModelUnavailableException.class);
        assertThat(calls).isEmpty();
    }
}
