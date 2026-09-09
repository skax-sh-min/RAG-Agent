package com.example.ragagent.service;

import com.example.ragagent.llm.ProviderContextWindows;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmProvider;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.ProviderRole;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.repository.LlmUsageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The other RetrievalService tests stub MessageSource with a minimal "{query} {number}" string —
 * none of them exercise the real prompt.retrieval.expansion text from messages_ko.properties /
 * messages.properties. This test builds the PromptTemplate/MultiQueryExpander from the actual
 * resource bundles to catch a broken placeholder or a stray '{'/'}' in the prompt text.
 */
class RetrievalServiceExpansionPromptTest {

    private static ResourceBundleMessageSource realMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }

    @Test
    @DisplayName("실제 메시지 번들의 prompt.retrieval.expansion에 {query}/{number} 플레이스홀더가 존재한다")
    void realBundle_hasRequiredPlaceholders() {
        String ko = realMessageSource().getMessage("prompt.retrieval.expansion", null, Locale.KOREAN);
        String en = realMessageSource().getMessage("prompt.retrieval.expansion", null, Locale.ENGLISH);
        assertThat(ko).contains("{query}").contains("{number}");
        assertThat(en).contains("{query}").contains("{number}");
    }

    @Test
    @DisplayName("실제 프롬프트 템플릿이 ST 렌더러에서 예외 없이 렌더링된다 (stray 중괄호 없음)")
    void realBundle_rendersWithoutException() {
        String ko = realMessageSource().getMessage("prompt.retrieval.expansion", null, Locale.KOREAN);
        PromptTemplate template = PromptTemplate.builder().template(ko).build();
        String rendered = template.render(Map.of("query", "[USER_QUESTION]\n테스트 질문\n[/USER_QUESTION]", "number", 2));
        assertThat(rendered).contains("테스트 질문").doesNotContain("{query}").doesNotContain("{number}");
    }

    /**
     * <b>줄 수가 정확히 맞지 않으면 확장 결과가 통째로 버려진다.</b> Spring AI 의
     * {@code MultiQueryExpander.expand()} 는 응답을 {@code split("\n")} 한 뒤
     * {@code numberOfQueries != 조각수} 면 경고 한 줄만 남기고 {@code List.of(원본질문)} 을 돌려준다
     * (1.1.8 기준). 판정이 <b>정확 일치</b>인 데다 빈 줄을 걸러내는 필터가 그 판정 <b>뒤</b>에 있어서,
     * 안내 문장 한 줄이나 변형 사이의 빈 줄 하나만으로도 실패한다 — 그러면 벡터 축이 원본 질문
     * 한 번으로 줄고, 이 프롬프트가 요구하는 영문 표기 변형·약어 정규화가 전부 사라진다.
     * 오류가 아니라 로그 한 줄이라 화면에서는 검색이 조용히 나빠질 뿐이다.
     *
     * <p>그래서 줄 수 제약은 프롬프트에만 있고 코드로는 아무도 눈치채지 못한다. 여기서 고정한다.
     * 숫자를 {@code 3} 으로 렌더하는 이유는 <b>{@code {number}} 로 박혀 있는지</b>를 보기 위해서다 —
     * "2줄" 처럼 손으로 적어 두면 {@code numberOfQueries} 를 바꾸는 순간 프롬프트가 거짓말을 한다.
     */
    @Test
    @DisplayName("두 번들 모두 '정확히 {number}줄' 제약을 담고, 그 숫자가 하드코딩이 아니다")
    void realBundle_pinsTheExactLineCountContract() {
        for (Locale locale : new Locale[] { Locale.KOREAN, Locale.ENGLISH }) {
            String template = realMessageSource().getMessage("prompt.retrieval.expansion", null, locale);
            String rendered = PromptTemplate.builder().template(template).build()
                    .render(Map.of("query", "[USER_QUESTION]\n질문\n[/USER_QUESTION]", "number", 3));

            assertThat(rendered)
                    .as("locale=%s — 줄 수 제약이 렌더된 숫자와 함께 있어야 한다", locale)
                    .containsAnyOf("정확히 3줄", "Exactly 3 lines");
            assertThat(rendered)
                    .as("locale=%s — 빈 줄 금지(빈 줄도 개수에 포함된다)", locale)
                    .containsAnyOf("빈 줄", "blank line");
        }
    }

    @Test
    @DisplayName("실제 메시지 번들로 RetrievalService 생성(MultiQueryExpander 빌드)이 예외 없이 성공한다")
    void retrievalService_constructsWithRealBundle() {
        AppProperties props = mock(AppProperties.class);
        when(props.searchRerankEnabled()).thenReturn(false);

        LlmRouter llmRouter = mock(LlmRouter.class);
        LlmProvider expansionProvider = new LlmProvider(
                "local", TaskType.TEXT, ProviderRole.LOCAL, 0, "key", null, "model", true, mock(ChatModel.class), null);
        when(llmRouter.routeProviderWithFallback(any(), any())).thenReturn(expansionProvider);

        assertThatCode(() -> new RetrievalService(llmRouter, mock(LlmUsageRepository.class), mock(RagService.class),
                props, Optional.empty(), Optional.empty(), realMessageSource(), new ChatImageAnalysisSkipRegistry(), new ProviderContextWindows()))
                .doesNotThrowAnyException();
    }
}
