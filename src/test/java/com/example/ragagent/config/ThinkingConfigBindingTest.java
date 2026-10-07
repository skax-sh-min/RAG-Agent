package com.example.ragagent.config;

import com.example.ragagent.llm.ThinkingDialect;
import com.example.ragagent.llm.ThinkingLevel;
import com.example.ragagent.llm.ThinkingSite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLAN §6.29 — {@code app.llm.thinking.*} 와 {@code app.llm.providers[N].thinking-dialect} 가 실제로 바인딩되는가.
 *
 * <p>따로 테스트를 두는 이유는 실패가 조용하기 때문이다: 두 레코드는 이제 생성자가 둘이라 정식 생성자의
 * {@code @ConstructorBinding} 이 빠지면 Spring 이 편의 생성자로 묶고, 그러면 설정을 아무리 적어도 모든 사이트가 출하값,
 * 모든 프로바이더가 auto 로 돈다 — 올바르게 설정된 배포와 겉보기로 구분되지 않는다({@code AuthConfig.guestIdentity} 와
 * 같은 함정, {@code UploadConfigBindingTest} 와 같은 방식).
 */
@ResourceLock("global-state")   // 수준을 정적 오버라이드 계층을 거쳐 읽는다 — AppPropertiesOverrideTest 가 같은 키를 바꾼다
class ThinkingConfigBindingTest {

    private static AppProperties.LlmConfig bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.llm", Bindable.of(AppProperties.LlmConfig.class))
                .orElse(null);
    }

    @Test
    @DisplayName("사이트별 수준이 사이트 id(하이픈 포함) 그대로의 키로 바인딩된다")
    void bindsLevelsBySiteId() {
        AppProperties.LlmConfig llm = bind(Map.of(
                "app.llm.thinking.answer-rag-n", "high",
                "app.llm.thinking.condense", "low",
                "app.llm.thinking.post-answer", "bogus"));

        assertThat(llm.thinking()).containsEntry("answer-rag-n", "high").containsEntry("condense", "low");
        assertThat(llm.thinkingLevel(ThinkingSite.ANSWER_RAG_N)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(llm.thinkingLevel(ThinkingSite.CONDENSE)).isEqualTo(ThinkingLevel.LOW);
        assertThat(llm.thinkingLevel(ThinkingSite.POST_ANSWER)).as("틀린 값 → 출하값").isEqualTo(ThinkingLevel.OFF);
        assertThat(llm.thinkingLevel(ThinkingSite.ANSWER_RAG_C)).as("줄 없음 → 출하값")
                .isEqualTo(ThinkingSite.ANSWER_RAG_C.shippedDefault()).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    @DisplayName("프로바이더별 thinking-dialect 가 바인딩되고, 비었거나 모르는 값은 auto 다")
    void bindsProviderDialect() {
        AppProperties.LlmConfig llm = bind(Map.of(
                "app.llm.providers[0].name", "openai",
                "app.llm.providers[0].thinking-dialect", "openai-effort",
                "app.llm.providers[1].name", "local",
                "app.llm.providers[2].name", "typo",
                "app.llm.providers[2].thinking-dialect", "enable-thinking"));

        List<AppProperties.ProviderConfig> providers = llm.providers();
        assertThat(providers.get(0).thinkingDialectOrAuto()).isEqualTo(ThinkingDialect.OPENAI_EFFORT);
        assertThat(providers.get(1).thinkingDialectOrAuto()).isEqualTo(ThinkingDialect.AUTO);
        assertThat(providers.get(2).thinkingDialect()).isEqualTo("enable-thinking");
        assertThat(providers.get(2).thinkingDialectOrAuto()).isEqualTo(ThinkingDialect.AUTO);
    }

    @Test
    @DisplayName("편의 생성자(테스트용)는 수준 맵이 비어 출하값으로, dialect 는 auto 로 돈다")
    void convenienceConstructorsDefaultToShippedAndAuto() {
        var llm = new AppProperties.LlmConfig(List.of(), 2, 10, 180, "COST_FIRST", 3, 20,
                0.0, 0.1, 0.0, 0.7, true, 6000, 1, false);
        var provider = new AppProperties.ProviderConfig("p", "http://x/v1", "", "m", "BOTH", "LOCAL", 1, true,
                null, null, null);

        assertThat(llm.thinking()).isEmpty();
        assertThat(llm.thinkingLevel(ThinkingSite.CONDENSE)).isEqualTo(ThinkingLevel.OFF);
        assertThat(provider.thinkingDialectOrAuto()).isEqualTo(ThinkingDialect.AUTO);
    }
}
