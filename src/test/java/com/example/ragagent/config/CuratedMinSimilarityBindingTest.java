package com.example.ragagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.search-curated-qa-min-similarity} 가 실제로 {@link AppProperties} 에 닿는가.
 *
 * <p>따로 재는 이유: {@code AppProperties} 가 생성자 둘(정식 + 테스트용 하위호환)을 갖게 되면서 정식 쪽의
 * {@code @ConstructorBinding} 이 빠지면 스프링이 하위호환 생성자로 바인딩하고, 이 값은 늘 {@code null} → 출하값 0.5 로 떨어진다.
 * 출하값과 같은 값을 설정한 배포에서는 아무 차이가 없어 보이므로 출하값이 <b>아닌</b> 값으로 확인한다
 * ({@code UploadConfigBindingTest}·{@code AuthConfig.guestIdentity} 와 같은 함정).
 */
class CuratedMinSimilarityBindingTest {

    private static AppProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app", Bindable.of(AppProperties.class))
                .get();
    }

    @Test
    @DisplayName("프로퍼티 값이 바인딩된다 — 출하값(0.5)이 아닌 값으로 확인")
    void bindsTheConfiguredValue() {
        AppProperties props = bind(Map.of("app.search-curated-qa-min-similarity", "0.42", "app.search-top-k", "7"));

        assertThat(props.searchCuratedQaMinSimilarity()).isEqualTo(0.42);
        assertThat(props.searchCuratedQaMinSimilaritySafe()).isEqualTo(0.42);
        assertThat(props.searchTopK()).as("다른 값도 같은 생성자로 함께 바인딩된다").isEqualTo(7);
    }

    @Test
    @DisplayName("없으면 출하값 0.5, 범위를 넘으면 [0, 1] 로 자른다")
    void defaultsAndClamps() {
        assertThat(bind(Map.of("app.search-top-k", "7")).searchCuratedQaMinSimilaritySafe())
                .isEqualTo(AppProperties.DEFAULT_CURATED_QA_MIN_SIMILARITY);
        assertThat(bind(Map.of("app.search-curated-qa-min-similarity", "1.7")).searchCuratedQaMinSimilaritySafe())
                .isEqualTo(1.0);
        assertThat(bind(Map.of("app.search-curated-qa-min-similarity", "-0.2")).searchCuratedQaMinSimilaritySafe())
                .isZero();
    }
}
