package com.example.ragagent.tool.catalog;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.model.DocumentInfo;
import com.example.ragagent.repository.LlmUsageRepository;
import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.service.ChunkReportService;
import com.example.ragagent.service.RagService;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolDeniedException;
import com.example.ragagent.tool.ToolGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** queryCatalog — 이름 있는 조회만, 관리자 조회는 이름으로 불러도 거부, 결과 50행 상한. */
class CatalogQueryToolTest {

    private static final ToolCaller GUEST = ToolCaller.guest();
    private static final ToolCaller ADMIN = new ToolCaller("root", true, "mcp");

    private RagService rag;
    private ChunkReportService reports;
    private LlmUsageRepository usage;
    private CatalogQueryTool tool;

    @BeforeEach
    void setUp() {
        rag = mock(RagService.class);
        reports = mock(ChunkReportService.class);
        usage = mock(LlmUsageRepository.class);
        tool = new CatalogQueryTool(new CatalogQueryService(rag, reports, usage), new ToolGuard(mock(AuditLogger.class)));
    }

    private static DocumentInfo info(int i, List<String> tags, String version) {
        return new DocumentInfo("d" + i, "f" + i + ".md", i % 2 == 0 ? "표시 " + i : null, version, i, "2026-11-0" + (1 + i % 5), "sha",
                tags, List.of());
    }

    @Test
    @DisplayName("documents.list — 태그로 거르고 표시 이름을 쓴다, 다른 버전 문서는 빠진다")
    void documentsListFiltersByTag() {
        when(rag.listDocuments(anyString())).thenReturn(List.of(
                info(0, List.of("배치"), "latest"), info(1, List.of("온라인"), "latest"), info(2, List.of("배치"), "v1")));

        CatalogQueryService.Result all = tool.queryCatalog("documents.list", null, GUEST.toContext());
        assertThat(all.rows()).extracting(r -> r.get("docId")).containsExactly("d0", "d1");
        assertThat(all.rows().get(0).get("file")).isEqualTo("표시 0");
        assertThat(all.rows().get(1).get("file")).isEqualTo("f1.md");

        assertThat(tool.queryCatalog("documents.list", "배치", GUEST.toContext()).rows())
                .extracting(r -> r.get("docId")).containsExactly("d0");
    }

    @Test
    @DisplayName("documents.recent — 개수 인자(기본 10, 최대 50)와 50행 상한 · truncated")
    void recentAndTheRowCap() {
        List<DocumentInfo> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(info(i, List.of(), "latest"));
        when(rag.listDocuments(anyString())).thenReturn(many);

        assertThat(tool.queryCatalog("documents.recent", null, GUEST.toContext()).rows()).hasSize(10);
        assertThat(tool.queryCatalog("documents.recent", "3", GUEST.toContext()).rows()).hasSize(3);
        assertThat(tool.queryCatalog("documents.recent", "abc", GUEST.toContext()).rows()).hasSize(10);
        CatalogQueryService.Result capped = tool.queryCatalog("documents.recent", "999", GUEST.toContext());
        assertThat(capped.rows()).hasSize(50);
        assertThat(capped.truncated()).isTrue();
        CatalogQueryService.Result list = tool.queryCatalog("documents.list", null, GUEST.toContext());
        assertThat(list.rows()).hasSize(50);
        assertThat(list.truncated()).isTrue();
    }

    @Test
    @DisplayName("tags.list — latest 버전의 태그")
    void tagsList() {
        when(rag.listTags("latest")).thenReturn(List.of("배치", "온라인"));
        assertThat(tool.queryCatalog("tags.list", null, GUEST.toContext()).rows()).extracting(r -> r.get("tag"))
                .containsExactly("배치", "온라인");
    }

    @Test
    @DisplayName("관리자 조회(reports.open · usage.summary)는 게스트가 이름으로 불러도 거부되고 서비스는 건드리지 않는다")
    void adminQueriesAreDeniedForGuests() {
        assertThatThrownBy(() -> tool.queryCatalog("reports.open", null, GUEST.toContext())).isInstanceOf(ToolDeniedException.class);
        assertThatThrownBy(() -> tool.queryCatalog("usage.summary", null, GUEST.toContext())).isInstanceOf(ToolDeniedException.class);
        assertThatThrownBy(() -> tool.queryCatalog("USAGE.SUMMARY", null, null)).isInstanceOf(ToolDeniedException.class);
        verifyNoInteractions(reports, usage);
    }

    @Test
    @DisplayName("관리자는 reports.open · usage.summary 를 읽는다")
    void adminQueriesWork() {
        when(reports.openGroups(0, 50)).thenReturn(List.of(
                new com.example.ragagent.repository.ChunkReportRepository.Group("c1", "d1", "latest", "f.md", 2, "a", "b")));
        when(reports.openChunkCount()).thenReturn(1);
        when(usage.usedProviders()).thenReturn(Set.of("local"));
        when(usage.getDaily("local")).thenReturn(new LlmUsageRepository.PeriodSummary(10, 5, 2));
        when(usage.getMonthly("local")).thenReturn(new LlmUsageRepository.PeriodSummary(100, 50, 20));

        CatalogQueryService.Result r = tool.queryCatalog("reports.open", null, ADMIN.toContext());
        assertThat(r.rows().get(0)).containsEntry("chunkId", "c1").containsEntry("reportCount", 2);
        assertThat(r.truncated()).isFalse();

        CatalogQueryService.Result u = tool.queryCatalog("usage.summary", null, ADMIN.toContext());
        assertThat(u.rows().get(0)).containsEntry("provider", "local").containsEntry("todayTokens", 15L).containsEntry("monthCalls", 20L);
    }

    @Test
    @DisplayName("모르는 이름은 거절한다 — 안내 목록은 호출자가 쓸 수 있는 이름만(게스트에게 관리자 조회 이름을 알리지 않는다)")
    void unknownNameListsOnlyPermittedNames() {
        Throwable guest = org.assertj.core.api.Assertions.catchThrowable(() -> tool.queryCatalog("select * from users", null, GUEST.toContext()));
        assertThat(guest).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents.list").hasMessageContaining("tags.list")
                .hasMessageNotContaining("usage.summary").hasMessageNotContaining("reports.open");
        Throwable admin = org.assertj.core.api.Assertions.catchThrowable(() -> tool.queryCatalog("users", null, ADMIN.toContext()));
        assertThat(admin).hasMessageContaining("usage.summary");
        verify(rag, never()).listDocuments(anyString());
    }

    @Test
    @DisplayName("조회 이름 집합은 닫혀 있다 — 이 다섯 개뿐이고 민감 테이블 이름은 어느 것도 아니다")
    void theNameSetIsClosed() {
        assertThat(CatalogQueryService.Query.names()).containsExactlyInAnyOrder(
                "documents.list", "documents.recent", "tags.list", "reports.open", "usage.summary");
        for (String sensitive : List.of("users", "app_secret", "persistent_logins", "settings_override", "llm_provider_override", "curated_qa")) {
            assertThat(CatalogQueryService.Query.parse(sensitive)).as(sensitive).isEmpty();
        }
    }
}
