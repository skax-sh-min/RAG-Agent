package com.example.ragagent.tool.knowledge;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolDeniedException;
import com.example.ragagent.tool.ToolGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** getChunkContext · getDocument — 이웃 청크, 큐레이션·다른 버전 청크 거절, 관리자 전용 문서 읽기와 구간 나누기. */
class DocumentLookupToolTest {

    private static final ToolCaller GUEST = ToolCaller.guest();
    private static final ToolCaller ADMIN = new ToolCaller("root", true, "mcp");

    private AdminService admin;
    private DocumentExportService export;
    private DocumentLookupTool tool;

    @BeforeEach
    void setUp() {
        admin = mock(AdminService.class);
        export = mock(DocumentExportService.class);
        when(admin.collectionFor("latest")).thenReturn("latest");
        tool = new DocumentLookupTool(admin, export, new ToolGuard(mock(AuditLogger.class)));
    }

    private static AdminService.ChunkRow row(String id, int index, String text, Map<String, String> extra) {
        Map<String, String> meta = new HashMap<>(Map.of("doc_id", "d1", "filename", "d1.md", "chunk_index", String.valueOf(index)));
        meta.putAll(extra);
        return new AdminService.ChunkRow(id, text, text, meta);
    }

    private void document(int count) {
        List<AdminService.ChunkRow> all = new ArrayList<>();
        for (int i = 0; i < count; i++) all.add(row("c" + i, i, "청크 " + i, Map.of()));
        when(admin.getChunk(eq("latest"), anyString())).thenAnswer(inv ->
                all.stream().filter(r -> r.id().equals(inv.getArgument(1))).findFirst().orElse(null));
        when(admin.getChunks(eq("latest"), eq("d1"), anyInt(), anyInt())).thenAnswer(inv -> {
            int off = inv.getArgument(2);
            int lim = inv.getArgument(3);
            return all.subList(Math.min(off, all.size()), Math.min(all.size(), off + lim));
        });
    }

    @Test
    @DisplayName("앞뒤 한 칸이 기본이고 요청한 청크가 표시된다")
    void neighborsDefaultToOneEachSide() {
        document(6);
        DocumentLookupTool.ChunkContextOutput out = tool.getChunkContext("c3", null, null, GUEST.toContext());

        assertThat(out.docId()).isEqualTo("d1");
        assertThat(out.chunks()).extracting(DocumentLookupTool.ContextChunk::chunkId).containsExactly("c2", "c3", "c4");
        assertThat(out.chunks()).filteredOn(DocumentLookupTool.ContextChunk::requested).extracting(DocumentLookupTool.ContextChunk::chunkId)
                .containsExactly("c3");
    }

    @Test
    @DisplayName("이웃 수는 2 로 눌리고 문서 처음·끝에서 잘린다")
    void neighborsAreClampedAndBounded() {
        document(6);
        assertThat(tool.getChunkContext("c0", 9, 9, GUEST.toContext()).chunks())
                .extracting(DocumentLookupTool.ContextChunk::chunkId).containsExactly("c0", "c1", "c2");
        assertThat(tool.getChunkContext("c5", 9, 0, GUEST.toContext()).chunks())
                .extracting(DocumentLookupTool.ContextChunk::chunkId).containsExactly("c3", "c4", "c5");
    }

