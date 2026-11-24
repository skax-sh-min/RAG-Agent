package com.example.ragagent.workflow;

import com.example.ragagent.repository.ChangeJobRepository;
import com.example.ragagent.repository.ChangeProposalRepository;
import com.example.ragagent.service.ChunkDiff;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 수정안 검토 화면(6e, AGENT_MCP_DESIGN §10.15 R10)의 <b>일괄 적용 규칙</b> — 순수 클래스. 검토 화면의 [일괄 적용] 과 작업 화면의 문서 단위 승인이
 * 같은 판정을 쓴다(버튼을 숨기는 것과 별개로 엔드포인트가 이것으로 막는다).
 *
 * <ul>
 *   <li><b>요청 단위</b>({@link #batchAllowed}): 자연어 수정 작업({@code intent_json.mode=nl})이고 유형이 {@code deprecate}·{@code add_note} 일 때만 —
 *       원문을 지우거나 바꾸지 않고 안내만 넣는 요청이다. 글자 바꾸기·{@code remove}·{@code revise}·외부 제안은 모두 하나씩 확인한다.</li>
 *   <li><b>수정안 단위</b>({@link #insertOnly}): 그 작업 안에서도 코드가 확인한 "넣기만" 수정안만 — {@code kind=edit} 이고 원문 줄이 하나도 사라지지
 *       않았다(줄만 더해졌다). 같은 {@code deprecate} 작업 안에도 선택 문장 고치기나 관리자 편집으로 원문을 바꾼 수정안이 섞일 수 있어서 유형 이름만
 *       믿지 않는다.</li>
 * </ul>
 */
public final class ReviewRules {

    /** 일괄 적용이 열리는 자연어 수정 유형 — 원문을 남기고 안내만 넣는다. */
    static final Set<String> BATCH_TYPES = Set.of(EditIntent.DEPRECATE, EditIntent.ADD_NOTE);

    private ReviewRules() {}

    /** 이 작업이 일괄 적용(검토 화면의 [일괄 적용]·작업 화면의 문서 단위 승인)을 받는가. */
    public static boolean batchAllowed(ChangeJobRepository.Job job) {
        if (job == null || job.intentJson() == null || job.intentJson().isBlank()) return false;
        try {
            JsonNode j = com.example.ragagent.llm.LenientJson.MAPPER.readTree(job.intentJson());
            return KnowledgeEditWorkflow.MODE_NL.equals(j.path("mode").asString(""))
                    && BATCH_TYPES.contains(j.path("type").asString(""));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 원문 줄을 지우거나 바꾸지 않고 줄만 더한 수정안인가 — 일괄 적용의 수정안 단위 조건. */
    public static boolean insertOnly(ChangeProposalRepository.Proposal p) {
        if (!ChangeProposalRepository.KIND_EDIT.equals(p.kind())) return false;
        ChunkDiff.Result d = ChunkDiff.compare(p.beforeText(), p.afterText());
        return d != null && !d.tooLarge() && !d.identical() && d.removedLines() == 0 && d.addedLines() > 0;
    }

    /** 작업 화면·검토 화면에 보일 요청 유형 — 자연어 수정이면 그 유형({@code deprecate} 등), 아니면 {@code null}. */
    public static String nlType(ChangeJobRepository.Job job) {
        if (job == null || job.intentJson() == null || job.intentJson().isBlank()) return null;
        try {
            JsonNode j = com.example.ragagent.llm.LenientJson.MAPPER.readTree(job.intentJson());
            if (!KnowledgeEditWorkflow.MODE_NL.equals(j.path("mode").asString(""))) return null;
            String t = j.path("type").asString("");
            return t.isBlank() ? null : t;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
