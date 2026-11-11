package com.example.ragagent.config;

import com.example.ragagent.llm.ProviderConnectivity.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelServersStartupCheckTest {

    private static AppProperties props(String embedUrls, String rerankUrls) {
        List<String> e = AppProperties.parseUrls(embedUrls);
        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe()).thenReturn(new AppProperties.EmbeddingConfig(e.isEmpty() ? null : e.get(0), null, "bge-m3",
                null, 10, 120, true, 0, e.size() > 1 ? e.subList(1, e.size()) : List.of(), 1));
        when(props.rerankerBaseUrls()).thenReturn(AppProperties.parseUrls(rerankUrls));
        return props;
    }

    private static Result up() { return new Result(true, 5L, true, null, Instant.now()); }
    private static Result down() { return new Result(false, null, null, "Connection refused", Instant.now()); }

    @Test
    @DisplayName("임베딩 — 꺼진 주소마다 WARN 한 줄, 켜진 주소는 말하지 않는다")
    void warnsForEachUnreachableEmbeddingAddress() {
        var check = new ModelServersStartupCheck(props("http://a:1/v1;http://b:1/v1", ""),
                (url, kind) -> url.contains("a:1") ? up() : down());

        assertThat(check.check()).singleElement().asString()
                .contains("[EMBED STARTUP]", "http://b:1/v1", "not reachable", "Connection refused");
    }

    @Test
    @DisplayName("리랭커 — 꺼진 주소는 RERANK STARTUP 경고, 주소가 없으면(꺼짐) 아무 말도 하지 않는다")
    void warnsForTheReranker() {
        var down = new ModelServersStartupCheck(props("http://a:1/v1", "http://r:2"),
                (url, kind) -> kind.equals("embedding") ? up() : down());
        assertThat(down.check()).singleElement().asString()
                .contains("[RERANK STARTUP]", "http://r:2", "not reachable", "RRF order");

        var off = new ModelServersStartupCheck(props("http://a:1/v1", ""), (url, kind) -> up());
        assertThat(off.check()).isEmpty();
    }

    @Test
    @DisplayName("모두 꺼져 있으면 주소별 경고 뒤에 전체가 죽었다는 한 줄이 더 붙는다")
    void warnsThatNoneAnswered() {
        var check = new ModelServersStartupCheck(props("", "http://r1:2;http://r2:2"), (url, kind) -> down());

        List<String> w = check.check();
        assertThat(w).hasSize(3);
        assertThat(w.get(2)).contains("none of the 2 reranker servers");
    }

    @Test
    @DisplayName("임베딩은 EMBED_MODEL 이 목록에 없으면 모델 경고, 리랭커는 모델명을 따지지 않는다")
    void modelNameIsOnlyCheckedForEmbedding() {
        var check = new ModelServersStartupCheck(props("http://a:1/v1", "http://r:2"),
                (url, kind) -> new Result(true, 5L, false, null, Instant.now()));

        assertThat(check.check()).singleElement().asString().contains("[EMBED STARTUP]", "does not list model 'bge-m3'");
    }

    @Test
    @DisplayName("점검이 던져도 기동을 막지 않는다 — 경고로 바뀐다")
    void aThrowingProbeBecomesAWarning() {
        var check = new ModelServersStartupCheck(props("http://a:1/v1", ""),
                (url, kind) -> { throw new IllegalStateException("boom"); });
        assertThat(check.check()).singleElement().asString().contains("boom");
    }
}
