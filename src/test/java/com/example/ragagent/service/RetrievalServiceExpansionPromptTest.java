package com.example.ragagent.service;

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
                props, Optional.empty(), Optional.empty(), realMessageSource(), new ChatImageAnalysisSkipRegistry()))
                .doesNotThrowAnyException();
    }
}
