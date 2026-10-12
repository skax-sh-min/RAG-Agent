package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "같은 로컬 서버 여러 대"의 묶음 규칙 — 기동 검증의 복제본 예외({@code LlmConfig})와 임베딩 구성 경고
 * ({@code EmbeddingBeanConfig})가 함께 읽는다.
 */
class LocalReplicasTest {

    private static AppProperties.ProviderConfig local(String name, String url, String type, int priority) {
        return new AppProperties.ProviderConfig(name, url, "", "m", type, "LOCAL", priority, true, null, null, null);
    }

    private static AppProperties.ProviderConfig remote(String name, String type, int priority) {
        return new AppProperties.ProviderConfig(name, "https://api.example.com", "key", "m", type, "NORMAL", priority,
                true, null, null, null);
    }

    @Test
    @DisplayName("같은 type·priority 의 등록될 LOCAL 프로바이더가 서로의 복제본이다")
    void sameTypeAndPriorityAreReplicas() {
        var local = local("local", "http://a:1234/v1", "BOTH", 1);
        var local2 = local("local-2", "http://b:1234/v1", "BOTH", 1);
        var all = List.of(local, local2);

        assertThat(LocalReplicas.peersOf(local, all)).containsExactly(local2);
        assertThat(LocalReplicas.peersOf(local2, all)).containsExactly(local);
        assertThat(LocalReplicas.anyReplicated(all)).isTrue();
    }

    @Test
    @DisplayName("priority 나 type 이 다르면 복제본이 아니라 폴백이다 — 소형 모델 서버, 다른 작업 유형의 서버")
    void differentPriorityOrTypeIsNotAReplica() {
        var fast = local("local-fast", "http://a:1235/v1", "MICRO_TEXT", 0);
        var local = local("local", "http://a:1234/v1", "BOTH", 1);
        var vision = local("local-vision", "http://c:1234/v1", "VISION", 1);
        var all = List.of(fast, local, vision);

        assertThat(LocalReplicas.peersOf(local, all)).isEmpty();
        assertThat(LocalReplicas.anyReplicated(all)).isFalse();
    }

    @Test
    @DisplayName("등록되지 않을 프로바이더(base-url 없음)와 원격 프로바이더는 복제본으로 세지 않는다")
    void disabledAndRemoteProvidersAreNotReplicas() {
        var local = local("local", "http://a:1234/v1", "BOTH", 1);
        var unset = local("local-2", "", "BOTH", 1);           // LOCAL_LLM_URL_2 를 비워 둔 기본 배포
        var cloud = remote("openai", "BOTH", 1);

        assertThat(LocalReplicas.peersOf(local, List.of(local, unset, cloud))).isEmpty();
        assertThat(LocalReplicas.peersOf(cloud, List.of(local, cloud))).as("원격은 묶음 자체가 없다").isEmpty();
    }

    @Test
    @DisplayName("type 을 비우면 BOTH 로 읽는다(설정 기본값과 같은 규칙), 대소문자는 가리지 않는다")
    void blankTypeReadsAsBoth() {
        var local = local("local", "http://a:1234/v1", null, 1);
        var local2 = local("local-2", "http://b:1234/v1", "both", 1);

        assertThat(LocalReplicas.peersOf(local, List.of(local, local2))).containsExactly(local2);
    }

    @Test
    @DisplayName("URL 비교는 뒤쪽 / 와 /v1, 대소문자를 걷어내고 한다 — EMBED_BASE_URL 과 LOCAL_LLM_URL 을 같은 서버로 알아본다")
    void normalizedRootIgnoresTrailingV1AndSlash() {
        assertThat(LocalReplicas.normalizedRoot("http://Host:1234/v1/"))
                .isEqualTo(LocalReplicas.normalizedRoot("http://host:1234"))
                .isEqualTo("http://host:1234");
        assertThat(LocalReplicas.normalizedRoot(null)).isEmpty();
    }
}
