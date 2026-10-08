package com.example.ragagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 의 이름 규칙 — 정확 일치 → 유일한 부분 일치 → 실패. HTTP 없이 규칙만 고정한다.
 *
 * <p>가장 중요한 두 가지: <b>정확한 이름은 예전과 똑같이 동작한다</b>(다른 id 가 그 이름을 포함해도
 * 모호해지지 않는다), 그리고 <b>부분 일치의 답은 언제나 서버 표기</b>다(요청 본문에 실리는 값이므로).
 */
class ModelNameResolverTest {

    @Test
    @DisplayName("정확히 같은 id 가 있으면 그것 — 설정값이 그대로 답이다")
    void exactMatchIsReturnedAsIs() {
        var r = ModelNameResolver.resolve("gemma-4-e4b", List.of("gemma-4-e4b", "other"));

        assertThat(r.found()).isTrue();
        assertThat(r.resolved()).isEqualTo("gemma-4-e4b");
        assertThat(r.renamed()).isFalse();
    }

    @Test
    @DisplayName("설정값이 서버 id 의 일부이고 그런 id 가 하나뿐이면 서버 id 로 해석된다")
    void uniquePartialMatchResolvesToTheServerId() {
        var r = ModelNameResolver.resolve("gemma-4-e4b", List.of("google/gemma-4-e4b", "qwen3-8b"));

        assertThat(r.found()).isTrue();
        assertThat(r.resolved()).isEqualTo("google/gemma-4-e4b");
        assertThat(r.renamed()).isTrue();
    }

    @Test
    @DisplayName("부분 일치는 대소문자를 가리지 않는다 — 답은 어차피 서버 표기라 틀린 요청이 될 수 없다")
    void partialMatchIgnoresCase() {
        var r = ModelNameResolver.resolve("Qwen3-8B", List.of("qwen3-8b-instruct-q4_k_m.gguf"));

        assertThat(r.resolved()).isEqualTo("qwen3-8b-instruct-q4_k_m.gguf");
    }

    @Test
    @DisplayName("정확 일치가 있으면 다른 id 가 그 이름을 포함해도 모호하지 않다 — 예전 배포는 그대로 뜬다")
    void exactMatchWinsOverOtherIdsThatContainIt() {
        var r = ModelNameResolver.resolve("gemma-4-e4b", List.of("gemma-4-e4b-it", "gemma-4-e4b"));

        assertThat(r.resolved()).isEqualTo("gemma-4-e4b");
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.candidates()).containsExactlyInAnyOrder("gemma-4-e4b-it", "gemma-4-e4b");
    }

    @Test
    @DisplayName("둘 이상의 id 에 걸리면 고르지 않는다 — 후보를 돌려주고 실패로 남긴다")
    void partialMatchOnSeveralIdsIsAmbiguous() {
        var r = ModelNameResolver.resolve("gemma-4", List.of("google/gemma-4-e4b", "google/gemma-4-e2b", "qwen3"));

        assertThat(r.found()).isFalse();
        assertThat(r.ambiguous()).isTrue();
        assertThat(r.candidates()).containsExactly("google/gemma-4-e4b", "google/gemma-4-e2b");
    }

    @Test
    @DisplayName("어떤 id 에도 없으면 실패이고 후보도 없다 — '없음'과 '모호함'을 호출자가 구분한다")
    void noMatchIsNotFoundWithNoCandidates() {
        var r = ModelNameResolver.resolve("llama", List.of("google/gemma-4-e4b"));

        assertThat(r.found()).isFalse();
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    @DisplayName("설정값이 비어 있으면 서버의 유일한 모델이라도 고르지 않는다 — 그건 검증이 아니라 추측이다")
    void blankConfiguredNeverResolves() {
        assertThat(ModelNameResolver.resolve("", List.of("only-model")).found()).isFalse();
        assertThat(ModelNameResolver.resolve(null, List.of("only-model")).found()).isFalse();
    }

    @Test
    @DisplayName("서버 목록의 null 과 중복은 무시한다 — 같은 id 가 두 번 와도 모호해지지 않는다")
    void nullAndDuplicateIdsAreIgnored() {
        var r = ModelNameResolver.resolve("m", java.util.Arrays.asList("model-a", null, "model-a"));

        assertThat(r.resolved()).isEqualTo("model-a");
    }
}
