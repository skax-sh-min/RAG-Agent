package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CrossEncoderReranker} 빈은 {@code app.reranker.base-url} 이 <b>실제로 있을 때만</b> 생긴다. 환경변수를 안 주면
 * {@code ${RERANK_BASE_URL:}} 가 빈 문자열이 되는데, 속성이 "있다"는 것만 보는 {@code @ConditionalOnProperty} 는 그것을 켜짐으로
 * 읽는다 — 그래서 SpEL 조건을 썼고, 이 테스트가 미설정 · 빈 문자열 · 공백 · 주소 있음을 고정한다.
 */
@ResourceLock("global-state")   // 스프링 컨텍스트를 만든다 — 로그 설정을 다시 잡는 다른 테스트와 겹치지 않게
class CrossEncoderRerankerBeanConditionTest {

    private static AppProperties props() {
        var reranker = new AppProperties.RerankerConfig("http://127.0.0.1:1", null, null, null, null);
        return new AppProperties("./data", 2, 800, 100, 100, 10, 0.0, true, 0, false, true, false, 3,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, reranker);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(AppProperties.class, CrossEncoderRerankerBeanConditionTest::props)
            .withUserConfiguration(CrossEncoderReranker.class);

    @Test
    @DisplayName("속성이 없으면 빈이 없다")
    void absentProperty() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CrossEncoderReranker.class));
    }

    @Test
    @DisplayName("빈 문자열(환경변수 미설정의 기본값)과 공백은 꺼짐이다")
    void blankValues() {
        runner.withPropertyValues("app.reranker.base-url=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CrossEncoderReranker.class));
        runner.withPropertyValues("app.reranker.base-url=   ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CrossEncoderReranker.class));
    }

    @Test
    @DisplayName("주소가 있으면 빈이 생긴다")
    void presentProperty() {
        runner.withPropertyValues("app.reranker.base-url=http://127.0.0.1:28095")
                .run(ctx -> assertThat(ctx).hasSingleBean(CrossEncoderReranker.class));
    }
}
