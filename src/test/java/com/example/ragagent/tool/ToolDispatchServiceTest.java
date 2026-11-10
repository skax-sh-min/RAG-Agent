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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
    @DisplayName("계획이 없으면 아무것도 하지 않는다")
    void noPlanIsANoOp() {
        AgentState in = stateWith(null);
        assertThat(dispatch.execute(in)).isSameAs(in);
    }
}
