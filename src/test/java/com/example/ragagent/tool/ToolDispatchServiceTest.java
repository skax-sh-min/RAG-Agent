package com.example.ragagent.tool;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.llm.RoutingMode;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import com.example.ragagent.tool.change.ChangeRequestTool;
import com.example.ragagent.tool.change.ImpactAnalysisTool;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import com.example.ragagent.workflow.ImpactAnalysisWorkflow;
import java.util.List;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@code TOOL} 노드의 실체 — 결과가 근거 문서가 되고(출처는 비어 있다), 어떤 실패도 계획을 지워 돌려준다.
 */
class ToolDispatchServiceTest {

    private ToolDispatchService dispatch;

    @BeforeEach
    void setUp() {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        // 카탈로그 서비스는 목이라 null 을 돌려주는 대신 DiffTool(순수 계산)로 성공 경로를, 모르는 이름·권한으로 실패 경로를 만든다.
        ToolCatalog catalog = new ToolCatalog(
                new KnowledgeSearchTool(null, Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, mock(DocumentExportService.class), guard),
                new CatalogQueryTool(mock(CatalogQueryService.class), guard),
                new DiffTool(guard));
        dispatch = new ToolDispatchService(catalog);
    }

    private static AgentState stateWith(ToolPlan plan) {
        return AgentState.of("질문", "latest", "t1", "u1", "", RoutingMode.COST_FIRST, false, java.util.Locale.KOREAN)
                .toBuilder().toolPlan(plan).build();
    }

    @Test
    @DisplayName("성공 — 결과가 근거 문서 하나로 실리고 출처는 비며 계획은 남는다")
    void successBecomesOneEvidenceDocumentWithoutSources() {
        ToolPlan plan = new ToolPlan("generateDiff", "{\"before\":\"a\\nb\",\"after\":\"a\\nc\"}");

        AgentState out = dispatch.execute(stateWith(plan));

        assertThat(out.toolPlan()).isEqualTo(plan);
        assertThat(out.sources()).isEmpty();
        assertThat(out.retrievedDocs()).hasSize(1);
        Document doc = out.retrievedDocs().get(0);
        assertThat(doc.getText()).isNotBlank();
        assertThat(doc.getMetadata().get(MetaKey.FILENAME)).isEqualTo("Tool: generateDiff");
    }

    @Test
    @DisplayName("모르는 Tool 이름이면(권한 없음과 구별하지 않는다) 계획을 지우고 근거는 건드리지 않는다 — 그래프가 RETRIEVAL 로 간다")
    void unknownToolClearsThePlan() {
        AgentState out = dispatch.execute(stateWith(new ToolPlan("noSuchTool", "{}")));

        assertThat(out.toolPlan()).isNull();
        assertThat(out.retrievedDocs()).isEmpty();
    }

    @Test
    @DisplayName("관리자 전용 Tool 은 채팅 호출자(관리자 아님)에게 거절된다 — 모델이 이름을 대도 권한이 넓어지지 않는다")
    void adminToolIsDeniedForTheChatCaller() {
        AgentState out = dispatch.execute(stateWith(new ToolPlan("getDocument", "{\"docId\":\"x\"}")));

        assertThat(out.toolPlan()).isNull();
        assertThat(out.retrievedDocs()).isEmpty();
    }

    @Test
    @DisplayName("Tool 이 던지면(인자 오류 포함) 계획을 지운다")
    void toolFailureClearsThePlan() {
        AgentState out = dispatch.execute(stateWith(new ToolPlan("queryCatalog", "{}")));

        assertThat(out.toolPlan()).isNull();
        assertThat(out.retrievedDocs()).isEmpty();
    }

