package com.example.ragagent.config;

import com.example.ragagent.llm.ProviderConnectivity.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingStartupCheckTest {

    private static AppProperties props(String baseUrls) {
        var embedding = new AppProperties.EmbeddingConfig(baseUrls, null, "bge-m3", null, 10, 120, true, 0, List.of(), 1);
        AppProperties props = mock(AppProperties.class);
        when(props.embeddingSafe()).thenReturn(AppProperties.parseUrls(baseUrls).size() > 1
                ? new AppProperties.EmbeddingConfig(AppProperties.parseUrls(baseUrls).get(0), null, "bge-m3", null, 10, 120, true, 0,
                        AppProperties.parseUrls(baseUrls).subList(1, AppProperties.parseUrls(baseUrls).size()), 1)
                : embedding);
        return props;
    }

    private static Result up() { return new Result(true, 5L, true, null, Instant.now()); }
    private static Result down() { return new Result(false, null, null, "Connection refused", Instant.now()); }

    @Test
    @DisplayName("꺼진 주소마다 WARN 한 줄 — 켜진 주소는 말하지 않는다")
    void warnsForEachUnreachableAddress() {
        var check = new EmbeddingStartupCheck(props("http://a:1/v1;http://b:1/v1"),
                url -> url.contains("a:1") ? up() : down());

        assertThat(check.check()).singleElement().asString().contains("http://b:1/v1", "not reachable", "Connection refused");
    }

    @Test
    @DisplayName("모두 꺼져 있으면 주소별 경고 뒤에 전체가 죽었다는 한 줄이 더 붙는다")
    void warnsThatNoneAnswered() {
        var check = new EmbeddingStartupCheck(props("http://a:1/v1;http://b:1/v1"), url -> down());

        List<String> w = check.check();
        assertThat(w).hasSize(3);
        assertThat(w.get(2)).contains("none of the 2");
    }

    @Test
    @DisplayName("닿지만 EMBED_MODEL 이 목록에 없으면 모델 경고, 정상이면 아무 경고도 없다")
    void warnsWhenTheModelIsNotListed() {
        var missing = new EmbeddingStartupCheck(props("http://a:1/v1"),
                url -> new Result(true, 5L, false, null, Instant.now()));
        assertThat(missing.check()).singleElement().asString().contains("does not list model 'bge-m3'");

        var ok = new EmbeddingStartupCheck(props("http://a:1/v1"), url -> up());
        assertThat(ok.check()).isEmpty();
    }

    @Test
    @DisplayName("점검이 던져도 기동을 막지 않는다 — 경고로 바뀐다")
    void aThrowingProbeBecomesAWarning() {
        var check = new EmbeddingStartupCheck(props("http://a:1/v1"), url -> { throw new IllegalStateException("boom"); });
        assertThat(check.check()).singleElement().asString().contains("boom");
    }
}
