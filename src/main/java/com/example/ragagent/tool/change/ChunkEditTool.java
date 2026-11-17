package com.example.ragagent.tool.change;

import com.example.ragagent.ingestion.KeywordSearchRepository;
import com.example.ragagent.model.MetaKey;
import com.example.ragagent.service.AdminService;
import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.tool.ToolResultFormatter;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import com.example.ragagent.workflow.AnchoredEdit;
import com.example.ragagent.workflow.ChangeProposalService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 외부 에이전트의 지식 수정 Tool 셋(AGENT_MCP_DESIGN §10.9, 6b) — 모두 {@link ToolAccess#ADMIN}.
 * <ul>
 *   <li>{@code findChunks} — 고칠 청크 찾기(전수 조회 또는 검색), 청크마다 {@code contentHash}</li>
 *   <li>{@code proposeChunkEdit} — 청크 하나의 수정안을 <b>대기열에 넣기만</b> 한다. 코퍼스에는 아무것도 쓰지 않는다</li>
 *   <li>{@code getChangeJob} — 낸 수정안의 상태·가드 결과·축약 Diff·검토 화면 주소</li>
 * </ul>
 * 임베딩·재색인·승인은 Tool 이 아니다 — 관리자가 {@code /admin} 화면에서 승인하면 기존 적용 단계가 한다. 채팅의 Tool 선택지에는 들어가지 않는다
 * ({@code ToolPlanService.PLANNABLE}).
 */
@Component
public class ChunkEditTool {

    static final String VERSION = "latest";
    static final int MAX_DIFF_CHARS = 1_500;

    public record ChunkHit(String chunkId, String docId, String file, String heading, String snippet, String contentHash) {}

    /**
     * @param truncated 더 있었지만 {@code limit} 에서 잘랐다(전수 조회라면 일부만 고치면 문서가 일관되지 않을 수 있다)
     * @param tooShort  대상이 3글자 미만이라 전수 조회를 못 했다 — "없다"는 뜻이 아니다. {@code mode=search} 로 다시 찾는다
     */
    public record FindOutput(String mode, List<ChunkHit> chunks, boolean truncated, boolean tooShort) {}

    /** {@code proposeChunkEdit} 의 편집 작업 하나 — {@link AnchoredEdit.Operation} 의 Tool 쪽 모양. */
    public record EditOp(
            @ToolParam(description = "replace | delete | deleteSection | insertAfter") String op,
            @ToolParam(description = "Exact text copied from the chunk. replace: the whole span to replace. delete: start of the span. "
                    + "deleteSection: the heading line. insertAfter: the sentence to insert after") String anchor,
            @ToolParam(required = false, description = "delete only: end of the span (copied exactly); omit to delete just the anchor") String endAnchor,
            @ToolParam(required = false, description = "replace: the new text. insertAfter: the block to insert (at most 500 chars)") String text) {}

    private final ChangeProposalService proposals;
    private final KeywordSearchRepository keywords;
    private final KnowledgeSearchTool knowledge;
    private final AdminService adminService;
    private final ToolGuard guard;

    public ChunkEditTool(ChangeProposalService proposals, KeywordSearchRepository keywords, KnowledgeSearchTool knowledge,
                         AdminService adminService, ToolGuard guard) {
        this.proposals = proposals;
        this.keywords = keywords;
        this.knowledge = knowledge;
        this.adminService = adminService;
        this.guard = guard;
    }

    @Tool(name = "findChunks",
            description = "Find the chunks to edit (administrators only). mode=mentions lists every chunk that contains the subject "
                    + "literally (exhaustive, needs 3+ characters); mode=search ranks chunks by relevance. Each result carries the "
                    + "contentHash to pass to proposeChunkEdit. Read the full text with getChunkContext before proposing.")
    public FindOutput findChunks(
            @ToolParam(description = "Name, identifier or phrase to look for (required)") String subject,
            @ToolParam(required = false, description = "mentions (default) or search") String mode,
            @ToolParam(required = false, description = "Maximum chunks (default 20, at most 50)") Integer limit,
            ToolContext context) {
        guard.require(ToolCaller.from(context), ToolAccess.ADMIN, "findChunks");
        String s = ToolResultFormatter.requireText(subject, "subject");
        int max = ToolResultFormatter.clamp(limit, 1, ToolResultFormatter.MAX_ROWS, 20);
        String m = mode == null || mode.isBlank() ? "mentions" : mode.strip().toLowerCase(Locale.ROOT);
        String collection = adminService.collectionFor(VERSION);
        if ("search".equals(m)) {
            KnowledgeSearchTool.SearchOutput found = knowledge.searchKnowledge(s, null, null, max, context);
            Set<String> ids = new LinkedHashSet<>();
            for (KnowledgeSearchTool.Hit h : found.hits()) ids.add(h.chunkId());
            return new FindOutput("search", hits(collection, ids, s, max), false, false);
        }
        if (!"mentions".equals(m)) throw new IllegalArgumentException("mode 는 mentions 또는 search 입니다");
        KeywordSearchRepository.Mentions mentions = keywords.findMentions(VERSION, s, max);
        Set<String> ids = new LinkedHashSet<>();
        for (Document d : mentions.hits()) ids.add(d.getId());
        return new FindOutput("mentions", hits(collection, ids, s, max), mentions.truncated(), mentions.tooShort());
    }

    @Tool(name = "proposeChunkEdit",
            description = "Propose an edit to one chunk (administrators only). NOTHING is written: the proposal waits in the review "
                    + "queue until an administrator approves it on the review page; embedding and indexing happen only then. Give exactly "
                    + "one of: ops (preferred - edits anchored on text copied exactly from the chunk), newText (the whole new chunk text), "
                    + "or deleteWholeChunk=true. Copy anchors from getChunkContext, never paraphrase them; each anchor must occur exactly "
                    + "once. Keep [이미지: ...] / [페이지: N] markers and code fences intact. If accepted=false, read violations, fix and "
                    + "propose again.")
    public ChangeProposalService.ProposeResult proposeChunkEdit(
            @ToolParam(description = "chunkId of the chunk to edit (required)") String chunkId,
            @ToolParam(description = "contentHash of the chunk as read by findChunks or getChunkContext (required)") String baseHash,
            @ToolParam(description = "What is changed and why, in one or two sentences (required; shown to the reviewer)") String rationale,
            @ToolParam(required = false, description = "Anchored edits, at most 5") List<EditOp> ops,
            @ToolParam(required = false, description = "The whole new text of the chunk, instead of ops") String newText,
            @ToolParam(required = false, description = "True to delete the whole chunk, instead of ops") Boolean deleteWholeChunk,
            @ToolParam(required = false, description = "Job number returned by an earlier proposeChunkEdit, to group proposals; "
                    + "omit to start a new job") Long jobId,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.ADMIN, "proposeChunkEdit");
        List<AnchoredEdit.Operation> operations = new ArrayList<>();
        if (ops != null) {
            for (EditOp o : ops) operations.add(toOperation(o));
        }
        return proposals.proposeExternal(caller.userId(), chunkId, baseHash, operations, newText,
                Boolean.TRUE.equals(deleteWholeChunk), rationale, jobId);
    }

    @Tool(name = "getChangeJob",
            description = "Read a maintenance job (administrators only): its proposals with status, a shortened diff and the review "
                    + "page to hand to a person. Status 'pending' waits for approval; 'applied', 'stale' (the chunk changed meanwhile), "
                    + "'failed', 'rejected' and 'withdrawn' are final.")
    public ChangeProposalService.JobSummary getChangeJob(
            @ToolParam(description = "Job number (required)") Long jobId,
            ToolContext context) {
        guard.require(ToolCaller.from(context), ToolAccess.ADMIN, "getChangeJob");
        if (jobId == null) throw new IllegalArgumentException("jobId 이(가) 필요합니다");
        return proposals.jobSummary(jobId, ToolResultFormatter.MAX_ROWS, MAX_DIFF_CHARS)
                .orElseThrow(() -> new IllegalArgumentException("유지보수 작업을 찾을 수 없습니다: " + jobId));
    }

    static AnchoredEdit.Operation toOperation(EditOp o) {
        if (o == null || o.op() == null) throw new IllegalArgumentException("op 이(가) 필요합니다");
        AnchoredEdit.Op kind = switch (o.op().strip().toLowerCase(Locale.ROOT)) {
            case "replace" -> AnchoredEdit.Op.REPLACE;
            case "delete" -> AnchoredEdit.Op.DELETE;
            case "deletesection", "delete_section" -> AnchoredEdit.Op.DELETE_SECTION;
            case "insertafter", "insert_after", "insert" -> AnchoredEdit.Op.INSERT_AFTER;
            default -> throw new IllegalArgumentException("op 는 replace · delete · deleteSection · insertAfter 중 하나입니다: " + o.op());
        };
        return new AnchoredEdit.Operation(kind, o.anchor(), o.endAnchor(), o.text());
    }

    private List<ChunkHit> hits(String collection, Set<String> ids, String subject, int max) {
        List<ChunkHit> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= max) break;
            AdminService.ChunkRow row = adminService.getChunk(collection, id);
            if (row == null || !DocumentLookupTool.readable(row)) continue;
            out.add(new ChunkHit(row.id(), row.docId(), row.filename(), row.metadata().get(MetaKey.HEADING),
                    snippetAround(row.fullText(), subject), DocumentLookupTool.hash(row)));
        }
        return List.copyOf(out);
    }

    /** 대상이 처음 나오는 자리 앞뒤로 자른 발췌 — 못 찾으면 앞부분. */
    static String snippetAround(String text, String subject) {
        if (text == null) return "";
        int at = text.toLowerCase(Locale.ROOT).indexOf(subject.toLowerCase(Locale.ROOT));
        if (at < 0) return ToolResultFormatter.snippet(text);
        int from = Math.max(0, at - 100);
        return (from > 0 ? "…" : "") + ToolResultFormatter.snippet(text.substring(from));
    }
}
