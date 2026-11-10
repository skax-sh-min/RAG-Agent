package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.config.AppProperties;
import com.example.ragagent.llm.LlmRouter;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.ChangeRequestTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.change.ImpactAnalysisTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import com.example.ragagent.workflow.ImpactAnalysisWorkflow;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.Mockito.mock;

/** 개발용 — 관리자에게 실제로 나가는 Tool 선택 프롬프트를 파일로 덤프한다({@code -Dtool-plan.dump=경로}). 기본 skip. */
@EnabledIfSystemProperty(named = "tool-plan.dump", matches = ".+")
class ToolPlanPromptDumpTest {

    @Test
    void dumpAdminPrompt() throws Exception {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        ToolCatalog catalog = new ToolCatalog(List.of(
                new KnowledgeSearchTool(null, Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, mock(DocumentExportService.class), guard),
                new CatalogQueryTool(mock(CatalogQueryService.class), guard),
                new DiffTool(guard),
                new ImpactAnalysisTool(mock(ImpactAnalysisWorkflow.class), guard),
                new ChangeRequestTool(mock(MaintenanceWorkflow.class), guard)));
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        ToolPlanService svc = new ToolPlanService(mock(LlmRouter.class), catalog, bundle, mock(AppProperties.class), true);
        String tools = ToolPlanService.toolList(svc.visibleTools(new ToolCaller("root", true, "chat")));
        String rules = svc.ruleList(svc.visibleTools(new ToolCaller("root", true, "chat")), Locale.KOREAN);
        String prompt = bundle.getMessage("prompt.agent.plan", null, Locale.KOREAN).replace("{tools}", tools).replace("{rules}", rules);
        Files.writeString(Path.of(System.getProperty("tool-plan.dump")), prompt);
    }
}
