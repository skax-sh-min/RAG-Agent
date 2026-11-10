package com.example.ragagent.tool;

import com.example.ragagent.agent.AgentState;
import com.example.ragagent.model.MetaKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * {@code TOOL} 노드의 실체(§6.33 3단계) — 모델이 고른 검색 아닌 Tool 을 <b>그래프 안에서, 게이트 퍼밋 밖으로</b> 실행하고 그 결과를 답변 노드가
 * 읽을 근거 문서로 바꾼다. 답변·검증·사다리는 건드리지 않는다 — 결과는 평소 검색 결과와 같은 자리({@code retrievedDocs})에 들어간다.
 *
 * <p><b>호출자는 서버가 정한다</b> — 요청 사용자의 id, 관리자 아님, 채널 {@code chat}. 채팅의 선택지는 처음부터 이 호출자에게 보이는 공개
 * Tool 뿐이고({@code ToolPlanService}), 그래도 모델이 만든 인자·이름이 권한을 넓히지 못하도록 실행은 {@link ToolCatalog#call} 의 권한 검사를
 * 다시 지난다(거부는 {@code tool.denied} 로 감사 기록된다).
 *
 * <p><b>실패는 턴을 실패시키지 않는다</b> — 어떤 예외든 계획을 지워 돌려주고(그래프가 {@code RETRIEVAL} 로 간다) 사용자 중단만 전파한다.
 *
 * <p><b>출처({@code sources})는 비운다.</b> 카탈로그 응답은 시간이 지나면 낡는데 재사용 출처({@code turn_source_ref})가 만들어지면 그 답이 재사용 후보가
 * 된다(설계서 §5.1). 근거 문서의 파일명은 Tool 이름이다.
 */
@Service
public class ToolDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatchService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 분석을 시작할 뿐 근거 문서를 만들지 않는 Tool — 결과를 문서로 읽어 답하게 하지 않고 정형 안내를 답으로 남긴다(설계서 §5.3). */
    static final String IMPACT_TOOL = "analyzeImpact";

    private final ToolCatalog catalog;
    private final MessageSource messages;

    @Autowired
    public ToolDispatchService(ToolCatalog catalog, MessageSource messages) {
        this.catalog = catalog;
        this.messages = messages;
    }

    /** 메시지 번들 없이 — 테스트용. 정형 안내는 한국어 기본 문구다. */
    public ToolDispatchService(ToolCatalog catalog) {
        this(catalog, null);
    }

    /**
     * @return 성공이면 {@code retrievedDocs} 에 Tool 결과 문서 하나가 실리고 {@code sources} 는 빈 채 {@link AgentState#toolPlan()} 이 남는다. 실패면
     *         {@code toolPlan} 이 {@code null} 이고 나머지는 그대로다 — 호출자(그래프)는 그것으로 {@code RETRIEVAL} 로 갈라진다
     */
    public AgentState execute(AgentState state) {
        ToolPlan plan = state.toolPlan();
        if (plan == null) return state;
        ToolCaller caller = new ToolCaller(state.userId(), false, "chat");
        try {
            String result = catalog.call(plan.toolName(), plan.argumentsJson(), caller);
            if (result == null || result.isBlank()) {
                log.info("[TOOL] 빈 결과 — 검색으로 진행한다 tool={} thread={}", plan.toolName(), state.threadId());
                return state.toBuilder().toolPlan(null).build();
            }
            if (IMPACT_TOOL.equals(plan.toolName())) {
                return startedAnswer(state, result);
            }
            Document doc = new Document("tool-" + plan.toolName(), result, Map.of(
                    MetaKey.FILENAME, "Tool: " + plan.toolName(),
                    MetaKey.PAGE_OR_SLIDE, "-"));
            log.info("[TOOL] 실행 tool={} thread={} resultChars={}", plan.toolName(), state.threadId(), result.length());
            return state.toBuilder().retrievedDocs(List.of(doc)).sources(List.of()).build();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);   // 사용자 중단은 그대로 전파한다
            }
            log.warn("[TOOL] Tool 실행 실패 — 검색으로 진행한다 tool={} thread={}: {}", plan.toolName(), state.threadId(), e.getMessage());
            return state.toBuilder().toolPlan(null).build();
        }
    }

    /**
     * 영향도 분석을 시작한 턴의 답 — 정형 문구와 작업 링크. 검색 0건 경로와 같은 모양으로 남긴다: 응답 모드는 {@code S}(검증 없음 · 좋아요 제안
     * 불가), 근거 문서·출처는 없고 판정도 없다. 그래프는 {@code ANSWER} 를 건너뛰고 {@code FINALIZE} 로 간다.
     */
    private AgentState startedAnswer(AgentState state, String toolResult) {
        JsonNode r = MAPPER.readTree(toolResult);
        boolean busy = "busy".equals(r.path("status").asString(""));
        String answer = busy
                ? text("chat.answer.impact-busy", "이미 진행 중인 영향도 분석이 있습니다. 끝난 뒤 다시 요청해 주세요.", state)
                : text("chat.answer.impact-started", "영향도 분석을 시작했습니다 (작업 #{0}). 끝나면 아래 링크에서 리포트를 볼 수 있습니다: /ui/impact-jobs/{0}", state,
                        String.valueOf(r.path("jobId").asLong(0)));
        log.info("[TOOL] 영향도 분석 {} thread={}", busy ? "중복 요청" : "시작", state.threadId());
        return state.toBuilder()
                .answer(answer)
                .responseMode(com.example.ragagent.model.ResponseMode.S)
                .retrievedDocs(List.of()).sources(List.of())
                .needsRetry(false).grounded(null).evalReason(null).envNote(null)
                .build();
    }

    private String text(String key, String fallback, AgentState state, Object... args) {
        if (messages == null) return java.text.MessageFormat.format(fallback, args);
        return messages.getMessage(key, args, fallback, state.locale());
    }
}
