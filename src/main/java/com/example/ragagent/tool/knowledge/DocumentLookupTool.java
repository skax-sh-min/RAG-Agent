package com.example.ragagent.tool.knowledge;

import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.service.DocumentExportService;
import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.ToolResultFormatter;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 읽기 Tool 둘 — 청크의 앞뒤 문맥({@code getChunkContext})과 문서 전문 구간({@code getDocument}, 관리자). AGENT_MCP_DESIGN §3.3.
 *
 * <p><b>이 클래스가 읽는 컬렉션은 {@value KnowledgeSearchTool#VERSION} 하나다.</b> 큐레이션 Q&amp;A(예약 네임스페이스 {@code curated})의
 * 청크는 id 로 직접 집어도 거절한다 — sqlite-vec 의 {@code AdminService.getChunk} 는 컬렉션을 보지 않고 id 로만 찾기 때문에, 이 확인이 없으면
 * 다른 네임스페이스의 청크가 그대로 읽힌다.
 */
@Component
public class DocumentLookupTool {

    static final int MAX_NEIGHBORS = 2;
    static final int DEFAULT_DOC_CHARS = 4_000;
    static final int MAX_DOC_CHARS = 8_000;

    public record ContextChunk(String chunkId, int index, boolean requested, String text) {}

    public record ChunkContextOutput(String docId, String file, List<ContextChunk> chunks) {}

    public record DocumentOutput(String docId, int totalChars, int offset, String text, Integer nextOffset) {}

    private final AdminService adminService;
    private final DocumentExportService exportService;
    private final ToolGuard guard;

    public DocumentLookupTool(AdminService adminService, DocumentExportService exportService, ToolGuard guard) {
        this.adminService = adminService;
        this.exportService = exportService;
        this.guard = guard;
    }

    @Tool(name = "getChunkContext",
            description = "Read the passages just before and after a passage returned by searchKnowledge, in document order.")
    public ChunkContextOutput getChunkContext(
            @ToolParam(description = "chunkId of a passage returned by searchKnowledge (required)") String chunkId,
            @ToolParam(required = false, description = "How many passages before (0-2, default 1)") Integer before,
            @ToolParam(required = false, description = "How many passages after (0-2, default 1)") Integer after,
            ToolContext context) {
        guard.require(ToolCaller.from(context), ToolAccess.PUBLIC, "getChunkContext");
        String id = ToolResultFormatter.requireText(chunkId, "chunkId");
        int b = ToolResultFormatter.clamp(before, 0, MAX_NEIGHBORS, 1);
        int a = ToolResultFormatter.clamp(after, 0, MAX_NEIGHBORS, 1);

        String collection = adminService.collectionFor(KnowledgeSearchTool.VERSION);
        AdminService.ChunkRow row = adminService.getChunk(collection, id);
        if (row == null || !readable(row)) {
            // 없는 청크와 읽을 수 없는 청크(큐레이션·다른 버전)를 같은 말로 거절한다 — 존재 여부를 흘리지 않는다.
            throw new IllegalArgumentException("청크를 찾을 수 없습니다: " + id);
        }
        int index = chunkIndex(row);
        int offset = Math.max(0, index - b);
        // 문서 순서로 정렬된 창을 읽고, 요청한 청크의 위치를 id 로 다시 찾는다 — 청크 삭제로 chunk_index 에 구멍이 있어도 이웃이 어긋나지 않는다.
        int limit = (index - offset) + a + 3;   // 요청한 청크까지 + 뒤 a개 + 구멍을 메우는 여유
        List<AdminService.ChunkRow> window = adminService.getChunks(collection, row.docId(), offset, limit);
        int pos = -1;
        for (int i = 0; i < window.size(); i++) {
            if (id.equals(window.get(i).id())) { pos = i; break; }
        }
        List<ContextChunk> out = new ArrayList<>();
        if (pos < 0) {
            out.add(new ContextChunk(row.id(), index, true, row.fullText()));
        } else {
            for (int i = Math.max(0, pos - b); i <= Math.min(window.size() - 1, pos + a); i++) {
                AdminService.ChunkRow r = window.get(i);
                if (!readable(r)) continue;
                out.add(new ContextChunk(r.id(), chunkIndex(r), i == pos, r.fullText()));
            }
        }
        return new ChunkContextOutput(row.docId(), row.filename(), List.copyOf(out));
    }

    @Tool(name = "getDocument",
            description = "Read a whole document in pieces (administrators only). Returns up to maxChars characters starting at offset "
                    + "and the nextOffset to continue from, or null at the end.")
    public DocumentOutput getDocument(
            @ToolParam(description = "docId of the document (required)") String docId,
            @ToolParam(required = false, description = "Character offset to start from (default 0)") Integer offset,
            @ToolParam(required = false, description = "Characters to return (default 4000, at most 8000)") Integer maxChars,
            ToolContext context) {
        guard.require(ToolCaller.from(context), ToolAccess.ADMIN, "getDocument");
        String id = ToolResultFormatter.requireText(docId, "docId");
        String full = exportService.reassembledText(id, KnowledgeSearchTool.VERSION);
        int start = Math.min(full.length(), ToolResultFormatter.clamp(offset, 0, Integer.MAX_VALUE, 0));
        int len = ToolResultFormatter.clamp(maxChars, 1, MAX_DOC_CHARS, DEFAULT_DOC_CHARS);
        int end = Math.min(full.length(), start + len);
        return new DocumentOutput(id, full.length(), start, full.substring(start, end), end < full.length() ? end : null);
    }

    /** 문서 청크인가 — 큐레이션 Q&amp;A 가 아니고, 메타데이터에 버전이 있으면 이 Tool 의 버전이어야 한다. */
    static boolean readable(AdminService.ChunkRow row) {
        if (row.isCurated()) return false;
        String version = row.metadata().get(MetaKey.VERSION);
        return version == null || version.isBlank() || KnowledgeSearchTool.VERSION.equals(version);
    }

    private static int chunkIndex(AdminService.ChunkRow row) {
        try {
            return Integer.parseInt(row.metadata().getOrDefault(MetaKey.CHUNK_INDEX, "0").strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
