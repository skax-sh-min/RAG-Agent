package com.example.ragagent.tool.catalog;

import com.example.ragagent.service.CatalogQueryService;
import com.example.ragagent.tool.ToolAccess;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolDeniedException;
import com.example.ragagent.tool.ToolGuard;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * {@code queryCatalog} — 앱 내부 메타데이터의 <b>이름 있는 조회</b> (AGENT_MCP_DESIGN §3.3). 자유 SQL 은 받지 않는다.
 *
 * <p>Tool 자체는 {@link ToolAccess#PUBLIC} 이지만 조회마다 권한이 다르다 — {@code reports.open}·{@code usage.summary} 는 관리자만이다.
 * 그래서 권한 검사가 두 번이다: 먼저 Tool 로(감사 기록), 그다음 조회 이름의 관리자 여부로. 일반 호출자가 관리자 조회를 이름으로 불러도
 * {@link ToolDeniedException} 이고, 조회 서비스는 호출되지 않는다.
 */
@Component
public class CatalogQueryTool {

    private final CatalogQueryService service;
    private final ToolGuard guard;

    public CatalogQueryTool(CatalogQueryService service, ToolGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @Tool(name = "queryCatalog",
            description = "Look up app metadata by a fixed query name. Names: documents.list (param: optional tag filter), "
                    + "documents.recent (param: optional count), tags.list. Administrators also have reports.open and usage.summary. "
                    + "Returns at most 50 rows.")
    public CatalogQueryService.Result queryCatalog(
            @ToolParam(description = "Query name, exactly one of the names listed (required)") String name,
            @ToolParam(required = false, description = "Optional argument for the query") String param,
            ToolContext context) {
        ToolCaller caller = ToolCaller.from(context);
        guard.require(caller, ToolAccess.PUBLIC, "queryCatalog");
        CatalogQueryService.Query query = CatalogQueryService.Query.parse(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "알 수 없는 조회 이름입니다. 사용할 수 있는 이름: " + String.join(", ", java.util.Arrays.stream(CatalogQueryService.Query.values())
                                .filter(q -> !q.adminOnly() || caller.admin()).map(CatalogQueryService.Query::wireName).toList())));
        if (query.adminOnly()) {
            guard.require(caller, ToolAccess.ADMIN, "queryCatalog:" + query.wireName());
        }
        return service.query(query, param);
    }
}
