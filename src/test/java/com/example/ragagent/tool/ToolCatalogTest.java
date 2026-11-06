package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tool 목록·권한 — 호출자에 따라 목록이 걸러지고, 이름으로 부른 호출도 같은 권한을 지나며, 선언 없는 Tool 은 기동을 막는다.
 * 의존 서비스는 목이고 {@code DiffTool} 만 실제로 돈다(순수 계산이라).
 */
class ToolCatalogTest {

    private static final ToolCaller GUEST = ToolCaller.guest();
    private static final ToolCaller ADMIN = new ToolCaller("root", true, "mcp");

    private DocumentExportService exportService;
    private CatalogQueryService catalogService;
    private ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        exportService = mock(DocumentExportService.class);
        catalogService = mock(CatalogQueryService.class);
        catalog = new ToolCatalog(
                new KnowledgeSearchTool(null, java.util.Optional.empty(), null, null, guard),
                new DocumentLookupTool(null, exportService, guard),
                new CatalogQueryTool(catalogService, guard),
                new DiffTool(guard));
    }

    private static List<String> names(List<ToolCallback> list) {
        return list.stream().map(cb -> cb.getToolDefinition().name()).toList();
    }

    @Test
    @DisplayName("등록된 Tool 과 등급 — 여섯 개, getDocument 만 ADMIN")
    void accessTable() {
        assertThat(catalog.accessTable()).containsOnlyKeys(
                "searchKnowledge", "findRelatedDocuments", "queryCatalog", "getChunkContext", "generateDiff", "getDocument");
        assertThat(catalog.accessTable().get("getDocument")).isEqualTo(ToolAccess.ADMIN);
        assertThat(catalog.accessTable().entrySet().stream().filter(e -> e.getValue() == ToolAccess.ADMIN).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("목록은 호출자로 거른다 — 게스트는 getDocument 의 스키마도 못 본다")
    void listIsFilteredByCaller() {
        assertThat(names(catalog.forCaller(GUEST))).doesNotContain("getDocument").hasSize(5);
        assertThat(names(catalog.forCaller(ADMIN))).contains("getDocument").hasSize(6);
        assertThat(names(catalog.forCaller(null))).doesNotContain("getDocument");
    }

    @Test
    @DisplayName("LLM 이 보는 스키마에 version · userId · 호출자 컨텍스트가 없다 — 서버가 정한다")
    void schemasHideServerOwnedInputs() {
        for (ToolCallback cb : catalog.forCaller(ADMIN)) {
            String schema = cb.getToolDefinition().inputSchema();
            assertThat(schema).as(cb.getToolDefinition().name()).doesNotContain("\"version\"", "\"userId\"", "\"user\"", "context", "caller");
        }
    }

    @Test
    @DisplayName("관리자 Tool 을 이름으로 불러도 게스트는 거부된다 — 서비스는 호출되지 않는다")
    void guestCannotCallAdminToolByName() {
        assertThatThrownBy(() -> catalog.call("getDocument", "{\"docId\":\"d1\"}", GUEST))
                .isInstanceOf(ToolDeniedException.class);
        verifyNoInteractions(exportService);
    }

    @Test
    @DisplayName("모르는 이름과 권한 없는 이름은 같은 예외다 — 존재 여부를 구별해 알려주지 않는다")
    void unknownAndForbiddenLookAlike() {
        assertThatThrownBy(() -> catalog.call("dropEverything", "{}", ADMIN)).isInstanceOf(ToolDeniedException.class);
        assertThatThrownBy(() -> catalog.call(null, "{}", ADMIN)).isInstanceOf(ToolDeniedException.class);
        assertThatThrownBy(() -> catalog.call("getDocument", "{}", GUEST)).isInstanceOf(ToolDeniedException.class);
        assertThat(catalog.find("getDocument", GUEST)).isEmpty();
        assertThat(catalog.find("getDocument", ADMIN)).isPresent();
    }

    @Test
    @DisplayName("이름으로 부르면 호출자는 ToolContext 로 들어가 Tool 이 실행된다 — generateDiff")
    void callRunsTheToolWithTheCaller() {
        String json = catalog.call("generateDiff", "{\"before\":\"a\\nb\",\"after\":\"a\\nc\"}", GUEST);
        assertThat(json).contains("\"addedLines\":1").contains("\"removedLines\":1").contains("-b").contains("+c");
    }

    @Test
    @DisplayName("접근 등급이 선언되지 않은 Tool 이 있으면 기동이 실패한다 — 조용히 공개되지 않는다")
    void undeclaredToolFailsStartup() {
        class Rogue {
            @Tool(name = "wipeIndex", description = "x")
            public String wipe() { return "x"; }
        }
        assertThatThrownBy(() -> new ToolCatalog(List.of(new Rogue())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wipeIndex");
    }

    @Test
    @DisplayName("Tool 이름이 겹치면 기동이 실패한다")
    void duplicateNamesFail() {
        ToolGuard guard = new ToolGuard(mock(AuditLogger.class));
        assertThatThrownBy(() -> new ToolCatalog(List.of(new DiffTool(guard), new DiffTool(guard))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generateDiff");
    }

    @Test
    @DisplayName("Tool 메서드는 첫 줄에서 권한을 검사한다 — 카탈로그를 거치지 않고 직접 불러도 getDocument 는 거부")
    void directInvocationIsGuardedToo() {
        DocumentLookupTool tool = new DocumentLookupTool(null, exportService, new ToolGuard(mock(AuditLogger.class)));
        assertThatThrownBy(() -> tool.getDocument("d1", 0, 100, GUEST.toContext())).isInstanceOf(ToolDeniedException.class);
        assertThatThrownBy(() -> tool.getDocument("d1", 0, 100, null)).isInstanceOf(ToolDeniedException.class);
        verifyNoInteractions(exportService);
    }
}
