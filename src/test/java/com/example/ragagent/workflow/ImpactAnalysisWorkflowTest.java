package com.example.ragagent.workflow;

import com.example.ragagent.config.AppProperties;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 영향도 분석 워크플로 — 인용 검증이 가짜 근거를 걸러내고, 상한·3글자 미만·실패가 리포트에 정직하게 남는지 고정한다.
 * 판정 프롬프트는 목이 아니라 실제 번들로 채운다.
 */
class ImpactAnalysisWorkflowTest {

    private KeywordSearchRepository keywords;
    private HybridSearchService hybrid;
    private LlmRouter router;
    private ChangeJobRepository jobs;
    private ImpactAnalysisWorkflow workflow;
    private final List<String> systemPrompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        keywords = mock(KeywordSearchRepository.class);
        hybrid = mock(HybridSearchService.class);
        router = mock(LlmRouter.class);
        jobs = mock(ChangeJobRepository.class);
        when(hybrid.withStoredText(any())).thenAnswer(inv -> inv.getArgument(0));
        when(router.localTier1Concurrency()).thenReturn(Optional.empty());

        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        workflow = new ImpactAnalysisWorkflow(keywords, hybrid, router, jobs, bundle, props,
                Duration.ofMillis(1), Duration.ofMillis(5));
    }

    private static Document chunk(String id, String docId, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of(
                MetaKey.DOC_ID, docId, MetaKey.FILENAME, docId + ".pdf", MetaKey.PAGE_OR_SLIDE, "3")).build();
    }

    /** 판정 호출마다 주어진 응답을 차례로 돌려준다. */
    @SuppressWarnings("unchecked")
    private void judgements(String... responses) {
        AtomicInteger i = new AtomicInteger();
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any())).thenAnswer(inv -> {
            Function<org.springframework.ai.chat.model.ChatModel, org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatModel model = mock(org.springframework.ai.chat.model.ChatModel.class);
            when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(c -> {
                systemPrompts.add(((SystemMessage) ((org.springframework.ai.chat.prompt.Prompt) c.getArgument(0))
                        .getInstructions().get(0)).getText());
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("{}"))));
            });
            fn.apply(model);
            return new LlmRouter.LlmResult(responses[Math.min(i.getAndIncrement(), responses.length - 1)], 10, 5);
        });
    }

    @Test
    @DisplayName("인용이 원문에 있으면 영향 있음, 없으면(지어낸 근거) 근거 미확인으로 내려간다")
    void citationVerificationFiltersFabricatedQuotes() {
        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(
                chunk("c1", "d1", "ABC API 는 인증   토큰을 요구한다."),
                chunk("c2", "d2", "ABC API 를 호출한다."),
                chunk("c3", "d3", "ABC API 이름만 언급된다.")), false, false));
        judgements(
                "{\"impacted\": true, \"reason\": \"인증 설명\", \"quote\": \"ABC API 는 인증 토큰을 요구한다.\"}",   // 공백 차이는 허용
                "{\"impacted\": true, \"reason\": \"호출\", \"quote\": \"문서에 없는 문장\"}",                        // 지어낸 인용
                "{\"impacted\": false, \"reason\": \"스침\"}");

        ImpactAnalysisWorkflow.Report r = workflow.analyze("ABC API", "삭제 예정", Locale.KOREAN);

        assertThat(r.mentions()).isEqualTo(3);
        assertThat(r.judged()).isEqualTo(3);
        assertThat(r.findings()).extracting(ImpactAnalysisWorkflow.Finding::verdict).containsExactly(
                ImpactAnalysisWorkflow.VERDICT_IMPACTED, ImpactAnalysisWorkflow.VERDICT_UNVERIFIED,
                ImpactAnalysisWorkflow.VERDICT_NOT_IMPACTED);
        assertThat(r.findings().get(0).file()).isEqualTo("d1.pdf");
        // 프롬프트는 실제 번들 — 치환이 끝나 있고 대상·메모가 들어간다.
        assertThat(systemPrompts.get(0)).doesNotContain("{subject}").doesNotContain("{note}")
                .contains("ABC API").contains("삭제 예정").contains("quote");
    }

    @Test
    @DisplayName("영향 있음인데 인용이 비어 있어도 근거 미확인이다")
    void emptyQuoteIsUnverified() {
        ImpactAnalysisWorkflow.Finding f = ImpactAnalysisWorkflow.parseJudgement(
                "```json\n{\"impacted\": true, \"reason\": \"r\", \"quote\": \"\"}\n```", "본문", "c", "d", "f.pdf", "1");
        assertThat(f.verdict()).isEqualTo(ImpactAnalysisWorkflow.VERDICT_UNVERIFIED);
    }

    @Test
    @DisplayName("읽을 수 없는 응답과 호출 실패는 error 로 남고 분석은 계속된다")
    void unreadableOrFailedJudgementsAreErrors() {
        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(
                chunk("c1", "d1", "NOTX 사용"), chunk("c2", "d2", "NOTX 사용 2")), false, false));
        judgements("모르겠습니다");   // 첫 응답은 JSON 이 아니다
        ImpactAnalysisWorkflow.Report first = workflow.analyze("NOTX", null, Locale.KOREAN);
        assertThat(first.findings()).extracting(ImpactAnalysisWorkflow.Finding::verdict)
                .containsOnly(ImpactAnalysisWorkflow.VERDICT_ERROR);

        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenThrow(new IllegalStateException("down"));
        ImpactAnalysisWorkflow.Report second = workflow.analyze("NOTX", null, Locale.KOREAN);
        assertThat(second.findings()).hasSize(2).extracting(ImpactAnalysisWorkflow.Finding::verdict)
                .containsOnly(ImpactAnalysisWorkflow.VERDICT_ERROR);
    }

    @Test
    @DisplayName("후보가 상한을 넘으면 나머지는 판정 없이 파일별 언급 수로만 보인다")
    void overTheCapIsListedNotJudged() {
        List<Document> many = new ArrayList<>();
        for (int i = 0; i < ImpactAnalysisWorkflow.MAX_JUDGED + 3; i++) {
            many.add(chunk("c" + i, i < ImpactAnalysisWorkflow.MAX_JUDGED ? "d" + i : "tail", "NOTX 사용 " + i));
        }
        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(many, true, false));
        judgements("{\"impacted\": false, \"reason\": \"x\"}");

        ImpactAnalysisWorkflow.Report r = workflow.analyze("NOTX", null, Locale.KOREAN);

        assertThat(r.judged()).isEqualTo(ImpactAnalysisWorkflow.MAX_JUDGED);
        assertThat(r.mentions()).isEqualTo(ImpactAnalysisWorkflow.MAX_JUDGED + 3);
        assertThat(r.truncated()).isTrue();
        assertThat(r.unjudged()).containsExactly(new ImpactAnalysisWorkflow.Unjudged("tail", "tail.pdf", 3));
    }

    @Test
    @DisplayName("3글자 미만 식별자는 LLM 을 부르지 않고 tooShort 로 밝힌다")
    void tooShortSubjectNeverCallsTheModel() {
        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, true));

        ImpactAnalysisWorkflow.Report r = workflow.analyze("DB", null, Locale.KOREAN);

        assertThat(r.tooShort()).isTrue();
        assertThat(r.findings()).isEmpty();
        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
    }

    @Test
    @DisplayName("분석이 던지면 작업은 failed 로, 끝나면 done 으로 저장된다 — 코퍼스는 건드리지 않는다")
    void storesDoneOrFailed() {
        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenReturn(new KeywordSearchRepository.Mentions(List.of(), false, false));
        workflow.analyzeAndStore(7L, "NOTX", null, Locale.KOREAN);
        verify(jobs).finish(eq(7L), eq(ChangeJobRepository.DONE), any(), eq(null));

        when(keywords.findMentions(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("fts down"));
        workflow.analyzeAndStore(8L, "NOTX", null, Locale.KOREAN);
        verify(jobs).finish(eq(8L), eq(ChangeJobRepository.FAILED), eq(null), eq("fts down"));
    }

    @Test
    @DisplayName("사용자당 동시 1건 · 빈 대상은 거절한다")
    void startGuards() {
        when(jobs.countRunning("u1")).thenReturn(1);
        assertThatThrownBy(() -> workflow.start("u1", "NOTX", null, Locale.KOREAN)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.start("u2", "  ", null, Locale.KOREAN)).isInstanceOf(IllegalArgumentException.class);
        verify(jobs, never()).create(any(), any(), any());
    }
}
