package com.example.ragagent.tool;

import com.example.ragagent.tool.catalog.CatalogQueryTool;
import com.example.ragagent.tool.change.DiffTool;
import com.example.ragagent.tool.knowledge.DocumentLookupTool;
import com.example.ragagent.tool.knowledge.KnowledgeSearchTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 전송 중립 Tool 목록 (AGENT_MCP_DESIGN §3.1 · §6.2). {@code @Tool} 메서드를 한 번만 정의하고, 내부 호출(채팅)과 외부 공개(MCP, Phase 2)가
 * 같은 {@link ToolCallback} 을 쓴다.
 *
 * <p><b>호출자에 따라 목록을 거른다.</b> {@link #forCaller} 는 권한 없는 Tool 을 스키마째 주지 않는다 — 모델이 쓸 수 없는 도구를 보고 시도하다
 * 헛돌지 않게 하는 편의다. 막는 것은 각 Tool 메서드 첫 줄의 {@link ToolGuard} 이고, {@link #call} 도 이름이 목록에 없거나 권한이 없으면
 * 실행 전에 거절한다.
 *
 * <p><b>접근 등급은 여기서 선언한다</b>({@code ACCESS}). {@code @Tool} 에는 등급을 실을 자리가 없고, 선언이 없는 Tool 이 조용히 공개되면
 * 안 되므로 — 생성할 때 선언 없는 Tool 이 있으면 <b>기동이 실패한다</b>(권한이 모자란 쪽으로 실패).
 */
@Component
public class ToolCatalog {

    /** Tool 이름 → 접근 등급. 이 표에 없는 이름의 Tool 은 등록되지 않고 기동이 실패한다. */
    static final Map<String, ToolAccess> ACCESS = Map.of(
            "searchKnowledge", ToolAccess.PUBLIC,
            "findRelatedDocuments", ToolAccess.PUBLIC,
            "queryCatalog", ToolAccess.PUBLIC,     // 조회마다 관리자 전용이 있다 — CatalogQueryTool 이 한 번 더 검사한다
            "getChunkContext", ToolAccess.PUBLIC,
            "generateDiff", ToolAccess.PUBLIC,
            "getDocument", ToolAccess.ADMIN);

    private record Entry(ToolCallback callback, ToolAccess access) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public ToolCatalog(KnowledgeSearchTool knowledge, DocumentLookupTool lookup, CatalogQueryTool catalog, DiffTool diff) {
        this(List.of(knowledge, lookup, catalog, diff));
    }

    /** 임의의 {@code @Tool} 빈들로 만든다 — 테스트용. */
    ToolCatalog(List<Object> toolBeans) {
        for (Object bean : toolBeans) {
            for (ToolCallback cb : ToolCallbacks.from(bean)) {
                String name = cb.getToolDefinition().name();
                ToolAccess access = ACCESS.get(name);
                if (access == null) {
                    throw new IllegalStateException("접근 등급이 선언되지 않은 Tool 입니다: " + name
                            + " — ToolCatalog.ACCESS 에 등급을 더하세요");
                }
                if (entries.put(name, new Entry(cb, access)) != null) {
                    throw new IllegalStateException("Tool 이름이 겹칩니다: " + name);
                }
            }
        }
    }

    /** 이 호출자에게 보이는 Tool(스키마 포함). 순서는 등록 순서다. */
    public List<ToolCallback> forCaller(ToolCaller caller) {
        return entries.values().stream()
                .filter(e -> ToolGuard.allows(caller, e.access()))
                .map(Entry::callback)
                .toList();
    }

    /** 이 호출자가 쓸 수 있는 Tool 하나 — 모르는 이름이거나 권한이 없으면 비어 있다(존재 여부를 구별해 알려주지 않는다). */
    public Optional<ToolCallback> find(String name, ToolCaller caller) {
        Entry e = name == null ? null : entries.get(name);
        if (e == null || !ToolGuard.allows(caller, e.access())) return Optional.empty();
        return Optional.of(e.callback());
    }

    /** 등록된 모든 Tool 의 이름과 등급 — 규약 테스트와 운영 점검용. */
    public Map<String, ToolAccess> accessTable() {
        Map<String, ToolAccess> out = new LinkedHashMap<>();
        entries.forEach((k, v) -> out.put(k, v.access()));
        return Map.copyOf(out);
    }

    /**
     * 이름으로 Tool 을 실행한다. 호출자는 인자가 아니라 {@link ToolContext} 로 들어가므로 모델이 만든 JSON 으로는 바꿀 수 없다.
     *
     * @throws ToolDeniedException      목록에 없는 이름이거나 권한이 없을 때(둘을 구별하지 않는다)
     * @throws IllegalArgumentException 인자가 잘못됐을 때(Tool 이 던진 것)
     */
    public String call(String name, String argumentsJson, ToolCaller caller) {
        ToolCallback cb = find(name, caller).orElseThrow(() -> new ToolDeniedException(String.valueOf(name)));
        return cb.call(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson, caller.toContext());
    }
}
