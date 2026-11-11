package com.example.ragagent.evaluation;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.config.OpenAiClients;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.llm.TaskType;
import com.example.ragagent.llm.ThinkingControl;
import com.example.ragagent.llm.ThinkingWire;
import com.example.ragagent.model.ResponseMode;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolCatalog;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.ToolPlan;
import com.example.ragagent.tool.ToolPlanService;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §6.33 3단계 <b>앱 경로 실측</b> — 실제 {@link ToolPlanService}(실제 번들 프롬프트 · 실제 파싱·인자 검증)를 로컬 서버에 붙여
 * {@code tool-gold.json} + 검색 골든셋의 질문을 돌린다. 사전 평가({@link ToolCallingEvaluationTest})는 앱 밖 하네스의 프롬프트로 쟀다 —
 * 이것은 앱이 실제로 보내는 프롬프트로 같은 세트를 다시 재서 둘이 갈라졌는지 본다.
 *
 * <p>채팅의 선택지는 셋뿐이라(영향도·유지보수 Tool 은 4단계) 기대 Tool 이 그 둘인 질문은 <b>"계획 없음 또는 검색"</b>이 맞는 답이다
 * (그 질문은 어차피 검색으로 내려간다). 서버·모델: {@code -Dtool-app-eval.base-url}(기본 {@code http://127.0.0.1:1234/v1}),
 * {@code -Dtool-app-eval.model}(기본 {@code gemma-4-E2B}). 기본 skip.
 */
@EnabledIfSystemProperty(named = "tool-app-eval.enabled", matches = "true")
class ToolPlanAppPathEvaluationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reportAppPathToolSelection() throws Exception {
        String baseUrl = System.getProperty("tool-app-eval.base-url", "http://127.0.0.1:1234/v1");
        String modelId = System.getProperty("tool-app-eval.model", "gemma-4-E2B");

        OpenAiChatModel raw = OpenAiClients.chatModel(
                OpenAiClients.clientOptions(baseUrl, "local", 5, 60, 0),
                OpenAiChatOptions.builder().model(modelId).build());
        // 앱의 체인(ThinkingControlChatModel)이 하는 일 — 표시를 걷고 생각 끔을 싣는다.
        ThinkingWire off = new ThinkingWire(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)), null, ThinkingWire.Sent.OFF);
        ChatModel model = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                OpenAiChatOptions o = ThinkingControl.applyTo((OpenAiChatOptions) prompt.getOptions(), off);
                return raw.call(new Prompt(prompt.getInstructions(), o.mutate().model(modelId).build()));
            }
        };
        LlmRouter router = mock(LlmRouter.class);
        when(router.executeGatedWithUsage(any(TaskType.class), any(RoutingMode.class), any())).thenAnswer(inv -> {
            Function<ChatModel, org.springframework.ai.chat.model.ChatResponse> fn = inv.getArgument(2);
            org.springframework.ai.chat.model.ChatResponse r = fn.apply(model);
            return new LlmRouter.LlmResult(r.getResult().getOutput().getText(), 0, 0);
        });

        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        ToolCatalog catalog = new ToolCatalog(
                new KnowledgeSearchTool(null, Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, mock(DocumentExportService.class), guard),
                new CatalogQueryTool(mock(CatalogQueryService.class), guard),
                new DiffTool(guard),
                new com.example.ragagent.tool.change.ImpactAnalysisTool(mock(com.example.ragagent.workflow.ImpactAnalysisWorkflow.class), guard),
                new com.example.ragagent.tool.change.ChangeRequestTool(mock(com.example.ragagent.workflow.MaintenanceWorkflow.class), guard));
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        AppProperties props = mock(AppProperties.class);
        AppProperties.LlmConfig llm = mock(AppProperties.LlmConfig.class);
        when(llm.temperature()).thenReturn(0.2);
        when(llm.maxTokens()).thenReturn(12_000);
        when(props.llmSafe()).thenReturn(llm);
        ToolPlanService service = new ToolPlanService(router, catalog, bundle, props, true);

        // 질문 세트: tool-gold.json 23건 + 검색 골든셋 26건(expected=searchKnowledge).
        List<String[]> cases = new ArrayList<>();   // id, kind, expected, question
        JsonNode gold = JSON.readTree(new ClassPathResource("agent-eval/tool-gold.json").getInputStream());
        for (JsonNode c : gold.path("cases")) {
            cases.add(new String[]{c.path("id").asString(), c.path("kind").asString(), c.path("expected").asString(), c.path("question").asString()});
        }
        JsonNode docs = JSON.readTree(new ClassPathResource("search-eval/nexcore-gold.json").getInputStream());
        for (JsonNode c : docs.path("cases")) {
            cases.add(new String[]{c.path("id").asString("doc"), "doc", "searchKnowledge", c.path("question").asString(c.path("query").asString())});
        }

        for (boolean admin : new boolean[]{false, true}) {
            Map<String, int[]> byKind = new LinkedHashMap<>();   // kind → {맞음, 전체}
            int wrongDetour = 0, docTotal = 0, calls = 0, nullPlans = 0;
            List<String> misses = new ArrayList<>();
            long totalMs = 0;
            for (String[] c : cases) {
                long t0 = System.nanoTime();
                ToolPlanService.Planned p = service.planFor(false, ResponseMode.N, c[3], "u", admin, "t-" + c[0], Locale.KOREAN);
                totalMs += (System.nanoTime() - t0) / 1_000_000;
                calls += p.called() ? 1 : 0;
                ToolPlan plan = p.plan();
                String picked = plan == null ? "NONE" : plan.toolName();
                if (plan == null) nullPlans++;
                // 채팅에서 고를 수 없는 기대(영향도·유지보수)는 "검색으로 내려감(NONE 또는 searchKnowledge)"이 맞다.
                boolean chatTool = List.of("searchKnowledge", "findRelatedDocuments", "queryCatalog", "analyzeImpact").contains(c[2])
                        || ("requestDocumentChange".equals(c[2]) && admin);
                boolean ok = chatTool ? picked.equals(c[2]) || ("NONE".equals(c[2]) && picked.equals("NONE"))
                        : (picked.equals("NONE") || picked.equals("searchKnowledge"));
                if ("NONE".equals(c[2])) ok = picked.equals("NONE") || picked.equals("searchKnowledge");   // 잡담은 검색으로 가도 안전(NONE 이 이상적)
                // 문서 질문이 검색이 아닌 Tool 로 우회하면 틀린 우회다.
                if ("doc".equals(c[1])) {
                    docTotal++;
                    if (!picked.equals("NONE") && !picked.equals("searchKnowledge")) wrongDetour++;
                    ok = picked.equals("NONE") || picked.equals("searchKnowledge");
                }
                int[] k = byKind.computeIfAbsent(c[1], x -> new int[2]);
                k[1]++;
                if (ok) k[0]++; else misses.add(c[0] + " [" + c[1] + "] expected=" + c[2] + " picked=" + picked + " : " + c[3]);
            }
            StringBuilder sb = new StringBuilder("\n== 앱 경로 Tool 선택 (" + modelId + ") ==\n");
            int right = 0, all = 0;
            for (var e : byKind.entrySet()) {
                sb.append(String.format("%-10s %d/%d%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
                right += e.getValue()[0];
                all += e.getValue()[1];
            }
            sb.append(String.format("정확도(채팅 관점) %d/%d = %.3f  문서 질문의 잘못된 우회 %d/%d  LLM 호출 %d  계획 없음 %d  평균 %d ms%n",
                    right, all, right / (double) all, wrongDetour, docTotal, calls, nullPlans, totalMs / Math.max(1, cases.size())));
            misses.forEach(m -> sb.append("MISS ").append(m).append('\n'));
            System.out.println(sb);
        }
    }
}
