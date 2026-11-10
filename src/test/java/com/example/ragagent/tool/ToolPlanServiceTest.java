package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingSite;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tool 선택(§6.33 3단계) — 프롬프트 JSON 의 해석 규칙과 "어떤 실패도 계획 없음"을 고정한다. 프롬프트는 목이 아니라 <b>실제 번들</b>로 채운다
 * (값의 줄 끝 {@code \n\} 하나가 빠지면 첫 줄만 값이 되고 오류가 없다).
 */
class ToolPlanServiceTest {

    private static final ToolCaller GUEST = ToolCaller.guest();

    private ToolCatalog catalog;
    private List<ToolCallback> visible;

    @BeforeEach
    void setUp() {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        catalog = new ToolCatalog(
                new KnowledgeSearchTool(null, Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, mock(DocumentExportService.class), guard),
                new CatalogQueryTool(mock(CatalogQueryService.class), guard),
                new DiffTool(guard));
        ToolPlanService svc = new ToolPlanService(mock(LlmRouter.class), catalog, bundle(), props(), true);
        visible = svc.visibleTools(GUEST);
    }

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource m = new ResourceBundleMessageSource();
        m.setBasename("messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        return m;
    }

    private static AppProperties props() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        return props;
    }

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
                    return new LlmRouter.LlmResult(response, 200, 20);
                });
        return router;
    }

    private ToolPlanService service(LlmRouter router, boolean enabled) {
        return new ToolPlanService(router, catalog, bundle(), props(), enabled);
    }

    // ── 해석 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("채팅의 선택지는 검색 · 관련 문서 · 카탈로그 셋이고 순서가 고정이다(읽기 보조·관리자 Tool 은 보이지 않는다)")
    void visibleToolsAreTheThreeChatChoices() {
        assertThat(visible.stream().map(cb -> cb.getToolDefinition().name()).toList())
                .containsExactly("searchKnowledge", "findRelatedDocuments", "queryCatalog");
    }

    @Test
    @DisplayName("정상 JSON — 고른 Tool 과 인자가 계획이 된다")
    void parsesAValidPick() {
        ToolPlan plan = ToolPlanService.parse("{\"tool\": \"queryCatalog\", \"arguments\": {\"name\": \"documents.list\"}}", visible);
        assertThat(plan).isNotNull();
        assertThat(plan.toolName()).isEqualTo("queryCatalog");
        assertThat(plan.argumentsJson()).contains("documents.list");
        assertThat(plan.routesToToolNode()).isTrue();
    }

    @Test
    @DisplayName("머리말·코드 펜스가 붙어도 첫 { ~ 마지막 } 를 읽는다")
    void ignoresFencesAndPreamble() {
        ToolPlan plan = ToolPlanService.parse("```json\n{\"tool\":\"findRelatedDocuments\",\"arguments\":{\"subject\":\"트랜잭션\"}}\n```", visible);
        assertThat(plan).isNotNull();
        assertThat(plan.toolName()).isEqualTo("findRelatedDocuments");
    }

    @Test
    @DisplayName("검색 Tool 은 계획이지만 TOOL 노드로 가지 않는다(기존 RETRIEVAL)")
    void searchToolStaysOnRetrieval() {
        ToolPlan plan = ToolPlanService.parse("{\"tool\":\"searchKnowledge\",\"arguments\":{\"query\":\"x\"}}", visible);
        assertThat(plan).isNotNull();
        assertThat(plan.routesToToolNode()).isFalse();
    }

    @Test
    @DisplayName("NONE · 빈 이름 · 모르는 Tool · 보이지 않는 Tool(관리자 getDocument·워크플로 Tool) · 깨진 JSON · 없는 응답은 전부 계획 없음")
    void everythingElseIsNoPlan() {
        assertThat(ToolPlanService.parse("{\"tool\":\"NONE\",\"arguments\":{}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"none\"}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"\",\"arguments\":{}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"deleteEverything\",\"arguments\":{}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"getDocument\",\"arguments\":{\"docId\":\"a\"}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"analyzeImpact\",\"arguments\":{}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\": \"queryCatalog\", ", visible)).isNull();
        assertThat(ToolPlanService.parse("도구가 필요 없습니다", visible)).isNull();
        assertThat(ToolPlanService.parse(null, visible)).isNull();
    }

    @Test
    @DisplayName("인자가 스키마와 맞지 않으면(필수 누락 · 빈 값 · 없는 인자 · 객체가 아님) 계획 없음")
    void invalidArgumentsAreNoPlan() {
        assertThat(ToolPlanService.parse("{\"tool\":\"queryCatalog\",\"arguments\":{}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"queryCatalog\",\"arguments\":{\"name\":\"  \"}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"queryCatalog\",\"arguments\":{\"name\":\"a\",\"version\":\"v2\"}}", visible)).isNull();
        assertThat(ToolPlanService.parse("{\"tool\":\"queryCatalog\",\"arguments\":[\"a\"]}", visible)).isNull();
    }

    // ── 호출 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("프롬프트는 실제 번들로 채워지고(도구 · 규칙 · 출력 형식 전부) 모델은 CONDENSE 사이트로 표시된다")
    void promptComesFromTheRealBundle() {
        ArgumentCaptor<Prompt> seen = ArgumentCaptor.forClass(Prompt.class);
        ToolPlanService svc = service(routerReturning("{\"tool\":\"queryCatalog\",\"arguments\":{\"name\":\"documents.list\"}}", seen), true);

        ToolPlanService.Planned planned = svc.plan("등록된 문서 목록 보여줘", GUEST, Locale.KOREAN);

        assertThat(planned.called()).isTrue();
        assertThat(planned.plan().toolName()).isEqualTo("queryCatalog");
        assertThat(planned.inputTokens()).isEqualTo(200);
        String system = ((SystemMessage) seen.getValue().getInstructions().get(0)).getText();
        assertThat(system).doesNotContain("{tools}").doesNotContain("{rules}")
                .contains("- searchKnowledge:", "- findRelatedDocuments:", "- queryCatalog:")
                .contains("searchKnowledge.", "findRelatedDocuments.", "queryCatalog.")
                .contains("확신이 없으면 searchKnowledge")
                .contains("{\"tool\":")
                .doesNotContain("analyzeImpact", "requestDocumentChange", "getDocument");
        assertThat(ThinkingControl.siteOf(seen.getValue())).isEqualTo(ThinkingSite.CONDENSE);
    }

    @Test
    @DisplayName("영어 로케일도 번들 키가 모두 있다(한/영 짝)")
    void englishBundleHasEveryKey() {
        ArgumentCaptor<Prompt> seen = ArgumentCaptor.forClass(Prompt.class);
        ToolPlanService svc = service(routerReturning("{\"tool\":\"NONE\",\"arguments\":{}}", seen), true);

        ToolPlanService.Planned planned = svc.plan("hello", GUEST, Locale.ENGLISH);

        assertThat(planned.called()).isTrue();
        assertThat(planned.plan()).isNull();
        String system = ((SystemMessage) seen.getValue().getInstructions().get(0)).getText();
        assertThat(system).contains("tool selector").contains("- queryCatalog:").doesNotContain("{rules}");
    }

    @Test
    @DisplayName("LLM 호출이 던져도 턴을 실패시키지 않는다 — 계획 없음, 호출 안 한 것으로 센다")
    void callFailureIsNoPlan() {
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any()))
                .thenThrow(new IllegalStateException("provider down"));

        ToolPlanService.Planned planned = service(router, true).plan("문서 몇 개?", GUEST, Locale.KOREAN);

        assertThat(planned.plan()).isNull();
        assertThat(planned.called()).isFalse();
    }

    @Test
    @DisplayName("꺼져 있으면(기본) LLM 을 부르지 않는다")
    void disabledNeverCallsTheModel() {
        LlmRouter router = mock(LlmRouter.class);
        ToolPlanService svc = service(router, false);

        assertThat(svc.planFor(false, ResponseMode.N, "문서 몇 개?", "u", Locale.KOREAN).called()).isFalse();
        assertThat(svc.plan("문서 몇 개?", GUEST, Locale.KOREAN).called()).isFalse();
        verify(router, never()).executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any());
    }

    @Test
    @DisplayName("Direct 요청과 S·C 모드는 적용 대상이 아니다 — N 모드 RAG 턴만")
    void appliesOnlyToStandardRagTurns() {
        ToolPlanService on = service(mock(LlmRouter.class), true);
        assertThat(on.appliesTo(false, ResponseMode.N)).isTrue();
        assertThat(on.appliesTo(true, ResponseMode.N)).isFalse();
        assertThat(on.appliesTo(false, ResponseMode.S)).isFalse();
        assertThat(on.appliesTo(false, ResponseMode.C)).isFalse();
        assertThat(service(mock(LlmRouter.class), false).appliesTo(false, ResponseMode.N)).isFalse();
    }
}