    @Test
    @DisplayName("영향도 Tool — 근거 문서가 아니라 정형 답(작업 링크)을 남기고 S 모드로, 출처·판정은 비운다")
    void impactToolLeavesAFixedAnswer() {
        ImpactAnalysisWorkflow workflow = mock(ImpactAnalysisWorkflow.class);
        org.mockito.Mockito.when(workflow.start(org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.eq("ABC API"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ImpactAnalysisWorkflow.Started(1234, "running"));
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        ToolCatalog catalog = new ToolCatalog(List.of(new ImpactAnalysisTool(workflow, guard)));
        org.springframework.context.support.ResourceBundleMessageSource bundle = new org.springframework.context.support.ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);

        AgentState out = new ToolDispatchService(catalog, bundle)
                .execute(stateWith(new ToolPlan("analyzeImpact", "{\"subject\":\"ABC API\"}")));

        assertThat(out.answer()).contains("/ui/impact-jobs/1234").doesNotContain("1,234");   // 숫자 서식(천 단위)이 링크를 깨지 않는다
        assertThat(out.responseMode()).isEqualTo(com.example.ragagent.model.ResponseMode.S);
        assertThat(out.retrievedDocs()).isEmpty();
        assertThat(out.sources()).isEmpty();
        assertThat(out.grounded()).isNull();
    }

    @Test
    @DisplayName("영향도 Tool — 이미 진행 중이면 새로 시작하지 않고 안내만(예외로 던져 검색으로 내려가지 않는다)")
    void impactToolBusyAnswer() {
        ImpactAnalysisWorkflow workflow = mock(ImpactAnalysisWorkflow.class);
        org.mockito.Mockito.when(workflow.start(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("busy"));
        ToolCatalog catalog = new ToolCatalog(List.of(new ImpactAnalysisTool(workflow, new ToolGuard(mock(AuditLogger.class)))));

        AgentState out = new ToolDispatchService(catalog)
                .execute(stateWith(new ToolPlan("analyzeImpact", "{\"subject\":\"ABC API\"}")));

        assertThat(out.toolPlan()).isNotNull();
        assertThat(out.answer()).contains("이미 진행 중");
    }

    @Test
    @DisplayName("계획이 없으면 아무것도 하지 않는다")
    void noPlanIsANoOp() {
        AgentState in = stateWith(null);
        assertThat(dispatch.execute(in)).isSameAs(in);
    }

    // ── 채팅의 유지보수 요청: 되묻고, "네" 라야 시작한다 ──────────────────────

    private MaintenanceWorkflow changeWorkflow;
    private PendingChangeConfirmations pending;
    private ToolDispatchService changeDispatch;

    private void changeSetUp() {
        changeWorkflow = mock(MaintenanceWorkflow.class);
        pending = new PendingChangeConfirmations();
        ToolCatalog catalog = new ToolCatalog(List.of(new ChangeRequestTool(changeWorkflow, new ToolGuard(mock(AuditLogger.class)))));
        org.springframework.context.support.ResourceBundleMessageSource bundle = new org.springframework.context.support.ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        changeDispatch = new ToolDispatchService(catalog, bundle, pending);
    }

    private static AgentState adminState(ToolPlan plan, boolean admin) {
        return AgentState.of("질문", "latest", "t1", "root", "", RoutingMode.COST_FIRST, false, java.util.Locale.KOREAN)
                .toBuilder().toolPlan(plan).callerAdmin(admin).build();
    }

    private static final String ARGS = "{\"instruction\":\"PostgreSQL 을 Oracle 로 바꿔 줘\",\"from\":\"PostgreSQL\",\"to\":\"Oracle\"}";

    @Test
    @DisplayName("처음 고른 계획은 되묻기만 한다 — 작업을 시작하지 않고, 바꿀 말·새 말을 되읽고, 옵션 화면 링크(미리 채움)를 준다")
    void firstPickOnlyAsks() {
        changeSetUp();

        AgentState out = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", ARGS), true));

        assertThat(out.answer()).contains("「PostgreSQL」").contains("「Oracle」").contains("수정안을 만들까요")
                .contains("\"네\"").contains("/admin/change-jobs?from=PostgreSQL&to=Oracle")
                .contains("승인한 뒤에만 적용");
        assertThat(out.responseMode()).isEqualTo(com.example.ragagent.model.ResponseMode.S);
        assertThat(out.retrievedDocs()).isEmpty();
        assertThat(pending.isPending("root", "t1")).isTrue();
        verify(changeWorkflow, org.mockito.Mockito.never()).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("모델이 semantic·caseVariants 를 채워도 읽지 않는다 — 확정 뒤에도 항상 끈 채로 시작한다(옵션은 화면에서만)")
    void optionsAreAlwaysOffInChat() {
        changeSetUp();
        String withOptions = "{\"instruction\":\"x\",\"from\":\"PostgreSQL\",\"to\":\"Oracle\",\"semantic\":true,\"caseVariants\":true}";
        org.mockito.Mockito.when(changeWorkflow.start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(new MaintenanceWorkflow.Started(1234, "running"));

        changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", withOptions), true));      // 되묻기
        changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", withOptions, true), true)); // 확정

        verify(changeWorkflow).start(org.mockito.ArgumentMatchers.eq("root"), any(), org.mockito.ArgumentMatchers.eq("PostgreSQL"),
                org.mockito.ArgumentMatchers.eq("Oracle"), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false), any());
    }

    @Test
    @DisplayName("확정된 계획만 시작한다 — Tool 이 실제로 불리고 작업 링크가 답이 된다(번호는 천 단위 서식 없이)")
    void confirmedPlanStartsTheJob() {
        changeSetUp();
        org.mockito.Mockito.when(changeWorkflow.start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(new MaintenanceWorkflow.Started(1234, "running"));

        AgentState out = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", ARGS, true), true));

        assertThat(out.answer()).contains("/admin/change-jobs/1234").doesNotContain("1,234").contains("시작했습니다");
        assertThat(out.responseMode()).isEqualTo(com.example.ragagent.model.ResponseMode.S);
    }

    @Test
    @DisplayName("이미 진행 중이면 예외로 던지지 않고 안내만 한다(검색으로 내려가면 안내가 사라진다)")
    void busyIsAnAnswerNotAFallback() {
        changeSetUp();
        org.mockito.Mockito.when(changeWorkflow.start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenThrow(new IllegalStateException("busy"));

        AgentState out = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", ARGS, true), true));

        assertThat(out.toolPlan()).isNotNull();
        assertThat(out.answer()).contains("이미 진행 중");
    }

    @Test
    @DisplayName("관리자가 아니면 되묻지도 시작하지도 않는다 — 계획을 지워 검색으로 내려가고 대기는 만들어지지 않는다(확정 계획이어도)")
    void nonAdminDoesNothing() {
        changeSetUp();

        AgentState asked = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", ARGS), false));
        AgentState confirmed = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", ARGS, true), false));

        assertThat(asked.toolPlan()).isNull();
        assertThat(asked.answer()).isNull();
        assertThat(confirmed.toolPlan()).isNull();
        assertThat(pending.isPending("root", "t1")).isFalse();
        verify(changeWorkflow, org.mockito.Mockito.never()).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("바꿀 말·새 말이 없거나 같거나 너무 길면 되묻지 않고 안내만 한다 — 대기는 만들어지지 않는다")
    void unusableTermsAreNotAskedAbout() {
        changeSetUp();
        for (String args : new String[]{
                "{\"instruction\":\"x\"}",
                "{\"instruction\":\"x\",\"from\":\"A\"}",
                "{\"instruction\":\"x\",\"from\":\"A\",\"to\":\"A\"}",
                "{\"instruction\":\"x\",\"from\":\"" + "가".repeat(201) + "\",\"to\":\"B\"}"}) {
            AgentState out = changeDispatch.execute(adminState(new ToolPlan("requestDocumentChange", args), true));
            assertThat(out.answer()).as(args).contains("분명하지 않습니다");
        }
        assertThat(pending.isPending("root", "t1")).isFalse();
    }

    @Test
    @DisplayName("되묻기 저장소가 없는 구성에서는 시작하지 않고 검색으로 내려간다")
    void noStoreMeansNoChange() {
        changeSetUp();
        ToolDispatchService noStore = new ToolDispatchService(
                new ToolCatalog(List.of(new ChangeRequestTool(changeWorkflow, new ToolGuard(mock(AuditLogger.class))))));

        AgentState out = noStore.execute(adminState(new ToolPlan("requestDocumentChange", ARGS, true), true));

        assertThat(out.toolPlan()).isNull();
        verify(changeWorkflow, org.mockito.Mockito.never()).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }
}
