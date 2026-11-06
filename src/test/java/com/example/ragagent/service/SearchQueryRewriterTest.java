package com.example.ragagent.service;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재검색 루프의 새 검색어 생성(§6.33). 프롬프트는 목이 아니라 <b>실제 번들</b>로 채운다 — 값의 줄 끝 {@code \n\} 가 하나라도 빠지면
 * 첫 줄만 값이 되고 오류가 없다(CLAUDE.md "메시지 번들의 여러 줄 값").
 */
class SearchQueryRewriterTest {

    private static ResourceBundleMessageSource realBundle() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return messages;
    }

    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        return props;
    }

    /** {@code executeGatedWithUsage} 가 모델을 한 번 부르고 주어진 응답을 돌려준다. */
    @SuppressWarnings("unchecked")
    private static LlmRouter routerReturning(String response, ArgumentCaptor<Prompt> promptOut) {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenAnswer(inv -> {
                    Function<ChatModel, ChatResponse> fn = inv.getArgument(2);
                    ChatModel model = mock(ChatModel.class);
                    when(model.call(promptOut.capture())).thenReturn(
                            new ChatResponse(List.of(new Generation(new AssistantMessage(response)))));
                    fn.apply(model);
                    return new LlmRouter.LlmResult(response, 120, 15);
                });
        return router;
    }

    private SearchQueryRewriter rewriter(LlmRouter router, String history) {
        QuestionCondenser condenser = mock(QuestionCondenser.class);
        when(condenser.buildMaterial(any(), any())).thenReturn(history);
        return new SearchQueryRewriter(router, condenser, realBundle(), props());
    }

    @Test
    @DisplayName("JSON 한 줄에서 새 검색어를 읽고 토큰 사용량을 함께 돌려준다")
    void readsTheNextQueryAndTokenUsage() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        SearchQueryRewriter r = rewriter(routerReturning("{\"nextQuery\": \"IBatchContext 처리일자 PROC_DATE\"}", prompt), "");

        Optional<SearchQueryRewriter.Next> next = r.nextQuery("u", "t", "처리일자는 어떻게 꺼내?",
                List.of("처리일자는 어떻게 꺼내?"), List.of(new Document("무관한 문서")), Locale.KOREAN);

        assertThat(next).isPresent();
        assertThat(next.get().query()).isEqualTo("IBatchContext 처리일자 PROC_DATE");
        assertThat(next.get().inputTokens()).isEqualTo(120);
        assertThat(next.get().outputTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("프롬프트는 한/영 실제 번들에서 자리표시자 없이 채워진다 — 이전 질문 · 쓴 검색어 · 찾은 문서 앞부분이 들어가고 질문은 감싼다")
    void realPromptIsFilledInBothLocales() {
        for (Locale locale : new Locale[]{Locale.KOREAN, Locale.ENGLISH}) {
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            SearchQueryRewriter r = rewriter(routerReturning("{\"nextQuery\": \"x y\"}", prompt), "- 앞 질문입니다");
            String longDoc = "가".repeat(400);
            r.nextQuery("u", "t", "메타 로더는?", List.of("메타 로더는?", "이미 쓴 두 번째"),
                    List.of(new Document(longDoc), new Document("두번째 문서"), new Document("세번째"), new Document("넷째는 안 들어간다")),
                    locale);

            Prompt p = prompt.getValue();
            String system = p.getInstructions().get(0).getText();
            String user = p.getInstructions().get(1).getText();
            assertThat(system).as("%s", locale).doesNotContain("{history}", "{used}", "{found}");
            assertThat(system.lines().count()).as("%s 줄 수 — 줄 끝 \\n\\ 가 빠지면 한 줄이 된다", locale).isGreaterThan(15);
            assertThat(system).contains("- 앞 질문입니다", "- 메타 로더는?", "- 이미 쓴 두 번째", "nextQuery");
            assertThat(system).contains("1. " + "가".repeat(SearchQueryRewriter.FOUND_EXCERPT_CHARS) + "…");
            assertThat(system).contains("2. 두번째 문서", "3. 세번째").doesNotContain("넷째는 안 들어간다");
            assertThat(user).as("질문은 지시가 아닌 데이터로 감싼다").contains("메타 로더는?").contains("[USER_QUESTION]");
        }
    }

    @Test
    @DisplayName("호출 지점은 CONDENSE 사이트 — 생각 끔 · 한 줄 출력 예약 · 고정 라우팅(distill 과 같은 자리)")
    void usesTheCondenseSite() {
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        LlmRouter router = routerReturning("{\"nextQuery\": \"새 검색어\"}", prompt);
        SearchQueryRewriter r = rewriter(router, "");

        r.nextQuery("u", "t", "질문", List.of("질문"), List.of(), Locale.KOREAN);

        assertThat(ThinkingControl.siteOf(prompt.getValue())).isEqualTo(ThinkingSite.CONDENSE);
        org.mockito.Mockito.verify(router).executeGatedWithUsage(
                org.mockito.ArgumentMatchers.eq(ThinkingSite.CONDENSE.taskType()),
                org.mockito.ArgumentMatchers.eq(ThinkingSite.CONDENSE.fixedRoutingMode()), any());
    }

    @Test
    @DisplayName("이미 쓴 검색어(공백·대소문자만 다른 것 포함)는 새 검색어로 치지 않는다 — 같은 검색을 되풀이하지 않는다")
    void anAlreadyUsedQueryIsNotNew() {
        SearchQueryRewriter r = rewriter(routerReturning("{\"nextQuery\": \"Meta  Loader\"}",
                ArgumentCaptor.forClass(Prompt.class)), "");

        assertThat(r.nextQuery("u", "t", "q", List.of("q", "meta loader"), List.of(), Locale.KOREAN)).isEmpty();
    }

    @Test
    @DisplayName("호출이 실패하면 비어 있다 — 예외는 검색을 멈추지 않는다")
    void failureIsEmpty() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenThrow(new IllegalStateException("All providers exhausted"));

        assertThat(rewriter(router, "").nextQuery("u", "t", "q", List.of("q"), List.of(), Locale.KOREAN)).isEmpty();
    }

    @Test
    @DisplayName("파싱 — 코드 펜스·머리말이 있어도 읽고, 이스케이프를 풀고, 키가 없거나 비었거나 너무 길면 버린다")
    void parse() {
        assertThat(SearchQueryRewriter.parse("```json\n{\"nextQuery\": \"가나다\"}\n```")).isEqualTo("가나다");
        assertThat(SearchQueryRewriter.parse("다음과 같습니다: {\"nextQuery\":\"a \\\"b\\\" c\"} 끝")).isEqualTo("a \"b\" c");
        assertThat(SearchQueryRewriter.parse("{\"nextQuery\": \"  여러   공백  \"}")).isEqualTo("여러 공백");
        assertThat(SearchQueryRewriter.parse("{\"query\": \"키 이름이 다르다\"}")).isNull();
        assertThat(SearchQueryRewriter.parse("{\"nextQuery\": \"   \"}")).isNull();
        assertThat(SearchQueryRewriter.parse("검색어만 그냥 적었다")).isNull();
        assertThat(SearchQueryRewriter.parse("{\"nextQuery\": \"" + "가".repeat(SearchQueryRewriter.MAX_QUERY_CHARS + 1) + "\"}"))
                .as("잘라 쓰지 않고 버린다").isNull();
        assertThat(SearchQueryRewriter.parse(null)).isNull();
        assertThat(SearchQueryRewriter.parse("")).isNull();
    }
}