    @Test
    @DisplayName("큐레이션 Q&A 청크는 id 로 직접 집어도 거절한다 — 없는 청크와 같은 말로")
    void curatedChunkIsRefusedLikeAMissingOne() {
        when(admin.getChunk(eq("latest"), eq("cur1"))).thenReturn(row("cur1", 0, "큐레이션", Map.of("doc_type", "curated_qa")));
        when(admin.getChunk(eq("latest"), eq("nope"))).thenReturn(null);

        Throwable curated = org.assertj.core.api.Assertions.catchThrowable(() -> tool.getChunkContext("cur1", 1, 1, GUEST.toContext()));
        Throwable missing = org.assertj.core.api.Assertions.catchThrowable(() -> tool.getChunkContext("nope", 1, 1, GUEST.toContext()));

        assertThat(curated).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("청크를 찾을 수 없습니다");
        assertThat(missing).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("청크를 찾을 수 없습니다");
        verify(admin, never()).getChunks(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("latest 가 아닌 버전의 청크는 거절한다 — sqlite-vec 의 getChunk 는 컬렉션을 보지 않고 id 로만 찾는다")
    void otherVersionChunkIsRefused() {
        when(admin.getChunk(eq("latest"), eq("old1"))).thenReturn(row("old1", 0, "옛 버전", Map.of("version", "v1")));
        assertThatThrownBy(() -> tool.getChunkContext("old1", 1, 1, GUEST.toContext()))
                .isInstanceOf(IllegalArgumentException.class);
        // 버전이 latest 로 적혀 있으면 읽힌다
        when(admin.getChunk(eq("latest"), eq("new1"))).thenReturn(row("new1", 0, "최신", Map.of("version", "latest")));
        when(admin.getChunks(eq("latest"), eq("d1"), anyInt(), anyInt())).thenReturn(List.of(row("new1", 0, "최신", Map.of("version", "latest"))));
        assertThat(tool.getChunkContext("new1", 0, 0, GUEST.toContext()).chunks()).hasSize(1);
    }

    @Test
    @DisplayName("이웃 가운데 큐레이션 청크가 섞여 있어도 빠진다")
    void curatedNeighborsAreDropped() {
        List<AdminService.ChunkRow> window = List.of(row("c1", 1, "앞", Map.of()),
                row("c2", 2, "본인", Map.of()), row("x", 3, "큐레이션", Map.of("doc_type", "curated_qa")));
        when(admin.getChunk(eq("latest"), eq("c2"))).thenReturn(window.get(1));
        when(admin.getChunks(eq("latest"), eq("d1"), anyInt(), anyInt())).thenReturn(window);

        assertThat(tool.getChunkContext("c2", 1, 1, GUEST.toContext()).chunks())
                .extracting(DocumentLookupTool.ContextChunk::chunkId).containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("이웃을 못 찾아도(구멍·삭제) 요청한 청크 하나는 돌려준다")
    void fallsBackToTheRequestedChunk() {
        when(admin.getChunk(eq("latest"), eq("c9"))).thenReturn(row("c9", 9, "혼자", Map.of()));
        when(admin.getChunks(eq("latest"), eq("d1"), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(tool.getChunkContext("c9", 1, 1, GUEST.toContext()).chunks())
                .extracting(DocumentLookupTool.ContextChunk::text).containsExactly("혼자");
    }

    @Test
    @DisplayName("getDocument 는 관리자만 — 게스트는 거부되고 재조립은 시작도 하지 않는다")
    void getDocumentIsAdminOnly() {
        assertThatThrownBy(() -> tool.getDocument("d1", 0, 100, GUEST.toContext())).isInstanceOf(ToolDeniedException.class);
        verify(export, never()).reassembledText(anyString(), anyString());
    }

    @Test
    @DisplayName("getDocument — 구간으로 나눠 읽고 끝에서 nextOffset 이 null, 기본 4000자 · 상한 8000자")
    void getDocumentReadsInSlices() {
        String text = "가".repeat(10_000);
        when(export.reassembledText("d1", "latest")).thenReturn(text);

        DocumentLookupTool.DocumentOutput first = tool.getDocument("d1", null, null, ADMIN.toContext());
        assertThat(first.totalChars()).isEqualTo(10_000);
        assertThat(first.text()).hasSize(4_000);
        assertThat(first.nextOffset()).isEqualTo(4_000);

        DocumentLookupTool.DocumentOutput big = tool.getDocument("d1", 4_000, 50_000, ADMIN.toContext());
        assertThat(big.text()).hasSize(6_000);
        assertThat(big.nextOffset()).isNull();

        DocumentLookupTool.DocumentOutput capped = tool.getDocument("d1", 0, 50_000, ADMIN.toContext());
        assertThat(capped.text()).hasSize(8_000);
        assertThat(capped.nextOffset()).isEqualTo(8_000);

        DocumentLookupTool.DocumentOutput past = tool.getDocument("d1", 99_999, 10, ADMIN.toContext());
        assertThat(past.text()).isEmpty();
        assertThat(past.nextOffset()).isNull();
    }

    @Test
    @DisplayName("getDocument 는 latest 버전만 읽는다 — 문서 id 가 있어도 다른 네임스페이스로 가지 않는다")
    void getDocumentUsesTheFixedVersion() {
        when(export.reassembledText(anyString(), anyString())).thenReturn("본문");
        tool.getDocument("curated", 0, 10, ADMIN.toContext());
        verify(export).reassembledText("curated", "latest");
    }
}
