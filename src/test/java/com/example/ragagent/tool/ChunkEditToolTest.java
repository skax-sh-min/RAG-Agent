package com.example.ragagent.tool;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.model.TextHash;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.tool.change.ChunkEditTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import com.example.ragagent.workflow.AnchoredEdit;
import com.example.ragagent.workflow.ChangeProposalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 외부 에이전트의 지식 수정 Tool 셋(AGENT_MCP_DESIGN §10.9) — 관리자만 보이고·부를 수 있고, 모델이 만든 JSON(편집 작업 배열)이 그대로 제안 서비스로
 * 간다. Tool 은 아무것도 쓰지 않는다(쓰기는 승인 뒤 적용 단계뿐 — {@code ToolBoundaryConventionTest} 가 소스를 본다).
 */
class ChunkEditToolTest {

    private static final ToolCaller ADMIN = new ToolCaller("mcp-admin", true, "mcp");
    private static final ToolCaller READER = new ToolCaller("mcp-read", false, "mcp");

    private ChangeProposalService proposals;
    private KeywordSearchRepository keywords;
    private AdminService admin;
    private ToolCatalog catalog;

    @BeforeEach
    void setUp() {
        proposals = mock(ChangeProposalService.class);
        keywords = mock(KeywordSearchRepository.class);
        admin = mock(AdminService.class);
        when(admin.collectionFor(anyString())).thenReturn("latest");
        ChunkEditTool tool = new ChunkEditTool(proposals, keywords, mock(KnowledgeSearchTool.class), admin,
                new ToolGuard(mock(AuditLogger.class)));
        catalog = new ToolCatalog(List.of(tool));
    }

    @Test
    @DisplayName("세 Tool 모두 관리자 전용 — 읽기 호출자에게는 스키마조차 없고 불러도 거절된다")
    void adminOnly() {
        assertThat(catalog.forCaller(READER)).isEmpty();
        assertThat(catalog.forCaller(ADMIN)).extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("findChunks", "proposeChunkEdit", "getChangeJob");
        assertThatThrownBy(() -> catalog.call("proposeChunkEdit", "{}", READER)).isInstanceOf(ToolDeniedException.class);
        verify(proposals, never()).proposeExternal(any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("findChunks(mentions) — 전수 조회 결과를 청크마다 contentHash 와 함께, 큐레이션·다른 버전은 빼고")
    void findChunksCarriesTheHash() {
        when(keywords.findMentions("latest", "a.timeout", 20)).thenReturn(new KeywordSearchRepository.Mentions(
                List.of(new Document("c1", "x", Map.of()), new Document("cq", "x", Map.of())), false, false));
        String body = "설정 키는 a.timeout 이다.";
        when(admin.getChunk("latest", "c1")).thenReturn(new AdminService.ChunkRow("c1", body, body,
                Map.of(MetaKey.DOC_ID, "d1", MetaKey.FILENAME, "guide.pdf", MetaKey.VERSION, "latest", MetaKey.HEADING, "설정")));
        when(admin.getChunk("latest", "cq")).thenReturn(new AdminService.ChunkRow("cq", "Q", "Q", Map.of(MetaKey.DOC_TYPE, "curated_qa")));

        String out = catalog.call("findChunks", "{\"subject\":\"a.timeout\"}", ADMIN);

        assertThat(out).contains("\"chunkId\":\"c1\"").contains(TextHash.sha256(body)).contains("\"heading\":\"설정\"").doesNotContain("cq");
    }

    @Test
    @DisplayName("proposeChunkEdit — 모델이 만든 편집 배열이 그대로 제안 서비스로 간다(호출자는 인자가 아니라 문맥에서)")
    void proposeDelegatesTheParsedOps() {
        when(proposals.proposeExternal(eq("mcp-admin"), eq("c1"), eq("h"), any(), isNull(), eq(false), eq("안내"), isNull()))
                .thenReturn(new ChangeProposalService.ProposeResult(true, 7, 11, List.of(), "/admin/change-jobs/7"));

        String out = catalog.call("proposeChunkEdit", """
                {"chunkId":"c1","baseHash":"h","rationale":"안내",
                 "ops":[{"op":"insertAfter","anchor":"30초 동안 기다린다.","text":"> 주의"},
                        {"op":"delete","anchor":"설정 키는","endAnchor":"이다."}]}""", ADMIN);

        assertThat(out).contains("\"accepted\":true").contains("/admin/change-jobs/7");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AnchoredEdit.Operation>> ops = ArgumentCaptor.forClass(List.class);
        verify(proposals).proposeExternal(eq("mcp-admin"), eq("c1"), eq("h"), ops.capture(), isNull(), eq(false), eq("안내"), isNull());
        assertThat(ops.getValue()).containsExactly(
                AnchoredEdit.Operation.insertAfter("30초 동안 기다린다.", "> 주의"),
                AnchoredEdit.Operation.delete("설정 키는", "이다."));
    }

    @Test
    @DisplayName("모르는 op 는 인자 오류다")
    void unknownOpIsAnArgumentError() {
        assertThatThrownBy(() -> catalog.call("proposeChunkEdit",
                "{\"chunkId\":\"c1\",\"baseHash\":\"h\",\"rationale\":\"x\",\"ops\":[{\"op\":\"rewrite\",\"anchor\":\"a\"}]}", ADMIN))
                .hasMessageContaining("op");
    }

    @Test
    @DisplayName("getChangeJob — 유지보수 작업이 아니면 인자 오류, 있으면 요약")
    void getChangeJob() {
        when(proposals.jobSummary(eq(7L), anyIntValue(), anyIntValue())).thenReturn(Optional.of(new ChangeProposalService.JobSummary(
                7, "MCP: 안내", "done", null, 1, Map.of("pending", 1), List.of(), false, "/admin/change-jobs/7")));

        assertThat(catalog.call("getChangeJob", "{\"jobId\":7}", ADMIN)).contains("\"reviewUrl\":\"/admin/change-jobs/7\"");
        assertThatThrownBy(() -> catalog.call("getChangeJob", "{\"jobId\":8}", ADMIN)).hasMessageContaining("8");
    }

    private static int anyIntValue() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
