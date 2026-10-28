package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** application.properties 의 프로바이더 목록에 화면의 편집을 얹는 규칙. */
class ProviderOverlayTest {

    private static AppProperties.ProviderConfig cfg(String name, String url, String key, String role, int priority) {
        return new AppProperties.ProviderConfig(name, url, key, "m-" + name, "BOTH", role, priority,
                false, 4, null, 3_000, "none");
    }

    private static final List<AppProperties.ProviderConfig> BASE = List.of(
            cfg("local", "http://a:1/v1", "", "LOCAL", 1),
            cfg("gemini", "https://g/v1", "secret", "NORMAL", 2));

    @Test
    @DisplayName("편집이 없으면 목록을 그대로 돌려준다")
    void noOverridesIsIdentity() {
        assertThat(ProviderOverlay.apply(BASE, Map.of())).isSameAs(BASE);
    }

    @Test
    @DisplayName("고친 칸만 바뀌고 키·stream·동시성·출력 상한·dialect 는 properties 값 그대로다")
    void onlyEditedFieldsChange() {
        var o = new ProviderOverride("local", "http://a:2/v1", null, "TEXT", null, 7, 32_768, false, false);

        var local = ProviderOverlay.apply(BASE, Map.of("local", o)).get(0);

        assertThat(local.baseUrl()).isEqualTo("http://a:2/v1");
        assertThat(local.model()).isEqualTo("m-local");
        assertThat(local.type()).isEqualTo("TEXT");
        assertThat(local.role()).isEqualTo("LOCAL");
        assertThat(local.priority()).isEqualTo(7);
        assertThat(local.contextSize()).isEqualTo(32_768);
        assertThat(local.apiKey()).isEmpty();
        assertThat(local.stream()).isFalse();
        assertThat(local.concurrency()).isEqualTo(4);
        assertThat(local.maxTokens()).isEqualTo(3_000);
        assertThat(local.thinkingDialect()).isEqualTo("none");
    }

    @Test
    @DisplayName("숨긴 것은 빠지고, 화면에서 추가한 것은 뒤에 이름 순으로 붙는다(키 없음)")
    void deletedAndAdded() {
        var hide = new ProviderOverride("gemini", null, null, null, null, null, null, false, true);
        var addB = new ProviderOverride("b-local", "http://b/v1", "mb", "BOTH", "LOCAL", 3, null, true, false);
        var addA = new ProviderOverride("a-local", "http://c/v1", "ma", "TEXT", "LOCAL", 4, 65_536, true, false);
        var addHidden = new ProviderOverride("z", "http://z/v1", "mz", "BOTH", "LOCAL", 5, null, true, true);

        var out = ProviderOverlay.apply(BASE, Map.of("gemini", hide, "b-local", addB, "a-local", addA, "z", addHidden));

        assertThat(out).extracting(AppProperties.ProviderConfig::name).containsExactly("local", "a-local", "b-local");
        var added = out.get(1);
        assertThat(added.apiKey()).isNull();
        assertThat(added.isEnabled()).as("LOCAL 은 키 없이 등록된다").isTrue();
        assertThat(added.declaredContextSize()).isEqualTo(65_536);
    }
}
